package moe.hikari.canvas.render;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * Bayer 4×4 有序抖动（M11-B 引入）。契约见 {@code docs/rendering.md §6.6}。
 *
 * <h2>算法</h2>
 * <p>对每个像素 {@code (x, y)} 用 Bayer 矩阵 {@code MATRIX[y%4][x%4]} 取阈值
 * {@code t = bayer / 16 - 0.5}（范围 {@code [-0.5, 0.4375]}），给 R/G/B 分别加
 * {@code t × AMPLITUDE × 2} 的偏移（±{@code AMPLITUDE} RGB 单通道），然后 {@link PaletteLut#matchColor}
 * 量化，最后从 {@link PaletteLut#getColor} 反查 RGB 写回（alpha 保留）。</p>
 *
 * <p>结果：均匀色块上呈现 4×4 周期的细颗粒抖动，让两个最近 palette 色按 Bayer 阈值
 * 交错出现 —— 渐变过渡区域因此能"显示出"非 palette 色（人眼平均后接近原色）。</p>
 *
 * <h2>双端一致性</h2>
 * <p>{@code MATRIX} 是双端镜像的硬约束（{@code web/src/render/BayerDither.ts} 必须用同一数组）。
 * {@link #AMPLITUDE} 同步。前端 dither 用 {@code getImageData} → 同算法 → {@code putImageData}
 * （前端 palette 走构建期 palette.json，与后端共享）。</p>
 *
 * <h2>线程安全</h2>
 * pure helper：仅读 {@link PaletteLut} 只读快照 + 修改传入 {@link BufferedImage}。同一图片不可并发，
 * 但不同图片并发安全。
 */
public final class BayerDither {

    /** 4×4 Bayer 矩阵，索引 {@code [y%4][x%4]}，值域 {@code 0..15}。 */
    public static final int[][] MATRIX = {
            { 0,  8,  2, 10},
            {12,  4, 14,  6},
            { 3, 11,  1,  9},
            {15,  7, 13,  5}
    };

    /** 矩阵规模（行=列=4）。双端镜像硬约束。 */
    public static final int SIZE = 4;

    /**
     * 单通道偏移幅度（RGB 各通道 ±{@code AMPLITUDE} 噪声）。
     *
     * <p>{@code 16 / 255 ≈ 6.3%}：偏弱抖动，避免对 clean 区块产生过强斑点；
     * 渐变过渡条带依然能被 248 色 palette 显著打散。</p>
     */
    public static final int AMPLITUDE = 16;

    private BayerDither() {}

    /**
     * 取归一化 Bayer 阈值 {@code [-0.5, 0.4375]}。
     *
     * <p>前后端 dither 必须用同一公式，否则 4×4 周期相位会错。</p>
     */
    public static double threshold(int x, int y) {
        return MATRIX[y & 3][x & 3] / 16.0 - 0.5;
    }

    /**
     * 对 ARGB {@link BufferedImage} 原地 dither。{@code alpha < 128} 的像素跳过（透明），
     * 其余像素加 Bayer offset → palette 量化 → 反查 RGB 写回。
     *
     * <p>性能：单次像素操作 = {@code getImageData + bayer + matchColor + getColor + putImageData}，
     * 1280×1280 主流画布约 1-2 ms（单线程，O(N) 比 LUT 慢一倍仍可接受）。</p>
     */
    public static void apply(BufferedImage img, PaletteLut palette) {
        if (img == null || palette == null) return;
        int w = img.getWidth();
        int h = img.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int argb = row[x];
                int alpha = (argb >>> 24) & 0xff;
                if (alpha < PaletteLut.ALPHA_THRESHOLD) continue;
                int r = (argb >> 16) & 0xff;
                int g = (argb >> 8) & 0xff;
                int b = argb & 0xff;
                double t = threshold(x, y);
                int offset = (int) Math.round(t * AMPLITUDE * 2);
                int dr = clamp(r + offset);
                int dg = clamp(g + offset);
                int db = clamp(b + offset);
                byte idx = palette.matchColor(dr, dg, db);
                Color qc = palette.getColor(idx);
                if (qc == null) continue;
                row[x] = (alpha << 24)
                        | (qc.getRed() << 16)
                        | (qc.getGreen() << 8)
                        | qc.getBlue();
            }
            img.setRGB(0, y, w, 1, row, 0, w);
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
