package moe.hikari.canvas.web;

import moe.hikari.canvas.state.BrushPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Web 包内共享的小工具：payload 类型解构 + brush point 解析。
 * <p>从 {@link WebServer} 抽出，供 {@link EditOpDispatcher} / {@link BrushOpDispatcher}
 * / {@link WallOpDispatcher} / {@link TemplateOpDispatcher} 复用。</p>
 */
final class WebHelpers {

    private WebHelpers() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> asPayloadMap(Object payload) {
        if (payload == null) return Map.of();
        if (payload instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("payload must be object");
    }

    static Map<?, ?> mapOrEmpty(Object v) {
        if (v == null) return Map.of();
        if (v instanceof Map<?, ?> m) return m;
        throw new IllegalArgumentException("expected object, got " + v.getClass().getSimpleName());
    }

    static String stringOrNull(Object v) {
        return (v instanceof String s) ? s : null;
    }

    static Integer intOrNull(Object v) {
        return (v instanceof Number n) ? n.intValue() : null;
    }

    /** 解析 brush.point 的 payload {@code points: [[x, y, pressure], ...]} 为 {@link BrushPoint} 列表。 */
    static List<BrushPoint> parseBrushPoints(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("points must be array");
        }
        List<BrushPoint> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof List<?> inner) || inner.size() < 3) {
                throw new IllegalArgumentException("each point must be [x, y, pressure]");
            }
            Object xo = inner.get(0), yo = inner.get(1), po = inner.get(2);
            if (!(xo instanceof Number) || !(yo instanceof Number) || !(po instanceof Number)) {
                throw new IllegalArgumentException("point values must be numbers");
            }
            double x = ((Number) xo).doubleValue();
            double y = ((Number) yo).doubleValue();
            double p = ((Number) po).doubleValue();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(p)) {
                throw new IllegalArgumentException("non-finite point value");
            }
            out.add(new BrushPoint(x, y, p));
        }
        return out;
    }
}
