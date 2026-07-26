package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.Glow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发光外接盒的宽度必须和排版用的是同一个度量（{@link TextLayout#charAdvance}）。
 *
 * <p>此前这里用 {@code canonicalCharWidth}（西文一律 0.5×fontSize），而排版早已改用字体真实
 * advance —— W / M 这类宽字符的右半边直接被裁在盒子外，游戏内看到的光晕缺一块，编辑器（按浏览器
 * 实测字宽算）却是完整的。本用例用真实内置字体渲一个 W，断言光晕确实画到了 canonical 宽度之外。</p>
 */
class GlowWideCharBboxTest {

    private static final int FONT_SIZE = 48;
    private static final int GLYPH_X = 20;
    private static final int BASELINE_Y = 60;

    private static FontRegistry registry;

    @BeforeAll
    static void setUp() {
        registry = new FontRegistry(Logger.getLogger(GlowWideCharBboxTest.class.getName()));
        registry.loadBuiltIn();
    }

    @Test
    void wideAsciiGlyph_glowExtendsBeyondCanonicalWidth() {
        String fontId = "inter";
        int canonical = TextLayout.canonicalCharWidth('W', FONT_SIZE);
        int advance = TextLayout.charAdvance(fontId, 'W', FONT_SIZE);
        assertTrue(advance > canonical,
                "前提：inter 的 W 真实 advance 应宽于 canonical（否则这条用例守不住东西）");

        BufferedImage img = renderGlowOnly(fontId, "W", 4);

        // 旧实现的盒子右缘 = GLYPH_X + canonical + (radius + 1)；取一个明显在它之外的位置
        int oldRightEdge = GLYPH_X + canonical + 5;
        assertTrue(hasInkRightOf(img, oldRightEdge + 2),
                "W 右半边的光晕不该被裁掉（盒宽应按真实 advance 算）");
    }

    @Test
    void cjkGlyph_unaffected() {
        // CJK 的 canonical 就等于 fontSize，与真实 advance 一致；这条确保修改没顺手改动全角路径
        String fontId = "source_han_sans";
        assertTrue(TextLayout.charAdvance(fontId, '中', FONT_SIZE)
                == TextLayout.canonicalCharWidth('中', FONT_SIZE));
        BufferedImage img = renderGlowOnly(fontId, "中", 3);
        assertTrue(hasInkRightOf(img, GLYPH_X), "全角字仍正常出光晕");
    }

    /** 只画 glow（不画字形填充），方便断言"光晕铺到了哪里"。 */
    private static BufferedImage renderGlowOnly(String fontId, String ch, int radius) {
        Font font = registry.getOrDefault(fontId).derive(FONT_SIZE);
        BufferedImage img = new BufferedImage(240, 120, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            GlowRenderer.render(g,
                    List.of(new TextLayout.PositionedGlyph(ch, GLYPH_X, BASELINE_Y)),
                    font, fontId, new Glow(radius, "#33CCFF"));
        } finally {
            g.dispose();
        }
        return img;
    }

    private static boolean hasInkRightOf(BufferedImage img, int x) {
        for (int px = Math.max(0, x); px < img.getWidth(); px++) {
            for (int py = 0; py < img.getHeight(); py++) {
                if ((img.getRGB(px, py) >>> 24) != 0) return true;
            }
        }
        return false;
    }
}
