package ac.haru.hikaricanvas.benchmark;

import java.util.List;

/**
 * Benchmark 完整聚合报告——{@code report.json} 的根、{@code /canvas bench report} 与 HTML /
 * 50mspt 公式区的数据源。
 *
 * <p>本报告只含描述性测量（per-scene percentile + per-element 边际 + GC + 环境），<b>不含</b>任何
 * 推荐配置。fps / viewer 数仅作为公式的输入参数随 {@link BenchmarkConfig} 记录——rasterize 成本
 * 本身不依赖 fps / viewer，故<b>不</b>为每个 fps/viewer 组合重复测量（那是测同一个东西）。</p>
 *
 * @param schemaVersion    报告 schema 版本（CI baseline 比对用）
 * @param generatedAtMillis 生成时刻（epoch ms，由命令层注入——核心保持无 clock 可测）
 * @param env              运行环境快照
 * @param config           本次压测配置（含 fps/viewer 作为公式参数）
 * @param scenes           每个场景的聚合结果
 * @param perElement       per-element 边际成本（来自隔离场景 − 空白基线）
 * @param gc               整段 run 的 GC 增量
 * @param blankBaselineMs  空白同尺寸画布 rasterize 均值（ms）——per-element 扣除的固定开销
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
}
