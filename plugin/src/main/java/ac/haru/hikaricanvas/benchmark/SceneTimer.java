package ac.haru.hikaricanvas.benchmark;

import ac.haru.hikaricanvas.render.CanvasCompositor;
import ac.haru.hikaricanvas.state.ProjectState;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * 单场景测量原语：对一个 {@link BenchmarkScene} 反复跑 rasterize + 全 tile palette 量化、
 * 分别计时、采分配字节，产出一串 {@link RasterizeSample}。
 *
 * <p>这是 benchmark 的最小测量单元。矩阵 runner（fps × viewer 轴）会对每个矩阵 cell
 * 调用一次本方法；基础路径只在 {@code SceneLibrary.select()} 出来的场景上逐个跑（见
 * {@code docs/dynamic-data.md §13.3} 与 {@code PROPOSAL.md §5.2.7}）。</p>
 *
 * <h2>纯函数压测</h2>
 * 直接把 {@code scene.state()} 喂 {@link CanvasCompositor#rasterize(ProjectState)}，不经地图池 /
 * ItemFrame / WS 协议 / PacketEvents——只量 rasterize 与 palette 两段 CPU 成本。{@code CanvasCompositor}
 * 无可变状态、每次分配新 {@code BufferedImage}，本类同步串行调用，分配全落在测量线程上，因此
 * {@link Instrumentation#threadAllocatedBytes()} 的前后差即本轮真实分配。
 *
 * <h2>JIT 防优化</h2>
 * <ul>
 *   <li><b>warmup</b>：测量前空转 {@code warmupIterations} 轮，触发 C2 编译——首轮解释执行的耗时
 *       会污染均值，必须排除。</li>
 *   <li><b>blackhole</b>：{@code rasterize} 与 {@code toPaletteSlice} 的结果都喂进静态 volatile
 *       {@link #sink}，否则 JIT 会判定整段是死代码（结果未被读取）直接消除，测出 0ns。</li>
 * </ul>
 *
 * <h2>线程模型</h2>
 * headless / 无可变实例状态（{@link #sink} 是进程级 blackhole，只写不语义化读）；无 Bukkit 依赖。
 * 多个 {@code SceneTimer} 实例并发跑互不干扰，但分配数据要准就别让多线程共用一个测量线程。
 */
public final class SceneTimer {

    /**
     * JIT blackhole：吸收 rasterize / palette 结果防止死代码消除。
     *
     * <p>{@code volatile} 让 JIT 无法证明写入不可见、不能优化掉对 sink 的累加；进而不能优化掉
     * 产生这些值的 rasterize/palette 调用。语义上无意义，永远别读它做判断。</p>
     */
    public static volatile long sink;

    /**
     * 对单场景跑 warmup + measured，返回每轮测量样本。
     *
     * @param compositor headless {@link CanvasCompositor}（{@code BenchCompositor.create} 产出）
     * @param scene      待测场景
     * @param config     轮数配置（用 {@code warmupIterations} / {@code measuredIterations}）
     * @return {@code measuredIterations} 条 {@link RasterizeSample}（按迭代序）
     */
    public List<RasterizeSample> time(CanvasCompositor compositor, BenchmarkScene scene, BenchmarkConfig config) {
        final int widthMaps = scene.tilesWide();
        final int tiles = scene.tileCount();
        final ProjectState state = scene.state();
        final String sceneId = scene.id();

        // ---- warmup：触发 C2 编译，结果全部丢弃（但要喂 sink 防消除）----
        long warmSink = 0L;
        for (int w = 0; w < config.warmupIterations(); w++) {
            BufferedImage img = compositor.rasterize(state);
            for (int m = 0; m < tiles; m++) {
                byte[] slice = compositor.toPaletteSlice(img, m, widthMaps);
                warmSink += slice[0];
            }
        }
        sink += warmSink;

        // ---- measured：逐轮分段计时 + 采分配增量 ----
        final int measured = config.measuredIterations();
        List<RasterizeSample> samples = new ArrayList<>(measured);
        for (int i = 0; i < measured; i++) {
            long allocBefore = Instrumentation.threadAllocatedBytes();

            long t0 = System.nanoTime();
            BufferedImage img = compositor.rasterize(state);
            long t1 = System.nanoTime();
            sink += img.getRGB(0, 0); // blackhole：读一像素，防 rasterize 整段被消除

            long pStart = System.nanoTime();
            for (int m = 0; m < tiles; m++) {
                byte[] slice = compositor.toPaletteSlice(img, m, widthMaps);
                sink += slice[0] + slice[slice.length - 1];
            }
            long pEnd = System.nanoTime();

            long allocAfter = Instrumentation.threadAllocatedBytes();
            long alloc = (allocBefore < 0 || allocAfter < 0) ? -1L : (allocAfter - allocBefore);

            samples.add(new RasterizeSample(sceneId, i, t1 - t0, pEnd - pStart, alloc));
        }
        return samples;
    }
}
