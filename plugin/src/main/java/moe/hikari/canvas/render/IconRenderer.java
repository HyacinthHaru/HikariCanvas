package moe.hikari.canvas.render;

import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.IconElement;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 绘制图标元素。{@code tint} 非空时对图标 alpha 做"source-in"染色 —— 保留 alpha 形状、
 * 整体替换为 tint 色（适合纯白图标 + 主题色染色）。
 *
 * <p>拆分自 god class {@code CanvasCompositor}（2026-05-14）。</p>
 */
public final class IconRenderer implements ElementRenderer {

    @Override
    public void draw(Graphics2D g, Element e, RenderContext ctx) {
        IconElement ic = (IconElement) e;
        if (ctx.assetService() == null) {
            ctx.log().warning("[compositor] IconElement '" + ic.id() + "' but no assetService bound");
            return;
        }
        BufferedImage img = ctx.assetService().loadIcon(ic.source());
        if (img == null) {
            // 占位：画个虚线方框 + ?，方便定位"图标 source 错"
            g.setColor(new Color(0xAAAAAA));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    1f, new float[]{3f, 2f}, 0f));
            g.drawRect(ic.x(), ic.y(), Math.max(1, ic.w() - 1), Math.max(1, ic.h() - 1));
            g.setStroke(new BasicStroke(1f));
            g.drawString("?", ic.x() + ic.w() / 2 - 3, ic.y() + ic.h() / 2 + 4);
            return;
        }
        if (ic.tint() == null || ic.tint().isBlank()) {
            // 原色直接缩放绘制
            g.drawImage(img, ic.x(), ic.y(), ic.w(), ic.h(), null);
            return;
        }
        // 染色：先画 tinted 形状 (source-in 合成) 到临时图，再贴
        BufferedImage tinted = new BufferedImage(ic.w(), ic.h(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D tg = tinted.createGraphics();
        try {
            tg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            tg.drawImage(img, 0, 0, ic.w(), ic.h(), null);
            tg.setComposite(AlphaComposite.SrcIn);
            tg.setColor(FillPaintBuilder.parseColor(ic.tint()));
            tg.fillRect(0, 0, ic.w(), ic.h());
        } finally {
            tg.dispose();
        }
        g.drawImage(tinted, ic.x(), ic.y(), null);
    }
}
