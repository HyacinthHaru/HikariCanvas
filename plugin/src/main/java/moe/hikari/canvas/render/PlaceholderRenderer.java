package moe.hikari.canvas.render;

import java.util.Arrays;

/**
 * Placeholder 地图像素渲染，契约见 {@code docs/architecture.md §4.4}。
 *
 * <p>每张 128×128：</p>
 * <ul>
 *   <li>浅灰背景（palette {@link #BG_PALETTE}）</li>
 *   <li>顶部居中大字 "HIKARICANVAS"（水印，scale=1）</li>
 *   <li>底部居中 "N/M" 位置标签（scale=3）</li>
 * </ul>
 *
 * <p>M2 阶段使用 {@link BitmapFont}；M4 渲染引擎接入真 TTF 字体后替换为真实渲染 +
 * "HikariCanvas" 大小写混排。</p>
 *
 * <p>调色板索引的精确值待 M4 调色板 LUT 接入后修正；当前选了经验值：
 * {@link #BG_PALETTE} 偏浅、{@link #FG_PALETTE} 偏深，对比足够 M2 demo。</p>
 *
 * <p>线程安全：{@link #render} 不持有状态，可并发调用。</p>
 */
public final class PlaceholderRenderer {

    /** 浅色背景 palette 索引（M2 经验值，M4 调色板 LUT 后修正）。 */
    public static final byte BG_PALETTE = 33;
    /** 深色前景 palette 索引。 */
    public static final byte FG_PALETTE = 44;

    public static final int MAP_SIZE = 128;

    private static final String WATERMARK = "HIKARICANVAS";

    /**
     * 渲染一张占位图。
     *
     * @param slotIndex  该地图在墙面矩阵中的序号（0-based）
     * @param totalSlots 墙面总地图数
     * @return 128×128 = 16384 字节的 palette 索引数组
     */
    public byte[] render(int slotIndex, int totalSlots) {
        if (slotIndex < 0 || totalSlots <= 0 || slotIndex >= totalSlots) {
            throw new IllegalArgumentException(
                    "invalid slot: index=" + slotIndex + " total=" + totalSlots);
        }
        byte[] pixels = new byte[MAP_SIZE * MAP_SIZE];
        Arrays.fill(pixels, BG_PALETTE);

        // 顶部：HIKARICANVAS（scale=1）
        // 12 字符 × (5+1) 像素 - 1 = 71 像素宽；居中 → x=(128-71)/2=28，y=12
        drawCenteredText(pixels, 12, WATERMARK, FG_PALETTE, 1);

        // 底部：N/M 位置标签（scale=3）
        String label = (slotIndex + 1) + "/" + totalSlots;
        // 字符高 7×3=21；底部留 10 像素空 → y=128-21-10 = 97
        drawCenteredText(pixels, 97, label, FG_PALETTE, 3);

        return pixels;
    }

    private static void drawCenteredText(byte[] pixels, int y, String text, byte color, int scale) {
        int charCount = text.length();
        int stride = BitmapFont.CHAR_WIDTH + 1;        // 字符间 1 像素间距
        int widthPx = (charCount * stride - 1) * scale;
        int startX = Math.max(0, (MAP_SIZE - widthPx) / 2);
        drawText(pixels, startX, y, text, color, scale);
    }

    private static void drawText(byte[] pixels, int x, int y, String text, byte color, int scale) {
        int cursor = x;
        for (int i = 0; i < text.length(); i++) {
            int[] glyph = BitmapFont.glyph(text.charAt(i));
            drawGlyph(pixels, cursor, y, glyph, color, scale);
            cursor += (BitmapFont.CHAR_WIDTH + 1) * scale;
        }
    }

    private static void drawGlyph(byte[] pixels, int x, int y, int[] glyph, byte color, int scale) {
        for (int row = 0; row < BitmapFont.CHAR_HEIGHT; row++) {
            int rowBits = glyph[row];
            for (int col = 0; col < BitmapFont.CHAR_WIDTH; col++) {
                boolean on = (rowBits & (1 << (BitmapFont.CHAR_WIDTH - 1 - col))) != 0;
                if (!on) continue;
                // 画 scale×scale 像素块
                int px0 = x + col * scale;
                int py0 = y + row * scale;
                for (int dy = 0; dy < scale; dy++) {
                    int py = py0 + dy;
                    if (py < 0 || py >= MAP_SIZE) continue;
                    int base = py * MAP_SIZE;
                    for (int dx = 0; dx < scale; dx++) {
                        int px = px0 + dx;
                        if (px < 0 || px >= MAP_SIZE) continue;
                        pixels[base + px] = color;
                    }
                }
            }
        }
    }
}
