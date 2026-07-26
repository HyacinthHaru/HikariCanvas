package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.BlendMode;
import ac.haru.hikaricanvas.state.RenderMode;
import ac.haru.hikaricanvas.state.TextElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行高的双端一致性（rendering.md §3.2）。
 *
 * <p>{@code TextElement.lineHeight} 存的是 float，而前端和 JSON 里是 double。直接做
 * float 乘法会在 {@code fontSize=15 / lineHeight=2.1}、{@code fontSize=30 / lineHeight=1.05}
 * 这类组合上与前端差一，多行文本第 N 行差 N 像素（fontSize 1..512 × lineHeight 0.10..4.00
 * 步进 0.01 的 200192 个组合里实测 286 个分歧）。这里的期望值一律按<b>前端算法</b>写：
 * {@code Math.round(fontSize × <JSON 里那个 double>)}。</p>
 */
class TextLayoutLineHeightTest {

    /** 前端 TextLayout.ts：{@code Math.max(1, Math.round(fontSize * lineHeightMul))}，double 运算。 */
    private static int frontendLineHeightPx(int fontSize, double lineHeight) {
        double mul = lineHeight <= 0 ? 1.2 : lineHeight;
        return Math.max(1, (int) Math.round(fontSize * mul));
    }

    @Test
    void lineHeightPx_matchesFrontendDoubleMath_acrossWholeRange() {
        // lineHeight 校验范围 [0.1, 4]，UI 步进 0.1；这里按 0.01 全网格 × fontSize 1..512 扫
        int mismatches = 0;
        StringBuilder detail = new StringBuilder();
        for (int fontSize = 1; fontSize <= 512; fontSize++) {
            for (int k = 10; k <= 400; k++) {
                double lh = k / 100.0;
                int backend = TextLayout.lineHeightPx(fontSize, (float) lh);
                int frontend = frontendLineHeightPx(fontSize, lh);
                if (backend != frontend) {
                    mismatches++;
                    if (detail.length() < 400) {
                        detail.append(" fontSize=").append(fontSize)
                              .append(" lineHeight=").append(lh)
                              .append(" backend=").append(backend)
                              .append(" frontend=").append(frontend).append(';');
                    }
                }
            }
        }
        assertEquals(0, mismatches, "行高必须逐值与前端一致，分歧样例：" + detail);
    }

    /**
     * 修前 float 乘法真实分歧的几个样例（全 200192 个组合里有 286 个分歧）。
     * 期望值一律按前端 double 算法写。
     */
    @Test
    void knownFloatVsDoubleDivergences_areGone() {
        assertEquals(32, TextLayout.lineHeightPx(15, 2.1f));    // float 乘法给 31
        assertEquals(32, TextLayout.lineHeightPx(30, 1.05f));   // float 乘法给 31
        assertEquals(14, TextLayout.lineHeightPx(25, 0.58f));   // float 乘法给 15
        assertEquals(28, TextLayout.lineHeightPx(25, 1.14f));   // float 乘法给 29
        assertEquals(31, TextLayout.lineHeightPx(45, 0.7f));    // float 乘法给 32
        // 强转 double 救不了：(double) 1.1f 根本不是 JSON 里那个 1.1
        assertTrue((double) 1.1f != 1.1, "(double) 1.1f 与 1.1 本就不是同一个数");
    }

    @Test
    void lineHeightMultiplier_recoversJsonValue() {
        assertEquals(1.1, TextLayout.lineHeightMultiplier(1.1f));
        assertEquals(1.15, TextLayout.lineHeightMultiplier(1.15f));
        assertEquals(2.0, TextLayout.lineHeightMultiplier(2.0f));
    }

    @Test
    void nonPositiveOrNaNLineHeight_fallsBackToDefault() {
        assertEquals(1.2, TextLayout.lineHeightMultiplier(0f));
        assertEquals(1.2, TextLayout.lineHeightMultiplier(-1f));
        assertEquals(1.2, TextLayout.lineHeightMultiplier(Float.NaN));
        assertTrue(TextLayout.lineHeightPx(1, 0.1f) >= 1, "行高至少 1px");
    }

    /** 端到端：多行文本的逐行基线间距 = 行高像素，不会逐行累积偏移。 */
    @Test
    void multiLineBaselines_stepByExactLineHeight() {
        TextElement t = new TextElement("t1", 0, 0, 400, 200, 0, false, true,
                "aaa\nbbb\nccc\nddd", "inter", 15, "#000000", "left",
                0f, 2.1f, false, null, 1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null, null);
        List<TextLayout.PositionedGlyph> glyphs = TextLayout.layout(t);
        int expectedStep = frontendLineHeightPx(15, 2.1);
        int firstBaseline = glyphs.get(0).baselineY();
        // 每行 3 个字形
        for (int line = 0; line < 4; line++) {
            int baseline = glyphs.get(line * 3).baselineY();
            assertEquals(firstBaseline + line * expectedStep, baseline,
                    "第 " + line + " 行基线应恰好是首行 + " + line + "×" + expectedStep);
        }
    }
}
