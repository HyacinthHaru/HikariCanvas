package moe.hikari.canvas.benchmark;

import java.util.List;

/**
 * Benchmark 压测配置：预热 / 测量轮数 + 矩阵轴（fps × viewer 数）+ 场景选择。
 *
 * <p>P1 只用到 {@code warmupIterations} / {@code measuredIterations} /
 * {@code sceneSelector}（{@code SceneTimer} 单场景计时）。{@code fpsValues} /
 * {@code viewerCounts} 为 P2 矩阵 runner 预留——现在就定义进契约，避免 P2 改 record
 * 形态破坏调用方（向后兼容设计）。</p>
 *
 * <p>viewer 数按决策 ②（{@code docs/dynamic-data.md §13.3}）是「单次 encode 的线性外推
 * 乘数」，benchmark 不实跑 PacketEvents；fps 仅参与 SERIALIZE 换算公式，不改变
 * rasterize 本身。</p>
 *
 * @param warmupIterations   每场景测量前预热轮数（触发 C2 JIT 编译；&lt;0 归零）
 * @param measuredIterations 每场景测量轮数（&lt;1 归 1）
 * @param fpsValues          P2：模拟帧率档（SERIALIZE 成本 = tile × fps）；空 → {@code [5]}
 * @param viewerCounts       P2：模拟 viewer 数（encode 线性外推乘数）；空 → {@code [1]}
 * @param sceneSelector      场景选择：具体 sceneId / category 名 / {@code "all"}；空 → {@code "all"}
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

    /** 快速冒烟：少量迭代、单 fps/viewer——给 {@code /canvas bench run} 默认 + CI 功能性 gate。 */
    public static BenchmarkConfig quick() {
        return new BenchmarkConfig(5, 20, List.of(5), List.of(1), "all");
    }

    /** 完整压测：充分 warmup + 高迭代 + 多 fps/viewer 档（P2 矩阵 runner 用）。 */
    public static BenchmarkConfig full() {
        return new BenchmarkConfig(20, 200, List.of(1, 5, 10, 20, 30),
                List.of(1, 5, 20, 50), "all");
    }
}
