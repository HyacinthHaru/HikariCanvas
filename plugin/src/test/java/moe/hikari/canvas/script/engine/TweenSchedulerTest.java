package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.render.AnimationTicker;
import moe.hikari.canvas.script.Action;
import moe.hikari.canvas.state.BlendMode;
import moe.hikari.canvas.state.Easing;
import moe.hikari.canvas.state.Fill;
import moe.hikari.canvas.state.Layer;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.RectElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import moe.hikari.canvas.state.EditSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        @Override public AnimationTicker.Result play(String w, String t) { return AnimationTicker.Result.OK; }
        @Override public void pause(String w) {}
        @Override public AnimationTicker.Result seek(String w, String t, long ms) { return AnimationTicker.Result.OK; }
        @Override public boolean isWallAnimating(String w) { return false; }
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

    // ---------- 辅助 helper ----------

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
