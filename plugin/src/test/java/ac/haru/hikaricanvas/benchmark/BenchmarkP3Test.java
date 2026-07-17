package ac.haru.hikaricanvas.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.5.0 Benchmark P3 单测。覆盖：
 * <ul>
 *   <li>{@link BudgetFormula} 的 50mspt 公式数学（HTML 内联 JS 计算器镜像它，须精确）；</li>
 *   <li>{@link SvgBarChart} 基本渲染 + 退化输入（空 / 全零 / 转义）；</li>
 *   <li>{@link HtmlReportRenderer} <b>自包含</b>（零外链）+ <b>转义</b>（对抗性 scene id 不破壳）+
 *       关键 section（计算器 + disclaimer）齐备。</li>
 * </ul>
 *
 * <p>全程纯 String 构建，无需 compositor / Bukkit。</p>
 */
class BenchmarkP3Test {

    private static final double EPS = 1e-9;

    // ====================================================================
    // BudgetFormula：50mspt 公式
    // ====================================================================

    @Test
    void budgetFormulaMathMatchesSpec() {
        BudgetFormula.Inputs d = BudgetFormula.Inputs.defaults();
        assertEquals(50.0, d.msptBudgetMs(), EPS);
        assertEquals(20, d.tps());
        assertEquals(30.0, d.mainThreadSharePct(), EPS);
        assertEquals(5, d.fps());

        // 可用预算 = 50 × 20 × 0.30 = 300 ms/s
        assertEquals(300.0, BudgetFormula.availableMsPerSecond(d), EPS);
        // p95=0.5ms, fps=5 → 单 wall 成本 2.5 ms/s → 300 / 2.5 = 120 walls
        assertEquals(120.0, BudgetFormula.projectedMaxWalls(0.5, d), EPS);
        // 成本为 0 → 无穷（防御性）
        assertEquals(Double.POSITIVE_INFINITY, BudgetFormula.projectedMaxWalls(0.0, d));
    }

    @Test
    void budgetFormulaInputsNormalizeAndDisclaimer() {
        BudgetFormula.Inputs in = new BudgetFormula.Inputs(-1, 0, 200, 0);
        assertEquals(50.0, in.msptBudgetMs(), EPS);   // <=0 → 50
        assertEquals(20, in.tps());                   // <=0 → 20
        assertEquals(30.0, in.mainThreadSharePct(), EPS); // 越界 → 30
        assertEquals(1, in.fps());                    // <=0 → 1

        assertNotNull(BudgetFormula.DISCLAIMER);
        assertTrue(BudgetFormula.DISCLAIMER.contains("保守"), "disclaimer 须说明保守下界口径");
    }

    // ====================================================================
    // SvgBarChart
    // ====================================================================

    @Test
    void svgBarChartBasicAndDegenerate() {
        String svg = SvgBarChart.horizontal("title", List.of(
                new SvgBarChart.Bar("alpha", 1.0),
                new SvgBarChart.Bar("beta", 2.0)), "ms");
        assertTrue(svg.contains("<svg"));
        assertTrue(svg.contains("alpha"));
        assertTrue(svg.contains("beta"));

        // 空列表 → 不抛、仍是 svg
        assertTrue(SvgBarChart.horizontal("t", List.of(), "ms").contains("<svg"));
        // 全零（maxValue<=0）→ 不除零崩
        assertDoesNotThrow(() -> SvgBarChart.horizontal("t",
                List.of(new SvgBarChart.Bar("z", 0.0)), "ms"));
        // label 含 < 被转义（无裸 <evil> 标签）
        String esc = SvgBarChart.horizontal("t",
                List.of(new SvgBarChart.Bar("<evil>", 1.0)), "ms");
        assertFalse(esc.contains("<evil>"), "label 须转义");
    }

    // ====================================================================
    // HtmlReportRenderer：自包含 + 转义 + 关键 section
    // ====================================================================

    @Test
    void htmlReportIsSelfContainedEscapedAndComplete() {
        EnvInfo env = new EnvInfo("21", "OpenJDK 64-Bit Server VM",
                "Linux", "amd64", 8, 4096, List.of("G1 Young Generation"));
        Percentiles ras = Percentiles.of(new double[]{1, 2, 3});
        String evilId = "evil</script><img src=x>";
        SceneResult sr = new SceneResult(
                evilId, "n", BenchmarkScene.CATEGORY_ELEMENT_ISOLATION, "text",
                2, 2, 256L * 256, 4, 24, 3, ras, ras, 1.5, true);
        PerElementCost pe = new PerElementCost(evilId, "text", 24, 0.05, 1.2);
        BenchmarkReport report = new BenchmarkReport(
                BenchmarkReport.SCHEMA_VERSION, 1_700_000_000_000L, env,
                BenchmarkConfig.quick(), List.of(sr), List.of(pe),
                new GcSummary(2, 10), 0.5);

        String html = HtmlReportRenderer.render(report);

        // 自包含 HTML5
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("HikariCanvas"));

        // 零外链（SVG xmlns 的 http://www.w3.org 是命名空间 URI、不 fetch，不属外链）
        assertFalse(html.contains("src=\"http"), "no external src");
        assertFalse(html.contains("<script src"), "no external script");
        assertFalse(html.contains("@import"), "no css @import");
        assertFalse(html.toLowerCase().contains("googleapis"), "no web font");

        // 转义：对抗性 scene id 不得裸出（防 </script> 逃逸 + img 注入）
        assertFalse(html.contains("evil</script>"), "scene id 须转义、不破壳");

        // 关键 section：50mspt 计算器输入 + disclaimer
        assertTrue(html.contains("<input"), "budget 计算器输入");
        assertTrue(html.contains("保守"), "disclaimer 展示");
    }
}
