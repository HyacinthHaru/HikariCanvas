package ac.haru.hikaricanvas.benchmark;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Benchmark 聚合报告及全部度量载体类型——{@code report.json} 的根对象，供
 * {@code /canvas bench report}、{@code summary.txt} 与 {@link HtmlReportRenderer} 共享。
 *
 * <p>字段契约见 {@code docs/benchmark.md §3}。</p>
 *
 * @param schemaVersion     报告 schema 版本
 * @param generatedAtMillis 生成时刻（epoch ms，由调用方注入，保持核心无时钟依赖）
 * @param env               运行环境快照
 * @param config            本次压测配置
 * @param scenes            各场景聚合结果
 * @param perElement        单元素边际成本（隔离场景均值 − 空白基线均值）
 * @param gc                整段 run 的 GC 增量
 * @param blankBaselineMs   同尺寸空白画布 rasterize 均值（ms）
 */
public record BenchmarkReport(
        int schemaVersion,
        long generatedAtMillis,
        EnvInfo env,
        BenchmarkConfig config,
        List<SceneResult> scenes,
        List<PerElementCost> perElement,
        GcSummary gc,
        double blankBaselineMs
) {
    /** 当前报告 schema 版本。 */
    public static final int SCHEMA_VERSION = 1;

    /**
     * 产出报告时的 JVM / OS 环境快照（纯只读、无副作用）。
     *
     * @param javaVersion         {@code java.version}
     * @param jvmName             {@code java.vm.name}
     * @param osName              {@code os.name}
     * @param osArch              {@code os.arch}
     * @param availableProcessors {@code Runtime.availableProcessors()}
     * @param maxHeapMb           最大堆容量（MB）；无上限时 -1
     * @param gcNames             激活的 GC 收集器名称列表
     */
    public record EnvInfo(
            String javaVersion,
            String jvmName,
            String osName,
            String osArch,
            int availableProcessors,
            long maxHeapMb,
            List<String> gcNames
    ) {
        public EnvInfo {
            gcNames = gcNames == null ? List.of() : List.copyOf(gcNames);
        }

        /** 抓取当前 JVM / OS 环境快照。 */
        public static EnvInfo capture() {
            Runtime rt = Runtime.getRuntime();
            long maxHeap = rt.maxMemory();
            long maxHeapMb = maxHeap == Long.MAX_VALUE ? -1 : maxHeap / (1024 * 1024);
            List<String> gc = new ArrayList<>();
            for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                gc.add(bean.getName());
            }
            return new EnvInfo(
                    System.getProperty("java.version", "?"),
                    System.getProperty("java.vm.name", "?"),
                    System.getProperty("os.name", "?"),
                    System.getProperty("os.arch", "?"),
                    rt.availableProcessors(), maxHeapMb, gc);
        }
    }

    /**
     * GC 次数与暂停时长统计（快照或前后差值）。
     *
     * <p>报告中的 {@code gc} 字段覆盖整段 run（空白基线 + 全部场景的预热与正式迭代），
     * 因此不等于各场景 {@code meanAllocMbPerIter} 之和。</p>
     *
     * @param collectionCount  GC 次数（或增量）
     * @param collectionTimeMs GC 累计耗时（ms，或增量）
     */
    public record GcSummary(long collectionCount, long collectionTimeMs) {
        /** 求本快照相对 {@code earlier} 的增量。 */
        public GcSummary minus(GcSummary earlier) {
            return new GcSummary(
                    collectionCount - earlier.collectionCount,
                    collectionTimeMs - earlier.collectionTimeMs);
        }
    }

    /**
     * 单个场景的聚合结果：rasterize / palette 分位数、每轮分配均值与场景元数据。
     *
     * @param sceneId             场景 id
     * @param name                场景名
     * @param category            场景分组
     * @param dominantElementType 主元素类型
     * @param tilesWide           画布宽（tile）
     * @param tilesHigh           画布高（tile）
     * @param pixelCount          总像素数
     * @param tileCount           总 tile 数
     * @param elementCount        跨全图层元素总数
     * @param sampleCount         正式测量样本数
     * @param rasterizeMs         rasterize 耗时分位数
     * @param paletteMs           全 tile 调色板量化耗时分位数
     * @param meanAllocMbPerIter  每轮平均分配内存（MB）；{@code allocSupported=false} 时为 0
     * @param allocSupported      当前 JVM 是否支持线程分配字节计数
     */
    public record SceneResult(
            String sceneId,
            String name,
            String category,
            String dominantElementType,
            int tilesWide,
            int tilesHigh,
            long pixelCount,
            int tileCount,
            int elementCount,
            int sampleCount,
            Percentiles rasterizeMs,
            Percentiles paletteMs,
            double meanAllocMbPerIter,
            boolean allocSupported
    ) {}

    /**
     * 从单元素饱和场景推导的每元素净边际渲染成本：
     * {@code (isolationSceneMeanMs − blankBaselineMeanMs) / elementCount}。
     *
     * <p>text 含普通文字与 {@code ${var:...}} 占位符两个隔离场景，按 {@code sceneId} 区分保留。</p>
     *
     * @param sceneId              来源隔离场景 id
     * @param elementType          主元素类型
     * @param elementCount         该场景元素实例数
     * @param marginalMsPerElement 净边际成本（ms/元素）；测量噪声导致的轻微负值原样保留
     * @param isolationSceneMeanMs 隔离场景 rasterize 均值（ms，未扣基线）
     */
    public record PerElementCost(
            String sceneId,
            String elementType,
            int elementCount,
            double marginalMsPerElement,
            double isolationSceneMeanMs
    ) {}

    /**
     * 一组毫秒样本的分位数与矩统计。p50/p95/p99 采用 R-7 线性插值法（与 numpy {@code linear} /
     * Excel {@code PERCENTILE.INC} 一致）。
     *
     * @param p50    中位数（ms）
     * @param p95    95 分位（ms）
     * @param p99    99 分位（ms）
     * @param mean   均值（ms）
     * @param min    最小值（ms）
     * @param max    最大值（ms）
     * @param stddev 样本标准差（ms，n&gt;1 时用 n−1 分母，否则 0）
     * @param count  样本数
     */
    public record Percentiles(
            double p50,
            double p95,
            double p99,
            double mean,
            double min,
            double max,
            double stddev,
            int count
    ) {
        /** 空统计（count=0，其余为 0）。 */
        public static final Percentiles EMPTY = new Percentiles(0, 0, 0, 0, 0, 0, 0, 0);

        /** 从毫秒样本数组计算分位数（内部拷贝排序，不修改入参；null/空返回 {@link #EMPTY}）。 */
        public static Percentiles of(double[] valuesMs) {
            if (valuesMs == null || valuesMs.length == 0) {
                return EMPTY;
            }
            double[] v = valuesMs.clone();
            Arrays.sort(v);
            int n = v.length;
            double sum = 0;
            for (double x : v) {
                sum += x;
            }
            double mean = sum / n;
            double var = 0;
            for (double x : v) {
                double d = x - mean;
                var += d * d;
            }
            double stddev = n > 1 ? Math.sqrt(var / (n - 1)) : 0.0;
            return new Percentiles(
                    percentile(v, 50), percentile(v, 95), percentile(v, 99),
                    mean, v[0], v[n - 1], stddev, n);
        }

        private static double percentile(double[] v, double p) {
            int n = v.length;
            if (n == 1) {
                return v[0];
            }
            double rank = (p / 100.0) * (n - 1);
            int lo = (int) Math.floor(rank);
            int hi = (int) Math.ceil(rank);
            if (lo == hi) {
                return v[lo];
            }
            double frac = rank - lo;
            return v[lo] + (v[hi] - v[lo]) * frac;
        }
    }

    /** 脚本 / 补间压测单行结果：横轴规模参数 {@code n}（动作数或活跃墙数）及其耗时分位数。 */
    public record BenchRow(int n, Percentiles p) {}
}
