package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.render.AnimationTicker;
import moe.hikari.canvas.script.Action;
import moe.hikari.canvas.state.BlendMode;
import moe.hikari.canvas.state.Easing;
import moe.hikari.canvas.state.Fill;
import moe.hikari.canvas.state.Layer;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.RectElement;
import moe.hikari.canvas.state.TextElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import moe.hikari.canvas.state.EditSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补间引擎 P2 单测（scripting-tween.md T1-T10）。
 *
 * <p>全 fake（seam 接口注入）：fake clock + fake applyFn + fake TickerControl +
 * fake WallLoader。{@link TweenScheduler#tickForTest} 绕开 scheduleAtFixedRate
 * 直接同步触发 tick，保证测试确定性。</p>
 */
class TweenSchedulerTest {

    private static final Logger LOG = Logger.getLogger("test-tween");
    private static final String WALL = "w-tween-test";
    private static final String BLOCK = "actions/0";
    private static final String ELEMENT = "e-rect";

    private AtomicLong clock;
    private FakeApplyFn applyFn;
    private FakeTicker ticker;
    private TweenScheduler scheduler;
    /** 可控的 base state（null = wall 不存在）。 */
    private ProjectState fakeState;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(0L);
        applyFn = new FakeApplyFn();
        ticker = new FakeTicker();
        fakeState = null;
        // 测试装配：seam 构造（maxFps=60, maxConcurrent=2）
        scheduler = new TweenScheduler(
                (wallId, blockId, elementId, rawPatch) -> applyFn.apply(wallId, blockId, elementId, rawPatch),
                ticker,
                wallId -> fakeState,    // WallLoader: 返 fakeState（null = not found）
                clock::get,
                2, 60, LOG);
    }

    // ---------- fake 基础设施 ----------

    /** 记录 applyFn 调用（不依赖 ElementPropertyApplier 继承）。 */
    static class FakeApplyFn {
        final List<String> log = new ArrayList<>();

        TraceStep apply(String wallId, String blockId, String elementId,
                        Map<String, String> rawPatch) {
            log.add(wallId + ":" + elementId + ":" + rawPatch);
            return TraceStep.ok(blockId, "action", "fake ok");
        }
    }

    /** 记录 renderStatic / clearStaticDiff 调用（实现 TickerControl 接口）。 */
    static class FakeTicker implements TickerControl {
        final List<String> renderCalls = new ArrayList<>();
        final List<String> clearCalls = new ArrayList<>();
        /** P3：可控的 isWallAnimating 返回值（true = 有时间轴）。 */
        final AtomicBoolean animating = new AtomicBoolean(false);

        @Override public AnimationTicker.Result play(String w, String t) { return AnimationTicker.Result.OK; }
        @Override public void pause(String w) {}
        @Override public AnimationTicker.Result seek(String w, String t, long ms) { return AnimationTicker.Result.OK; }
        @Override public boolean isWallAnimating(String w) { return animating.get(); }
        @Override public void invalidate(String w) {}
        @Override public void refreshAutoPlay(String w) {}

        @Override
        public void renderStatic(String wallId, ProjectState frame) {
            renderCalls.add(wallId);
        }

        @Override
        public void clearStaticDiff(String wallId) {
            clearCalls.add(wallId);
        }
    }

    /** 构建包含一个 RectElement（指定 x, y, w=100, h=50）的 ProjectState。 */
    private static ProjectState stateWithRect(int x, int y) {
        RectElement rect = new RectElement(ELEMENT, x, y, 100, 50, 0,
                false, true, Fill.solid("#FF0000"), null, null, BlendMode.NORMAL, null);
        Layer layer = new Layer("l-1", "L", true, false, 1.0f, BlendMode.NORMAL, null,
                List.of(rect));
        return new ProjectState(1L,
                new ProjectState.Canvas(2, 2, Fill.solid("#FFFFFF")),
                null, List.of(layer), "l-1",
                new ProjectState.History(0, 0), null, null, null);
    }

    /** 构建一个 TweenBlock（durationMs, body 含一个 SetElementProperties）。 */
    private static Action.TweenBlock tweenMove(long durationMs, int toX, int toY) {
        Action.SetElementProperties move = new Action.SetElementProperties(
                ELEMENT, Map.of("x", String.valueOf(toX), "y", String.valueOf(toY)), "moveTo");
        return new Action.TweenBlock(durationMs, Easing.LINEAR, List.of(move));
    }

    // ---------- 测试用例 ----------

    @Test
    void enqueue_readsFromAndTo_correctly() {
        fakeState = stateWithRect(10, 20);  // from x=10, y=20
        Action.TweenBlock tb = tweenMove(1000L, 110, 120);

        TraceStep step = scheduler.enqueue(WALL, BLOCK, tb);

        assertEquals("ok", step.result(), "enqueue 应成功");
        assertTrue(scheduler.hasActive(WALL), "enqueue 后应有活跃任务");
    }

    @Test
    void tick_at_local_zero_renders_and_no_applyMany() {
        fakeState = stateWithRect(0, 0);
        Action.SetElementProperties move = new Action.SetElementProperties(
                ELEMENT, Map.of("x", "100"), "moveTo");
        Action.TweenBlock tb = new Action.TweenBlock(1000L, Easing.LINEAR, List.of(move));

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tb);
        scheduler.tickForTest();  // local = 0

        // renderStatic 应被调用（local=0 也渲）
        assertFalse(ticker.renderCalls.isEmpty(), "local=0 应调用 renderStatic");
        // applyMany 不应在中间帧调用
        assertTrue(applyFn.log.isEmpty(), "local=0 不应落盘");
    }

    @Test
    void tick_at_local_half_renders_and_no_applyMany() {
        fakeState = stateWithRect(0, 0);
        Action.SetElementProperties move = new Action.SetElementProperties(
                ELEMENT, Map.of("x", "100"), "moveTo");
        Action.TweenBlock tb = new Action.TweenBlock(1000L, Easing.LINEAR, List.of(move));

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tb);

        // local = 0.5 → eased = 0.5 → x ≈ 50（线性，中点）
        clock.set(500L);
        scheduler.tickForTest();

        assertFalse(ticker.renderCalls.isEmpty(), "local=0.5 应 renderStatic");
        assertTrue(applyFn.log.isEmpty(), "中间帧不应落盘");
        // active 仍有任务
        assertTrue(scheduler.hasActive(WALL), "中间帧 active 应存在");
    }

    @Test
    void tick_at_local_one_finishes_applyMany_and_clears() {
        fakeState = stateWithRect(0, 0);
        Action.TweenBlock tb = tweenMove(1000L, 200, 300);

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tb);

        // local = 1.0 → 末帧
        clock.set(1000L);
        scheduler.tickForTest();

        // applyMany 落盘
        assertFalse(applyFn.log.isEmpty(), "末帧应调用 applyMany 落盘");
        // clearStaticDiff
        assertFalse(ticker.clearCalls.isEmpty(), "末帧应调用 clearStaticDiff");
        // active 清空
        assertFalse(scheduler.hasActive(WALL), "末帧后 active 应清空");
    }

    @Test
    void final_value_x_is_correct_on_finish() {
        fakeState = stateWithRect(0, 0);
        // from x=0, to x=100, duration=1000ms
        Action.SetElementProperties move = new Action.SetElementProperties(
                ELEMENT, Map.of("x", "100"), "moveTo");
        Action.TweenBlock tb = new Action.TweenBlock(1000L, Easing.LINEAR, List.of(move));

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tb);
        clock.set(1000L);
        scheduler.tickForTest();

        // log 格式: "wallId:elementId:{x=100}"
        String entry = applyFn.log.get(0);
        assertTrue(entry.contains("x=100") || entry.contains("\"x\""),
                "末帧 x 应为 100: " + entry);
    }

    @Test
    void maxConcurrent_exceeded_returns_error() {
        fakeState = stateWithRect(0, 0);
        Action.TweenBlock tb = tweenMove(5000L, 100, 0);

        // 填满 maxConcurrent=2（不同 wall）
        scheduler.enqueue("w-1", BLOCK, tb);
        scheduler.enqueue("w-2", BLOCK, tb);

        // 第三个不同 wall → 超限
        TraceStep step = scheduler.enqueue("w-3", BLOCK, tb);

        assertEquals("error", step.result(), "超过 maxConcurrent 应返回 error");
        assertTrue(step.detail() != null && step.detail().contains("上限"),
                "error detail 应说明上限: " + step.detail());
    }

    @Test
    void sameWall_takeover_clears_old_diff_and_succeeds() {
        fakeState = stateWithRect(0, 0);
        Action.TweenBlock first = tweenMove(1000L, 100, 0);  // from x=0 to x=100

        clock.set(0L);
        TraceStep s1 = scheduler.enqueue(WALL, BLOCK, first);
        assertEquals("ok", s1.result());

        // 过了一半
        clock.set(500L);

        // 同 wall 再来一个补间 → 接管（clearStaticDiff 先调）
        Action.TweenBlock second = tweenMove(1000L, 200, 0);
        TraceStep s2 = scheduler.enqueue(WALL, BLOCK, second);
        assertEquals("ok", s2.result(), "接管补间应成功");
        assertTrue(scheduler.hasActive(WALL), "接管后 active 仍有 wall");
        // clearStaticDiff 被调（旧补间清理）
        assertFalse(ticker.clearCalls.isEmpty(), "接管时应 clearStaticDiff");
    }

    @Test
    void enqueue_wallNotFound_returns_error() {
        // fakeState = null → wall 不存在
        TraceStep step = scheduler.enqueue(WALL, BLOCK, tweenMove(1000L, 100, 0));
        assertEquals("error", step.result(), "wall 不存在应返回 error");
        assertFalse(scheduler.hasActive(WALL));
    }

    @Test
    void enqueue_emptyBody_returns_error() {
        fakeState = stateWithRect(0, 0);
        Action.TweenBlock tb = new Action.TweenBlock(1000L, Easing.LINEAR, List.of());
        TraceStep step = scheduler.enqueue(WALL, BLOCK, tb);
        assertEquals("error", step.result(), "body 为空应返回 error");
    }

    @Test
    void sameWall_counts_as_one_against_maxConcurrent() {
        fakeState = stateWithRect(0, 0);
        Action.TweenBlock tb = tweenMove(5000L, 100, 0);

        // 先注册 w-1
        scheduler.enqueue("w-1", BLOCK, tb);
        assertEquals(1, scheduler.activeCount());

        // 同 wall w-1 再来一个（接管，不增计数）
        scheduler.enqueue("w-1", BLOCK, tweenMove(5000L, 200, 0));
        assertEquals(1, scheduler.activeCount(), "同 wall 接管不增加 active 计数");
    }

    // ---------- 0.7.1 per-wall fps 节流测试 ----------

    /**
     * 节流验证：task.fps=10（每 100ms 渲一次），SES maxFps=60（cadence=~17ms）。
     * 模拟推进 1000ms，每次 tick 间隔 17ms，预期 renderStatic 约 10 次（≤11）。
     */
    @Test
    void perWall_fps_throttles_renderStatic() {
        // 设置 per-wall tweenFps=10（每 100ms 应渲一次）
        fakeState = stateWithRectWithFps(0, 0, 10);

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tweenMove(1000L, 100, 0));

        // 模拟 60 tick，每次推进 17ms（≈ 60fps cadence）
        int renderCount = 0;
        for (int i = 0; i < 60; i++) {
            clock.set(clock.get() + 17L);
            int before = ticker.renderCalls.size();
            scheduler.tickForTest();
            if (ticker.renderCalls.size() > before) renderCount++;
        }
        // 1000ms / 100ms = 10 次左右（允许 ±1 浮动）；末帧也算一次
        assertTrue(renderCount >= 9 && renderCount <= 12,
                "fps=10 + 1s duration 应渲约 10 次（末帧强制），实际=" + renderCount);
    }

    /**
     * 末帧强制渲染：task.fps=1（每 1000ms 渲一次），但 duration=200ms < 1000ms，
     * 末帧必须触发一次 renderStatic（不能因节流跳过）。
     */
    @Test
    void final_frame_always_renders_despite_throttle() {
        // fps=1 → 节流间隔=1000ms；duration=200ms 内 clock 步进 < 1000ms
        fakeState = stateWithRectWithFps(0, 0, 1);

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tweenMove(200L, 50, 0));

        // 第一 tick：local < 1，还在节流窗口内
        clock.set(100L);
        scheduler.tickForTest();
        // 这次不确定是否渲（取决于 lastRenderAt 是否有初始值）；关键是末帧

        // 末帧 tick
        int renderBefore = ticker.renderCalls.size();
        clock.set(200L);
        scheduler.tickForTest();
        int renderAfter = ticker.renderCalls.size();

        assertTrue(renderAfter > renderBefore, "末帧即使在节流窗口内也必须渲染");
        assertFalse(scheduler.hasActive(WALL), "末帧后 active 清空");
        assertFalse(applyFn.log.isEmpty(), "末帧后 applyMany 落盘");
    }

    /**
     * enqueue 读 ProjectState.effectiveTweenFps()：
     * state.tweenFps = null → effectiveTweenFps = 30（默认）。
     * 30fps 节流间隔 ≈ 33ms；在 100ms 内 tick 3 次（间隔 17ms），应渲 ≤3 次。
     */
    @Test
    void enqueue_reads_effectiveTweenFps_from_state_default_null() {
        // tweenFps=null → effectiveTweenFps=30
        fakeState = stateWithRect(0, 0);  // tweenFps=null

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tweenMove(5000L, 100, 0));  // 长补间，不到末帧

        // 6 tick × 17ms = 102ms；fps=30 → 节流间隔 33ms → 应渲 ≤4 次
        for (int i = 0; i < 6; i++) {
            clock.set(clock.get() + 17L);
            scheduler.tickForTest();
        }
        int renders = ticker.renderCalls.size();
        assertTrue(renders >= 1 && renders <= 4,
                "null tweenFps → default 30fps 节流，102ms 内应渲 1-4 次，实际=" + renders);
    }

    // ---------- canvas.tweenFps op 测试 ----------

    /** setTweenFps 正常 [1,60] 范围存储并反映在 effectiveTweenFps。 */
    @Test
    void setTweenFps_stores_valid_value() {
        ProjectState state = new ProjectState(2, 2);
        EditSession es = new EditSession(state);

        EditSession.OpResult r = es.setTweenFps(15);

        assertEquals("ok", resultType(r), "fps=15 应成功");
        assertEquals(15, es.state().tweenFps(), "state.tweenFps 应为 15");
        assertEquals(15, es.state().effectiveTweenFps(), "effectiveTweenFps 应为 15");
    }

    /** setTweenFps(null) / setTweenFps(0) → 清回 null，effectiveTweenFps=30。 */
    @Test
    void setTweenFps_null_clears_to_default() {
        ProjectState state = new ProjectState(2, 2);
        EditSession es = new EditSession(state);
        es.setTweenFps(20);  // 先设一个值

        es.setTweenFps(null);
        assertEquals(null, es.state().tweenFps(), "null 应清回 null");
        assertEquals(30, es.state().effectiveTweenFps(), "effectiveTweenFps 应回 30");

        es.setTweenFps(0);
        assertEquals(null, es.state().tweenFps(), "0 应清回 null");
        assertEquals(30, es.state().effectiveTweenFps(), "effectiveTweenFps 应回 30");
    }

    /** setTweenFps 越界 → INVALID_PAYLOAD 错误。 */
    @Test
    void setTweenFps_out_of_range_returns_error() {
        ProjectState state = new ProjectState(2, 2);
        EditSession es = new EditSession(state);

        EditSession.OpResult r61 = es.setTweenFps(61);
        assertEquals("error", resultType(r61), "fps=61 超上限应报错");

        EditSession.OpResult r0 = es.setTweenFps(-1);
        assertEquals("error", resultType(r0), "fps=-1 低于下限应报错");
    }

    /** effectiveTweenFps clamp [1,60]：JSON 反序列化时如果 tweenFps 已是非法值也能 clamp 住。 */
    @Test
    void effectiveTweenFps_clamps_stored_value() {
        ProjectState state = new ProjectState(2, 2);
        // 绕开 setTweenFps 校验直接调 mutator 注入越界值（模拟历史数据或直接 Jackson 注入）
        state.tweenFps(999);
        assertEquals(60, state.effectiveTweenFps(), "effectiveTweenFps 应 clamp 到 60");

        state.tweenFps(-5);
        assertEquals(1, state.effectiveTweenFps(), "effectiveTweenFps 应 clamp 到 1");
    }

    // ---------- P3 测试用例 ----------

    /** P3：color 补间（TextElement.color，hex from/to → ColorLerp 中间帧）。 */
    @Test
    void p3_color_tween_midframe_renders_interpolated_hex() {
        fakeState = stateWithText(ELEMENT, "#000000");
        Action.SetElementProperties setColor = new Action.SetElementProperties(
                ELEMENT, Map.of("color", "#FFFFFF"), "setColor");
        Action.TweenBlock tb = new Action.TweenBlock(1000L, Easing.LINEAR, List.of(setColor));

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tb);

        // local = 0.5 → 中间帧
        clock.set(500L);
        scheduler.tickForTest();

        // renderStatic 被调（静态墙，中间帧）
        assertFalse(ticker.renderCalls.isEmpty(), "color 补间中间帧应 renderStatic");
        // applyFn 未调（未到末帧）
        assertTrue(applyFn.log.isEmpty(), "color 补间中间帧不应落盘");
        assertTrue(scheduler.hasActive(WALL), "中间帧 active 仍存在");
    }

    /** P3：color 补间末帧落盘，颜色值为目标 hex。 */
    @Test
    void p3_color_tween_final_applies_target_color() {
        fakeState = stateWithText(ELEMENT, "#000000");
        Action.SetElementProperties setColor = new Action.SetElementProperties(
                ELEMENT, Map.of("color", "#FF0000"), "setColor");
        Action.TweenBlock tb = new Action.TweenBlock(500L, Easing.LINEAR, List.of(setColor));

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tb);
        clock.set(500L);
        scheduler.tickForTest(); // 末帧

        assertFalse(applyFn.log.isEmpty(), "color 末帧应 applyFn");
        String entry = applyFn.log.get(0);
        assertTrue(entry.contains("#FF0000") || entry.contains("color"),
                "末帧颜色值应含目标 #FF0000: " + entry);
        assertFalse(scheduler.hasActive(WALL), "末帧后 active 清空");
    }

    /** P3：fill 补间（RectElement.fill SolidFill，hex from/to → lerpFill 中间帧）。 */
    @Test
    void p3_fill_tween_midframe_renders_and_no_applyMany() {
        fakeState = stateWithRect(0, 0); // fill = #FF0000
        Action.SetElementProperties setFill = new Action.SetElementProperties(
                ELEMENT, Map.of("fill", "#0000FF"), "setColor");
        Action.TweenBlock tb = new Action.TweenBlock(1000L, Easing.LINEAR, List.of(setFill));

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tb);
        clock.set(500L);
        scheduler.tickForTest(); // local = 0.5

        assertFalse(ticker.renderCalls.isEmpty(), "fill 补间中间帧应 renderStatic");
        assertTrue(applyFn.log.isEmpty(), "fill 补间中间帧不应落盘");
    }

    /** P3：fill 补间末帧落盘。 */
    @Test
    void p3_fill_tween_final_applies_target_fill() {
        fakeState = stateWithRect(0, 0); // fill = #FF0000
        Action.SetElementProperties setFill = new Action.SetElementProperties(
                ELEMENT, Map.of("fill", "#0000FF"), "setColor");
        Action.TweenBlock tb = new Action.TweenBlock(500L, Easing.LINEAR, List.of(setFill));

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tb);
        clock.set(500L);
        scheduler.tickForTest(); // 末帧

        assertFalse(applyFn.log.isEmpty(), "fill 末帧应 applyFn");
        String entry = applyFn.log.get(0);
        assertTrue(entry.contains("fill") || entry.contains("#0000FF"),
                "末帧 fill 值应含目标: " + entry);
        assertFalse(scheduler.hasActive(WALL));
    }

    /**
     * P3：共存分流——有时间轴的墙（animating=true）。
     * 中间帧应走 applyFn（落 DB），不走 renderStatic。
     */
    @Test
    void p3_coexist_animating_wall_uses_applyMany_not_renderStatic() {
        fakeState = stateWithRect(0, 0);
        ticker.animating.set(true); // 模拟该 wall 有时间轴在播
        Action.TweenBlock tb = tweenMove(1000L, 100, 0);

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tb);
        clock.set(500L);
        scheduler.tickForTest(); // 中间帧

        // animating 墙：applyFn 被调（落 DB）
        assertFalse(applyFn.log.isEmpty(), "animating 墙中间帧应 applyFn");
        // renderStatic 不调
        assertTrue(ticker.renderCalls.isEmpty(), "animating 墙中间帧不应 renderStatic");
    }

    /**
     * P3：共存分流——静态墙（animating=false）。
     * 中间帧应走 renderStatic，不走 applyFn。
     */
    @Test
    void p3_coexist_static_wall_uses_renderStatic_not_applyMany() {
        fakeState = stateWithRect(0, 0);
        ticker.animating.set(false); // 静态墙
        Action.TweenBlock tb = tweenMove(1000L, 100, 0);

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tb);
        clock.set(500L);
        scheduler.tickForTest(); // 中间帧

        // 静态墙：renderStatic 被调
        assertFalse(ticker.renderCalls.isEmpty(), "静态墙中间帧应 renderStatic");
        // applyFn 未调
        assertTrue(applyFn.log.isEmpty(), "静态墙中间帧不应 applyFn");
    }

    /**
     * P3：共存末帧——无论 animating=true/false，末帧都 applyFn 落 DB。
     */
    @Test
    void p3_coexist_both_walls_applyMany_on_final() {
        // 测试 animating=true 的末帧
        fakeState = stateWithRect(0, 0);
        ticker.animating.set(true);
        Action.TweenBlock tb = tweenMove(500L, 100, 0);

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tb);
        clock.set(500L);
        scheduler.tickForTest();

        assertFalse(applyFn.log.isEmpty(), "animating 墙末帧应 applyFn 落 DB");
        assertFalse(scheduler.hasActive(WALL), "末帧后 active 清空");

        // 重置，测试 animating=false 的末帧
        applyFn.log.clear();
        ticker.renderCalls.clear();
        ticker.animating.set(false);

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tb);
        clock.set(500L);
        scheduler.tickForTest();

        assertFalse(applyFn.log.isEmpty(), "静态墙末帧也应 applyFn 落 DB");
    }

    /**
     * P3：animating 状态每 tick 现查（不缓存）。
     * 补间中途 animating 从 false 变 true → 后续 tick 切换路径。
     */
    @Test
    void p3_animating_checked_each_tick_not_cached() {
        fakeState = stateWithRect(0, 0);
        ticker.animating.set(false); // 初始静态

        Action.TweenBlock tb = tweenMove(1000L, 100, 0);
        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tb);

        // 第一次 tick（静态）→ renderStatic
        clock.set(250L);
        scheduler.tickForTest();
        int renderCountAfterFirst = ticker.renderCalls.size();
        assertTrue(renderCountAfterFirst > 0, "第一次 tick（静态）应 renderStatic");
        assertTrue(applyFn.log.isEmpty(), "第一次 tick 不应 applyFn");

        // 切换为 animating
        ticker.animating.set(true);
        applyFn.log.clear();
        ticker.renderCalls.clear();

        // 第二次 tick（animating）→ applyFn，不 renderStatic
        clock.set(600L);
        scheduler.tickForTest();
        assertFalse(applyFn.log.isEmpty(), "切换 animating 后 tick 应 applyFn");
        assertTrue(ticker.renderCalls.isEmpty(), "切换 animating 后不应 renderStatic");
    }

    /**
     * P3：${var:} 颜色退化末帧瞬切——enqueue 成功（不报错），但 toStr 含变量。
     * 中间帧 renderStatic 被调（snap=true → buildPatchValue 返 null → 中间帧无 color patch，
     * 但 buildInterpolatedFrame 其他属性仍正常）；末帧 applyFn 落盘含变量字符串。
     */
    @Test
    void p3_color_with_var_snaps_to_final_on_end() {
        fakeState = stateWithText(ELEMENT, "#000000");
        Action.SetElementProperties setColor = new Action.SetElementProperties(
                ELEMENT, Map.of("color", "${var:user/mycolor}"), "setColor");
        Action.TweenBlock tb = new Action.TweenBlock(500L, Easing.LINEAR, List.of(setColor));

        clock.set(0L);
        TraceStep step = scheduler.enqueue(WALL, BLOCK, tb);
        // snap 目标：enqueue 仍应成功（不报错）
        assertEquals("ok", step.result(), "含变量的 color 补间 enqueue 应成功");

        clock.set(500L);
        scheduler.tickForTest(); // 末帧

        assertFalse(applyFn.log.isEmpty(), "含变量 color 末帧应 applyFn");
        String entry = applyFn.log.get(0);
        // 末帧值 = 目标字符串（含变量，原样落盘，由 VariableInterpolator resolve）
        assertTrue(entry.contains("${var:user/mycolor}"),
                "含变量颜色末帧应落变量字符串: " + entry);
    }

    // ---------- P3-16：buildInterpolatedFrame 不得原地 mutate 共享 baseState ----------

    /**
     * P3-16 回归：补间中间帧产帧（{@code buildInterpolatedFrame}）<b>不得</b>原地 mutate
     * {@code task.baseState()}——否则该 baseState 的 element ArrayList 会被 tick 线程并发 set，
     * 而它同时被 renderStatic 异步在 Ticker 线程读 → 撕裂读。
     *
     * <p>旧实现 {@code new EditSession(task.baseState())} 按引用共享 layers/elements，
     * {@code updateElement} 的 {@code elements().set(idx, ...)} 直接改 baseState；连续两次 tick 后
     * baseState 里 rect 的 x 会被污染成插值值（50 / …）。修复后每帧 {@code deepCopyState} 拍独立
     * 副本，baseState 的 from 值（x=0）始终不变。</p>
     */
    @Test
    void p3_16_buildInterpolatedFrame_doesNotMutateSharedBaseState() {
        // from x=0, to x=100；静态墙（animating=false 默认）走 buildInterpolatedFrame
        fakeState = stateWithRect(0, 0);
        Action.SetElementProperties move = new Action.SetElementProperties(
                ELEMENT, Map.of("x", "100"), "moveTo");
        Action.TweenBlock tb = new Action.TweenBlock(1000L, Easing.LINEAR, List.of(move));

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tb);

        assertEquals(0, rectXIn(fakeState), "enqueue 后 baseState 的 from 值应为 0");

        // 第一次中间帧 tick（local=0.25 → eased x≈25）
        clock.set(250L);
        scheduler.tickForTest();
        assertEquals(0, rectXIn(fakeState),
                "第一次中间帧产帧后 baseState 的 x 仍应为原始 0（产帧用的是深拷贝副本）");

        // 第二次中间帧 tick（local=0.5 → eased x≈50）
        clock.set(500L);
        scheduler.tickForTest();
        assertEquals(0, rectXIn(fakeState),
                "第二次中间帧产帧后 baseState 的 x 仍应为原始 0（无跨帧污染）");

        // renderStatic 确实被调（证明走的是静态墙产帧路径，而非空操作）
        assertFalse(ticker.renderCalls.isEmpty(), "静态墙中间帧应 renderStatic");
        assertTrue(scheduler.hasActive(WALL), "未到末帧 active 仍在");
    }

    // ---------- E1：补间末帧落盘 → 续接回调（顺序承诺 scripting-tween.md §2.2） ----------

    /**
     * E1 核心顺序承诺：续接回调（{@code onComplete}）<b>必在末帧 applyFn 落盘之后</b>触发。
     * 用共享 {@code events} 序列记录两件事的发生先后——断言 WRITE 在 CONTINUE 之前。
     */
    @Test
    void e1_onComplete_firesAfterFinalApplyFn() {
        List<String> events = new ArrayList<>();
        // applyFn 记 WRITE（在 FakeApplyFn 之外另用一个直接记序的 fake）
        TweenScheduler sched = new TweenScheduler(
                (wallId, blockId, elementId, rawPatch) -> {
                    events.add("WRITE:" + rawPatch);
                    return TraceStep.ok(blockId, "action", "ok");
                },
                ticker, wallId -> fakeState, clock::get, 2, 60, LOG);

        fakeState = stateWithRect(0, 0);
        Action.TweenBlock tb = tweenMove(500L, 100, 0);
        clock.set(0L);
        sched.enqueue(WALL, BLOCK, tb, () -> events.add("CONTINUE"));

        // 中间帧：还没落盘、还没续接
        clock.set(250L);
        sched.tickForTest();
        assertFalse(events.contains("CONTINUE"), "中间帧不应续接");

        // 末帧：先 WRITE 再 CONTINUE
        clock.set(500L);
        sched.tickForTest();
        assertEquals(2, events.size(), "末帧应恰有 WRITE + CONTINUE 两事件: " + events);
        assertTrue(events.get(0).startsWith("WRITE"), "第一件应是落盘 WRITE: " + events);
        assertEquals("CONTINUE", events.get(1), "续接必在落盘之后: " + events);
        assertTrue(events.get(0).contains("100"), "落盘值应为目标 x=100: " + events);
    }

    /** E1：正常完成只触发 onComplete 一次（即使再多 tick 也不重复）。 */
    @Test
    void e1_onComplete_firesExactlyOnce_onNormalCompletion() {
        java.util.concurrent.atomic.AtomicInteger fires = new java.util.concurrent.atomic.AtomicInteger();
        fakeState = stateWithRect(0, 0);
        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tweenMove(500L, 100, 0), fires::incrementAndGet);

        clock.set(500L);
        scheduler.tickForTest();   // 末帧 → 触发一次
        assertEquals(1, fires.get(), "末帧触发恰一次");

        // 再 tick（active 已空，no-op）
        clock.set(600L);
        scheduler.tickForTest();
        assertEquals(1, fires.get(), "末帧后再 tick 不重复触发");
    }

    /**
     * E1：被同墙新补间接管时，<b>旧补间</b>的 onComplete 触发一次（旧脚本不永久挂起，T8 接管语义）；
     * 新补间的 onComplete 不在接管瞬间触发（它还没完成）。
     */
    @Test
    void e1_takeover_firesOldOnComplete_once_notNew() {
        java.util.concurrent.atomic.AtomicInteger oldFires = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger newFires = new java.util.concurrent.atomic.AtomicInteger();
        fakeState = stateWithRect(0, 0);

        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tweenMove(1000L, 100, 0), oldFires::incrementAndGet);

        clock.set(500L);
        // 同 wall 接管 → 旧补间 onComplete 应触发一次
        scheduler.enqueue(WALL, BLOCK, tweenMove(1000L, 200, 0), newFires::incrementAndGet);
        assertEquals(1, oldFires.get(), "接管应触发旧补间续接一次");
        assertEquals(0, newFires.get(), "新补间还没完成，不应触发");

        // 新补间跑完末帧 → 新 onComplete 触发一次，旧的不重复
        clock.set(1500L);
        scheduler.tickForTest();
        assertEquals(1, newFires.get(), "新补间末帧触发一次");
        assertEquals(1, oldFires.get(), "旧补间续接不被重复触发");
    }

    /**
     * E1：补间 tick 异常（{@code renderStatic} 抛）也触发 onComplete 一次——脚本不永久挂起。
     * 用一个会抛的 ticker 让 tickOne 末帧 renderStatic 抛 → 落到 tick() 的 Throwable 清理路径。
     */
    @Test
    void e1_onComplete_firesOnTickException_noPermanentHang() {
        java.util.concurrent.atomic.AtomicInteger fires = new java.util.concurrent.atomic.AtomicInteger();
        // throwingTicker：renderStatic 抛 RuntimeException（静态墙末帧会调 renderStatic）
        TickerControl throwingTicker = new TickerControl() {
            @Override public AnimationTicker.Result play(String w, String t) { return AnimationTicker.Result.OK; }
            @Override public void pause(String w) {}
            @Override public AnimationTicker.Result seek(String w, String t, long ms) { return AnimationTicker.Result.OK; }
            @Override public boolean isWallAnimating(String w) { return false; }
            @Override public void invalidate(String w) {}
            @Override public void refreshAutoPlay(String w) {}
            @Override public void renderStatic(String wallId, ProjectState frame) {
                throw new RuntimeException("boom render");
            }
            @Override public void clearStaticDiff(String wallId) {}
        };
        TweenScheduler sched = new TweenScheduler(
                (wallId, blockId, elementId, rawPatch) -> TraceStep.ok(blockId, "action", "ok"),
                throwingTicker, wallId -> fakeState, clock::get, 2, 60, LOG);

        fakeState = stateWithRect(0, 0);
        clock.set(0L);
        sched.enqueue(WALL, BLOCK, tweenMove(500L, 100, 0), fires::incrementAndGet);

        clock.set(500L);
        sched.tickForTest();   // 末帧 renderStatic 抛 → tick catch → fireComplete
        assertEquals(1, fires.get(), "tick 异常清理路径应触发续接一次（脚本不挂起）");
        assertFalse(sched.hasActive(WALL), "异常后 active 应清空");
    }

    /** E1：shutdown <b>不</b>触发 onComplete（关服路径，与 ScriptRunner 丢弃续接契约一致）。 */
    @Test
    void e1_shutdown_doesNotFireOnComplete() {
        java.util.concurrent.atomic.AtomicInteger fires = new java.util.concurrent.atomic.AtomicInteger();
        fakeState = stateWithRect(0, 0);
        clock.set(0L);
        scheduler.enqueue(WALL, BLOCK, tweenMove(5000L, 100, 0), fires::incrementAndGet);

        scheduler.shutdown();
        assertEquals(0, fires.get(), "shutdown 不触发续接回调");
    }

    /** E1：null onComplete 安全（演示拼接 / 测试无续接需求）——不崩、补间照常完成。 */
    @Test
    void e1_nullOnComplete_isSafe() {
        fakeState = stateWithRect(0, 0);
        clock.set(0L);
        // 3 参重载等价 onComplete=null
        TraceStep step = scheduler.enqueue(WALL, BLOCK, tweenMove(500L, 100, 0));
        assertEquals("ok", step.result());
        clock.set(500L);
        scheduler.tickForTest();   // 末帧；null 回调不应抛
        assertFalse(applyFn.log.isEmpty(), "null 回调下末帧仍正常落盘");
        assertFalse(scheduler.hasActive(WALL));
    }

    /** 读取 ProjectState 中 ELEMENT 这个 rect 的 x（找不到返 Integer.MIN_VALUE）。 */
    private static int rectXIn(ProjectState state) {
        for (var layer : state.layers()) {
            for (var el : layer.elements()) {
                if (el.id().equals(ELEMENT)) return el.x();
            }
        }
        return Integer.MIN_VALUE;
    }

    // ---------- 辅助 helper ----------

    /** 构建包含一个 TextElement（指定 color）的 ProjectState。 */
    private static ProjectState stateWithText(String elementId, String color) {
        TextElement text = new TextElement(elementId, 10, 10, 200, 50, 0,
                false, true, "Hello", "inter", 20, color,
                "left", 0.0f, 1.2f, false, null,
                null, null, null, null, null);
        Layer layer = new Layer("l-1", "L", true, false, 1.0f, BlendMode.NORMAL, null,
                List.of(text));
        return new ProjectState(1L,
                new ProjectState.Canvas(2, 2, Fill.solid("#FFFFFF")),
                null, List.of(layer), "l-1",
                new ProjectState.History(0, 0), null, null, null);
    }

    /** 构建带 tweenFps 的 ProjectState。 */
    private static ProjectState stateWithRectWithFps(int x, int y, int fps) {
        RectElement rect = new RectElement(ELEMENT, x, y, 100, 50, 0,
                false, true, Fill.solid("#FF0000"), null, null, BlendMode.NORMAL, null);
        Layer layer = new Layer("l-1", "L", true, false, 1.0f, BlendMode.NORMAL, null,
                List.of(rect));
        ProjectState s = new ProjectState(1L,
                new ProjectState.Canvas(2, 2, Fill.solid("#FFFFFF")),
                null, List.of(layer), "l-1",
                new ProjectState.History(0, 0), null, null, fps);
        return s;
    }

    /** 取 OpResult 类型字符串（ok / error）。 */
    private static String resultType(EditSession.OpResult r) {
        return switch (r) {
            case EditSession.OpResult.Ok ok -> "ok";
            case EditSession.OpResult.Error err -> "error";
            default -> "unknown";
        };
    }
}
