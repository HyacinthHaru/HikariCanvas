package ac.haru.hikaricanvas.benchmark;

import ac.haru.hikaricanvas.benchmark.BenchmarkReport.EnvInfo;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.GcSummary;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.PerElementCost;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.Percentiles;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.SceneResult;
import ac.haru.hikaricanvas.benchmark.HtmlReportRenderer.Bar;
import ac.haru.hikaricanvas.benchmark.HtmlReportRenderer.Inputs;
import ac.haru.hikaricanvas.benchmark.SceneLibrary.BenchmarkScene;
import ac.haru.hikaricanvas.i18n.Messages;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HtmlReportRenderer}（含内联 SVG 条形图、预算输入归一化、自包含与转义、双语渲染）单元测试。
 */
class HtmlReportRendererTest {

    private static final double EPS = 1e-9;

    @Test
    void inputsDefaultsAndClamping() {
        Inputs d = Inputs.defaults();
        assertEquals(50.0, d.msptBudgetMs(), EPS);
        assertEquals(20, d.tps());
        assertEquals(30.0, d.mainThreadSharePct(), EPS);
        assertEquals(5, d.fps());

        Inputs clamped = new Inputs(-1, 0, 200, 0);
        assertEquals(50.0, clamped.msptBudgetMs(), EPS);
        assertEquals(20, clamped.tps());
        assertEquals(30.0, clamped.mainThreadSharePct(), EPS);
        assertEquals(1, clamped.fps());
    }

    @Test
    void svgBarChartBasicDegenerateAndEscaped() {
        String svg = HtmlReportRenderer.horizontalBarChart("title", List.of(
                new Bar("alpha", 1.0),
                new Bar("beta", 2.0)), "ms");
        assertTrue(svg.contains("<svg"));
        assertTrue(svg.contains("alpha"));
        assertTrue(svg.contains("beta"));
        assertTrue(svg.contains("1.000 ms"));
        assertTrue(svg.contains("2.000 ms"));

        String empty = HtmlReportRenderer.horizontalBarChart("t", List.of(), "ms");
        assertTrue(empty.contains("<svg"));
        assertTrue(empty.contains("(no data)"));

        assertDoesNotThrow(() -> HtmlReportRenderer.horizontalBarChart("t",
                List.of(new Bar("zero", 0.0), new Bar("neg", -2.5), new Bar("nan", Double.NaN)), "ms"));

        String escaped = HtmlReportRenderer.horizontalBarChart("<script>",
                List.of(new Bar("<evil>&\"'", 1.0)), "ms");
        assertFalse(escaped.contains("<evil>"));
        assertFalse(escaped.contains("<script>"));
        assertTrue(escaped.contains("&lt;evil&gt;&amp;&quot;&#39;"));
    }

    @Test
    void htmlReportIsSelfContainedAndEscapesAdversarialInput() {
        String evilId = "evil</script><img src=x>";
        BenchmarkReport report = sampleReport(evilId);

        Messages msgs = new Messages(Logger.getLogger("test"));
        msgs.loadBuiltIn();
        String html = HtmlReportRenderer.render(report, msgs);

        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("<html lang=\"en\">"));
        assertTrue(html.contains("HikariCanvas"));

        assertFalse(html.contains("src=\"http"));
        assertFalse(html.contains("<script src"));
        assertFalse(html.contains("<link href"));
        assertFalse(html.contains("@import"));
        assertFalse(html.toLowerCase().contains("googleapis"));

        assertFalse(html.contains("evil</script>"));
        assertFalse(html.contains("<img src=x>"));
        assertTrue(html.contains("evil&lt;/script&gt;&lt;img src=x&gt;"));
        assertTrue(html.contains("evil\\u003c/script\\u003e\\u003cimg src=x\\u003e"));

        assertTrue(html.contains("id=\"calc-mspt\""));
        assertTrue(html.contains("id=\"calc-tps\""));
        assertTrue(html.contains("id=\"calc-share\""));
        assertTrue(html.contains("id=\"calc-fps\""));
        assertTrue(html.contains("class=\"disclaimer\""));
    }

    @Test
    void htmlReportRendersChineseLocaleWhenSelected() {
        BenchmarkReport report = sampleReport("subway-sign-2x1");
        Messages msgs = new Messages(Logger.getLogger("test"));
        msgs.loadBuiltIn();
        msgs.setDefaultLocale("zh_cn");

        String html = HtmlReportRenderer.render(report, msgs);
        assertTrue(html.contains("<html lang=\"zh-CN\">"));
        assertTrue(html.contains("性能预算计算器"));
        assertTrue(html.contains("各场景耗时"));
    }

    private static BenchmarkReport sampleReport(String sceneId) {
        EnvInfo env = new EnvInfo("21", "OpenJDK 64-Bit Server VM",
                "Linux", "amd64", 8, 4096, List.of("G1 Young Generation"));
        Percentiles ras = Percentiles.of(new double[]{1, 2, 3});
        SceneResult sr = new SceneResult(
                sceneId, "n", BenchmarkScene.CATEGORY_ELEMENT_ISOLATION, "text",
                2, 2, 256L * 256, 4, 24, 3, ras, ras, 1.5, true);
        PerElementCost pe = new PerElementCost(sceneId, "text", 24, 0.05, 1.2);
        return new BenchmarkReport(
                BenchmarkReport.SCHEMA_VERSION, 1_700_000_000_000L, env,
                BenchmarkConfig.quick(), List.of(sr), List.of(pe),
                new GcSummary(2, 10), 0.5);
    }
}
