package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.Fill;
import ac.haru.hikaricanvas.state.PathElement;
import org.junit.jupiter.api.Test;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class PathRendererFillRuleTest {
    // 外大方 + 内小方(同向绕):nonzero 整块填实,evenodd 中心透空
    private static final String DONUT =
            "M0 0 L40 0 L40 40 L0 40 Z M10 10 L30 10 L30 30 L10 30 Z";

    private int centerAlpha(String fillRule) {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // 17 参,末位 fillRule;参数顺序对照真实 PathElement record 校正
        PathElement p = new PathElement("e", 0, 0, 40, 40, 0, false, true,
                DONUT, Fill.solid("#ff0000"), null, null, null, null, null, null, fillRule);
        new PathRenderer().draw(g, p, null);   // draw 不使用 ctx,传 null
        g.dispose();
        return (img.getRGB(20, 20) >>> 24) & 0xff;   // 中心像素 alpha
    }

    @Test
    void evenodd_centerIsHollow() { assertEquals(0, centerAlpha("evenodd")); }

    @Test
    void nonzero_centerIsFilled() { assertTrue(centerAlpha("nonzero") > 0); }
}
