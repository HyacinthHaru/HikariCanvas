package ac.haru.hikaricanvas.benchmark;

import ac.haru.hikaricanvas.render.CanvasCompositor;
import ac.haru.hikaricanvas.state.Element;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.5.0 Benchmark P1 单测。覆盖四块：
 * <ul>
 *   <li>{@link SceneLibrary} 的<b>确定性</b>（同 seed → 结构相同）与<b>全元素覆盖</b>；</li>
 *   <li>{@link BenchmarkConfig} 的默认值与归一化；</li>
 *   <li>{@link Instrumentation} 的优雅降级 / 单调分配计数；</li>
 *   <li><b>端到端 smoke</b>：{@link BenchCompositor} + {@link SceneTimer} 在 headless 下把<b>全部</b>
 *       场景跑通一遍——这同时是 「CI 功能性 gate」的种子（CI 只断言 bench 能跑通、
 *       不崩、产出样本，<b>不</b>做性能数值门禁）。</li>
 * </ul>
 *
 * <p>全程 headless，零 Bukkit——不引 MockBukkit，与 {@code RendererSnapshotTest} 同一条
 * 已验证的无头渲染装配路径。</p>
 */
class BenchmarkP1Test {

    private static final Logger LOG = Logger.getLogger(BenchmarkP1Test.class.getName());
    private static CanvasCompositor compositor;

    @BeforeAll
    static void setUp() {
        // 与 RendererSnapshotTest 同源的无头 compositor（palette + 内置字体 + 合成图片加载器）。
        compositor = BenchCompositor.create(LOG);
    }

    // ====================================================================
    // SceneLibrary：确定性
    // ====================================================================

    @Test
    void sceneLibraryIsDeterministicAcrossInstances() {
        List<BenchmarkScene> a = new SceneLibrary().generate();
        List<BenchmarkScene> b = new SceneLibrary().generate();

        assertEquals(a.size(), b.size(), "两次生成场景数应相同");
        for (int i = 0; i < a.size(); i++) {
            BenchmarkScene sa = a.get(i);
            BenchmarkScene sb = b.get(i);
            assertEquals(sa.id(), sb.id(), "场景 id 顺序应相同");
            assertEquals(sa.category(), sb.category());
            assertEquals(sa.dominantElementType(), sb.dominantElementType());
            assertEquals(sa.tilesWide(), sb.tilesWide());
            assertEquals(sa.tilesHigh(), sb.tilesHigh());
            // 逐元素类型序列必须一致（layer id 内部用 UUID 漂移，但元素内容确定）
            assertEquals(elementTypeSeq(sa), elementTypeSeq(sb),
                    "场景 " + sa.id() + " 的元素类型序列应确定");
        }
    }

    @Test
    void generateIsCachedAndStable() {
        SceneLibrary lib = new SceneLibrary();
        List<BenchmarkScene> first = lib.generate();
        List<BenchmarkScene> second = lib.generate();
        assertSame(first, second, "同一实例 generate() 应返回缓存的同一列表");
    }

    // ====================================================================
    // SceneLibrary：全元素覆盖
    // ====================================================================

    @Test
    void sceneLibraryCoversAllElementTypesAndCategories() {
        SceneLibrary lib = new SceneLibrary();
        List<BenchmarkScene> scenes = lib.generate();

        assertEquals(21, scenes.size(), "P1 应产出 21 个场景");
        assertEquals(List.of(
                        BenchmarkScene.CATEGORY_ELEMENT_ISOLATION,
                        BenchmarkScene.CATEGORY_EFFECT,
                        BenchmarkScene.CATEGORY_MIXED),
                lib.categories(), "category 顺序应稳定");

        Set<String> ids = scenes.stream().map(BenchmarkScene::id).collect(Collectors.toSet());
        // 单元素饱和：每种元素类型一面墙
        for (String id : List.of(
                "text-saturated-2x2", "variable-text-2x2", "rect-saturated-2x2",
                "circle-saturated-2x2", "shape-star-saturated-2x2", "path-saturated-2x2",
                "image-saturated-2x2", "brush-saturated-2x2", "icon-saturated-2x2")) {
            assertTrue(ids.contains(id), "缺少单元素场景 " + id);
        }
        // 尺寸缩放梯度
        for (String id : List.of("size-1x1", "size-2x2", "size-4x4", "size-8x8")) {
            assertTrue(ids.contains(id), "缺少尺寸场景 " + id);
        }
        // 真实混合
        assertTrue(ids.contains("subway-sign-2x1"));
        assertTrue(ids.contains("welcome-wall-4x2"));

        // 每个场景至少有一个元素，且 pixelCount/tileCount 自洽
        for (BenchmarkScene s : scenes) {
            assertFalse(s.state().elements().isEmpty(), s.id() + " 应至少含一个元素");
            assertEquals(s.tilesWide() * s.tilesHigh(), s.tileCount());
            assertEquals(s.tilesWide() * 128 * s.tilesHigh() * 128, s.pixelCount());
        }
    }

    // ====================================================================
    // SceneLibrary：select / byId
    // ====================================================================

    @Test
    void selectResolvesAllCategoryAndId() {
        SceneLibrary lib = new SceneLibrary();
        assertEquals(21, lib.select("all").size());
        assertEquals(21, lib.select(null).size());
        assertEquals(21, lib.select("  ").size());

        List<BenchmarkScene> iso = lib.select(BenchmarkScene.CATEGORY_ELEMENT_ISOLATION);
        assertEquals(9, iso.size(), "element-isolation 应有 9 个场景");
        assertTrue(iso.stream().allMatch(
                s -> s.category().equals(BenchmarkScene.CATEGORY_ELEMENT_ISOLATION)));

        assertEquals(1, lib.select("text-saturated-2x2").size());
        assertTrue(lib.select("no-such-scene").isEmpty());
        assertTrue(lib.byId("no-such-scene").isEmpty());
        assertEquals("welcome-wall-4x2", lib.byId("welcome-wall-4x2").orElseThrow().id());
    }

    // ====================================================================
    // BenchmarkConfig：默认 + 归一化
    // ====================================================================

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
        assertEquals(0, c.warmupIterations(), "负 warmup 归 0");
        assertEquals(1, c.measuredIterations(), "measured<1 归 1");
        assertEquals(List.of(5), c.fpsValues(), "空 fps 归默认 [5]");
        assertEquals(List.of(1), c.viewerCounts(), "空 viewer 归默认 [1]");
        assertEquals("all", c.sceneSelector(), "空白 selector 归 all");
    }

    // ====================================================================
    // Instrumentation：优雅降级 / 单调
    // ====================================================================

    @Test
    void instrumentationAllocationIsGracefulOrMonotonic() {
        long before = Instrumentation.threadAllocatedBytes();
        // 在本线程分配 ~8MB，制造可观测的分配增量
        byte[] junk = new byte[8 * 1024 * 1024];
        junk[0] = 1;
        junk[junk.length - 1] = 2;
        long after = Instrumentation.threadAllocatedBytes();
        // 不支持时两端都为 -1（优雅降级）；支持时本线程计数单调不减
        if (before >= 0 && after >= 0) {
            assertTrue(after >= before, "本线程分配计数应单调不减");
        } else {
            assertEquals(-1, before, "不支持时应统一返回 -1");
            assertEquals(-1, after, "不支持时应统一返回 -1");
        }
        assertTrue(junk.length == 8 * 1024 * 1024); // 防 junk 被 DCE
    }

    @Test
    void gcSnapshotAndMinusWork() {
        Instrumentation.GcStat s1 = Instrumentation.gcSnapshot();
        assertNotNull(s1);
        assertTrue(s1.collectionCount() >= 0);
        assertTrue(s1.collectionTimeMs() >= 0);
        Instrumentation.GcStat delta = s1.minus(new Instrumentation.GcStat(0, 0));
        assertEquals(s1.collectionCount(), delta.collectionCount());
        assertEquals(s1.collectionTimeMs(), delta.collectionTimeMs());
    }

    // ====================================================================
    // 端到端 smoke —— 同时是 P4 CI 功能性 gate 的种子
    // ====================================================================

    @Test
    void everySceneRasterizesEndToEnd() {
        SceneTimer timer = new SceneTimer();
        // warmup=0 / measured=1：只验「能跑通 + 产出样本」，不做性能断言（决策①）
        BenchmarkConfig smoke = new BenchmarkConfig(0, 1, null, null, "all");

        for (BenchmarkScene scene : new SceneLibrary().generate()) {
            List<RasterizeSample> samples = timer.time(compositor, scene, smoke);
            assertEquals(1, samples.size(), scene.id() + " 应产出 1 个样本");
            RasterizeSample s = samples.get(0);
            assertEquals(scene.id(), s.sceneId());
            assertEquals(0, s.iteration());
            assertTrue(s.rasterizeNanos() > 0, scene.id() + " rasterize 应耗时为正");
            assertTrue(s.paletteNanos() >= 0, scene.id() + " palette 耗时应非负");
        }
    }

    @Test
    void sceneTimerProducesRequestedSampleCount() {
        SceneTimer timer = new SceneTimer();
        BenchmarkScene scene = new SceneLibrary().byId("text-saturated-2x2").orElseThrow();
        BenchmarkConfig cfg = new BenchmarkConfig(2, 5, null, null, "text-saturated-2x2");

        List<RasterizeSample> samples = timer.time(compositor, scene, cfg);
        assertEquals(5, samples.size(), "measured=5 应产出 5 个样本");
        for (int i = 0; i < samples.size(); i++) {
            assertEquals(i, samples.get(i).iteration(), "迭代序号应 0..N 递增");
            assertEquals(scene.id(), samples.get(i).sceneId());
            assertTrue(samples.get(i).rasterizeNanos() > 0);
        }
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private static List<String> elementTypeSeq(BenchmarkScene scene) {
        List<String> seq = new ArrayList<>();
        for (Element e : scene.state().elements()) {
            seq.add(e.getClass().getSimpleName());
        }
        return seq;
    }
}
