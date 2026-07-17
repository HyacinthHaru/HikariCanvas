package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.Element;

import java.awt.Graphics2D;

/**
 * 单 element 类型的几何绘制接口。{@code CanvasCompositor.drawElementBody} 按 {@link Element}
 * 子类型 dispatch 到具体实现；implementations 持 {@link RenderContext}（构造注入）。
 *
 * <p>{@code sealed} 收口 8 个子类，新增 element 类型时编译期失败提醒补 renderer。</p>
 */
public sealed interface ElementRenderer
        permits TextRenderer, RectRenderer, IconRenderer, PathRenderer,
                CircleRenderer, ShapeRenderer, BrushRenderer, ImageRenderer {

    /**
     * 在 {@code g} 上画 {@code e}。调用方已处理 rotation / element.opacity / dither，
     * 此方法只负责 element body 的几何绘制。
     */
    void draw(Graphics2D g, Element e, RenderContext ctx);
}
