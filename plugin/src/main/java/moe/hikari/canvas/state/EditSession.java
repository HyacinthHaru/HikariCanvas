package moe.hikari.canvas.state;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 权威编辑会话：WS 上行 op → {@link ProjectState} mutation → 产出 {@link StatePatch}。
 * 契约见 {@code docs/protocol.md §5.3 / §5.4}。
 *
 * <p>1 个 EditSession ↔ 1 个 {@link moe.hikari.canvas.session.Session}；随 session 生灭。</p>
 *
 * <p><b>并发：</b> Javalin WS handler 跑在 Jetty 线程池，同一连接也可能出现 op pipeline。
 * 所有 {@code apply*} 方法 {@code synchronized(this)} 保证 {@link ProjectState} 单线程变更。</p>
 *
 * <p><b>线程：</b> M3-T6 阶段所有 op 均为纯数据变更（不碰 Bukkit API / MapPool / world）。
 * T7 脏矩形推送会把渲染部分迁到主线程。</p>
 *
 * <p><b>验证层级：</b> 这一层做字段格式与范围 sanity 校验（color / rotation / text len / 数值区间）；
 * 业务不变式（跨 session 排他、池容量等）由 SessionManager / MapPool 负责。</p>
 */
public final class EditSession {

    // ---------- 校验常量 ----------
    private static final Pattern COLOR_RE = Pattern.compile("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$");
    private static final int MAX_TEXT_LEN = 256;
    private static final int MAX_COORD = 10_000;
    private static final int MAX_DIM = 10_000;
    private static final int MAX_FONT_SIZE = 512;
    private static final int MAX_STROKE_WIDTH = 128;

    private final ProjectState state;

    public EditSession(ProjectState state) {
        this.state = state;
    }

    public ProjectState state() {
        return state;
    }

    // ---------- 结果类型 ----------

    public sealed interface OpResult {
        record Ok(StatePatch patch) implements OpResult {}
        record Error(String code, String message) implements OpResult {}
    }

    // ---------- element.add ----------

    /**
     * 新增元素。{@code afterId} 为 null 时追加到末尾；否则插入到该元素之后。
     * 若 {@code afterId} 指向不存在的元素，返回 {@code INVALID_ELEMENT}。
     */
    public synchronized OpResult addElement(String type, Map<String, Object> props, String afterId) {
        if (type == null) return err("INVALID_PAYLOAD", "element type missing");
        if (props == null) props = Map.of();

        int insertIdx;
        if (afterId == null || afterId.isEmpty()) {
            insertIdx = state.elements().size();
        } else {
            int afterIdx = state.indexOfElement(afterId);
            if (afterIdx < 0) return err("INVALID_ELEMENT", "after element not found: " + afterId);
            insertIdx = afterIdx + 1;
        }

        String id = "e-" + UUID.randomUUID();
        Element element;
        try {
            element = switch (type) {
                case "text" -> buildText(id, props);
                case "rect" -> buildRect(id, props);
                default -> throw new ValidationException("INVALID_ELEMENT", "unknown element type: " + type);
            };
        } catch (ValidationException ve) {
            return err(ve.code, ve.getMessage());
        }

        state.addElement(insertIdx, element);
        long v = state.bumpVersion();

        StatePatch patch = new StatePatchBuilder()
                .add("/elements/" + insertIdx, element)
                .build(v);
        return new OpResult.Ok(patch);
    }

    // ---------- element.update ----------

    /**
     * 字段级部分更新。{@code patch} 的每个 key 代表要修改的字段名，value 为新值。
     * 逐字段校验；失败时**不变更** state（all-or-nothing 语义）。
     *
     * <p>M3 元素字段覆盖：</p>
     * <ul>
     *   <li>共通：{@code x / y / w / h / rotation / locked / visible}</li>
     *   <li>Text：{@code text / fontId / fontSize / color / align}</li>
     *   <li>Rect：{@code fill / stroke}（{@code stroke} 值为 {@code { "width":int, "color":string }} 或 null）</li>
     * </ul>
     */
    public synchronized OpResult updateElement(String elementId, Map<String, Object> patch) {
        if (elementId == null) return err("INVALID_PAYLOAD", "elementId missing");
        if (patch == null || patch.isEmpty()) return err("INVALID_PAYLOAD", "empty patch");
        int idx = state.indexOfElement(elementId);
        if (idx < 0) return err("INVALID_ELEMENT", "element not found: " + elementId);
        Element existing = state.elements().get(idx);

        Element updated;
        try {
            updated = switch (existing) {
                case TextElement t -> applyTextPatch(t, patch);
                case RectElement r -> applyRectPatch(r, patch);
            };
        } catch (ValidationException ve) {
            return err(ve.code, ve.getMessage());
        }

        state.replaceElementAt(idx, updated);
        long v = state.bumpVersion();

        StatePatchBuilder b = new StatePatchBuilder();
        for (var e : patch.entrySet()) {
            String path = "/elements/" + idx + "/" + e.getKey();
            Object value = e.getValue();
            // null 值用 remove：RectElement.fill / stroke 可清空；
            // 若保留 replace+null，{@code NON_NULL} 序列化会丢 value 字段，违反 RFC 6902
            if (value == null) {
                b.remove(path);
            } else {
                b.replace(path, value);
            }
        }
        return new OpResult.Ok(b.build(v));
    }

    // ---------- element.delete ----------

    public synchronized OpResult deleteElement(String elementId) {
        if (elementId == null) return err("INVALID_PAYLOAD", "elementId missing");
        int idx = state.indexOfElement(elementId);
        if (idx < 0) return err("INVALID_ELEMENT", "element not found: " + elementId);
        state.removeElementAt(idx);
        long v = state.bumpVersion();
        return new OpResult.Ok(new StatePatchBuilder().remove("/elements/" + idx).build(v));
    }

    // ---------- element.reorder ----------

    /**
     * 把元素移动到 z-order 的 {@code newIndex} 位置（0 = 底层）。
     * 超出范围时 clamp 到 {@code [0, size-1]}。
     */
    public synchronized OpResult reorderElement(String elementId, int newIndex) {
        if (elementId == null) return err("INVALID_PAYLOAD", "elementId missing");
        int from = state.indexOfElement(elementId);
        if (from < 0) return err("INVALID_ELEMENT", "element not found: " + elementId);
        int size = state.elements().size();
        int to = Math.max(0, Math.min(newIndex, size - 1));
        if (to == from) {
            // 无实际变化；仍 bump version 保持简单（也可以返回空 ops）
            long v = state.bumpVersion();
            return new OpResult.Ok(new StatePatch(v, List.of()));
        }
        Element moved = state.elements().get(from);
        state.moveElement(from, to);
        long v = state.bumpVersion();
        StatePatch patch = new StatePatchBuilder()
                .remove("/elements/" + from)
                .add("/elements/" + to, moved)
                .build(v);
        return new OpResult.Ok(patch);
    }

    // ---------- element.transform ----------

    /**
     * 几何变换特化 op：是 {@link #updateElement} 在 {@code {x,y,w,h,rotation}} 五字段上的等价调用。
     * 任一字段为 {@code null} = 不修改。
     */
    public synchronized OpResult transformElement(String elementId,
                                                  Integer x, Integer y,
                                                  Integer w, Integer h,
                                                  Integer rotation) {
        if (x == null && y == null && w == null && h == null && rotation == null) {
            return err("INVALID_PAYLOAD", "transform has no fields");
        }
        java.util.Map<String, Object> patch = new java.util.LinkedHashMap<>();
        if (x != null) patch.put("x", x);
        if (y != null) patch.put("y", y);
        if (w != null) patch.put("w", w);
        if (h != null) patch.put("h", h);
        if (rotation != null) patch.put("rotation", rotation);
        return updateElement(elementId, patch);
    }

    // ---------- canvas.resize ----------

    /**
     * M3 只接受尺寸等于当前值的 no-op resize（保持 op channel 通畅 + 前端试探能通过）。
     * 真正的动态扩缩容涉及 MapPool 借还和物品框增删，留给 M7。
     */
    public synchronized OpResult resizeCanvas(int widthMaps, int heightMaps) {
        ProjectState.Canvas c = state.canvas();
        if (widthMaps != c.widthMaps() || heightMaps != c.heightMaps()) {
            return err("POOL_EXHAUSTED",
                    "canvas resize to " + widthMaps + "x" + heightMaps
                    + " not supported (wall fixed at " + c.widthMaps() + "x" + c.heightMaps() + ")");
        }
        long v = state.bumpVersion();
        return new OpResult.Ok(new StatePatch(v, List.of()));
    }

    // ---------- canvas.background ----------

    public synchronized OpResult setBackground(String color) {
        if (!isValidColor(color)) return err("INVALID_PAYLOAD", "invalid color: " + color);
        ProjectState.Canvas c = state.canvas();
        state.canvas(new ProjectState.Canvas(c.widthMaps(), c.heightMaps(), color));
        long v = state.bumpVersion();
        return new OpResult.Ok(
                new StatePatchBuilder().replace("/canvas/background", color).build(v));
    }

    // ---------- 构造与更新辅助 ----------

    private Element buildText(String id, Map<String, Object> p) {
        String text = requireString(p, "text", true);
        validateText(text);
        int x = intFieldOrDefault(p, "x", 0); validateCoord(x, "x");
        int y = intFieldOrDefault(p, "y", 0); validateCoord(y, "y");
        int w = intFieldOrDefault(p, "w", 128); validateDim(w, "w");
        int h = intFieldOrDefault(p, "h", 32); validateDim(h, "h");
        int rotation = intFieldOrDefault(p, "rotation", 0); validateRotation(rotation);
        boolean locked = boolFieldOrDefault(p, "locked", false);
        boolean visible = boolFieldOrDefault(p, "visible", true);
        String fontId = stringFieldOrDefault(p, "fontId", "bitmap");
        int fontSize = intFieldOrDefault(p, "fontSize", 8); validateFontSize(fontSize);
        String color = stringFieldOrDefault(p, "color", "#000000"); validateColor(color);
        String align = stringFieldOrDefault(p, "align", "left"); validateAlign(align);
        return new TextElement(id, x, y, w, h, rotation, locked, visible,
                text, fontId, fontSize, color, align);
    }

    private Element buildRect(String id, Map<String, Object> p) {
        int x = intFieldOrDefault(p, "x", 0); validateCoord(x, "x");
        int y = intFieldOrDefault(p, "y", 0); validateCoord(y, "y");
        int w = intFieldOrDefault(p, "w", 64); validateDim(w, "w");
        int h = intFieldOrDefault(p, "h", 64); validateDim(h, "h");
        int rotation = intFieldOrDefault(p, "rotation", 0); validateRotation(rotation);
        boolean locked = boolFieldOrDefault(p, "locked", false);
        boolean visible = boolFieldOrDefault(p, "visible", true);
        String fill = nullableString(p, "fill");
        if (fill != null) validateColor(fill);
        Stroke stroke = buildStroke(p.get("stroke"));
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "rect needs fill or non-zero stroke");
        }
        return new RectElement(id, x, y, w, h, rotation, locked, visible, fill, stroke);
    }

    private Stroke buildStroke(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) {
            throw new ValidationException("INVALID_PAYLOAD", "stroke must be object");
        }
        int width = ((Number) requireNumber(m, "width")).intValue();
        if (width < 0 || width > MAX_STROKE_WIDTH) {
            throw new ValidationException("INVALID_PAYLOAD", "stroke.width out of range");
        }
        Object c = m.get("color");
        if (!(c instanceof String color)) {
            throw new ValidationException("INVALID_PAYLOAD", "stroke.color must be string");
        }
        validateColor(color);
        return new Stroke(width, color);
    }

    private TextElement applyTextPatch(TextElement t, Map<String, Object> patch) {
        String text = t.text();
        int x = t.x(); int y = t.y(); int w = t.w(); int h = t.h();
        int rotation = t.rotation();
        boolean locked = t.locked(); boolean visible = t.visible();
        String fontId = t.fontId(); int fontSize = t.fontSize();
        String color = t.color(); String align = t.align();

        for (var e : patch.entrySet()) {
            String k = e.getKey(); Object v = e.getValue();
            switch (k) {
                case "text" -> { text = requireStringValue(v, k); validateText(text); }
                case "x" -> { x = intValue(v, k); validateCoord(x, k); }
                case "y" -> { y = intValue(v, k); validateCoord(y, k); }
                case "w" -> { w = intValue(v, k); validateDim(w, k); }
                case "h" -> { h = intValue(v, k); validateDim(h, k); }
                case "rotation" -> { rotation = intValue(v, k); validateRotation(rotation); }
                case "locked" -> locked = boolValue(v, k);
                case "visible" -> visible = boolValue(v, k);
                case "fontId" -> fontId = requireStringValue(v, k);
                case "fontSize" -> { fontSize = intValue(v, k); validateFontSize(fontSize); }
                case "color" -> { color = requireStringValue(v, k); validateColor(color); }
                case "align" -> { align = requireStringValue(v, k); validateAlign(align); }
                default -> throw new ValidationException("INVALID_PAYLOAD",
                        "unknown text field: " + k);
            }
        }
        return new TextElement(t.id(), x, y, w, h, rotation, locked, visible,
                text, fontId, fontSize, color, align);
    }

    private RectElement applyRectPatch(RectElement r, Map<String, Object> patch) {
        int x = r.x(); int y = r.y(); int w = r.w(); int h = r.h();
        int rotation = r.rotation();
        boolean locked = r.locked(); boolean visible = r.visible();
        String fill = r.fill();
        Stroke stroke = r.stroke();

        for (var e : patch.entrySet()) {
            String k = e.getKey(); Object v = e.getValue();
            switch (k) {
                case "x" -> { x = intValue(v, k); validateCoord(x, k); }
                case "y" -> { y = intValue(v, k); validateCoord(y, k); }
                case "w" -> { w = intValue(v, k); validateDim(w, k); }
                case "h" -> { h = intValue(v, k); validateDim(h, k); }
                case "rotation" -> { rotation = intValue(v, k); validateRotation(rotation); }
                case "locked" -> locked = boolValue(v, k);
                case "visible" -> visible = boolValue(v, k);
                case "fill" -> {
                    if (v == null) fill = null;
                    else { fill = requireStringValue(v, k); validateColor(fill); }
                }
                case "stroke" -> stroke = buildStroke(v);
                default -> throw new ValidationException("INVALID_PAYLOAD",
                        "unknown rect field: " + k);
            }
        }
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "rect needs fill or non-zero stroke");
        }
        return new RectElement(r.id(), x, y, w, h, rotation, locked, visible, fill, stroke);
    }

    // ---------- 校验 helpers ----------

    private static boolean isValidColor(String s) {
        return s != null && COLOR_RE.matcher(s).matches();
    }

    private static void validateColor(String s) {
        if (!isValidColor(s)) throw new ValidationException("INVALID_PAYLOAD", "invalid color: " + s);
    }

    private static void validateRotation(int r) {
        if (r != 0 && r != 90 && r != 180 && r != 270) {
            throw new ValidationException("INVALID_PAYLOAD", "rotation must be 0/90/180/270: " + r);
        }
    }

    private static void validateText(String s) {
        if (s.length() > MAX_TEXT_LEN) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "text length " + s.length() + " exceeds " + MAX_TEXT_LEN);
        }
    }

    private static void validateCoord(int v, String name) {
        if (v < -MAX_COORD || v > MAX_COORD) {
            throw new ValidationException("INVALID_PAYLOAD", name + " out of range: " + v);
        }
    }

    private static void validateDim(int v, String name) {
        if (v <= 0 || v > MAX_DIM) {
            throw new ValidationException("INVALID_PAYLOAD", name + " must be 1.." + MAX_DIM + ": " + v);
        }
    }

    private static void validateFontSize(int v) {
        if (v < 1 || v > MAX_FONT_SIZE) {
            throw new ValidationException("INVALID_PAYLOAD", "fontSize out of range: " + v);
        }
    }

    private static void validateAlign(String v) {
        if (!"left".equals(v) && !"center".equals(v) && !"right".equals(v)) {
            throw new ValidationException("INVALID_PAYLOAD", "invalid align: " + v);
        }
    }

    // ---------- Map<String,Object> 读取 helpers ----------

    private static String requireString(Map<String, Object> m, String k, boolean required) {
        Object v = m.get(k);
        if (v == null) {
            if (required) throw new ValidationException("INVALID_PAYLOAD", k + " required");
            return null;
        }
        if (!(v instanceof String s)) {
            throw new ValidationException("INVALID_PAYLOAD", k + " must be string");
        }
        return s;
    }

    private static String stringFieldOrDefault(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (!(v instanceof String s)) {
            throw new ValidationException("INVALID_PAYLOAD", k + " must be string");
        }
        return s;
    }

    private static String nullableString(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (!(v instanceof String s)) {
            throw new ValidationException("INVALID_PAYLOAD", k + " must be string or null");
        }
        return s;
    }

    private static int intFieldOrDefault(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (!(v instanceof Number n)) {
            throw new ValidationException("INVALID_PAYLOAD", k + " must be number");
        }
        return n.intValue();
    }

    private static boolean boolFieldOrDefault(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (!(v instanceof Boolean b)) {
            throw new ValidationException("INVALID_PAYLOAD", k + " must be boolean");
        }
        return b;
    }

    private static Number requireNumber(Map<?, ?> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Number n)) {
            throw new ValidationException("INVALID_PAYLOAD", k + " must be number");
        }
        return n;
    }

    private static String requireStringValue(Object v, String key) {
        if (!(v instanceof String s)) {
            throw new ValidationException("INVALID_PAYLOAD", key + " must be string");
        }
        return s;
    }

    private static int intValue(Object v, String key) {
        if (!(v instanceof Number n)) {
            throw new ValidationException("INVALID_PAYLOAD", key + " must be number");
        }
        return n.intValue();
    }

    private static boolean boolValue(Object v, String key) {
        if (!(v instanceof Boolean b)) {
            throw new ValidationException("INVALID_PAYLOAD", key + " must be boolean");
        }
        return b;
    }

    private static OpResult.Error err(String code, String msg) {
        return new OpResult.Error(code, msg);
    }

    /** 内部用：validate 失败时抛，外层 catch 转 {@link OpResult.Error}。 */
    private static final class ValidationException extends RuntimeException {
        final String code;
        ValidationException(String code, String message) { super(message); this.code = code; }
    }
}
