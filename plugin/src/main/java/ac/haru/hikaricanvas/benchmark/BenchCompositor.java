package ac.haru.hikaricanvas.benchmark;

import ac.haru.hikaricanvas.render.CanvasCompositor;
import ac.haru.hikaricanvas.render.FontRegistry;
import ac.haru.hikaricanvas.render.PaletteLut;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * 构建一个 <b>纯无头</b>（headless）{@link CanvasCompositor} 的工厂，
 * 让 {@code SceneTimer} / {@code /canvas bench} / CI harness 在不依赖 Bukkit / 世界 / 玩家 /
 * 网络的前提下，光栅化 + 量化 benchmark 场景，测纯 CPU / 内存渲染成本。
 *
 * <h2>装配方式</h2>
 * headless 装配：仅 3 参构造，复刻 {@code RendererSnapshotTest}（{@link PaletteLut} +
 * {@link FontRegistry} + {@link Logger}），零外部依赖、线程安全。
 *
 * <h2>合成图片加载器</h2>
 * {@link CanvasCompositor#setImageLoader} 是一个干净的 SAM 注入口（{@code BufferedImage load(String)}，
 * 零 Bukkit）。生产环境注入 {@code ImageStorage::load} 从磁盘读真实上传文件；benchmark 场景里没有
 * 磁盘文件，若不注入则所有 ImageElement / 蒙版场景会走「文件缺失占位」分支，渲染成本失真（占位是
 * 一个轻量灰框，远低于真实图片解码 + 蒙版 clip + dither 的开销）。这里注入一个
 * <b>内存合成渐变生成器</b>：对<b>任意</b> hash 都确定性地返回一张 256×256 的 RGB 渐变图，让
 * image + mask 场景能渲染出真实像素、量化真实成本。该生成器纯 AWT、无 IO、无 Bukkit，是个安全的
 * 一行注入。
 *
 * <h3>已知局限</h3>
 * <ul>
 *   <li>合成图固定 256×256 RGB 渐变，无 alpha；真实上传图尺寸 / 通道 / 解码路径不同，
 *       image 场景成本是「代表性近似」而非「逐文件精确」。</li>
 *   <li>IconElement（矢量 SVG）仍走占位——本工厂用 3 参构造，未注入 {@code IconRegistry}，
 *       SVG icon 渲染退占位。icon 真实成本需在不拉起插件运行期的前提下，离线构造
 *       一个无头 IconRegistry。</li>
 * </ul>
 */
public final class BenchCompositor {

    /** classpath 上构建期由 Gradle {@code generatePalette} 注入的 248 色调色板 JSON。 */
    private static final String PALETTE_RESOURCE = "/palette.json";

    /** 合成渐变图边长（px）；够大让蒙版 clip / dither 路径有代表性像素量，又不至于拖慢 warmup。 */
    private static final int SYNTHETIC_IMAGE_SIZE = 256;

    private BenchCompositor() {
        // 工具类，禁实例化
    }

    /**
     * 构建一个无头、可渲染 benchmark 场景的 {@link CanvasCompositor}。
     *
     * <p>装配步骤（与 {@code RendererSnapshotTest#setUp} 完全一致，外加合成图片加载器）：</p>
     * <ol>
     *   <li>从 classpath 加载 {@link PaletteLut}（{@code /palette.json}）</li>
     *   <li>构造 {@link FontRegistry} 并 {@code loadBuiltIn()} 内置字体矩阵</li>
     *   <li>调最小 3 参构造 {@code new CanvasCompositor(paletteLut, fontRegistry, log)}</li>
     *   <li>注入合成渐变图片加载器，让 image / mask 场景渲染真实像素</li>
     * </ol>
     *
     * @param log 日志器；透传给 {@link FontRegistry} 与 {@link CanvasCompositor}
     * @return 可被多线程并发调用的无头 compositor
     * @throws IllegalStateException 调色板 / 字体加载失败时抛出（包装原因，<b>不吞</b>）——
     *                               benchmark 没有调色板 / 字体就无法得到有意义的渲染成本，
     *                               必须让调用方立即看到根因而非静默产出垃圾数据
     */
    public static CanvasCompositor create(Logger log) {
        PaletteLut paletteLut;
        try {
            paletteLut = PaletteLut.loadFromClasspath(PALETTE_RESOURCE);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "Benchmark 无法加载调色板 " + PALETTE_RESOURCE
                            + "（确认构建期 generatePalette 已注入 classpath）", e);
        }

        FontRegistry fontRegistry = new FontRegistry(log);
        try {
            fontRegistry.loadBuiltIn();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Benchmark 无法加载内置字体矩阵（FontRegistry.loadBuiltIn 失败）", e);
        }

        CanvasCompositor compositor = new CanvasCompositor(paletteLut, fontRegistry, log);
        // 合成渐变图片加载器。纯 AWT、确定性、无 IO / 无 Bukkit。
        // 对任意 source（含未知 hash）都返回同一张渐变图，让 ImageElement / mask 场景渲染真实
        // 像素而非占位；从而量化真实的图片解码替身 + 蒙版 clip + dither 成本。
        compositor.setImageLoader(source -> SYNTHETIC_GRADIENT);
        return compositor;
    }

    /**
     * 合成渐变图<b>只建一次</b>。生产的 loader 是 {@code imageStorage::load}，带 TTL LRU 内存缓存；
     * 而 {@code ImageRenderer} 对每个 ImageElement 每次 rasterize 都会调一次 loader —— 以前这里
     * 每次调用都跑 65536 次 {@code setRGB} 重建 256×256，夹具生成成本被算进了被测的 rasterize 里，
     * image / mask 场景的数字整体虚高，与 Benchmark 原则 1「跑真实渲染代码路径」相悖。
     *
     * <p>对任意 source 本来就返回同一张图，提成常量不改变确定性。渲染侧只读不改这张图。</p>
     */
    private static final BufferedImage SYNTHETIC_GRADIENT = syntheticGradient();

    /**
     * 生成一张确定性 256×256 RGB 渐变图（无 IO），供 {@link #SYNTHETIC_GRADIENT} 初始化。
     *
     * <p>红 = x 方向线性、绿 = y 方向线性、蓝 = 固定 0x80，构成一张对调色板量化有代表性
     * （覆盖大量不同颜色 → 触发 LUT 大量 distinct 匹配）的图，保证 benchmark 跨 iteration 可比。</p>
     *
     * @return 256×256 {@code TYPE_INT_RGB} 渐变图
     */
    private static BufferedImage syntheticGradient() {
        int size = SYNTHETIC_IMAGE_SIZE;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            int gChannel = (y * 255) / (size - 1);
            for (int x = 0; x < size; x++) {
                int rChannel = (x * 255) / (size - 1);
                int rgb = (rChannel << 16) | (gChannel << 8) | 0x80;
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }
}
