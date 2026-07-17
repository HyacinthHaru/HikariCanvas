package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.CircleElement;
import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.Stroke;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;

/**
 * 绘制 circle / 椭圆。bbox 推 cx/cy/rx/ry：cx = x + w/2, cy = y + h/2, rx = w/2, ry = h/2。
 */
public final class CircleRenderer implements ElementRenderer {

    @Override
    public void draw(Graphics2D g, Element e, RenderContext ctx) {
        CircleElement c = (CircleElement) e;
        // 渲染层兜底；w/h ≤ 0 时半径 ≤ 0，Ellipse2D 行为退化 → 直接 return
        if (c.w() <= 0 || c.h() <= 0) return;
        Ellipse2D.Double el = new Ellipse2D.Double(c.x(), c.y(), c.w(), c.h());
        if (c.fill() != null) {
            g.setPaint(FillPaintBuilder.fillToPaint(c.fill(), c.x(), c.y(), c.w(), c.h()));
            g.fill(el);
        }
        Stroke s = c.stroke();
        if (s != null && s.width() > 0) {
            g.setColor(FillPaintBuilder.parseColor(s.color()));
            java.awt.Stroke prevStroke = g.getStroke();
            g.setStroke(new BasicStroke(s.width(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g.draw(el);
            g.setStroke(prevStroke);
        }
    }
}
