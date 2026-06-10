package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.HikariCanvasConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 脚本执行预算三闸（{@code docs/scripting.md §2.4}；config {@code scripts.budget} 段）：
 * <ol>
 *   <li><b>runs/s</b>：per-rule 1s 固定窗触发频率（{@link #tryAcquireRun}）；超 → 丢弃本次 run</li>
 *   <li><b>chain depth</b>：ABA 熔断（D8；{@link #chainDepthExceeded}）；不自动禁用脚本</li>
 *   <li><b>actions/run</b>：单次触发展开执行的动作总数（{@link #actionsExceeded}）</li>
 * </ol>
 *
 * <p><b>audit 限频（K5）</b>：{@code SCRIPT_RUN_BLOCKED} per-rule 10s 窗只记 1 条
 * （{@link #shouldAuditBlock}）——10 runs/s 限流被持续触发时不能把 audit DB 打爆；
 * 掐断照常发生，只是不刷 audit 表。</p>
 *
 * <p>热更：字段 volatile，{@code /canvas reload} 时（批次 3 在
 * {@code HikariCanvas.applyConfig} 接线，照 {@code ScriptStore.setMaxRulesPerWall} 先例）
 * 调 {@link #applyConfig}。clock 经 {@link LongSupplier} 注入（照 {@code HistoryStack} 范式），
 * 单测确定性。</p>
 *
 * <p>线程安全：任意线程可调（Runner 线程 / Router 线程 / reload 主线程）。
 * 内存表按 ruleKey 记账，超 {@link #MAP_MAX} 整体 clear 兜底（规则总量受 per-wall 配额
 * 约束，正常永远到不了上限；照 {@code ConditionEvaluator.CACHE_MAX} 纪律）。</p>
 *
 * <p>非 final：单测以子类记录调用（{@code AuditLog} 是 final 无接口，限频判定只能在
 * 本层侧证）。生产不应继承。</p>
 */
public class ScriptBudget {

    /** runs/s 固定窗宽。 */
    static final long RUN_WINDOW_MS = 1000L;
    /** audit 限频窗宽（K5）。 */
    static final long AUDIT_WINDOW_MS = 10_000L;
    /** 记账表容量上限，超限整体 clear（防异常输入面撑爆）。 */
    static final int MAP_MAX = 4096;

    private volatile int maxActionsPerRun;
    private volatile int maxRunsPerSecond;
    private volatile int maxChainDepth;

    private final LongSupplier clock;

    /** ruleKey → 当前 1s 窗（windowStart + count）。 */
    private final ConcurrentHashMap<String, RunWindow> runWindows = new ConcurrentHashMap<>();
    /** ruleKey → 上次 RUN_BLOCKED audit 时间戳（K5 限频）。 */
    private final ConcurrentHashMap<String, Long> auditStamps = new ConcurrentHashMap<>();

    public ScriptBudget(HikariCanvasConfig.ScriptsConfig config) {
        this(config, System::currentTimeMillis);
    }

    /** clock 注入 seam（测试用；照 {@code HistoryStack.setClock}）。 */
    public ScriptBudget(HikariCanvasConfig.ScriptsConfig config, LongSupplier clock) {
        this.clock = clock;
        applyConfig(config);
    }

    /** 热更（/canvas reload；批次 3 接线）。已开的 1s 窗不重置，下个窗起生效。 */
    public final void applyConfig(HikariCanvasConfig.ScriptsConfig config) {
        this.maxActionsPerRun = Math.max(1, config.maxActionsPerRun());
        this.maxRunsPerSecond = Math.max(1, config.maxRunsPerSecond());
        this.maxChainDepth = Math.max(1, config.maxChainDepth());
    }

    /**
     * per-rule runs/s 闸：1s 固定窗计数。窗内未满 → 计数 +1 并放行；满 → false（丢弃）。
     */
    public boolean tryAcquireRun(String ruleKey) {
        long now = clock.getAsLong();
        if (runWindows.size() >= MAP_MAX) {
            runWindows.clear();
        }
        RunWindow w = runWindows.computeIfAbsent(ruleKey, k -> new RunWindow(now));
        synchronized (w) {
            if (now - w.windowStart >= RUN_WINDOW_MS || now < w.windowStart) {
                // 新窗（含时钟回拨防御：now 倒退也重开窗，不永久卡死）
                w.windowStart = now;
                w.count = 0;
            }
            if (w.count >= maxRunsPerSecond) return false;
            w.count++;
            return true;
        }
    }

    /** ABA 链深判定（K1/D8）：depth ≥ max → 掐断整个 run。 */
    public boolean chainDepthExceeded(int chainDepth) {
        return chainDepth >= maxChainDepth;
    }

    /** 单 run 动作计数判定：count &gt; max → 掐断剩余动作。 */
    public boolean actionsExceeded(int actionCount) {
        return actionCount > maxActionsPerRun;
    }

    public int maxActionsPerRun() {
        return maxActionsPerRun;
    }

    public int maxChainDepth() {
        return maxChainDepth;
    }

    public int maxRunsPerSecond() {
        return maxRunsPerSecond;
    }

    /**
     * K5 audit 限频：per-rule 10s 窗内只放行 1 条 {@code SCRIPT_RUN_BLOCKED}。
     * compute 原子换 stamp，无 race 双记。
     */
    public boolean shouldAuditBlock(String ruleKey) {
        long now = clock.getAsLong();
        if (auditStamps.size() >= MAP_MAX) {
            auditStamps.clear();
        }
        boolean[] allow = new boolean[1];
        auditStamps.compute(ruleKey, (k, prev) -> {
            if (prev != null && now - prev < AUDIT_WINDOW_MS && now >= prev) {
                allow[0] = false;
                return prev;
            }
            allow[0] = true;
            return now;
        });
        return allow[0];
    }

    /** per-rule 1s 固定窗（per-entry synchronized；条目数受 MAP_MAX 约束）。 */
    private static final class RunWindow {
        long windowStart;
        int count;

        RunWindow(long start) {
            this.windowStart = start;
        }
    }
}
