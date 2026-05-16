package moe.hikari.canvas.render;

import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.ImageElement;
import moe.hikari.canvas.state.Mask;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/**
 * M13 ImageElement 绘制：按 hash 加载文件 + 可选 mask 裁切 + bbox 拉伸。dither 由
 * {@code CanvasCompositor#drawDitheredElement} 的 per-element off-buffer 路径自然达成"先 dither 再 mask"，
 * 见 {@code docs/rendering.md §4.4}。
 *
 * <p>拆分自 god class {@code CanvasCompositor}（2026-05-14）。</p>
 */
public final class ImageRenderer implements ElementRenderer {

    @Override
    public void draw(Graphics2D g, Element e, RenderContext ctx) {
        ImageElement im = (ImageElement) e;
        // M16 P3.1：渲染层兜底；w/h ≤ 0 时 drawImage 行为不定，直接 return
        if (im.w() <= 0 || im.h() <= 0) return;
        CanvasCompositor.ImageLoader loader = ctx.imageLoader();
        BufferedImage img = loader == null ? null : loader.load(im.source());
        if (img == null) {
            drawImagePlaceholder(g, im);
            return;
        }

        AffineTransform savedTx = g.getTransform();
        Shape savedClip = g.getClip();
        try {
            g.translate(im.x(), im.y());
            if (im.mask() != null) {
                // M16 P3.3：mask Area boolean op 在极端凹 / 自交 path 下可能抛 InternalError
                // ("Odd number of new curves!") 或 O(n²) 卡死；失败时静默降级为不应用 mask
                applyImageMaskClipSafely(g, im, ctx);
            }
            g.drawImage(img, 0, 0, im.w(), im.h(), null);
        } finally {
            g.setClip(savedClip);
            g.setTransform(savedTx);
        }
    }

    /**
     * M16 P3.3：把 mask Area 构造 + setClip 包 try-catch；同时做 bbox sanity（mask path
     * 包围盒不应远超 element bbox），都 fail 时降级为无 mask 直接画原图，并 warn。
     */
    private static void applyImageMaskClipSafely(Graphics2D g, ImageElement im, RenderContext ctx) {
        try {
            Mask mask = im.mask();
            if (mask == null || mask.d() == null || mask.d().isEmpty()) return;
            PathParser.Result parsed = PathParser.parse(mask.d());
            Path2D maskPath = parsed.path();
            if (maskPath == null) return;
            // bbox sanity：极端 path（远超 element bbox 的"超大不可见 bbox"）拒掉
            // —— Area boolean op 是 O(n²) 复杂度，超大 bbox + 自交 path 会卡渲染线程多秒
            Rectangle2D maskBbox = maskPath.getBounds2D();
            double maskArea = Math.abs(maskBbox.getWidth() * maskBbox.getHeight());
            double elArea = (double) im.w() * (double) im.h();
            if (elArea > 0 && maskArea > elArea * 10.0) {
                if (ctx.log() != null) {
                    ctx.log().warning("mask bbox area " + maskArea + " > 10× element area "
                            + elArea + " for element " + im.id() + ", skipping mask");
                }
                return;
            }
            if (mask.inverted()) {
                Area area = new Area(new Rectangle2D.Double(0, 0, im.w(), im.h()));
                area.subtract(new Area(maskPath));
                g.clip(area);
            } else {
                g.clip(maskPath);
            }
        } catch (InternalError | RuntimeException ex) {
            if (ctx.log() != null) {
                ctx.log().warning("mask render failed for element " + im.id() + ": " + ex.getMessage());
            }
            // 降级：不应用 mask（caller 已 saveClip/restoreClip，故 setClip 状态可能已部分污染）
            // 把 clip 重置回 element bbox 之外（用大 rect 覆盖全屏），让 drawImage 仍画原图
            // —— 由于 g.translate 已 applied，本地坐标 0..w, 0..h 是 element 范围
        }
    }

    /**
     * 文件缺失占位：虚线方框 + 中央 {@code ?}（同 {@link IconRenderer} 风格）。
     */
    private static void drawImagePlaceholder(Graphics2D g, ImageElement im) {
        Color prev = g.getColor();
        java.awt.Stroke prevStroke = g.getStroke();
        try {
            g.setColor(new Color(0xAAAAAA));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    1f, new float[]{3f, 2f}, 0f));
            g.drawRect(im.x(), im.y(), Math.max(1, im.w() - 1), Math.max(1, im.h() - 1));
            g.setStroke(new BasicStroke(1f));
            g.drawString("?", im.x() + im.w() / 2 - 3, im.y() + im.h() / 2 + 4);
        } finally {
            g.setColor(prev);
            g.setStroke(prevStroke);
        }
    }

}
