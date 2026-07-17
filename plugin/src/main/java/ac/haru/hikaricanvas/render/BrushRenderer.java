package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.BrushPoint;
import ac.haru.hikaricanvas.state.BrushStrokeElement;
import ac.haru.hikaricanvas.state.Element;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.geom.Path2D;
import java.util.List;

/**
 * 笔触绘制：Catmull-Rom 拟合相邻点 → 每段画一段 cubic Bezier；
 * 段宽度 / 段 alpha 按 {@code pressureSize / pressureOpacity} 取值。
 *
 * <p><b>Catmull-Rom → Bezier 公式：</b></p>
 * <pre>
 *   给定 P0, P1, P2, P3 四个点，P1 → P2 段：
 *     B0 = P1
 *     B1 = P1 + (P2 - P0) / 6
 *     B2 = P2 - (P3 - P1) / 6
 *     B3 = P2
 *   两端用 phantom（P[-1] = P[0]，P[n] = P[n-1]）。
 * </pre>
 *
 * <p><b>变宽 / 变 alpha：</b> AWT 没有原生"沿曲线变宽"，所以每段单独 setStroke + setComposite，
 * 段宽度 = {@code size × avgPressure}；段 alpha = {@code outerAlpha × pressureAlpha}（与外层
 * element-level opacity 复合，避免覆盖）。视觉上段间会有微小阶梯，但相邻段平均压感相近，
 * 实际不可见。</p>
 */
public final class BrushRenderer implements ElementRenderer {

    @Override
    public void draw(Graphics2D g, Element e, RenderContext ctx) {
        BrushStrokeElement b = (BrushStrokeElement) e;
        List<BrushPoint> pts = b.points();
        if (pts == null || pts.size() < 2) return;
        Paint paint = FillPaintBuilder.fillToPaint(b.fill(), b.x(), b.y(), b.w(), b.h());
        g.setPaint(paint);

        // 抓外层 composite alpha（来自 drawElementsTo 设置的 element.opacity SrcOver）
        Composite outerComposite = g.getComposite();
        float outerAlpha = 1f;
        if (outerComposite instanceof AlphaComposite ac) {
            outerAlpha = ac.getAlpha();
        }

        int n = pts.size();
        for (int i = 0; i < n - 1; i++) {
            BrushPoint p1 = pts.get(i);
            BrushPoint p2 = pts.get(i + 1);
            BrushPoint p0 = i > 0 ? pts.get(i - 1) : p1;
            BrushPoint p3 = i < n - 2 ? pts.get(i + 2) : p2;

            // 全局坐标（element 已 translate 时此处仍是 element-local；drawElementsTo 不 translate
            // 所以加 b.x() / b.y() 转 stage 坐标）
            double x1 = b.x() + p1.x(), y1 = b.y() + p1.y();
            double x2 = b.x() + p2.x(), y2 = b.y() + p2.y();
            double x0 = b.x() + p0.x(), y0 = b.y() + p0.y();
            double x3 = b.x() + p3.x(), y3 = b.y() + p3.y();

            // C-R → Bezier 控制点
            double cx1 = x1 + (x2 - x0) / 6.0;
            double cy1 = y1 + (y2 - y0) / 6.0;
            double cx2 = x2 - (x3 - x1) / 6.0;
            double cy2 = y2 - (y3 - y1) / 6.0;

            // 段宽度（平均压感 × size，pressureSize 关闭时 = size）
            double avgPressure = (p1.pressure() + p2.pressure()) / 2.0;
            double segWidth = b.pressureSize()
                    ? b.size() * Math.max(0.05, avgPressure)  // 0 压感 fallback 0.05 防消失
                    : b.size();
            if (segWidth < 1.0) segWidth = 1.0;

            // 段 alpha（与外层 element.opacity 复合）
            float segAlpha = outerAlpha;
            if (b.pressureOpacity()) {
                segAlpha *= (float) Math.max(0.05, avgPressure);
            }
            if (segAlpha < 1.0f) {
                g.setComposite(AlphaComposite.SrcOver.derive(Math.min(1f, segAlpha)));
            } else {
                g.setComposite(outerComposite);
            }

            g.setStroke(new BasicStroke((float) segWidth,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            Path2D.Double curve = new Path2D.Double();
            curve.moveTo(x1, y1);
            curve.curveTo(cx1, cy1, cx2, cy2, x2, y2);
            g.draw(curve);
        }
        // 还原外层 composite，避免污染后续 element
        g.setComposite(outerComposite);
    }
}
