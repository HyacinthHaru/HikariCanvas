package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 元素字段级 sanity 校验与 Map → typed-value 提取的纯静态 helper 集中处。
 *
 * <p>从原 {@code EditSession} god class 抽出（2026-05-14 重构）。所有方法均 {@code static}、
 * 无实例状态；外部调用方包括：</p>
 * <ul>
 *   <li>{@link EditSession} 自己的 buildXxx / applyXxxPatch / brush op</li>
 *   <li>{@link BrushSession}（M12 brush op 状态机）</li>
 *   <li>{@link LayerOperations}（layer.* op，guide / blendMode 校验）</li>
 *   <li>{@code moe.hikari.canvas.template.TemplateInstantiator}（raw_state 二次校验）</li>
 * </ul>
 *
 * <p>抛出统一为 {@link ValidationException}，外层 op handler 接住并转为
 * {@code OpResult.Error(code, message)} 下行。</p>
 *
 * <p><b>规则：</b>此类只校验"字段格式与范围 sanity"——业务不变式（跨 session 排他、
 * 池容量等）由 SessionManager / MapPool 负责；这里不动 {@link ProjectState}。</p>
 */
public final class ElementValidator {

    private ElementValidator() {}

    // ---------- 校验常量 ----------

    /** 6/8 位十六进制颜色（带可选 alpha）。 */
    public static final Pattern COLOR_RE =
            Pattern.compile("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$");

    /** sha256[:16] lowercase hex；image element source 内容寻址。 */
    public static final Pattern IMAGE_SOURCE_RE =
            Pattern.compile("^[0-9a-f]{16}$");

    /** mask path d 字符串内数字 token 抽取（v1 仅约束坐标 ≤ 10000）。 */
    public static final Pattern MASK_NUMBER_RE =
            Pattern.compile("-?\\d+(?:\\.\\d+)?");

    /** M11：fill object（含 type 字段）→ {@link Fill} 子类映射的共享 ObjectMapper。 */
    public static final ObjectMapper FILL_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public static final int MAX_TEXT_LEN = 256;
    public static final int MAX_COORD = 10_000;
    public static final int MAX_DIM = 10_000;
    public static final int MAX_FONT_SIZE = 512;
    public static final int MAX_STROKE_WIDTH = 128;
    /** M15.3 P0-10：mask path 最大 vertex 数。 */
    public static final int MAX_MASK_VERTICES = 64;
    public static final float MIN_LETTER_SPACING = -32f;
    public static final float MAX_LETTER_SPACING = 128f;
    public static final float MIN_LINE_HEIGHT = 0.5f;
    public static final float MAX_LINE_HEIGHT = 4.0f;
    public static final int MAX_SHADOW_OFFSET = 128;
    public static final int MAX_GLOW_RADIUS = 64;

    /** ShapeElement（M9）多边形/星形参数范围。 */
    public static final int MIN_SIDES = 3;
    public static final int MAX_SIDES = 32;
    public static final float MIN_INNER_RATIO = 0.1f;
    public static final float MAX_INNER_RATIO = 0.95f;

    /** BrushStrokeElement（M12）笔刷大小范围。 */
    public static final int MIN_BRUSH_SIZE = 1;
    public static final int MAX_BRUSH_SIZE = 64;

    // ---------- M16 P3.2：non-finite 兜底 ----------

    /**
     * M16 P3.2：非 finite（NaN / ±Inf）→ {@code fallback}；否则返回原值。
     * 用于渲染层兜底所有浮点字段（rotation / opacity / stop.offset / dx / dy / blur 等）；
     * 对已存在的残破数据（极端模板 / 旧 .canvas 文件）容忍替换、不抛，避免单元素污染整 wall。
     *
     * <p>protocol 入口（{@link FillValidator} / {@link #parseOpacityNullable} 等）已经
     * reject 非 finite 值；本 helper 是渲染期的兜底，覆盖模板 raw_state 反序列化等绕过路径。</p>
     */
    public static double finiteOr(double v, double fallback) {
        return Double.isFinite(v) ? v : fallback;
    }

    public static float finiteOr(float v, float fallback) {
        return Float.isFinite(v) ? v : fallback;
    }

    // ---------- 颜色 ----------

    public static boolean isValidColor(String s) {
        return s != null && COLOR_RE.matcher(s).matches();
    }

    public static void validateColor(String s) {
        if (!isValidColor(s)) {
            throw new ValidationException("INVALID_PAYLOAD", "invalid color: " + s);
        }
    }

    // ---------- 几何 / 数值 ----------

    public static void validateRotation(int r) {
        if (r < 0 || r >= 360) {
            throw new ValidationException("INVALID_PAYLOAD", "rotation must be in [0,360): " + r);
        }
    }

    public static void validateText(String s) {
        if (s.length() > MAX_TEXT_LEN) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "text length " + s.length() + " exceeds " + MAX_TEXT_LEN);
        }
    }

    public static void validateCoord(int v, String name) {
        if (v < -MAX_COORD || v > MAX_COORD) {
            throw new ValidationException("INVALID_PAYLOAD", name + " out of range: " + v);
        }
    }

    public static void validateDim(int v, String name) {
        if (v <= 0 || v > MAX_DIM) {
            throw new ValidationException("INVALID_PAYLOAD",
                    name + " must be 1.." + MAX_DIM + ": " + v);
        }
    }

    public static void validateFontSize(int v) {
        if (v < 1 || v > MAX_FONT_SIZE) {
            throw new ValidationException("INVALID_PAYLOAD", "fontSize out of range: " + v);
        }
    }

    public static void validateAlign(String v) {
        if (!"left".equals(v) && !"center".equals(v) && !"right".equals(v)) {
            throw new ValidationException("INVALID_PAYLOAD", "invalid align: " + v);
        }
    }

    public static void validateLetterSpacing(float v) {
        if (!Float.isFinite(v) || v < MIN_LETTER_SPACING || v > MAX_LETTER_SPACING) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "letterSpacing out of range [" + MIN_LETTER_SPACING + ", "
                            + MAX_LETTER_SPACING + "]: " + v);
        }
    }

    public static void validateLineHeight(float v) {
        if (!Float.isFinite(v) || v < MIN_LINE_HEIGHT || v > MAX_LINE_HEIGHT) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "lineHeight out of range [" + MIN_LINE_HEIGHT + ", "
                            + MAX_LINE_HEIGHT + "]: " + v);
        }
    }

    // ---------- M9 shape / path ----------

    public static void validateShapeKind(String k) {
        if (!"polygon".equals(k) && !"star".equals(k)) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "shape.kind must be 'polygon' or 'star': " + k);
        }
    }

    public static void validateSides(int v) {
        if (v < MIN_SIDES || v > MAX_SIDES) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "shape.sides out of range [" + MIN_SIDES + ", " + MAX_SIDES + "]: " + v);
        }
    }

    public static void validateInnerRatio(float v) {
        if (!Float.isFinite(v) || v < MIN_INNER_RATIO || v > MAX_INNER_RATIO) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "shape.innerRatio out of range [" + MIN_INNER_RATIO + ", "
                            + MAX_INNER_RATIO + "]: " + v);
        }
    }

    public static void validatePathD(String d) {
        PathDValidator.Result r = PathDValidator.validate(d);
        if (!r.ok()) {
            throw new ValidationException("INVALID_PAYLOAD", "path.d invalid: " + r.reason());
        }
    }

    // ---------- M12 brush ----------

    public static void validateBrushSize(int v) {
        if (v < MIN_BRUSH_SIZE || v > MAX_BRUSH_SIZE) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "brush.size out of range [" + MIN_BRUSH_SIZE + ", "
                            + MAX_BRUSH_SIZE + "]: " + v);
        }
    }

    // ---------- M13 image / mask ----------

    public static void validateImageSource(String s) {
        if (s == null || !IMAGE_SOURCE_RE.matcher(s).matches()) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "image source must be sha256[:16] lowercase hex: " + s);
        }
    }

    /**
     * M15.3 P0-10：在 {@link #validatePathD} 之上对 mask path 加二次约束。
     */
    public static void validateMaskPathBounds(String d) {
        int vertexCount = 0;
        for (int i = 0, n = d.length(); i < n; i++) {
            char c = d.charAt(i);
            if (c == 'M' || c == 'L' || c == 'Q' || c == 'C'
                    || c == 'm' || c == 'l' || c == 'q' || c == 'c') {
                vertexCount++;
            }
        }
        if (vertexCount > MAX_MASK_VERTICES) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "mask.d has " + vertexCount + " vertices > " + MAX_MASK_VERTICES);
        }
        java.util.regex.Matcher m = MASK_NUMBER_RE.matcher(d);
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group());
                if (Math.abs(v) > 10_000) {
                    throw new ValidationException("INVALID_PAYLOAD",
                            "mask.d coordinate " + v + " > 10000");
                }
            } catch (NumberFormatException ignored) {
                // 正则已限定为合法数字格式
            }
        }
    }

    // ---------- 解析 helpers（raw Object → typed Fill / enum / Mask）----------

    /**
     * M11：把 fill 字段 raw value（{@code Map} / {@code String} / {@code null}）解析为
     * {@link Fill}，并跑 {@link FillValidator} 校验。
     */
    public static Fill parseFillNullable(Object raw) {
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

    public static String parseMarkerNullable(Object v) {
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

    public static Float parseOpacityNullable(Object v) {
        if (v == null) return null;
        float f = floatValue(v, "opacity");
        if (!Float.isFinite(f) || f < 0f || f > 1f) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "opacity must be in [0,1]: " + f);
        }
        return f;
    }

    public static BlendMode parseBlendModeNullable(Object v) {
        if (v == null) return null;
        return parseBlendMode(v);
    }

    public static BlendMode parseBlendMode(Object v) {
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

    public static RenderMode parseRenderModeNullable(Object v) {
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

    /**
     * 解析可选的 {@link Mask}：{@code null} → 无蒙版；非空时必须含 {@code d}（SVG path）+
     * {@code inverted}（boolean）。{@code d} 复用 {@link PathDValidator}（含 4096 长度上限）+
     * mask 二次约束（顶点数 / 坐标范围）。
     */
    public static Mask parseMaskNullable(Object v) {
        if (v == null) return null;
        if (!(v instanceof Map<?, ?> m)) {
            throw new ValidationException("INVALID_PAYLOAD", "mask must be object");
        }
        Object dRaw = m.get("d");
        if (!(dRaw instanceof String d)) {
            throw new ValidationException("INVALID_PAYLOAD", "mask.d must be string");
        }
        validatePathD(d);
        validateMaskPathBounds(d);
        Object invRaw = m.get("inverted");
        boolean inverted;
        if (invRaw == null) {
            inverted = false;
        } else if (invRaw instanceof Boolean bb) {
            inverted = bb;
        } else {
            throw new ValidationException("INVALID_PAYLOAD", "mask.inverted must be boolean");
        }
        // 2026-05-25 项 2：featherPx 可选字段。null / 缺省 / 0 = 无羽化；范围 [1, 32]
        Object fRaw = m.get("featherPx");
        Integer feather = null;
        if (fRaw != null) {
            if (!(fRaw instanceof Number fNum)) {
                throw new ValidationException("INVALID_PAYLOAD", "mask.featherPx must be number");
            }
            int fi = fNum.intValue();
            if (fi < 0 || fi > 32) {
                throw new ValidationException("INVALID_PAYLOAD",
                        "mask.featherPx out of range [0, 32]: " + fi);
            }
            feather = fi == 0 ? null : fi;
        }
        return new Mask(d, inverted, feather);
    }

    // ---------- stroke / effects 构造（纯静态，无 EditSession 依赖）----------

    public static Stroke buildStroke(Object raw) {
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

    public static Effects buildEffects(Object raw) {
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

    public static Shadow buildShadow(Object raw) {
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

    public static Glow buildGlow(Object raw) {
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

    // ---------- Map<String,Object> 字段读取 helpers ----------

    public static String requireString(Map<String, Object> m, String k, boolean required) {
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

    public static String stringFieldOrDefault(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (!(v instanceof String s)) {
            throw new ValidationException("INVALID_PAYLOAD", k + " must be string");
        }
        return s;
    }

    public static String nullableString(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (!(v instanceof String s)) {
            throw new ValidationException("INVALID_PAYLOAD", k + " must be string or null");
        }
        return s;
    }

    public static int intFieldOrDefault(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (!(v instanceof Number n)) {
            throw new ValidationException("INVALID_PAYLOAD", k + " must be number");
        }
        return n.intValue();
    }

    public static float floatFieldOrDefault(Map<String, Object> m, String k, float def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (!(v instanceof Number n)) {
            throw new ValidationException("INVALID_PAYLOAD", k + " must be number");
        }
        return n.floatValue();
    }

    public static boolean boolFieldOrDefault(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (!(v instanceof Boolean b)) {
            throw new ValidationException("INVALID_PAYLOAD", k + " must be boolean");
        }
        return b;
    }

    /**
     * 0.4.6：可空 bool 字段（用于 TextElement.bold / italic 等向下兼容 null 字段）。
     * 缺省返 null 让 record 走默认值（NON_NULL 序列化时跳过）。
     */
    public static Boolean boolFieldOrNull(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (!(v instanceof Boolean b)) {
            throw new ValidationException("INVALID_PAYLOAD", k + " must be boolean");
        }
        return b;
    }

    public static Number requireNumber(Map<?, ?> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Number n)) {
            throw new ValidationException("INVALID_PAYLOAD", k + " must be number");
        }
        return n;
    }

    public static String requireStringValue(Object v, String key) {
        if (!(v instanceof String s)) {
            throw new ValidationException("INVALID_PAYLOAD", key + " must be string");
        }
        return s;
    }

    public static int intValue(Object v, String key) {
        if (!(v instanceof Number n)) {
            throw new ValidationException("INVALID_PAYLOAD", key + " must be number");
        }
        return n.intValue();
    }

    public static float floatValue(Object v, String key) {
        if (!(v instanceof Number n)) {
            throw new ValidationException("INVALID_PAYLOAD", key + " must be number");
        }
        return n.floatValue();
    }

    public static boolean boolValue(Object v, String key) {
        if (!(v instanceof Boolean b)) {
            throw new ValidationException("INVALID_PAYLOAD", key + " must be boolean");
        }
        return b;
    }

    // ---------- M15.4 P0-23：模板 raw_state 反序列化得到的 element 二次校验 ----------

    /**
     * 模板 raw_state 反序列化得到的 element 通过本方法二次校验。
     * 之前位于 {@code EditSession.validateElementForTemplateApply}（M15.4 引入），
     * 2026-05-14 重构搬至 {@link ElementValidator}；
     * {@code EditSession.validateElementForTemplateApply} 保留 wrapper 维持外部 API。
     *
     * @throws ValidationException 任一字段不合法
     */
    public static void validateElementForTemplateApply(Element el) {
        validateCoord(el.x(), "x");
        validateCoord(el.y(), "y");
        validateDim(el.w(), "w");
        validateDim(el.h(), "h");
        validateRotation(el.rotation());

        if (el instanceof PathElement p) {
            if (p.d() == null) {
                throw new ValidationException("INVALID_PAYLOAD", "path.d required");
            }
            validatePathD(p.d());
            if (p.fill() != null) FillValidator.validate(p.fill());
        } else if (el instanceof BrushStrokeElement b) {
            if (b.points() == null) {
                throw new ValidationException("INVALID_PAYLOAD", "brush.points required");
            }
            if (b.fill() != null) FillValidator.validate(b.fill());
        } else if (el instanceof ShapeElement sh) {
            // P2-53：模板 raw_state 的 ShapeElement 走与 WS buildShape/applyShapePatch 同款校验。
            validateShapeKind(sh.kind());
            validateSides(sh.sides());
            if (sh.innerRatio() != null) validateInnerRatio(sh.innerRatio());
            if (sh.fill() != null) FillValidator.validate(sh.fill());
        } else if (el instanceof RectElement r) {
            // P3-56：raw_state Rect fill 含 NaN 渐变在反序列化期 fail-fast，而非渲染期。
            if (r.fill() != null) FillValidator.validate(r.fill());
        } else if (el instanceof CircleElement c) {
            if (c.fill() != null) FillValidator.validate(c.fill());
        } else if (el instanceof ImageElement im) {
            if (im.source() == null || !IMAGE_SOURCE_RE.matcher(im.source()).matches()) {
                throw new ValidationException("INVALID_PAYLOAD",
                        "image source must match [0-9a-f]{16}: " + im.source());
            }
            if (im.mask() != null) {
                if (im.mask().d() == null) {
                    throw new ValidationException("INVALID_PAYLOAD", "mask.d required");
                }
                validatePathD(im.mask().d());
                validateMaskPathBounds(im.mask().d());
            }
        } else if (el instanceof IconElement ic) {
            // M26：扩展 source schema 接受 fa-solid/heart / user/foo / material/star.fill / legacy "heart"
            if (!IconElement.isValidSource(ic.source())) {
                throw new ValidationException("INVALID_PAYLOAD",
                        "icon source must match " + IconElement.SOURCE_RE.pattern()
                                + " (≤" + IconElement.SOURCE_MAX_LEN + "): " + ic.source());
            }
            if (ic.fill() != null) FillValidator.validate(ic.fill());
        }
    }
}
