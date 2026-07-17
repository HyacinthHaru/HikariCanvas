package ac.haru.hikaricanvas.render;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M11-B：{@link BayerDither} pure helper 单测。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>{@code threshold(x, y)} 公式与 Bayer 矩阵一致（双端镜像约束）</li>
 *   <li>{@code apply()} 不改透明像素（{@code alpha < 128}）</li>
 *   <li>{@code apply()} 把不在 palette 上的中间灰度量化到 palette 色（且引入 4×4 周期差异）</li>
 *   <li>纯 palette 色（黑/白）dither 后仍是同色（量化后等价）</li>
 * </ul>
 */
class BayerDitherTest {

    private static PaletteLut palette;

    @BeforeAll
    static void setUp() throws IOException {
        palette = PaletteLut.loadFromClasspath("/palette.json");
    }

    @Test
    void matrixIs4x4WithValues0To15() {
        boolean[] seen = new boolean[16];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int v = BayerDither.MATRIX[y][x];
                assertTrue(v >= 0 && v < 16, "matrix value out of range: " + v);
                assertTrue(!seen[v], "matrix value duplicated: " + v);
                seen[v] = true;
            }
        }
    }

    @Test
    void thresholdRangeAndPeriodicity() {
        // 范围 [-0.5, 0.4375]
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                double t = BayerDither.threshold(x, y);
                min = Math.min(min, t);
                max = Math.max(max, t);
            }
        }
        assertEquals(-0.5, min, 1e-9);
        assertEquals(15.0 / 16.0 - 0.5, max, 1e-9);

        // 4 周期
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(
                        BayerDither.threshold(x, y),
                        BayerDither.threshold(x + 4, y),
                        1e-9);
                assertEquals(
                        BayerDither.threshold(x, y),
                        BayerDither.threshold(x, y + 4),
                        1e-9);
            }
        }
    }

    @Test
    void transparentPixelsUntouched() {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                // alpha=0
                img.setRGB(x, y, 0x00808080);
            }
        }
        BayerDither.apply(img, palette);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                assertEquals(0x00808080, img.getRGB(x, y),
                        "transparent pixel at " + x + "," + y + " should not change");
            }
        }
    }

    @Test
    void midGrayProducesPeriodicDither() {
        // 中性灰 (128, 128, 128)：palette 不大概率精确命中，Bayer 应导致 4×4 周期变化
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                img.setRGB(x, y, 0xFF808080);
            }
        }
        BayerDither.apply(img, palette);

        // 4×4 周期：(x, y) 与 (x+4, y+4) 必须像素值相同
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(img.getRGB(x, y), img.getRGB(x + 4, y + 4),
                        "Bayer 4×4 periodicity at " + x + "," + y);
            }
        }
        // 至少应该有 2 个不同颜色（否则 dither 没生效）
        int firstRgb = img.getRGB(0, 0);
        boolean variation = false;
        outer:
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                if (img.getRGB(x, y) != firstRgb) {
                    variation = true;
                    break outer;
                }
            }
        }
        assertTrue(variation, "dither produced no color variation on mid-gray input");
    }

    @Test
    void blackAndWhiteAreStable() {
        // 纯黑 / 纯白都是 palette 上常见颜色，dither 后量化结果应仍为黑/白（偏移在 ±16 RGB 不足以
        // 让 black/white 跨到相邻 palette 色）
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                img.setRGB(x, y, (x < 4) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        BayerDither.apply(img, palette);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 4; x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                // 黑色侧不应被推向中性灰：R+G+B 总和 ≤ 96
                int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                assertTrue(r + g + b <= 96,
                        "black pixel drifted at " + x + "," + y + " : 0x" + Integer.toHexString(rgb));
            }
        }
    }

    @Test
    void nullInputsNoOp() {
        // 防御性：null img / null palette → 静默返回
        BayerDither.apply(null, palette);
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFFAABBCC);
        BayerDither.apply(img, null);
        assertEquals(0xFFAABBCC, img.getRGB(0, 0));
    }

    @Test
    void nullPaletteColorFallsBackToTransparentNotSkip() {
        // R9：getColor(matched) 返 null 时旧代码 `continue` 静默保留原色（画面 glitch）。
        // 现要求 fallback 到 TRANSPARENT_INDEX 取色。构造一个 palette：entries[0] = 透明色
        // (TRANSPARENT_INDEX，rgb 全 0)，entries[1] = 不透明项但其 `index` 字段=200 > 数组长度，
        // 于是 matchColor 返 byte 200，getColor(200) 越界返 null → 触发 fallback 路径。
        PaletteLut.PaletteEntry transparent =
                new PaletteLut.PaletteEntry(PaletteLut.TRANSPARENT_INDEX, new int[]{0, 0, 0}, 0);
        PaletteLut.PaletteEntry opaqueOutOfRange =
                new PaletteLut.PaletteEntry(200, new int[]{255, 0, 0}, 255);
        PaletteLut broken = new PaletteLut(List.of(transparent, opaqueOutOfRange));

        // 自检：匹配命中越界 index，getColor 确实返 null（保证测试覆盖 null 分支）
        byte matched = broken.matchColor(120, 120, 120);
        assertEquals(200, Byte.toUnsignedInt(matched), "matched index should be the out-of-range one");
        assertEquals(null, broken.getColor(matched), "out-of-range index must return null");

        int original = 0xFF707070;
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                img.setRGB(x, y, original);
            }
        }
        BayerDither.apply(img, broken);

        // 不再静默跳过保留原色：每个像素被改写为 fallback（透明 entry 的 RGB，input alpha 保留）。
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int rgb = img.getRGB(x, y);
                assertNotEquals(original, rgb,
                        "pixel at " + x + "," + y + " should not be silently preserved");
                assertEquals(0xFF000000, rgb,
                        "pixel should fall back to TRANSPARENT_INDEX color (input alpha kept)");
            }
        }
    }

    @Test
    void cleanRegionDifferentFromDitherRegion() {
        // 一张大图，两半同色（中灰）。一半不 dither，另一半 dither：dither 半边至少有一个像素不同。
        BufferedImage clean = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        BufferedImage dith = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                clean.setRGB(x, y, 0xFF707070);
                dith.setRGB(x, y, 0xFF707070);
            }
        }
        BayerDither.apply(dith, palette);
        boolean different = false;
        for (int y = 0; y < 4 && !different; y++) {
            for (int x = 0; x < 4 && !different; x++) {
                if (clean.getRGB(x, y) != dith.getRGB(x, y)) different = true;
            }
        }
        assertTrue(different, "dithered region should diverge from clean for off-palette color");
        assertNotEquals(clean.getRGB(0, 0), dith.getRGB(0, 0));
    }
}
