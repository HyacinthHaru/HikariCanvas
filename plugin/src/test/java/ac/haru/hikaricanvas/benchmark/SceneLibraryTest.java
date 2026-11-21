package ac.haru.hikaricanvas.benchmark;

import ac.haru.hikaricanvas.benchmark.SceneLibrary.BenchmarkScene;
import ac.haru.hikaricanvas.state.Element;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SceneLibrary}、{@link BenchmarkScene} 与 {@link BenchmarkConfig} 单元测试。
 */
class SceneLibraryTest {

    @Test
    void sceneLibraryIsDeterministicAcrossInstances() {
        List<BenchmarkScene> a = new SceneLibrary().generate();
        List<BenchmarkScene> b = new SceneLibrary().generate();

        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            BenchmarkScene sa = a.get(i);
            BenchmarkScene sb = b.get(i);
            assertEquals(sa.id(), sb.id());
            assertEquals(sa.category(), sb.category());
            assertEquals(sa.dominantElementType(), sb.dominantElementType());
            assertEquals(sa.tilesWide(), sb.tilesWide());
            assertEquals(sa.tilesHigh(), sb.tilesHigh());
            assertEquals(elementTypeSeq(sa), elementTypeSeq(sb),
                    "场景 " + sa.id() + " 的元素类型序列应确定一致");
        }
    }

    @Test
    void generateIsCachedWithinSameInstance() {
        SceneLibrary lib = new SceneLibrary();
        assertSame(lib.generate(), lib.generate());
    }

    @Test
    void sceneLibraryCoversAllElementTypesAndCategories() {
        SceneLibrary lib = new SceneLibrary();
        List<BenchmarkScene> scenes = lib.generate();

        assertEquals(21, scenes.size());
        assertEquals(List.of(
                        BenchmarkScene.CATEGORY_ELEMENT_ISOLATION,
                        BenchmarkScene.CATEGORY_EFFECT,
                        BenchmarkScene.CATEGORY_MIXED),
                lib.categories());

        Set<String> ids = scenes.stream().map(BenchmarkScene::id).collect(Collectors.toSet());
        for (String id : List.of(
                "text-saturated-2x2", "variable-text-2x2", "rect-saturated-2x2",
                "circle-saturated-2x2", "shape-star-saturated-2x2", "path-saturated-2x2",
                "image-saturated-2x2", "brush-saturated-2x2", "icon-saturated-2x2")) {
            assertTrue(ids.contains(id), "缺少单元素场景 " + id);
        }
        for (String id : List.of("size-1x1", "size-2x2", "size-4x4", "size-8x8")) {
            assertTrue(ids.contains(id), "缺少尺寸梯度场景 " + id);
        }
        assertTrue(ids.contains("subway-sign-2x1"));
        assertTrue(ids.contains("welcome-wall-4x2"));
        assertTrue(ids.contains("dense-dashboard-2x2"));

        for (BenchmarkScene s : scenes) {
            assertFalse(s.state().elements().isEmpty(), s.id() + " 应至少含一个元素");
            assertEquals(s.tilesWide() * s.tilesHigh(), s.tileCount());
            assertEquals(s.tilesWide() * 128 * s.tilesHigh() * 128, s.pixelCount());
        }
    }

    @Test
    void selectResolvesAllCategoryAndId() {
        SceneLibrary lib = new SceneLibrary();
        assertEquals(21, lib.select("all").size());
        assertEquals(21, lib.select(null).size());
        assertEquals(21, lib.select("  ").size());

        List<BenchmarkScene> iso = lib.select(BenchmarkScene.CATEGORY_ELEMENT_ISOLATION);
        assertEquals(9, iso.size());
        assertTrue(iso.stream().allMatch(
                s -> s.category().equals(BenchmarkScene.CATEGORY_ELEMENT_ISOLATION)));

        assertEquals(5, lib.select(BenchmarkScene.CATEGORY_EFFECT).size());
        assertEquals(7, lib.select(BenchmarkScene.CATEGORY_MIXED).size());

        assertEquals(1, lib.select("size-1x1").size());
        assertTrue(lib.select("no-such-scene").isEmpty());
        assertTrue(lib.byId("no-such-scene").isEmpty());
        assertTrue(lib.byId(null).isEmpty());
        assertEquals("welcome-wall-4x2", lib.byId("welcome-wall-4x2").orElseThrow().id());
    }

    @Test
    void benchmarkSceneValidatesArguments() {
        BenchmarkScene valid = new SceneLibrary().byId("size-1x1").orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkScene(" ", "name", "mixed", "mixed", 1, 1, valid.state()));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkScene("id", "name", "mixed", "mixed", 1, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkScene("id", "name", "mixed", "mixed", 0, 1, valid.state()));
    }

    @Test
    void configQuickAndFullHaveExpectedShape() {
        BenchmarkConfig quick = BenchmarkConfig.quick();
        assertEquals(5, quick.warmupIterations());
        assertEquals(20, quick.measuredIterations());
        assertEquals("all", quick.sceneSelector());

        BenchmarkConfig full = BenchmarkConfig.full();
        assertTrue(full.measuredIterations() >= quick.measuredIterations());
        assertTrue(full.fpsValues().size() > 1);
        assertTrue(full.viewerCounts().size() > 1);
    }

    @Test
    void configNormalizesInvalidInputs() {
        BenchmarkConfig c = new BenchmarkConfig(-3, 0, List.of(), null, "  ");
        assertEquals(0, c.warmupIterations());
        assertEquals(1, c.measuredIterations());
        assertEquals(List.of(5), c.fpsValues());
        assertEquals(List.of(1), c.viewerCounts());
        assertEquals("all", c.sceneSelector());
    }

    private static List<String> elementTypeSeq(BenchmarkScene scene) {
        List<String> seq = new ArrayList<>();
        for (Element e : scene.state().elements()) {
            seq.add(e.getClass().getSimpleName());
        }
        return seq;
    }
}
