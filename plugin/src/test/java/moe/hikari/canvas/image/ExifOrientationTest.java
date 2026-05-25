package moe.hikari.canvas.image;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-05-25 项 4：{@link ExifOrientation} 行为校验。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>EXIF TIFF 解析（II / MM byte order，Orientation tag = 0x0112）</li>
 *   <li>非 JPEG / 损坏 / 缺 EXIF 输入的 fail-soft 行为</li>
 *   <li>applyOrientation 在各种值下的 w/h 互换 + 像素位置正确性</li>
 * </ul>
 */
class ExifOrientationTest {

    // ---------- parseExifOrientation ----------

    @Test
    void parsesLittleEndianOrientation6() {
        // 构造 II + Orientation=6（顺时针 90°）的最小 EXIF App1 payload
        byte[] data = buildExifApp1(true, 6);
        assertEquals(6, ExifOrientation.parseExifOrientation(data));
    }

    @Test
    void parsesBigEndianOrientation3() {
        byte[] data = buildExifApp1(false, 3);
        assertEquals(3, ExifOrientation.parseExifOrientation(data));
    }

    @Test
    void rejectsInvalidMagic() {
        byte[] data = buildExifApp1(true, 6);
        // 破坏 TIFF magic 0x002A
        data[6 + 2] = 0x00;
        data[6 + 3] = 0x00;
        assertEquals(1, ExifOrientation.parseExifOrientation(data));
    }

    @Test
    void emptyOrShortInputReturnsOne() {
        assertEquals(1, ExifOrientation.parseExifOrientation(null));
        assertEquals(1, ExifOrientation.parseExifOrientation(new byte[0]));
        assertEquals(1, ExifOrientation.parseExifOrientation(new byte[4]));
    }

    @Test
    void nonExifPrefixStillParses() {
        // 没有 "Exif\0\0" 前缀也应能从位置 0 开始读 II
        byte[] full = buildExifApp1(true, 8);
        byte[] noPrefix = new byte[full.length - 6];
        System.arraycopy(full, 6, noPrefix, 0, noPrefix.length);
        assertEquals(8, ExifOrientation.parseExifOrientation(noPrefix));
    }

    @Test
    void outOfRangeOrientationReturnsOne() {
        byte[] data = buildExifApp1(true, 99);
        assertEquals(1, ExifOrientation.parseExifOrientation(data));
    }

    // ---------- read（top-level） ----------

    @Test
    void readNonJpegReturnsOne() {
        byte[] png = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
        assertEquals(1, ExifOrientation.read(png));
    }

    @Test
    void readCorruptedJpegReturnsOne() {
        // 像 JPEG 的开头但没 EXIF data
        byte[] fake = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0 };
        assertEquals(1, ExifOrientation.read(fake));
    }

    // ---------- applyOrientation ----------

    /** 创建 2x3 测试图：颜色按位置编码（便于验证翻转 / 旋转结果）。 */
    private static BufferedImage testImage() {
        // 2 列 3 行：
        //   (0,0)=R  (1,0)=G
        //   (0,1)=B  (1,1)=W
        //   (0,2)=K  (1,2)=Y
        BufferedImage img = new BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFFFF0000);  // R
        img.setRGB(1, 0, 0xFF00FF00);  // G
        img.setRGB(0, 1, 0xFF0000FF);  // B
        img.setRGB(1, 1, 0xFFFFFFFF);  // W
        img.setRGB(0, 2, 0xFF000000);  // K
        img.setRGB(1, 2, 0xFFFFFF00);  // Y
        return img;
    }

    @Test
    void orientation1ReturnsSameImage() {
        BufferedImage src = testImage();
        BufferedImage out = ExifOrientation.applyOrientation(src, 1);
        assertSame(src, out);
    }

    @Test
    void orientation6SwapsDimensions() {
        BufferedImage src = testImage();  // 2x3
        BufferedImage out = ExifOrientation.applyOrientation(src, 6);  // 顺时针 90°
        assertEquals(3, out.getWidth());
        assertEquals(2, out.getHeight());
        // 原 (0,0) R 应该出现在新图右上 (2, 0)
        assertEquals(0xFFFF0000, out.getRGB(2, 0));
        // 原 (1,0) G 应该出现在新图 (2, 1)
        assertEquals(0xFF00FF00, out.getRGB(2, 1));
        // 原 (0,2) K 应该出现在新图 (0, 0)
        assertEquals(0xFF000000, out.getRGB(0, 0));
    }

    @Test
    void orientation8SwapsDimensions() {
        BufferedImage src = testImage();  // 2x3
        BufferedImage out = ExifOrientation.applyOrientation(src, 8);  // 顺时针 270° / 逆时针 90°
        assertEquals(3, out.getWidth());
        assertEquals(2, out.getHeight());
        // 原 (0,0) R 应该出现在新图左下 (0, 1)
        assertEquals(0xFFFF0000, out.getRGB(0, 1));
        // 原 (1,2) Y 应该出现在新图右上 (2, 0)
        assertEquals(0xFFFFFF00, out.getRGB(2, 0));
    }

    @Test
    void orientation3KeepsDimensionsRotates180() {
        BufferedImage src = testImage();  // 2x3
        BufferedImage out = ExifOrientation.applyOrientation(src, 3);
        assertEquals(2, out.getWidth());
        assertEquals(3, out.getHeight());
        // 原 (0,0) R 应到 (1, 2)
        assertEquals(0xFFFF0000, out.getRGB(1, 2));
        // 原 (1,2) Y 应到 (0, 0)
        assertEquals(0xFFFFFF00, out.getRGB(0, 0));
    }

    @Test
    void orientation2FlipsHorizontal() {
        BufferedImage src = testImage();  // 2x3
        BufferedImage out = ExifOrientation.applyOrientation(src, 2);
        assertEquals(2, out.getWidth());
        assertEquals(3, out.getHeight());
        // R(0,0) ↔ G(1,0)
        assertEquals(0xFFFF0000, out.getRGB(1, 0));
        assertEquals(0xFF00FF00, out.getRGB(0, 0));
    }

    @Test
    void orientationOutOfRangeReturnsSrc() {
        BufferedImage src = testImage();
        assertSame(src, ExifOrientation.applyOrientation(src, 9));
        assertSame(src, ExifOrientation.applyOrientation(src, 0));
        assertSame(src, ExifOrientation.applyOrientation(src, -1));
    }

    @Test
    void autoRotateJpegFailsoftOnEmptyBytes() {
        BufferedImage src = testImage();
        // null + empty bytes 都视为 orientation=1（read 早返 1）
        BufferedImage out1 = ExifOrientation.autoRotateJpeg(null, src, null);
        BufferedImage out2 = ExifOrientation.autoRotateJpeg(new byte[0], src, null);
        assertSame(src, out1);
        assertSame(src, out2);
    }

    // ---------- helpers ----------

    /**
     * 构造一个最小可解析的 EXIF App1 payload（仅含一个 Orientation IFD entry）。
     *
     * 结构：
     *   "Exif\0\0" (6) +
     *   TIFF header (8: II/MM + magic + ifd0 offset) +
     *   IFD0 (2 entry count + 1 entry × 12 字节)
     */
    private static byte[] buildExifApp1(boolean littleEndian, int orientation) {
        // 6 + 8 + 2 + 12 + 4(next IFD offset, 0)
        byte[] out = new byte[6 + 8 + 2 + 12 + 4];
        // "Exif\0\0"
        out[0] = 'E'; out[1] = 'x'; out[2] = 'i'; out[3] = 'f'; out[4] = 0; out[5] = 0;
        int p = 6;
        if (littleEndian) {
            out[p] = 'I'; out[p + 1] = 'I';
        } else {
            out[p] = 'M'; out[p + 1] = 'M';
        }
        // magic 0x002A
        writeU16(out, p + 2, 0x002A, littleEndian);
        // IFD0 offset = 8（从 TIFF 头算起）
        writeU32(out, p + 4, 8, littleEndian);
        // IFD0 entries 在 p+8 处
        int ifd0 = p + 8;
        // entry count = 1
        writeU16(out, ifd0, 1, littleEndian);
        // entry: tag=0x0112, type=3(SHORT), count=1, value=orientation
        int eOff = ifd0 + 2;
        writeU16(out, eOff, 0x0112, littleEndian);
        writeU16(out, eOff + 2, 3, littleEndian);
        writeU32(out, eOff + 4, 1, littleEndian);
        writeU16(out, eOff + 8, orientation, littleEndian);
        // 高 2 字节余 0 + 末尾 4 字节 next IFD offset = 0
        return out;
    }

    private static void writeU16(byte[] d, int off, int v, boolean le) {
        if (le) {
            d[off] = (byte) (v & 0xff);
            d[off + 1] = (byte) ((v >> 8) & 0xff);
        } else {
            d[off] = (byte) ((v >> 8) & 0xff);
            d[off + 1] = (byte) (v & 0xff);
        }
    }

    private static void writeU32(byte[] d, int off, long v, boolean le) {
        if (le) {
            d[off] = (byte) (v & 0xff);
            d[off + 1] = (byte) ((v >> 8) & 0xff);
            d[off + 2] = (byte) ((v >> 16) & 0xff);
            d[off + 3] = (byte) ((v >> 24) & 0xff);
        } else {
            d[off] = (byte) ((v >> 24) & 0xff);
            d[off + 1] = (byte) ((v >> 16) & 0xff);
            d[off + 2] = (byte) ((v >> 8) & 0xff);
            d[off + 3] = (byte) (v & 0xff);
        }
    }
}
