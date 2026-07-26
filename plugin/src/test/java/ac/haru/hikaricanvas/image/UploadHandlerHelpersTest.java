package ac.haru.hikaricanvas.image;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UploadHandler} 静态辅助方法（magic bytes 探测 + bilinear downscale）。
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

    // ---------- 白名单放行 ≠ 真能解码 ----------

    @Test
    void canDecodePngAndJpeg() {
        assertTrue(UploadHandler.canDecodeMime("image/png"));
        assertTrue(UploadHandler.canDecodeMime("image/jpeg"));
    }

    /**
     * {@code image/webp} 一直在默认白名单里，但标准 JDK 根本没有 WebP reader——白名单放行、
     * magic 探测认得，然后必定死在解码那一步，报的还是笼统的"图片解码失败"。
     * 在 MIME 校验层问一次运行时能力，就能给出说得清的理由。
     *
     * <p>不写死"webp 一定不行"：服主真装了 WebP 的 ImageIO 插件（启动期 IIORegistry 过滤会
     * 保留它）时它就该可用。本用例只钉住"没装解码器 → 判定为不可解码"这条因果。</p>
     */
    @Test
    void canDecodeMimeReflectsWhatImageIoActuallyHas() {
        boolean hasWebpReader = javax.imageio.ImageIO
                .getImageReadersByMIMEType("image/webp").hasNext();
        assertEquals(hasWebpReader, UploadHandler.canDecodeMime("image/webp"));
    }

    @Test
    void canDecodeMimeRejectsUnknownAndNull() {
        assertFalse(UploadHandler.canDecodeMime("image/definitely-not-a-format"));
        assertFalse(UploadHandler.canDecodeMime(null));
    }

    // ---------- 会话级 IP 绑定（security.md §2.5）在 HTTP 面的比对原语 ----------

    @Test
    void normalizeIp_stripsSlashPrefixBracketsAndPort() {
        assertEquals("127.0.0.1", UploadHandler.normalizeIp("/127.0.0.1"));
        assertEquals("127.0.0.1", UploadHandler.normalizeIp("127.0.0.1:54321"));
        assertEquals("127.0.0.1", UploadHandler.normalizeIp("  127.0.0.1  "));
        assertEquals("0:0:0:0:0:0:0:1", UploadHandler.normalizeIp("[::1]:8877"));
    }

    /**
     * WS 侧存的是 {@code InetAddress.getHostAddress()} 的写法，HTTP 侧拿到的可能是
     * {@code ::1} 这种缩写 —— 不归一就会把同一个地址判成两个，合法上传全被拒。
     */
    @Test
    void normalizeIp_ipv6ShorthandAndLongFormAgree() {
        assertEquals(UploadHandler.normalizeIp("0:0:0:0:0:0:0:1"),
                UploadHandler.normalizeIp("::1"));
        assertEquals(UploadHandler.normalizeIp("::ffff:127.0.0.1"),
                UploadHandler.normalizeIp("127.0.0.1"));
    }

    @Test
    void normalizeIp_blankOrNull_becomesUnknown() {
        assertEquals("unknown", UploadHandler.normalizeIp(null));
        assertEquals("unknown", UploadHandler.normalizeIp(""));
        assertEquals("unknown", UploadHandler.normalizeIp("   "));
    }

    /** 同机部署时浏览器可能给 WS 和 HTTP 各挑一个地址族，两者互认。 */
    @Test
    void bothLoopback_acceptsMixedLocalhostFamilies() {
        assertTrue(UploadHandler.bothLoopback("127.0.0.1", "0:0:0:0:0:0:0:1"));
        assertTrue(UploadHandler.bothLoopback("::1", "127.0.0.2"));
    }

    /** 但绝不能把真实外网地址也放进来 —— 那就等于 IP 绑定没做。 */
    @Test
    void bothLoopback_rejectsRealAddresses() {
        assertFalse(UploadHandler.bothLoopback("127.0.0.1", "203.0.113.7"));
        assertFalse(UploadHandler.bothLoopback("203.0.113.7", "127.0.0.1"));
        assertFalse(UploadHandler.bothLoopback("192.168.1.5", "192.168.1.6"));
        assertFalse(UploadHandler.bothLoopback("unknown", "unknown"));
        assertFalse(UploadHandler.bothLoopback(null, "127.0.0.1"));
    }
}
