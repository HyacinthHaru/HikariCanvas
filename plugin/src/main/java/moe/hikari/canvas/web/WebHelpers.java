package moe.hikari.canvas.web;

import moe.hikari.canvas.state.BrushPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Web 包内共享的小工具：payload 类型解构 + brush point 解析 + JSON Pointer 段编解码。
 * <p>从 {@link WebServer} 抽出，供 {@link EditOpDispatcher} / {@link BrushOpDispatcher}
 * / {@link WallOpDispatcher} / {@link TemplateOpDispatcher} 复用。</p>
 * <p>0.4.10 P3-114：类与 JSON Pointer 编解码方法 public 化，让 state/session 包的
 * {@code EditSession} / {@code SessionManager} 复用同一来源（消除 4 份重复实现）。</p>
 */
public final class WebHelpers {

    /** brush 点坐标绝对值上限（与 state 层 MAX_COORD 一致），防巨大 finite 值绕过整数溢出 / 超宽 stroke。 */
    private static final double BRUSH_MAX_COORD = 10000.0;

    private WebHelpers() {}

    /**
     * 0.4.10 P3-114：RFC 6902 JSON Pointer 段编码（{@code ~ → ~0}，{@code / → ~1}）。
     * 单一来源——state.patch 路径里凡需把 fullName 当 path 段都走这里。
     */
    public static String encodeJsonPointerSegment(String s) {
        return s.replace("~", "~0").replace("/", "~1");
    }

    /** JSON Pointer 段解码（{@code ~1 → /}，{@code ~0 → ~}，顺序不可换）。 */
    public static String decodeJsonPointerSegment(String s) {
        return s.replace("~1", "/").replace("~0", "~");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asPayloadMap(Object payload) {
        if (payload == null) return Map.of();
        if (payload instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("payload must be object");
    }

    static Map<?, ?> mapOrEmpty(Object v) {
        if (v == null) return Map.of();
        if (v instanceof Map<?, ?> m) return m;
        // M16 P6.1：不暴露内部类名（Java SimpleName），固定消息。
        throw new IllegalArgumentException("expected object");
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
            // 0.4.10 P2-12：拒绝巨大 finite 坐标（绕过 MAX_COORD 会致整数溢出 / 超宽 stroke），
            // 压感钳到 [0,1]（越界压感会被 BrushRenderer 放大成异常线宽）。
            if (Math.abs(x) > BRUSH_MAX_COORD || Math.abs(y) > BRUSH_MAX_COORD) {
                throw new IllegalArgumentException("point coordinate out of range");
            }
            p = Math.max(0.0, Math.min(1.0, p));
            out.add(new BrushPoint(x, y, p));
        }
        return out;
    }
}
