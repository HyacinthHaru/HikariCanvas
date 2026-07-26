package ac.haru.hikaricanvas.script.engine;

import ac.haru.hikaricanvas.render.AnimationTicker;
import ac.haru.hikaricanvas.session.WallKey;
import ac.haru.hikaricanvas.state.BlendMode;
import ac.haru.hikaricanvas.state.Easing;
import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.Fill;
import ac.haru.hikaricanvas.state.Keyframe;
import ac.haru.hikaricanvas.state.KfValue;
import ac.haru.hikaricanvas.state.Layer;
import ac.haru.hikaricanvas.state.LoopMode;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.RenderMode;
import ac.haru.hikaricanvas.state.TextElement;
import ac.haru.hikaricanvas.state.Timeline;
import ac.haru.hikaricanvas.state.TriggerConfig;
import ac.haru.hikaricanvas.storage.Database;
import ac.haru.hikaricanvas.storage.MigrationRunner;
import ac.haru.hikaricanvas.storage.WallRepo;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
                new ProjectState.History(0, 0), timelines, activeId, null);
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
    void buildPatch_supportsColor() {
        assertEquals(Map.of("color", "#FF0000"),
                ElementPropertyApplier.buildPatch("color", "#FF0000"));
        assertEquals(Map.of("color", "#FF000080"),
                ElementPropertyApplier.buildPatch("color", "#FF000080"), "#RRGGBBAA 合法");
        assertThrows(IllegalArgumentException.class,
                () -> ElementPropertyApplier.buildPatch("color", "red"));
        assertThrows(IllegalArgumentException.class,
                () -> ElementPropertyApplier.buildPatch("color", null));
    }

    /**
     * 结构守卫：buildPatch 的 switch 必须覆盖
     * {@link ac.haru.hikaricanvas.script.ScriptRuleValidator#ELEMENT_PROPERTIES} 全集。
     *
     * <p>校验层放行、这里却抛 unsupported 的属性，会让积木保存成功但运行时恒返 error step，
     * 且 TweenBlock 末帧无法落库（clearStaticDiff 后视觉弹回原值）。{@code color} 就是这样
     * 漏掉的——白名单有它、buildPatch 没有，而补间测试注的是 fake applyFn 从未打通真实
     * applyMany，于是一直没被发现。</p>
     */
    @Test
    void buildPatch_coversEveryWhitelistedProperty() {
        for (String prop : ac.haru.hikaricanvas.script.ScriptRuleValidator.ELEMENT_PROPERTIES) {
            String sample = switch (prop) {
                case "fill", "color" -> "#123456";
                case "text" -> "hi";
                case "opacity" -> "0.5";
                default -> "1";
            };
            assertDoesNotThrow(() -> ElementPropertyApplier.buildPatch(prop, sample),
                    () -> "ELEMENT_PROPERTIES 含 '" + prop + "' 但 buildPatch 无对应 case");
        }
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

    /**
     * 端到端补上 buildPatch → applyMany → EditSession.applyTextPatch → DB 这条真链。
     * 此前只有 fake applyFn 的补间测试，真链从未被打通（这是 color 漏 case 的根因）。
     */
    @Test
    void headless_colorChange_persistsToDb() {
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = headlessApplier().apply(wallId, "actions/0", "e-1", "color", "#00FF00");
        assertEquals("ok", step.result(), () -> "setColor 积木 / color 补间末帧应落库: " + step.detail());
        assertEquals("#00FF00",
                findText(wallRepo.loadById(wallId).orElseThrow().state(), "e-1").color());
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

    /**
     * 静态墙（无活跃 session、无时间轴）被脚本改完必须补一次重画——否则数据库变了、
     * 游戏内地图还是旧画面，要等重启 / 有人开编辑器才对上。
     */
    @Test
    void headless_staticWall_repaintsViaRenderStatic() {
        String wallId = createWall(stateWithText("e-1", false));
        headlessApplier().apply(wallId, "b", "e-1", "text", "changed");
        assertEquals(List.of(wallId), ticker.renderStatics, "静态墙落库后补一次 renderStatic");
        assertEquals(List.of(wallId), ticker.clearedStaticDiffs, "渲完清 per-wall diff，不留缓存");
        assertEquals("changed", findText(ticker.lastStaticFrame, "e-1").text(),
                "推给 Ticker 的是改后的 state，不是旧快照");
    }

    /** clone / delete 等结构变更走同一条 runHeadless 骨架，静态墙同样要补画。 */
    @Test
    void headless_staticWall_cloneAndDeleteAlsoRepaint() {
        String wallId = createWall(stateWithText("e-1", false));
        headlessApplier().applyClone(wallId, "b", "e-1", 5, 5);
        assertEquals(1, ticker.renderStatics.size(), "clone 后补画");
        headlessApplier().applyDelete(wallId, "b", "e-1");
        assertEquals(2, ticker.renderStatics.size(), "delete 后补画");
    }

    @Test
    void headless_animatingWall_doesNotRenderStatic() {
        String wallId = createWall(stateWithText("e-1", true));
        ticker.animating = true;
        headlessApplier().apply(wallId, "b", "e-1", "text", "x");
        assertEquals(0, ticker.renderStatics.size(), "在播的墙交给 Ticker 出帧，不抢 renderStatic");
    }

    @Test
    void headless_idleWallWithActiveTimeline_doesNotRenderStatic() {
        String wallId = createWall(stateWithText("e-1", true));
        ticker.animating = false;
        headlessApplier().apply(wallId, "b", "e-1", "text", "x");
        assertEquals(0, ticker.renderStatics.size(), "有时间轴走 refreshAutoPlay，不额外 renderStatic");
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

    // ---------- 0.7.1：applyMany（批量设属性） ----------

    @Test
    void applyMany_headless_setsMultipleProps() {
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = headlessApplier().applyMany(wallId, "b", "e-1",
                Map.of("x", "128", "y", "64"));
        assertEquals("ok", step.result(), () -> "应成功: " + step.detail());
        TextElement el = findText(wallRepo.loadById(wallId).orElseThrow().state(), "e-1");
        assertEquals(128, el.x());
        assertEquals(64, el.y());
    }

    @Test
    void applyMany_emptyPatch_errorStep() {
        TraceStep step = headlessApplier().applyMany("w-any", "b", "e-1", Map.of());
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("patch"), step.detail());
    }

    @Test
    void applyMany_badProperty_errorStep_beforeDb() {
        // 非白名单属性 → buildPatch 抛 → error step（不落库）
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = headlessApplier().applyMany(wallId, "b", "e-1",
                Map.of("x", "5", "fontSize", "12"));
        assertEquals("error", step.result());
        // 原 x 不变（整批拒，无部分落地）
        assertEquals(10, findText(wallRepo.loadById(wallId).orElseThrow().state(), "e-1").x());
    }

    @Test
    void applyMany_missingElementId_errorStep() {
        TraceStep step = headlessApplier().applyMany("w-any", "b", "", Map.of("x", "1"));
        assertEquals("error", step.result());
    }

    @Test
    void applyMany_sessionPath_mergesPatch() {
        List<Map<String, Object>> seen = new ArrayList<>();
        ElementPropertyApplier a = new ElementPropertyApplier((w, e, p) -> {
            seen.add(p);
            return ElementPropertyApplier.SessionOutcome.applied();
        }, wallRepo, ticker, LOG);
        TraceStep step = a.applyMany("w-1", "b", "e-1", Map.of("x", "9", "y", "7"));
        assertEquals("ok", step.result());
        assertEquals(1, seen.size());
        assertEquals(9, seen.get(0).get("x"), "x 合进单一 patch");
        assertEquals(7, seen.get(0).get("y"), "y 合进单一 patch");
    }

    // ---------- 0.7.1：applyNudge（相对移动） ----------

    @Test
    void applyNudge_headless_readsCurrentPlusDelta() {
        // fixture x=10,y=20；nudge(+5,-3) → x=15,y=17
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = headlessApplier().applyNudge(wallId, "b", "e-1", 5.0, -3.0);
        assertEquals("ok", step.result(), () -> "应成功: " + step.detail());
        TextElement el = findText(wallRepo.loadById(wallId).orElseThrow().state(), "e-1");
        assertEquals(15, el.x());
        assertEquals(17, el.y());
    }

    @Test
    void applyNudge_roundsFractionalDelta() {
        // x=10,y=20；nudge(+2.6,-0.4) → round → +3,-0 → x=13,y=20
        String wallId = createWall(stateWithText("e-1", false));
        headlessApplier().applyNudge(wallId, "b", "e-1", 2.6, -0.4);
        TextElement el = findText(wallRepo.loadById(wallId).orElseThrow().state(), "e-1");
        assertEquals(13, el.x());
        assertEquals(20, el.y());
    }

    @Test
    void applyNudge_nonFiniteDelta_errorStep() {
        TraceStep step = headlessApplier().applyNudge("w-any", "b", "e-1", Double.NaN, 1.0);
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("finite"), step.detail());
    }

    @Test
    void applyNudge_elementNotFound_errorStep() {
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = headlessApplier().applyNudge(wallId, "b", "e-MISSING", 1.0, 1.0);
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("element not found"), step.detail());
    }

    @Test
    void applyNudge_sessionPath_usesNudgeSeam() {
        List<int[]> seen = new ArrayList<>();
        ElementPropertyApplier a = new ElementPropertyApplier(
                new ElementPropertyApplier.SessionPatchApplier() {
                    @Override
                    public ElementPropertyApplier.SessionOutcome apply(
                            String w, String e, Map<String, Object> p) {
                        throw new AssertionError("nudge 不应走 apply()");
                    }

                    @Override
                    public ElementPropertyApplier.SessionOutcome nudge(
                            String w, String e, int dx, int dy) {
                        seen.add(new int[]{dx, dy});
                        return ElementPropertyApplier.SessionOutcome.applied();
                    }
                }, wallRepo, ticker, LOG);
        TraceStep step = a.applyNudge("w-1", "b", "e-1", 5.4, -3.6);
        assertEquals("ok", step.result());
        assertEquals(1, seen.size());
        assertEquals(5, seen.get(0)[0], "dx round");
        assertEquals(-4, seen.get(0)[1], "dy round");
    }

    @Test
    void applyNudge_sessionNoSession_fallsThroughHeadless() {
        // 无活跃 session（apply + 默认 nudge 都返 noSession）→ nudge 走 headless 读改写，
        // 其内部 applyMany 也因 noSession 落 DB（x=10 +1 → 11）
        String wallId = createWall(stateWithText("e-1", false));
        ElementPropertyApplier a = new ElementPropertyApplier(
                (w, e, p) -> ElementPropertyApplier.SessionOutcome.noSession(),
                wallRepo, ticker, LOG);
        TraceStep step = a.applyNudge(wallId, "b", "e-1", 1.0, 1.0);
        assertEquals("ok", step.result());
        assertEquals(11, findText(wallRepo.loadById(wallId).orElseThrow().state(), "e-1").x());
    }

    // ---------- 0.7.2-P2：applyClone / applyDelete（headless 双路径） ----------

    private static int elementCount(ProjectState st) {
        int n = 0;
        for (Layer l : st.layers()) n += l.elements().size();
        return n;
    }

    @Test
    void applyClone_headless_addsElement_withOffset_andNewId() {
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = headlessApplier().applyClone(wallId, "b", "e-1", 5, 7);
        assertEquals("ok", step.result(), () -> "应成功: " + step.detail());
        ProjectState reloaded = wallRepo.loadById(wallId).orElseThrow().state();
        assertEquals(2, elementCount(reloaded), "落库后元素 +1");
        // 找到不是 e-1 的那个（副本）
        TextElement copy = null;
        for (Layer l : reloaded.layers()) {
            for (Element e : l.elements()) {
                if (!e.id().equals("e-1")) copy = (TextElement) e;
            }
        }
        assertEquals(15, copy.x(), "x 偏移生效（10+5）");
        assertEquals(27, copy.y(), "y 偏移生效（20+7）");
        assertNotEquals("e-1", copy.id(), "副本新 id");
    }

    @Test
    void applyClone_headless_quotaExceeded_errorStep() {
        String wallId = createWall(stateWithText("e-1", false));
        ElementPropertyApplier a = headlessApplier();
        a.setMaxElementsPerWall(1); // 已有 1 个元素 = 上限
        TraceStep step = a.applyClone(wallId, "b", "e-1", 1, 1);
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("QUOTA_EXCEEDED"), step.detail());
        assertEquals(1, elementCount(wallRepo.loadById(wallId).orElseThrow().state()),
                "拒绝后未落库");
    }

    @Test
    void applyClone_headless_quotaUnset_allowsClone() {
        String wallId = createWall(stateWithText("e-1", false));
        // 默认 maxElementsPerWall=0（未注入）→ 不限
        TraceStep step = headlessApplier().applyClone(wallId, "b", "e-1", 0, 0);
        assertEquals("ok", step.result());
        assertEquals(2, elementCount(wallRepo.loadById(wallId).orElseThrow().state()));
    }

    @Test
    void applyClone_headless_missingElement_errorStep() {
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = headlessApplier().applyClone(wallId, "b", "e-MISSING", 1, 1);
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("INVALID_ELEMENT"), step.detail());
        assertEquals(1, elementCount(wallRepo.loadById(wallId).orElseThrow().state()));
    }

    @Test
    void applyClone_emptyElementId_errorStep() {
        TraceStep step = headlessApplier().applyClone("w-any", "b", "", 1, 1);
        assertEquals("error", step.result());
    }

    @Test
    void applyClone_animatingWall_invalidates() {
        String wallId = createWall(stateWithText("e-1", true));
        ticker.animating = true;
        headlessApplier().applyClone(wallId, "b", "e-1", 1, 1);
        assertEquals(List.of(wallId), ticker.invalidates, "在播 → invalidate（persistWall 链）");
    }

    @Test
    void applyDelete_headless_removesElement() {
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = headlessApplier().applyDelete(wallId, "b", "e-1");
        assertEquals("ok", step.result(), () -> "应成功: " + step.detail());
        assertEquals(0, elementCount(wallRepo.loadById(wallId).orElseThrow().state()),
                "落库后元素 -1");
    }

    @Test
    void applyDelete_headless_missingElement_errorStep() {
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = headlessApplier().applyDelete(wallId, "b", "e-MISSING");
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("INVALID_ELEMENT"), step.detail());
        assertEquals(1, elementCount(wallRepo.loadById(wallId).orElseThrow().state()),
                "拒绝后元素不变");
    }

    @Test
    void applyDelete_emptyElementId_errorStep() {
        TraceStep step = headlessApplier().applyDelete("w-any", "b", "");
        assertEquals("error", step.result());
    }

    @Test
    void applyDelete_wallNotFound_errorStep() {
        TraceStep step = headlessApplier().applyDelete("w-nope", "b", "e-1");
        assertEquals("error", step.result());
    }

    @Test
    void applyDelete_repoMissing_errorStep() {
        ElementPropertyApplier a = new ElementPropertyApplier(null, null, null, LOG);
        TraceStep step = a.applyDelete("w-1", "b", "e-1");
        assertEquals("error", step.result());
    }

    // ---------- 0.7.2-P2：applyClone / applyDelete（session seam 路径 A） ----------

    @Test
    void applyClone_sessionPath_usesCloneSeam() {
        List<int[]> seen = new ArrayList<>();
        ElementPropertyApplier a = new ElementPropertyApplier(
                new ElementPropertyApplier.SessionPatchApplier() {
                    @Override
                    public ElementPropertyApplier.SessionOutcome apply(
                            String w, String e, Map<String, Object> p) {
                        throw new AssertionError("clone 不应走 apply()");
                    }

                    @Override
                    public ElementPropertyApplier.SessionOutcome clone(
                            String w, String e, int ox, int oy) {
                        seen.add(new int[]{ox, oy});
                        return ElementPropertyApplier.SessionOutcome.applied();
                    }
                }, wallRepo, ticker, LOG);
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = a.applyClone(wallId, "b", "e-1", 8, 9);
        assertEquals("ok", step.result());
        assertEquals(1, seen.size());
        assertEquals(8, seen.get(0)[0]);
        assertEquals(9, seen.get(0)[1]);
        // session APPLIED → 不落 headless（DB 仍 1 个元素）
        assertEquals(1, elementCount(wallRepo.loadById(wallId).orElseThrow().state()),
                "session 路径吞掉后不再走 headless");
    }

    @Test
    void applyClone_sessionFailed_errorStep_noHeadlessFallback() {
        ElementPropertyApplier a = new ElementPropertyApplier(
                new ElementPropertyApplier.SessionPatchApplier() {
                    @Override
                    public ElementPropertyApplier.SessionOutcome apply(
                            String w, String e, Map<String, Object> p) {
                        return ElementPropertyApplier.SessionOutcome.noSession();
                    }

                    @Override
                    public ElementPropertyApplier.SessionOutcome clone(
                            String w, String e, int ox, int oy) {
                        return ElementPropertyApplier.SessionOutcome.failed("QUOTA_EXCEEDED: x");
                    }
                }, wallRepo, ticker, LOG);
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = a.applyClone(wallId, "b", "e-1", 1, 1);
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("QUOTA_EXCEEDED"));
        assertEquals(1, elementCount(wallRepo.loadById(wallId).orElseThrow().state()),
                "session 拒绝不回退 headless");
    }

    @Test
    void applyClone_sessionNoSession_fallsThroughHeadless() {
        ElementPropertyApplier a = new ElementPropertyApplier(
                (w, e, p) -> ElementPropertyApplier.SessionOutcome.noSession(),
                wallRepo, ticker, LOG);
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = a.applyClone(wallId, "b", "e-1", 0, 0);
        assertEquals("ok", step.result());
        assertEquals(2, elementCount(wallRepo.loadById(wallId).orElseThrow().state()),
                "noSession → headless 落库");
    }

    @Test
    void applyDelete_sessionPath_usesDeleteSeam() {
        List<String> seen = new ArrayList<>();
        ElementPropertyApplier a = new ElementPropertyApplier(
                new ElementPropertyApplier.SessionPatchApplier() {
                    @Override
                    public ElementPropertyApplier.SessionOutcome apply(
                            String w, String e, Map<String, Object> p) {
                        throw new AssertionError("delete 不应走 apply()");
                    }

                    @Override
                    public ElementPropertyApplier.SessionOutcome delete(String w, String e) {
                        seen.add(e);
                        return ElementPropertyApplier.SessionOutcome.applied();
                    }
                }, wallRepo, ticker, LOG);
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = a.applyDelete(wallId, "b", "e-1");
        assertEquals("ok", step.result());
        assertEquals(List.of("e-1"), seen);
        assertEquals(1, elementCount(wallRepo.loadById(wallId).orElseThrow().state()),
                "session 吞掉后不走 headless 删");
    }

    @Test
    void applyDelete_sessionApplierThrows_errorStep_chainSafe() {
        ElementPropertyApplier a = new ElementPropertyApplier(
                new ElementPropertyApplier.SessionPatchApplier() {
                    @Override
                    public ElementPropertyApplier.SessionOutcome apply(
                            String w, String e, Map<String, Object> p) {
                        return ElementPropertyApplier.SessionOutcome.noSession();
                    }

                    @Override
                    public ElementPropertyApplier.SessionOutcome delete(String w, String e) {
                        throw new IllegalStateException("boom");
                    }
                }, wallRepo, ticker, LOG);
        String wallId = createWall(stateWithText("e-1", false));
        TraceStep step = a.applyDelete(wallId, "b", "e-1");
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("boom"));
    }

    // ---------- 0.7.3：applySetElementLayer（置顶/置底 headless 双路径 + session seam） ----------

    /** 双 text 元素工程（l-1 内 [a, b]，b 在上）；locked 可配（D-24 LAYER_LOCKED 覆盖）。 */
    private static ProjectState stateWithTwoElements(boolean locked) {
        TextElement a = new TextElement("e-a", 10, 20, 100, 50, 0, false, true,
                "A", "inter", 24, "#000000", "left", 0f, 1.2f, false, null,
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null, null);
        TextElement b = new TextElement("e-b", 30, 40, 100, 50, 0, false, true,
                "B", "inter", 24, "#000000", "left", 0f, 1.2f, false, null,
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null, null);
        Layer layer = new Layer("l-1", "L", true, locked, 1.0f, BlendMode.NORMAL, null,
                new ArrayList<>(List.of(a, b)));
        return new ProjectState(1L,
                new ProjectState.Canvas(2, 2, Fill.solid("#FFFFFF")),
                null, new ArrayList<>(List.of(layer)), "l-1",
                new ProjectState.History(0, 0), null, null, null);
    }

    /** 返回 l-1 内元素 id 的顺序（z-order，index 0 = 最底）。 */
    private static List<String> elementOrder(ProjectState st) {
        List<String> ids = new ArrayList<>();
        for (Element e : st.layers().get(0).elements()) ids.add(e.id());
        return ids;
    }

    @Test
    void setElementLayer_headless_front_movesToEnd() {
        // [a, b] → front(a) → [b, a]（a 移到末尾 = 显示最上）
        String wallId = createWall(stateWithTwoElements(false));
        TraceStep step = headlessApplier().applySetElementLayer(wallId, "b", "e-a", "front");
        assertEquals("ok", step.result(), () -> "应成功: " + step.detail());
        assertEquals(List.of("e-b", "e-a"),
                elementOrder(wallRepo.loadById(wallId).orElseThrow().state()),
                "front → 移到所在层末尾");
    }

    @Test
    void setElementLayer_headless_back_movesToStart() {
        // [a, b] → back(b) → [b, a]（b 移到开头 = 显示最下）
        String wallId = createWall(stateWithTwoElements(false));
        TraceStep step = headlessApplier().applySetElementLayer(wallId, "b", "e-b", "back");
        assertEquals("ok", step.result(), () -> "应成功: " + step.detail());
        assertEquals(List.of("e-b", "e-a"),
                elementOrder(wallRepo.loadById(wallId).orElseThrow().state()),
                "back → 移到所在层开头");
    }

    @Test
    void setElementLayer_headless_layerLocked_errorStep() {
        // D-24：所在层锁住 → moveElementToFront 返 LAYER_LOCKED → error step（不落库）
        String wallId = createWall(stateWithTwoElements(true));
        TraceStep step = headlessApplier().applySetElementLayer(wallId, "b", "e-a", "front");
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("LAYER_LOCKED"), step.detail());
        assertEquals(List.of("e-a", "e-b"),
                elementOrder(wallRepo.loadById(wallId).orElseThrow().state()),
                "锁层拒绝后顺序不变");
    }

    @Test
    void setElementLayer_headless_missingElement_errorStep() {
        String wallId = createWall(stateWithTwoElements(false));
        TraceStep step = headlessApplier().applySetElementLayer(wallId, "b", "e-MISSING", "front");
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("INVALID_ELEMENT"), step.detail());
        assertEquals(List.of("e-a", "e-b"),
                elementOrder(wallRepo.loadById(wallId).orElseThrow().state()),
                "拒绝后顺序不变");
    }

    @Test
    void setElementLayer_emptyElementId_errorStep() {
        TraceStep step = headlessApplier().applySetElementLayer("w-any", "b", "", "front");
        assertEquals("error", step.result());
    }

    @Test
    void setElementLayer_headless_wallNotFound_errorStep() {
        TraceStep step = headlessApplier().applySetElementLayer("w-nope", "b", "e-a", "front");
        assertEquals("error", step.result());
    }

    @Test
    void setElementLayer_headless_animatingWall_invalidates() {
        String wallId = createWall(stateWithTwoElements(false));
        ticker.animating = true;
        headlessApplier().applySetElementLayer(wallId, "b", "e-a", "front");
        assertEquals(List.of(wallId), ticker.invalidates, "在播 → invalidate（persistWall 链）");
    }

    @Test
    void setElementLayer_sessionPath_usesReorderSeam() {
        // 活跃 session（reorderToEdge 返 APPLIED）→ 不落 headless（DB 顺序不变）
        List<String> seen = new ArrayList<>();
        ElementPropertyApplier a = new ElementPropertyApplier(
                new ElementPropertyApplier.SessionPatchApplier() {
                    @Override
                    public ElementPropertyApplier.SessionOutcome apply(
                            String w, String e, Map<String, Object> p) {
                        throw new AssertionError("setElementLayer 不应走 apply()");
                    }

                    @Override
                    public ElementPropertyApplier.SessionOutcome reorderToEdge(
                            String w, String e, String mode) {
                        seen.add(e + ":" + mode);
                        return ElementPropertyApplier.SessionOutcome.applied();
                    }
                }, wallRepo, ticker, LOG);
        String wallId = createWall(stateWithTwoElements(false));
        TraceStep step = a.applySetElementLayer(wallId, "b", "e-a", "front");
        assertEquals("ok", step.result());
        assertEquals(List.of("e-a:front"), seen, "走 reorderToEdge seam 且带 mode");
        assertEquals(List.of("e-a", "e-b"),
                elementOrder(wallRepo.loadById(wallId).orElseThrow().state()),
                "session APPLIED → 不再走 headless reorder");
    }

    @Test
    void setElementLayer_sessionFailed_errorStep_noHeadlessFallback() {
        ElementPropertyApplier a = new ElementPropertyApplier(
                new ElementPropertyApplier.SessionPatchApplier() {
                    @Override
                    public ElementPropertyApplier.SessionOutcome apply(
                            String w, String e, Map<String, Object> p) {
                        return ElementPropertyApplier.SessionOutcome.noSession();
                    }

                    @Override
                    public ElementPropertyApplier.SessionOutcome reorderToEdge(
                            String w, String e, String mode) {
                        return ElementPropertyApplier.SessionOutcome.failed("LAYER_LOCKED: x");
                    }
                }, wallRepo, ticker, LOG);
        String wallId = createWall(stateWithTwoElements(false));
        TraceStep step = a.applySetElementLayer(wallId, "b", "e-a", "front");
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("LAYER_LOCKED"));
        assertEquals(List.of("e-a", "e-b"),
                elementOrder(wallRepo.loadById(wallId).orElseThrow().state()),
                "session 拒绝不回退 headless");
    }

    @Test
    void setElementLayer_sessionNoSession_fallsThroughHeadless() {
        // 默认 reorderToEdge 返 noSession（旧 fake 兼容）→ headless 落库 [a,b] → front(a) → [b,a]
        ElementPropertyApplier a = new ElementPropertyApplier(
                (w, e, p) -> ElementPropertyApplier.SessionOutcome.noSession(),
                wallRepo, ticker, LOG);
        String wallId = createWall(stateWithTwoElements(false));
        TraceStep step = a.applySetElementLayer(wallId, "b", "e-a", "front");
        assertEquals("ok", step.result());
        assertEquals(List.of("e-b", "e-a"),
                elementOrder(wallRepo.loadById(wallId).orElseThrow().state()),
                "noSession → headless reorder 落库");
    }

    @Test
    void setElementLayer_sessionApplierThrows_errorStep_chainSafe() {
        ElementPropertyApplier a = new ElementPropertyApplier(
                new ElementPropertyApplier.SessionPatchApplier() {
                    @Override
                    public ElementPropertyApplier.SessionOutcome apply(
                            String w, String e, Map<String, Object> p) {
                        return ElementPropertyApplier.SessionOutcome.noSession();
                    }

                    @Override
                    public ElementPropertyApplier.SessionOutcome reorderToEdge(
                            String w, String e, String mode) {
                        throw new IllegalStateException("boom");
                    }
                }, wallRepo, ticker, LOG);
        String wallId = createWall(stateWithTwoElements(false));
        TraceStep step = a.applySetElementLayer(wallId, "b", "e-a", "front");
        assertEquals("error", step.result());
        assertTrue(step.detail().contains("boom"));
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
        final List<String> renderStatics = new ArrayList<>();
        final List<String> clearedStaticDiffs = new ArrayList<>();
        ProjectState lastStaticFrame;

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

        @Override
        public void renderStatic(String wallId, ac.haru.hikaricanvas.state.ProjectState frame) {
            renderStatics.add(wallId);
            lastStaticFrame = frame;
        }

        @Override
        public void clearStaticDiff(String wallId) {
            clearedStaticDiffs.add(wallId);
        }
    }
}
