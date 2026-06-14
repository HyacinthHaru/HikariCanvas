package moe.hikari.canvas.render;

import moe.hikari.canvas.state.BlendMode;
import moe.hikari.canvas.state.Easing;
import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.Fill;
import moe.hikari.canvas.state.Keyframe;
import moe.hikari.canvas.state.KfValue;
import moe.hikari.canvas.state.Layer;
import moe.hikari.canvas.state.LoopMode;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.RenderMode;
import moe.hikari.canvas.state.TextElement;
import moe.hikari.canvas.state.Timeline;
import moe.hikari.canvas.state.TriggerConfig;
import moe.hikari.canvas.state.TriggerType;
import moe.hikari.canvas.storage.WallRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnimationTicker} 单测（0.6 P2）。线程模型与播放语义见 {@code docs/architecture.md §5.5}
 * 与 {@code docs/timeline.md §3.1 / §5.2}。
 *
 * <p><b>确定性策略：</b>注入 fake {@code WallSource}（内存 Map，不碰 SQLite）+ fake
 * {@code FrameRenderer}（{@link AtomicInteger} 计数，线程安全）+ {@code setNanoClock}
 * 控制墙钟 + {@code tickOnceForTest} 直接驱动单 tick，不依赖真实调度线程时序。</p>
 *
 * <p><b>cadence 防御：</b>{@code play()} 会在 daemon 调度器上排一个 fixed-rate 任务。
 * 测试用例统一把 timeline fps 设为 1（cadence ≈ 1000ms 首次延迟），并在每次显式
 * {@code tickOnceForTest} 前 {@code renderer.reset()}——调度任务在断言这一瞬（微秒级）不会
 * 抢先触发，计数因此只反映显式驱动的那一次 tick。每个用例收尾 {@code shutdown()} 防线程残留
 * （{@link #tearDown}）。</p>
 */
class AnimationTickerTest {

    private static final Logger LOG = Logger.getLogger(AnimationTickerTest.class.getName());

    private AnimationTicker ticker;

    @AfterEach
    void tearDown() {
        if (ticker != null) ticker.shutdown();
    }

    // ---------- fake 装配 ----------

    /** 内存 wall 源；put 覆盖（模拟编辑持久化后 invalidate 重载），remove 模拟 wall 删除。 */
    private static final class FakeWallSource implements AnimationTicker.WallSource {
        final Map<String, WallRepo.Wall> walls = new HashMap<>();

        @Override
        public WallRepo.Wall load(String wallId) {
            return walls.get(wallId);
        }

        @Override
        public List<WallRepo.Wall> loadAll() {
            return new ArrayList<>(walls.values());
        }
    }

    /**
     * 可配 viewers + 记录 renderFrame 调用次数与最近一帧（线程安全）。
     * 照生产实现（CanvasProjector.renderFrame）语义：viewers 空且非 force → 返 -1 不渲染
     * （skippedCount 记录），仅 force 或有观察者时才算一次真实渲染。
     */
    private static final class FakeRenderer implements AnimationTicker.FrameRenderer {
        volatile boolean viewers = true;
        final AtomicInteger renderCount = new AtomicInteger();
        final AtomicInteger skippedCount = new AtomicInteger();
        volatile ProjectState lastFrame;

        @Override
        public int renderFrame(WallRepo.Wall wall, ProjectState frame,
                               AnimationTicker.FrameDiff diff, boolean force) {
            if (!viewers && !force) {
                skippedCount.incrementAndGet();
                return -1;
            }
            renderCount.incrementAndGet();
            lastFrame = frame;
            return 0;
        }

        void reset() {
            renderCount.set(0);
            skippedCount.set(0);
            lastFrame = null;
        }

        /** 最近一帧被动画元素 "e-1" 的 x 值（用来反推播放位置；timeline 设计为 x == t）。 */
        int lastX() {
            ProjectState f = lastFrame;
            if (f == null) return Integer.MIN_VALUE;
            for (Layer l : f.layers()) {
                for (Element e : l.elements()) {
                    if (e.id().equals("e-1")) return e.x();
                }
            }
            return Integer.MIN_VALUE;
        }
    }

    // ---------- state / wall builders ----------

    /** 单文本元素 + 单 LOOP/ONCE timeline 的 wall state；x 轨设计为 x(t) == t（t∈[0,1000]）。 */
    private static ProjectState stateWith(String elementId, LoopMode mode, String timelineId, int fps) {
        return stateWith(elementId, mode, timelineId, fps, TriggerConfig.MANUAL);
    }

    private static ProjectState stateWith(String elementId, LoopMode mode, String timelineId, int fps,
                                          TriggerConfig trigger) {
        TextElement t = new TextElement(elementId, 0, 0, 100, 50, 0, false, true,
                "hi", "inter", 24, "#000000", "left", 0f, 1.2f, false, null,
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null, null);
        List<Element> els = new ArrayList<>(List.of(t));
        Layer layer = new Layer("l-1", "L", true, false, 1.0f, BlendMode.NORMAL, null, els);

        Map<String, List<Keyframe>> tracks = new LinkedHashMap<>();
        tracks.put(elementId, List.of(
                new Keyframe("k0", "x", 0, KfValue.of(0.0), Easing.LINEAR),
                new Keyframe("k1", "x", 1000, KfValue.of(1000.0), Easing.LINEAR)));
        Timeline tl = new Timeline(timelineId, "T", 1000, fps, mode, trigger, tracks);

        List<Layer> layers = new ArrayList<>(List.of(layer));
        return new ProjectState(1L,
                new ProjectState.Canvas(2, 2, Fill.solid("#FFFFFF")),
                null, layers, "l-1", new ProjectState.History(0, 0),
                List.of(tl), timelineId, null);
    }

    private static WallRepo.Wall wall(String wallId, ProjectState state) {
        return new WallRepo.Wall(wallId, null, state, List.of(0),
                2, 2, new UUID(0L, 0L), "owner", null, null, 0L, 0L);
    }

    private AnimationTicker newTicker(FakeWallSource src, FakeRenderer renderer) {
        AnimationTicker t = new AnimationTicker(src, renderer, 60, LOG);
        this.ticker = t;
        return t;
    }

    // ---------- play / pause / seek 控制语义 ----------

    @Test
    void play_success() {
        FakeWallSource src = new FakeWallSource();
        FakeRenderer r = new FakeRenderer();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, r);

        assertEquals(AnimationTicker.Result.OK, t.play("w-1", "tl-1"), "play 成功");
        assertTrue(t.isWallAnimating("w-1"), "播放中 isWallAnimating=true");
        assertEquals(1, t.registeredCount(), "注册一条");
    }

    @Test
    void play_wallNotFound() {
        AnimationTicker t = newTicker(new FakeWallSource(), new FakeRenderer());
        assertEquals(AnimationTicker.Result.WALL_NOT_FOUND, t.play("nope", "tl-1"),
                "不存在 wall → WALL_NOT_FOUND");
        assertFalse(t.isWallAnimating("nope"));
    }

    @Test
    void play_timelineNotFound() {
        FakeWallSource src = new FakeWallSource();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, new FakeRenderer());
        assertEquals(AnimationTicker.Result.TIMELINE_NOT_FOUND, t.play("w-1", "tl-MISSING"),
                "timeline 不存在 → TIMELINE_NOT_FOUND");
    }

    @Test
    void play_nullTimelineIdUsesActive() {
        FakeWallSource src = new FakeWallSource();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-active", 1)));
        AnimationTicker t = newTicker(src, new FakeRenderer());
        assertEquals(AnimationTicker.Result.OK, t.play("w-1", null),
                "timelineId null → 取 activeTimelineId");
        assertTrue(t.isWallAnimating("w-1"));
    }

    @Test
    void autoRegisterAll_skipsNonManualTrigger() {
        FakeWallSource src = new FakeWallSource();
        // 0.6 P5：VARIABLE_CHANGE + LOOP 启动期不自动播（由 TimelineTriggerRegistry 在变量变化时驱动）
        TriggerConfig vc = new TriggerConfig(TriggerType.VARIABLE_CHANGE, Map.of("fullName", "user/hp"));
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1, vc)));
        AnimationTicker t = newTicker(src, new FakeRenderer());
        assertEquals(0, t.autoRegisterAll(null), "VARIABLE_CHANGE 触发不自动播");
        assertFalse(t.isWallAnimating("w-1"));
    }

    @Test
    void autoRegisterAll_playsManualLoop() {
        FakeWallSource src = new FakeWallSource();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));   // MANUAL + LOOP
        AnimationTicker t = newTicker(src, new FakeRenderer());
        assertEquals(1, t.autoRegisterAll(null), "MANUAL + LOOP 仍自动播");
        assertTrue(t.isWallAnimating("w-1"));
    }

    @Test
    void pause_thenIdempotent() {
        FakeWallSource src = new FakeWallSource();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, new FakeRenderer());
        t.play("w-1", "tl-1");
        t.pause("w-1");
        assertFalse(t.isWallAnimating("w-1"), "pause 后 isWallAnimating=false");
        assertEquals(1, t.registeredCount(), "pause 不注销（保位置）");
        // 幂等
        t.pause("w-1");
        assertFalse(t.isWallAnimating("w-1"), "重复 pause 仍 false");
        // 未注册 pause = no-op
        t.pause("w-other");
        assertEquals(1, t.registeredCount());
    }

    @Test
    void seek_outOfRangeClampedToDuration() {
        FakeWallSource src = new FakeWallSource();
        FakeRenderer r = new FakeRenderer();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, r);
        // 越上界：clamp 到 duration=1000；建暂停态条目
        assertEquals(AnimationTicker.Result.OK, t.seek("w-1", "tl-1", 99999L),
                "未注册 seek 建条目");
        assertEquals(1, t.registeredCount(), "seek 建一条");
        assertFalse(t.isWallAnimating("w-1"), "seek 建的是暂停态条目");

        // play 从 seek 位置续：固定 nanoClock，位置 = clamp(99999)=1000 → mapTime(LOOP,1000) = 0 → x=0
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        t.setNanoClock(clock::get);
        t.play("w-1", "tl-1");
        r.reset();
        t.tickOnceForTest("w-1");
        // LOOP 在 posMs=1000 取模回 0 → x(0)=0
        assertEquals(0, r.lastX(), "seek 到末尾 1000 在 LOOP 取模为 0 → 渲染 x=0");
    }

    @Test
    void seek_negativeClampedToZero() {
        FakeWallSource src = new FakeWallSource();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, new FakeRenderer());
        assertEquals(AnimationTicker.Result.OK, t.seek("w-1", "tl-1", -500L), "负 seek clamp 到 0");
        assertEquals(1, t.registeredCount());
    }

    @Test
    void seek_thenPlayResumesAtSeekedPosition() {
        FakeWallSource src = new FakeWallSource();
        FakeRenderer r = new FakeRenderer();
        // ONCE：posMs 不取模，便于位置 = x 直接验证
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.ONCE, "tl-1", 1)));
        AnimationTicker t = newTicker(src, r);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        t.setNanoClock(clock::get);

        t.seek("w-1", "tl-1", 400L);   // 暂停态停在 400
        assertEquals(1, t.registeredCount());
        assertFalse(t.isWallAnimating("w-1"));

        t.play("w-1", null);   // null → activeTimelineId=tl-1 → resume（保位置 400）
        assertTrue(t.isWallAnimating("w-1"));
        // nanoClock 不推进 → currentPos = 400；ONCE mapTime(400)=400 → x(400)=400
        r.reset();
        t.tickOnceForTest("w-1");
        assertEquals(400, r.lastX(), "play 从 seek 位置 400 续 → 渲染 x=400");

        // 推进墙钟 +200ms → pos=600 → x=600
        clock.addAndGet(200_000_000L);
        r.reset();
        t.tickOnceForTest("w-1");
        assertEquals(600, r.lastX(), "墙钟 +200ms → 位置 600 → x=600");
    }

    // ---------- tick：viewer-gate ----------

    @Test
    void tick_viewerGatedNoRenderWhenNoViewers() {
        FakeWallSource src = new FakeWallSource();
        FakeRenderer r = new FakeRenderer();
        r.viewers = false;
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, r);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        t.setNanoClock(clock::get);
        t.play("w-1", "tl-1");

        r.reset();
        t.tickOnceForTest("w-1");
        assertEquals(0, r.renderCount.get(), "无观察者 → 不渲染（viewer-gated）");
    }

    @Test
    void tick_rendersOnceWhenViewersPresent() {
        FakeWallSource src = new FakeWallSource();
        FakeRenderer r = new FakeRenderer();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, r);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        t.setNanoClock(clock::get);
        t.play("w-1", "tl-1");

        r.reset();
        t.tickOnceForTest("w-1");
        assertEquals(1, r.renderCount.get(), "有观察者 → 渲染一次");
    }

    // ---------- ONCE 末帧自动注销 ----------

    @Test
    void once_endFrameRenderedAndDeregistered() {
        FakeWallSource src = new FakeWallSource();
        FakeRenderer r = new FakeRenderer();
        // ONCE 末帧分支无视 viewer，故 viewers=false 也应渲染末帧
        r.viewers = false;
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.ONCE, "tl-1", 1)));
        AnimationTicker t = newTicker(src, r);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        t.setNanoClock(clock::get);
        t.play("w-1", "tl-1");

        // 推过 duration（+1500ms > 1000）
        clock.addAndGet(1_500_000_000L);
        r.reset();
        t.tickOnceForTest("w-1");
        assertEquals(1, r.renderCount.get(), "ONCE 越尾 → 渲染末帧一次");
        assertEquals(1000, r.lastX(), "末帧 = duration → x(1000)=1000");
        assertEquals(0, t.registeredCount(), "末帧后自动注销");
        assertFalse(t.isWallAnimating("w-1"));
    }

    // ---------- invalidate：重载 / 注销 ----------

    @Test
    void invalidate_reloadsNewState() {
        FakeWallSource src = new FakeWallSource();
        FakeRenderer r = new FakeRenderer();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.ONCE, "tl-1", 1)));
        AnimationTicker t = newTicker(src, r);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        t.setNanoClock(clock::get);
        t.play("w-1", "tl-1");

        // 改 fake 源里同 wall 的元素几何（基值 x=500），fps 不变；invalidate 后下一 tick 重载
        TextElement moved = new TextElement("e-1", 500, 0, 100, 50, 0, false, true,
                "hi", "inter", 24, "#000000", "left", 0f, 1.2f, false, null,
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null, null);
        // 新 timeline：x 轨恒为基值不变（无 x 关键帧），让渲染直接反映新基值 500
        Map<String, List<Keyframe>> noXTracks = new LinkedHashMap<>();
        noXTracks.put("e-1", List.of(
                new Keyframe("ko0", "opacity", 0, KfValue.of(1.0), Easing.LINEAR),
                new Keyframe("ko1", "opacity", 1000, KfValue.of(1.0), Easing.LINEAR)));
        Timeline tl2 = new Timeline("tl-1", "T", 1000, 1, LoopMode.ONCE, TriggerConfig.MANUAL, noXTracks);
        List<Element> els = new ArrayList<>(List.of(moved));
        Layer layer = new Layer("l-1", "L", true, false, 1.0f, BlendMode.NORMAL, null, els);
        List<Layer> layers = new ArrayList<>(List.of(layer));
        ProjectState s2 = new ProjectState(2L,
                new ProjectState.Canvas(2, 2, Fill.solid("#FFFFFF")),
                null, layers, "l-1", new ProjectState.History(0, 0),
                List.of(tl2), "tl-1", null);
        src.walls.put("w-1", wall("w-1", s2));
        t.invalidate("w-1");

        r.reset();
        t.tickOnceForTest("w-1");   // pos=0（nanoClock 未推进）→ 渲染新 state，x=基值 500
        assertEquals(500, r.lastX(), "invalidate 后 tick 重载新 state → x=新基值 500");
    }

    @Test
    void invalidate_wallRemovedDeregisters() {
        FakeWallSource src = new FakeWallSource();
        FakeRenderer r = new FakeRenderer();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, r);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        t.setNanoClock(clock::get);
        t.play("w-1", "tl-1");
        assertEquals(1, t.registeredCount());

        src.walls.remove("w-1");   // wall 删除
        t.invalidate("w-1");
        r.reset();
        t.tickOnceForTest("w-1");
        assertEquals(0, t.registeredCount(), "wall 移除 + tick → 自动注销");
        assertEquals(0, r.renderCount.get(), "wall 没了不渲染");
    }

    @Test
    void invalidate_timelineRemovedDeregisters() {
        FakeWallSource src = new FakeWallSource();
        FakeRenderer r = new FakeRenderer();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, r);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        t.setNanoClock(clock::get);
        t.play("w-1", "tl-1");

        // 同 wall 但 state 已无 tl-1（timeline 删除）
        TextElement keep = new TextElement("e-1", 0, 0, 100, 50, 0, false, true,
                "hi", "inter", 24, "#000000", "left", 0f, 1.2f, false, null,
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null, null);
        List<Element> els = new ArrayList<>(List.of(keep));
        Layer layer = new Layer("l-1", "L", true, false, 1.0f, BlendMode.NORMAL, null, els);
        List<Layer> layers = new ArrayList<>(List.of(layer));
        ProjectState noTl = new ProjectState(2L,
                new ProjectState.Canvas(2, 2, Fill.solid("#FFFFFF")),
                null, layers, "l-1", new ProjectState.History(0, 0),
                null, null, null);   // 无 timelines
        src.walls.put("w-1", wall("w-1", noTl));
        t.invalidate("w-1");
        t.tickOnceForTest("w-1");
        assertEquals(0, t.registeredCount(), "timeline 从 state 移除 → tick 自动注销");
    }

    // ---------- autoRegisterAll / refreshAutoPlay ----------

    @Test
    void autoRegisterAll_registersLoopOnly() {
        FakeWallSource src = new FakeWallSource();
        src.walls.put("w-loop", wall("w-loop", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        src.walls.put("w-once", wall("w-once", stateWith("e-1", LoopMode.ONCE, "tl-1", 1)));
        // 无 activeTimelineId 的 wall
        ProjectState noActive = new ProjectState(1L,
                new ProjectState.Canvas(2, 2, Fill.solid("#FFFFFF")),
                null, new ArrayList<>(List.of(new Layer("l-1", "L", true, false, 1.0f, BlendMode.NORMAL, null, new ArrayList<>()))),
                "l-1", new ProjectState.History(0, 0), null, null, null);
        src.walls.put("w-static", wall("w-static", noActive));

        AnimationTicker t = newTicker(src, new FakeRenderer());
        int n = t.autoRegisterAll(null);
        assertEquals(1, n, "仅 LOOP+activeTimelineId 自动注册");
        assertTrue(t.isWallAnimating("w-loop"), "LOOP 墙自动播");
        assertFalse(t.isWallAnimating("w-once"), "ONCE 墙不自动播");
        assertFalse(t.isWallAnimating("w-static"), "无 activeTimelineId 不自动播");
    }

    @Test
    void refreshAutoPlay_loopAutoPlays() {
        FakeWallSource src = new FakeWallSource();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, new FakeRenderer());
        t.refreshAutoPlay("w-1");
        assertTrue(t.isWallAnimating("w-1"), "LOOP + activeTimelineId + 未注册 → 自动播");
    }

    @Test
    void refreshAutoPlay_onceDoesNotAutoPlay() {
        FakeWallSource src = new FakeWallSource();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.ONCE, "tl-1", 1)));
        AnimationTicker t = newTicker(src, new FakeRenderer());
        t.refreshAutoPlay("w-1");
        assertFalse(t.isWallAnimating("w-1"), "ONCE 不自动播");
        assertEquals(0, t.registeredCount());
    }

    @Test
    void refreshAutoPlay_missingWallStops() {
        FakeWallSource src = new FakeWallSource();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, new FakeRenderer());
        t.play("w-1", "tl-1");
        assertEquals(1, t.registeredCount());
        src.walls.remove("w-1");
        t.refreshAutoPlay("w-1");
        assertEquals(0, t.registeredCount(), "wall 不存在 → 注销");
    }

    // ---------- shutdown 幂等 + 后续拒绝 ----------

    @Test
    void shutdown_idempotentAndRejectsSubsequentPlay() {
        FakeWallSource src = new FakeWallSource();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, new FakeRenderer());
        t.play("w-1", "tl-1");
        t.shutdown();
        t.shutdown();   // 幂等
        assertEquals(AnimationTicker.Result.WALL_NOT_FOUND, t.play("w-1", "tl-1"),
                "shutdown 后 play 拒（WALL_NOT_FOUND）");
        assertFalse(t.isWallAnimating("w-1"), "shutdown 后 gate 关闭");
    }

    @Test
    void seek_unknownWallNotFound() {
        AnimationTicker t = newTicker(new FakeWallSource(), new FakeRenderer());
        assertEquals(AnimationTicker.Result.WALL_NOT_FOUND, t.seek("nope", "tl-1", 0L),
                "seek 不存在 wall → WALL_NOT_FOUND");
    }

    @Test
    void stopWall_idempotent() {
        FakeWallSource src = new FakeWallSource();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, new FakeRenderer());
        t.play("w-1", "tl-1");
        t.stopWall("w-1");
        assertEquals(0, t.registeredCount());
        t.stopWall("w-1");   // 幂等
        assertEquals(0, t.registeredCount());
        assertNull(null);   // sanity
    }

    // ---------- P2 审查修复回归（确定性语义面；真并发交错由锁内成员资格复核结构性排除） ----------

    /** 审查 #0：stopWall 后 play 必须建全新 entry 并恢复 gate 可见性（不会粘在被摘除的旧 entry 上）。 */
    @Test
    void playAfterStopCreatesFreshEntry() {
        FakeWallSource src = new FakeWallSource();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, new FakeRenderer());

        t.play("w-1", "tl-1");
        t.stopWall("w-1");
        assertFalse(t.isWallAnimating("w-1"));
        assertEquals(AnimationTicker.Result.OK, t.play("w-1", "tl-1"),
                "stop 后再 play 建新 entry");
        assertTrue(t.isWallAnimating("w-1"), "gate 对新 entry 可见");
        assertEquals(1, t.registeredCount());
    }

    /** 审查 #1：play（非 resume）不直接动 diff，置标志由下一 tick 在 Ticker 线程 reset。 */
    @Test
    void replayResetsDiffOnNextTickNotInline() {
        FakeWallSource src = new FakeWallSource();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        // 捕获 renderFrame 进入时 diff.lastFrames 的状态，并模拟生产实现写入基准帧
        final boolean[] diffWasNullOnEntry = new boolean[1];
        AnimationTicker.FrameRenderer probe = (wall, frame, diff, force) -> {
            diffWasNullOnEntry[0] = diff.lastFrames == null;
            diff.lastFrames = new byte[][] { new byte[] {1} };   // 模拟 projector 写基准
            return 0;
        };
        AnimationTicker t = new AnimationTicker(src, probe, 60, LOG);
        this.ticker = t;

        t.play("w-1", "tl-1");
        t.tickOnceForTest("w-1");
        assertTrue(diffWasNullOnEntry[0], "首帧 diff 应为空基准");

        t.tickOnceForTest("w-1");
        assertFalse(diffWasNullOnEntry[0], "第二帧沿用基准（未被控制线程清掉）");

        // 重播（非 resume：播放中再 play 同 timeline）→ 标志置位 → 下一 tick 才 reset
        t.play("w-1", "tl-1");
        t.tickOnceForTest("w-1");
        assertTrue(diffWasNullOnEntry[0], "重播后的首帧 diff 由 Ticker 线程 reset（全量基准重建）");
    }

    /** 审查 #13：暂停态 entry 的 timeline 被删除后，invalidate 当场清理 stale 条目。 */
    @Test
    void invalidatePausedEntryWithDeletedTimelineRemoves() {
        FakeWallSource src = new FakeWallSource();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, new FakeRenderer());
        t.play("w-1", "tl-1");
        t.pause("w-1");
        assertEquals(1, t.registeredCount());

        // 模拟编辑删除 timeline 后持久化（state 不再含 tl-1）→ invalidate
        ProjectState noTl = new ProjectState(2L,
                new ProjectState.Canvas(2, 2, Fill.solid("#FFFFFF")),
                null, new ArrayList<>(List.of(
                        new Layer("l-1", "L", true, false, 1.0f, BlendMode.NORMAL, null,
                                new ArrayList<>()))),
                "l-1", new ProjectState.History(0, 0), null, null, null);
        src.walls.put("w-1", wall("w-1", noTl));
        t.invalidate("w-1");
        assertEquals(0, t.registeredCount(), "暂停态 stale 条目被当场清理");
    }

    /** 审查 #3/#6/#8：gate 回调 onReactiveYield = invalidate（被吸收的 reactive 意图转重载+全量补发）。 */
    @Test
    void onReactiveYieldInvalidates() {
        FakeWallSource src = new FakeWallSource();
        src.walls.put("w-1", wall("w-1", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        FakeRenderer r = new FakeRenderer();
        AnimationTicker t = newTicker(src, r);
        t.play("w-1", "tl-1");

        // 改 wall state（fps 仍同）模拟编辑落库，再走 gate 回调
        src.walls.put("w-1", wall("w-1", stateWith("e-2", LoopMode.LOOP, "tl-1", 1)));
        t.onReactiveYield("w-1");
        r.reset();
        t.tickOnceForTest("w-1");
        assertEquals(1, r.renderCount.get());
        // 重载后帧里是新 state 的元素（e-2 轨道）——旧 e-1 不在
        assertEquals(Integer.MIN_VALUE, r.lastX(), "重载后渲染新 state（e-1 已不存在）");
    }

    // ---------- P2-6：clearStaticDiff 线程契约（孤儿 FrameDiff 泄漏） ----------

    /**
     * P2-6 回归：补间末帧序列「先 renderStatic（异步投 Ticker 线程 computeIfAbsent 建 diff）
     * 再 clearStaticDiff」后，staticDiffs <b>不</b>得残留该 wall 条目。
     *
     * <p>旧实现 clearStaticDiff 在调用线程同步 remove → 排在异步 renderStatic 的 computeIfAbsent
     * <b>之前</b>执行 → 该条目永不被清（每个静态墙补间完泄漏一条 byte[mapCount][16384]）。
     * 修复后 clearStaticDiff 同走 scheduler.execute，单线程 FIFO 保证 remove 排在 computeIfAbsent
     * 之后，孤儿条目被关死。</p>
     */
    @Test
    void clearStaticDiffAfterRenderStatic_leavesNoOrphan() {
        FakeWallSource src = new FakeWallSource();
        FakeRenderer r = new FakeRenderer();   // viewers=true → renderStatic 会 computeIfAbsent 建 diff
        // 静态墙：源里有 wall，但不 play（renderStatic 内 entries.containsKey 为 false 才会建 diff）
        src.walls.put("w-static", wall("w-static", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, r);

        ProjectState frame = stateWith("e-1", LoopMode.LOOP, "tl-1", 1);

        // 补间末帧顺序：renderStatic 投递在前，clearStaticDiff 投递在后
        t.renderStatic("w-static", frame);
        t.clearStaticDiff("w-static");

        // 等 Ticker 线程把两道 trampoline 任务排空
        t.flushSchedulerForTest();

        assertFalse(t.hasStaticDiffForTest("w-static"),
                "clearStaticDiff 应排在 renderStatic 的 computeIfAbsent 之后 → 无孤儿条目");
        assertEquals(0, t.staticDiffCountForTest(), "staticDiffs 应彻底清空");
    }

    /**
     * P2-6 子项：renderStatic 单独投递（无后续 clear）确实会建一条 diff——
     * 反向锚定「上面那条测试的 0 残留是 clear 真生效，而非 renderStatic 根本没建 diff」。
     */
    @Test
    void renderStaticAlone_createsStaticDiff() {
        FakeWallSource src = new FakeWallSource();
        FakeRenderer r = new FakeRenderer();   // viewers=true
        src.walls.put("w-static", wall("w-static", stateWith("e-1", LoopMode.LOOP, "tl-1", 1)));
        AnimationTicker t = newTicker(src, r);

        t.renderStatic("w-static", stateWith("e-1", LoopMode.LOOP, "tl-1", 1));
        t.flushSchedulerForTest();

        assertTrue(t.hasStaticDiffForTest("w-static"),
                "renderStatic（有观察者）应 computeIfAbsent 建一条 staticDiff");

        // 再 clearStaticDiff → 清掉
        t.clearStaticDiff("w-static");
        t.flushSchedulerForTest();
        assertFalse(t.hasStaticDiffForTest("w-static"), "clearStaticDiff 后该条目被移除");
    }
}
