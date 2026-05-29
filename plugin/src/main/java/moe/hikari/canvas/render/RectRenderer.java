package moe.hikari.canvas.render;

import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.RectElement;
import moe.hikari.canvas.state.Stroke;

import java.awt.Graphics2D;

/**
 * RectElement 绘制。fill + 4 个 fillRect 边框（与 M3 行为一致，避免 BasicStroke 半像素分摊）。
 * 拆分自 god class {@code CanvasCompositor}（2026-05-14）。
 */
public final class RectRenderer implements ElementRenderer {

    @Override
    public void draw(Graphics2D g, Element e, RenderContext ctx) {
        RectElement r = (RectElement) e;
        // M16 P3.1：渲染层兜底（ElementValidator.validateDim 已在协议入口拒 w/h≤0，
        // 但模板 raw_state / 旧数据迁移路径可能漏校验）；负 / 零尺寸 → 不画
        if (r.w() <= 0 || r.h() <= 0) return;
        if (r.fill() != null) {
            g.setPaint(FillPaintBuilder.fillToPaint(r.fill(), r.x(), r.y(), r.w(), r.h()));
            g.fillRect(r.x(), r.y(), r.w(), r.h());
        }
        Stroke s = r.stroke();
        if (s != null && s.width() > 0) {
            int sw = Math.min(s.width(), Math.max(1, Math.min(r.w(), r.h()) / 2));
            g.setColor(FillPaintBuilder.parseColor(s.color()));
            // BasicStroke 的 drawRect 会把线宽分摊在矩形边界两侧，像素对齐不精确。
            // 整数像素画法：用 4 个 fillRect 手工画边框，与 M3 行为一致、稳定可控
            g.fillRect(r.x(), r.y(), r.w(), sw);
            g.fillRect(r.x(), r.y() + r.h() - sw, r.w(), sw);
            g.fillRect(r.x(), r.y(), sw, r.h());
            g.fillRect(r.x() + r.w() - sw, r.y(), sw, r.h());
        }
    }
}
