package moe.hikari.canvas.benchmark;

import moe.hikari.canvas.render.CanvasCompositor;
import moe.hikari.canvas.render.FontRegistry;
import moe.hikari.canvas.render.PaletteLut;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * 0.5.0 Benchmark P1：构建一个 <b>纯无头</b>（headless）{@link CanvasCompositor} 的工厂，
 * 让 {@code SceneTimer} / {@code /canvas bench} / CI harness 在不依赖 Bukkit / 世界 / 玩家 /
 * 网络的前提下，光栅化 + 量化 benchmark 场景，测纯 CPU / 内存渲染成本。
 *
 * <h2>为什么单独一个工厂</h2>
 * 生产路径（{@code HikariCanvas.onEnable}）走 5 参完整构造（含 TemplateAssetService /
 * IconRegistry / VariableSupport）；那条路径需要插件运行期的一堆服务实例，benchmark 无法也不该
 * 拉起。本工厂复刻 {@code RendererSnapshotTest} 的「已验证无头装配」：仅 {@link PaletteLut} +
 * {@link FontRegistry} + {@link Logger} 三件套，调最小 3 参构造。这正是 snapshot 测试每天在 CI 上
 * 跑的同一条路径——稳定可信、零外部依赖、线程安全（{@link CanvasCompositor} 无可变状态、每次
 * {@code rasterize} 分配新 buffer）。
 *
 * <h2>合成图片加载器（P1 增强）</h2>
 * {@link CanvasCompositor#setImageLoader} 是一个干净的 SAM 注入口（{@code BufferedImage load(String)}，
 * 零 Bukkit）。生产环境注入 {@code ImageStorage::load} 从磁盘读真实上传文件；benchmark 场景里没有
 * 磁盘文件，若不注入则所有 ImageElement / 蒙版场景会走「文件缺失占位」分支，渲染成本失真（占位是
 * 一个轻量灰框，远低于真实图片解码 + 蒙版 clip + dither 的开销）。这里注入一个
 * <b>内存合成渐变生成器</b>：对<b>任意</b> hash 都确定性地返回一张 256×256 的 RGB 渐变图，让
 * image + mask 场景能渲染出真实像素、量化真实成本。该生成器纯 AWT、无 IO、无 Bukkit，是个安全的
 * 一行注入。
 *
 * <h3>已知局限（留 P2 精化）</h3>
 * <ul>
 *   <li>合成图固定 256×256 RGB 渐变，无 alpha；真实上传图尺寸 / 通道 / 解码路径不同，
 *       image 场景成本是「代表性近似」而非「逐文件精确」。P2 可按场景声明的尺寸生成。</li>
 *   <li>IconElement（矢量 SVG）仍走占位——本工厂用 3 参构造，未注入 {@code IconRegistry}，
 *       SVG icon 渲染退占位。icon 真实成本同样留 P2（需在不拉起插件运行期的前提下，离线构造
 *       一个无头 IconRegistry）。</li>
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
        // P1 增强：合成渐变图片加载器。纯 AWT、确定性、无 IO / 无 Bukkit。
        // 对任意 source（含未知 hash）都返回同一张渐变图，让 ImageElement / mask 场景渲染真实
        // 像素而非占位；从而量化真实的图片解码替身 + 蒙版 clip + dither 成本。
        compositor.setImageLoader(BenchCompositor::syntheticGradient);
        return compositor;
    }

    /**
     * 为任意 {@code source} 生成一张确定性 256×256 RGB 渐变图（无 IO）。
     *
     * <p>红 = x 方向线性、绿 = y 方向线性、蓝 = 固定 0x80，构成一张对调色板量化有代表性
     * （覆盖大量不同颜色 → 触发 LUT 大量 distinct 匹配）的图。同一进程内重复调用对同一
     * source 返回等价像素（确定性），保证 benchmark 跨 iteration 可比。</p>
     *
     * @param source ImageElement 的 sha256 hash；此处忽略具体值（任意 hash 都给同一渐变）
     * @return 256×256 {@code TYPE_INT_RGB} 渐变图
     */
    private static BufferedImage syntheticGradient(String source) {
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
