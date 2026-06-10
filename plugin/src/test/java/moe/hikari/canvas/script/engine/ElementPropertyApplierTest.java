package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.render.AnimationTicker;
import moe.hikari.canvas.session.WallKey;
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
import moe.hikari.canvas.storage.Database;
import moe.hikari.canvas.storage.MigrationRunner;
import moe.hikari.canvas.storage.WallRepo;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0-P2-4：{@link ElementPropertyApplier} 双路径。
 *
 * <p>headless（路径 B）走<b>真</b> WallRepo + tmpdir SQLite 全链（落库后重读断言）；
 * 路径 A 用 fake {@link ElementPropertyApplier.SessionPatchApplier}（真 SessionManager
 * 装配链过重——confirm 需 MapPool/WallResolver/主线程，A 路径真链留批次 3 集成测试）。</p>
 */
class ElementPropertyApplierTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final AtomicInteger ORIGIN = new AtomicInteger();

    private Path tmpDir;
    private Database database;
    private WallRepo wallRepo;
    private FakeTicker ticker;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-applier-test-");
        database = new Database(LOG, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), LOG).run();
        wallRepo = new WallRepo(LOG, database.jdbi());
        ticker = new FakeTicker();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) database.close();
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var s = Files.walk(tmpDir)) {
                s.sorted(Comparator.reverseOrder()).map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }

    // ---------- fixtures ----------

    /** 单 text 元素工程；withTimeline 时挂一条 LOOP timeline 并设 activeTimelineId。 */
    private static ProjectState stateWithText(String elementId, boolean withTimeline) {
        TextElement t = new TextElement(elementId, 10, 20, 100, 50, 0, false, true,
                "hello", "inter", 24, "#000000", "left", 0f, 1.2f, false, null,
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null, null);
        Layer layer = new Layer("l-1", "L", true, false, 1.0f, BlendMode.NORMAL, null,
                new ArrayList<>(List.of(t)));
        List<Timeline> timelines = null;
        String activeId = null;
        if (withTimeline) {
            Map<String, List<Keyframe>> tracks = new LinkedHashMap<>();
            tracks.put(elementId, List.of(
                    new Keyframe("k0", "x", 0, KfValue.of(0.0), Easing.LINEAR),
                    new Keyframe("k1", "x", 1000, KfValue.of(100.0), Easing.LINEAR)));
            timelines = List.of(new Timeline("tl-1", "T", 1000, 20, LoopMode.LOOP,
                    TriggerConfig.MANUAL, tracks));
            activeId = "tl-1";
        }
        return new ProjectState(1L,
                new ProjectState.Canvas(2, 2, Fill.solid("#FFFFFF")),
                null, new ArrayList<>(List.of(layer)), "l-1",
                new ProjectState.History(0, 0), timelines, activeId);
    }

    private String createWall(ProjectState state) {
        WallKey key = new WallKey("world", ORIGIN.incrementAndGet(), 64, 0, BlockFace.NORTH);
        return wallRepo.createWithMapIds(key, state, List.of(0), 2, 2,
                UUID.randomUUID(), "tester");
    }

    private ElementPropertyApplier headlessApplier() {
        return new ElementPropertyApplier(null, wallRepo, ticker, LOG);
    }

    private static TextElement findText(ProjectState st, String id) {
        for (Layer l : st.layers()) {
            for (Element e : l.elements()) {
                if (e.id().equals(id)) return (TextElement) e;
            }
        }
        throw new AssertionError("element not found: " + id);
    }

    // ---------- buildPatch 值转换 ----------

    @Test
    void buildPatch_numericConversions() {
        assertEquals(Map.of("x", 4), ElementPropertyApplier.buildPatch("x", "3.7"));
        assertEquals(Map.of("y", -5), ElementPropertyApplier.buildPatch("y", "-5"));
        assertEquals(Map.of("rotation", 0), ElementPropertyApplier.buildPatch("rotation", "abc"),
                "非数值 → 0（StrictNumber 链终点语义）");
        assertEquals(Map.of("opacity", 1.0), ElementPropertyApplier.buildPatch("opacity", "3"),
                "opacity 钳 [0,1]");
        assertEquals(Map.of("opacity", 0.5), ElementPropertyApplier.buildPatch("opacity", "0.5"));
        assertEquals(Map.of("text", "hi"), ElementPropertyApplier.buildPatch("text", "hi"));
        assertEquals(Map.of("fill", "#FF0000"), ElementPropertyApplier.buildPatch("fill", "#FF0000"));
        assertEquals(Map.of("fill", "#FF000080"),
                ElementPropertyApplier.buildPatch("fill", "#FF000080"), "#RRGGBBAA 合法");
    }

    @Test
    void buildPatch_rejectsBadFillAndUnknownProperty() {
        assertThrows(IllegalArgumentException.class,
                () -> ElementPropertyApplier.buildPatch("fill", "red"));
        assertThrows(IllegalArgumentException.class,
                () -> ElementPropertyApplier.buildPatch("fill", "rgb(1,2,3)"));
        assertThrows(IllegalArgumentException.class,
                () -> ElementPropertyApplier.buildPatch("fill", null));
        assertThrows(IllegalArgumentException.class,
                () -> ElementPropertyApplier.buildPatch("fontSize", "12"), "白名单外属性拒");
        assertThrows(IllegalArgumentException.class,
                () -> ElementPropertyApplier.buildPatch(null, "1"));
    }

    // ---------- 路径 B：headless 全链 ----------

    @Test
    void headless_textChange_persistsToDb() {
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = headlessApplier().apply(wallId, "actions/0", "e-1", "text", "world");
        assertEquals("ok", step.result(), () -> "应成功: " + step.detail());

        ProjectState reloaded = wallRepo.loadById(wallId).orElseThrow().state();
        assertEquals("world", findText(reloaded, "e-1").text(), "落库后重读生效");
    }

    @Test
    void headless_numericChange_persistsToDb() {
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = headlessApplier().apply(wallId, "b", "e-1", "x", "42.6");
        assertEquals("ok", step.result());
        assertEquals(43, findText(wallRepo.loadById(wallId).orElseThrow().state(), "e-1").x());
    }

    @Test
    void headless_staticWall_doesNotTouchTicker() {
        String wallId = createWall(stateWithText("e-1", false));
        headlessApplier().apply(wallId, "b", "e-1", "text", "x");
        assertEquals(0, ticker.invalidates.size(), "静态墙不 invalidate");
        assertEquals(0, ticker.refreshes.size(), "静态墙不 refreshAutoPlay");
    }

    @Test
    void headless_animatingWall_invalidates() {
        String wallId = createWall(stateWithText("e-1", true));
        ticker.animating = true;
        headlessApplier().apply(wallId, "b", "e-1", "text", "x");
        assertEquals(List.of(wallId), ticker.invalidates, "在播 → invalidate（persistWall 链）");
        assertEquals(0, ticker.refreshes.size());
    }

    @Test
    void headless_idleWallWithActiveTimeline_refreshesAutoPlay() {
        String wallId = createWall(stateWithText("e-1", true));
        ticker.animating = false;
        headlessApplier().apply(wallId, "b", "e-1", "text", "x");
        assertEquals(0, ticker.invalidates.size());
        assertEquals(List.of(wallId), ticker.refreshes,
                "没在播但有 activeTimeline → refreshAutoPlay（persistWall 链）");
    }

    @Test
    void headless_elementNotFound_errorStep() {
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = headlessApplier().apply(wallId, "b", "e-MISSING", "text", "x");
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("INVALID_ELEMENT"), step.detail());
        // 未落库：原文本不变
        assertEquals("hello", findText(wallRepo.loadById(wallId).orElseThrow().state(), "e-1").text());
    }

    @Test
    void headless_wallNotFound_errorStep() {
        TraceStep step = headlessApplier().apply("w-nope", "b", "e-1", "text", "x");
        assertEquals("error", step.result());
    }

    @Test
    void headless_fillOnTextElement_errorStep_chainSafe() {
        // text 元素无 fill 字段 → EditSession 校验拒（与编辑器路径同一语义权威）
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = headlessApplier().apply(wallId, "b", "e-1", "fill", "#FF0000");
        assertEquals("error", step.result());
        assertNotEquals(null, step.detail());
    }

    @Test
    void headless_badFillFormat_errorStep_beforeDb() {
        TraceStep step = headlessApplier().apply("w-any", "b", "e-1", "fill", "not-a-color");
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("#RRGGBB"), step.detail());
    }

    @Test
    void headless_repoMissing_errorStep() {
        ElementPropertyApplier a = new ElementPropertyApplier(null, null, null, LOG);
        TraceStep step = a.apply("w-1", "b", "e-1", "text", "x");
        assertEquals("error", step.result());
    }

    // ---------- 路径 A：session seam ----------

    @Test
    void sessionApplied_skipsHeadless() {
        List<Map<String, Object>> seen = new ArrayList<>();
        ElementPropertyApplier a = new ElementPropertyApplier((w, e, p) -> {
            seen.add(p);
            return ElementPropertyApplier.SessionOutcome.applied();
        }, wallRepo, ticker, LOG);
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = a.apply(wallId, "b", "e-1", "text", "viaSession");
        assertEquals("ok", step.result());
        assertEquals(1, seen.size());
        assertEquals("hello",
                findText(wallRepo.loadById(wallId).orElseThrow().state(), "e-1").text(),
                "session 路径吞掉后不再走 headless 落库");
    }

    @Test
    void sessionFailed_errorStep_noHeadlessFallback() {
        ElementPropertyApplier a = new ElementPropertyApplier(
                (w, e, p) -> ElementPropertyApplier.SessionOutcome.failed("LAYER_LOCKED: x"),
                wallRepo, ticker, LOG);
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = a.apply(wallId, "b", "e-1", "text", "x");
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("LAYER_LOCKED"));
        assertEquals("hello",
                findText(wallRepo.loadById(wallId).orElseThrow().state(), "e-1").text(),
                "session 拒绝不回退 headless（避免绕过编辑器语义）");
    }

    @Test
    void sessionAbsent_fallsThroughToHeadless() {
        ElementPropertyApplier a = new ElementPropertyApplier(
                (w, e, p) -> ElementPropertyApplier.SessionOutcome.noSession(),
                wallRepo, ticker, LOG);
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = a.apply(wallId, "b", "e-1", "text", "headless");
        assertEquals("ok", step.result());
        assertEquals("headless",
                findText(wallRepo.loadById(wallId).orElseThrow().state(), "e-1").text());
    }

    @Test
    void sessionApplierThrows_errorStep_chainSafe() {
        ElementPropertyApplier a = new ElementPropertyApplier(
                (w, e, p) -> { throw new IllegalStateException("boom"); },
                wallRepo, ticker, LOG);
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = a.apply(wallId, "b", "e-1", "text", "x");
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("boom"));
    }

    // ---------- fake ----------

    /** 可配置结果 + 记录调用的 TickerControl fake（ActionExecutorTest 共用）。 */
    static final class FakeTicker implements TickerControl {
        boolean animating;
        boolean throwOnPlay;
        AnimationTicker.Result playResult = AnimationTicker.Result.OK;
        AnimationTicker.Result seekResult = AnimationTicker.Result.OK;
        final List<String> plays = new ArrayList<>();
        final List<String> pauses = new ArrayList<>();
        final List<Long> seeks = new ArrayList<>();
        final List<String> invalidates = new ArrayList<>();
        final List<String> refreshes = new ArrayList<>();

        @Override
        public AnimationTicker.Result play(String wallId, String timelineId) {
            if (throwOnPlay) throw new IllegalStateException("ticker boom");
            plays.add(wallId + ":" + timelineId);
            return playResult;
        }

        @Override
        public void pause(String wallId) {
            pauses.add(wallId);
        }

        @Override
        public AnimationTicker.Result seek(String wallId, String timelineId, long atMs) {
            seeks.add(atMs);
            return seekResult;
        }

        @Override
        public boolean isWallAnimating(String wallId) {
            return animating;
        }

        @Override
        public void invalidate(String wallId) {
            invalidates.add(wallId);
        }

        @Override
        public void refreshAutoPlay(String wallId) {
            refreshes.add(wallId);
        }
    }
}
