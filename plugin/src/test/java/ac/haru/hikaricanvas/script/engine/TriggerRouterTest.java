package ac.haru.hikaricanvas.script.engine;

import ac.haru.hikaricanvas.HikariCanvasConfig;
import ac.haru.hikaricanvas.script.Action;
import ac.haru.hikaricanvas.script.ScriptRule;
import ac.haru.hikaricanvas.script.ScriptStore;
import ac.haru.hikaricanvas.script.Trigger;
import ac.haru.hikaricanvas.variable.VariableInterpolator;
import ac.haru.hikaricanvas.variable.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0-P2-5：{@link TriggerRouter} 单元测试（索引 / timer 调度 / 链深读取 / 关停）。
 * 全 fake：内存 ScriptStore（dao null）+ 真 ScriptRunner（同步替身调度）+ 记录型 sink +
 * 手动 tick timer 替身。全链贯通（含真 VariableStore 事件）在 {@link EndToEndScriptTest}。
 */
class TriggerRouterTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final String WALL = "w-rt";
    /** B2：fake 墙原点所在世界（near 测试共用）。 */
    private static final java.util.UUID WORLD_ID =
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** B2：fake 原点源——所有墙原点固定 (0,0,0)。 */
    private static final TriggerRouter.WallOriginSource ORIGIN_AT_ZERO =
            wallId -> new TriggerRouter.WallOrigin(WORLD_ID, 0, 0, 0);

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private ScriptStore scriptStore;
    private RecordingSink sink;
    private ScriptRunner runner;
    private FakeTimerScheduler fakeTimers;
    private TriggerRouter router;

    @BeforeEach
    void setUp() {
        scriptStore = new ScriptStore(LOG, null, 16);
        sink = new RecordingSink();
        ScriptBudget budget = new ScriptBudget(
                HikariCanvasConfig.ScriptsConfig.defaults(), clock::get);
        ConditionEvaluator conditions = new ConditionEvaluator(LOG,
                (java.util.function.Function<String, String>) null);
        runner = new ScriptRunner(conditions, sink, budget, null, LOG, new InlineScheduler());
        fakeTimers = new FakeTimerScheduler();
        router = new TriggerRouter(scriptStore, runner,
                VariableInterpolator::resolveFullName, ORIGIN_AT_ZERO, LOG, fakeTimers);
    }

    private ScriptRule addRule(String wallId, boolean enabled, Trigger trigger) {
        return scriptStore.create(wallId, new ScriptRule(null, null, enabled,
                "r", trigger, List.of(new Action.Log("x")), "{}"));
    }

    private static VariableStore.VariableChangeEvent event(String fullName,
                                                           VariableStore.ChangeType type) {
        return new VariableStore.VariableChangeEvent(fullName, null, type, Set.of());
    }

    // ---------- 重建期的单条隔离 ----------

    /**
     * 一条规则登记失败不该把整墙的索引重建带崩。
     *
     * <p>rebuild 是"先清空该墙的旧索引、再逐条重登"。中间某条抛出去的话，旧索引已经清了、
     * 新索引只登了一半，该墙的触发就静默失效到下一次 rebuild（外层 ScriptStore.notifyWall
     * 有 catch，所以连报错都看不到）。生产里的抛点很实在：near 类触发器要
     * {@code originSource.load} 读库 + 查世界，DB 抖动 / 世界卸载都可能抛。</p>
     */
    @Test
    void ruleRegistrationFailure_doesNotAbortWholeWallRebuild() {
        TriggerRouter.WallOriginSource throwingOrigin = wallId -> {
            throw new IllegalStateException("world not loaded");
        };
        TriggerRouter r2 = new TriggerRouter(scriptStore, runner,
                VariableInterpolator::resolveFullName, throwingOrigin, LOG,
                new FakeTimerScheduler());
        addRule(WALL, true, new Trigger.PlayerNear(8));                    // 这条会抛
        addRule(WALL, true, new Trigger.VariableChange("user/score"));     // 这条必须照常登记

        assertDoesNotThrow(() -> r2.rebuildWall(WALL));

        r2.onVariableChange(event("user:" + WALL + "/score",
                VariableStore.ChangeType.VALUE_SET));
        assertEquals(1, sink.blockIds.size(),
                "坏规则不该连累同墙其他规则的登记");
    }

    /** 全量重建同款：一条坏规则不影响其他墙。 */
    @Test
    void ruleRegistrationFailure_doesNotAbortRebuildAll() {
        TriggerRouter.WallOriginSource throwingOrigin = wallId -> {
            throw new IllegalStateException("db down");
        };
        TriggerRouter r2 = new TriggerRouter(scriptStore, runner,
                VariableInterpolator::resolveFullName, throwingOrigin, LOG,
                new FakeTimerScheduler());
        addRule("w-bad", true, new Trigger.PlayerNear(8));
        addRule("w-good", true, new Trigger.VariableChange("user/score"));

        assertDoesNotThrow(r2::rebuildAll);

        r2.onVariableChange(event("user:w-good/score",
                VariableStore.ChangeType.VALUE_SET));
        assertEquals(1, sink.blockIds.size());
    }

    // ---------- variableChange 索引 ----------

    @Test
    void variableChange_indexedWithWallInjection_andFires() {
        addRule(WALL, true, new Trigger.VariableChange("user/score"));
        router.rebuildWall(WALL);
        // user/score 经 resolveFullName 注入 wallId → user:w-rt/score
        assertEquals(1, router.bindingCount("user:" + WALL + "/score"));
        router.onVariableChange(event("user:" + WALL + "/score",
                VariableStore.ChangeType.VALUE_SET));
        assertEquals(1, sink.blockIds.size(), "VALUE_SET 命中索引 → 投递执行");
        router.onVariableChange(event("user:" + WALL + "/other",
                VariableStore.ChangeType.VALUE_SET));
        assertEquals(1, sink.blockIds.size(), "无关 fullName 不触发");
    }

    @Test
    void changeTypeFilter_onlyValueSetUpdatedCreated() {
        addRule(WALL, true, new Trigger.VariableChange("user/score"));
        router.rebuildWall(WALL);
        String fn = "user:" + WALL + "/score";
        router.onVariableChange(event(fn, VariableStore.ChangeType.DELETED));
        router.onVariableChange(event(fn, VariableStore.ChangeType.BOUND));
        router.onVariableChange(event(fn, VariableStore.ChangeType.WALL_REFS_UPDATED));
        assertEquals(0, sink.blockIds.size(), "DELETED / BOUND / WALL_REFS_UPDATED 不触发");
        router.onVariableChange(event(fn, VariableStore.ChangeType.CREATED));
        router.onVariableChange(event(fn, VariableStore.ChangeType.UPDATED));
        assertEquals(2, sink.blockIds.size(), "CREATED / UPDATED 触发");
    }

    @Test
    void disabledRule_notIndexed_notScheduled() {
        addRule(WALL, false, new Trigger.VariableChange("user/score"));
        ScriptRule timer = addRule(WALL, false, new Trigger.Timer(30));
        router.rebuildWall(WALL);
        assertEquals(0, router.bindingCount("user:" + WALL + "/score"));
        assertFalse(router.hasTimer(WALL, timer.id()));
        assertEquals(0, fakeTimers.entries.size());
    }

    @Test
    void staleIndexEntry_ruleFetchedFromStore_disabledSkipped() {
        ScriptRule r = addRule(WALL, true, new Trigger.VariableChange("user/score"));
        router.rebuildWall(WALL);
        // 索引未重建（不经 listener），store 内已禁用 → 触发时刻 find 最新 → 跳过
        scriptStore.setEnabled(WALL, r.id(), false);
        router.onVariableChange(event("user:" + WALL + "/score",
                VariableStore.ChangeType.VALUE_SET));
        assertEquals(0, sink.blockIds.size(), "stale 索引条目不执行已禁用规则");
    }

    // ---------- 链深读取（K1） ----------

    @Test
    void chainDepth_nullThreadLocal_isDepth0_runnerThreadIsPlus1() {
        addRule(WALL, true, new Trigger.VariableChange("user/score"));
        router.rebuildWall(WALL);
        String fn = "user:" + WALL + "/score";
        // 非脚本来源：CHAIN_DEPTH null → depth 0 → maxChainDepth(8) 内放行
        router.onVariableChange(event(fn, VariableStore.ChangeType.VALUE_SET));
        assertEquals(1, sink.blockIds.size());
        // 模拟 runner 线程执行态（CHAIN_DEPTH=7）→ depth 8 ≥ max(8) → 掐断
        ScriptRunner.CHAIN_DEPTH.set(7);
        try {
            router.onVariableChange(event(fn, VariableStore.ChangeType.VALUE_SET));
            assertEquals(1, sink.blockIds.size(), "depth 7+1=8 被链深闸掐断");
            ScriptRunner.CHAIN_DEPTH.set(6);
            router.onVariableChange(event(fn, VariableStore.ChangeType.VALUE_SET));
            assertEquals(2, sink.blockIds.size(), "depth 6+1=7 放行");
        } finally {
            ScriptRunner.CHAIN_DEPTH.remove();
        }
    }

    // ---------- timer ----------

    @Test
    void timer_scheduledAtFixedRate_firstFireAfterPeriod() {
        ScriptRule r = addRule(WALL, true, new Trigger.Timer(30));
        router.rebuildWall(WALL);
        assertTrue(router.hasTimer(WALL, r.id()));
        assertEquals(1, fakeTimers.entries.size());
        FakeTimerScheduler.Entry e = fakeTimers.entries.get(0);
        assertEquals(30_000L, e.initialDelayMs, "首次不立即（initialDelay = period）");
        assertEquals(30_000L, e.periodMs);
        assertEquals(0, sink.blockIds.size(), "调度 ≠ 执行");
        e.task.run();
        assertEquals(1, sink.blockIds.size(), "周期到 → store.find 最新规则 → 投递");
    }

    @Test
    void timer_ruleDeletedInStore_cancelsSelfOnFire() {
        ScriptRule r = addRule(WALL, true, new Trigger.Timer(30));
        router.rebuildWall(WALL);
        FakeTimerScheduler.Entry e = fakeTimers.entries.get(0);
        scriptStore.delete(WALL, r.id());   // 不经 listener rebuild（测兜底自清路径）
        assertDoesNotThrow(e.task::run);
        assertEquals(0, sink.blockIds.size(), "规则没了 → 不投递");
        assertTrue(e.future.cancelled, "到期发现规则没了 → cancel 自己");
        assertFalse(router.hasTimer(WALL, r.id()));
    }

    @Test
    void rebuildWall_cancelsOldTimer_reschedulesNew() {
        ScriptRule r = addRule(WALL, true, new Trigger.Timer(30));
        router.rebuildWall(WALL);
        FakeTimerScheduler.Entry old = fakeTimers.entries.get(0);
        scriptStore.update(r.id(), new ScriptRule(null, null, true, "r",
                new Trigger.Timer(60), List.of(new Action.Log("x")), "{}"));
        router.rebuildWall(WALL);
        assertTrue(old.future.cancelled, "旧周期任务被 cancel");
        assertEquals(2, fakeTimers.entries.size());
        assertEquals(60_000L, fakeTimers.entries.get(1).periodMs, "新间隔重新调度");
    }

    // ---------- rebuild / removeWall 清理 ----------

    @Test
    void rebuildWall_replacesOldIndexContribution() {
        ScriptRule r = addRule(WALL, true, new Trigger.VariableChange("user/a"));
        router.rebuildWall(WALL);
        assertEquals(1, router.bindingCount("user:" + WALL + "/a"));
        scriptStore.update(r.id(), new ScriptRule(null, null, true, "r",
                new Trigger.VariableChange("user/b"),
                List.of(new Action.Log("x")), "{}"));
        router.rebuildWall(WALL);
        assertEquals(0, router.bindingCount("user:" + WALL + "/a"), "旧 fullName 条目清掉");
        assertEquals(1, router.bindingCount("user:" + WALL + "/b"));
    }

    @Test
    void removeWall_clearsIndexAndCancelsTimers() {
        addRule(WALL, true, new Trigger.VariableChange("user/a"));
        ScriptRule timer = addRule(WALL, true, new Trigger.Timer(30));
        addRule("w-other", true, new Trigger.VariableChange("user/a"));
        router.rebuildWall(WALL);
        router.rebuildWall("w-other");
        router.removeWall(WALL);
        assertEquals(0, router.bindingCount("user:" + WALL + "/a"));
        assertEquals(1, router.bindingCount("user:w-other/a"), "别的墙不受影响");
        assertFalse(router.hasTimer(WALL, timer.id()));
        assertTrue(fakeTimers.entries.get(0).future.cancelled);
    }

    // ---------- playerJoin / playerKill 全局索引（0.7.0-P3 B1 / K15） ----------

    @Test
    void globalIndex_rebuildAddsAndRemoves() {
        ScriptRule join = addRule(WALL, true, new Trigger.PlayerJoin());
        addRule(WALL, true, new Trigger.PlayerKill());
        addRule("w-other", true, new Trigger.PlayerJoin());
        router.rebuildWall(WALL);
        router.rebuildWall("w-other");
        assertEquals(2, router.joinRuleCount());
        assertEquals(1, router.killRuleCount());
        // 规则换型 → rebuild 后 join 索引出、kill 索引进
        scriptStore.update(join.id(), new ScriptRule(null, null, true, "r",
                new Trigger.PlayerKill(), List.of(new Action.Log("x")), "{}"));
        router.rebuildWall(WALL);
        assertEquals(1, router.joinRuleCount(), "换型后旧 join 条目清掉");
        assertEquals(2, router.killRuleCount());
    }

    @Test
    void globalIndex_disabledRuleNotIndexed() {
        addRule(WALL, false, new Trigger.PlayerJoin());
        addRule(WALL, false, new Trigger.PlayerKill());
        router.rebuildWall(WALL);
        assertEquals(0, router.joinRuleCount());
        assertEquals(0, router.killRuleCount());
        router.firePlayerJoin("Steve");
        router.firePlayerKill("Alex", "Steve");
        assertEquals(0, sink.blockIds.size(), "disabled → 不索引不触发");
    }

    @Test
    void firePlayerJoin_submitsWithJoinContext() {
        RecordingSubmitter rec = new RecordingSubmitter();
        TriggerRouter r2 = new TriggerRouter(scriptStore, rec,
                VariableInterpolator::resolveFullName, ORIGIN_AT_ZERO, LOG,
                new FakeTimerScheduler());
        addRule(WALL, true, new Trigger.PlayerJoin());
        r2.rebuildWall(WALL);
        r2.firePlayerJoin("Steve");
        assertEquals(1, rec.contexts.size(), "join 规则被投递");
        assertEquals(WALL, rec.wallIds.get(0));
        assertEquals(TriggerContext.Source.PLAYER_JOIN, rec.contexts.get(0).source());
        assertEquals(0, rec.contexts.get(0).chainDepth(), "事件来源链深恒 0");
        assertEquals("Steve", rec.contexts.get(0).detail(), "detail = 玩家名");
    }

    @Test
    void firePlayerKill_detailIsVictimArrowKiller() {
        RecordingSubmitter rec = new RecordingSubmitter();
        TriggerRouter r2 = new TriggerRouter(scriptStore, rec,
                VariableInterpolator::resolveFullName, ORIGIN_AT_ZERO, LOG,
                new FakeTimerScheduler());
        addRule(WALL, true, new Trigger.PlayerKill());
        r2.rebuildWall(WALL);
        r2.firePlayerKill("Alex", "Steve");
        assertEquals(1, rec.contexts.size());
        assertEquals(TriggerContext.Source.PLAYER_KILL, rec.contexts.get(0).source());
        assertEquals(0, rec.contexts.get(0).chainDepth());
        assertEquals("Alex→Steve", rec.contexts.get(0).detail(), "detail = victim→killer");
        // 触发玩家单独给击杀者——sendMessage(target=trigger) 靠它找人，
        // 拿 detail 那个拼接串去 getPlayerExact 永远找不到人
        assertEquals("Steve", rec.contexts.get(0).triggerPlayer(), "triggerPlayer = 击杀者");
        // join 事件不触发 kill 规则
        r2.firePlayerJoin("Steve");
        assertEquals(1, rec.contexts.size());
    }

    /** 进服 / 退服 / 右键墙 / 靠近：触发玩家 = 事件里那名玩家。 */
    @Test
    void firePlayerJoin_carriesTriggerPlayer() {
        RecordingSubmitter rec = new RecordingSubmitter();
        TriggerRouter r2 = new TriggerRouter(scriptStore, rec,
                VariableInterpolator::resolveFullName, ORIGIN_AT_ZERO, LOG,
                new FakeTimerScheduler());
        addRule(WALL, true, new Trigger.PlayerJoin());
        r2.rebuildWall(WALL);
        r2.firePlayerJoin("Steve");
        assertEquals(1, rec.contexts.size());
        assertEquals("Steve", rec.contexts.get(0).triggerPlayer());
    }

    @Test
    void globalIndex_staleEntry_latestRuleWins() {
        ScriptRule r = addRule(WALL, true, new Trigger.PlayerJoin());
        router.rebuildWall(WALL);
        // 索引未重建（不经 listener）：store 内禁用 → fire 时刻 find 最新 → 跳过
        scriptStore.setEnabled(WALL, r.id(), false);
        router.firePlayerJoin("Steve");
        assertEquals(0, sink.blockIds.size(), "stale 索引条目不执行已禁用规则");
        // store 内换型（join → kill）但索引残留 join 条目 → join 事件不误触发
        scriptStore.setEnabled(WALL, r.id(), true);
        scriptStore.update(r.id(), new ScriptRule(null, null, true, "r",
                new Trigger.PlayerKill(), List.of(new Action.Log("x")), "{}"));
        router.firePlayerJoin("Steve");
        assertEquals(0, sink.blockIds.size(), "换型规则不被旧型事件误触发");
    }

    @Test
    void globalIndex_removeWallAndShutdownClear() {
        addRule(WALL, true, new Trigger.PlayerJoin());
        addRule(WALL, true, new Trigger.PlayerKill());
        addRule("w-other", true, new Trigger.PlayerJoin());
        router.rebuildWall(WALL);
        router.rebuildWall("w-other");
        router.removeWall(WALL);
        assertEquals(1, router.joinRuleCount(), "删墙只清本墙条目");
        assertEquals(0, router.killRuleCount());
        router.firePlayerJoin("Steve");
        assertEquals(1, sink.blockIds.size(), "别的墙照常触发");
        router.shutdown();
        assertEquals(0, router.joinRuleCount(), "关停清空全局索引");
        router.firePlayerJoin("Steve");
        assertEquals(1, sink.blockIds.size(), "关停后 fire no-op");
    }

    // ---------- 0.7.1：playerQuit 全局索引 ----------

    @Test
    void quitIndex_rebuildAddsAndRemoves() {
        ScriptRule quit = addRule(WALL, true, new Trigger.PlayerQuit());
        addRule("w-other", true, new Trigger.PlayerQuit());
        router.rebuildWall(WALL);
        router.rebuildWall("w-other");
        assertEquals(2, router.quitRuleCount());
        // 换型 quit → join → rebuild 后 quit 索引出
        scriptStore.update(quit.id(), new ScriptRule(null, null, true, "r",
                new Trigger.PlayerJoin(), List.of(new Action.Log("x")), "{}"));
        router.rebuildWall(WALL);
        assertEquals(1, router.quitRuleCount(), "换型后旧 quit 条目清掉");
    }

    @Test
    void firePlayerQuit_submitsWithQuitContext() {
        RecordingSubmitter rec = new RecordingSubmitter();
        TriggerRouter r2 = new TriggerRouter(scriptStore, rec,
                VariableInterpolator::resolveFullName, ORIGIN_AT_ZERO, LOG,
                new FakeTimerScheduler());
        addRule(WALL, true, new Trigger.PlayerQuit());
        r2.rebuildWall(WALL);
        r2.firePlayerQuit("Steve");
        assertEquals(1, rec.contexts.size(), "quit 规则被投递");
        assertEquals(WALL, rec.wallIds.get(0));
        assertEquals(TriggerContext.Source.PLAYER_QUIT, rec.contexts.get(0).source());
        assertEquals(0, rec.contexts.get(0).chainDepth(), "事件来源链深恒 0");
        assertEquals("Steve", rec.contexts.get(0).detail(), "detail = 玩家名");
    }

    @Test
    void quitIndex_disabledNotIndexed_removeWallAndShutdownClear() {
        addRule(WALL, false, new Trigger.PlayerQuit());
        router.rebuildWall(WALL);
        assertEquals(0, router.quitRuleCount(), "disabled 不索引");
        ScriptRule quit = addRule(WALL, true, new Trigger.PlayerQuit());
        router.rebuildWall(WALL);
        assertEquals(1, router.quitRuleCount());
        router.removeWall(WALL);
        assertEquals(0, router.quitRuleCount(), "删墙清本墙 quit 条目");
        router.rebuildWall(WALL);
        assertEquals(1, router.quitRuleCount());
        router.shutdown();
        assertEquals(0, router.quitRuleCount(), "关停清空 quit 索引");
        router.firePlayerQuit("Steve");
        assertEquals(0, sink.blockIds.size(), "关停后 fire no-op");
    }

    // ---------- 0.7.1：rightClickWall 按墙索引 ----------

    @Test
    void rightClickWall_perWallIndex_firesOnlyMatchingWall() {
        RecordingSubmitter rec = new RecordingSubmitter();
        TriggerRouter r2 = new TriggerRouter(scriptStore, rec,
                VariableInterpolator::resolveFullName, ORIGIN_AT_ZERO, LOG,
                new FakeTimerScheduler());
        addRule(WALL, true, new Trigger.RightClickWall());
        addRule("w-other", true, new Trigger.RightClickWall());
        r2.rebuildWall(WALL);
        r2.rebuildWall("w-other");
        assertEquals(1, r2.rightClickRuleCount(WALL));
        r2.fireRightClickWall(WALL, "Steve");
        assertEquals(1, rec.contexts.size(), "右键本墙触发");
        assertEquals(WALL, rec.wallIds.get(0));
        assertEquals(TriggerContext.Source.RIGHT_CLICK_WALL, rec.contexts.get(0).source());
        assertEquals("Steve", rec.contexts.get(0).detail());
        // 右键无登记规则的墙 → 不触发
        r2.fireRightClickWall("w-nobody", "Steve");
        assertEquals(1, rec.contexts.size(), "无规则墙右键不触发");
    }

    @Test
    void rightClickWall_staleDisabledAndShutdownSkipped() {
        ScriptRule rc = addRule(WALL, true, new Trigger.RightClickWall());
        router.rebuildWall(WALL);
        router.fireRightClickWall(WALL, "Steve");
        assertEquals(1, sink.blockIds.size());
        // store 内禁用（不经 listener rebuild）→ fire 时刻 find 最新 → 跳过
        scriptStore.setEnabled(WALL, rc.id(), false);
        router.fireRightClickWall(WALL, "Steve");
        assertEquals(1, sink.blockIds.size(), "stale 索引条目不执行已禁用规则");
        // 换型（rightClickWall → timer）→ 不被右键事件误触发
        scriptStore.setEnabled(WALL, rc.id(), true);
        scriptStore.update(rc.id(), new ScriptRule(null, null, true, "r",
                new Trigger.Timer(30), List.of(new Action.Log("x")), "{}"));
        router.fireRightClickWall(WALL, "Steve");
        assertEquals(1, sink.blockIds.size(), "换型规则不被右键事件误触发");
        // removeWall 清本墙 + shutdown no-op
        router.shutdown();
        router.fireRightClickWall(WALL, "Steve");
        assertEquals(1, sink.blockIds.size(), "关停后 fire no-op");
        assertEquals(0, router.rightClickRuleCount(WALL), "关停清空 rightClick 索引");
    }

    // ---------- 0.7.1：playerLeaveRange 进 NearEntry（leaveEdge=true） ----------

    @Test
    void playerLeaveRange_indexedAsNearEntryWithLeaveEdge() {
        addRule(WALL, true, new Trigger.PlayerLeaveRange(8));
        router.rebuildWall(WALL);
        List<TriggerRouter.NearEntry> entries = router.nearRules();
        assertEquals(1, entries.size(), "playerLeaveRange 进 near 快照");
        assertTrue(entries.get(0).leaveEdge(), "playerLeaveRange 的 NearEntry leaveEdge=true");
        assertEquals(8, entries.get(0).rangeBlocks());
    }

    @Test
    void playerNear_nearEntryLeaveEdgeIsFalse() {
        addRule(WALL, true, new Trigger.PlayerNear(8));
        router.rebuildWall(WALL);
        List<TriggerRouter.NearEntry> entries = router.nearRules();
        assertEquals(1, entries.size());
        assertFalse(entries.get(0).leaveEdge(), "playerNear 的 NearEntry leaveEdge=false");
    }

    @Test
    void firePlayerNear_dispatchesSourceByRuleType() {
        RecordingSubmitter rec = new RecordingSubmitter();
        TriggerRouter r2 = new TriggerRouter(scriptStore, rec,
                VariableInterpolator::resolveFullName, ORIGIN_AT_ZERO, LOG,
                new FakeTimerScheduler());
        ScriptRule near = addRule(WALL, true, new Trigger.PlayerNear(8));
        ScriptRule leave = addRule(WALL, true, new Trigger.PlayerLeaveRange(8));
        r2.rebuildWall(WALL);
        r2.firePlayerNear(WALL, near.id(), "Steve");
        assertEquals(TriggerContext.Source.PLAYER_NEAR, rec.contexts.get(0).source(),
                "playerNear 规则 → PLAYER_NEAR source");
        r2.firePlayerNear(WALL, leave.id(), "Steve");
        assertEquals(TriggerContext.Source.PLAYER_LEAVE_RANGE, rec.contexts.get(1).source(),
                "playerLeaveRange 规则 → PLAYER_LEAVE_RANGE source");
    }

    // ---------- playerNear 索引 + firePlayerNear（0.7.0-P3 B2 / K14） ----------

    @Test
    void nearIndex_rebuildAssemblesEntryWithOrigin() {
        ScriptRule near = addRule(WALL, true, new Trigger.PlayerNear(8));
        addRule(WALL, false, new Trigger.PlayerNear(16));   // disabled 不进
        addRule(WALL, true, new Trigger.Timer(30));         // 别的触发型不进
        router.rebuildWall(WALL);
        List<TriggerRouter.NearEntry> entries = router.nearRules();
        assertEquals(1, entries.size(), "仅 enabled 的 near 规则进快照");
        TriggerRouter.NearEntry e = entries.get(0);
        assertEquals(WALL, e.wallId());
        assertEquals(near.id(), e.ruleId());
        assertEquals(8, e.rangeBlocks());
        assertEquals(WORLD_ID, e.worldId(), "原点在 rebuild 期经 originSource 解析");
        assertEquals(0.0, e.x());
        // removeWall → 快照清空
        router.removeWall(WALL);
        assertTrue(router.nearRules().isEmpty(), "删墙后 near 快照清空");
    }

    @Test
    void nearIndex_originUnresolved_skippedWithoutEntry() {
        // ① originSource 注入但解析返 null（墙不存在 / 世界未加载）→ 跳过登记
        TriggerRouter rNull = new TriggerRouter(scriptStore, runner,
                VariableInterpolator::resolveFullName, wallId -> null, LOG,
                new FakeTimerScheduler());
        addRule(WALL, true, new Trigger.PlayerNear(8));
        rNull.rebuildWall(WALL);
        assertTrue(rNull.nearRules().isEmpty(), "原点解析失败 → near 规则跳过登记");
        // ② originSource 本身 null（装配不支持 playerNear）→ 同样跳过、不抛
        TriggerRouter rNoSource = new TriggerRouter(scriptStore, runner,
                VariableInterpolator::resolveFullName, null, LOG, new FakeTimerScheduler());
        assertDoesNotThrow(() -> rNoSource.rebuildWall(WALL));
        assertTrue(rNoSource.nearRules().isEmpty());
    }

    @Test
    void firePlayerNear_submitsContext_staleDisabledAndShutdownSkipped() {
        RecordingSubmitter rec = new RecordingSubmitter();
        TriggerRouter r2 = new TriggerRouter(scriptStore, rec,
                VariableInterpolator::resolveFullName, ORIGIN_AT_ZERO, LOG,
                new FakeTimerScheduler());
        ScriptRule near = addRule(WALL, true, new Trigger.PlayerNear(8));
        r2.rebuildWall(WALL);
        r2.firePlayerNear(WALL, near.id(), "Steve");
        assertEquals(1, rec.contexts.size());
        assertEquals(TriggerContext.Source.PLAYER_NEAR, rec.contexts.get(0).source());
        assertEquals(0, rec.contexts.get(0).chainDepth(), "事件来源链深恒 0");
        assertEquals("Steve", rec.contexts.get(0).detail(), "detail = 玩家名");
        // store 内禁用（不经 listener rebuild）→ fire 时刻 find 最新 → 跳过
        scriptStore.setEnabled(WALL, near.id(), false);
        r2.firePlayerNear(WALL, near.id(), "Steve");
        assertEquals(1, rec.contexts.size(), "disabled 规则不投递");
        // 换型（near → timer）→ 不被 near 事件误触发
        scriptStore.setEnabled(WALL, near.id(), true);
        scriptStore.update(near.id(), new ScriptRule(null, null, true, "r",
                new Trigger.Timer(30), List.of(new Action.Log("x")), "{}"));
        r2.firePlayerNear(WALL, near.id(), "Steve");
        assertEquals(1, rec.contexts.size(), "换型规则不被旧型事件误触发");
        // shutdown 后 no-op + 快照清空
        r2.shutdown();
        r2.firePlayerNear(WALL, near.id(), "Steve");
        assertEquals(1, rec.contexts.size(), "关停后 fire no-op");
        assertTrue(r2.nearRules().isEmpty(), "关停清空 near 快照");
    }

    // ---------- wallReady ----------

    @Test
    void fireWallReady_onlyEnabledWallReadyRules() {
        addRule(WALL, true, new Trigger.WallReady());
        addRule(WALL, false, new Trigger.WallReady());
        addRule(WALL, true, new Trigger.Timer(30));
        router.fireWallReady(WALL);
        assertEquals(1, sink.blockIds.size(), "仅 enabled 的 wallReady 规则投递一次");
        router.fireWallReadyAll(List.of(WALL, "w-none"));
        assertEquals(2, sink.blockIds.size(), "fireWallReadyAll 逐墙触发；无规则墙 no-op");
    }

    // ---------- shutdown ----------

    @Test
    void shutdown_idempotent_allEntrancesNoop() {
        addRule(WALL, true, new Trigger.VariableChange("user/a"));
        ScriptRule timer = addRule(WALL, true, new Trigger.Timer(30));
        addRule(WALL, true, new Trigger.WallReady());
        router.rebuildWall(WALL);
        router.shutdown();
        assertDoesNotThrow(router::shutdown, "幂等");
        assertEquals(1, fakeTimers.shutdownCount, "底层调度只关一次");
        assertTrue(fakeTimers.entries.get(0).future.cancelled);
        assertFalse(router.hasTimer(WALL, timer.id()));
        router.onVariableChange(event("user:" + WALL + "/a",
                VariableStore.ChangeType.VALUE_SET));
        router.fireWallReady(WALL);
        router.rebuildWall(WALL);
        assertEquals(0, sink.blockIds.size(), "关停后全入口 no-op");
        assertEquals(0, router.bindingCount("user:" + WALL + "/a"), "关停后 rebuild 不重建索引");
    }

    // ---------- fakes ----------

    /** 同步直跑调度替身（照 ScriptRunnerTest.InlineScheduler）。 */
    private static final class InlineScheduler implements ScriptRunner.TaskScheduler {
        final Deque<Runnable> pending = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void schedule(Runnable task, long delayMs) {
            pending.add(task);
        }

        @Override
        public void shutdown() {
        }
    }

    /** 记录投递的 RunSubmitter 替身（B1：ctx 形态断言）。 */
    private static final class RecordingSubmitter implements TriggerRouter.RunSubmitter {
        final List<String> wallIds = new ArrayList<>();
        final List<TriggerContext> contexts = new ArrayList<>();

        @Override
        public void submit(String wallId, ScriptRule rule, TriggerContext ctx) {
            wallIds.add(wallId);
            contexts.add(ctx);
        }
    }

    /** 记录调用的 ActionSink。 */
    private static final class RecordingSink implements ActionSink {
        final List<String> blockIds = new ArrayList<>();

        @Override
        public TraceStep execute(String wallId, String blockId, Action action) {
            blockIds.add(blockId);
            return TraceStep.ok(blockId, "action", null);
        }
    }

    /** 手动 tick 的 timer 调度替身。 */
    static final class FakeTimerScheduler implements TriggerRouter.TimerScheduler {
        static final class Entry {
            final Runnable task;
            final long initialDelayMs;
            final long periodMs;
            final FakeFuture future = new FakeFuture();

            Entry(Runnable task, long initialDelayMs, long periodMs) {
                this.task = task;
                this.initialDelayMs = initialDelayMs;
                this.periodMs = periodMs;
            }
        }

        final List<Entry> entries = new ArrayList<>();
        int shutdownCount = 0;

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task,
                                                      long initialDelayMs, long periodMs) {
            Entry e = new Entry(task, initialDelayMs, periodMs);
            entries.add(e);
            return e.future;
        }

        @Override
        public void shutdown() {
            shutdownCount++;
        }
    }

    /** 只记录 cancel 的 ScheduledFuture 替身。 */
    static final class FakeFuture implements ScheduledFuture<Object> {
        volatile boolean cancelled;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed o) {
            return 0;
        }
    }
}
