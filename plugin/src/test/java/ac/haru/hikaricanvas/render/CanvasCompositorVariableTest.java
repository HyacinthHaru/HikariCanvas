package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.BlendMode;
import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.Fill;
import ac.haru.hikaricanvas.state.Layer;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.RenderMode;
import ac.haru.hikaricanvas.state.TextElement;
import ac.haru.hikaricanvas.storage.UserVariableDao;
import ac.haru.hikaricanvas.variable.VarType;
import ac.haru.hikaricanvas.variable.VariableInterpolator;
import ac.haru.hikaricanvas.variable.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-P1-C：{@link CanvasCompositor} 接入 {@link VariableInterpolator} 集成测试。
 *
 * <p>只验证管线接通——rasterize 内 TextElement 替换走过、{@link VariableStore#markWallReferences}
 * 收到了正确的 fullName 集合。不做像素 baseline 比对（baseline 留给 {@link RendererSnapshotTest}）。</p>
 */
class CanvasCompositorVariableTest {

    private FakeUserVariableDao fakeDao;
    private VariableStore store;
    private VariableInterpolator interp;
    private CanvasCompositor compositor;

    @BeforeEach
    void setUp() throws Exception {
        Logger log = Logger.getLogger(CanvasCompositorVariableTest.class.getName());
        PaletteLut paletteLut = PaletteLut.loadFromClasspath("/palette.json");
        FontRegistry fontRegistry = new FontRegistry(log);
        fontRegistry.loadBuiltIn();
        compositor = new CanvasCompositor(paletteLut, fontRegistry, log);

        fakeDao = new FakeUserVariableDao();
        store = new VariableStore(fakeDao, w -> { });
        interp = new VariableInterpolator(store);
    }

    @Test
    void rasterize_withoutSetVariableSupport_doesNotMarkReferences() {
        // 不注入 interpolator —— rasterize 走老路径，存储不联动
        ProjectState state = singleTextState("${var:user/x|fallback=Y}");
        BufferedImage img = compositor.rasterize(state, "w-1");
        assertNotNull(img);
        // 倒排索引为空（compositor 未调 markWallReferences）
        assertTrue(store.referencedFullNamesByWall("w-1").isEmpty(),
                "未注入 interpolator 时不写倒排索引");
    }

    @Test
    void rasterize_withSetVariableSupport_marksReferences() {
        compositor.setVariableSupport(interp, store);
        store.create("user:w-1", "score", VarType.NUMBER, "0", null);

        ProjectState state = singleTextState("Score: ${var:user/score}");
        BufferedImage img = compositor.rasterize(state, "w-1");
        assertNotNull(img);

        Set<String> ref = store.referencedFullNamesByWall("w-1");
        assertEquals(1, ref.size());
        assertTrue(ref.contains("user:w-1/score"));
    }

    @Test
    void rasterize_nullWallId_skipsMarkReferences() {
        compositor.setVariableSupport(interp, store);
        // wallId == null → compositor 不联动 markWallReferences（避免给 null wall 写索引）
        ProjectState state = singleTextState("${var:user/score|fallback=N}");
        BufferedImage img = compositor.rasterize(state, null);
        assertNotNull(img);
        // 任何 wall 都不应包含此变量
        assertTrue(store.referencedFullNamesByWall("w-1").isEmpty());
    }

    @Test
    void rasterize_plainText_marksEmptyReferences() {
        compositor.setVariableSupport(interp, store);

        // 文本内无占位符 → referenced 为空，markWallReferences 仍被调（清掉上次记录）
        store.create("bedwars", "phase", VarType.STRING, null, null);
        store.markWallReferences("w-1", Set.of("bedwars/phase"));
        assertFalse(store.referencedFullNamesByWall("w-1").isEmpty(),
                "前置：手动 mark 一次 w-1 引用");

        ProjectState state = singleTextState("Plain text without placeholders");
        compositor.rasterize(state, "w-1");
        assertTrue(store.referencedFullNamesByWall("w-1").isEmpty(),
                "rasterize 后 w-1 引用应被清空（diff 算法移除"
                        + "上次记录但本次不在的 fullName）");
    }

    @Test
    void rasterize_multipleTextElements_aggregatesReferences() {
        compositor.setVariableSupport(interp, store);
        store.create("user:w-1", "red", VarType.NUMBER, "0", null);
        store.create("bedwars", "phase", VarType.STRING, "WAITING", null);

        List<Element> elements = new ArrayList<>();
        elements.add(makeText("t1", "红 ${var:user/red}"));
        elements.add(makeText("t2", "阶段: ${var:bedwars/phase}"));

        ProjectState state = stateWithElements(elements);
        compositor.rasterize(state, "w-1");

        Set<String> ref = store.referencedFullNamesByWall("w-1");
        assertEquals(2, ref.size());
        assertTrue(ref.contains("user:w-1/red"));
        assertTrue(ref.contains("bedwars/phase"));
    }

    @Test
    void rasterize_renderedTextReflectsCurrentValue() {
        compositor.setVariableSupport(interp, store);
        store.create("bedwars", "phase", VarType.STRING, "WAITING", null);
        store.setValue("bedwars/phase", "RUNNING", null);

        // 字面验证：rasterize 内部应当替换 text 后 dispatch；这里跑通管线 + 不 crash 即可。
        // 双 rasterize 同 wallId 多次调用幂等（markWallReferences ConcurrentHashMap 操作）。
        ProjectState state = singleTextState("Phase: ${var:bedwars/phase}");
        BufferedImage img1 = compositor.rasterize(state, "w-1");
        BufferedImage img2 = compositor.rasterize(state, "w-1");
        assertNotNull(img1);
        assertNotNull(img2);
        assertEquals(img1.getWidth(), img2.getWidth());
    }

    @Test
    void clearWallReferences_removesFromIndex() {
        compositor.setVariableSupport(interp, store);
        store.create("bedwars", "x", VarType.STRING, "v", null);

        ProjectState state = singleTextState("${var:bedwars/x}");
        compositor.rasterize(state, "w-doomed");
        assertFalse(store.referencedFullNamesByWall("w-doomed").isEmpty(),
                "rasterize 后应有索引");

        // 模拟 wall 删除联动
        store.clearWallReferences("w-doomed");
        assertTrue(store.referencedFullNamesByWall("w-doomed").isEmpty(),
                "clearWallReferences 后索引为空");
    }

    // ──────────────────────────────────────────────────────────
    //  Helper
    // ──────────────────────────────────────────────────────────

    private static ProjectState singleTextState(String text) {
        List<Element> elements = new ArrayList<>();
        elements.add(makeText("t1", text));
        return stateWithElements(elements);
    }

    private static ProjectState stateWithElements(List<Element> elements) {
        ProjectState s = new ProjectState(1, 1, "#FFFFFF");
        // 替换 active layer 的 elements。M8 v2 path：通过 layers().get(0).elements()
        // 是不可变视图，所以用反射或者 ProjectState.elements() mutable mutator 不存在；
        // 替代：构造时不传 elements，再用 ProjectState mutator
        // 简化：直接构造一份新 state with layer elements
        Layer l = new Layer(s.activeLayer().id(), s.activeLayer().name(),
                true, false, 1.0f, BlendMode.NORMAL, elements);
        return new ProjectState(s.version(), s.canvas(), null,
                List.of(l), l.id(), s.history(), null, null, null);
    }

    private static TextElement makeText(String id, String text) {
        return new TextElement(
                id,
                4, 4, 120, 24,      // x y w h（小尺寸够 1×1 maps 容纳）
                0, false, true,
                text,
                "default",          // fontId
                12,                 // fontSize
                "#000000", "left",
                0f, 1.2f, false,
                null,
                null, BlendMode.NORMAL, RenderMode.CLEAN,
                null, null);
    }

    private static final class FakeUserVariableDao extends UserVariableDao {
        FakeUserVariableDao() {
            super(Logger.getLogger("test"), null);
        }
        @Override
        public void upsert(String wallId, String name, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) { }
        @Override
        public void delete(String wallId, String name) { }
        @Override
        public List<Row> loadAll() { return new ArrayList<>(); }
    }
}
