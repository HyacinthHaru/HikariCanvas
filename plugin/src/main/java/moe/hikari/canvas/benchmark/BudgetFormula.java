package moe.hikari.canvas.benchmark;

/**
 * 50mspt 主线程预算公式（{@code docs/dynamic-data.md §13.3} / {@code PROPOSAL.md §5.2.7}）。
 *
 * <p>服主用<b>自己</b>的 mspt 预算 / 主线程份额 / 目标帧率代入算。HTML 报告里的内联 JS 计算器
 * 镜像本类的算法，让服主在浏览器里实时试算。</p>
 *
 * <h2>公式</h2>
 * <pre>
 *   可用主线程预算(ms/s) = mspt × tps × 主线程份额%
 *   单 wall×fps 成本(ms/s) = rasterize_p95(ms) × fps
 *   可载 wall 数 ≈ 可用预算 ÷ 单 wall×fps 成本
 * </pre>
 *
 * <h2>这是保守下界，不是上限</h2>
 * 公式把<b>整个</b> rasterize 成本当作主线程成本。实际 rasterize 走 async 线程，主线程只做 schedule +
 * packet handoff，真实主线程成本<b>更低</b>——故算出的「可载 wall 数」偏保守（实际能开更多）。真正的
 * 主线程成本需后续 Benchmark 在主线程侧实测标定。这条 disclaimer 必须随计算器一起展示，
 * 避免被当成硬上限。
 */
public final class BudgetFormula {

    /** 必须随计算器一起展示的口径说明。 */
    public static final String DISCLAIMER =
            "这个数偏保守，实际通常能放得更多。它只是个大概参考，别当成硬上限。";

    /**
     * 计算器输入。默认值取自 §13.3 示例（mspt 50 / tps 20 / 主线程份额 30% / fps 5 = v1 静态招牌默认）。
     *
     * @param msptBudgetMs        每 tick 主线程预算（ms，默认 50）
     * @param tps                 目标 tps（默认 20）
     * @param mainThreadSharePct  分给本插件的主线程份额（%，默认 30——其余给 world tick / 别的插件）
     * @param fps                 目标帧率（默认 5）
     */
    public record Inputs(double msptBudgetMs, int tps, double mainThreadSharePct, int fps) {
        public Inputs {
            if (msptBudgetMs <= 0) msptBudgetMs = 50.0;
            if (tps <= 0) tps = 20;
            if (mainThreadSharePct <= 0 || mainThreadSharePct > 100) mainThreadSharePct = 30.0;
            if (fps <= 0) fps = 1;
        }

        /** §13.3 示例默认值。 */
        public static Inputs defaults() {
            return new Inputs(50.0, 20, 30.0, 5);
        }
    }

    private BudgetFormula() {
        // 工具类
    }

    /** 主线程每秒可用预算（ms/s）= mspt × tps × 份额%。 */
    public static double availableMsPerSecond(Inputs in) {
        return in.msptBudgetMs() * in.tps() * (in.mainThreadSharePct() / 100.0);
    }

    /**
     * 某场景在给定帧率下的「可载 wall 数」保守下界。
     *
     * @param rasterizeP95Ms 该场景 rasterize 的 p95（ms）
     * @param in             预算输入
     * @return 可载 wall 数；当单 wall 成本 ≤ 0（理论上不会，防御性）返回 {@link Double#POSITIVE_INFINITY}
     */
    public static double projectedMaxWalls(double rasterizeP95Ms, Inputs in) {
        double costPerWallSecond = rasterizeP95Ms * in.fps();
        if (costPerWallSecond <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return availableMsPerSecond(in) / costPerWallSecond;
    }
}
