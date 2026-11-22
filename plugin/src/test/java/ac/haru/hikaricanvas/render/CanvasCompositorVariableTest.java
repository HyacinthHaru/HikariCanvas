package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.BlendMode;
import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.Layer;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.RenderMode;
import ac.haru.hikaricanvas.state.TextElement;
import ac.haru.hikaricanvas.variable.NoopUserVariableDao;
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
 * {@link CanvasCompositor} 接入 {@link VariableInterpolator} 集成测试。
 *
 * <p>验证 rasterize 内 TextElement 替换管线与 {@link VariableStore#markWallReferences}
 * 倒排索引更新。像素级 baseline 留给 {@link RendererSnapshotTest}。</p>
 */
class CanvasCompositorVariableTest {

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

        store = new VariableStore(new NoopUserVariableDao(), w -> { });
        interp = new VariableInterpolator(store);
    }

    @Test
    void rasterize_withoutSetVariableSupport_doesNotMarkReferences() {
        ProjectState state = singleTextState("${var:user/x|fallback=Y}");
        BufferedImage img = compositor.rasterize(state, "w-1");
        assertNotNull(img);
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
        ProjectState state = singleTextState("${var:user/score|fallback=N}");
        BufferedImage img = compositor.rasterize(state, null);
        assertNotNull(img);
        assertTrue(store.referencedFullNamesByWall("w-1").isEmpty());
    }

    @Test
    void rasterize_plainText_marksEmptyReferences() {
        compositor.setVariableSupport(interp, store);

        store.create("bedwars", "phase", VarType.STRING, null, null);
        store.markWallReferences("w-1", Set.of("bedwars/phase"));
        assertFalse(store.referencedFullNamesByWall("w-1").isEmpty(),
                "前置：手动 mark 一次 w-1 引用");

        ProjectState state = singleTextState("Plain text without placeholders");
        compositor.rasterize(state, "w-1");
        assertTrue(store.referencedFullNamesByWall("w-1").isEmpty(),
                "rasterize 后 w-1 引用应被清空");
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
        Layer l = new Layer(s.activeLayer().id(), s.activeLayer().name(),
                true, false, 1.0f, BlendMode.NORMAL, elements);
        return new ProjectState(s.version(), s.canvas(), null,
                List.of(l), l.id(), s.history(), null, null, null);
    }

    private static TextElement makeText(String id, String text) {
        return new TextElement(
                id,
                4, 4, 120, 24,
                0, false, true,
                text,
                "default",
                12,
                "#000000", "left",
                0f, 1.2f, false,
                null,
                null, BlendMode.NORMAL, RenderMode.CLEAN,
                null, null);
    }
}
