package ac.haru.hikaricanvas.render;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R10：损坏 / 篡改的 {@code palette.json} 含负 / &gt;255 rgb 分量时，{@link PaletteLut} 构造器
 * 应就地 clamp 到 [0,255] 而非抛异常（单条坏色不拖垮启动），且 {@link PaletteLut#matchColor}
 * 仍正常工作。正常 palette 行为不变。
 */
class PaletteLutTest {

    private static PaletteLut.PaletteEntry entry(int index, int r, int g, int b, int alpha) {
        return new PaletteLut.PaletteEntry(index, new int[]{r, g, b}, alpha);
    }

    @Test
    void outOfRangeRgbComponentsDoNotThrow() {
        // 含越界分量：负值 [-1,0,0] 与 >255 值 [300,0,0]
        List<PaletteLut.PaletteEntry> palette = List.of(
                entry(0, 0, 0, 0, 0),          // 透明
                entry(1, -1, 0, 0, 255),       // 负分量
                entry(2, 300, 0, 0, 255),      // 超 255 分量
                entry(3, 255, 255, 255, 255)   // 正常白
        );
        PaletteLut lut = assertDoesNotThrow(() -> new PaletteLut(palette));
        // matchColor 仍正常返回某个不透明索引
        byte idx = assertDoesNotThrow(() -> lut.matchColor(255, 255, 255));
        assertTrue(idx >= 0 && idx < lut.size());
    }

    @Test
    void outOfRangeRgbClampedInGetColor() {
        List<PaletteLut.PaletteEntry> palette = List.of(
                entry(0, 0, 0, 0, 0),
                entry(1, -1, 300, 128, 255),
                entry(2, 255, 255, 255, 255)
        );
        PaletteLut lut = new PaletteLut(palette);
        // index 1 的 rgb 被就地 clamp：-1 → 0，300 → 255，128 不变
        Color c = lut.getColor((byte) 1);
        assertNotNull(c);
        assertEquals(0, c.getRed());
        assertEquals(255, c.getGreen());
        assertEquals(128, c.getBlue());
    }

    @Test
    void normalPaletteMatchesNearestColor() {
        // 正常 palette：红 / 绿 / 蓝 三不透明项 + 透明项，行为不变
        List<PaletteLut.PaletteEntry> palette = List.of(
                entry(0, 0, 0, 0, 0),          // 透明
                entry(1, 255, 0, 0, 255),      // 红
                entry(2, 0, 255, 0, 255),      // 绿
                entry(3, 0, 0, 255, 255)       // 蓝
        );
        PaletteLut lut = new PaletteLut(palette);
        // 接近纯红的色应匹配到红 index 1
        assertEquals(1, lut.matchColor(250, 5, 5));
        // 接近纯绿的色应匹配到绿 index 2
        assertEquals(2, lut.matchColor(5, 250, 5));
        // alpha < 128 直接返回透明
        assertEquals(PaletteLut.TRANSPARENT_INDEX, lut.matchColor(250, 5, 5, 0));
    }

    @Test
    void builtInPaletteJsonStillLoads() {
        // 构建期 palette.json 行为不变
        PaletteLut lut = assertDoesNotThrow(() -> PaletteLut.loadFromClasspath("/palette.json"));
        assertTrue(lut.size() > 0);
        assertDoesNotThrow(() -> lut.matchColor(128, 128, 128));
    }
}
