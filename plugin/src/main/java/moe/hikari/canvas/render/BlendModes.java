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
 * <h2>合成（W3C source-over 标准）</h2>
 * <pre>
 *   for each pixel:
 *     src_a = (srcAlpha / 255) * layerOpacity
 *     if src_a == 0: skip
 *     dst_a = dstAlpha / 255
 *     blend_rgb = blendChannel(srcRGB, dstRGB, mode)   // per channel
 *     out_a   = src_a + dst_a * (1 - src_a)
 *     out_rgb = (blend_rgb * src_a + dst_rgb * dst_a * (1 - src_a)) / out_a
 *     # out_a == 0 → 输出全透明 #00000000
 * </pre>
 *
 * <p><b>Ultrareview 2026-05-25 #7（0.4.6 P2 修正）：</b>主 buffer 已升 TYPE_INT_ARGB 支持
 * 透明背景；slow path 必须按真 source-over 合成 alpha 通道，不能再强写 0xFF 不透明
 * （之前 layer.opacity ≠ 1 / blendMode ≠ NORMAL 路径会把背景透明像素变成不透明黑）。</p>
 *
 * <h2>双端一致性</h2>
 * 前端 {@code web/src/render/BlendModes.ts} 必须与本类逐行公式一致；任何一边修改要
 * 同步另一边，否则双端预览像素漂移。
 */
public final class BlendModes {

    private BlendModes() {}

    /**
     * 把 src（ARGB layer buffer）按 layer.opacity + layer.blendMode 合成到 dst（ARGB 主 buffer）。
     *
     * <p>per-pixel 循环；对 8×4 wall（1024×512 = 524k 像素）约 ~3M 浮点 op，本机约 30ms。
     * 仅在 layer.opacity ≠ 1 / blendMode ≠ NORMAL 时调用（fast path 走 Graphics2D 原生合成）。</p>
     *
     * <p>P3-92：dst 是 TYPE_INT_ARGB 主 buffer（0.4.6 P2 起），da 真实参与 source-over 合成，
     * 不强写 0xFF——措辞与实现一致。</p>
     *
     * @param dst          主 ARGB BufferedImage（被 mutate）
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
                int da = (dstArgb >>> 24) & 0xff;
                int dr = (dstArgb >> 16) & 0xff;
                int dg = (dstArgb >> 8) & 0xff;
                int db = dstArgb & 0xff;

                int br = blendChannel(sr, dr, mode);
                int bg = blendChannel(sg, dg, mode);
                int bb = blendChannel(sb, db, mode);

                // Ultrareview 2026-05-25 #7：W3C source-over 完整公式（含 alpha）。
                // outA = srcA + dstA * (1 - srcA)
                // outRGB = (blend * srcA + dst * dstA * (1 - srcA)) / outA
                // 主 buffer 升 ARGB 后 alpha 必须真传递，不能强写 0xFF。
                float dstAf = da / 255f;
                float invA = 1f - srcA;
                float outAf = srcA + dstAf * invA;
                int outR, outG, outB, outA;
                if (outAf <= 0f) {
                    outR = outG = outB = outA = 0;  // 全透明像素
                } else {
                    float weight = dstAf * invA;
                    outR = clamp255(Math.round((br * srcA + dr * weight) / outAf));
                    outG = clamp255(Math.round((bg * srcA + dg * weight) / outAf));
                    outB = clamp255(Math.round((bb * srcA + db * weight) / outAf));
                    outA = clamp255(Math.round(outAf * 255f));
                }
                dstRow[x] = (outA << 24) | (outR << 16) | (outG << 8) | outB;
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
