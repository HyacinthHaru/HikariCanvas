package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.Glow;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本发光效果渲染，契约对应 {@code docs/rendering.md §5.3}。
 *
 * <h2>算法（自实现盒模糊，不用系统高斯模糊以保双端一致）</h2>
 * <ol>
 *   <li>算 glyphs 的整体外接矩形 + 四向 padding = {@code glow.radius}</li>
 *   <li>把外接盒裁到「画布向外扩 padding」的范围内，再卡 {@link #MAX_GLOW_PIXELS} 面积上限</li>
 *   <li>分配 local {@code TYPE_INT_ARGB} 图（覆盖裁剪后的 bbox）</li>
 *   <li>在 local 上用白色 drawString 画字形 mask（任意不透明色都行，只关心 alpha）</li>
 *   <li>提取 alpha 通道：{@code radius} 半径的水平 + 垂直两次均值滤波（分离核，等效盒模糊）</li>
 *   <li>着色：alpha 保留作不透明度，RGB 换成 {@code glow.color}</li>
 *   <li>{@code mainG.drawImage(local, bboxX, bboxY)}——Graphics2D SRC_OVER alpha 合成自动生效</li>
 * </ol>
 *
 * <h2>性能与资源上限</h2>
 * Per-element，只在字形外接盒内做模糊。128×128 + radius=4 约 100 K ops，微秒级。
 * 若同一画布有多个 glow text elements，每个独立一张 local image，无共享状态。
 *
 * <p>外接盒<b>只由字形位置决定</b>，与画布尺寸无关：256 字 CJK + fontSize 512 + lineHeight 4 +
 * letterSpacing 128 这套全部合法的参数能让它涨到几亿像素（ARGB 图 + {@code getRGB} 的 int[]
 * 合计几 GB），而渲染又跑在编辑会话的锁里。所以分配前必须走
 * {@code clipToCanvas}：先与画布求交（可见像素逐位不变），再卡
 * {@link #MAX_GLOW_PIXELS}，超限就本次不画发光而不是硬分配。</p>
 *
 * <h2>纯函数</h2>
 * 静态方法、无可变状态；{@link #render} 可并发调用（每次分配新 local image）。
 */
public final class GlowRenderer {

    private static final Pattern HEX_RE = Pattern.compile("^#([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?$");

    private static final Logger LOG = Logger.getLogger(GlowRenderer.class.getName());

    /**
     * 发光缓冲区面积硬上限（像素）。够 32×32 maps 满画布（4096×4096）再加四周 padding，
     * 正常用法碰不到；碰到就是 rendering.md §5.3 说的那套「全部合法但能吃 3.5 GB」的参数组合。
     */
    static final long MAX_GLOW_PIXELS = 20_000_000L;

    /** 超限警告的限流间隔：畸形工程每帧都会撞上限，不能让它把日志刷爆。 */
    private static final long WARN_INTERVAL_NANOS = 60_000_000_000L;  // 60s

    private static final java.util.concurrent.atomic.AtomicLong LAST_WARN_NANOS =
            new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);

    private GlowRenderer() {}

    /**
     * 在 {@code mainG} 上画发光效果（叠底层）。
     *
     * @param mainG  主画布 Graphics2D（TYPE_INT_RGB，drawImage 时自动 SRC_OVER 合成）
     * @param glyphs TextLayout 产出的字形位置列表
     * @param font   已按 fontSize 派生的 Font（从 {@code mainG.getFont()} 取也可）
     * @param fontId 字体 id（来自 {@code TextElement.fontId}），用于查 advance 表；null 走 canonical
     * @param glow   发光参数；{@code radius <= 0} 直接 no-op
     */
    public static void render(Graphics2D mainG, List<TextLayout.PositionedGlyph> glyphs,
                              Font font, String fontId, Glow glow) {
        if (glow == null || glow.radius() <= 0 || glyphs.isEmpty()) return;

        int radius = glow.radius();
        // 非旋转 glyph bbox 与前端 PreviewRenderer.renderGlow 用同一套规则：
        //   ascent = round(fontSize * 0.8)，descent = fontSize - ascent，
        //   宽度 = TextLayout.charAdvance(fontId, ch, fontSize)。
        // 宽度必须与主字形 layout 同源。之前这里用 canonicalCharWidth（ASCII 一律 0.5×fontSize），
        // 而 layout 早已改用真实 advance —— 于是 W / M 这类宽字符的墨迹超出 bbox 被裁掉右缘光晕，
        // 前端却按真实字宽算，双端对不上。旋转 glyph（fontSize×fontSize 方格）两端一致，不改。
        int fontSize = font.getSize();
        int ascent = (int) Math.round(fontSize * TextLayout.ASCENT_RATIO);
        int descent = fontSize - ascent;
        int rotatedAscent = ascent; // rotated 分支复用同一比例（与原 round(fontSize*0.8) 等价）

        // 1) 外接矩形 + padding。用 double 累计 + 末尾 floor/ceil 与前端一致；非旋转 glyph 的
        //    minX/maxX 仍是整数，floor/ceil 为 no-op，snapshot baseline 不漂移。
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (TextLayout.PositionedGlyph pg : glyphs) {
            if (pg.rotated()) {
                // 方格 fontSize × fontSize，中心 = (pg.x, pg.baselineY)
                minX = Math.min(minX, pg.x() - fontSize / 2.0);
                maxX = Math.max(maxX, pg.x() + fontSize / 2.0);
                minY = Math.min(minY, pg.baselineY() - fontSize / 2.0);
                maxY = Math.max(maxY, pg.baselineY() + fontSize / 2.0);
            } else {
                // 与 TextLayout.layout 推进 cursor 用的是同一个度量，前端 renderGlow 同款
                int chW = TextLayout.charAdvance(fontId, pg.ch().charAt(0), fontSize);
                minX = Math.min(minX, pg.x());
                maxX = Math.max(maxX, pg.x() + chW);
                minY = Math.min(minY, pg.baselineY() - ascent);
                maxY = Math.max(maxY, pg.baselineY() + descent);
            }
        }
        int pad = radius + 1;
        int bboxX = (int) Math.floor(minX - pad);
        int bboxY = (int) Math.floor(minY - pad);
        int bboxW = (int) Math.ceil(maxX - minX) + pad * 2;
        int bboxH = (int) Math.ceil(maxY - minY) + pad * 2;
        if (bboxW <= 0 || bboxH <= 0) return;

        // 1.5) 与画布求交 + 面积上限（rendering.md §5.3）。外接盒只看字形位置，不受画布约束，
        //      合法参数就能涨到几亿像素；下面这段是唯一的分配闸门。
        java.awt.Rectangle box = clipToCanvas(mainG, bboxX, bboxY, bboxW, bboxH, pad);
        if (box == null) return;
        bboxX = box.x;
        bboxY = box.y;
        bboxW = box.width;
        bboxH = box.height;

        // 2) local ARGB image
        BufferedImage local = new BufferedImage(bboxW, bboxH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D lg = local.createGraphics();
        try {
            // 同步 main 的 hints（关抗锯齿，确保字形掩膜与主画布字形对齐）
            lg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            lg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            lg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            lg.setFont(font);
            lg.setColor(Color.WHITE);  // 任意不透明色；只关心 alpha 通道的形状
            for (TextLayout.PositionedGlyph pg : glyphs) {
                if (pg.rotated()) {
                    // 镜像 TextRenderer.drawGlyph rotated 分支 + 前端 renderGlow
                    // translate 到方格中心 → rotate 90° → drawString 在 (-chW/2, ascent - fontSize/2)
                    java.awt.geom.AffineTransform saved = lg.getTransform();
                    lg.translate(pg.x() - bboxX, pg.baselineY() - bboxY);
                    lg.rotate(Math.PI / 2);
                    int chW = lg.getFontMetrics().stringWidth(pg.ch());
                    lg.drawString(pg.ch(), -chW / 2, rotatedAscent - fontSize / 2);
                    lg.setTransform(saved);
                } else {
                    lg.drawString(pg.ch(), pg.x() - bboxX, pg.baselineY() - bboxY);
                }
            }
        } finally {
            lg.dispose();
        }

        // 3) 提取 alpha 通道并 box-blur
        int n = bboxW * bboxH;
        int[] pixels = new int[n];
        local.getRGB(0, 0, bboxW, bboxH, pixels, 0, bboxW);
        byte[] alpha = new byte[n];
        for (int i = 0; i < n; i++) alpha[i] = (byte) ((pixels[i] >>> 24) & 0xff);

        byte[] tmp = new byte[n];
        boxBlurHorizontal(alpha, tmp, bboxW, bboxH, radius);
        boxBlurVertical(tmp, alpha, bboxW, bboxH, radius);

        // 4) 着色：保留 alpha，RGB 全替换为 glow.color
        int glowRgb = parseRgb(glow.color());
        for (int i = 0; i < n; i++) {
            int a = alpha[i] & 0xff;
            pixels[i] = (a << 24) | glowRgb;
        }
        local.setRGB(0, 0, bboxW, bboxH, pixels, 0, bboxW);

        // 5) 合成到主画布
        mainG.drawImage(local, bboxX, bboxY, null);
    }

    /**
     * 把字形外接盒裁到「画布向外扩 {@code pad}」的范围内，再卡面积上限。
     *
     * <p>扩 {@code pad}（= {@code radius + 1}）是关键：模糊输出在 (x, y) 只依赖输入
     * {@code [x-radius, x+radius] × [y-radius, y+radius]}，多留一圈就能保证**画布内每个像素的
     * 模糊结果与不裁剪时逐位相同**，裁掉的全是画布外看不见的部分。</p>
     *
     * @return 可以分配的矩形；{@code null} = 完全在画布外 / 超面积上限，本次不画发光
     */
    static java.awt.Rectangle clipToCanvas(Graphics2D g, int x, int y, int w, int h, int pad) {
        java.awt.Rectangle canvas = CanvasCompositor.visibleBounds(g);
        java.awt.Rectangle box = new java.awt.Rectangle(x, y, w, h);
        if (canvas != null) {
            java.awt.Rectangle grown = new java.awt.Rectangle(canvas);
            grown.grow(pad, pad);
            box = box.intersection(grown);
            if (box.isEmpty()) return null;
        }
        if ((long) box.width * (long) box.height > MAX_GLOW_PIXELS) {
            warnThrottled("glow buffer " + box.width + "x" + box.height
                    + " exceeds " + MAX_GLOW_PIXELS + " px cap; skipping glow for this element");
            return null;
        }
        return box;
    }

    /** 每 60s 最多一条：超限的工程每帧都撞，不限流会把日志刷爆。 */
    private static void warnThrottled(String msg) {
        long now = System.nanoTime();
        long last = LAST_WARN_NANOS.get();
        if (now - last < WARN_INTERVAL_NANOS && last != Long.MIN_VALUE) return;
        if (LAST_WARN_NANOS.compareAndSet(last, now)) {
            LOG.warning("[GlowRenderer] " + msg);
        }
    }

    // ---------- 分离核盒模糊 ----------

    /**
     * 水平方向 {@code (2*radius+1)} 长度的均值滤波，滑窗边界 clamp 到 {@code [0, w-1]}。
     * {@code src → dst} 独立数组，避免 in-place 覆盖破坏未处理像素。
     */
    private static void boxBlurHorizontal(byte[] src, byte[] dst, int w, int h, int radius) {
        int diameter = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            int rowBase = y * w;
            int sum = 0;
            // priming：窗口 [-r, r] clamp
            for (int dx = -radius; dx <= radius; dx++) {
                int sx = clampInt(dx, 0, w - 1);
                sum += src[rowBase + sx] & 0xff;
            }
            for (int x = 0; x < w; x++) {
                dst[rowBase + x] = (byte) (sum / diameter);
                int addIdx = clampInt(x + radius + 1, 0, w - 1);
                int subIdx = clampInt(x - radius, 0, w - 1);
                sum += (src[rowBase + addIdx] & 0xff) - (src[rowBase + subIdx] & 0xff);
            }
        }
    }

    private static void boxBlurVertical(byte[] src, byte[] dst, int w, int h, int radius) {
        int diameter = radius * 2 + 1;
        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int dy = -radius; dy <= radius; dy++) {
                int sy = clampInt(dy, 0, h - 1);
                sum += src[sy * w + x] & 0xff;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = (byte) (sum / diameter);
                int addIdx = clampInt(y + radius + 1, 0, h - 1);
                int subIdx = clampInt(y - radius, 0, h - 1);
                sum += (src[addIdx * w + x] & 0xff) - (src[subIdx * w + x] & 0xff);
            }
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int parseRgb(String hex) {
        if (hex == null) return 0xFFFFFF;
        Matcher m = HEX_RE.matcher(hex);
        if (!m.matches()) return 0xFFFFFF;
        return Integer.parseInt(m.group(1), 16);
    }
}
