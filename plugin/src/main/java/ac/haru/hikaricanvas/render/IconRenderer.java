package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.ElementValidator;
import ac.haru.hikaricanvas.state.Fill;
import ac.haru.hikaricanvas.state.IconElement;
import ac.haru.hikaricanvas.state.SolidFill;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Shape;
import java.awt.geom.AffineTransform;

/**
 * 绘制矢量图标元素（SVG 矢量路径，见 {@code docs/rendering.md §4.4}）。
 *
 * <p>从 {@link IconRegistry} 按 {@code <pack>/<name>} 取 path d + viewBox，
 * {@link PathParser} 解析为 {@link java.awt.geom.Path2D}；viewBox → bbox 等比缩放居中变换；
 * 用 {@link IconElement#fill()}（{@link FillPaintBuilder#fillToPaint}）填充，
 * {@code fill == null} 时退黑色默认色。未注册或 {@code iconRegistry == null} 时画占位框。</p>
 */
public final class IconRenderer implements ElementRenderer {

    @Override
    public void draw(Graphics2D g, Element e, RenderContext ctx) {
        IconElement ic = (IconElement) e;
        if (ic.w() <= 0 || ic.h() <= 0
                || ic.w() > ElementValidator.MAX_DIM || ic.h() > ElementValidator.MAX_DIM) {
            return;
        }
        renderSvgPath(g, ic, ctx);
    }

    private void renderSvgPath(Graphics2D g, IconElement ic, RenderContext ctx) {
        IconRegistry registry = ctx.iconRegistry();
        if (registry == null) {
            // 测试 / 配置缺失场景：占位
            drawPlaceholder(g, ic);
            return;
        }
        String pathD = registry.getPathD(ic.source());
        String viewBox = registry.getViewBox(ic.source());
        if (pathD == null || viewBox == null) {
            drawPlaceholder(g, ic);
            return;
        }

        // 1. parse path d → Path2D（PathParser 支持完整 SVG 命令集 M/L/H/V/Q/T/C/S/A；
        //    A 弧用 ≤π/2 段 cubic bezier 近似——见 PathParser 类 doc）
        PathParser.Result parsed = PathParser.parse(pathD);
        Shape shape = parsed.path();
        if (shape == null) return;

        // 2. viewBox → element bbox 的等比缩放居中变换
        double[] vb = parseViewBox(viewBox);
        if (vb == null) {
            drawPlaceholder(g, ic);
            return;
        }
        double vbX = vb[0], vbY = vb[1], vbW = vb[2], vbH = vb[3];
        if (vbW <= 0 || vbH <= 0) {
            drawPlaceholder(g, ic);
            return;
        }
        double scale = Math.min((double) ic.w() / vbW, (double) ic.h() / vbH);
        double offX = (ic.w() - vbW * scale) / 2.0 - vbX * scale;
        double offY = (ic.h() - vbH * scale) / 2.0 - vbY * scale;

        AffineTransform xf = new AffineTransform();
        xf.translate(ic.x() + offX, ic.y() + offY);
        xf.scale(scale, scale);
        Shape transformed = xf.createTransformedShape(shape);

        // 3. fill paint：null → 黑色 fallback（pack 默认色 = 黑）
        //    渐变 bbox 取 element bbox（与 Rect/Circle/Path 一致）
        Fill fill = ic.fill();
        if (fill == null) {
            fill = new SolidFill("#000000");
        }
        Paint paint = FillPaintBuilder.fillToPaint(fill, ic.x(), ic.y(), ic.w(), ic.h());

        // 4. 绘制
        Paint prevPaint = g.getPaint();
        try {
            g.setPaint(paint);
            // 矢量 path 需要 AA off 一致；上层 applyHints 已设，无需重复
            g.fill(transformed);
        } finally {
            g.setPaint(prevPaint);
        }
    }

    /**
     * 解析 SVG viewBox 字符串 {@code "minX minY width height"}（空格 / 逗号分隔）。
     * 失败返回 null。
     */
    private static double[] parseViewBox(String viewBox) {
        if (viewBox == null) return null;
        String[] parts = viewBox.trim().split("[\\s,]+");
        if (parts.length != 4) return null;
        try {
            double[] out = new double[4];
            for (int i = 0; i < 4; i++) out[i] = Double.parseDouble(parts[i]);
            return out;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 占位：虚线方框 + ?。 */
    private static void drawPlaceholder(Graphics2D g, IconElement ic) {
        Paint prev = g.getPaint();
        g.setColor(new Color(0xAAAAAA));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                1f, new float[]{3f, 2f}, 0f));
        g.drawRect(ic.x(), ic.y(), Math.max(1, ic.w() - 1), Math.max(1, ic.h() - 1));
        g.setStroke(new BasicStroke(1f));
        g.drawString("?", ic.x() + ic.w() / 2 - 3, ic.y() + ic.h() / 2 + 4);
        g.setPaint(prev);
    }
}
