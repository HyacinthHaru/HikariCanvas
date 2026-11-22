package ac.haru.hikaricanvas.benchmark;

import ac.haru.hikaricanvas.render.CanvasCompositor;
import ac.haru.hikaricanvas.render.FontRegistry;
import ac.haru.hikaricanvas.render.PaletteLut;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * 构建无头（headless）{@link CanvasCompositor} 的工厂，供 {@link BenchmarkRunner}、
 * {@code /canvas bench} 与测试在不依赖 Bukkit / 世界 / 网络的条件下测量纯 CPU / 内存渲染成本。
 *
 * <h2>合成图片加载器</h2>
 * 生产环境通过 {@link CanvasCompositor#setImageLoader} 从磁盘缓存读取上传图片；压测场景无磁盘文件，
 * 故注入预生成的 256×256 RGB 渐变图 {@link #SYNTHETIC_GRADIENT}，使图片与蒙版裁剪路径走真实像素管线
 * 而非缺图占位框。
 *
 * <h3>已知局限</h3>
 * <ul>
 *   <li>合成图固定为无 alpha 的 256×256 RGB 渐变，与真实上传图片的尺寸及通道分布存在差异。</li>
 *   <li>未注入运行期 {@code IconRegistry}，{@code IconElement} 走占位绘制路径。</li>
 * </ul>
 */
public final class BenchCompositor {

    /** 构建期由 Gradle {@code generatePalette} 注入 classpath 的 248 色调色板 JSON。 */
    private static final String PALETTE_RESOURCE = "/palette.json";

    /** 合成渐变图边长（px）。 */
    private static final int SYNTHETIC_IMAGE_SIZE = 256;

    /**
     * 预构建的只读 256×256 RGB 渐变图，跨全部场景与迭代共享——对标生产 {@code ImageStorage}
     * 内存缓存命中路径，避免把夹具构造开销混入 {@code rasterize()} 计时。
     */
    private static final BufferedImage SYNTHETIC_GRADIENT = syntheticGradient();

    private BenchCompositor() {
    }

    /**
     * 构建无头 {@link CanvasCompositor}（加载 classpath 调色板、内置字体矩阵，并注入合成图片加载器）。
     *
     * @param log 日志器，透传给 {@link FontRegistry} 与 {@link CanvasCompositor}
     * @return 线程安全的无头合成器
     * @throws IllegalStateException 调色板或内置字体加载失败时抛出
     */
    public static CanvasCompositor create(Logger log) {
        PaletteLut paletteLut;
        try {
            paletteLut = PaletteLut.loadFromClasspath(PALETTE_RESOURCE);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "Failed to load built-in color palette (" + PALETTE_RESOURCE + ")", e);
        }

        FontRegistry fontRegistry = new FontRegistry(log);
        try {
            fontRegistry.loadBuiltIn();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Failed to load built-in fonts for benchmark", e);
        }

        CanvasCompositor compositor = new CanvasCompositor(paletteLut, fontRegistry, log);
        compositor.setImageLoader(source -> SYNTHETIC_GRADIENT);
        return compositor;
    }

    /** 生成确定性 256×256 RGB 渐变图（R 沿 X 线性、G 沿 Y 线性、B 固定 0x80，覆盖丰富色相以压测 LUT）。 */
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
