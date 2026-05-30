package moe.hikari.canvas.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import moe.hikari.canvas.render.CanvasCompositor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.5.0 Benchmark P2 单测。覆盖：
 * <ul>
 *   <li>{@link Percentiles#of} 线性插值数学（已知数据集精确比对）；</li>
 *   <li>{@link ResultAggregator} 聚合 + alloc 统计 + per-element 边际推导；</li>
 *   <li>{@link BenchmarkRunner} 端到端（headless 跑全场景 → 聚合报告，验「测一次」纪律）；</li>
 *   <li>{@link BenchmarkReport} 的 Jackson <b>round-trip</b>——{@code /canvas bench report} 读
 *       {@code report.json} 依赖它。</li>
 * </ul>
 */
class BenchmarkP2Test {

    private static final Logger LOG = Logger.getLogger(BenchmarkP2Test.class.getName());
    private static final double EPS = 1e-9;
    private static final long MB = 1024L * 1024L;
    private static CanvasCompositor compositor;

    @BeforeAll
    static void setUp() {
        compositor = BenchCompositor.create(LOG);
    }

    // ====================================================================
    // Percentiles：线性插值数学
    // ====================================================================

    @Test
    void percentilesLinearInterpolationOnKnownData() {
        // 1..10 线性插值（numpy linear / Excel PERCENTILE.INC）：p50=5.5, p95=9.55, p99=9.91
        Percentiles p = Percentiles.of(new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        assertEquals(10, p.count());
        assertEquals(5.5, p.p50(), EPS);
        assertEquals(9.55, p.p95(), EPS);
        assertEquals(9.91, p.p99(), EPS);
        assertEquals(5.5, p.mean(), EPS);
        assertEquals(1.0, p.min(), EPS);
        assertEquals(10.0, p.max(), EPS);
    }

    @Test
    void percentilesSingleAndEmpty() {
        Percentiles one = Percentiles.of(new double[]{42});
        assertEquals(1, one.count());
        assertEquals(42, one.p50(), EPS);
        assertEquals(42, one.p99(), EPS);
        assertEquals(0, one.stddev(), EPS);

        assertSame(Percentiles.EMPTY, Percentiles.of(new double[]{}));
        assertSame(Percentiles.EMPTY, Percentiles.of(null));
        assertEquals(0, Percentiles.EMPTY.count());
    }

    @Test
    void percentilesStddevIsSampleBased() {
        // [2,4,4,4,5,5,7,9] mean=5；样本标准差（n-1=7 分母）= sqrt(32/7)
        Percentiles p = Percentiles.of(new double[]{2, 4, 4, 4, 5, 5, 7, 9});
        assertEquals(5.0, p.mean(), EPS);
        assertEquals(Math.sqrt(32.0 / 7.0), p.stddev(), EPS);
    }

    @Test
    void percentilesDoesNotMutateInput() {
        double[] in = {5, 3, 1, 4, 2};
        Percentiles.of(in);
        assertEquals(5, in[0], EPS); // 入参未被 sort 改动
        assertEquals(3, in[1], EPS);
    }

    // ====================================================================
    // ResultAggregator
    // ====================================================================

    @Test
    void aggregateComputesPercentilesAndAlloc() {
        ResultAggregator agg = new ResultAggregator();
        BenchmarkScene scene = new SceneLibrary().byId("rect-saturated-2x2").orElseThrow();
        List<RasterizeSample> samples = List.of(
                new RasterizeSample(scene.id(), 0, 2_000_000L, 1_000_000L, 1 * MB),
                new RasterizeSample(scene.id(), 1, 4_000_000L, 3_000_000L, 3 * MB));
        SceneResult r = agg.aggregate(scene, samples);
        assertEquals(scene.id(), r.sceneId());
        assertEquals(2, r.sampleCount());
        assertEquals(24, r.elementCount(), "rect 饱和场景 24 元素");
        assertEquals(3.0, r.rasterizeMs().mean(), 1e-6);  // (2+4)/2 ms
        assertEquals(2.0, r.paletteMs().mean(), 1e-6);    // (1+3)/2 ms
        assertTrue(r.allocSupported());
        assertEquals(2.0, r.meanAllocMbPerIter(), 1e-6);  // (1+3)/2 MB
        assertEquals(4, r.tileCount());                   // 2x2
    }

    @Test
    void aggregateHandlesUnsupportedAllocAndEmpty() {
        ResultAggregator agg = new ResultAggregator();
        BenchmarkScene scene = new SceneLibrary().byId("rect-saturated-2x2").orElseThrow();

        SceneResult noAlloc = agg.aggregate(scene, List.of(
                new RasterizeSample(scene.id(), 0, 1_000_000L, 500_000L, -1)));
        assertFalse(noAlloc.allocSupported());
        assertEquals(0.0, noAlloc.meanAllocMbPerIter(), EPS);

        SceneResult empty = agg.aggregate(scene, List.of());
        assertEquals(0, empty.sampleCount());
        assertEquals(0, empty.rasterizeMs().count());
        assertEquals(24, empty.elementCount()); // 元素数来自场景，不受空样本影响
    }

    @Test
    void derivePerElementSubtractsBlankBaseline() {
        ResultAggregator agg = new ResultAggregator();
        // rasterize 均值 10ms、4 元素，空白基线 2ms → marginal = (10-2)/4 = 2
        Percentiles ras = Percentiles.of(new double[]{10, 10});
        SceneResult iso = new SceneResult(
                "x-iso", "x", BenchmarkScene.CATEGORY_ELEMENT_ISOLATION, "rect",
                2, 2, 256L * 256, 4, 4, 2,
                ras, Percentiles.EMPTY, 0.0, false);

        List<PerElementCost> pe = agg.derivePerElement(List.of(iso), 2.0);
        assertEquals(1, pe.size());
        assertEquals("rect", pe.get(0).elementType());
        assertEquals(4, pe.get(0).elementCount());
        assertEquals(2.0, pe.get(0).marginalMsPerElement(), EPS);   // (10-2)/4
        assertEquals(10.0, pe.get(0).isolationSceneMeanMs(), EPS);

        assertTrue(agg.derivePerElement(List.of(), 5).isEmpty());
        assertTrue(agg.derivePerElement(null, 5).isEmpty());
    }

    // ====================================================================
    // BenchmarkRunner 端到端
    // ====================================================================

    @Test
    void runnerProducesAggregatedReportMeasuringEachSceneOnce() {
        BenchmarkConfig cfg = new BenchmarkConfig(0, 2, null, null, "all");
        long ts = 1_234_567_890L;
        BenchmarkReport report = new BenchmarkRunner().run(compositor, new SceneLibrary(), cfg, ts);

        assertEquals(BenchmarkReport.SCHEMA_VERSION, report.schemaVersion());
        assertEquals(ts, report.generatedAtMillis(), "generatedAtMillis 透传，runner 不读时钟");
        assertNotNull(report.env());
        assertNotNull(report.gc());
        assertTrue(report.blankBaselineMs() >= 0);

        // 「测一次」纪律：每场景一条 SceneResult，无 fps/viewer 展开
        assertEquals(21, report.scenes().size());
        for (SceneResult r : report.scenes()) {
            assertEquals(2, r.sampleCount(), r.sceneId());
            assertEquals(2, r.rasterizeMs().count(), r.sceneId());
            assertTrue(r.rasterizeMs().mean() > 0, r.sceneId() + " rasterize 均值应为正");
        }
        // per-element：每个 element-isolation 场景一条（9 个）
        assertEquals(9, report.perElement().size());
    }

    @Test
    void runnerSelectorNarrowsAndReportJsonRoundTrips() throws Exception {
        BenchmarkConfig cfg = new BenchmarkConfig(0, 2, null, null, "text-saturated-2x2");
        BenchmarkReport report = new BenchmarkRunner().run(compositor, new SceneLibrary(), cfg, 99L);
        assertEquals(1, report.scenes().size());
        assertEquals("text-saturated-2x2", report.scenes().get(0).sceneId());

        // report.json round-trip：/canvas bench report 读盘依赖此
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(report);
        BenchmarkReport back = mapper.readValue(json, BenchmarkReport.class);

        assertEquals(report.schemaVersion(), back.schemaVersion());
        assertEquals(report.generatedAtMillis(), back.generatedAtMillis());
        assertEquals(report.blankBaselineMs(), back.blankBaselineMs(), EPS);
        assertEquals(report.scenes().size(), back.scenes().size());
        assertEquals(report.scenes().get(0).sceneId(), back.scenes().get(0).sceneId());
        assertEquals(report.scenes().get(0).rasterizeMs().p50(),
                back.scenes().get(0).rasterizeMs().p50(), EPS);
        assertEquals(report.env().availableProcessors(), back.env().availableProcessors());
        assertEquals(report.config().measuredIterations(), back.config().measuredIterations());
    }
}
