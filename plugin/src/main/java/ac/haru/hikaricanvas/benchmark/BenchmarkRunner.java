package ac.haru.hikaricanvas.benchmark;

import ac.haru.hikaricanvas.benchmark.BenchmarkReport.EnvInfo;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.GcSummary;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.PerElementCost;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.Percentiles;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.SceneResult;
import ac.haru.hikaricanvas.benchmark.SceneLibrary.BenchmarkScene;
import ac.haru.hikaricanvas.render.CanvasCompositor;
import ac.haru.hikaricanvas.state.ProjectState;

import java.awt.image.BufferedImage;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

/**
 * 无头渲染压测编排器：串联场景选取、空白基线测量、逐场景分段计时、分位数聚合、单元素边际成本推导与
 * JVM 环境/GC 快照，产出 {@link BenchmarkReport}（见 {@code docs/benchmark.md} 与
 * {@code docs/architecture.md §1.2}）。
 *
 * <p>全部测量在调用线程上同步串行完成，使线程级分配计数与实际 {@code rasterize + toPaletteSlice}
 * 区间一一对应。时钟时刻 {@code generatedAtMillis} 由外部注入以保持确定性与可测性。</p>
 */
public final class BenchmarkRunner {

    /** 空白基线画布边长（tile），与全部单元素隔离场景尺寸（2×2）一致以扣除固定缓冲与清屏开销。 */
    private static final int BASELINE_TILES = 2;

    private static final double BYTES_PER_MB = 1024.0 * 1024.0;

    /** JIT 防死代码消除 blackhole（供渲染、脚本与补间压测驱动写入）。 */
    static volatile long sink;

    /**
     * 执行一次完整渲染 benchmark 并返回聚合报告。
     *
     * @param compositor        无头合成器（{@link BenchCompositor#create} 产出）
     * @param lib               场景库
     * @param config            压测轮数与场景选择配置
     * @param generatedAtMillis 报告生成时刻（epoch ms）
     * @return 完整 {@link BenchmarkReport}
     */
    public BenchmarkReport run(CanvasCompositor compositor, SceneLibrary lib,
                               BenchmarkConfig config, long generatedAtMillis) {
        List<BenchmarkScene> scenes = lib.select(config.sceneSelector());
        GcSummary gcBefore = Instrumentation.gcSnapshot();

        BenchmarkScene blankScene = new BenchmarkScene(
                "blank-baseline-" + BASELINE_TILES + "x" + BASELINE_TILES,
                "Blank baseline " + BASELINE_TILES + "x" + BASELINE_TILES,
                "baseline", "none",
                BASELINE_TILES, BASELINE_TILES,
                new ProjectState(BASELINE_TILES, BASELINE_TILES));
        SceneResult blank = aggregate(blankScene, SceneTimer.time(compositor, blankScene, config));
        double blankMeanMs = blank.rasterizeMs().mean();

        List<SceneResult> results = new ArrayList<>(scenes.size());
        for (BenchmarkScene scene : scenes) {
            results.add(aggregate(scene, SceneTimer.time(compositor, scene, config)));
        }

        GcSummary gc = Instrumentation.gcSnapshot().minus(gcBefore);

        List<SceneResult> isolationResults = new ArrayList<>();
        for (SceneResult r : results) {
            if (BenchmarkScene.CATEGORY_ELEMENT_ISOLATION.equals(r.category())) {
                isolationResults.add(r);
            }
        }
        List<PerElementCost> perElement = derivePerElement(isolationResults, blankMeanMs);

        return new BenchmarkReport(
                BenchmarkReport.SCHEMA_VERSION, generatedAtMillis, EnvInfo.capture(), config,
                results, perElement, gc, blankMeanMs);
    }

    /** 将单场景原始测量样本聚合为 {@link SceneResult}。 */
    static SceneResult aggregate(BenchmarkScene scene, List<RasterizeSample> samples) {
        List<RasterizeSample> safe = samples == null ? List.of() : samples;
        int sampleCount = safe.size();

        double[] rasterMs = new double[sampleCount];
        double[] palMs = new double[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            RasterizeSample s = safe.get(i);
            rasterMs[i] = s.rasterizeMillis();
            palMs[i] = s.paletteMillis();
        }
        Percentiles rasterizeMs = Percentiles.of(rasterMs);
        Percentiles paletteMs = Percentiles.of(palMs);

        long allocSum = 0;
        int allocN = 0;
        for (RasterizeSample s : safe) {
            if (s.hasAllocation()) {
                allocSum += s.allocatedBytes();
                allocN++;
            }
        }
        boolean allocSupported = allocN > 0;
        double meanAllocMbPerIter = allocSupported
                ? (allocSum / (double) allocN) / BYTES_PER_MB
                : 0.0;

        return new SceneResult(
                scene.id(), scene.name(), scene.category(), scene.dominantElementType(),
                scene.tilesWide(), scene.tilesHigh(), scene.pixelCount(), scene.tileCount(),
                totalElements(scene.state()), sampleCount,
                rasterizeMs, paletteMs, meanAllocMbPerIter, allocSupported);
    }

    /** 从单元素隔离场景结果扣除空白画布基线，推导每元素边际耗时。 */
    static List<PerElementCost> derivePerElement(
            List<SceneResult> isolationResults, double blankBaselineMeanMs) {
        if (isolationResults == null || isolationResults.isEmpty()) {
            return List.of();
        }
        List<PerElementCost> out = new ArrayList<>(isolationResults.size());
        for (SceneResult r : isolationResults) {
            double isolationMean = r.rasterizeMs().mean();
            double marginal = (isolationMean - blankBaselineMeanMs) / Math.max(1, r.elementCount());
            out.add(new PerElementCost(
                    r.sceneId(), r.dominantElementType(), r.elementCount(),
                    marginal, isolationMean));
        }
        return out;
    }

    private static int totalElements(ProjectState state) {
        int n = 0;
        for (var layer : state.layers()) {
            n += layer.elements().size();
        }
        return n;
    }

    /** 单轮正式测量的原始样本（纳秒耗时 + 当前线程字节分配增量）。 */
    record RasterizeSample(
            String sceneId,
            int iteration,
            long rasterizeNanos,
            long paletteNanos,
            long allocatedBytes
    ) {
        double rasterizeMillis() {
            return rasterizeNanos / 1_000_000.0;
        }

        double paletteMillis() {
            return paletteNanos / 1_000_000.0;
        }

        boolean hasAllocation() {
            return allocatedBytes >= 0;
        }
    }

    /** 单场景预热与分段计时器：分别测量 {@code rasterize()} 与全分块 {@code toPaletteSlice()}。 */
    static final class SceneTimer {

        private SceneTimer() {
        }

        static List<RasterizeSample> time(CanvasCompositor compositor,
                                          BenchmarkScene scene,
                                          BenchmarkConfig config) {
            final int widthMaps = scene.tilesWide();
            final int tiles = scene.tileCount();
            final ProjectState state = scene.state();
            final String sceneId = scene.id();

            long warmSink = 0L;
            for (int w = 0; w < config.warmupIterations(); w++) {
                BufferedImage img = compositor.rasterize(state);
                for (int m = 0; m < tiles; m++) {
                    byte[] slice = compositor.toPaletteSlice(img, m, widthMaps);
                    warmSink += slice[0];
                }
            }
            sink += warmSink;

            final int measured = config.measuredIterations();
            List<RasterizeSample> samples = new ArrayList<>(measured);
            for (int i = 0; i < measured; i++) {
                long allocBefore = Instrumentation.threadAllocatedBytes();

                long t0 = System.nanoTime();
                BufferedImage img = compositor.rasterize(state);
                long t1 = System.nanoTime();
                sink += img.getRGB(0, 0);

                long pStart = System.nanoTime();
                for (int m = 0; m < tiles; m++) {
                    byte[] slice = compositor.toPaletteSlice(img, m, widthMaps);
                    sink += slice[0] + slice[slice.length - 1];
                }
                long pEnd = System.nanoTime();

                long allocAfter = Instrumentation.threadAllocatedBytes();
                long alloc = (allocBefore < 0 || allocAfter < 0) ? -1L : (allocAfter - allocBefore);

                samples.add(new RasterizeSample(sceneId, i, t1 - t0, pEnd - pStart, alloc));
            }
            return samples;
        }
    }

    /** JVM 线程分配字节与跨收集器 GC 快照读取；不支持 {@code com.sun.management} 时降级返回 {@code -1L}。 */
    static final class Instrumentation {

        private static final com.sun.management.ThreadMXBean SUN_THREAD_BEAN;
        private static final boolean ALLOCATION_SUPPORTED;

        static {
            com.sun.management.ThreadMXBean bean = null;
            boolean supported = false;
            try {
                ThreadMXBean base = ManagementFactory.getThreadMXBean();
                if (base instanceof com.sun.management.ThreadMXBean sun
                        && sun.isThreadAllocatedMemorySupported()) {
                    if (!sun.isThreadAllocatedMemoryEnabled()) {
                        sun.setThreadAllocatedMemoryEnabled(true);
                    }
                    bean = sun;
                    supported = sun.isThreadAllocatedMemoryEnabled();
                }
            } catch (RuntimeException | LinkageError ignored) {
                bean = null;
                supported = false;
            }
            SUN_THREAD_BEAN = bean;
            ALLOCATION_SUPPORTED = supported;
        }

        private Instrumentation() {
        }

        static long threadAllocatedBytes() {
            if (!ALLOCATION_SUPPORTED || SUN_THREAD_BEAN == null) {
                return -1L;
            }
            try {
                return SUN_THREAD_BEAN.getCurrentThreadAllocatedBytes();
            } catch (RuntimeException ignored) {
                return -1L;
            }
        }

        static GcSummary gcSnapshot() {
            long count = 0L;
            long timeMs = 0L;
            List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
            for (GarbageCollectorMXBean bean : beans) {
                long c = bean.getCollectionCount();
                long t = bean.getCollectionTime();
                if (c >= 0) count += c;
                if (t >= 0) timeMs += t;
            }
            return new GcSummary(count, timeMs);
        }
    }
}
