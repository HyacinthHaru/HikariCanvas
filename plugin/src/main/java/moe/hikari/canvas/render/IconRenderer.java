package moe.hikari.canvas.render;

import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.ElementValidator;
import moe.hikari.canvas.state.Fill;
import moe.hikari.canvas.state.IconElement;
import moe.hikari.canvas.state.SolidFill;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * 绘制图标元素。
 *
 * <p><b>M26.2 双路径分流</b>：</p>
 * <ul>
 *   <li><b>SVG 矢量路径</b>（{@code source} 含 {@code /}）：从 {@link IconRegistry} 取 path d
 *       + viewBox，{@link PathParser} 解析为 {@link java.awt.geom.Path2D}；viewBox → bbox
 *       等比缩放居中变换；用 {@link IconElement#fill()}（{@link FillPaintBuilder#fillToPaint}）
 *       填充。fill 为 null 时退黑色。</li>
 *   <li><b>PNG legacy 路径</b>（{@code source} 不含 {@code /}）：保留 M7 行为不变；
 *       {@code tint} 非空时对 alpha 做 source-in 染色（保留 alpha 形状、整体替换 tint 色）。</li>
 * </ul>
 *
 * <p>拆分自 god class {@code CanvasCompositor}（2026-05-14）；M26.2 加 SVG 矢量分支。</p>
 */
public final class IconRenderer implements ElementRenderer {

    @Override
    public void draw(Graphics2D g, Element e, RenderContext ctx) {
        IconElement ic = (IconElement) e;
        if (IconElement.isLegacySource(ic.source())) {
            renderLegacyPng(g, ic, ctx);
        } else {
            renderSvgPath(g, ic, ctx);
        }
    }

    // ---------- M7 legacy PNG 路径（行为完全不变） ----------

    private void renderLegacyPng(Graphics2D g, IconElement ic, RenderContext ctx) {
        // P3-49：渲染层兜底（对齐 renderSvgPath:92 与 M16.3 兄弟 renderer 模式）；
        // w/h ≤ 0 时 drawImage / BufferedImage 行为退化 → 直接 return
        if (ic.w() <= 0 || ic.h() <= 0) return;
        // B1：关键帧 w/h 可能超 MAX_DIM（动画 ticker 线程 OOM 防御）
        if (ic.w() > ElementValidator.MAX_DIM || ic.h() > ElementValidator.MAX_DIM) return;
        if (ctx.assetService() == null) {
            ctx.log().warning("[compositor] IconElement '" + ic.id() + "' but no assetService bound");
            return;
        }
        BufferedImage img = ctx.assetService().loadIcon(ic.source());
        if (img == null) {
            drawPlaceholder(g, ic);
            return;
        }
        if (ic.tint() == null || ic.tint().isBlank()) {
            g.drawImage(img, ic.x(), ic.y(), ic.w(), ic.h(), null);
            return;
        }
        // 染色：先画 tinted 形状 (source-in 合成) 到临时图，再贴
        BufferedImage tinted = new BufferedImage(ic.w(), ic.h(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D tg = tinted.createGraphics();
        try {
            tg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            tg.drawImage(img, 0, 0, ic.w(), ic.h(), null);
            tg.setComposite(AlphaComposite.SrcIn);
            tg.setColor(FillPaintBuilder.parseColor(ic.tint()));
            tg.fillRect(0, 0, ic.w(), ic.h());
        } finally {
            tg.dispose();
        }
        g.drawImage(tinted, ic.x(), ic.y(), null);
    }

    // ---------- M26.2 SVG 矢量路径 ----------

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
        if (ic.w() <= 0 || ic.h() <= 0) return;

        // 1. parse path d → Path2D（PathParser 支持完整 SVG 命令集 M/L/H/V/Q/T/C/S/A，M26-C 起引入；
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

        // 3. fill paint：null → 黑色 fallback（M26 决策：pack 默认色 = 黑）
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

    /** 占位：虚线方框 + ?。同 M7 风格。 */
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
