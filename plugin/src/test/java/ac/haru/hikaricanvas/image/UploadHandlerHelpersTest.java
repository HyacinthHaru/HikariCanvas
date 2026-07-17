package ac.haru.hikaricanvas.image;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13-B：{@link UploadHandler} 静态辅助方法（magic bytes 探测 + bilinear downscale）。
 *
 * <p>HTTP path 完整 e2e（{@code handleUpload}）需 Javalin {@code JavalinTest} 依赖，未引入；
 * 这里只覆盖纯函数辅助，确保压缩炸弹防御层的核心逻辑可回归。</p>
 */
class UploadHandlerHelpersTest {

    // ---------- magic bytes ----------

    @Test
    void detectMagicMimePng() {
        byte[] png = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0
        };
        assertEquals("image/png", UploadHandler.detectMagicMime(png));
    }

    @Test
    void detectMagicMimeJpeg() {
        byte[] jpg = new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0, 0, 0, 0, 0, 0, 0, 0
        };
        assertEquals("image/jpeg", UploadHandler.detectMagicMime(jpg));
    }

    @Test
    void detectMagicMimeWebp() {
        byte[] webp = new byte[]{
                'R', 'I', 'F', 'F', 0, 0, 0, 0,
                'W', 'E', 'B', 'P'
        };
        assertEquals("image/webp", UploadHandler.detectMagicMime(webp));
    }

    @Test
    void detectMagicMimeUnknown() {
        assertNull(UploadHandler.detectMagicMime(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
        assertNull(UploadHandler.detectMagicMime(new byte[]{1, 2}));  // 太短
        assertNull(UploadHandler.detectMagicMime(null));
    }

    @Test
    void detectMagicMimeRejectsRiffButNonWebp() {
        // RIFF 头但容器不是 WEBP（比如 .wav）
        byte[] wav = new byte[]{
                'R', 'I', 'F', 'F', 0, 0, 0, 0,
                'W', 'A', 'V', 'E'
        };
        assertNull(UploadHandler.detectMagicMime(wav));
    }

    // ---------- downscale ----------

    private static BufferedImage solidImage(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(rgb));
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        return img;
    }

    @Test
    void downscaleShrinksToMaxEdge() {
        BufferedImage src = solidImage(2048, 1024, 0xFF0000);
        BufferedImage dst = UploadHandler.downscale(src, 512);
        assertEquals(512, dst.getWidth());
        assertEquals(256, dst.getHeight());
    }

    @Test
    void downscalePreservesAspectRatio() {
        // 9:16 → 缩到 maxEdge=900 时，宽 506.25 → 506
        BufferedImage src = solidImage(900, 1600, 0x00FF00);
        BufferedImage dst = UploadHandler.downscale(src, 800);
        assertEquals(800, dst.getHeight());
        // 宽度按比例：900 * 800 / 1600 = 450
        assertEquals(450, dst.getWidth());
    }

    @Test
    void downscaleSquare() {
        BufferedImage src = solidImage(2000, 2000, 0x0000FF);
        BufferedImage dst = UploadHandler.downscale(src, 1024);
        assertEquals(1024, dst.getWidth());
        assertEquals(1024, dst.getHeight());
    }
}
