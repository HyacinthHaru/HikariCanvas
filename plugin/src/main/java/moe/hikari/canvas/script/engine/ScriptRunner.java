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
 * 脚本单线程执行管线（{@code docs/scripting.md §3}）。
 *
 * <p><b>线程模型</b>：单线程 {@link ScheduledExecutorService}（daemon，线程名
 * {@code hikari-script-runner}；照 {@code AnimationTicker} 范式）。同一时刻最多一个
 * action 在跑——天然串行化同墙脚本副作用，避免并发写 ProjectState。</p>
 *
 * <p><b>执行语义</b>：</p>
 * <ul>
 *   <li>{@link #submit}：①链深闸（ABA，≥ max 整个 run 掐断 + WARNING）
 *       ②runs/s 闸（{@link ScriptBudget#tryAcquireRun}；拒 → 丢弃）→ 队列执行。
 *       两闸掐断都记 audit {@code SCRIPT_RUN_BLOCKED}（per-rule 10s 限频）。</li>
 *   <li>{@code if} → {@link ConditionEvaluator#eval}（坏条件恒 false 不炸链）+
 *       递归对应分支；condition 步进 trace。</li>
 *   <li>{@code wait} → 把剩余动作（含外层 if 之后的后续）包成 continuation
 *       {@code schedule(ms)} 重入队列，<b>不睡线程</b>；动作计数跨段累计，
 *       <b>不重过 runs/s</b>；chainDepth 延续。</li>
 *   <li>动作总数（含嵌套 if / wait 自身）超 {@code max-actions-per-run} → 掐断剩余 +
 *       blocked step + audit（限频）。</li>
 * </ul>
 *
 * <p><b>ABA 链深</b>：执行段全程 {@link #CHAIN_DEPTH} ThreadLocal 置
 * {@code ctx.chainDepth()}（finally 必清）。脚本动作 setVariable →
 * {@code VariableStore.fireChange} 同步发生在 runner 线程 → TriggerRouter
 * 的 listener <b>直读 {@code CHAIN_DEPTH.get()}</b>：null（非脚本来源）→ depth 0；
 * 非 null（runner 线程脚本写变量）→ +1。不能用 currentChainDepth()+1——它把无上下文
 * 折叠成 0 会让非脚本来源被错算成 depth 1。零 VariableStore API 改动。</p>
 *
 * <p><b>trace</b>：每 run 收集 {@code List<TraceStep>}，结束后 FINE log 一行
 * summary + {@code script.test} ack。</p>
 */
public final class ScriptRunner {

    /**
     * ABA 链深 ThreadLocal。<b>包级可见</b>——TriggerRouter（同包）在
     * VariableStore 同步 fireChange 回调里读。null = 当前线程无脚本 run 上下文。
     */
    static final ThreadLocal<Integer> CHAIN_DEPTH = new ThreadLocal<>();

    /** Router 读取入口（包级）：当前线程链深；无 run 上下文返 0。 */
    static int currentChainDepth() {
        Integer d = CHAIN_DEPTH.get();
        return d == null ? 0 : d;
    }

    /**
     * 当前 run 的 ruleKey（{@code wallId:ruleId}）ThreadLocal。
     * 与 {@link #CHAIN_DEPTH} 同生命周期（runFrames 置位 / finally 清）；
     * {@code ActionExecutor.runCommand} 的 SCRIPT_COMMAND_EXECUTED audit 读它记
     * "来源规则"（scripting.md §5.2）——ActionSink 接口不为此扩参。
     */
    static final ThreadLocal<String> RULE_KEY = new ThreadLocal<>();

    /** 包级读取入口：当前线程 run 的 ruleKey；无 run 上下文返 null。 */
    static @Nullable String currentRuleKey() {
        return RULE_KEY.get();
    }

    /**
     * 当前 run 的触发玩家名（{@link TriggerContext#detail()}，仅 player* 触发器有值）。
     * 与 {@link #RULE_KEY} 同生命周期（runFrames 置位 / finally 清）。
     * {@code ActionExecutor.doSendMessage} 读它拿触发玩家——ActionSink 接口不为此扩参。
     */
    static final ThreadLocal<String> TRIGGER_DETAIL = new ThreadLocal<>();

    /** 包级读取入口：当前线程触发玩家名；无 run 上下文返 null。 */
    static @Nullable String currentTriggerDetail() {
        return TRIGGER_DETAIL.get();
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
    /** 时间源（WaitUntil 超时判定）。生产 {@code System::currentTimeMillis}；测试注入可控时钟。 */
    private final java.util.function.LongSupplier clock;
    /**
     * 补间引擎（TweenScheduler）注入 seam。{@code null} = 未装配（降级行为：记 trace 跳过）。
     * 生产由 {@link moe.hikari.canvas.HikariCanvas} onEnable 经 {@link #setTweenScheduler} 注入；
     * 测试可注 fake。
     */
    private volatile @Nullable TweenScheduler tweenScheduler;

    /** WaitUntil 轮询间隔（ms）。 */
    private static final long WAIT_UNTIL_TICK_MS = 100L;

    /**
     * RandomBranch 随机源（runner 单线程直调，无并发竞争）。
     * 生产默认 {@code ThreadLocalRandom.current()}；测试经 {@link #setRngForTest} 注入确定性源。
     * IntSupplier 语义：返回 [0,100) 内随机整数（nextInt(100) 等价）。
     */
    private java.util.function.IntSupplier rng =
            () -> java.util.concurrent.ThreadLocalRandom.current().nextInt(100);

    /** 生产装配：自建单线程 daemon SES（线程名 {@code hikari-script-runner}）。 */
    public ScriptRunner(ConditionEvaluator conditions, ActionSink sink, ScriptBudget budget,
                        @Nullable AuditLog audit, Logger log) {
        this(conditions, sink, budget, audit, log, new SesScheduler());
    }

    /** 测试装配：注入调度替身（时钟用 System 默认）。 */
    ScriptRunner(ConditionEvaluator conditions, ActionSink sink, ScriptBudget budget,
                 @Nullable AuditLog audit, Logger log, TaskScheduler scheduler) {
        this(conditions, sink, budget, audit, log, scheduler, System::currentTimeMillis);
    }

    /** 测试装配：注入调度替身 + 时钟（WaitUntil 超时测试）。 */
    ScriptRunner(ConditionEvaluator conditions, ActionSink sink, ScriptBudget budget,
                 @Nullable AuditLog audit, Logger log, TaskScheduler scheduler,
                 java.util.function.LongSupplier clock) {
        this.conditions = conditions;
        this.sink = sink;
        this.budget = budget;
        this.audit = audit;
        this.log = log;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /**
     * 注入补间引擎（onEnable 装配后调；热更不需要，TweenScheduler 本身持 config 引用）。
     * volatile write 对 runner 线程的后续读可见。
     */
    public void setTweenScheduler(TweenScheduler ts) {
        this.tweenScheduler = ts;
    }

    /**
     * 测试 seam：注入确定性随机源（生产不调用）。
     * {@code rng} 返回 [0,100) 范围的整数值——调用侧 {@code rng.getAsInt() < probability}。
     */
    void setRngForTest(java.util.function.IntSupplier rng) {
        this.rng = rng;
    }

    /**
     * 投递一次 run（任意线程可调；TriggerRouter 路径用）。
     * 两闸（chain / rate）在投递侧判定，重活全在 runner 线程。
     */
    public void submit(String wallId, ScriptRule rule, TriggerContext ctx) {
        submit(wallId, rule, ctx, null);
    }

    /**
     * 带 trace callback 的投递（{@code script.test} 路径）。
     *
     * <p><b>callback 契约——恰一次</b>：run 最终段结束（含 wait 续接跨段后的真正末尾）/
     * Budget 掐断（actions 超限）/ 投递侧两闸拒（chain / rate，回 blocked trigger step）/
     * run 级异常，都恰好回调一次。例外：shutdown 竞态丢弃（关服路径，session 已死）
     * 不回调。callback 在 runner 线程（闸拒路径在调用线程）执行；callback 自身抛异常
     * 被吞 + WARNING，不杀 runner。</p>
     */
    public void submit(String wallId, ScriptRule rule, TriggerContext ctx,
                       @Nullable java.util.function.Consumer<List<TraceStep>> traceCallback) {
        if (shutdown || wallId == null || rule == null || ctx == null) return;
        String ruleKey = ruleKey(wallId, rule);

        // 闸 1：ABA 链深——整个 run 掐断，不自动禁用规则
        if (budget.chainDepthExceeded(ctx.chainDepth())) {
            log.warning("[script] ABA chain depth circuit-breaker: rule=" + ruleKey
                    + " depth=" + ctx.chainDepth() + "/" + budget.maxChainDepth()
                    + " source=" + ctx.source()
                    + (ctx.detail() == null ? "" : " detail=" + ctx.detail()));
            auditBlocked(ruleKey, wallId, rule, "chain", ctx);
            fireTrace(traceCallback, List.of(TraceStep.blocked("trigger",
                    "chain-depth tripped (depth=" + ctx.chainDepth() + "/" + budget.maxChainDepth() + ")")));
            return;
        }

        // 闸 2：per-rule runs/s（超 → 丢弃 + blocked trace 进 FINE + audit 限频）
        if (!budget.tryAcquireRun(ruleKey)) {
            if (log.isLoggable(Level.FINE)) {
                log.fine("[script trace] rule=" + ruleKey + " source=" + ctx.source()
                        + " outcome=blocked steps=[trigger=blocked(rate)]");
            }
            auditBlocked(ruleKey, wallId, rule, "rate", ctx);
            fireTrace(traceCallback, List.of(TraceStep.blocked("trigger",
                    "trigger rate exceeded (" + budget.maxRunsPerSecond() + "/s)")));
            return;
        }

        try {
            scheduler.execute(() -> startRun(wallId, rule, ctx, traceCallback));
        } catch (RejectedExecutionException e) {
            // shutdown 竞态：静默丢弃（关服路径不需要补跑，callback 不回调——契约例外）
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
        /** trace callback（wait 续接经本对象延续传递）；可 null。 */
        final @Nullable java.util.function.Consumer<List<TraceStep>> traceCallback;
        final List<TraceStep> trace = new ArrayList<>();
        int actionCount;
        /** callback 恰一次保险（finish 与 run 级异常路径互斥触发）。 */
        boolean callbackFired;
        /**
         * RepeatUntil 每个 blockId 的已执行轮数（while 语义动态循环计数）。嵌套
         * RepeatUntil 各 blockId 独立；跳出（满足 / 达上限）时 remove 该 blockId；run 结束随 RunState 弃。
         */
        final java.util.Map<String, Integer> repeatUntilRounds = new java.util.HashMap<>();

        RunState(String wallId, ScriptRule rule, TriggerContext ctx,
                 @Nullable java.util.function.Consumer<List<TraceStep>> traceCallback) {
            this.wallId = wallId;
            this.rule = rule;
            this.ctx = ctx;
            this.traceCallback = traceCallback;
        }
    }

    /** 执行帧：actions 列表 + 续接下标 + blockId 树路径前缀（如 "actions/" / "actions/2/then/"）。 */
    private record Frame(List<Action> actions, int index, String prefix) {}

    private void startRun(String wallId, ScriptRule rule, TriggerContext ctx,
                          @Nullable java.util.function.Consumer<List<TraceStep>> traceCallback) {
        RunState st = new RunState(wallId, rule, ctx, traceCallback);
        st.trace.add(TraceStep.ok("trigger", "trigger",
                ctx.source() + (ctx.detail() == null ? "" : " " + ctx.detail())));
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(rule.actions(), 0, "actions/"));
        runFrames(st, stack);
    }

    /**
     * 执行帧栈直到耗尽 / wait 续接 / 掐断。整段在 runner 线程上跑；
     * {@link #CHAIN_DEPTH} 全程置位，finally 必清。
     */
    private void runFrames(RunState st, Deque<Frame> stack) {
        CHAIN_DEPTH.set(st.ctx.chainDepth());
        RULE_KEY.set(ruleKey(st.wallId, st.rule));
        TRIGGER_DETAIL.set(st.ctx.detail());   // 触发玩家名（player* 触发器有值）→ sendMessage
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
                                "action count over limit " + budget.maxActionsPerRun() + "; remaining actions cut"));
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
                    if (a instanceof Action.RandomBranch rb) {
                        // 随机分支（控制流，照 if；rng 由 ActionExecutor.rng 注入 seam 持有，
                        // 但 ScriptRunner 不依赖 ActionExecutor——改由 runner 自持 rng seam）。
                        boolean pick = rng.getAsInt() < rb.probability();
                        st.trace.add(TraceStep.ok(blockId, "condition",
                                "random=" + rb.probability() + "% → " + (pick ? "then" : "else")));
                        stack.push(new Frame(acts, i + 1, f.prefix()));
                        List<Action> branch = pick ? rb.then() : rb.elseActions();
                        stack.push(new Frame(branch, 0,
                                blockId + (pick ? "/then/" : "/else/")));
                        continue outer;
                    }
                    if (a instanceof Action.Repeat rep) {
                        // 有界循环展开。repeat 自身计 1 个动作（上方 actionCount++ 已计）；
                        // 展开后的 body 动作各自再计入 —— 超 max-actions-per-run 自然熔断。
                        st.trace.add(TraceStep.ok(blockId, "action", "repeat " + rep.count() + "x"));
                        // 先押回当前帧剩余（含外层后续），再押 count 轮 body 帧。
                        stack.push(new Frame(acts, i + 1, f.prefix()));
                        // 关键（守前后端同构）：bodyPrefix 不含 round —— count 轮的 body[j] 的
                        // blockId 都是 <blockId>/body/<j>，与前端 body 子块 path 一致。各轮 body
                        // 帧完全相同（同 body / 同 prefix / index 0），LIFO 弹出顺序对可观察行为
                        // 无影响（相同副作用、相同 trace blockId 序列，仅重复 count 次）。
                        String bodyPrefix = blockId + "/body/";
                        for (int round = 0; round < rep.count(); round++) {
                            stack.push(new Frame(rep.body(), 0, bodyPrefix));
                        }
                        continue outer;
                    }
                    if (a instanceof Action.RepeatUntil ru) {
                        // 动态 while 循环——先查 condition（满足 / 达 maxIterations → 跳出），
                        // 否则轮数+1 + 压回自身（Frame(acts,i) 含其后续）+ 压一轮 body（LIFO body 先执行）。
                        // body 执行完弹回 Frame(acts,i) → 重新进入 RepeatUntil 再查 condition。轮数 Map 记、
                        // 跳出清。RepeatUntil 自身每轮进入计 1 actionCount（循环顶 ++）+ body 计入 → Budget 50
                        // 先于 maxIterations 100 兜底（防 condition 永 false 失控）。blockId 同构不带轮数。
                        int rounds = st.repeatUntilRounds.getOrDefault(blockId, 0);
                        boolean cond;
                        try {
                            cond = conditions.eval(ru.condition(), st.wallId);
                        } catch (RuntimeException e) {
                            cond = false; // 坏条件按未满足，靠 maxIterations 兜底
                        }
                        if (cond || rounds >= ru.maxIterations()) {
                            st.repeatUntilRounds.remove(blockId);
                            st.trace.add(TraceStep.ok(blockId, "action",
                                    cond ? "repeatUntil condition met" : "repeatUntil max iterations reached"));
                            i++; // 跳出循环，内层 while 继续后续 acts[i+1]
                            continue;
                        }
                        st.repeatUntilRounds.put(blockId, rounds + 1);
                        st.trace.add(TraceStep.ok(blockId, "action",
                                "repeatUntil round " + (rounds + 1)));
                        stack.push(new Frame(acts, i, f.prefix())); // 压回自身（i 不变，含后续）
                        stack.push(new Frame(ru.body(), 0, blockId + "/body/")); // body 先执行
                        continue outer;
                    }
                    if (a instanceof Action.StopScript) {
                        // 中止当前 run——清空帧栈，外层 while 见栈空退出 → finish(st,"ok")。
                        st.trace.add(TraceStep.ok(blockId, "action", "stopScript"));
                        stack.clear();
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
                    if (a instanceof Action.PlayTimelineAwait pta) {
                        // 先 play（副作用走 sink），再据 durationMs 决定是否挂起续接（复用 wait 范式）。
                        // play 失败 / 查无 timeline（dur==0）→ 不挂起，紧接下一动作。
                        TraceStep step;
                        try {
                            step = sink.execute(st.wallId, blockId, pta);
                        } catch (RuntimeException e) {
                            log.log(Level.WARNING, "[script] playTimelineAwait execution error (chain continues): rule="
                                    + ruleKey(st.wallId, st.rule) + " block=" + blockId
                                    + " err=" + e.getMessage(), e);
                            step = TraceStep.error(blockId, String.valueOf(e.getMessage()));
                        }
                        if (step == null) step = TraceStep.error(blockId, "ActionSink returned null step");
                        st.trace.add(step);
                        long dur = "ok".equals(step.result())
                                ? sink.timelineDurationMs(st.wallId, pta.timelineId()) : 0L;
                        if (dur > 0) {
                            stack.push(new Frame(acts, i + 1, f.prefix()));
                            Deque<Frame> cont = new ArrayDeque<>(stack);
                            if (!shutdown) {
                                try {
                                    scheduler.schedule(() -> runFrames(st, cont), dur);
                                } catch (RejectedExecutionException e) {
                                    // shutdown 竞态：续接丢弃
                                }
                            }
                            return;
                        }
                        i++; // 不挂起：继续下一动作
                        continue;
                    }
                    if (a instanceof Action.TweenBlock tb) {
                        // 补间引擎接入（TweenScheduler）。顺序承诺（scripting-tween.md §2.2）：
                        // 续接<b>不再</b>由本 runner 自按 durationMs 定时——改由 TweenScheduler 在
                        // 「末帧落盘完成后」回调触发（否则续接早于落盘，后续动作读到补间前旧值）。
                        TweenScheduler ts = tweenScheduler;
                        if (ts == null) {
                            // 未装配（测试 / 降级）：记 trace 跳过 body，不崩 runner
                            st.trace.add(TraceStep.ok(blockId, "action", "tween engine not wired, skipping"));
                            i++; continue;
                        }
                        // 先打包 continuation 快照（含剩余动作 acts[i+1..] + 外层后续）——不污染 live
                        // stack：push→拷贝→pop，使「不挂起」分支的 stack 保持原样。
                        stack.push(new Frame(acts, i + 1, f.prefix()));
                        Deque<Frame> cont = new ArrayDeque<>(stack);
                        stack.pop();
                        // 续接回调：被 TweenScheduler 在末帧落盘后 / 异常 / 接管时（tween 或 runner 线程）
                        // 调用——内部只把 runFrames 续接 schedule(0) 到 <b>runner SES</b>（复用
                        // playTimelineAwait/wait/pollWaitUntil 的 schedule 续接范式），脚本续接<b>始终</b>
                        // 在 runner 线程跑，绝不在 tween 线程。TweenScheduler 已对回调做一次性 + 抛吞兜底。
                        Runnable continuation = () -> {
                            if (shutdown) return;
                            try {
                                scheduler.schedule(() -> runFrames(st, cont), 0L);
                            } catch (RejectedExecutionException e) {
                                // shutdown 竞态：续接丢弃（session 已死，不补跑）
                            }
                        };
                        TraceStep step = ts.enqueue(st.wallId, blockId, tb, continuation);
                        st.trace.add(step);
                        if ("ok".equals(step.result()) && tb.durationMs() > 0) {
                            // 挂起：等 TweenScheduler 末帧落盘后经 continuation 续接（此处不另行 schedule）。
                            return;
                        }
                        // enqueue 失败 / duration=0 → 不挂起，链继续（continuation 不会被 TweenScheduler
                        // 触发：失败 / 0 时长不注册会回调的 task）。
                        i++; continue;
                    }
                    if (a instanceof Action.WaitUntil wu) {
                        // 轮询条件，满足/超时才续接。不阻塞 runner 线程——首次计 1 个 action
                        // （上方 actionCount++ 已计）+ 压栈后续（i+1）+ 启动独立 pollWaitUntil 递归。
                        // 轮询走独立调度、不重入 action 循环 → 不重复计 actionCount、不被 Budget 误拦。
                        st.trace.add(TraceStep.ok(blockId, "action", "waitUntil start"));
                        long deadline = clock.getAsLong() + wu.timeoutMs();
                        stack.push(new Frame(acts, i + 1, f.prefix()));
                        Deque<Frame> cont = new ArrayDeque<>(stack);
                        pollWaitUntil(st, wu, deadline, cont, blockId);
                        return;
                    }
                    // 普通动作 → ActionSink（实现侧三层隔离；此处兜底防御）
                    TraceStep step;
                    try {
                        step = sink.execute(st.wallId, blockId, a);
                    } catch (RuntimeException e) {
                        log.log(Level.WARNING, "[script] action execution error (chain continues): rule="
                                + ruleKey(st.wallId, st.rule) + " block=" + blockId
                                + " err=" + e.getMessage(), e);
                        step = TraceStep.error(blockId, String.valueOf(e.getMessage()));
                    }
                    st.trace.add(step == null
                            ? TraceStep.error(blockId, "ActionSink returned null step") : step);
                    i++;
                }
            }
            finish(st, "ok");
        } catch (Throwable t) {
            // 任务级异常隔离：单 run 失败不杀 runner 线程（照 AnimationTicker.tick 范式）
            log.log(Level.WARNING, "[script] run failed: rule=" + ruleKey(st.wallId, st.rule)
                    + " err=" + t.getMessage(), t);
            // 异常掐断也要恰一次回调（finish 未到达——callbackFired 防 finish 后
            // 续接段异常的双发竞态）
            st.trace.add(TraceStep.error("run", "run failed: " + t.getMessage()));
            fireTraceOnce(st);
        } finally {
            CHAIN_DEPTH.remove();
            RULE_KEY.remove();
            TRIGGER_DETAIL.remove();
        }
    }

    /**
     * WaitUntil 轮询——每 tick 评估 condition，满足 / 超时 → 续接后续动作（cont），否则
     * tick 后再评估。走<b>独立调度</b>，不重入 runFrames 的 action 循环，故轮询不重复计 actionCount、
     * 不被 Budget 误拦。shutdown / 调度拒绝 → 安静放弃（同 wait 续接容错）。
     */
    private void pollWaitUntil(RunState st, Action.WaitUntil wu, long deadline,
                               Deque<Frame> cont, String blockId) {
        if (shutdown) return;
        try {
            boolean satisfied;
            try {
                satisfied = conditions.eval(wu.condition(), st.wallId);
            } catch (RuntimeException e) {
                satisfied = false; // 评估异常按未满足，靠超时兜底（坏条件不卡死链）
            }
            boolean timedOut = clock.getAsLong() >= deadline;
            if (satisfied || timedOut) {
                st.trace.add(TraceStep.ok(blockId, "action",
                        satisfied ? "waitUntil satisfied" : "waitUntil timed out"));
                try {
                    scheduler.schedule(() -> runFrames(st, cont), 0L); // 续接后续动作
                } catch (RejectedExecutionException ignored) {
                    // shutdown 竞态：续接丢弃
                }
                return;
            }
            try {
                scheduler.schedule(() -> pollWaitUntil(st, wu, deadline, cont, blockId), WAIT_UNTIL_TICK_MS);
            } catch (RejectedExecutionException ignored) {
                // shutdown 竞态：轮询停止
            }
        } catch (Throwable t) {
            // poll 走独立调度（不在 runFrames 的 try 内）——兜底防 Throwable 孤儿化 run：记 error +
            // callback 恰一次（照 runFrames catch 范式），与 WaitUntil 的"恰一次"契约对齐。
            log.log(Level.WARNING, "[script] waitUntil poll failed: rule=" + ruleKey(st.wallId, st.rule)
                    + " block=" + blockId + " err=" + t.getMessage(), t);
            st.trace.add(TraceStep.error(blockId, "waitUntil poll failed: " + t.getMessage()));
            fireTraceOnce(st);
        } finally {
            // pollWaitUntil 走独立调度、不在 runFrames 的 try-finally 内——自清这三个 ThreadLocal，
            // 防异常/inline 首调路径把 CHAIN_DEPTH/RULE_KEY/TRIGGER_DETAIL 泄漏到 runner 线程后续任务。
            // 续接 runFrames（上方 schedule）会在自身入口重设这三个值，故此处清理不影响续接。
            CHAIN_DEPTH.remove();
            RULE_KEY.remove();
            TRIGGER_DETAIL.remove();
        }
    }

    /** run 结束（正常 / blocked）：trace callback 恰一次 + FINE log 一行 summary。 */
    private void finish(RunState st, String outcome) {
        fireTraceOnce(st);
        if (!log.isLoggable(Level.FINE)) return;
        StringBuilder sb = new StringBuilder(128);
        sb.append("[script trace] rule=").append(ruleKey(st.wallId, st.rule))
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

    /** RunState 级 callback 恰一次触发（trace 防御性快照拷贝；callback 抛被吞）。 */
    private void fireTraceOnce(RunState st) {
        if (st.callbackFired) return;
        st.callbackFired = true;
        fireTrace(st.traceCallback, List.copyOf(st.trace));
    }

    /** callback 调用统一兜底（callback 抛异常不杀 runner / 不影响投递方）。 */
    private void fireTrace(@Nullable java.util.function.Consumer<List<TraceStep>> cb,
                           List<TraceStep> steps) {
        if (cb == null) return;
        try {
            cb.accept(steps);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "[script] trace callback threw exception (swallowed): " + e.getMessage(), e);
        }
    }

    /** audit {@code SCRIPT_RUN_BLOCKED}（per-rule 10s 限频；DB 失败由 AuditLog 自吞）。 */
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
            log.log(Level.WARNING, "[script] SCRIPT_RUN_BLOCKED audit failed: " + e.getMessage(), e);
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
