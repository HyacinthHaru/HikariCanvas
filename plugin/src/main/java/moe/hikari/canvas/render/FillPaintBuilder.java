package moe.hikari.canvas.render;

import moe.hikari.canvas.state.Fill;
import moe.hikari.canvas.state.LinearGradient;
import moe.hikari.canvas.state.RadialGradient;
import moe.hikari.canvas.state.SolidFill;
import moe.hikari.canvas.state.Stop;

import java.awt.Color;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fill / Paint 构造工具集。拆分自 god class {@code CanvasCompositor}（2026-05-14）。
 * 所有方法 static + pure，无副作用，可被任意 Renderer 共用。
 *
 * <p>同时承担 {@link #parseColor} 颜色解析职责（HEX → AWT Color），供 Renderer 直接调。</p>
 */
public final class FillPaintBuilder {

    private static final Pattern HEX_RE = Pattern.compile("^#([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?$");

    private FillPaintBuilder() {}

    /**
     * 解析 {@code #RRGGBB} 或 {@code #RRGGBBAA} 为 AWT Color。
     *
     * <p>M10：alpha 通道支持。alpha 字段缺省时 = 255（不透明）；非空时 0-255 控制半透明。
     * 在 {@code TYPE_INT_RGB} 主 buffer 上 fill 时 Graphics2D 走 Porter-Duff SrcOver，
     * alpha < 255 的色与底层像素叠加（"颜色变浅"语义，同 docs/rendering.md §6.5 element opacity）。</p>
     */
    public static Color parseColor(String hex) {
        if (hex == null) return Color.WHITE;
        Matcher m = HEX_RE.matcher(hex);
        if (!m.matches()) return Color.WHITE;
        int rgb = Integer.parseInt(m.group(1), 16);
        int alpha = m.group(2) != null ? Integer.parseInt(m.group(2), 16) : 255;
        return new Color((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff, alpha);
    }

    /**
     * M11-B：把 {@link Fill} 转成 AWT {@link Paint}，喂给 {@code g.setPaint}。
     *
     * <ul>
     *   <li>{@link SolidFill} → {@link Color}（{@code Color extends Paint}，等价 setColor）</li>
     *   <li>{@link LinearGradient} → {@link LinearGradientPaint}，渐变线穿过 bbox 中心，
     *       两端点 = bbox 4 角向方向向量投影的 max/min 点</li>
     *   <li>{@link RadialGradient} → {@link RadialGradientPaint}，中心 = bbox 内归一化 (cx, cy)，
     *       半径 = r × min(w, h) / 2</li>
     * </ul>
     *
     * @param bx 元素 bbox 左上 x（path 元素已 translate 时传 0）
     * @param by 元素 bbox 左上 y
     * @param bw 元素 bbox 宽
     * @param bh 元素 bbox 高
     */
    public static Paint fillToPaint(Fill fill, double bx, double by, double bw, double bh) {
        if (fill == null) return Color.WHITE;
        if (fill instanceof SolidFill s) return parseColor(s.color());
        if (fill instanceof LinearGradient lg) return buildLinearPaint(lg, bx, by, bw, bh);
        if (fill instanceof RadialGradient rg) return buildRadialPaint(rg, bx, by, bw, bh);
        return Color.WHITE;
    }

    public static Paint buildLinearPaint(LinearGradient g,
                                          double bx, double by, double bw, double bh) {
        List<Stop> stops = g.stops();
        if (stops == null || stops.isEmpty()) return Color.WHITE;
        // 角度 → 方向向量（0° 沿 +x，90° 沿 +y，画布坐标系 Y 朝下，顺时针为正）
        double rad = Math.toRadians(g.angle());
        double dx = Math.cos(rad);
        double dy = Math.sin(rad);
        double cx = bx + bw / 2.0;
        double cy = by + bh / 2.0;
        // 把 bbox 4 角投影到方向向量，取最远的两点作为渐变线端点
        double minP = Double.POSITIVE_INFINITY, maxP = Double.NEGATIVE_INFINITY;
        double[][] corners = {{bx, by}, {bx + bw, by}, {bx, by + bh}, {bx + bw, by + bh}};
        for (double[] c : corners) {
            double p = (c[0] - cx) * dx + (c[1] - cy) * dy;
            if (p < minP) minP = p;
            if (p > maxP) maxP = p;
        }
        float x1 = (float) (cx + dx * minP);
        float y1 = (float) (cy + dy * minP);
        float x2 = (float) (cx + dx * maxP);
        float y2 = (float) (cy + dy * maxP);
        // 退化（0 尺寸 bbox）：LinearGradientPaint 要求 (x1,y1) != (x2,y2)，否则抛 IAE → fallback 纯色
        if (x1 == x2 && y1 == y2) return parseColor(stops.get(0).color());

        float[] fractions = monotonicFractions(stops);
        Color[] colors = stopColors(stops);
        try {
            return new LinearGradientPaint(x1, y1, x2, y2, fractions, colors);
        } catch (IllegalArgumentException ex) {
            // 极端形态（如 stops 全 position=1.0，monotonicFractions 推到 [1.0, 1.0] 仍非严格递增）
            // 触发 AWT IAE → 优雅降级首 stop 纯色，不让 rasterize 崩
            return parseColor(stops.get(0).color());
        }
    }

    public static Paint buildRadialPaint(RadialGradient g,
                                          double bx, double by, double bw, double bh) {
        List<Stop> stops = g.stops();
        if (stops == null || stops.isEmpty()) return Color.WHITE;
        float cx = (float) (bx + g.cx() * bw);
        float cy = (float) (by + g.cy() * bh);
        float radius = (float) (g.r() * Math.min(bw, bh) / 2.0);
        // RadialGradientPaint 要求 radius > 0；退化时 fallback 首 stop 纯色
        if (radius <= 0f) return parseColor(stops.get(0).color());

        float[] fractions = monotonicFractions(stops);
        Color[] colors = stopColors(stops);
        try {
            return new RadialGradientPaint(cx, cy, radius, fractions, colors);
        } catch (IllegalArgumentException ex) {
            // 同 buildLinearPaint 兜底：AWT 严格 fractions 单调要求触发的 IAE → 纯色 fallback
            return parseColor(stops.get(0).color());
        }
    }

    /**
     * AWT {@code GradientPaint} 要求 fractions 严格递增。FillValidator 允许相等（硬切色），
     * 这里 epsilon-bump 处理：连续相等 fraction 改为 {@code prev + 1e-5f}，最高 clamp 到 1。
     */
    public static float[] monotonicFractions(List<Stop> stops) {
        int n = stops.size();
        float[] out = new float[n];
        float prev = -1f;
        for (int i = 0; i < n; i++) {
            float f = (float) stops.get(i).position();
            if (f <= prev) f = Math.min(1f, prev + 1e-5f);
            out[i] = f;
            prev = f;
        }
        return out;
    }

    public static Color[] stopColors(List<Stop> stops) {
        Color[] out = new Color[stops.size()];
        for (int i = 0; i < stops.size(); i++) out[i] = parseColor(stops.get(i).color());
        return out;
    }
}
