package moe.hikari.canvas.render;

import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.PathElement;
import moe.hikari.canvas.state.Stroke;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;

/**
 * 绘制 path 元素。d 内坐标相对 element.(x, y)，所以临时 translate 后绘制；marker 在
 * path 端点上，方向由 {@link PathParser} 提取的切线决定。
 *
 * <p>拆分自 god class {@code CanvasCompositor}（2026-05-14）。</p>
 */
public final class PathRenderer implements ElementRenderer {

    @Override
    public void draw(Graphics2D g, Element e, RenderContext ctx) {
        PathElement p = (PathElement) e;
        if (p.d() == null || p.d().isEmpty()) return;
        PathParser.Result parsed = PathParser.parse(p.d());
        if (parsed.path() == null) return;

        AffineTransform savedTx = g.getTransform();
        g.translate(p.x(), p.y());

        // 填充
        if (p.fill() != null) {
            g.setPaint(FillPaintBuilder.fillToPaint(p.fill(), 0, 0, p.w(), p.h()));
            g.fill(parsed.path());
        }

        // 描边
        Stroke s = p.stroke();
        Color strokeColor = null;
        int strokeWidth = 0;
        if (s != null && s.width() > 0) {
            strokeWidth = s.width();
            strokeColor = FillPaintBuilder.parseColor(s.color());
            g.setColor(strokeColor);
            java.awt.Stroke prevStroke = g.getStroke();
            g.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            // 2026-05-15 修箭头 Bug：arrow marker 在 apex 处宽度 = 0（三角形收尖），
            // stroke 直线宽 strokeWidth。Visual：arrow tip 附近 distance < strokeWidth/3 范围内
            // arrow 比 stroke 窄 → stroke 矩形从 arrow 锥尖处突出。修法：把 arrow 内部从
            // 描边 clip 中扣掉，让 stroke 不画进 arrow 三角形，arrow 自己 fill 覆盖。
            Shape savedClip = g.getClip();
            Shape strokeClip = buildArrowSubtractedClip(savedClip, parsed, p, strokeWidth);
            if (strokeClip != null) {
                g.setClip(strokeClip);
            }
            g.draw(parsed.path());
            if (strokeClip != null) {
                g.setClip(savedClip);
            }
            g.setStroke(prevStroke);
        }

        // marker（需要 stroke 才有意义；color 沿用 stroke.color）
        if (parsed.hasSegments() && strokeColor != null) {
            if (p.markerEnd() != null) {
                drawMarker(g, p.markerEnd(),
                        parsed.endX(), parsed.endY(),
                        parsed.endTangentX(), parsed.endTangentY(),
                        strokeWidth, strokeColor);
            }
            if (p.markerStart() != null) {
                // markerStart 朝起点外 = startTangent 反向
                drawMarker(g, p.markerStart(),
                        parsed.startX(), parsed.startY(),
                        -parsed.startTangentX(), -parsed.startTangentY(),
                        strokeWidth, strokeColor);
            }
        }

        g.setTransform(savedTx);
    }

    /**
     * 若 path 有 arrow marker，把 arrow 三角形从 stroke clip 中扣掉。返回 null = 无 arrow，
     * 调用方不用改 clip。dot marker 不参与（圆形 marker 与直线在端点重叠不会"突破"）。
     */
    private static Shape buildArrowSubtractedClip(Shape baseClip,
                                                  PathParser.Result parsed,
                                                  PathElement p, int strokeWidth) {
        boolean hasEndArrow = "arrow".equals(p.markerEnd());
        boolean hasStartArrow = "arrow".equals(p.markerStart());
        if (!hasEndArrow && !hasStartArrow) return null;
        if (!parsed.hasSegments()) return null;

        int arrowSize = MarkerRenderer.arrowSize(strokeWidth);
        Area subtractClip = baseClip == null
                ? new Area(new Rectangle2D.Double(-1e6, -1e6, 2e6, 2e6))
                : new Area(baseClip);
        if (hasEndArrow) {
            subtractClip.subtract(new Area(MarkerRenderer.arrowShape(
                    parsed.endX(), parsed.endY(),
                    parsed.endTangentX(), parsed.endTangentY(), arrowSize)));
        }
        if (hasStartArrow) {
            subtractClip.subtract(new Area(MarkerRenderer.arrowShape(
                    parsed.startX(), parsed.startY(),
                    -parsed.startTangentX(), -parsed.startTangentY(), arrowSize)));
        }
        return subtractClip;
    }

    private static void drawMarker(Graphics2D g, String type, double x, double y,
                                   double dirX, double dirY, int strokeWidth, Color color) {
        switch (type) {
            case "arrow" -> MarkerRenderer.drawArrow(g, x, y, dirX, dirY,
                    MarkerRenderer.arrowSize(strokeWidth), color);
            case "dot" -> MarkerRenderer.drawDot(g, x, y,
                    MarkerRenderer.dotRadius(strokeWidth), color);
            default -> { /* ignore unknown marker；EditSession 已限值，理论不可达 */ }
        }
    }
}
