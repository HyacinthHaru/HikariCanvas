package moe.hikari.canvas.render;

import moe.hikari.canvas.state.BlendMode;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8-E：{@link BlendModes} 公式与合成行为校验。
 *
 * <p>关键不变量：</p>
 * <ul>
 *   <li>normal: out = src（与 src 同色，不依赖 dst）</li>
 *   <li>multiply: out ≤ min(src, dst)（不变亮）</li>
 *   <li>screen: out ≥ max(src, dst)（不变暗）</li>
 *   <li>overlay 0/255 边界：与 multiply / screen 等价</li>
 *   <li>layerOpacity = 0 → 不修改 dst</li>
 *   <li>srcAlpha = 0 → 不修改 dst</li>
 * </ul>
 *
 * <p>这套断言保证前端 BlendModes.ts 必须用同一公式，否则双端预览像素漂移。</p>
 */
class BlendModesTest {

    @Test
    void normalReturnsSrc() {
        assertEquals(100, BlendModes.blendChannel(100, 0, BlendMode.NORMAL));
        assertEquals(255, BlendModes.blendChannel(255, 0, BlendMode.NORMAL));
        assertEquals(0, BlendModes.blendChannel(0, 200, BlendMode.NORMAL));
    }

    @Test
    void multiplyDarkens() {
        // 1.0 * 1.0 = 1.0 → 255
        assertEquals(255, BlendModes.blendChannel(255, 255, BlendMode.MULTIPLY));
        // 0 * anything = 0
        assertEquals(0, BlendModes.blendChannel(0, 255, BlendMode.MULTIPLY));
        assertEquals(0, BlendModes.blendChannel(255, 0, BlendMode.MULTIPLY));
        // 128 * 128 = 0.5 * 0.5 = 0.25 → 64
        int r = BlendModes.blendChannel(128, 128, BlendMode.MULTIPLY);
        assertTrue(r >= 63 && r <= 65, "expected ~64, got " + r);
    }

    @Test
    void screenLightens() {
        // 1.0, 1.0 → 1.0
        assertEquals(255, BlendModes.blendChannel(255, 0, BlendMode.SCREEN));
        assertEquals(255, BlendModes.blendChannel(0, 255, BlendMode.SCREEN));
        // 0, 0 → 0
        assertEquals(0, BlendModes.blendChannel(0, 0, BlendMode.SCREEN));
        // 128 + 128 - 128*128/255 = 1 - (1-0.5)(1-0.5) = 0.75 → 191/192
        int r = BlendModes.blendChannel(128, 128, BlendMode.SCREEN);
        assertTrue(r >= 190 && r <= 193, "expected ~192, got " + r);
    }

    @Test
    void overlayBranches() {
        // dst < 0.5 → 2 * s * d
        // s=255, d=64: dst/255=0.25 < 0.5 → 2 * 1.0 * 0.25 = 0.5 → 128
        int r1 = BlendModes.blendChannel(255, 64, BlendMode.OVERLAY);
        assertTrue(r1 >= 127 && r1 <= 129, "expected ~128, got " + r1);

        // dst >= 0.5 → 1 - 2 * (1-s)(1-d)
        // s=128, d=200: 1 - 2 * (1-0.5)(1-0.784) = 1 - 0.216 ≈ 0.784 → 200
        int r2 = BlendModes.blendChannel(128, 200, BlendMode.OVERLAY);
        assertTrue(r2 >= 198 && r2 <= 202, "expected ~200, got " + r2);
    }

    @Test
    void multiplyNotLighter() {
        for (int s = 0; s <= 255; s += 17) {
            for (int d = 0; d <= 255; d += 17) {
                int out = BlendModes.blendChannel(s, d, BlendMode.MULTIPLY);
                assertTrue(out <= Math.min(s, d) + 1,  // +1 for round-up tolerance
                        "multiply out " + out + " > min(" + s + "," + d + ")");
            }
        }
    }

    @Test
    void screenNotDarker() {
        for (int s = 0; s <= 255; s += 17) {
            for (int d = 0; d <= 255; d += 17) {
                int out = BlendModes.blendChannel(s, d, BlendMode.SCREEN);
                assertTrue(out >= Math.max(s, d) - 1,
                        "screen out " + out + " < max(" + s + "," + d + ")");
            }
        }
    }

    // ---------- applyBlendModeOver 整体行为 ----------

    @Test
    void applyZeroOpacityNoop() {
        BufferedImage dst = solidRgb(4, 4, 0xff8080ff);
        BufferedImage src = solidArgb(4, 4, 0xff00ff00);
        BlendModes.applyBlendModeOver(dst, src, 0f, BlendMode.NORMAL);
        // dst 不应被修改
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(0xff8080ff, dst.getRGB(x, y) & 0xffffffff);
            }
        }
    }

    @Test
    void applyTransparentSrcNoop() {
        BufferedImage dst = solidRgb(4, 4, 0xff8080ff);
        BufferedImage src = solidArgb(4, 4, 0x00ff0000);  // fully transparent
        BlendModes.applyBlendModeOver(dst, src, 1f, BlendMode.NORMAL);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(0xff8080ff, dst.getRGB(x, y) & 0xffffffff);
            }
        }
    }

    @Test
    void applyFullNormalReplacesDst() {
        BufferedImage dst = solidRgb(4, 4, 0xff8080ff);
        BufferedImage src = solidArgb(4, 4, 0xffff0000);  // opaque red
        BlendModes.applyBlendModeOver(dst, src, 1f, BlendMode.NORMAL);
        // 完全不透明 + opacity 1 + normal → 完全替换
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(0xffff0000, dst.getRGB(x, y) & 0xffffffff);
            }
        }
    }

    @Test
    void applyHalfOpacityBlendsHalfway() {
        BufferedImage dst = solidRgb(2, 2, 0xff000000);  // black
        BufferedImage src = solidArgb(2, 2, 0xffffffff);  // opaque white
        BlendModes.applyBlendModeOver(dst, src, 0.5f, BlendMode.NORMAL);
        // 半透明白 + 黑 = 灰（约 128）
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                int rgb = dst.getRGB(x, y) & 0xffffff;
                int r = (rgb >> 16) & 0xff;
                assertTrue(r >= 127 && r <= 129,
                        "expected ~128, got " + r);
            }
        }
    }

    @Test
    void applyMultiplyBlackKeepsBlack() {
        BufferedImage dst = solidRgb(2, 2, 0xffff0000);  // red
        BufferedImage src = solidArgb(2, 2, 0xff000000);  // opaque black
        BlendModes.applyBlendModeOver(dst, src, 1f, BlendMode.MULTIPLY);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                int rgb = dst.getRGB(x, y) & 0xffffff;
                // multiply red * black = black (per-channel)
                assertEquals(0x000000, rgb);
            }
        }
    }

    @Test
    void applyScreenWhiteKeepsWhite() {
        BufferedImage dst = solidRgb(2, 2, 0xffff0000);  // red
        BufferedImage src = solidArgb(2, 2, 0xffffffff);  // opaque white
        BlendModes.applyBlendModeOver(dst, src, 1f, BlendMode.SCREEN);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                int rgb = dst.getRGB(x, y) & 0xffffff;
                // screen any with white = white
                assertEquals(0xffffff, rgb);
            }
        }
    }

    // ---------- helpers ----------

    private static BufferedImage solidRgb(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) img.setRGB(x, y, argb);
        }
        return img;
    }

    private static BufferedImage solidArgb(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) img.setRGB(x, y, argb);
        }
        return img;
    }
}
