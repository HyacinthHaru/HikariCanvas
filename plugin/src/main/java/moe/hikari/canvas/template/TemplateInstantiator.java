package moe.hikari.canvas.template;

import moe.hikari.canvas.state.Effects;
import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.ElementValidator;
import moe.hikari.canvas.state.Fill;
import moe.hikari.canvas.state.Glow;
import moe.hikari.canvas.state.IconElement;
import moe.hikari.canvas.state.RectElement;
import moe.hikari.canvas.state.Shadow;
import moe.hikari.canvas.state.SolidFill;
import moe.hikari.canvas.state.StrictNumber;
import moe.hikari.canvas.state.Stroke;
import moe.hikari.canvas.state.TextElement;
import moe.hikari.canvas.state.ValidationException;
import moe.hikari.canvas.template.expr.Expr;
import moe.hikari.canvas.template.expr.ExpressionEvaluator;
import moe.hikari.canvas.template.expr.ExpressionParser;
import moe.hikari.canvas.template.expr.Interpolator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 模板 → {@link moe.hikari.canvas.state.ProjectState} 内容的实例化引擎。
 * 契约见 {@code docs/template-spec.md §7}。
 *
 * <p><b>纯函数语义：</b> 不碰 Bukkit / DB / Session；输入 {@link TemplateSpec} +
 * 用户参数 + 目标 wall 尺寸（已由 {@link moe.hikari.canvas.session.Session} 确定），
 * 输出 {@code Result.Ok}（背景色 + element list）或 {@code Result.Failed}（错误码 + 详情）。
 * WS 层（M6-D）在主线程拿到结果后走 EditSession {@code replace}。</p>
 *
 * <p><b>M6 v1 实施范围：</b></p>
 * <ul>
 *   <li>layout: {@code stack} + {@code free}（{@code grid} 推迟 M7）</li>
 *   <li>element: {@code text} + {@code rect}（{@code line} 占位允许，但 v1 不渲染→
 *       materialize 时返回 null 跳过；{@code icon} 已在 loader 拒绝）</li>
 *   <li>{@code w}/{@code h} 支持 int / "auto" / "N%" / "${param}"</li>
 *   <li>{@code visible} + {@code visible_when} 都求值，AND 入 element.visible</li>
 *   <li>不可见元素直接 skip（不写入 ProjectState，避免"隐形僵尸"）</li>
 * </ul>
 *
 * <p><b>线程安全：</b> 实例本身无状态字段（只装无状态的 Parser/Evaluator），多线程并发
 * 调 {@code instantiate} 安全。</p>
 */
public final class TemplateInstantiator {

    private static final Pattern PERCENT = Pattern.compile("^(-?\\d+(?:\\.\\d+)?)%$");
    private static final Pattern INT_NUMERIC = Pattern.compile("^-?\\d+$");
    private static final Pattern COLOR_RE =
            Pattern.compile("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$");

    private final ExpressionParser exprParser = new ExpressionParser();
    private final ExpressionEvaluator exprEval = new ExpressionEvaluator();

    /**
     * 实例化结果。失败时 {@code errors} 给出全部累计校验问题。
     *
     * <p>M17 F5：{@code backgroundColor} 字段语义未变（仍是 hex 字符串），非 raw_state
     * 模板的 {@code resolveBackground} 仍输出 hex；raw_state 模式下从 {@link Fill}
     * 抽 {@link moe.hikari.canvas.state.SolidFill#color()}，非 solid 形态退默认 {@code "#FFFFFF"}。
     * v1 模板谱系不支持渐变背景；要做需扩展 template-spec 接口。</p>
     */
    public sealed interface Result {
        record Ok(int widthMaps, int heightMaps, String backgroundColor,
                  List<Element> elements,
                  Map<String, Object> resolvedParams) implements Result {}

        record Failed(String code, List<String> errors) implements Result {}
    }

    /** 画布几何 + 内容区。padding 已展开为 4 元数组（top/right/bottom/left）。 */
    private record Canvas(int widthMaps, int heightMaps,
                          int canvasW, int canvasH,
                          int contentX, int contentY,
                          int contentW, int contentH) {
    }

    public Result instantiate(TemplateSpec spec,
                              Map<String, Object> userInput,
                              int wallWidthMaps,
                              int wallHeightMaps) {
        if (spec == null) {
            return new Result.Failed("INVALID_TEMPLATE", List.of("spec is null"));
        }
        if (spec.isRawStateMode()) {
            return instantiateRawState(spec, userInput, wallWidthMaps, wallHeightMaps);
        }

        List<String> errors = new ArrayList<>();

        // 1) 参数校验 + 默认值填充
        Map<String, Object> params = validateParams(spec.params(), userInput, errors);
        if (!errors.isEmpty()) {
            return new Result.Failed("INVALID_PARAM", errors);
        }

        // 2) 画布尺寸适配
        Canvas canvas = fitCanvas(spec.canvas(), wallWidthMaps, wallHeightMaps, errors);
        if (canvas == null) {
            return new Result.Failed("CANVAS_MISMATCH", errors);
        }

        // 3) 背景色（支持 ${param}）
        String bg = resolveBackground(spec.canvas(), params, errors);
        if (!errors.isEmpty()) {
            return new Result.Failed("INVALID_VALUE", errors);
        }

        // 4) 布局 + 物化
        List<Element> elements;
        try {
            elements = layoutAndMaterialize(spec.layout(), canvas, params);
        } catch (InstantiationException ie) {
            errors.add(ie.getMessage());
            return new Result.Failed(ie.code, errors);
        } catch (IllegalArgumentException iae) {
            // M16 P1.5：Interpolator 长度上限触发——IAE 透过 interp() helper 上抛
            errors.add(iae.getMessage());
            return new Result.Failed("INVALID_TEMPLATE", errors);
        }

        return new Result.Ok(wallWidthMaps, wallHeightMaps, bg, elements, params);
    }

    // ==================== M14 raw state 模式 ====================

    /**
     * 创意工坊模板（{@link TemplateSpec#rawState} 非空）的实例化路径。
     *
     * <p>流程：参数校验 → 深拷贝 rawState → 遍历 Map 把所有 String 字段中的
     * {@code "${paramId}"} 替换为 params 实际值（text 类型直接 toString）→
     * 用 Jackson 反序列化为 {@link moe.hikari.canvas.state.ProjectState} → 平铺 layers
     * 内所有 element 为 List。</p>
     *
     * <p>{@code layers / canvas} 在 rawState 内自带，wallWidthMaps / wallHeightMaps 仅记入
     * Result（不用于 fit/scale —— v1 raw 模式忽略目标 wall 尺寸差异，原样 apply）。</p>
     */
    @SuppressWarnings("unchecked")
    private Result instantiateRawState(TemplateSpec spec, Map<String, Object> userInput,
                                       int wallWidthMaps, int wallHeightMaps) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> params = validateParams(spec.params(), userInput, errors);
        if (!errors.isEmpty()) {
            return new Result.Failed("INVALID_PARAM", errors);
        }

        // 深拷贝 rawState（避免污染 spec 单例）
        Map<String, Object> raw;
        try {
            raw = deepCopyMap(spec.rawState());
        } catch (IllegalArgumentException iae) {
            errors.add(iae.getMessage());
            return new Result.Failed("INVALID_TEMPLATE", errors);
        }

        // 遍历替换字符串中的 ${paramId} 占位符
        Object replaced;
        try {
            replaced = replacePlaceholders(raw, params);
        } catch (Interpolator.MissingParamException mpe) {
            // P2-65：raw_state 内引用了未声明 / 未填值的 param。与非 raw 路径
            // resolveBackground 对齐，回 INVALID_PARAM（含缺失 param 名）而非让
            // RuntimeException 逃逸到 WS dispatcher 变成 ack_timeout。
            errors.add("rawState references missing param '" + mpe.paramName() + "'");
            return new Result.Failed("INVALID_PARAM", errors);
        } catch (IllegalArgumentException iae) {
            // M16 P1.5：Interpolator 长度上限被触发（单参数 / 累计输出）
            errors.add(iae.getMessage());
            return new Result.Failed("INVALID_TEMPLATE", errors);
        }
        if (!(replaced instanceof Map<?, ?> rawMap)) {
            errors.add("rawState root must be object");
            return new Result.Failed("INVALID_TEMPLATE", errors);
        }

        // Jackson 反序列化为 ProjectState
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        moe.hikari.canvas.state.ProjectState state;
        try {
            state = mapper.convertValue(rawMap, moe.hikari.canvas.state.ProjectState.class);
        } catch (Exception e) {
            errors.add("rawState deserialize: " + e.getMessage());
            return new Result.Failed("INVALID_TEMPLATE", errors);
        }

        // M17 F5：state.canvas().background() 现在是 Fill；raw_state 模板若是渐变背景，
        // v1 不支持向 template.apply.Result.Ok 透传——抽 SolidFill.color 或退默认。
        moe.hikari.canvas.state.Fill bgFill = state.canvas().background();
        String bg = bgFill instanceof moe.hikari.canvas.state.SolidFill sf ? sf.color() : "#FFFFFF";
        List<Element> flat = new ArrayList<>();
        for (var layer : state.layers()) {
            for (Element el : layer.elements()) {
                // M15.4 P0-23：raw_state 反序列化得到的 element 跑安全校验，
                // 避免 canvas.template.save 玩家通过模板注入畸形 element（path / image source / mask 等）。
                try {
                    moe.hikari.canvas.state.EditSession.validateElementForTemplateApply(el);
                } catch (moe.hikari.canvas.state.ValidationException ve) {
                    errors.add("template element invalid: " + ve.getMessage());
                    return new Result.Failed("INVALID_TEMPLATE", errors);
                }
                // 给 element 新 id，避免与目标 wall 现有 id 冲突
                flat.add(cloneElementWithFreshId(el));
            }
        }
        return new Result.Ok(wallWidthMaps, wallHeightMaps, bg, flat, params);
    }

    /** 把节点中所有 String 内的 "${paramId}" 替换为 params 对应值的 toString()。 */
    @SuppressWarnings("unchecked")
    private static Object replacePlaceholders(Object node, Map<String, Object> params) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> map = (Map<String, Object>) m;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                e.setValue(replacePlaceholders(e.getValue(), params));
            }
            return map;
        }
        if (node instanceof List<?> list) {
            List<Object> rawList = (List<Object>) list;
            for (int i = 0; i < rawList.size(); i++) {
                rawList.set(i, replacePlaceholders(rawList.get(i), params));
            }
            return rawList;
        }
        if (node instanceof String s) {
            return Interpolator.interpolate(s, params);
        }
        return node;
    }

    /**
     * M16 P1.5：raw_state 嵌套深度上限。Map / List 任一层嵌套累计超过此值抛
     * {@link IllegalArgumentException}。32 足以覆盖现实模板（典型 ProjectState
     * 深度 ≤ 6：layers[].elements[].effects.stroke），同时挡住恶意指数嵌套触发
     * 解析期 StackOverflow / 内存爆炸。
     */
    private static final int MAX_DEEP_COPY_DEPTH = 32;

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepCopyMap(Map<String, Object> src) {
        return deepCopyMap(src, 0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<String, Object> src, int depth) {
        if (depth > MAX_DEEP_COPY_DEPTH) {
            throw new IllegalArgumentException(
                    "template structure too deep (depth > " + MAX_DEEP_COPY_DEPTH + ")");
        }
        Map<String, Object> dst = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : src.entrySet()) {
            dst.put(e.getKey(), deepCopyValue(e.getValue(), depth + 1));
        }
        return dst;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object v, int depth) {
        if (depth > MAX_DEEP_COPY_DEPTH) {
            throw new IllegalArgumentException(
                    "template structure too deep (depth > " + MAX_DEEP_COPY_DEPTH + ")");
        }
        if (v instanceof Map<?, ?> m) return deepCopyMap((Map<String, Object>) m, depth);
        if (v instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(deepCopyValue(item, depth + 1));
            return copy;
        }
        return v;
    }

    /** 给 element 一个新的 e-<uuid> id，避免 apply 时与 wall 中现有 element id 冲突。 */
    private static Element cloneElementWithFreshId(Element el) {
        String newId = "e-" + UUID.randomUUID();
        return switch (el) {
            case TextElement t -> new TextElement(newId, t.x(), t.y(), t.w(), t.h(),
                    t.rotation(), t.locked(), t.visible(), t.text(), t.fontId(), t.fontSize(),
                    t.color(), t.align(), t.letterSpacing(), t.lineHeight(), t.vertical(),
                    t.effects(), t.opacity(), t.blendMode(), t.renderMode(),
                    t.bold(), t.italic());
            case RectElement r -> new RectElement(newId, r.x(), r.y(), r.w(), r.h(),
                    r.rotation(), r.locked(), r.visible(), r.fill(), r.stroke(),
                    r.opacity(), r.blendMode(), r.renderMode());
            case IconElement ic -> new IconElement(newId, ic.x(), ic.y(), ic.w(), ic.h(),
                    ic.rotation(), ic.locked(), ic.visible(), ic.source(), ic.tint(),
                    ic.opacity(), ic.blendMode(), ic.renderMode(), ic.fill());
            case moe.hikari.canvas.state.PathElement p -> new moe.hikari.canvas.state.PathElement(newId,
                    p.x(), p.y(), p.w(), p.h(), p.rotation(), p.locked(), p.visible(),
                    p.d(), p.fill(), p.stroke(), p.markerStart(), p.markerEnd(),
                    p.opacity(), p.blendMode(), p.renderMode());
            case moe.hikari.canvas.state.CircleElement c -> new moe.hikari.canvas.state.CircleElement(newId,
                    c.x(), c.y(), c.w(), c.h(), c.rotation(), c.locked(), c.visible(),
                    c.fill(), c.stroke(), c.opacity(), c.blendMode(), c.renderMode());
            case moe.hikari.canvas.state.ShapeElement sh -> new moe.hikari.canvas.state.ShapeElement(newId,
                    sh.x(), sh.y(), sh.w(), sh.h(), sh.rotation(), sh.locked(), sh.visible(),
                    sh.kind(), sh.sides(), sh.innerRatio(), sh.fill(), sh.stroke(),
                    sh.opacity(), sh.blendMode(), sh.renderMode());
            case moe.hikari.canvas.state.BrushStrokeElement b -> new moe.hikari.canvas.state.BrushStrokeElement(newId,
                    b.x(), b.y(), b.w(), b.h(), b.rotation(), b.locked(), b.visible(),
                    b.points(), b.size(), b.fill(), b.pressureSize(), b.pressureOpacity(),
                    b.opacity(), b.blendMode(), b.renderMode());
            case moe.hikari.canvas.state.ImageElement im -> new moe.hikari.canvas.state.ImageElement(newId,
                    im.x(), im.y(), im.w(), im.h(), im.rotation(), im.locked(), im.visible(),
                    im.source(), im.mask(), im.opacity(), im.blendMode(), im.renderMode());
        };
    }

    // ==================== 1. 参数校验 ====================

    private static Map<String, Object> validateParams(
            Map<String, TemplateParam> declared,
            Map<String, Object> userInput,
            List<String> errors) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (declared == null) declared = Map.of();
        if (userInput == null) userInput = Map.of();

        for (var entry : declared.entrySet()) {
            String key = entry.getKey();
            TemplateParam p = entry.getValue();
            boolean userSupplied = userInput.containsKey(key);
            Object raw = userSupplied ? userInput.get(key) : p.defaultValue();
            if (raw == null) {
                if (p.required()) {
                    errors.add("param '" + key + "' is required");
                }
                out.put(key, null);
                continue;
            }
            Object value = coerceAndValidate(key, p, raw, errors);
            out.put(key, value);
        }
        return out;
    }

    private static Object coerceAndValidate(String key, TemplateParam p,
                                            Object raw, List<String> errors) {
        switch (p.type()) {
            case "string", "text" -> {
                String s = raw.toString();
                if (p.minLength() != null && s.length() < p.minLength()) {
                    errors.add("param '" + key + "' length < " + p.minLength());
                }
                if (p.maxLength() != null && s.length() > p.maxLength()) {
                    errors.add("param '" + key + "' length > " + p.maxLength());
                }
                if (p.pattern() != null) {
                    try {
                        if (!Pattern.compile(p.pattern()).matcher(s).matches()) {
                            errors.add("param '" + key + "' doesn't match pattern " + p.pattern());
                        }
                    } catch (Exception e) {
                        errors.add("param '" + key + "' pattern invalid: " + e.getMessage());
                    }
                }
                return s;
            }
            case "int" -> {
                Integer i = asInt(raw);
                if (i == null) {
                    errors.add("param '" + key + "' is not int: " + raw);
                    return 0;
                }
                if (p.min() != null && i < p.min()) errors.add("param '" + key + "' < min");
                if (p.max() != null && i > p.max()) errors.add("param '" + key + "' > max");
                return i;
            }
            case "float" -> {
                Double d = asDouble(raw);
                if (d == null) {
                    errors.add("param '" + key + "' is not float: " + raw);
                    return 0.0;
                }
                if (p.min() != null && d < p.min()) errors.add("param '" + key + "' < min");
                if (p.max() != null && d > p.max()) errors.add("param '" + key + "' > max");
                return d;
            }
            case "bool" -> {
                if (raw instanceof Boolean b) return b;
                if (raw instanceof String s) {
                    if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
                    if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
                }
                errors.add("param '" + key + "' is not bool: " + raw);
                return Boolean.FALSE;
            }
            case "color" -> {
                String s = raw.toString();
                if (!COLOR_RE.matcher(s).matches()) {
                    errors.add("param '" + key + "' invalid color: " + s);
                }
                return s;
            }
            case "enum" -> {
                String s = raw.toString();
                if (p.options() != null) {
                    boolean ok = p.options().stream().anyMatch(
                            o -> String.valueOf(o.value()).equals(s));
                    if (!ok) errors.add("param '" + key + "' not in enum options: " + s);
                }
                return s;
            }
            case "font" -> {
                return raw.toString();
            }
            default -> {
                errors.add("param '" + key + "' has unknown type: " + p.type());
                return raw;
            }
        }
    }

    // ==================== 2. 画布尺寸 ====================

    private static Canvas fitCanvas(TemplateCanvas tc,
                                    int wallW, int wallH,
                                    List<String> errors) {
        if (tc == null) {
            errors.add("template has no canvas block");
            return null;
        }
        String size = tc.size() == null ? "auto" : tc.size();
        if ("fixed".equals(size)) {
            if (tc.maps() == null || tc.maps().size() != 2
                    || tc.maps().get(0) != wallW
                    || tc.maps().get(1) != wallH) {
                errors.add("template canvas.size=fixed maps=" + tc.maps()
                        + " does not match wall " + wallW + "x" + wallH);
                return null;
            }
        } else {
            // auto
            List<Integer> min = tc.minMaps() == null ? List.of(1, 1) : tc.minMaps();
            List<Integer> max = tc.maxMaps() == null ? List.of(8, 4) : tc.maxMaps();
            if (wallW < min.get(0) || wallH < min.get(1)) {
                errors.add("wall " + wallW + "x" + wallH + " smaller than template min_maps " + min);
                return null;
            }
            if (wallW > max.get(0) || wallH > max.get(1)) {
                errors.add("wall " + wallW + "x" + wallH + " larger than template max_maps " + max);
                return null;
            }
        }
        int canvasW = wallW * 128;
        int canvasH = wallH * 128;
        int[] pad = normalizePadding(tc.padding());
        int top = pad[0], right = pad[1], bottom = pad[2], left = pad[3];
        int contentX = left;
        int contentY = top;
        int contentW = Math.max(0, canvasW - left - right);
        int contentH = Math.max(0, canvasH - top - bottom);
        return new Canvas(wallW, wallH, canvasW, canvasH,
                contentX, contentY, contentW, contentH);
    }

    private static int[] normalizePadding(Object padding) {
        if (padding == null) return new int[]{0, 0, 0, 0};
        if (padding instanceof Number n) {
            int v = n.intValue();
            return new int[]{v, v, v, v};
        }
        if (padding instanceof List<?> list && list.size() == 4) {
            int[] out = new int[4];
            for (int i = 0; i < 4; i++) {
                out[i] = ((Number) list.get(i)).intValue();
            }
            return out;
        }
        return new int[]{0, 0, 0, 0};
    }

    // ==================== 3. 背景色 ====================

    private static String resolveBackground(TemplateCanvas tc, Map<String, Object> params,
                                            List<String> errors) {
        String bg = tc != null ? tc.background() : null;
        if (bg == null) return "#FFFFFF";
        String resolved;
        try {
            resolved = Interpolator.interpolate(bg, params);
        } catch (Interpolator.MissingParamException e) {
            errors.add("canvas.background references missing param '" + e.paramName() + "'");
            return "#FFFFFF";
        }
        if (!COLOR_RE.matcher(resolved).matches()) {
            errors.add("canvas.background resolved to non-color: " + resolved);
        }
        return resolved;
    }

    // ==================== 4. 布局 + 物化 ====================

    /** 单内部 checked exception，串起 layout 错误向 result 上抛。 */
    private static final class InstantiationException extends RuntimeException {
        final String code;
        InstantiationException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private List<Element> layoutAndMaterialize(TemplateLayout layout,
                                               Canvas canvas,
                                               Map<String, Object> params) {
        if (layout == null || layout.elements() == null) return List.of();
        String type = layout.type();
        return switch (type) {
            case "stack" -> stackLayout(layout, canvas, params);
            case "free" -> freeLayout(layout, canvas, params);
            case "grid" -> gridLayout(layout, canvas, params);
            default -> throw new InstantiationException(
                    "INVALID_LAYOUT", "unsupported layout.type: " + type);
        };
    }

    /**
     * grid 布局：把 elements 按 {@code columns × rows} 网格平铺到 content 区。
     * 每个 cell 尺寸 = (contentW - (col-1)·gap) / col 等；多余 elements 落到下一页则截断
     * （v1 不支持多页，超过 col×row 个的元素直接 skip 并 warn）。每个 cell 内元素居中、
     * 撑满 cell 减去内边距。
     */
    private List<Element> gridLayout(TemplateLayout layout, Canvas canvas,
                                     Map<String, Object> params) {
        int cols = layout.columns() != null ? layout.columns() : 1;
        int rows = layout.rows() != null ? layout.rows() : 1;
        int gap = layout.gap() == null ? 0 : layout.gap();
        // 总内距：每行有 (cols-1) 个 gap；每列 (rows-1) 个 gap
        int cellW = (canvas.contentW - (cols - 1) * gap) / Math.max(1, cols);
        int cellH = (canvas.contentH - (rows - 1) * gap) / Math.max(1, rows);
        List<Element> out = new ArrayList<>();
        int idx = 0;
        for (TemplateElement el : layout.elements()) {
            if (!isVisible(el, params)) { idx++; continue; }
            int row = idx / cols;
            int col = idx % cols;
            if (row >= rows) break;  // v1：超容量直接截断
            int cellX = canvas.contentX + col * (cellW + gap);
            int cellY = canvas.contentY + row * (cellH + gap);
            Element materialized = materialize(el, cellX, cellY, cellW, cellH, params);
            if (materialized != null) out.add(materialized);
            idx++;
        }
        return out;
    }

    /** stack 布局：忽略每个 element 自带的 x/y，按 direction 累加 + gap。w 撑满 content。 */
    private List<Element> stackLayout(TemplateLayout layout, Canvas canvas,
                                      Map<String, Object> params) {
        List<Element> out = new ArrayList<>();
        boolean vertical = !"horizontal".equals(layout.direction());
        int gap = layout.gap() == null ? 0 : layout.gap();
        int cursorX = canvas.contentX;
        int cursorY = canvas.contentY;
        for (TemplateElement el : layout.elements()) {
            if (!isVisible(el, params)) continue;
            int w = vertical ? canvas.contentW : naturalWidth(el, params, canvas);
            int h = vertical ? naturalHeight(el, params) : canvas.contentH;
            Element materialized = materialize(el, cursorX, cursorY, w, h, params);
            if (materialized == null) continue;
            out.add(materialized);
            if (vertical) cursorY += h + gap;
            else cursorX += w + gap;
        }
        return out;
    }

    /** free 布局：x/y/w/h 直接从 element 取，% 按 content 区计算。 */
    private List<Element> freeLayout(TemplateLayout layout, Canvas canvas,
                                     Map<String, Object> params) {
        List<Element> out = new ArrayList<>();
        for (TemplateElement el : layout.elements()) {
            if (!isVisible(el, params)) continue;
            int x = resolveDimension(el.x(), canvas.contentW, params, 0);
            int y = resolveDimension(el.y(), canvas.contentH, params, 0);
            int w = resolveDimensionWithAuto(el.w(), canvas.contentW, params,
                    () -> naturalWidth(el, params, canvas));
            int h = resolveDimensionWithAuto(el.h(), canvas.contentH, params,
                    () -> naturalHeight(el, params));
            Element materialized = materialize(el, x, y, w, h, params);
            if (materialized == null) continue;
            out.add(materialized);
        }
        return out;
    }

    private boolean isVisible(TemplateElement el, Map<String, Object> params) {
        // 顶层 visible 字段
        if (el.visible() != null) {
            Object v = el.visible();
            if (v instanceof Boolean b && !b) return false;
            if (v instanceof String s) {
                try {
                    String resolved = Interpolator.interpolate(s, params);
                    if ("false".equalsIgnoreCase(resolved.trim())) return false;
                } catch (Interpolator.MissingParamException ignored) {
                    return false;
                }
            }
        }
        // visible_when 条件表达式
        if (el.visibleWhen() != null && !el.visibleWhen().isBlank()) {
            try {
                Expr expr = exprParser.parse(el.visibleWhen());
                if (!exprEval.evalBoolean(expr, params)) return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private int naturalHeight(TemplateElement el, Map<String, Object> params) {
        if (el instanceof TemplateElement.Text t) {
            int size = t.size() == null ? 24 : t.size();
            double lh = t.lineHeight() == null ? 1.2 : t.lineHeight();
            String content;
            try {
                content = Interpolator.interpolate(t.content() == null ? "" : t.content(), params);
            } catch (Interpolator.MissingParamException e) {
                content = "";
            }
            int lines = countLines(content);
            return (int) Math.ceil(size * lh * lines);
        }
        if (el instanceof TemplateElement.Rect r) {
            // rect 在 stack 中没有"自然高度"——必须显式
            Integer h = asInt(r.h());
            if (h != null) return h;
            throw new InstantiationException(
                    "INVALID_LAYOUT", "rect element in stack layout needs explicit h");
        }
        if (el instanceof TemplateElement.Line l) {
            return l.width() == null ? 1 : l.width();
        }
        if (el instanceof TemplateElement.Icon ic) {
            Integer h = asInt(ic.h());
            return h != null ? h : 32;  // 默认 32×32（最常用图标尺寸）
        }
        return 0;
    }

    private int naturalWidth(TemplateElement el, Map<String, Object> params, Canvas canvas) {
        // stack 横向时才用；text 取近似（ASCII=0.5×size, CJK=1×size）；rect/line 必须显式
        if (el instanceof TemplateElement.Text t) {
            int size = t.size() == null ? 24 : t.size();
            String content;
            try {
                content = Interpolator.interpolate(t.content() == null ? "" : t.content(), params);
            } catch (Interpolator.MissingParamException e) {
                content = "";
            }
            // 取最宽行
            int max = 0;
            int cur = 0;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '\n') { max = Math.max(max, cur); cur = 0; continue; }
                cur += (c < 128) ? (size / 2) : size;
            }
            max = Math.max(max, cur);
            return Math.min(max, canvas.contentW);
        }
        if (el instanceof TemplateElement.Rect r) {
            Integer w = asInt(r.w());
            if (w != null) return w;
            throw new InstantiationException(
                    "INVALID_LAYOUT", "rect element in stack-horizontal needs explicit w");
        }
        if (el instanceof TemplateElement.Line l) {
            return l.width() == null ? 1 : l.width();
        }
        if (el instanceof TemplateElement.Icon ic) {
            Integer w = asInt(ic.w());
            return w != null ? w : 32;
        }
        return 0;
    }

    private static int countLines(String s) {
        if (s.isEmpty()) return 1;
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    /**
     * 解析 x/y 这种没有"auto"语义的维度。返回 int。null/无法解析 → fallback。
     *
     * <p>C2-P2-12 修：</p>
     * <ul>
     *   <li>INT_NUMERIC 匹配超 int 范围的字符串（如 {@code "99999999999"}）时，
     *       旧路径直接 {@code Integer.parseInt} 抛 {@link NumberFormatException}，
     *       被外层 {@link IllegalArgumentException} catch 接住并把 JDK 原始报文
     *       透传给客户端（含 "For input string: …" 格式）。现用
     *       {@link StrictNumber#clampInt} 统一钳位到 int 范围，不外泄 JDK 文案。</li>
     *   <li>百分比分支 {@code Math.round(contentBasis*pct)} 返回 {@code long}，
     *       旧的 {@code (int)} 收窄对超大 pct 静默回绕；现改用 {@code clampInt}。</li>
     * </ul>
     */
    private int resolveDimension(Object raw, int contentBasis,
                                 Map<String, Object> params, int fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try {
                s = Interpolator.interpolate(s, params);
            } catch (Interpolator.MissingParamException e) {
                return fallback;
            }
            var m = PERCENT.matcher(s);
            if (m.matches()) {
                double pct = Double.parseDouble(m.group(1)) / 100.0;
                // C2: clampInt 防超大百分比静默回绕
                return StrictNumber.clampInt(Math.round(contentBasis * pct));
            }
            if (INT_NUMERIC.matcher(s).matches()) {
                // C2: 超 int 范围字符串走 clampInt，不外泄 NFE JDK 报文
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException nfe) {
                    // 字符串合法数字但超 int 范围（如 "99999999999"）
                    return StrictNumber.clampInt(Long.parseLong(s));
                }
            }
        }
        return fallback;
    }

    /**
     * 解析 w/h 维度：支持 "auto" 走 supplier，"N%" 按 basis，数字按数字。
     *
     * <p>C2-P2-12：同 {@link #resolveDimension} 修复超 int 回绕与 JDK 报文泄漏。</p>
     */
    private int resolveDimensionWithAuto(Object raw, int basis,
                                         Map<String, Object> params,
                                         java.util.function.IntSupplier autoFallback) {
        if (raw == null) return autoFallback.getAsInt();
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try {
                s = Interpolator.interpolate(s, params);
            } catch (Interpolator.MissingParamException e) {
                return autoFallback.getAsInt();
            }
            if ("auto".equalsIgnoreCase(s)) return autoFallback.getAsInt();
            var m = PERCENT.matcher(s);
            if (m.matches()) {
                double pct = Double.parseDouble(m.group(1)) / 100.0;
                // C2: clampInt 防超大百分比静默回绕
                return StrictNumber.clampInt(Math.round(basis * pct));
            }
            if (INT_NUMERIC.matcher(s).matches()) {
                // C2: 超 int 范围字符串走 clampInt，不外泄 NFE JDK 报文
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException nfe) {
                    return StrictNumber.clampInt(Long.parseLong(s));
                }
            }
        }
        return autoFallback.getAsInt();
    }

    /**
     * TemplateElement → state.Element 物化。line 在 v1 跳过返回 null。
     *
     * <p><b>C1-P2-11 修：</b> 常规布局路径（stack/free/grid）的物化结果现在与 raw_state
     * 路径对称，通过两层校验：</p>
     * <ol>
     *   <li>{@link ElementValidator#validateElementForTemplateApply}：坐标/尺寸/旋转/fill
     *       安全校验（与 raw_state 路径完全相同）。</li>
     *   <li>TextElement 专属字段钳位：fontSize / lineHeight / letterSpacing 超范围时
     *       <b>clamp</b>（非拒）——模板作者通常给静态字面值，钳位比报错更宽容；
     *       text content 超 {@link ElementValidator#MAX_TEXT_LEN} 时截断并抛
     *       {@link InstantiationException}，因为内容来自用户参数插值，过长通常是意外。</li>
     * </ol>
     * <p>校验失败时抛 {@link InstantiationException}（携带 {@code INVALID_TEMPLATE} 错误码），
     * 由 {@link #layoutAndMaterialize} 上层统一捕获并转为 {@code Result.Failed}。</p>
     */
    private Element materialize(TemplateElement el, int x, int y, int w, int h,
                                Map<String, Object> params) {
        String id = "e-" + UUID.randomUUID();
        Element result;
        if (el instanceof TemplateElement.Text t) {
            String content = interp(t.content() == null ? "" : t.content(), params);
            // C1: 超长 content 给友好错误（内容来自用户参数，过长是意外）
            if (content.length() > ElementValidator.MAX_TEXT_LEN) {
                throw new InstantiationException("INVALID_TEMPLATE",
                        "text content length " + content.length()
                                + " exceeds " + ElementValidator.MAX_TEXT_LEN);
            }
            String color = interp(t.color() == null ? "#000000" : t.color(), params);
            String fontId = interp(t.font() == null ? "ark_pixel" : t.font(), params);
            // C1: fontSize clamp [1, MAX_FONT_SIZE]
            int size = t.size() == null ? 24 : Math.max(1,
                    Math.min(ElementValidator.MAX_FONT_SIZE, t.size()));
            String align = t.align() == null ? "left" : t.align();
            // C1: lineHeight clamp [MIN_LINE_HEIGHT, MAX_LINE_HEIGHT]
            float lineHeight = t.lineHeight() == null ? 1.2f
                    : Math.max(ElementValidator.MIN_LINE_HEIGHT,
                            Math.min(ElementValidator.MAX_LINE_HEIGHT,
                                    t.lineHeight().floatValue()));
            // C1: letterSpacing clamp [MIN_LETTER_SPACING, MAX_LETTER_SPACING]
            float letterSpacing = t.letterSpacing() == null ? 0f
                    : Math.max(ElementValidator.MIN_LETTER_SPACING,
                            Math.min(ElementValidator.MAX_LETTER_SPACING,
                                    t.letterSpacing().floatValue()));
            boolean vertical = t.vertical() != null && t.vertical();
            Effects effects = materializeEffects(t.effects(), params);
            result = new TextElement(
                    id, x, y, w, h,
                    t.rotation(), false, true,
                    content, fontId, size, color, align,
                    letterSpacing, lineHeight, vertical, effects,
                    null, null, null,
                    null, null);
        } else if (el instanceof TemplateElement.Rect r) {
            Fill fill = r.fill() == null ? null : new SolidFill(interp(r.fill(), params));
            Stroke stroke;
            if (r.stroke() == null) {
                stroke = null;
            } else {
                // P2-36：stroke.width 支持 ${param}，先插值再解析。
                Integer sw = asIntInterp(r.stroke().width(), params);
                stroke = new Stroke(sw == null ? 1 : sw, interp(r.stroke().color(), params));
            }
            result = new RectElement(
                    id, x, y, w, h,
                    r.rotation(), false, true,
                    fill, stroke,
                    null, null, null);
        } else if (el instanceof TemplateElement.Icon ic) {
            String source = interp(ic.source(), params);
            String tint = ic.tint() == null ? null : interp(ic.tint(), params);
            // M26：模板 tint 字段语义升级——若指定 tint 则同步为 SolidFill；否则 fill=null（用 pack 默认色）
            Fill fill = (tint == null || tint.isBlank()) ? null : new SolidFill(tint);
            result = new IconElement(
                    id, x, y, w, h,
                    ic.rotation(), false, true,
                    source, tint,
                    null, null, null, fill);
        } else {
            // line: v1 不渲染，但保留 instantiate 链路以待 v2+
            return null;
        }

        // C1-P2-11: 与 raw_state 路径对称——跑 validateElementForTemplateApply 校验
        // 坐标/尺寸/旋转/fill 等字段（x/y 超范围时给友好 INVALID_TEMPLATE 错误）。
        try {
            ElementValidator.validateElementForTemplateApply(result);
        } catch (ValidationException ve) {
            throw new InstantiationException("INVALID_TEMPLATE",
                    "materialized element invalid: " + ve.getMessage());
        }
        return result;
    }

    private Effects materializeEffects(TemplateEffects te, Map<String, Object> params) {
        if (te == null) return null;
        Stroke s = null;
        Shadow sh = null;
        Glow g = null;
        if (te.stroke() != null) {
            Integer w = asIntInterp(te.stroke().width(), params);
            s = new Stroke(w == null ? 1 : w, interp(te.stroke().color(), params));
        }
        if (te.shadow() != null) {
            Integer dx = asIntInterp(te.shadow().dx(), params);
            Integer dy = asIntInterp(te.shadow().dy(), params);
            sh = new Shadow(dx == null ? 0 : dx, dy == null ? 0 : dy,
                    interp(te.shadow().color(), params));
        }
        if (te.glow() != null) {
            Integer r = asIntInterp(te.glow().radius(), params);
            g = new Glow(r == null ? 0 : r, interp(te.glow().color(), params));
        }
        return new Effects(s, sh, g);
    }

    private static String interp(String src, Map<String, Object> params) {
        if (src == null) return null;
        try {
            return Interpolator.interpolate(src, params);
        } catch (Interpolator.MissingParamException e) {
            // 默认 fallback：空串。校验阶段已 collect 过未声明引用，运行时遇到通常是
            // 必填 param 没填 + 已走到这一步 → 回到 INVALID_PARAM 路径才对，
            // 这里兜底返回原文本避免 NPE。
            return src;
        }
    }

    // ==================== 工具 ====================

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            if (INT_NUMERIC.matcher(s).matches()) {
                try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    /**
     * P2-36：先插值再 asInt 的整数解析（复用 {@link #resolveDimension} 的
     * 『String→interp→INT_NUMERIC 解析』+ MissingParam 回退模式）。TemplateEffects
     * width/dx/dy/radius 与 Rect stroke.width 声明支持 {@code ${param}}，但 {@link #asInt}
     * 直接对 {@code "${n}"} 返 null 会被静默当 0/默认；本 helper 先把 String 中的占位符
     * 替换为实际值再解析，使参数化尺寸真正生效。缺值时与 {@link #resolveDimension} 一致
     * 返 null 让上层走默认。
     */
    private static Integer asIntInterp(Object o, Map<String, Object> params) {
        if (o instanceof String s) {
            try {
                return asInt(Interpolator.interpolate(s, params));
            } catch (Interpolator.MissingParamException e) {
                return null;
            }
        }
        return asInt(o);
    }

    private static Double asDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
