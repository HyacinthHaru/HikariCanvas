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
                applyImageMaskClip(g, im.mask(), im.w(), im.h());
            }
            g.drawImage(img, 0, 0, im.w(), im.h(), null);
        } finally {
            g.setClip(savedClip);
            g.setTransform(savedTx);
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

    /**
     * mask.d → Path2D（复用 M9 {@link PathParser}）；{@code inverted=true} 时用整 bbox
     * 减去 mask 形状（图层蒙版反相）。坐标系：调用前 {@code g} 已 translate 到 element 左上角，
     * mask path 坐标也是 0..w/0..h 相对，所以直接 setClip 即可。
     */
    private static void applyImageMaskClip(Graphics2D g, Mask mask, int w, int h) {
        if (mask == null || mask.d() == null || mask.d().isEmpty()) return;
        PathParser.Result parsed = PathParser.parse(mask.d());
        Path2D maskPath = parsed.path();
        if (maskPath == null) return;
        if (mask.inverted()) {
            Area area = new Area(new Rectangle2D.Double(0, 0, w, h));
            area.subtract(new Area(maskPath));
            g.clip(area);
        } else {
            g.clip(maskPath);
        }
    }
}
