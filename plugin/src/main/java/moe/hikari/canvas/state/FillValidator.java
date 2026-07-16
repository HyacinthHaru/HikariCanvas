package moe.hikari.canvas.state;

import java.util.List;
import java.util.regex.Pattern;

/**
 * {@link Fill} 字段范围与一致性校验。
 *
 * <p><b>范围约束（v1）：</b></p>
 * <ul>
 *   <li>{@code stops}：数量 {@code [2, 8]}；{@code position ∈ [0, 1]} 单调非递减；
 *       颜色合法 hex</li>
 *   <li>{@link LinearGradient#angle()}：{@code [0, 360)}，{@code Double.isFinite}</li>
 *   <li>{@link RadialGradient#cx()} / {@link RadialGradient#cy()}：{@code [0, 1]}</li>
 *   <li>{@link RadialGradient#r()}：{@code (0, 2]}</li>
 * </ul>
 */
public final class FillValidator {

    private static final Pattern COLOR_RE = Pattern.compile("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$");

    /** 渐变停止点数下限。低于 2 不能形成渐变。 */
    public static final int MIN_STOPS = 2;
    /** 渐变停止点数上限。UI 体感复杂度 + 渲染性能折中。 */
    public static final int MAX_STOPS = 8;

    private FillValidator() {}

    /**
     * 入口：把 {@link Fill} 各子类型分发到对应校验逻辑。
     *
     * @throws ValidationException 任何字段不合规
     */
    public static void validate(Fill fill) {
        if (fill == null) return;
        if (fill instanceof SolidFill s) {
            validateColor(s.color(), "fill.color");
        } else if (fill instanceof LinearGradient g) {
            validateAngle(g.angle());
            validateStops(g.stops());
        } else if (fill instanceof RadialGradient g) {
            validateUnit(g.cx(), "fill.cx");
            validateUnit(g.cy(), "fill.cy");
            validateRadius(g.r());
            validateStops(g.stops());
        } else {
            throw new ValidationException("INVALID_PAYLOAD",
                    "unknown fill subtype: " + fill.getClass().getName());
        }
    }

    private static void validateColor(String s, String field) {
        if (s == null) {
            throw new ValidationException("INVALID_PAYLOAD", field + " must be string");
        }
        if (!COLOR_RE.matcher(s).matches()) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "invalid color (need #RRGGBB or #RRGGBBAA): " + s);
        }
    }

    private static void validateAngle(double a) {
        if (!Double.isFinite(a) || a < 0.0 || a >= 360.0) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "fill.angle out of range [0, 360): " + a);
        }
    }

    private static void validateUnit(double v, String field) {
        if (!Double.isFinite(v) || v < 0.0 || v > 1.0) {
            throw new ValidationException("INVALID_PAYLOAD",
                    field + " out of range [0, 1]: " + v);
        }
    }

    private static void validateRadius(double r) {
        if (!Double.isFinite(r) || r <= 0.0 || r > 2.0) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "fill.r out of range (0, 2]: " + r);
        }
    }

    private static void validateStops(List<Stop> stops) {
        if (stops == null) {
            throw new ValidationException("INVALID_PAYLOAD", "fill.stops must be array");
        }
        if (stops.size() < MIN_STOPS || stops.size() > MAX_STOPS) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "fill.stops count out of range [" + MIN_STOPS + ", " + MAX_STOPS + "]: " + stops.size());
        }
        double last = -1.0;
        for (int i = 0; i < stops.size(); i++) {
            Stop s = stops.get(i);
            if (s == null) {
                throw new ValidationException("INVALID_PAYLOAD",
                        "fill.stops[" + i + "] is null");
            }
            double p = s.position();
            if (!Double.isFinite(p) || p < 0.0 || p > 1.0) {
                throw new ValidationException("INVALID_PAYLOAD",
                        "fill.stops[" + i + "].position out of range [0, 1]: " + p);
            }
            if (p < last) {
                throw new ValidationException("INVALID_PAYLOAD",
                        "fill.stops[" + i + "].position must be non-decreasing: " + p + " < " + last);
            }
            last = p;
            validateColor(s.color(), "fill.stops[" + i + "].color");
        }
    }
}
