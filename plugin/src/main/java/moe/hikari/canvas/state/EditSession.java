package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import moe.hikari.canvas.render.DirtyRegion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 权威编辑会话：WS 上行 op → {@link ProjectState} mutation → 产出 {@link StatePatch}。
 * 契约见 {@code docs/protocol.md §5.3 / §5.4}。
 *
 * <p>1 个 EditSession ↔ 1 个 {@code moe.hikari.canvas.session.Session}；随 session 生灭。</p>
 *
 * <p><b>M8-C 升级：</b> 所有 element / layer / canvas op 切换到 v2 path
 * （{@code /layers/{i}/elements/{j}/...}）。新增 layer.* op 族 + element.move-to-layer
 * + canvas.grid + canvas.guides.set。{@code locked} 层内 element op 全部拒
 * {@code LAYER_LOCKED}。</p>
 *
 * <p><b>并发：</b> Javalin WS handler 跑在 Jetty 线程池，同一连接也可能出现 op pipeline。
 * 所有 {@code apply*} 方法 {@code synchronized(this)} 保证 {@link ProjectState} 单线程变更。</p>
 *
 * <p><b>验证层级：</b> 这一层做字段格式与范围 sanity 校验（color / rotation / text len / 数值区间）；
 * 业务不变式（跨 session 排他、池容量等）由 SessionManager / MapPool 负责。</p>
 */
public final class EditSession {

    // ---------- 校验常量 ----------
    private static final Pattern COLOR_RE = Pattern.compile("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$");

    /** M11：用于把 patch / op payload 中的 fill object 转成 {@link Fill}（走 {@link FillDeserializer}）。 */
    private static final ObjectMapper FILL_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final int MAX_TEXT_LEN = 256;
    private static final int MAX_COORD = 10_000;
    private static final int MAX_DIM = 10_000;
    private static final int MAX_FONT_SIZE = 512;
    private static final int MAX_STROKE_WIDTH = 128;
    /** letterSpacing 容许范围（px）。负值表示压字距；极值保护渲染不死循环。 */
    private static final float MIN_LETTER_SPACING = -32f;
    private static final float MAX_LETTER_SPACING = 128f;
    /** lineHeight 倍数允许范围。&lt; 0.5 会让行重叠到不可读；&gt; 4 属不合理范围。 */
    private static final float MIN_LINE_HEIGHT = 0.5f;
    private static final float MAX_LINE_HEIGHT = 4.0f;
    /** shadow 偏移允许范围（px）。 */
    private static final int MAX_SHADOW_OFFSET = 128;
    /** glow 半径允许范围（px）。太大会让盒模糊性能劣化。 */
    private static final int MAX_GLOW_RADIUS = 64;
    /** 单工程层数上限（软限，超过 warn 但不拒；硬上限避免内存炸）。 */
    private static final int MAX_LAYERS = 64;
    /** 用户可见 layer 名最大长度。 */
    private static final int MAX_LAYER_NAME = 64;
    /** 单工程参考线数上限（防意外刷爆）。 */
    private static final int MAX_GUIDES = 256;

    /** T11 历史栈上限（每会话）；超过后踢掉最老的。 */
    private static final int MAX_HISTORY = 16;

    private final ProjectState state;

    /** 过去快照栈：每条记录一次成功 op 的 pre-mutation 状态；push=头、pop=头。 */
    private final Deque<ProjectSnapshot> past = new ArrayDeque<>();
    /** 未来快照栈：undo 时从 past 出的快照入此栈，redo 取用；每次新 edit 会清空。 */
    private final Deque<ProjectSnapshot> future = new ArrayDeque<>();

    public EditSession(ProjectState state) {
        this.state = state;
    }

    public ProjectState state() {
        return state;
    }

    // ---------- 结果类型 ----------

    public sealed interface OpResult {
        /**
         * 普通 op：下行 {@code state.patch}。
         *
         * @param patch 下行给前端的 state.patch（空 ops 表示"仅 version 推进"）
         * @param dirty 受影响的画布矩形；{@code null} = 无像素变化（如 canvas.resize no-op）
         */
        record Ok(StatePatch patch, DirtyRegion dirty) implements OpResult {}

        /**
         * 结构性跳变（undo/redo/template.apply）：下行 {@code state.snapshot} 全量状态。
         * patch 在这种跳变里无法用 JSON Patch 简洁表达，直接发全量更可靠。
         *
         * @param version 跳变后新的 {@link ProjectState#version()}
         * @param dirty   像素层面受影响的区域；跳变一般 = full canvas
         */
        record OkSnapshot(long version, DirtyRegion dirty) implements OpResult {}

        record Error(String code, String message) implements OpResult {}
    }

    // ---------- 内部反查 ----------

    /** 元素在 {@link ProjectState} 树里的定位：层索引 + 层内索引 + 引用。 */
    private record Locator(int layerIdx, int elementIdx, Layer layer, Element element) {}

    private Locator findElement(String elementId) {
        if (elementId == null) return null;
        List<Layer> ls = state.layers();
        for (int li = 0; li < ls.size(); li++) {
            List<Element> es = ls.get(li).elements();
            for (int ei = 0; ei < es.size(); ei++) {
                if (es.get(ei).id().equals(elementId)) {
                    return new Locator(li, ei, ls.get(li), es.get(ei));
                }
            }
        }
        return null;
    }

    private int findLayerIdx(String layerId) {
        if (layerId == null) return -1;
        List<Layer> ls = state.layers();
        for (int i = 0; i < ls.size(); i++) {
            if (ls.get(i).id().equals(layerId)) return i;
        }
        return -1;
    }

    private static String elementPath(int layerIdx, int elIdx) {
        return "/layers/" + layerIdx + "/elements/" + elIdx;
    }

    private static String elementFieldPath(int layerIdx, int elIdx, String field) {
        return "/layers/" + layerIdx + "/elements/" + elIdx + "/" + field;
    }

    private static String layerPath(int layerIdx) {
        return "/layers/" + layerIdx;
    }

    private static String layerFieldPath(int layerIdx, String field) {
        return "/layers/" + layerIdx + "/" + field;
    }

    // ---------- element.add ----------

    /**
     * 新增元素。{@code afterId} 为 null 时追加到末尾；否则插入到该元素之后。
     * {@code layerId} 为 null 时落到 activeLayer。
     *
     * @return {@code INVALID_ELEMENT} 若 {@code afterId} 指向不存在的元素；
     *         {@code LAYER_NOT_FOUND} 若 {@code layerId} 不存在；
     *         {@code LAYER_LOCKED} 若目标层被锁
     */
    public synchronized OpResult addElement(String type, Map<String, Object> props,
                                            String afterId, String layerId) {
        if (type == null) return err("INVALID_PAYLOAD", "element type missing");
        if (props == null) props = Map.of();

        Layer target;
        int layerIdx;
        if (layerId == null || layerId.isEmpty()) {
            target = state.activeLayer();
            layerIdx = findLayerIdx(target.id());
        } else {
            layerIdx = findLayerIdx(layerId);
            if (layerIdx < 0) return err("LAYER_NOT_FOUND", "layer not found: " + layerId);
            target = state.layers().get(layerIdx);
        }
        if (target.locked()) return err("LAYER_LOCKED", "target layer is locked: " + target.id());

        int insertIdx;
        if (afterId == null || afterId.isEmpty()) {
            insertIdx = target.elements().size();
        } else {
            int afterIdxInLayer = indexOfElementInLayer(target, afterId);
            if (afterIdxInLayer < 0) {
                return err("INVALID_ELEMENT", "after element not found in target layer: " + afterId);
            }
            insertIdx = afterIdxInLayer + 1;
        }

        String id = "e-" + UUID.randomUUID();
        Element element;
        try {
            element = switch (type) {
                case "text" -> buildText(id, props);
                case "rect" -> buildRect(id, props);
                case "path" -> buildPath(id, props);
                case "circle" -> buildCircle(id, props);
                case "shape" -> buildShape(id, props);
                default -> throw new ValidationException("INVALID_ELEMENT", "unknown element type: " + type);
            };
        } catch (ValidationException ve) {
            return err(ve.code, ve.getMessage());
        }

        ProjectSnapshot pre = snapshotNow();
        target.elements().add(insertIdx, element);
        commitHistory(pre);
        long v = state.bumpVersion();

        StatePatch patch = new StatePatchBuilder()
                .add(elementPath(layerIdx, insertIdx), element)
                .build(v);
        return new OpResult.Ok(patch, DirtyRegion.of(element));
    }

    private static int indexOfElementInLayer(Layer l, String elementId) {
        List<Element> es = l.elements();
        for (int i = 0; i < es.size(); i++) {
            if (es.get(i).id().equals(elementId)) return i;
        }
        return -1;
    }

    // ---------- element.update ----------

    /**
     * 字段级部分更新。{@code patch} 的每个 key 代表要修改的字段名，value 为新值。
     * 逐字段校验；失败时**不变更** state（all-or-nothing 语义）。
     *
     * <p>支持的字段：</p>
     * <ul>
     *   <li>共通：{@code x / y / w / h / rotation / locked / visible /
     *       opacity / blendMode / renderMode}（v2 新字段 M8-C 起接 patch）</li>
     *   <li>Text：{@code text / fontId / fontSize / color / align /
     *       letterSpacing / lineHeight / vertical / effects}</li>
     *   <li>Rect：{@code fill / stroke}</li>
     *   <li>Icon：{@code source / tint}</li>
     * </ul>
     */
    public synchronized OpResult updateElement(String elementId, Map<String, Object> patch) {
        if (elementId == null) return err("INVALID_PAYLOAD", "elementId missing");
        if (patch == null || patch.isEmpty()) return err("INVALID_PAYLOAD", "empty patch");

        Locator loc = findElement(elementId);
        if (loc == null) return err("INVALID_ELEMENT", "element not found: " + elementId);
        if (loc.layer.locked()) return err("LAYER_LOCKED", "owning layer is locked");

        Element updated;
        try {
            updated = switch (loc.element) {
                case TextElement t -> applyTextPatch(t, patch);
                case RectElement r -> applyRectPatch(r, patch);
                case IconElement ic -> applyIconPatch(ic, patch);
                case PathElement p -> applyPathPatch(p, patch);
                case CircleElement c -> applyCirclePatch(c, patch);
                case ShapeElement sh -> applyShapePatch(sh, patch);
            };
        } catch (ValidationException ve) {
            return err(ve.code, ve.getMessage());
        }

        ProjectSnapshot pre = snapshotNow();
        loc.layer.elements().set(loc.elementIdx, updated);
        commitHistory(pre);
        long v = state.bumpVersion();

        StatePatchBuilder b = new StatePatchBuilder();
        for (var e : patch.entrySet()) {
            String path = elementFieldPath(loc.layerIdx, loc.elementIdx, e.getKey());
            Object value = e.getValue();
            if (value == null) {
                b.remove(path);
            } else {
                b.replace(path, value);
            }
        }
        // 脏矩形 = 旧 bbox ∪ 新 bbox，覆盖元素"从旧位移到新位"的扫过区域
        DirtyRegion dirty = DirtyRegion.of(loc.element).union(DirtyRegion.of(updated));
        return new OpResult.Ok(b.build(v), dirty);
    }

    // ---------- element.delete ----------

    public synchronized OpResult deleteElement(String elementId) {
        if (elementId == null) return err("INVALID_PAYLOAD", "elementId missing");
        Locator loc = findElement(elementId);
        if (loc == null) return err("INVALID_ELEMENT", "element not found: " + elementId);
        if (loc.layer.locked()) return err("LAYER_LOCKED", "owning layer is locked");

        ProjectSnapshot pre = snapshotNow();
        loc.layer.elements().remove(loc.elementIdx);
        commitHistory(pre);
        long v = state.bumpVersion();
        return new OpResult.Ok(
                new StatePatchBuilder().remove(elementPath(loc.layerIdx, loc.elementIdx)).build(v),
                DirtyRegion.of(loc.element));
    }

    // ---------- element.reorder ----------

    /**
     * 把元素移动到所在层的 {@code newIndex} 位置（0 = 底层）。
     * 超出范围时 clamp 到 {@code [0, size-1]}。跨层用 {@link #moveElementToLayer}。
     */
    public synchronized OpResult reorderElement(String elementId, int newIndex) {
        if (elementId == null) return err("INVALID_PAYLOAD", "elementId missing");
        Locator loc = findElement(elementId);
        if (loc == null) return err("INVALID_ELEMENT", "element not found: " + elementId);
        if (loc.layer.locked()) return err("LAYER_LOCKED", "owning layer is locked");

        int size = loc.layer.elements().size();
        int to = Math.max(0, Math.min(newIndex, size - 1));
        if (to == loc.elementIdx) {
            // 无实际变化；仍 bump version 保持简单
            long v = state.bumpVersion();
            return new OpResult.Ok(new StatePatch(v, List.of()), null);
        }

        ProjectSnapshot pre = snapshotNow();
        Element moved = loc.layer.elements().remove(loc.elementIdx);
        loc.layer.elements().add(to, moved);
        commitHistory(pre);
        long v = state.bumpVersion();

        StatePatch patch = new StatePatchBuilder()
                .remove(elementPath(loc.layerIdx, loc.elementIdx))
                .add(elementPath(loc.layerIdx, to), moved)
                .build(v);
        return new OpResult.Ok(patch, DirtyRegion.of(moved));
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
        Map<String, Object> patch = new LinkedHashMap<>();
        if (x != null) patch.put("x", x);
        if (y != null) patch.put("y", y);
        if (w != null) patch.put("w", w);
        if (h != null) patch.put("h", h);
        if (rotation != null) patch.put("rotation", rotation);
        return updateElement(elementId, patch);
    }

    // ---------- element.move-to-layer ----------

    /**
     * 跨层移动元素。{@code index == null} = 落到目标层底层（index 0），其他值 clamp 到合法范围。
     *
     * <p>错误码：{@code INVALID_ELEMENT} / {@code LAYER_NOT_FOUND} / {@code LAYER_LOCKED}
     * （源层或目标层任一锁住都拒）。源层 == 目标层等价于 {@link #reorderElement}，但允许调用，
     * 仅按层内 reorder 处理。</p>
     */
    public synchronized OpResult moveElementToLayer(String elementId, String targetLayerId,
                                                    Integer index) {
        if (elementId == null) return err("INVALID_PAYLOAD", "elementId missing");
        if (targetLayerId == null || targetLayerId.isEmpty()) {
            return err("INVALID_PAYLOAD", "targetLayerId missing");
        }
        Locator loc = findElement(elementId);
        if (loc == null) return err("INVALID_ELEMENT", "element not found: " + elementId);
        if (loc.layer.locked()) return err("LAYER_LOCKED", "source layer is locked");

        int targetIdx = findLayerIdx(targetLayerId);
        if (targetIdx < 0) return err("LAYER_NOT_FOUND", "target layer not found: " + targetLayerId);
        Layer target = state.layers().get(targetIdx);
        if (target.locked()) return err("LAYER_LOCKED", "target layer is locked");

        // 同层移动 = reorder（保证 patch 输出一致）
        if (loc.layerIdx == targetIdx) {
            int to = index == null ? 0 : Math.max(0,
                    Math.min(index, loc.layer.elements().size() - 1));
            return reorderElementUnsafe(loc, to);
        }

        int to = index == null ? 0 : Math.max(0, Math.min(index, target.elements().size()));

        ProjectSnapshot pre = snapshotNow();
        Element moved = loc.layer.elements().remove(loc.elementIdx);
        target.elements().add(to, moved);
        commitHistory(pre);
        long v = state.bumpVersion();

        StatePatch patch = new StatePatchBuilder()
                .remove(elementPath(loc.layerIdx, loc.elementIdx))
                .add(elementPath(targetIdx, to), moved)
                .build(v);
        return new OpResult.Ok(patch, DirtyRegion.of(moved));
    }

    /** 内部 helper：已经 findElement 过的 reorder，避免再扫一遍。 */
    private OpResult reorderElementUnsafe(Locator loc, int to) {
        if (to == loc.elementIdx) {
            long v = state.bumpVersion();
            return new OpResult.Ok(new StatePatch(v, List.of()), null);
        }
        ProjectSnapshot pre = snapshotNow();
        Element moved = loc.layer.elements().remove(loc.elementIdx);
        loc.layer.elements().add(to, moved);
        commitHistory(pre);
        long v = state.bumpVersion();
        StatePatch patch = new StatePatchBuilder()
                .remove(elementPath(loc.layerIdx, loc.elementIdx))
                .add(elementPath(loc.layerIdx, to), moved)
                .build(v);
        return new OpResult.Ok(patch, DirtyRegion.of(moved));
    }

    // ---------- layer.create ----------

    /**
     * 新建空 Layer。{@code afterLayerId} 缺省 = 顶端（layers 末尾，渲染顺序最上）；
     * 非空时插入到该层之上（index + 1）。
     */
    public synchronized OpResult createLayer(String name, String afterLayerId) {
        if (state.layers().size() >= MAX_LAYERS) {
            return err("INVALID_PAYLOAD", "max layers reached: " + MAX_LAYERS);
        }
        String layerName;
        if (name == null || name.isBlank()) {
            layerName = "Layer " + (state.layers().size() + 1);
        } else if (name.length() > MAX_LAYER_NAME) {
            return err("INVALID_PAYLOAD", "layer name too long (max " + MAX_LAYER_NAME + ")");
        } else {
            layerName = name;
        }

        int insertIdx;
        if (afterLayerId == null || afterLayerId.isEmpty()) {
            insertIdx = state.layers().size();
        } else {
            int afterIdx = findLayerIdx(afterLayerId);
            if (afterIdx < 0) {
                return err("LAYER_NOT_FOUND", "afterLayerId not found: " + afterLayerId);
            }
            insertIdx = afterIdx + 1;
        }

        String id = "l-" + UUID.randomUUID().toString().substring(0, 8);
        Layer newLayer = new Layer(id, layerName, true, false, 1.0f, BlendMode.NORMAL,
                new ArrayList<>());

        ProjectSnapshot pre = snapshotNow();
        state.insertLayer(insertIdx, newLayer);
        commitHistory(pre);
        long v = state.bumpVersion();

        StatePatch patch = new StatePatchBuilder()
                .add(layerPath(insertIdx), newLayer)
                .build(v);
        // 空层创建不产生像素变化
        return new OpResult.Ok(patch, null);
    }

    // ---------- layer.delete ----------

    public synchronized OpResult deleteLayer(String layerId) {
        if (layerId == null) return err("INVALID_PAYLOAD", "layerId missing");
        int idx = findLayerIdx(layerId);
        if (idx < 0) return err("LAYER_NOT_FOUND", "layer not found: " + layerId);
        if (state.layers().size() <= 1) {
            return err("LAST_LAYER", "cannot delete the last layer");
        }

        Layer doomed = state.layers().get(idx);
        boolean hadVisibleContent = doomed.visible() && !doomed.elements().isEmpty();
        boolean wasActive = layerId.equals(state.activeLayerId());

        ProjectSnapshot pre = snapshotNow();
        state.removeLayer(idx);
        // 删的是 activeLayer：转到剩余的第一层
        String newActive = wasActive ? state.layers().get(0).id() : null;
        if (newActive != null) state.activeLayerId(newActive);
        commitHistory(pre);
        long v = state.bumpVersion();

        StatePatchBuilder b = new StatePatchBuilder()
                .remove(layerPath(idx));
        if (newActive != null) {
            b.replace("/activeLayerId", newActive);
        }
        // 删除可见非空层 = full canvas 重绘（合成顺序变了）
        DirtyRegion dirty = hadVisibleContent ? DirtyRegion.fullCanvas(state) : null;
        return new OpResult.Ok(b.build(v), dirty);
    }

    // ---------- layer.update ----------

    /**
     * 修改层属性。支持字段：{@code name / visible / locked / opacity / blendMode}。
     * locked 层<b>仍可</b>改自身这些属性（包括 unlock 自己）。
     */
    public synchronized OpResult updateLayer(String layerId, Map<String, Object> patch) {
        if (layerId == null) return err("INVALID_PAYLOAD", "layerId missing");
        if (patch == null || patch.isEmpty()) return err("INVALID_PAYLOAD", "empty patch");
        int idx = findLayerIdx(layerId);
        if (idx < 0) return err("LAYER_NOT_FOUND", "layer not found: " + layerId);

        Layer cur = state.layers().get(idx);
        String name = cur.name();
        boolean visible = cur.visible();
        boolean locked = cur.locked();
        float opacity = cur.opacity();
        BlendMode blendMode = cur.blendMode();

        try {
            for (var e : patch.entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                switch (k) {
                    case "name" -> {
                        String n = requireStringValue(v, k);
                        if (n.length() > MAX_LAYER_NAME) {
                            throw new ValidationException("INVALID_PAYLOAD",
                                    "name too long (max " + MAX_LAYER_NAME + ")");
                        }
                        name = n;
                    }
                    case "visible" -> visible = boolValue(v, k);
                    case "locked" -> locked = boolValue(v, k);
                    case "opacity" -> {
                        float o = floatValue(v, k);
                        if (!Float.isFinite(o) || o < 0f || o > 1f) {
                            throw new ValidationException("INVALID_PAYLOAD",
                                    "opacity must be in [0,1]: " + o);
                        }
                        opacity = o;
                    }
                    case "blendMode" -> blendMode = parseBlendMode(v);
                    default -> throw new ValidationException("INVALID_PAYLOAD",
                            "unknown layer field: " + k);
                }
            }
        } catch (ValidationException ve) {
            return err(ve.code, ve.getMessage());
        }

        Layer updated = new Layer(cur.id(), name, visible, locked, opacity, blendMode,
                cur.elements());

        ProjectSnapshot pre = snapshotNow();
        state.replaceLayer(idx, updated);
        commitHistory(pre);
        long v = state.bumpVersion();

        StatePatchBuilder b = new StatePatchBuilder();
        for (var e : patch.entrySet()) {
            String k = e.getKey();
            Object value = (k.equals("blendMode") && e.getValue() instanceof String s)
                    ? s.toLowerCase()   // 规范化输出 lowercase
                    : e.getValue();
            b.replace(layerFieldPath(idx, k), value);
        }
        // visible / opacity / blendMode 改动 → 影响渲染；name 不影响
        boolean pixelAffecting = patch.containsKey("visible")
                || patch.containsKey("opacity")
                || patch.containsKey("blendMode");
        DirtyRegion dirty = (pixelAffecting && !updated.elements().isEmpty())
                ? DirtyRegion.fullCanvas(state)
                : null;
        return new OpResult.Ok(b.build(v), dirty);
    }

    // ---------- layer.reorder ----------

    public synchronized OpResult reorderLayer(String layerId, int newIndex) {
        if (layerId == null) return err("INVALID_PAYLOAD", "layerId missing");
        int from = findLayerIdx(layerId);
        if (from < 0) return err("LAYER_NOT_FOUND", "layer not found: " + layerId);
        int size = state.layers().size();
        int to = Math.max(0, Math.min(newIndex, size - 1));
        if (to == from) {
            long v = state.bumpVersion();
            return new OpResult.Ok(new StatePatch(v, List.of()), null);
        }
        Layer moved = state.layers().get(from);

        ProjectSnapshot pre = snapshotNow();
        state.moveLayer(from, to);
        commitHistory(pre);
        long v = state.bumpVersion();
        StatePatch patch = new StatePatchBuilder()
                .remove(layerPath(from))
                .add(layerPath(to), moved)
                .build(v);
        // 层重排 = 合成顺序变 = full canvas 重绘
        DirtyRegion dirty = moved.visible() && !moved.elements().isEmpty()
                ? DirtyRegion.fullCanvas(state)
                : null;
        return new OpResult.Ok(patch, dirty);
    }

    // ---------- layer.duplicate ----------

    /** 复制层，所有元素分配新 id；插入到原层之上。 */
    public synchronized OpResult duplicateLayer(String layerId) {
        if (layerId == null) return err("INVALID_PAYLOAD", "layerId missing");
        if (state.layers().size() >= MAX_LAYERS) {
            return err("INVALID_PAYLOAD", "max layers reached: " + MAX_LAYERS);
        }
        int idx = findLayerIdx(layerId);
        if (idx < 0) return err("LAYER_NOT_FOUND", "layer not found: " + layerId);
        Layer src = state.layers().get(idx);

        String newId = "l-" + UUID.randomUUID().toString().substring(0, 8);
        String newName = src.name() + " copy";
        if (newName.length() > MAX_LAYER_NAME) {
            newName = newName.substring(0, MAX_LAYER_NAME);
        }
        List<Element> copiedElements = new ArrayList<>(src.elements().size());
        for (Element e : src.elements()) {
            copiedElements.add(cloneElementWithNewId(e));
        }
        Layer copy = new Layer(newId, newName, src.visible(), false, // 复制的层默认不锁
                src.opacity(), src.blendMode(), copiedElements);

        int insertIdx = idx + 1;
        ProjectSnapshot pre = snapshotNow();
        state.insertLayer(insertIdx, copy);
        commitHistory(pre);
        long v = state.bumpVersion();
        StatePatch patch = new StatePatchBuilder()
                .add(layerPath(insertIdx), copy)
                .build(v);
        DirtyRegion dirty = copy.visible() && !copy.elements().isEmpty()
                ? DirtyRegion.fullCanvas(state)
                : null;
        return new OpResult.Ok(patch, dirty);
    }

    // ---------- layer.set-active ----------

    /**
     * 切换活动层。仅改 {@code activeLayerId}，**不进 undo 栈**（纯 UI 状态）。
     * 空 ops 还是要返回 patch 让前端同步 activeLayerId 镜像。
     */
    public synchronized OpResult setActiveLayer(String layerId) {
        if (layerId == null || layerId.isEmpty()) {
            return err("INVALID_PAYLOAD", "layerId missing");
        }
        int idx = findLayerIdx(layerId);
        if (idx < 0) return err("LAYER_NOT_FOUND", "layer not found: " + layerId);
        if (layerId.equals(state.activeLayerId())) {
            long v = state.bumpVersion();
            return new OpResult.Ok(new StatePatch(v, List.of()), null);
        }
        state.activeLayerId(layerId);
        long v = state.bumpVersion();
        StatePatch patch = new StatePatchBuilder()
                .replace("/activeLayerId", layerId)
                .build(v);
        return new OpResult.Ok(patch, null);
    }

    // ---------- canvas.resize ----------

    /**
     * M3 只接受尺寸等于当前值的 no-op resize（保持 op channel 通畅 + 前端试探能通过）。
     * 真正的动态扩缩容涉及 MapPool 借还和物品框增删，留给后续 milestone。
     */
    public synchronized OpResult resizeCanvas(int widthMaps, int heightMaps) {
        ProjectState.Canvas c = state.canvas();
        if (widthMaps != c.widthMaps() || heightMaps != c.heightMaps()) {
            return err("POOL_EXHAUSTED",
                    "canvas resize to " + widthMaps + "x" + heightMaps
                    + " not supported (wall fixed at " + c.widthMaps() + "x" + c.heightMaps() + ")");
        }
        long v = state.bumpVersion();
        return new OpResult.Ok(new StatePatch(v, List.of()), null);
    }

    // ---------- canvas.background ----------

    public synchronized OpResult setBackground(String color) {
        if (!isValidColor(color)) return err("INVALID_PAYLOAD", "invalid color: " + color);
        ProjectState.Canvas c = state.canvas();
        ProjectSnapshot pre = snapshotNow();
        state.canvas(new ProjectState.Canvas(c.widthMaps(), c.heightMaps(), color,
                c.gridSize(), c.guides()));
        commitHistory(pre);
        long v = state.bumpVersion();
        return new OpResult.Ok(
                new StatePatchBuilder().replace("/canvas/background", color).build(v),
                DirtyRegion.fullCanvas(state));
    }

    // ---------- canvas.grid ----------

    /** {@code size} = 0 / null → 关闭网格。 */
    public synchronized OpResult setGridSize(Integer size) {
        if (size != null && (size < 0 || size > 512)) {
            return err("INVALID_PAYLOAD", "gridSize out of range 0..512: " + size);
        }
        ProjectState.Canvas c = state.canvas();
        Integer normalized = (size == null || size == 0) ? null : size;
        ProjectSnapshot pre = snapshotNow();
        state.canvas(new ProjectState.Canvas(c.widthMaps(), c.heightMaps(),
                c.background(), normalized, c.guides()));
        commitHistory(pre);
        long v = state.bumpVersion();
        // gridSize 仅前端预览，不影响 MC 像素
        return new OpResult.Ok(
                new StatePatchBuilder().replace("/canvas/gridSize", normalized).build(v),
                null);
    }

    // ---------- canvas.guides.set ----------

    /** 整组替换 guides。空列表 / null 都清空。 */
    public synchronized OpResult setGuides(List<?> rawGuides) {
        if (rawGuides == null) rawGuides = List.of();
        if (rawGuides.size() > MAX_GUIDES) {
            return err("INVALID_PAYLOAD", "too many guides (max " + MAX_GUIDES + ")");
        }
        List<Guide> guides = new ArrayList<>(rawGuides.size());
        try {
            for (Object o : rawGuides) {
                if (!(o instanceof Map<?, ?> m)) {
                    throw new ValidationException("INVALID_PAYLOAD", "guide must be object");
                }
                Object ax = m.get("axis");
                if (!(ax instanceof String axisStr) || (!axisStr.equals("x") && !axisStr.equals("y"))) {
                    throw new ValidationException("INVALID_PAYLOAD",
                            "guide.axis must be 'x' or 'y'");
                }
                Object pos = m.get("position");
                if (!(pos instanceof Number pn)) {
                    throw new ValidationException("INVALID_PAYLOAD",
                            "guide.position must be number");
                }
                guides.add(new Guide(axisStr, pn.intValue()));
            }
        } catch (ValidationException ve) {
            return err(ve.code, ve.getMessage());
        }

        ProjectState.Canvas c = state.canvas();
        ProjectSnapshot pre = snapshotNow();
        state.canvas(new ProjectState.Canvas(c.widthMaps(), c.heightMaps(),
                c.background(), c.gridSize(), guides));
        commitHistory(pre);
        long v = state.bumpVersion();
        return new OpResult.Ok(
                new StatePatchBuilder().replace("/canvas/guides", guides).build(v),
                null);
    }

    // ---------- template.apply ----------

    /**
     * 替换 ProjectState 内容（背景 + 整 layers 树）。{@code template.apply} 的"replace 语义"。
     *
     * <p>M8-C 升级：清空所有层 → 生成一个 Default Layer 包住 {@code elements} → activeLayerId
     * 指向它。符合 {@code docs/architecture.md §10.5}。</p>
     *
     * @param backgroundColor 新背景色（null = 保留当前）
     * @param elements        新 element 列表（已由 TemplateInstantiator 物化）
     */
    public synchronized OpResult replaceContent(String backgroundColor, List<Element> elements) {
        ProjectSnapshot pre = snapshotNow();
        ProjectState.Canvas c = state.canvas();
        state.canvas(new ProjectState.Canvas(c.widthMaps(), c.heightMaps(),
                backgroundColor == null ? c.background() : backgroundColor,
                c.gridSize(), c.guides()));
        // 重建：单个 Default Layer 包新 elements，activeLayerId 指向它
        String newLayerId = "l-" + UUID.randomUUID().toString().substring(0, 8);
        Layer defLayer = new Layer(newLayerId, ProjectState.DEFAULT_LAYER_NAME,
                true, false, 1.0f, BlendMode.NORMAL,
                new ArrayList<>(elements == null ? List.of() : elements));
        state.replaceAllLayers(List.of(defLayer), newLayerId);
        commitHistory(pre);
        long v = state.bumpVersion();
        return new OpResult.OkSnapshot(v, DirtyRegion.fullCanvas(state));
    }

    // ---------- undo / redo / history.mark ----------

    /**
     * 撤销到最近一次成功 op 之前的状态。past 栈为空时返回错。
     * 恢复后下行 {@code state.snapshot}（跳变无法用 patch 简洁表达），像素层面全画布重绘。
     */
    public synchronized OpResult undo() {
        if (past.isEmpty()) {
            return err("INVALID_PAYLOAD", "nothing to undo");
        }
        future.push(snapshotNow());
        ProjectSnapshot restoreTo = past.pop();
        state.restore(restoreTo);
        long v = state.bumpVersion();
        return new OpResult.OkSnapshot(v, DirtyRegion.fullCanvas(state));
    }

    /** undo 的逆操作。future 栈为空时返回错。 */
    public synchronized OpResult redo() {
        if (future.isEmpty()) {
            return err("INVALID_PAYLOAD", "nothing to redo");
        }
        ProjectSnapshot preRedo = snapshotNow();
        past.push(preRedo);
        while (past.size() > MAX_HISTORY) past.removeLast();
        ProjectSnapshot restoreTo = future.pop();
        state.restore(restoreTo);
        long v = state.bumpVersion();
        return new OpResult.OkSnapshot(v, DirtyRegion.fullCanvas(state));
    }

    /**
     * 在 past 栈顶加一个命名检查点（{@code docs/protocol.md §5.5}）。
     * <b>不</b>清空 future——mark 只给当前点贴标签，不创建新 edit 分支。
     */
    public synchronized OpResult historyMark(String label) {
        if (label == null || label.isEmpty()) {
            return err("INVALID_PAYLOAD", "label required");
        }
        if (label.length() > 64) {
            return err("INVALID_PAYLOAD", "label too long (max 64)");
        }
        ProjectSnapshot marked = new ProjectSnapshot(
                state.canvas(), state.layers(), state.activeLayerId(), label);
        past.push(marked);
        while (past.size() > MAX_HISTORY) past.removeLast();
        long v = state.bumpVersion();
        return new OpResult.Ok(new StatePatch(v, List.of()), null);
    }

    // ---------- 历史栈内部 helpers ----------

    private ProjectSnapshot snapshotNow() {
        return new ProjectSnapshot(
                state.canvas(), state.layers(), state.activeLayerId(), null);
    }

    /**
     * 把 {@code preSnapshot} 推进 past 栈，超过 {@link #MAX_HISTORY} 踢掉最老一条；
     * 清空 future 栈（标准 undo 语义：新 edit 弃用 redo 分支）。
     */
    private void commitHistory(ProjectSnapshot preSnapshot) {
        past.push(preSnapshot);
        while (past.size() > MAX_HISTORY) past.removeLast();
        future.clear();
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
        String fontId = stringFieldOrDefault(p, "fontId", "ark_pixel");
        int fontSize = intFieldOrDefault(p, "fontSize", 12); validateFontSize(fontSize);
        String color = stringFieldOrDefault(p, "color", "#000000"); validateColor(color);
        String align = stringFieldOrDefault(p, "align", "left"); validateAlign(align);
        float letterSpacing = floatFieldOrDefault(p, "letterSpacing", 0f);
        validateLetterSpacing(letterSpacing);
        float lineHeight = floatFieldOrDefault(p, "lineHeight", 1.2f);
        validateLineHeight(lineHeight);
        boolean vertical = boolFieldOrDefault(p, "vertical", false);
        Effects effects = buildEffects(p.get("effects"));
        return new TextElement(id, x, y, w, h, rotation, locked, visible,
                text, fontId, fontSize, color, align,
                letterSpacing, lineHeight, vertical, effects,
                null, null, null);
    }

    /** 解析 {@code payload.effects}。null 或空 object 都返 null。 */
    private Effects buildEffects(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) {
            throw new ValidationException("INVALID_PAYLOAD", "effects must be object");
        }
        Stroke stroke = buildStroke(m.get("stroke"));
        Shadow shadow = buildShadow(m.get("shadow"));
        Glow glow = buildGlow(m.get("glow"));
        if (stroke == null && shadow == null && glow == null) return null;
        return new Effects(stroke, shadow, glow);
    }

    private Shadow buildShadow(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) {
            throw new ValidationException("INVALID_PAYLOAD", "shadow must be object");
        }
        int dx = ((Number) requireNumber(m, "dx")).intValue();
        int dy = ((Number) requireNumber(m, "dy")).intValue();
        if (Math.abs(dx) > MAX_SHADOW_OFFSET || Math.abs(dy) > MAX_SHADOW_OFFSET) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "shadow offset out of range ±" + MAX_SHADOW_OFFSET);
        }
        Object c = m.get("color");
        if (!(c instanceof String color)) {
            throw new ValidationException("INVALID_PAYLOAD", "shadow.color must be string");
        }
        validateColor(color);
        return new Shadow(dx, dy, color);
    }

    private Glow buildGlow(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) {
            throw new ValidationException("INVALID_PAYLOAD", "glow must be object");
        }
        int radius = ((Number) requireNumber(m, "radius")).intValue();
        if (radius < 0 || radius > MAX_GLOW_RADIUS) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "glow.radius out of range 0.." + MAX_GLOW_RADIUS);
        }
        Object c = m.get("color");
        if (!(c instanceof String color)) {
            throw new ValidationException("INVALID_PAYLOAD", "glow.color must be string");
        }
        validateColor(color);
        return new Glow(radius, color);
    }

    private Element buildRect(String id, Map<String, Object> p) {
        int x = intFieldOrDefault(p, "x", 0); validateCoord(x, "x");
        int y = intFieldOrDefault(p, "y", 0); validateCoord(y, "y");
        int w = intFieldOrDefault(p, "w", 64); validateDim(w, "w");
        int h = intFieldOrDefault(p, "h", 64); validateDim(h, "h");
        int rotation = intFieldOrDefault(p, "rotation", 0); validateRotation(rotation);
        boolean locked = boolFieldOrDefault(p, "locked", false);
        boolean visible = boolFieldOrDefault(p, "visible", true);
        Fill fill = parseFillNullable(p.get("fill"));
        Stroke stroke = buildStroke(p.get("stroke"));
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "rect needs fill or non-zero stroke");
        }
        return new RectElement(id, x, y, w, h, rotation, locked, visible, fill, stroke,
                null, null, null);
    }

    // ---------- M9 PathElement ----------

    private Element buildPath(String id, Map<String, Object> p) {
        int x = intFieldOrDefault(p, "x", 0); validateCoord(x, "x");
        int y = intFieldOrDefault(p, "y", 0); validateCoord(y, "y");
        int w = intFieldOrDefault(p, "w", 100); validateDim(w, "w");
        int h = intFieldOrDefault(p, "h", 100); validateDim(h, "h");
        int rotation = intFieldOrDefault(p, "rotation", 0); validateRotation(rotation);
        boolean locked = boolFieldOrDefault(p, "locked", false);
        boolean visible = boolFieldOrDefault(p, "visible", true);
        String d = requireString(p, "d", true);
        validatePathD(d);
        Fill fill = parseFillNullable(p.get("fill"));
        Stroke stroke = buildStroke(p.get("stroke"));
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "path needs fill or non-zero stroke");
        }
        String markerStart = parseMarkerNullable(p.get("markerStart"));
        String markerEnd = parseMarkerNullable(p.get("markerEnd"));
        return new PathElement(id, x, y, w, h, rotation, locked, visible,
                d, fill, stroke, markerStart, markerEnd,
                null, null, null);
    }

    private PathElement applyPathPatch(PathElement orig, Map<String, Object> patch) {
        int x = orig.x(); int y = orig.y(); int w = orig.w(); int h = orig.h();
        int rotation = orig.rotation();
        boolean locked = orig.locked(); boolean visible = orig.visible();
        String d = orig.d();
        Fill fill = orig.fill();
        Stroke stroke = orig.stroke();
        String markerStart = orig.markerStart();
        String markerEnd = orig.markerEnd();
        Float opacity = orig.opacity();
        BlendMode blendMode = orig.blendMode();
        RenderMode renderMode = orig.renderMode();

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
                case "d" -> { d = requireStringValue(v, k); validatePathD(d); }
                case "fill" -> fill = parseFillNullable(v);
                case "stroke" -> stroke = buildStroke(v);
                case "markerStart" -> markerStart = parseMarkerNullable(v);
                case "markerEnd" -> markerEnd = parseMarkerNullable(v);
                case "opacity" -> opacity = parseOpacityNullable(v);
                case "blendMode" -> blendMode = parseBlendModeNullable(v);
                case "renderMode" -> renderMode = parseRenderModeNullable(v);
                default -> throw new ValidationException("INVALID_PAYLOAD",
                        "unknown path field: " + k);
            }
        }
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "path needs fill or non-zero stroke");
        }
        return new PathElement(orig.id(), x, y, w, h, rotation, locked, visible,
                d, fill, stroke, markerStart, markerEnd,
                opacity, blendMode, renderMode);
    }

    private static void validatePathD(String d) {
        PathDValidator.Result r = PathDValidator.validate(d);
        if (!r.ok()) throw new ValidationException("INVALID_PAYLOAD", "path.d invalid: " + r.reason());
    }

    private static String parseMarkerNullable(Object v) {
        if (v == null) return null;
        if (!(v instanceof String s)) {
            throw new ValidationException("INVALID_PAYLOAD", "marker must be string");
        }
        return switch (s) {
            case "arrow", "dot" -> s;
            default -> throw new ValidationException("INVALID_PAYLOAD",
                    "marker must be 'arrow' or 'dot': " + s);
        };
    }

    // ---------- M9 CircleElement ----------

    private Element buildCircle(String id, Map<String, Object> p) {
        int x = intFieldOrDefault(p, "x", 0); validateCoord(x, "x");
        int y = intFieldOrDefault(p, "y", 0); validateCoord(y, "y");
        int w = intFieldOrDefault(p, "w", 64); validateDim(w, "w");
        int h = intFieldOrDefault(p, "h", 64); validateDim(h, "h");
        int rotation = intFieldOrDefault(p, "rotation", 0); validateRotation(rotation);
        boolean locked = boolFieldOrDefault(p, "locked", false);
        boolean visible = boolFieldOrDefault(p, "visible", true);
        Fill fill = parseFillNullable(p.get("fill"));
        Stroke stroke = buildStroke(p.get("stroke"));
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "circle needs fill or non-zero stroke");
        }
        return new CircleElement(id, x, y, w, h, rotation, locked, visible,
                fill, stroke,
                null, null, null);
    }

    private CircleElement applyCirclePatch(CircleElement orig, Map<String, Object> patch) {
        int x = orig.x(); int y = orig.y(); int w = orig.w(); int h = orig.h();
        int rotation = orig.rotation();
        boolean locked = orig.locked(); boolean visible = orig.visible();
        Fill fill = orig.fill();
        Stroke stroke = orig.stroke();
        Float opacity = orig.opacity();
        BlendMode blendMode = orig.blendMode();
        RenderMode renderMode = orig.renderMode();

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
                case "fill" -> fill = parseFillNullable(v);
                case "stroke" -> stroke = buildStroke(v);
                case "opacity" -> opacity = parseOpacityNullable(v);
                case "blendMode" -> blendMode = parseBlendModeNullable(v);
                case "renderMode" -> renderMode = parseRenderModeNullable(v);
                default -> throw new ValidationException("INVALID_PAYLOAD",
                        "unknown circle field: " + k);
            }
        }
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "circle needs fill or non-zero stroke");
        }
        return new CircleElement(orig.id(), x, y, w, h, rotation, locked, visible,
                fill, stroke,
                opacity, blendMode, renderMode);
    }

    // ---------- M9 ShapeElement ----------

    private static final int MIN_SIDES = 3;
    private static final int MAX_SIDES = 32;
    private static final float MIN_INNER_RATIO = 0.1f;
    private static final float MAX_INNER_RATIO = 0.95f;

    private Element buildShape(String id, Map<String, Object> p) {
        int x = intFieldOrDefault(p, "x", 0); validateCoord(x, "x");
        int y = intFieldOrDefault(p, "y", 0); validateCoord(y, "y");
        int w = intFieldOrDefault(p, "w", 80); validateDim(w, "w");
        int h = intFieldOrDefault(p, "h", 80); validateDim(h, "h");
        int rotation = intFieldOrDefault(p, "rotation", 0); validateRotation(rotation);
        boolean locked = boolFieldOrDefault(p, "locked", false);
        boolean visible = boolFieldOrDefault(p, "visible", true);
        String kind = stringFieldOrDefault(p, "kind", "polygon");
        validateShapeKind(kind);
        int sides = intFieldOrDefault(p, "sides", kind.equals("star") ? 5 : 6);
        validateSides(sides);
        Float innerRatio = null;
        Object irRaw = p.get("innerRatio");
        if (irRaw != null) {
            innerRatio = ((Number) irRaw).floatValue();
            validateInnerRatio(innerRatio);
        }
        Fill fill = parseFillNullable(p.get("fill"));
        Stroke stroke = buildStroke(p.get("stroke"));
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "shape needs fill or non-zero stroke");
        }
        return new ShapeElement(id, x, y, w, h, rotation, locked, visible,
                kind, sides, innerRatio,
                fill, stroke,
                null, null, null);
    }

    private ShapeElement applyShapePatch(ShapeElement orig, Map<String, Object> patch) {
        int x = orig.x(); int y = orig.y(); int w = orig.w(); int h = orig.h();
        int rotation = orig.rotation();
        boolean locked = orig.locked(); boolean visible = orig.visible();
        String kind = orig.kind();
        int sides = orig.sides();
        Float innerRatio = orig.innerRatio();
        Fill fill = orig.fill();
        Stroke stroke = orig.stroke();
        Float opacity = orig.opacity();
        BlendMode blendMode = orig.blendMode();
        RenderMode renderMode = orig.renderMode();

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
                case "kind" -> { kind = requireStringValue(v, k); validateShapeKind(kind); }
                case "sides" -> { sides = intValue(v, k); validateSides(sides); }
                case "innerRatio" -> {
                    if (v == null) innerRatio = null;
                    else { innerRatio = floatValue(v, k); validateInnerRatio(innerRatio); }
                }
                case "fill" -> fill = parseFillNullable(v);
                case "stroke" -> stroke = buildStroke(v);
                case "opacity" -> opacity = parseOpacityNullable(v);
                case "blendMode" -> blendMode = parseBlendModeNullable(v);
                case "renderMode" -> renderMode = parseRenderModeNullable(v);
                default -> throw new ValidationException("INVALID_PAYLOAD",
                        "unknown shape field: " + k);
            }
        }
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "shape needs fill or non-zero stroke");
        }
        return new ShapeElement(orig.id(), x, y, w, h, rotation, locked, visible,
                kind, sides, innerRatio,
                fill, stroke,
                opacity, blendMode, renderMode);
    }

    private static void validateShapeKind(String k) {
        if (!"polygon".equals(k) && !"star".equals(k)) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "shape.kind must be 'polygon' or 'star': " + k);
        }
    }

    private static void validateSides(int v) {
        if (v < MIN_SIDES || v > MAX_SIDES) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "shape.sides out of range [" + MIN_SIDES + ", " + MAX_SIDES + "]: " + v);
        }
    }

    private static void validateInnerRatio(float v) {
        if (!Float.isFinite(v) || v < MIN_INNER_RATIO || v > MAX_INNER_RATIO) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "shape.innerRatio out of range [" + MIN_INNER_RATIO + ", " + MAX_INNER_RATIO + "]: " + v);
        }
    }

    // ---------- 共享 helpers ----------

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
        float letterSpacing = t.letterSpacing();
        float lineHeight = t.lineHeight();
        boolean vertical = t.vertical();
        Effects effects = t.effects();
        Float opacity = t.opacity();
        BlendMode blendMode = t.blendMode();
        RenderMode renderMode = t.renderMode();

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
                case "letterSpacing" -> {
                    letterSpacing = floatValue(v, k); validateLetterSpacing(letterSpacing);
                }
                case "lineHeight" -> {
                    lineHeight = floatValue(v, k); validateLineHeight(lineHeight);
                }
                case "vertical" -> vertical = boolValue(v, k);
                case "effects" -> effects = buildEffects(v);
                case "opacity" -> opacity = parseOpacityNullable(v);
                case "blendMode" -> blendMode = parseBlendModeNullable(v);
                case "renderMode" -> renderMode = parseRenderModeNullable(v);
                default -> throw new ValidationException("INVALID_PAYLOAD",
                        "unknown text field: " + k);
            }
        }
        return new TextElement(t.id(), x, y, w, h, rotation, locked, visible,
                text, fontId, fontSize, color, align,
                letterSpacing, lineHeight, vertical, effects,
                opacity, blendMode, renderMode);
    }

    private RectElement applyRectPatch(RectElement r, Map<String, Object> patch) {
        int x = r.x(); int y = r.y(); int w = r.w(); int h = r.h();
        int rotation = r.rotation();
        boolean locked = r.locked(); boolean visible = r.visible();
        Fill fill = r.fill();
        Stroke stroke = r.stroke();
        Float opacity = r.opacity();
        BlendMode blendMode = r.blendMode();
        RenderMode renderMode = r.renderMode();

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
                case "fill" -> fill = parseFillNullable(v);
                case "stroke" -> stroke = buildStroke(v);
                case "opacity" -> opacity = parseOpacityNullable(v);
                case "blendMode" -> blendMode = parseBlendModeNullable(v);
                case "renderMode" -> renderMode = parseRenderModeNullable(v);
                default -> throw new ValidationException("INVALID_PAYLOAD",
                        "unknown rect field: " + k);
            }
        }
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "rect needs fill or non-zero stroke");
        }
        return new RectElement(r.id(), x, y, w, h, rotation, locked, visible, fill, stroke,
                opacity, blendMode, renderMode);
    }

    private IconElement applyIconPatch(IconElement ic, Map<String, Object> patch) {
        int x = ic.x(); int y = ic.y(); int w = ic.w(); int h = ic.h();
        int rotation = ic.rotation();
        boolean locked = ic.locked(); boolean visible = ic.visible();
        String source = ic.source();
        String tint = ic.tint();
        Float opacity = ic.opacity();
        BlendMode blendMode = ic.blendMode();
        RenderMode renderMode = ic.renderMode();

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
                case "source" -> {
                    source = requireStringValue(v, k);
                    if (!source.matches("^[a-z0-9_-]{1,32}$")) {
                        throw new ValidationException("INVALID_PAYLOAD",
                                "icon source must match [a-z0-9_-]{1,32}: " + source);
                    }
                }
                case "tint" -> {
                    if (v == null) tint = null;
                    else { tint = requireStringValue(v, k); validateColor(tint); }
                }
                case "opacity" -> opacity = parseOpacityNullable(v);
                case "blendMode" -> blendMode = parseBlendModeNullable(v);
                case "renderMode" -> renderMode = parseRenderModeNullable(v);
                default -> throw new ValidationException("INVALID_PAYLOAD",
                        "unknown icon field: " + k);
            }
        }
        return new IconElement(ic.id(), x, y, w, h, rotation, locked, visible, source, tint,
                opacity, blendMode, renderMode);
    }

    private static Element cloneElementWithNewId(Element src) {
        String newId = "e-" + UUID.randomUUID();
        return switch (src) {
            case TextElement t -> new TextElement(newId,
                    t.x(), t.y(), t.w(), t.h(), t.rotation(), t.locked(), t.visible(),
                    t.text(), t.fontId(), t.fontSize(), t.color(), t.align(),
                    t.letterSpacing(), t.lineHeight(), t.vertical(), t.effects(),
                    t.opacity(), t.blendMode(), t.renderMode());
            case RectElement r -> new RectElement(newId,
                    r.x(), r.y(), r.w(), r.h(), r.rotation(), r.locked(), r.visible(),
                    r.fill(), r.stroke(),
                    r.opacity(), r.blendMode(), r.renderMode());
            case IconElement ic -> new IconElement(newId,
                    ic.x(), ic.y(), ic.w(), ic.h(), ic.rotation(), ic.locked(), ic.visible(),
                    ic.source(), ic.tint(),
                    ic.opacity(), ic.blendMode(), ic.renderMode());
            case PathElement p -> new PathElement(newId,
                    p.x(), p.y(), p.w(), p.h(), p.rotation(), p.locked(), p.visible(),
                    p.d(), p.fill(), p.stroke(), p.markerStart(), p.markerEnd(),
                    p.opacity(), p.blendMode(), p.renderMode());
            case CircleElement c -> new CircleElement(newId,
                    c.x(), c.y(), c.w(), c.h(), c.rotation(), c.locked(), c.visible(),
                    c.fill(), c.stroke(),
                    c.opacity(), c.blendMode(), c.renderMode());
            case ShapeElement sh -> new ShapeElement(newId,
                    sh.x(), sh.y(), sh.w(), sh.h(), sh.rotation(), sh.locked(), sh.visible(),
                    sh.kind(), sh.sides(), sh.innerRatio(),
                    sh.fill(), sh.stroke(),
                    sh.opacity(), sh.blendMode(), sh.renderMode());
        };
    }

    // ---------- 校验 helpers ----------

    private static boolean isValidColor(String s) {
        return s != null && COLOR_RE.matcher(s).matches();
    }

    private static void validateColor(String s) {
        if (!isValidColor(s)) throw new ValidationException("INVALID_PAYLOAD", "invalid color: " + s);
    }

    /**
     * M11：把 fill 字段 raw value（{@code Map} / {@code String} / {@code null}）解析为
     * {@link Fill}，并跑 {@link FillValidator} 校验。
     *
     * <ul>
     *   <li>{@code null} → 返回 {@code null}（空心 / 仅描边）</li>
     *   <li>{@code String} → {@link SolidFill}（向后兼容 M10 及以前的形态）</li>
     *   <li>{@code Map} → 走 {@link FillDeserializer}（{@code "type"} 字段决定子类）</li>
     * </ul>
     */
    private static Fill parseFillNullable(Object raw) {
        if (raw == null) return null;
        Fill fill;
        if (raw instanceof String s) {
            fill = new SolidFill(s);
        } else if (raw instanceof Map<?, ?> m) {
            try {
                fill = FILL_MAPPER.convertValue(m, Fill.class);
            } catch (IllegalArgumentException e) {
                throw new ValidationException("INVALID_PAYLOAD",
                        "invalid fill: " + e.getMessage());
            }
        } else {
            throw new ValidationException("INVALID_PAYLOAD",
                    "fill must be string or object");
        }
        FillValidator.validate(fill);
        return fill;
    }

    private static void validateRotation(int r) {
        if (r < 0 || r >= 360) {
            throw new ValidationException("INVALID_PAYLOAD", "rotation must be in [0,360): " + r);
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

    private static void validateLetterSpacing(float v) {
        if (!Float.isFinite(v) || v < MIN_LETTER_SPACING || v > MAX_LETTER_SPACING) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "letterSpacing out of range [" + MIN_LETTER_SPACING + ", " + MAX_LETTER_SPACING + "]: " + v);
        }
    }

    private static void validateLineHeight(float v) {
        if (!Float.isFinite(v) || v < MIN_LINE_HEIGHT || v > MAX_LINE_HEIGHT) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "lineHeight out of range [" + MIN_LINE_HEIGHT + ", " + MAX_LINE_HEIGHT + "]: " + v);
        }
    }

    /** v2 element 字段：null = 清除（element 用默认 1.0）。 */
    private static Float parseOpacityNullable(Object v) {
        if (v == null) return null;
        float f = floatValue(v, "opacity");
        if (!Float.isFinite(f) || f < 0f || f > 1f) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "opacity must be in [0,1]: " + f);
        }
        return f;
    }

    /** v2 element 字段：null = 清除（element 用默认 normal）。layer.* op 不允 null。 */
    private static BlendMode parseBlendModeNullable(Object v) {
        if (v == null) return null;
        return parseBlendMode(v);
    }

    private static BlendMode parseBlendMode(Object v) {
        if (!(v instanceof String s)) {
            throw new ValidationException("INVALID_PAYLOAD", "blendMode must be string");
        }
        return switch (s.toLowerCase()) {
            case "normal" -> BlendMode.NORMAL;
            case "multiply" -> BlendMode.MULTIPLY;
            case "screen" -> BlendMode.SCREEN;
            case "overlay" -> BlendMode.OVERLAY;
            default -> throw new ValidationException("INVALID_PAYLOAD",
                    "unknown blendMode: " + s);
        };
    }

    private static RenderMode parseRenderModeNullable(Object v) {
        if (v == null) return null;
        if (!(v instanceof String s)) {
            throw new ValidationException("INVALID_PAYLOAD", "renderMode must be string");
        }
        return switch (s.toLowerCase()) {
            case "clean" -> RenderMode.CLEAN;
            case "dither" -> RenderMode.DITHER;
            default -> throw new ValidationException("INVALID_PAYLOAD",
                    "unknown renderMode: " + s);
        };
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

    private static float floatFieldOrDefault(Map<String, Object> m, String k, float def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (!(v instanceof Number n)) {
            throw new ValidationException("INVALID_PAYLOAD", k + " must be number");
        }
        return n.floatValue();
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

    private static float floatValue(Object v, String key) {
        if (!(v instanceof Number n)) {
            throw new ValidationException("INVALID_PAYLOAD", key + " must be number");
        }
        return n.floatValue();
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

    // ValidationException：M11 提取为 top-level 同包类（让 FillValidator 共用），见 ValidationException.java。

}
