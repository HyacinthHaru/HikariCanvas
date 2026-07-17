package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.ElementValidator;
import ac.haru.hikaricanvas.state.ImageElement;
import ac.haru.hikaricanvas.state.Mask;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;

/**
 * ImageElement 绘制：按 hash 加载文件 + 可选 mask 裁切 + bbox 拉伸。dither 由
 * {@code CanvasCompositor#drawDitheredElement} 的 per-element off-buffer 路径自然达成"先 dither 再 mask"，
 * 见 {@code docs/rendering.md §4.4}。
 */
public final class ImageRenderer implements ElementRenderer {

    @Override
    public void draw(Graphics2D g, Element e, RenderContext ctx) {
        ImageElement im = (ImageElement) e;
        // 渲染层兜底；w/h ≤ 0 时 drawImage 行为不定，直接 return
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
            Mask mask = im.mask();
            // 羽化路径走 alpha mask off-buffer；硬边路径仍走原 clip。
            if (mask != null && mask.hasFeather()) {
                drawWithFeather(g, im, img, ctx);
            } else {
                if (mask != null) {
                    // mask Area boolean op 在极端凹 / 自交 path 下可能抛 InternalError
                    // ("Odd number of new curves!") 或 O(n²) 卡死；失败时静默降级为不应用 mask
                    applyImageMaskClipSafely(g, im, ctx);
                }
                g.drawImage(img, 0, 0, im.w(), im.h(), null);
            }
        } finally {
            g.setClip(savedClip);
            g.setTransform(savedTx);
        }
    }

    /**
     * feather 路径。流程：
     * <ol>
     *   <li>off-buffer1：把 image 缩放绘到 (w, h) ARGB</li>
     *   <li>off-buffer2：把 mask path 填白色到 (w, h)；inverted 时填外部</li>
     *   <li>对 off-buffer2 做盒滤波模糊（box blur 三遍近似高斯）</li>
     *   <li>用 {@code AlphaComposite.DST_IN} 把 mask alpha 复合到 image off-buffer1</li>
     *   <li>把 off-buffer1 画回主 g</li>
     * </ol>
     *
     * <p>失败时降级为硬边 clip 路径（不影响渲染稳定性）。</p>
     */
    private static void drawWithFeather(Graphics2D g, ImageElement im, BufferedImage src, RenderContext ctx) {
        int w = im.w();
        int h = im.h();
        // 关键帧 w/h 可能超 MAX_DIM（动画 ticker 线程 OOM 防御）；dither 路径按 clip∩canvas 分配安全，不用改
        if (w > ElementValidator.MAX_DIM || h > ElementValidator.MAX_DIM) return;
        Mask mask = im.mask();
        int featherPx = mask == null ? 0 : mask.featherPxOrZero();
        try {
            // (1) image off-buffer
            BufferedImage imgBuf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ig = imgBuf.createGraphics();
            try {
                ig.drawImage(src, 0, 0, w, h, null);
            } finally {
                ig.dispose();
            }

            // (2) mask off-buffer (grayscale alpha)
            BufferedImage maskBuf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            PathParser.Result parsed = PathParser.parse(mask.d());
            Path2D maskPath = parsed.path();
            Graphics2D mg = maskBuf.createGraphics();
            try {
                mg.setColor(Color.WHITE);
                if (mask.inverted()) {
                    Area area = new Area(new Rectangle2D.Double(0, 0, w, h));
                    area.subtract(new Area(maskPath));
                    mg.fill(area);
                } else {
                    mg.fill(maskPath);
                }
            } finally {
                mg.dispose();
            }

            // (3) box blur 三遍（近似高斯；半径 = featherPx）
            BufferedImage blurred = boxBlurAlpha(maskBuf, featherPx);

            // (4) DST_IN 复合 mask 到 image
            Graphics2D cg = imgBuf.createGraphics();
            try {
                cg.setComposite(AlphaComposite.DstIn);
                cg.drawImage(blurred, 0, 0, null);
            } finally {
                cg.dispose();
            }

            // (5) 主 g 已 translate(im.x, im.y) → drawImage 落到 element 位置
            g.drawImage(imgBuf, 0, 0, null);
        } catch (InternalError | RuntimeException ex) {
            if (ctx.log() != null) {
                ctx.log().warning("mask feather render failed for element " + im.id()
                        + ": " + ex.getMessage());
            }
            // 降级：硬边 clip + 原图
            try {
                applyImageMaskClipSafely(g, im, ctx);
                g.drawImage(src, 0, 0, w, h, null);
            } catch (Exception ignored) {}
        }
    }

    /**
     * 盒滤波模糊（box blur，3 次近似高斯）。仅对 alpha 通道关键，但本实现对 ARGB
     * 整体跑——更简洁且性能差距可忽略（mask off-buffer 一般 ≤ 1MP）。
     *
     * @param src     ARGB 输入图
     * @param radius  半径（像素）；0 / 负值直接 return src
     */
    static BufferedImage boxBlurAlpha(BufferedImage src, int radius) {
        if (src == null || radius <= 0) return src;
        int r = Math.min(radius, 32);
        // 3 次 box → 高斯近似（CLT 收敛）；总等效半径 ≈ radius
        int passes = 3;
        BufferedImage out = src;
        int kSize = 2 * r + 1;
        float kVal = 1f / (kSize * kSize);
        float[] kernel = new float[kSize * kSize];
        java.util.Arrays.fill(kernel, kVal);
        ConvolveOp op = new ConvolveOp(new Kernel(kSize, kSize, kernel), ConvolveOp.EDGE_NO_OP, null);
        for (int i = 0; i < passes; i++) {
            BufferedImage next = new BufferedImage(out.getWidth(), out.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            op.filter(out, next);
            out = next;
        }
        return out;
    }

    /**
     * 把 mask Area 构造 + setClip 包 try-catch；同时做 bbox sanity（mask path
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
