package ac.haru.hikaricanvas.benchmark;

import ac.haru.hikaricanvas.benchmark.BenchmarkReport.BenchRow;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.GcSummary;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.PerElementCost;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.Percentiles;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.SceneResult;
import ac.haru.hikaricanvas.benchmark.BenchmarkRunner.Instrumentation;
import ac.haru.hikaricanvas.benchmark.BenchmarkRunner.RasterizeSample;
import ac.haru.hikaricanvas.benchmark.BenchmarkRunner.SceneTimer;
import ac.haru.hikaricanvas.benchmark.SceneLibrary.BenchmarkScene;
import ac.haru.hikaricanvas.i18n.Messages;
import ac.haru.hikaricanvas.render.CanvasCompositor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * {@link Percentiles}、{@link BenchmarkRunner}（含采样器、聚合与全场景无头管线冒烟）及
 * {@link ScriptBenchmarkDriver} / {@link TweenBenchmarkDriver} 测试。
 */
class BenchmarkRunnerTest {

    private static final Logger LOG = Logger.getLogger(BenchmarkRunnerTest.class.getName());
    private static final double EPS = 1e-9;
    private static final long MB = 1024L * 1024L;

    private static CanvasCompositor compositor;

    @BeforeAll
    static void setUp() {
        compositor = BenchCompositor.create(LOG);
    }

    @Test
    void percentilesLinearInterpolationOnKnownData() {
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
        Percentiles p = Percentiles.of(new double[]{2, 4, 4, 4, 5, 5, 7, 9});
        assertEquals(5.0, p.mean(), EPS);
        assertEquals(Math.sqrt(32.0 / 7.0), p.stddev(), EPS);
    }

    @Test
    void percentilesDoesNotMutateInput() {
        double[] in = {5, 3, 1, 4, 2};
        Percentiles.of(in);
        assertEquals(5, in[0], EPS);
        assertEquals(3, in[1], EPS);
    }

    @Test
    void instrumentationAllocationIsGracefulOrMonotonic() {
        long before = Instrumentation.threadAllocatedBytes();
        byte[] junk = new byte[8 * 1024 * 1024];
        junk[0] = 1;
        junk[junk.length - 1] = 2;
        long after = Instrumentation.threadAllocatedBytes();
        if (before >= 0 && after >= 0) {
            assertTrue(after >= before);
        } else {
            assertEquals(-1, before);
            assertEquals(-1, after);
        }
        assertEquals(8 * 1024 * 1024, junk.length);
    }

    @Test
    void gcSnapshotAndMinusWork() {
        GcSummary s1 = Instrumentation.gcSnapshot();
        assertNotNull(s1);
        assertTrue(s1.collectionCount() >= 0);
        assertTrue(s1.collectionTimeMs() >= 0);
        GcSummary delta = s1.minus(new GcSummary(0, 0));
        assertEquals(s1.collectionCount(), delta.collectionCount());
        assertEquals(s1.collectionTimeMs(), delta.collectionTimeMs());
    }

    @Test
    void sceneTimerProducesRequestedSampleCount() {
        BenchmarkScene scene = new SceneLibrary().byId("size-1x1").orElseThrow();
        BenchmarkConfig cfg = new BenchmarkConfig(1, 3, null, null, "size-1x1");

        List<RasterizeSample> samples = SceneTimer.time(compositor, scene, cfg);
        assertEquals(3, samples.size());
        for (int i = 0; i < samples.size(); i++) {
            assertEquals(i, samples.get(i).iteration());
            assertEquals(scene.id(), samples.get(i).sceneId());
            assertTrue(samples.get(i).rasterizeNanos() > 0);
            assertTrue(samples.get(i).paletteNanos() >= 0);
        }
    }

    @Test
    void aggregateComputesPercentilesAndAlloc() {
        BenchmarkScene scene = new SceneLibrary().byId("rect-saturated-2x2").orElseThrow();
        List<RasterizeSample> samples = List.of(
                new RasterizeSample(scene.id(), 0, 2_000_000L, 1_000_000L, 1 * MB),
                new RasterizeSample(scene.id(), 1, 4_000_000L, 3_000_000L, 3 * MB));
        SceneResult r = BenchmarkRunner.aggregate(scene, samples);
        assertEquals(scene.id(), r.sceneId());
        assertEquals(2, r.sampleCount());
        assertEquals(24, r.elementCount());
        assertEquals(3.0, r.rasterizeMs().mean(), 1e-6);
        assertEquals(2.0, r.paletteMs().mean(), 1e-6);
        assertTrue(r.allocSupported());
        assertEquals(2.0, r.meanAllocMbPerIter(), 1e-6);
        assertEquals(4, r.tileCount());
    }

    @Test
    void aggregateHandlesUnsupportedAllocAndEmpty() {
        BenchmarkScene scene = new SceneLibrary().byId("rect-saturated-2x2").orElseThrow();

        SceneResult noAlloc = BenchmarkRunner.aggregate(scene, List.of(
                new RasterizeSample(scene.id(), 0, 1_000_000L, 500_000L, -1)));
        assertFalse(noAlloc.allocSupported());
        assertEquals(0.0, noAlloc.meanAllocMbPerIter(), EPS);

        SceneResult empty = BenchmarkRunner.aggregate(scene, List.of());
        assertEquals(0, empty.sampleCount());
        assertEquals(0, empty.rasterizeMs().count());
        assertEquals(24, empty.elementCount());
    }

    @Test
    void derivePerElementSubtractsBlankBaseline() {
        Percentiles ras = Percentiles.of(new double[]{10, 10});
        SceneResult iso = new SceneResult(
                "x-iso", "x", BenchmarkScene.CATEGORY_ELEMENT_ISOLATION, "rect",
                2, 2, 256L * 256, 4, 4, 2,
                ras, Percentiles.EMPTY, 0.0, false);

        List<PerElementCost> pe = BenchmarkRunner.derivePerElement(List.of(iso), 2.0);
        assertEquals(1, pe.size());
        assertEquals("rect", pe.get(0).elementType());
        assertEquals(4, pe.get(0).elementCount());
        assertEquals(2.0, pe.get(0).marginalMsPerElement(), EPS);
        assertEquals(10.0, pe.get(0).isolationSceneMeanMs(), EPS);

        assertTrue(BenchmarkRunner.derivePerElement(List.of(), 5).isEmpty());
        assertTrue(BenchmarkRunner.derivePerElement(null, 5).isEmpty());
    }

    @Test
    void runnerSelectorNarrowsAndReportJsonRoundTrips() throws Exception {
        BenchmarkConfig cfg = new BenchmarkConfig(0, 2, null, null, "text-saturated-2x2");
        BenchmarkReport report = new BenchmarkRunner().run(compositor, new SceneLibrary(), cfg, 99L);
        assertEquals(1, report.scenes().size());
        assertEquals("text-saturated-2x2", report.scenes().get(0).sceneId());
        assertEquals(1, report.perElement().size());

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(report);
        JsonNode root = mapper.readTree(json);
        for (String key : List.of("schemaVersion", "generatedAtMillis", "env", "config",
                "scenes", "perElement", "gc", "blankBaselineMs")) {
            assertTrue(root.has(key), "report.json 缺少字段 " + key);
        }

        BenchmarkReport back = mapper.readValue(json, BenchmarkReport.class);
        assertEquals(report.schemaVersion(), back.schemaVersion());
        assertEquals(report.generatedAtMillis(), back.generatedAtMillis());
        assertEquals(report.blankBaselineMs(), back.blankBaselineMs(), EPS);
        assertEquals(report.scenes().size(), back.scenes().size());
        assertEquals(report.scenes().get(0).sceneId(), back.scenes().get(0).sceneId());
        assertEquals(report.scenes().get(0).rasterizeMs().p50(),
                back.scenes().get(0).rasterizeMs().p50(), EPS);
        assertEquals(report.perElement().get(0).marginalMsPerElement(),
                back.perElement().get(0).marginalMsPerElement(), EPS);
        assertEquals(report.env().availableProcessors(), back.env().availableProcessors());
        assertEquals(report.config().measuredIterations(), back.config().measuredIterations());
    }

    @Test
    void fullBenchPipelineRunsHeadlessEndToEnd() {
        BenchmarkConfig cfg = new BenchmarkConfig(0, 2, null, null, "all");
        long ts = 1_700_000_000_000L;

        BenchmarkReport report = new BenchmarkRunner()
                .run(compositor, new SceneLibrary(), cfg, ts);

        assertEquals(BenchmarkReport.SCHEMA_VERSION, report.schemaVersion());
        assertEquals(ts, report.generatedAtMillis());
        assertNotNull(report.env());
        assertNotNull(report.gc());
        assertTrue(report.blankBaselineMs() >= 0);
        assertEquals(21, report.scenes().size());
        assertEquals(9, report.perElement().size());
        for (SceneResult r : report.scenes()) {
            assertEquals(2, r.sampleCount(), r.sceneId());
            assertTrue(r.rasterizeMs().mean() > 0, r.sceneId());
            assertTrue(r.paletteMs().mean() >= 0, r.sceneId());
        }

        Messages msgs = new Messages(LOG);
        msgs.loadBuiltIn();
        String html = HtmlReportRenderer.render(report, msgs);
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertFalse(html.contains("<script src"));
        for (SceneResult r : report.scenes()) {
            assertTrue(html.contains(r.sceneId()), "HTML 应含场景 " + r.sceneId());
        }
    }

    @Test
    void scriptDriverMeasuresSingleAndMultipleActionCounts() {
        List<Integer> counts = List.of(1, 10, 49);
        List<BenchRow> rows = ScriptBenchmarkDriver.measure(counts, 1, 2);
        assertEquals(counts, rows.stream().map(BenchRow::n).toList());
        for (BenchRow r : rows) {
            assertNotNull(r.p());
            assertEquals(2, r.p().count());
            assertTrue(r.p().p50() >= 0);
        }
    }

    @Test
    void tweenDriverMeasuresSingleAndMultipleWallCounts() {
        List<Integer> wallCounts = List.of(1, 4);
        List<BenchRow> rows = TweenBenchmarkDriver.measure(wallCounts, 1, 2);
        assertEquals(wallCounts, rows.stream().map(BenchRow::n).toList());
        for (BenchRow r : rows) {
            assertNotNull(r.p());
            assertEquals(2, r.p().count());
            assertTrue(r.p().p50() >= 0);
        }
    }
}
