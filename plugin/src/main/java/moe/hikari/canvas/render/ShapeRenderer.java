package moe.hikari.canvas.render;

import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.ShapeElement;
import moe.hikari.canvas.state.Stroke;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;

/**
 * 绘制正多边形 / 星。外接圆中心在 bbox 中心；半径 = min(w, h) / 2。
 * 第一个顶点朝上（角度 -π/2）；后续顶点等角度分布。star 时外内交替（外用 outerR，内用 outerR × innerRatio）。
 *
 * <p>拆分自 god class {@code CanvasCompositor}（2026-05-14）。</p>
 */
public final class ShapeRenderer implements ElementRenderer {

    @Override
    public void draw(Graphics2D g, Element e, RenderContext ctx) {
        ShapeElement s = (ShapeElement) e;
        Path2D.Double path = buildShapePath(s);
        if (s.fill() != null) {
            g.setPaint(FillPaintBuilder.fillToPaint(s.fill(), s.x(), s.y(), s.w(), s.h()));
            g.fill(path);
        }
        Stroke st = s.stroke();
        if (st != null && st.width() > 0) {
            g.setColor(FillPaintBuilder.parseColor(st.color()));
            java.awt.Stroke prevStroke = g.getStroke();
            g.setStroke(new BasicStroke(st.width(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(path);
            g.setStroke(prevStroke);
        }
    }

    static Path2D.Double buildShapePath(ShapeElement s) {
        double cx = s.x() + s.w() / 2.0;
        double cy = s.y() + s.h() / 2.0;
        double outerR = Math.min(s.w(), s.h()) / 2.0;
        boolean star = "star".equals(s.kind());
        double innerR = star
                ? outerR * (s.innerRatio() == null ? 0.5 : s.innerRatio())
                : 0;
        int sides = s.sides();
        int totalVerts = star ? sides * 2 : sides;

        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < totalVerts; i++) {
            double angle = -Math.PI / 2 + (Math.PI * 2 * i / totalVerts);
            double r = (star && (i & 1) == 1) ? innerR : outerR;
            double x = cx + Math.cos(angle) * r;
            double y = cy + Math.sin(angle) * r;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.closePath();
        return path;
    }
}
