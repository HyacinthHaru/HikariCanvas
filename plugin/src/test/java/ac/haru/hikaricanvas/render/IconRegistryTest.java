package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.Fill;
import ac.haru.hikaricanvas.state.IconElement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IconRegistry} 逐图标 {@code viewBox} 解析与 {@link IconRenderer} 等比居中渲染单测。
 */
class IconRegistryTest {

    private static final Logger LOG = Logger.getLogger(IconRegistryTest.class.getName());
    private static IconRegistry registry;
    private static RenderContext renderContext;

    @BeforeAll
    static void setUp() {
        registry = new IconRegistry(LOG);
        registry.loadBuiltIn();
        renderContext = new RenderContext(null, null, registry, LOG, () -> null);
    }

    @Test
    void loadBuiltIn_preservesPerIconViewBox() {
        assertEquals("0 0 512 512", registry.getViewBox("fa-solid/heart"));
        assertEquals("0 0 576 512", registry.getViewBox("fa-solid/star"));
        assertEquals("0 0 640 512", registry.getViewBox("fa-solid/users"));
        assertEquals("0 0 384 512", registry.getViewBox("fa-solid/location-dot"));
    }

    @Test
    void render_wideIcon_horizontallyCenteredWithoutRightEdgeOverflow() {
        // 640×512 宽图标：先在 64×64 全画布验证水平对称居中 + 上下留白（等比缩放到 64×51.2）
        BufferedImage full = renderIcon("fa-solid/users", 0, 0, 64, 64);
        PixelBounds fb = nonEmptyBounds(full);
        assertTrue(fb.count > 0, "wide icon should produce non-empty pixels");

        int leftMargin = fb.minX;
        int rightMargin = (full.getWidth() - 1) - fb.maxX;
        assertTrue(Math.abs(leftMargin - rightMargin) <= 2,
                "wide icon should be horizontally centered, got minX=" + fb.minX + ", maxX=" + fb.maxX);
        assertTrue(fb.minY >= 6 && fb.maxY <= 57,
                "640×512 icon in 64×64 box must letterbox vertically, got minY=" + fb.minY + ", maxY=" + fb.maxY);

        // 再以内缩 bbox (x=8, y=8, w=48, h=48) 画在 64×64 画布上：若误按 512×512 缩放，
        // 右侧像素会溢出 x >= 56 甚至顶到右边缘；正确按 640×512 缩放时严格落在 [8, 55] 内
        BufferedImage inset = renderIcon("fa-solid/users", 8, 8, 48, 48);
        PixelBounds ib = nonEmptyBounds(inset);
        assertTrue(ib.minX >= 8 && ib.maxX < 56,
                "wide icon must not overflow element right edge (expected maxX < 56, got " + ib.maxX + ")");
        double centerX = (ib.minX + ib.maxX) / 2.0;
        assertTrue(Math.abs(centerX - 31.5) <= 1.5,
                "inset wide icon center should align near 31.5, got " + centerX);
    }

    @Test
    void render_narrowIcon_horizontallyCenteredWithoutOverflow() {
        // 384×512 窄图标在 64×64 内等比缩放到 48×64，左右各留约 8px 空白（x ∈ [8, 55]）
        BufferedImage img = renderIcon("fa-solid/location-dot", 0, 0, 64, 64);
        PixelBounds b = nonEmptyBounds(img);
        assertTrue(b.count > 0, "narrow icon should produce non-empty pixels");
        assertTrue(b.minX >= 8 && b.maxX <= 55,
                "384×512 icon in 64×64 box should pillarbox into [8, 55], got [" + b.minX + ", " + b.maxX + "]");

        int leftMargin = b.minX;
        int rightMargin = (img.getWidth() - 1) - b.maxX;
        assertTrue(Math.abs(leftMargin - rightMargin) <= 2,
                "narrow icon should be horizontally centered, got minX=" + b.minX + ", maxX=" + b.maxX);
    }

    private static BufferedImage renderIcon(String source, int x, int y, int w, int h) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            IconElement el = new IconElement(
                    "ic-test", x, y, w, h, 0, false, true,
                    source, null, null, null, null, Fill.solid("#FFFFFF"));
            new IconRenderer().draw(g, el, renderContext);
        } finally {
            g.dispose();
        }
        return img;
    }

    private record PixelBounds(int minX, int maxX, int minY, int maxY, int count) {}

    private static PixelBounds nonEmptyBounds(BufferedImage img) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int count = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int alpha = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (alpha != 0) {
                    count++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        return new PixelBounds(minX, maxX, minY, maxY, count);
    }
}
