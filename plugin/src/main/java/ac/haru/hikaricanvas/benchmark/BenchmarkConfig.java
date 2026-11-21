package ac.haru.hikaricanvas.benchmark;

import java.util.List;

/**
 * Benchmark 压测配置：预热轮数、测量轮数、报告预算参数（帧率档 / 观看人数档）与场景选择器。
 *
 * <p>{@code warmupIterations}、{@code measuredIterations} 与 {@code sceneSelector} 控制渲染计时；
 * {@code fpsValues} 与 {@code viewerCounts} 随报告记录供摘要与预算计算器参考，不重复触发渲染测量
 * （详见 {@code docs/benchmark.md §2.2}）。</p>
 *
 * @param warmupIterations   每场景测量前预热轮数（触发 JIT 编译；&lt;0 归零）
 * @param measuredIterations 每场景测量轮数（&lt;1 归 1）
 * @param fpsValues          报告记录的帧率档；null/空 → {@code [5]}
 * @param viewerCounts       报告记录的观看人数档；null/空 → {@code [1]}
 * @param sceneSelector      场景选择器：sceneId / category 名 / {@code "all"}；空白 → {@code "all"}
 */
public record BenchmarkConfig(
        int warmupIterations,
        int measuredIterations,
        List<Integer> fpsValues,
        List<Integer> viewerCounts,
        String sceneSelector
) {
    public BenchmarkConfig {
        if (warmupIterations < 0) warmupIterations = 0;
        if (measuredIterations < 1) measuredIterations = 1;
        if (fpsValues == null || fpsValues.isEmpty()) fpsValues = List.of(5);
        if (viewerCounts == null || viewerCounts.isEmpty()) viewerCounts = List.of(1);
        if (sceneSelector == null || sceneSelector.isBlank()) sceneSelector = "all";
        fpsValues = List.copyOf(fpsValues);
        viewerCounts = List.copyOf(viewerCounts);
    }

    /** 默认快速压测配置（{@code /canvas bench run} 无参默认）。 */
    public static BenchmarkConfig quick() {
        return new BenchmarkConfig(5, 20, List.of(5), List.of(1), "all");
    }

    /** 完整压测配置：高预热 + 高测量轮数 + 多档帧率与观看人数参考值。 */
    public static BenchmarkConfig full() {
        return new BenchmarkConfig(20, 200, List.of(1, 5, 10, 20, 30),
                List.of(1, 5, 20, 50), "all");
    }
}
