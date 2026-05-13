package moe.hikari.canvas.render;

import moe.hikari.canvas.state.BlendMode;

import java.awt.image.BufferedImage;

/**
 * M8-E v1 混合模式实现。契约见 {@code docs/rendering.md §6.6}。
 *
 * <h2>公式（per-channel，输入输出均 [0, 1] float）</h2>
 * <pre>
 *   normal:   out = src
 *   multiply: out = src * dst
 *   screen:   out = 1 - (1 - src) * (1 - dst)
 *   overlay:  if dst < 0.5: out = 2 * src * dst
 *             else:         out = 1 - 2 * (1 - src) * (1 - dst)
 * </pre>
 *
 * <h2>合成（W3C blending 简化版）</h2>
 * <pre>
 *   for each pixel:
 *     src_a = (srcAlpha / 255) * layerOpacity
 *     if src_a == 0: skip
 *     blend_rgb = blendChannel(srcRGB, dstRGB, mode)   // per channel
 *     out_rgb   = blend_rgb * src_a + dst_rgb * (1 - src_a)
 *     out_a     = src_a + dst_a * (1 - src_a)         // dst 是主 buffer (RGB) → dst_a 视为 1
 * </pre>
 *
 * <p><b>注意：</b> 主 buffer 是 {@code TYPE_INT_RGB}（无 alpha 通道），合成时
 * 目标 alpha 视为 1。{@link #applyBlendModeOver} 输出永远是不透明 RGB。</p>
 *
 * <h2>双端一致性</h2>
 * 前端 {@code web/src/render/BlendModes.ts} 必须与本类逐行公式一致；任何一边修改要
 * 同步另一边，否则双端预览像素漂移。
 */
public final class BlendModes {

    private BlendModes() {}

    /**
     * 把 src（ARGB layer buffer）按 layer.opacity + layer.blendMode 合成到 dst（RGB 主 buffer）。
     *
     * <p>per-pixel 循环；对 8×4 wall（1024×512 = 524k 像素）约 ~3M 浮点 op，本机约 30ms。
     * 仅在 layer.opacity ≠ 1 / blendMode ≠ NORMAL 时调用（fast path 走 Graphics2D 原生合成）。</p>
     *
     * @param dst          主 RGB BufferedImage（被 mutate）
     * @param src          层 ARGB BufferedImage
     * @param layerOpacity 0.0–1.0
     * @param mode         混合模式
     */
    public static void applyBlendModeOver(BufferedImage dst, BufferedImage src,
                                          float layerOpacity, BlendMode mode) {
        if (layerOpacity <= 0f) return;
        int w = Math.min(dst.getWidth(), src.getWidth());
        int h = Math.min(dst.getHeight(), src.getHeight());
        int[] dstRow = new int[w];
        int[] srcRow = new int[w];
        for (int y = 0; y < h; y++) {
            dst.getRGB(0, y, w, 1, dstRow, 0, w);
            src.getRGB(0, y, w, 1, srcRow, 0, w);
            for (int x = 0; x < w; x++) {
                int srcArgb = srcRow[x];
                int sa = (srcArgb >>> 24) & 0xff;
                if (sa == 0) continue;
                float srcA = (sa / 255f) * layerOpacity;
                if (srcA <= 0f) continue;

                int sr = (srcArgb >> 16) & 0xff;
                int sg = (srcArgb >> 8) & 0xff;
                int sb = srcArgb & 0xff;
                int dstArgb = dstRow[x];
                int dr = (dstArgb >> 16) & 0xff;
                int dg = (dstArgb >> 8) & 0xff;
                int db = dstArgb & 0xff;

                int br = blendChannel(sr, dr, mode);
                int bg = blendChannel(sg, dg, mode);
                int bb = blendChannel(sb, db, mode);

                // source-over with srcA: out = blend * srcA + dst * (1 - srcA)
                float invA = 1f - srcA;
                int outR = clamp255(Math.round(br * srcA + dr * invA));
                int outG = clamp255(Math.round(bg * srcA + dg * invA));
                int outB = clamp255(Math.round(bb * srcA + db * invA));
                dstRow[x] = 0xff000000 | (outR << 16) | (outG << 8) | outB;
            }
            dst.setRGB(0, y, w, 1, dstRow, 0, w);
        }
    }

    /**
     * 单通道混合（0–255 输入输出）。
     */
    public static int blendChannel(int srcByte, int dstByte, BlendMode mode) {
        float s = srcByte / 255f;
        float d = dstByte / 255f;
        float b;
        switch (mode) {
            case MULTIPLY -> b = s * d;
            case SCREEN -> b = 1f - (1f - s) * (1f - d);
            case OVERLAY -> b = (d < 0.5f) ? (2f * s * d) : (1f - 2f * (1f - s) * (1f - d));
            case NORMAL -> b = s;
            default -> b = s;
        }
        return clamp255(Math.round(b * 255f));
    }

    private static int clamp255(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }
}
