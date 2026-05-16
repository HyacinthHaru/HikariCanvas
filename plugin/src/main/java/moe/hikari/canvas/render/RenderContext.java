package moe.hikari.canvas.render;

import moe.hikari.canvas.template.asset.TemplateAssetService;

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
    private final Logger log;
    private final ImageLoaderHolder imageLoaderHolder;

    public RenderContext(PaletteLut paletteLut, FontRegistry fontRegistry,
                         TemplateAssetService assetService, Logger log,
                         ImageLoaderHolder imageLoaderHolder) {
        this.paletteLut = paletteLut;
        this.fontRegistry = fontRegistry;
        this.assetService = assetService;
        this.log = log;
        this.imageLoaderHolder = imageLoaderHolder;
    }

    public PaletteLut paletteLut() { return paletteLut; }
    public FontRegistry fontRegistry() { return fontRegistry; }
    public TemplateAssetService assetService() { return assetService; }
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
