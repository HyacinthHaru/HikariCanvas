package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.Element;
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
     * feather 路径。流程（契约见 {@code docs/rendering.md §4.4}）：
     * <ol>
     *   <li>算可分配区域：元素矩形 ∩（画布向外扩 {@code 3 × featherPx + 1}）</li>
     *   <li>off-buffer1：把 image 缩放绘到 (w, h) ARGB，只保留可分配区域那块</li>
     *   <li>off-buffer2：把 mask path 填白色；inverted 时填外部</li>
     *   <li>对 off-buffer2 做盒滤波模糊（可分离 1D 核，3 遍近似高斯）</li>
     *   <li>用 {@code AlphaComposite.DST_IN} 把 mask alpha 复合到 image off-buffer1</li>
     *   <li>把 off-buffer1 画回主 g</li>
     * </ol>
     *
     * <p><b>为什么必须裁剪：</b>元素 w/h 上限 {@code MAX_DIM} = 10000，两张 ARGB 中间图各 400 MB；
     * 关键帧插值还能把 w/h 顶过 MAX_DIM。裁到画布内之后最坏就是画布大小。向外多扩
     * {@code 3 × featherPx}（3 遍模糊，每遍影响半径 featherPx）保证可见区内像素与不裁剪时逐位相同。</p>
     *
     * <p><b>降级而不是不画：</b>裁完面积仍超 {@link #MAX_FEATHER_PIXELS} 时走硬边 clip + 直接画原图
     * —— O(1) 内存。以前这里是直接 {@code return}，
     * 后端整张图消失、前端照常显示，双端分叉。中途抛异常时同样降级。</p>
     */
    private static void drawWithFeather(Graphics2D g, ImageElement im, BufferedImage src, RenderContext ctx) {
        int w = im.w();
        int h = im.h();
        Mask mask = im.mask();
        int featherPx = mask == null ? 0 : mask.featherPxOrZero();

        java.awt.Rectangle crop = featherBufferRect(g, w, h, featherPx);
        if (crop == null) {
            // 超上限 / 读不到画布范围 —— 降级硬边，别硬分配也别整张图不画
            drawHardEdgeFallback(g, im, src, ctx);
            return;
        }
        if (crop.isEmpty()) return;   // 完全在画布外，本来就看不见

        try {
            // (1) image off-buffer（局部 (0,0) 对应元素坐标 (crop.x, crop.y)）
            BufferedImage imgBuf = new BufferedImage(crop.width, crop.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ig = imgBuf.createGraphics();
            try {
                ig.translate(-crop.x, -crop.y);
                ig.drawImage(src, 0, 0, w, h, null);
            } finally {
                ig.dispose();
            }

            // (2) mask off-buffer (grayscale alpha)
            BufferedImage maskBuf = new BufferedImage(crop.width, crop.height, BufferedImage.TYPE_INT_ARGB);
            PathParser.Result parsed = PathParser.parse(mask.d());
            Path2D maskPath = parsed.path();
            Graphics2D mg = maskBuf.createGraphics();
            try {
                mg.translate(-crop.x, -crop.y);
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
            g.drawImage(imgBuf, crop.x, crop.y, null);
        } catch (InternalError | RuntimeException ex) {
            if (ctx.log() != null) {
                ctx.log().warning("mask feather render failed for element " + im.id()
                        + ": " + ex.getMessage());
            }
            drawHardEdgeFallback(g, im, src, ctx);
        }
    }

    /** 羽化中间缓冲区面积硬上限（像素）。32×32 maps 满画布 = 16.8 M，正常用法碰不到。 */
    static final long MAX_FEATHER_PIXELS = 16_777_216L;

    /**
     * 算羽化 off-buffer 的可分配矩形（元素本地坐标，主 g 已 translate 到元素原点）。
     *
     * @return 可分配矩形；{@code isEmpty()} = 完全在画布外（不画）；{@code null} = 超上限 / 无从裁剪，调用方降级
     */
    static java.awt.Rectangle featherBufferRect(Graphics2D g, int w, int h, int featherPx) {
        java.awt.Rectangle full = new java.awt.Rectangle(0, 0, w, h);
        java.awt.Rectangle canvas = CanvasCompositor.visibleBounds(g);
        java.awt.Rectangle crop;
        if (canvas == null) {
            // 拿不到画布范围（直接拿裸 Graphics2D 调 renderer 的路径）：只能退回全尺寸
            crop = full;
        } else {
            java.awt.Rectangle grown = new java.awt.Rectangle(canvas);
            int pad = 3 * Math.max(0, featherPx) + 1;   // 3 遍模糊，每遍影响半径 featherPx
            grown.grow(pad, pad);
            crop = full.intersection(grown);
            if (crop.isEmpty()) return crop;
        }
        if ((long) crop.width * (long) crop.height > MAX_FEATHER_PIXELS) return null;
        return crop;
    }

    /** 降级路径：硬边 clip + 原图。O(1) 内存，任何尺寸都画得出来。 */
    private static void drawHardEdgeFallback(Graphics2D g, ImageElement im,
                                             BufferedImage src, RenderContext ctx) {
        try {
            applyImageMaskClipSafely(g, im, ctx);
            g.drawImage(src, 0, 0, im.w(), im.h(), null);
        } catch (InternalError | RuntimeException ignored) {
            // 连硬边都画不出来（clip 状态已污染）——调用方 finally 会恢复 clip / transform
        }
    }

    /**
     * 盒滤波模糊（box blur，3 次近似高斯）。仅对 alpha 通道关键，但本实现对 ARGB
     * 整体跑——更简洁且性能差距可忽略。
     *
     * <p><b>用可分离 1D 核</b>（水平一遍 + 垂直一遍），每像素 {@code O(k)}。原来用
     * {@code (2r+1)²} 二维核：{@code r=32} 时每像素 4225 次乘加、跑 3 遍，500×500 元素就是
     * 30 亿次运算/帧，而这段跑在编辑会话锁里。二维均值核本身就是两个 1D 均值核的卷积，
     * 拆开是数学等价的。</p>
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
        float kVal = 1f / kSize;
        float[] kernel = new float[kSize];
        java.util.Arrays.fill(kernel, kVal);
        ConvolveOp horizontal = new ConvolveOp(new Kernel(kSize, 1, kernel), ConvolveOp.EDGE_NO_OP, null);
        ConvolveOp vertical = new ConvolveOp(new Kernel(1, kSize, kernel), ConvolveOp.EDGE_NO_OP, null);
        for (int i = 0; i < passes; i++) {
            out = applyKernel(vertical, applyKernel(horizontal, out));
        }
        return out;
    }

    private static BufferedImage applyKernel(ConvolveOp op, BufferedImage in) {
        BufferedImage next = new BufferedImage(in.getWidth(), in.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        op.filter(in, next);
        return next;
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
            // 降级为「无 mask 直接画原图」。g.clip() 自己抛的时候 clip 可能已被部分收窄，
            // 不重置的话接下来那句 drawImage 会把图片画没 —— 这里把 clip 拨回 element bbox
            // （g.translate 已 applied，本地 0..w / 0..h 就是元素范围），等价于没有 mask。
            // caller 的 finally 仍会把 clip 恢复成进方法前的值。
            try {
                g.setClip(0, 0, im.w(), im.h());
            } catch (InternalError | RuntimeException ignored) {
                // 连 setClip 都失败就只能放弃，外层 finally 兜底恢复
            }
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
