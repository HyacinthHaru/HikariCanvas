package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.script.ScriptRule;
import moe.hikari.canvas.script.ScriptStore;
import moe.hikari.canvas.script.Trigger;
import moe.hikari.canvas.variable.VariableStore;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 脚本触发器路由（0.7.0-P2 T5；{@code docs/scripting.md §3}）。3 触发器落地：
 *
 * <ul>
 *   <li><b>variableChange</b>：倒排索引 {@code 解析后 fullName → Set<RuleRef>}
 *       （照 {@link moe.hikari.canvas.render.TimelineTriggerRegistry} 范式）。
 *       {@link #onVariableChange} 由 {@code VariableStore.fireChange} 同步回调，
 *       只在"值真的变了"（VALUE_SET / UPDATED / CREATED）时 O(1) 查表投递。</li>
 *   <li><b>timer</b>：自持单线程 daemon 调度器（{@code hikari-script-trigger}）
 *       {@code scheduleAtFixedRate(interval, interval)}——首次不立即，周期到才跑；
 *       到期时 {@code store.find} 拿<b>最新</b>规则（没了 / 禁用 / 换型 → cancel 自己），
 *       投递后执行在 runner 线程。调度本身轻（仅 submit），不复用 runner 的 SES——
 *       runner 单线程串行执行重活，timer 到期不该排在 wait 续接后面才投递。</li>
 *   <li><b>wallReady</b>：不进索引——{@link #fireWallReady} 时直查 {@code store.listByWall}
 *       （K9 两触发点：启动恢复成功墙 + {@code SessionManager.confirm} 部署完成）。</li>
 *   <li><b>playerJoin / playerKill</b>（0.7.0-P3 B1 / K15）：全局触发索引
 *       {@code Set<RuleRef>} 两枚——事件不带墙语义，到达时遍历全服该型规则
 *       （规则量受 per-wall 配额约束，O(全服规则) 可接受）。事件入口集中在
 *       {@link GameEventListenerHub}（主线程 MONITOR），本类 fire 方法只做
 *       store.find 最新 + enabled 判定 + submit。</li>
 * </ul>
 *
 * <p><b>索引只存引用（防 stale rule）</b>：{@code RuleRef = (wallId, ruleId)}，触发时刻
 * 始终 {@code store.find} 拿最新规则——规则被更新 / 禁用 / 删除后旧索引条目即便残留
 * （rebuild 竞态窗口）也不会执行过期动作。</p>
 *
 * <p><b>ABA 链深（K1）</b>：脚本动作 setVariable 的 fireChange 同步发生在 runner 线程
 * （{@link ScriptRunner#CHAIN_DEPTH} 已置位）→ 本类读 ThreadLocal：非 null → depth+1
 * （脚本引发的下游链）；null → depth 0（玩家 / Provider / 命令等非脚本来源）。
 * 注意不能用 {@code currentChainDepth()+1}——它把"无上下文"折叠成 0，非脚本来源会被
 * 错算成 depth 1。</p>
 *
 * <p><b>不做 debounce</b>（与 TimelineTriggerRegistry 的 200ms 去抖不同）：脚本触发的
 * 节流闸是 {@link ScriptBudget} per-rule runs/s（默 10/s）——高频变量连环触发被 budget
 * 丢尾，无需第二层去抖；时间轴去抖是为防"重播 thrash"（play 重置进度），脚本 run 无此语义。</p>
 *
 * <p><b>线程安全</b>：索引 ConcurrentHashMap + 不可变快照源（store.listByWall）；
 * {@code rebuild* / removeWall / shutdown} 方法级 synchronized 串行化结构变更
 * （调用频率低：编辑保存 / 启动 / 删墙）；{@link #onVariableChange} / {@link #fireWallReady}
 * 无锁读，rebuild 中途的瞬时 miss 可接受（与 TimelineTriggerRegistry 同纪律）。</p>
 */
public final class TriggerRouter {

    /** rawName + wallId → 内部 fullName seam（生产 = {@code VariableInterpolator::resolveFullName}）。 */
    @FunctionalInterface
    public interface FullNameResolver {
        String resolve(String rawName, String wallId);
    }

    /**
     * 投递 seam（0.7.0-P3 B1）：生产 = {@code ScriptRunner::submit}；测试注记录替身
     * 可直接断言 TriggerContext 形态（source / chainDepth / detail）。
     */
    @FunctionalInterface
    interface RunSubmitter {
        void submit(String wallId, ScriptRule rule, TriggerContext ctx);
    }

    /**
     * timer 调度 seam：生产 = 自持单线程 daemon SES（{@code hikari-script-trigger}）；
     * 测试注手动 tick 替身。
     */
    interface TimerScheduler {
        ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelayMs, long periodMs);

        /** 幂等优雅关停（生产实现 awaitTermination 5s 后 shutdownNow）。 */
        void shutdown();
    }

    /** 索引只存引用——执行时刻 {@code store.find} 拿最新规则，防 stale rule 执行。 */
    private record RuleRef(String wallId, String ruleId) {}

    private final ScriptStore store;
    private final RunSubmitter runner;
    private final FullNameResolver resolver;
    private final Logger log;
    private final TimerScheduler timers;
    private volatile boolean shutdown;

    /** 解析后 fullName → 绑定集合（variableChange 倒排表）。 */
    private final Map<String, Set<RuleRef>> byFullName = new ConcurrentHashMap<>();
    /** wallId → 它贡献的解析后 fullName 集合（增量清理用，照 TimelineTriggerRegistry.wallKeys）。 */
    private final Map<String, Set<String>> wallKeys = new ConcurrentHashMap<>();
    /** {@code wallId:ruleId} → 该 timer 规则的周期任务。 */
    private final Map<String, ScheduledFuture<?>> timerTasks = new ConcurrentHashMap<>();
    /**
     * 0.7.0-P3 B1（K15）：playerJoin 全局触发索引。规则量受 per-wall 配额约束，
     * 事件到达时 O(全服 join 规则) 遍历可接受；与 byFullName 同纪律——只存引用，
     * fire 时刻 {@code store.find} 拿最新规则。
     */
    private final Set<RuleRef> joinRules = ConcurrentHashMap.newKeySet();
    /** 0.7.0-P3 B1（K15）：playerKill 全局触发索引（同 {@link #joinRules} 纪律）。 */
    private final Set<RuleRef> killRules = ConcurrentHashMap.newKeySet();

    /** 生产装配：自建单线程 daemon 调度器（线程名 {@code hikari-script-trigger}）。 */
    public TriggerRouter(ScriptStore store, ScriptRunner runner,
                         FullNameResolver resolver, Logger log) {
        this(store, (RunSubmitter) runner::submit, resolver, log, new SesTimerScheduler());
    }

    /** 测试装配：注入 timer 调度替身（投递走真 runner）。 */
    TriggerRouter(ScriptStore store, ScriptRunner runner,
                  FullNameResolver resolver, Logger log, TimerScheduler timers) {
        this(store, (RunSubmitter) runner::submit, resolver, log, timers);
    }

    /** 测试装配：注入投递替身（B1，ctx 形态可断言）+ timer 调度替身。 */
    TriggerRouter(ScriptStore store, RunSubmitter runner,
                  FullNameResolver resolver, Logger log, TimerScheduler timers) {
        this.store = store;
        this.runner = runner;
        this.resolver = resolver;
        this.log = log;
        this.timers = timers;
    }

    // ──────────────────────────────────────────────────────────
    //  索引重建（K7：ScriptStore.Listener 接入，墙级粒度）
    // ──────────────────────────────────────────────────────────

    /**
     * 重建某墙的触发登记：清掉该墙在倒排表的旧贡献 + cancel 该墙全部旧 timer，
     * 再按 store 当前 enabled 规则重登。{@code ScriptStore.addListener} 的回调入口
     * （任何 create / update / delete / setEnabled / clearWall 后整墙重建）。
     */
    public synchronized void rebuildWall(String wallId) {
        if (wallId == null) return;
        clearWallLocked(wallId);
        if (shutdown) return;
        for (ScriptRule r : store.listByWall(wallId)) {
            if (!r.enabled()) continue;       // disabled 规则不进索引、不调度
            registerRuleLocked(wallId, r);
        }
    }

    /** 全量重建（启动期，restore + autoRegisterAll 之后调一次）。 */
    public synchronized void rebuildAll() {
        cancelAllTimersLocked();
        byFullName.clear();
        wallKeys.clear();
        joinRules.clear();
        killRules.clear();
        if (shutdown) return;
        int n = 0;
        for (Map.Entry<String, java.util.List<ScriptRule>> e : store.snapshotAll().entrySet()) {
            for (ScriptRule r : e.getValue()) {
                if (!r.enabled()) continue;
                registerRuleLocked(e.getKey(), r);
                n++;
            }
        }
        if (n > 0) log.info("TriggerRouter: " + n + " 条 enabled 脚本规则已登记");
    }

    /** 注销某墙的全部触发登记（wall delete hook）。 */
    public synchronized void removeWall(String wallId) {
        if (wallId == null) return;
        clearWallLocked(wallId);
    }

    /** 幂等关停（onDisable）：cancel 全部 timer + 关调度器；之后所有入口 no-op。 */
    public synchronized void shutdown() {
        if (shutdown) return;
        shutdown = true;
        cancelAllTimersLocked();
        byFullName.clear();
        wallKeys.clear();
        joinRules.clear();
        killRules.clear();
        timers.shutdown();
    }

    // ──────────────────────────────────────────────────────────
    //  触发入口
    // ──────────────────────────────────────────────────────────

    /**
     * 变量变化 → 投递匹配规则（{@code VariableStore.registerChangeListener} 接入）。
     * 只认 VALUE_SET / UPDATED / CREATED（值真的变了）；BOUND / DELETED /
     * WALL_REFS_UPDATED 不触发。链深读 {@link ScriptRunner#CHAIN_DEPTH}（K1，见类注释）。
     */
    public void onVariableChange(VariableStore.VariableChangeEvent event) {
        if (shutdown || event == null) return;
        VariableStore.ChangeType type = event.type();
        if (type != VariableStore.ChangeType.VALUE_SET
                && type != VariableStore.ChangeType.UPDATED
                && type != VariableStore.ChangeType.CREATED) {
            return;
        }
        Set<RuleRef> refs = byFullName.get(event.fullName());
        if (refs == null || refs.isEmpty()) return;
        // K1：runner 线程脚本写变量 → 同步 fireChange 走到这里，链深延续 +1；
        // 非脚本来源（ThreadLocal null）→ depth 0。
        Integer running = ScriptRunner.CHAIN_DEPTH.get();
        int depth = running == null ? 0 : running + 1;
        for (RuleRef ref : refs) {
            ScriptRule latest = store.find(ref.wallId(), ref.ruleId()).orElse(null);
            if (latest == null || !latest.enabled()) continue;  // 索引残留 / 已禁用 → 跳过
            runner.submit(ref.wallId(), latest,
                    new TriggerContext(TriggerContext.Source.VARIABLE, depth, event.fullName()));
        }
    }

    /**
     * 墙就绪（K9：启动恢复成功 / 部署完成）→ 投递该墙 enabled 的 wallReady 规则。
     * 不走索引，直查 store 快照（触发频率极低）。
     */
    public void fireWallReady(String wallId) {
        if (shutdown || wallId == null) return;
        for (ScriptRule r : store.listByWall(wallId)) {
            if (!r.enabled() || !(r.trigger() instanceof Trigger.WallReady)) continue;
            runner.submit(wallId, r,
                    new TriggerContext(TriggerContext.Source.WALL_READY, 0, "wallReady"));
        }
    }

    /** K9①：启动期对全部恢复成功的墙各发一次 wallReady。 */
    public void fireWallReadyAll(Collection<String> wallIds) {
        if (wallIds == null) return;
        for (String wallId : wallIds) {
            fireWallReady(wallId);
        }
    }

    /**
     * 玩家进服（B1/K15；{@code GameEventListenerHub.onPlayerJoin} 接入，主线程调）→
     * 遍历全局 join 索引投递。事件来源非脚本，链深恒 0；detail = 玩家名（K17：
     * 只进 trace / audit，不注入动作参数）。
     */
    public void firePlayerJoin(String playerName) {
        fireGlobal(joinRules, Trigger.PlayerJoin.class,
                TriggerContext.Source.PLAYER_JOIN, playerName);
    }

    /**
     * 玩家被击杀（B1/K15；{@code GameEventListenerHub.onPlayerDeath}，killer 非 null
     * 才到这里）→ 遍历全局 kill 索引投递。detail = {@code victim→killer}。
     */
    public void firePlayerKill(String victimName, String killerName) {
        fireGlobal(killRules, Trigger.PlayerKill.class,
                TriggerContext.Source.PLAYER_KILL, victimName + "→" + killerName);
    }

    /**
     * 全局触发公共路径：遍历索引 → {@code store.find} 最新规则（防 stale）→
     * enabled 且触发器仍是期望类型才投递（rebuild 竞态窗口里换型规则不误触发）。
     */
    private void fireGlobal(Set<RuleRef> refs, Class<? extends Trigger> expectedType,
                            TriggerContext.Source source, String detail) {
        if (shutdown || refs.isEmpty()) return;
        for (RuleRef ref : refs) {
            ScriptRule latest = store.find(ref.wallId(), ref.ruleId()).orElse(null);
            if (latest == null || !latest.enabled()
                    || !expectedType.isInstance(latest.trigger())) {
                continue;   // 索引残留 / 已禁用 / 已换型 → 跳过
            }
            runner.submit(ref.wallId(), latest, new TriggerContext(source, 0, detail));
        }
    }

    // ──────────────────────────────────────────────────────────
    //  内部（*Locked 方法仅在 synchronized 内调）
    // ──────────────────────────────────────────────────────────

    private void registerRuleLocked(String wallId, ScriptRule rule) {
        Trigger t = rule.trigger();
        if (t instanceof Trigger.VariableChange vc) {
            String resolved;
            try {
                resolved = resolver.resolve(vc.fullName(), wallId);
            } catch (RuntimeException e) {
                log.warning("TriggerRouter: fullName 解析失败 wall=" + wallId
                        + " rule=" + rule.id() + " raw=" + vc.fullName()
                        + " err=" + e.getMessage());
                return;
            }
            if (resolved == null || resolved.isBlank()) return;
            byFullName.computeIfAbsent(resolved, k -> ConcurrentHashMap.newKeySet())
                    .add(new RuleRef(wallId, rule.id()));
            wallKeys.computeIfAbsent(wallId, k -> ConcurrentHashMap.newKeySet()).add(resolved);
        } else if (t instanceof Trigger.Timer timer) {
            scheduleTimerLocked(wallId, rule.id(), timer.intervalSeconds());
        } else if (t instanceof Trigger.PlayerJoin) {
            joinRules.add(new RuleRef(wallId, rule.id()));
        } else if (t instanceof Trigger.PlayerKill) {
            killRules.add(new RuleRef(wallId, rule.id()));
        }
        // playerNear：0.7.0-P3 B2（PlayerNearSampler）；
        // wallReady 不进索引（fireWallReady 直查 store）。
    }

    private void scheduleTimerLocked(String wallId, String ruleId, int intervalSeconds) {
        String key = timerKey(wallId, ruleId);
        long periodMs = Math.max(1, intervalSeconds) * 1000L;
        // self 引用：到期发现规则没了 / 禁用 / 换型时 cancel 自己（remove(key, mine) 条件移除——
        // rebuild 已换上新 future 时不误删新条目）。
        AtomicReference<ScheduledFuture<?>> self = new AtomicReference<>();
        Runnable task = () -> onTimerFire(wallId, ruleId, key, self);
        ScheduledFuture<?> future;
        try {
            // 首次不立即：initialDelay = period（周期到才第一次跑）
            future = timers.scheduleAtFixedRate(task, periodMs, periodMs);
        } catch (RejectedExecutionException e) {
            // shutdown 竞态：静默丢弃
            return;
        }
        self.set(future);
        ScheduledFuture<?> old = timerTasks.put(key, future);
        if (old != null) old.cancel(false);   // 防御：clearWallLocked 已 cancel，残留兜底
    }

    /** timer 到期（调度线程）。异常必须自吞——scheduleAtFixedRate 任务抛异常会终止周期。 */
    private void onTimerFire(String wallId, String ruleId, String key,
                             AtomicReference<ScheduledFuture<?>> self) {
        try {
            if (shutdown) return;
            ScriptRule latest = store.find(wallId, ruleId).orElse(null);
            if (latest == null || !latest.enabled()
                    || !(latest.trigger() instanceof Trigger.Timer)) {
                // 规则没了 / 禁用 / 触发器换型 → cancel 自己（正常由 rebuild cancel，这是兜底）
                ScheduledFuture<?> mine = self.get();
                if (mine != null) {
                    timerTasks.remove(key, mine);
                    mine.cancel(false);
                }
                return;
            }
            runner.submit(wallId, latest,
                    new TriggerContext(TriggerContext.Source.TIMER, 0, "timer"));
        } catch (Throwable t) {
            log.log(Level.WARNING, "TriggerRouter: timer 触发失败 rule=" + key
                    + " err=" + t.getMessage(), t);
        }
    }

    private void clearWallLocked(String wallId) {
        joinRules.removeIf(ref -> ref.wallId().equals(wallId));
        killRules.removeIf(ref -> ref.wallId().equals(wallId));
        Set<String> keys = wallKeys.remove(wallId);
        if (keys != null) {
            for (String fn : keys) {
                Set<RuleRef> set = byFullName.get(fn);
                if (set == null) continue;
                set.removeIf(ref -> ref.wallId().equals(wallId));
                if (set.isEmpty()) byFullName.remove(fn);
            }
        }
        String prefix = wallId + ":";
        Iterator<Map.Entry<String, ScheduledFuture<?>>> it = timerTasks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ScheduledFuture<?>> e = it.next();
            if (e.getKey().startsWith(prefix)) {
                e.getValue().cancel(false);
                it.remove();
            }
        }
    }

    private void cancelAllTimersLocked() {
        for (ScheduledFuture<?> f : timerTasks.values()) {
            f.cancel(false);
        }
        timerTasks.clear();
    }

    private static String timerKey(String wallId, String ruleId) {
        return wallId + ":" + ruleId;
    }

    // ---------- 测试观察（包级） ----------

    /** 某解析后 fullName 当前绑定数。 */
    int bindingCount(String fullName) {
        Set<RuleRef> set = byFullName.get(fullName);
        return set == null ? 0 : set.size();
    }

    /** 某 timer 规则当前是否有调度条目。 */
    boolean hasTimer(String wallId, String ruleId) {
        return timerTasks.containsKey(timerKey(wallId, ruleId));
    }

    /** 全局 join 索引当前条目数（B1）。 */
    int joinRuleCount() {
        return joinRules.size();
    }

    /** 全局 kill 索引当前条目数（B1）。 */
    int killRuleCount() {
        return killRules.size();
    }

    /** 生产调度器：单线程 daemon SES（照 {@code ScriptRunner.SesScheduler} 关停纪律）。 */
    private static final class SesTimerScheduler implements TimerScheduler {
        private final ScheduledExecutorService ses =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "hikari-script-trigger");
                    t.setDaemon(true);
                    return t;
                });

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task,
                                                      long initialDelayMs, long periodMs) {
            return ses.scheduleAtFixedRate(task, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
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
