package ac.haru.hikaricanvas.render;

import java.util.logging.Logger;

/**
 * 共享给所有 {@link ElementRenderer} 的渲染上下文。无自身可变状态（{@code imageLoader} 经
 * {@link ImageLoaderHolder} 读取 {@link CanvasCompositor} 上的 {@code volatile} 引用）。
 */
public final class RenderContext {

    private final PaletteLut paletteLut;
    private final FontRegistry fontRegistry;
    private final IconRegistry iconRegistry;
    private final Logger log;
    private final ImageLoaderHolder imageLoaderHolder;

    /**
     * 含 {@link IconRegistry} 注入，供 {@link IconRenderer} 矢量 path 渲染查 path d / viewBox。
     * {@code null} = 测试场景，{@link IconRenderer} 自动画占位框。
     */
    public RenderContext(PaletteLut paletteLut, FontRegistry fontRegistry,
                         IconRegistry iconRegistry, Logger log,
                         ImageLoaderHolder imageLoaderHolder) {
        this.paletteLut = paletteLut;
        this.fontRegistry = fontRegistry;
        this.iconRegistry = iconRegistry;
        this.log = log;
        this.imageLoaderHolder = imageLoaderHolder;
    }

    public PaletteLut paletteLut() { return paletteLut; }
    public FontRegistry fontRegistry() { return fontRegistry; }
    public IconRegistry iconRegistry() { return iconRegistry; }
    public Logger log() { return log; }
    public CanvasCompositor.ImageLoader imageLoader() {
        return imageLoaderHolder.get();
    }

    /** 通过 holder 暴露 imageLoader 引用，让 CanvasCompositor.setImageLoader 写后立即可见。 */
    @FunctionalInterface
    public interface ImageLoaderHolder {
        CanvasCompositor.ImageLoader get();
    }
}
