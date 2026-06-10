package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.script.Action;
import moe.hikari.canvas.script.ScriptRule;
import moe.hikari.canvas.storage.AuditLog;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 脚本单线程执行管线（0.7.0-P2 T3；{@code docs/scripting.md §3}）。
 *
 * <p><b>线程模型</b>：单线程 {@link ScheduledExecutorService}（daemon，线程名
 * {@code hikari-script-runner}；照 {@code AnimationTicker} 范式）。同一时刻最多一个
 * action 在跑——天然串行化同墙脚本副作用，避免并发写 ProjectState。</p>
 *
 * <p><b>执行语义</b>：</p>
 * <ul>
 *   <li>{@link #submit}：①链深闸（ABA / K1，≥ max 整个 run 掐断 + WARNING）
 *       ②runs/s 闸（{@link ScriptBudget#tryAcquireRun}；拒 → 丢弃）→ 队列执行。
 *       两闸掐断都记 audit {@code SCRIPT_RUN_BLOCKED}（K5 per-rule 10s 限频）。</li>
 *   <li>{@code if} → {@link ConditionEvaluator#eval}（坏条件恒 false 不炸链）+
 *       递归对应分支；condition 步进 trace。</li>
 *   <li>{@code wait} → 把剩余动作（含外层 if 之后的后续）包成 continuation
 *       {@code schedule(ms)} 重入队列，<b>不睡线程</b>；动作计数跨段累计，
 *       <b>不重过 runs/s</b>；chainDepth 延续。</li>
 *   <li>动作总数（含嵌套 if / wait 自身）超 {@code max-actions-per-run} → 掐断剩余 +
 *       blocked step + audit（限频）。</li>
 * </ul>
 *
 * <p><b>ABA 链深（K1）</b>：执行段全程 {@link #CHAIN_DEPTH} ThreadLocal 置
 * {@code ctx.chainDepth()}（finally 必清）。脚本动作 setVariable →
 * {@code VariableStore.fireChange} 同步发生在 runner 线程 → TriggerRouter（批次 3）
 * 的 listener <b>直读 {@code CHAIN_DEPTH.get()}</b>：null（非脚本来源）→ depth 0；
 * 非 null（runner 线程脚本写变量）→ +1。不能用 currentChainDepth()+1——它把无上下文
 * 折叠成 0 会让非脚本来源被错算成 depth 1。零 VariableStore API 改动。</p>
 *
 * <p><b>trace（K10）</b>：每 run 收集 {@code List<TraceStep>}，结束后 FINE log 一行
 * summary；P3 接 {@code script.test} ack。</p>
 */
public final class ScriptRunner {

    /**
     * K1：ABA 链深 ThreadLocal。<b>包级可见</b>——TriggerRouter（批次 3，同包）在
     * VariableStore 同步 fireChange 回调里读。null = 当前线程无脚本 run 上下文。
     */
    static final ThreadLocal<Integer> CHAIN_DEPTH = new ThreadLocal<>();

    /** Router 读取入口（包级）：当前线程链深；无 run 上下文返 0。 */
    static int currentChainDepth() {
        Integer d = CHAIN_DEPTH.get();
        return d == null ? 0 : d;
    }

    /**
     * 调度 seam：生产 = 单线程 SES；测试注同步直跑替身（wait 续接 / 计数延续可同步验证）。
     */
    interface TaskScheduler {
        void execute(Runnable task);

        void schedule(Runnable task, long delayMs);

        /** 幂等优雅关停（生产实现 awaitTermination 5s 后 shutdownNow）。 */
        void shutdown();
    }

    private final ConditionEvaluator conditions;
    private final ActionSink sink;
    private final ScriptBudget budget;
    private final @Nullable AuditLog audit;
    private final Logger log;
    private final TaskScheduler scheduler;
    private volatile boolean shutdown;

    /** 生产装配：自建单线程 daemon SES（线程名 {@code hikari-script-runner}）。 */
    public ScriptRunner(ConditionEvaluator conditions, ActionSink sink, ScriptBudget budget,
                        @Nullable AuditLog audit, Logger log) {
        this(conditions, sink, budget, audit, log, new SesScheduler());
    }

    /** 测试装配：注入调度替身。 */
    ScriptRunner(ConditionEvaluator conditions, ActionSink sink, ScriptBudget budget,
                 @Nullable AuditLog audit, Logger log, TaskScheduler scheduler) {
        this.conditions = conditions;
        this.sink = sink;
        this.budget = budget;
        this.audit = audit;
        this.log = log;
        this.scheduler = scheduler;
    }

    /**
     * 投递一次 run（任意线程可调；TriggerRouter / script.test 路径用）。
     * 两闸（chain / rate）在投递侧判定，重活全在 runner 线程。
     */
    public void submit(String wallId, ScriptRule rule, TriggerContext ctx) {
        if (shutdown || wallId == null || rule == null || ctx == null) return;
        String ruleKey = ruleKey(wallId, rule);

        // 闸 1：ABA 链深（K1/D8）——整个 run 掐断，不自动禁用规则
        if (budget.chainDepthExceeded(ctx.chainDepth())) {
            log.warning("[脚本] ABA 链深熔断: rule=" + ruleKey
                    + " depth=" + ctx.chainDepth() + "/" + budget.maxChainDepth()
                    + " source=" + ctx.source()
                    + (ctx.detail() == null ? "" : " detail=" + ctx.detail()));
            auditBlocked(ruleKey, wallId, rule, "chain", ctx);
            return;
        }

        // 闸 2：per-rule runs/s（超 → 丢弃 + blocked trace 进 FINE + audit 限频）
        if (!budget.tryAcquireRun(ruleKey)) {
            if (log.isLoggable(Level.FINE)) {
                log.fine("[脚本 trace] rule=" + ruleKey + " source=" + ctx.source()
                        + " outcome=blocked steps=[trigger=blocked(rate)]");
            }
            auditBlocked(ruleKey, wallId, rule, "rate", ctx);
            return;
        }

        try {
            scheduler.execute(() -> startRun(wallId, rule, ctx));
        } catch (RejectedExecutionException e) {
            // shutdown 竞态：静默丢弃（关服路径不需要补跑）
        }
    }

    /** 幂等关停（onDisable；照 {@code AnimationTicker.shutdown}）。 */
    public void shutdown() {
        if (shutdown) return;
        shutdown = true;
        scheduler.shutdown();
    }

    // ---------- 执行（仅 runner 线程） ----------

    /** 跨 wait 续接共享的 run 级状态（单线程串行，无并发访问）。 */
    private static final class RunState {
        final String wallId;
        final ScriptRule rule;
        final TriggerContext ctx;
        final List<TraceStep> trace = new ArrayList<>();
        int actionCount;

        RunState(String wallId, ScriptRule rule, TriggerContext ctx) {
            this.wallId = wallId;
            this.rule = rule;
            this.ctx = ctx;
        }
    }

    /** 执行帧：actions 列表 + 续接下标 + blockId 树路径前缀（如 "actions/" / "actions/2/then/"）。 */
    private record Frame(List<Action> actions, int index, String prefix) {}

    private void startRun(String wallId, ScriptRule rule, TriggerContext ctx) {
        RunState st = new RunState(wallId, rule, ctx);
        st.trace.add(TraceStep.ok("trigger", "trigger",
                ctx.source() + (ctx.detail() == null ? "" : " " + ctx.detail())));
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(rule.actions(), 0, "actions/"));
        runFrames(st, stack);
    }

    /**
     * 执行帧栈直到耗尽 / wait 续接 / 掐断。整段在 runner 线程上跑；
     * {@link #CHAIN_DEPTH} 全程置位，finally 必清（K1）。
     */
    private void runFrames(RunState st, Deque<Frame> stack) {
        CHAIN_DEPTH.set(st.ctx.chainDepth());
        try {
            outer:
            while (!stack.isEmpty()) {
                Frame f = stack.pop();
                List<Action> acts = f.actions();
                int i = f.index();
                while (i < acts.size()) {
                    Action a = acts.get(i);
                    String blockId = f.prefix() + i;
                    st.actionCount++;
                    if (budget.actionsExceeded(st.actionCount)) {
                        st.trace.add(TraceStep.blocked(blockId,
                                "动作总数超上限 " + budget.maxActionsPerRun() + "，剩余动作已掐断"));
                        auditBlocked(ruleKey(st.wallId, st.rule), st.wallId, st.rule,
                                "actions", st.ctx);
                        finish(st, "blocked");
                        return;
                    }
                    if (a instanceof Action.If iff) {
                        // 条件求值（坏条件 → false，不炸链；ConditionEvaluator 契约）
                        boolean cond = conditions.eval(iff.condition(), st.wallId);
                        st.trace.add(TraceStep.ok(blockId, "condition", Boolean.toString(cond)));
                        // 先押回当前帧剩余部分，再押分支帧（栈序 = 分支先执行）
                        stack.push(new Frame(acts, i + 1, f.prefix()));
                        List<Action> branch = cond ? iff.then() : iff.elseActions();
                        stack.push(new Frame(branch, 0,
                                blockId + (cond ? "/then/" : "/else/")));
                        continue outer;
                    }
                    if (a instanceof Action.Wait wt) {
                        st.trace.add(TraceStep.ok(blockId, "action", "wait " + wt.ms() + "ms"));
                        // 剩余动作（含外层 if 后续——已在栈里）打包成 continuation。
                        // 动作计数 / trace / chainDepth 经 RunState 延续；不重过 runs/s。
                        stack.push(new Frame(acts, i + 1, f.prefix()));
                        Deque<Frame> cont = new ArrayDeque<>(stack);
                        if (!shutdown) {
                            try {
                                scheduler.schedule(() -> runFrames(st, cont), wt.ms());
                            } catch (RejectedExecutionException e) {
                                // shutdown 竞态：续接丢弃
                            }
                        }
                        return;
                    }
                    // 普通动作 → ActionSink（实现侧三层隔离；此处兜底防御）
                    TraceStep step;
                    try {
                        step = sink.execute(st.wallId, blockId, a);
                    } catch (RuntimeException e) {
                        log.log(Level.WARNING, "[脚本] 动作执行异常（链继续）: rule="
                                + ruleKey(st.wallId, st.rule) + " block=" + blockId
                                + " err=" + e.getMessage(), e);
                        step = TraceStep.error(blockId, String.valueOf(e.getMessage()));
                    }
                    st.trace.add(step == null
                            ? TraceStep.error(blockId, "ActionSink 返回 null step") : step);
                    i++;
                }
            }
            finish(st, "ok");
        } catch (Throwable t) {
            // 任务级异常隔离：单 run 失败不杀 runner 线程（照 AnimationTicker.tick 范式）
            log.log(Level.WARNING, "[脚本] run 失败: rule=" + ruleKey(st.wallId, st.rule)
                    + " err=" + t.getMessage(), t);
        } finally {
            CHAIN_DEPTH.remove();
        }
    }

    /** run 结束（正常 / blocked）：trace 进 FINE log 一行 summary（K10）。 */
    private void finish(RunState st, String outcome) {
        if (!log.isLoggable(Level.FINE)) return;
        StringBuilder sb = new StringBuilder(128);
        sb.append("[脚本 trace] rule=").append(ruleKey(st.wallId, st.rule))
                .append(" source=").append(st.ctx.source())
                .append(" depth=").append(st.ctx.chainDepth())
                .append(" outcome=").append(outcome)
                .append(" actions=").append(st.actionCount)
                .append(" steps=[");
        for (int i = 0; i < st.trace.size(); i++) {
            if (i > 0) sb.append(", ");
            TraceStep s = st.trace.get(i);
            sb.append(s.blockId()).append('=').append(s.result());
            if (s.detail() != null && !"ok".equals(s.result())) {
                sb.append('(').append(s.detail()).append(')');
            }
        }
        sb.append(']');
        log.fine(sb.toString());
    }

    /** audit {@code SCRIPT_RUN_BLOCKED}（K5 per-rule 10s 限频；DB 失败由 AuditLog 自吞）。 */
    private void auditBlocked(String ruleKey, String wallId, ScriptRule rule,
                              String reason, TriggerContext ctx) {
        if (audit == null || !budget.shouldAuditBlock(ruleKey)) return;
        try {
            audit.record("SCRIPT_RUN_BLOCKED", null, null, null, null, Map.of(
                    "wallId", wallId,
                    "ruleId", String.valueOf(rule.id()),
                    "ruleName", String.valueOf(rule.name()),
                    "reason", reason,
                    "source", ctx.source().name(),
                    "chainDepth", ctx.chainDepth()));
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "[脚本] SCRIPT_RUN_BLOCKED audit 失败: " + e.getMessage(), e);
        }
    }

    private static String ruleKey(String wallId, ScriptRule rule) {
        return wallId + ":" + rule.id();
    }

    /** 生产调度器：单线程 daemon SES（照 {@code AnimationTicker} 关停纪律）。 */
    private static final class SesScheduler implements TaskScheduler {
        private final ScheduledExecutorService ses =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "hikari-script-runner");
                    t.setDaemon(true);
                    return t;
                });

        @Override
        public void execute(Runnable task) {
            ses.execute(task);
        }

        @Override
        public void schedule(Runnable task, long delayMs) {
            ses.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void shutdown() {
            ses.shutdown();
            try {
                if (!ses.awaitTermination(5, TimeUnit.SECONDS)) {
                    ses.shutdownNow();
                }
            } catch (InterruptedException e) {
                ses.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
