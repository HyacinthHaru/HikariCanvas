package moe.hikari.canvas.benchmark;

import moe.hikari.canvas.render.CanvasCompositor;
import moe.hikari.canvas.state.ProjectState;

import java.util.ArrayList;
import java.util.List;

/**
 * Benchmark 编排器：把场景选取 → 计时 → 聚合 → per-element 归因 → GC/环境
 * 快照串成一次完整 run，产出 {@link BenchmarkReport}。
 *
 * <p>纯 headless：只依赖 {@link CanvasCompositor} + {@link ProjectState} + {@code java.*} +
 * {@link Instrumentation}，零 Bukkit / Player / PacketEvents（{@code PROPOSAL.md §5.2.7} /
 * {@code docs/dynamic-data.md §13.3}）。「只测 CPU / 内存，绝不测网络」。</p>
 *
 * <h2>「测一次」纪律</h2>
 * rasterize / palette 成本<b>只取决于场景本身</b>（canvas 尺寸已烘进每个场景），<b>不</b>取决于
 * fps / viewer 数。故每个场景只测<b>一次</b>——绝不为每个 fps/viewer 组合重复 rasterize（那是
 * 在重复测同一个东西）。fps / viewer 仅作为公式的输入参数随 {@link BenchmarkConfig} 记录在
 * 报告里，不参与本类的任何测量循环。
 *
 * <h2>per-element 边际成本的空白基线</h2>
 * 单元素隔离场景全是 2×2 墙；要分离出「多画一个元素」的净成本，必须扣掉同尺寸空白画布的固定开销
 * （buffer 分配 / clear / palette 量化）。本类用 {@code new ProjectState(2,2)} 造一个临时空白场景，
 * <b>用与正式场景完全相同的 config</b> 计时（保证可比），其 rasterize 均值即基线
 * {@code blankBaselineMs}。该空白场景<b>不</b>进报告的 {@code scenes} 列表，仅作内部基线。
 *
 * <h2>确定性 / 可测性</h2>
 * 不在内部读时钟——{@code generatedAtMillis} 由命令层注入，让 runner 保持确定性、可单测。
 * 空场景选择优雅降级：{@code scenes} / {@code perElement} 为空，空白基线仍照测（报告仍有
 * 环境 + GC + 基线信息）。
 *
 * <h2>线程模型</h2>
 * 同步串行——全部计时在调用线程上完成（与 {@link SceneTimer} 的分配计数前提一致）。无可变实例
 * 状态；多实例并发互不干扰，但单次 run 期间的 GC 快照是进程级全局量，并发跑会互相污染 GC 归因。
 */
public final class BenchmarkRunner {

    /** 空白基线场景的尺寸（tile）——必须与单元素隔离场景一致（皆 2×2），否则扣减不可比。 */
    private static final int BASELINE_TILES = 2;

    /**
     * 跑一次完整 benchmark，产出聚合报告。
     *
     * <p>流程：选场景 → 取 GC 起点快照 → 测同尺寸空白基线 → 逐场景计时 + 聚合 → 取 GC 终点快照 →
     * 从隔离场景结果减基线推 per-element → 抓环境 → 组装报告。</p>
     *
     * @param compositor        headless {@link CanvasCompositor}（{@code BenchCompositor.create} 产出）
     * @param lib               场景工厂
     * @param config            压测配置（轮数 + selector；fps/viewer 仅作公式参数随报告记录）
     * @param generatedAtMillis 报告生成时刻（epoch ms，由调用方注入——核心不读时钟保持可测）
     * @return 完整 {@link BenchmarkReport}
     */
    public BenchmarkReport run(CanvasCompositor compositor, SceneLibrary lib,
                               BenchmarkConfig config, long generatedAtMillis) {
        SceneTimer timer = new SceneTimer();
        ResultAggregator agg = new ResultAggregator();

        // 1) 按 selector 选场景（可能为空——优雅降级）。
        List<BenchmarkScene> scenes = lib.select(config.sceneSelector());

        // 2) GC 起点快照（整段 run 的 GC 增量起算点，含空白基线 + 全场景）。
        Instrumentation.GcStat gcBefore = Instrumentation.gcSnapshot();

        // 3) 空白同尺寸基线：用与正式场景相同的 config 计时，保证可比。
        //    不进报告 scenes 列表，仅供 per-element 扣减固定开销。
        BenchmarkScene blankScene = new BenchmarkScene(
                "blank-baseline-" + BASELINE_TILES + "x" + BASELINE_TILES,
                "Blank baseline " + BASELINE_TILES + "x" + BASELINE_TILES,
                "baseline", "none",
                BASELINE_TILES, BASELINE_TILES,
                new ProjectState(BASELINE_TILES, BASELINE_TILES));
        SceneResult blank = agg.aggregate(blankScene, timer.time(compositor, blankScene, config));
        double blankMeanMs = blank.rasterizeMs().mean();

        // 4) 逐场景计时 + 聚合（每场景只测一次——成本与 fps/viewer 无关）。
        List<SceneResult> results = new ArrayList<>(scenes.size());
        for (BenchmarkScene scene : scenes) {
            List<RasterizeSample> samples = timer.time(compositor, scene, config);
            results.add(agg.aggregate(scene, samples));
        }

        // 5) GC 终点快照 → 整段 run 的 GC 增量。
        Instrumentation.GcStat gcAfter = Instrumentation.gcSnapshot();
        GcSummary gc = GcSummary.between(gcBefore, gcAfter);

        // 6) 仅从单元素隔离场景推 per-element 边际成本（其余分类无单一主元素，归因无意义）。
        List<SceneResult> isolationResults = new ArrayList<>();
        for (SceneResult r : results) {
            if (BenchmarkScene.CATEGORY_ELEMENT_ISOLATION.equals(r.category())) {
                isolationResults.add(r);
            }
        }
        List<PerElementCost> perElement = agg.derivePerElement(isolationResults, blankMeanMs);

        // 7) 环境快照 + 组装报告。
        EnvInfo env = EnvInfo.capture();
        return new BenchmarkReport(
                BenchmarkReport.SCHEMA_VERSION, generatedAtMillis, env, config,
                results, perElement, gc, blankMeanMs);
    }
}
