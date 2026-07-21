package ac.haru.hikaricanvas.benchmark;

import ac.haru.hikaricanvas.render.CanvasCompositor;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>CI 功能性 gate</b>。
 *
 * <p>跑完整的 {@code /canvas bench run} 管线——{@link BenchCompositor} → {@link BenchmarkRunner}
 * → {@link HtmlReportRenderer}——在 headless 下端到端走通一遍。<b>只断言「能跑通 + 不崩 + 产出
 * 非空报告」</b>，<b>不</b>做任何性能数值断言（{@code docs/dynamic-data.md §13.3}：0.4.7/0.4.8/0.4.9
 * 三次 CI flaky 全栽在 perf/平台敏感断言，共享 runner ±2-3x 抖动，数值门禁要么松到没用要么紧到 flaky；
 * 性能 drift 由服主在<b>自己机器</b>上对比 report.json 人工复查）。</p>
 *
 * <p>这是 0.5.0 Benchmark 的真·CI gate：随 {@code :plugin:test} 在每次 push/PR 跑，确保某次改动若
 * 弄坏了 scene 构造 / rasterize / 聚合 / 报告渲染，CI 立刻红。</p>
 */
class BenchmarkPipelineSmokeTest {

    @Test
    void fullBenchPipelineRunsHeadlessEndToEnd() {
        CanvasCompositor compositor = BenchCompositor.create(Logger.getLogger("bench-smoke"));
        // 与 /canvas bench run 同款装配，迭代数压到最小（功能性 gate 不测性能）
        BenchmarkConfig cfg = new BenchmarkConfig(1, 3, null, null, "all");

        BenchmarkReport report = new BenchmarkRunner()
                .run(compositor, new SceneLibrary(), cfg, 1_700_000_000_000L);

        // 跑通 + 产出聚合
        assertEquals(21, report.scenes().size(), "全 21 场景应各产一条 SceneResult");
        assertFalse(report.perElement().isEmpty(), "应有 per-element 边际成本");
        assertTrue(report.blankBaselineMs() >= 0);
        for (SceneResult r : report.scenes()) {
            assertEquals(3, r.sampleCount(), r.sceneId());
            assertTrue(r.rasterizeMs().mean() > 0, r.sceneId());
        }

        // 渲染自包含 HTML（含每个真实 scene id；scene id 均 word-safe，转义后原样）
        var msgs = new ac.haru.hikaricanvas.i18n.Messages(Logger.getLogger("test"));
        msgs.loadBuiltIn();
        String html = HtmlReportRenderer.render(report, msgs);
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertFalse(html.contains("<script src"), "报告须自包含、零外链");
        for (SceneResult r : report.scenes()) {
            assertTrue(html.contains(r.sceneId()), "HTML 应含场景 " + r.sceneId());
        }
    }
}
