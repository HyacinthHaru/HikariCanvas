package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.template.asset.TemplateAssetService;

import java.util.logging.Logger;

/**
 * 共享给所有 {@link ElementRenderer} 的渲染上下文。无可变状态（{@code imageLoader} 由
 * {@link CanvasCompositor#setImageLoader} 通过 ctx getter 反射读取，本身 volatile）。
 *
 * <p>拆分自原 {@code CanvasCompositor}（god class 拆分，2026-05-14）。</p>
 */
public final class RenderContext {

    private final PaletteLut paletteLut;
    private final FontRegistry fontRegistry;
    private final TemplateAssetService assetService;
    private final IconRegistry iconRegistry;
    private final Logger log;
    private final ImageLoaderHolder imageLoaderHolder;

    public RenderContext(PaletteLut paletteLut, FontRegistry fontRegistry,
                         TemplateAssetService assetService, Logger log,
                         ImageLoaderHolder imageLoaderHolder) {
        this(paletteLut, fontRegistry, assetService, null, log, imageLoaderHolder);
    }

    /**
     * 新增 {@link IconRegistry} 注入，供 {@link IconRenderer} 矢量 path 渲染查 path d / viewBox。
     * null = 测试 / 老路径，IconRenderer 自动降级（matrix 元素走 legacy PNG，新 SVG 元素走占位）。
     */
    public RenderContext(PaletteLut paletteLut, FontRegistry fontRegistry,
                         TemplateAssetService assetService, IconRegistry iconRegistry,
                         Logger log, ImageLoaderHolder imageLoaderHolder) {
        this.paletteLut = paletteLut;
        this.fontRegistry = fontRegistry;
        this.assetService = assetService;
        this.iconRegistry = iconRegistry;
        this.log = log;
        this.imageLoaderHolder = imageLoaderHolder;
    }

    public PaletteLut paletteLut() { return paletteLut; }
    public FontRegistry fontRegistry() { return fontRegistry; }
    public TemplateAssetService assetService() { return assetService; }
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
