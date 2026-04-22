package moe.hikari.canvas.render;

import moe.hikari.canvas.state.Glow;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本发光效果渲染（M4-T10），契约对应 {@code docs/rendering.md §5.3}。
 *
 * <h2>算法（自实现盒模糊，不用系统高斯模糊以保双端一致）</h2>
 * <ol>
 *   <li>算 glyphs 的整体外接矩形 + 四向 padding = {@code glow.radius}</li>
 *   <li>分配 local {@code TYPE_INT_ARGB} 图（覆盖 bbox + padding）</li>
 *   <li>在 local 上用白色 drawString 画字形 mask（任意不透明色都行，只关心 alpha）</li>
 *   <li>提取 alpha 通道：{@code radius} 半径的水平 + 垂直两次均值滤波（分离核，等效盒模糊）</li>
 *   <li>着色：alpha 保留作不透明度，RGB 换成 {@code glow.color}</li>
 *   <li>{@code mainG.drawImage(local, bboxX, bboxY)}——Graphics2D SRC_OVER alpha 合成自动生效</li>
 * </ol>
 *
 * <h2>性能</h2>
 * Per-element，只在字形外接盒内做模糊。128×128 + radius=4 约 100 K ops，微秒级。
 * 若同一画布有多个 glow text elements，每个独立一张 local image，无共享状态。
 *
 * <h2>纯函数</h2>
 * 静态方法、无可变状态；{@link #render} 可并发调用（每次分配新 local image）。
 */
public final class GlowRenderer {

    private static final Pattern HEX_RE = Pattern.compile("^#([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?$");

    private GlowRenderer() {}

    /**
     * 在 {@code mainG} 上画发光效果（叠底层）。
     *
     * @param mainG  主画布 Graphics2D（TYPE_INT_RGB，drawImage 时自动 SRC_OVER 合成）
     * @param glyphs TextLayout 产出的字形位置列表
     * @param font   已按 fontSize 派生的 Font（从 {@code mainG.getFont()} 取也可）
     * @param glow   发光参数；{@code radius <= 0} 直接 no-op
     */
    public static void render(Graphics2D mainG, List<TextLayout.PositionedGlyph> glyphs,
                              Font font, Glow glow) {
        if (glow == null || glow.radius() <= 0 || glyphs.isEmpty()) return;

        int radius = glow.radius();
        FontMetrics fm = mainG.getFontMetrics(font);
        int ascent = fm.getAscent();
        int descent = fm.getDescent();

        // 1) 外接矩形 + padding
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (TextLayout.PositionedGlyph pg : glyphs) {
            int chW = fm.charWidth(pg.ch().charAt(0));
            if (pg.x() < minX) minX = pg.x();
            if (pg.x() + chW > maxX) maxX = pg.x() + chW;
            if (pg.baselineY() - ascent < minY) minY = pg.baselineY() - ascent;
            if (pg.baselineY() + descent > maxY) maxY = pg.baselineY() + descent;
        }
        int pad = radius + 1;
        int bboxX = minX - pad;
        int bboxY = minY - pad;
        int bboxW = (maxX - minX) + pad * 2;
        int bboxH = (maxY - minY) + pad * 2;
        if (bboxW <= 0 || bboxH <= 0) return;

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
                lg.drawString(pg.ch(), pg.x() - bboxX, pg.baselineY() - bboxY);
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
