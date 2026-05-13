package moe.hikari.canvas.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

/**
 * M9-B marker（path 端点装饰）渲染。支持 {@code arrow} 三角形与 {@code dot} 圆点。
 *
 * <p><b>几何约定：</b></p>
 * <ul>
 *   <li>{@code arrow}：apex 在 path 端点上；base 在 apex 朝外退 {@code size} 距离。
 *       base 两顶点 = base center ± perpendicular × (size/2)。整体填实色 = stroke color。</li>
 *   <li>{@code dot}：圆心在 path 端点上；半径 = {@code max(2, strokeWidth+1)}。</li>
 * </ul>
 *
 * <p><b>方向：</b> {@code dirX/dirY} 是 path 端点向外的单位向量。markerEnd 时
 * = path 末段切线（沿 path 走向）；markerStart 时 = 起段切线的反向。
 * {@link #drawArrow} / {@link #drawDot} 接受归一化向量，长度不为 1 时仍工作但形状会失真。</p>
 */
public final class MarkerRenderer {

    private MarkerRenderer() {}

    /** arrow size = max(MIN_ARROW_SIZE, strokeWidth × 3)。 */
    public static int arrowSize(int strokeWidth) {
        return Math.max(6, strokeWidth * 3);
    }

    /** dot radius = max(MIN_DOT_RADIUS, strokeWidth + 1)。 */
    public static int dotRadius(int strokeWidth) {
        return Math.max(2, strokeWidth + 1);
    }

    /**
     * 在 (apexX, apexY) 画一个朝 (dirX, dirY) 方向的实心三角形 arrow。
     * 调用方应预先 setColor；本方法只用 {@link Graphics2D#fill}。
     */
    public static void drawArrow(Graphics2D g, double apexX, double apexY,
                                 double dirX, double dirY, int size, Color color) {
        // 归一化 dir（容错调用方传非单位向量）
        double len = Math.hypot(dirX, dirY);
        if (len < 1e-9) return;  // 无方向：跳过
        double dx = dirX / len;
        double dy = dirY / len;
        // base center 在 apex 朝 dir 反方向退 size
        double bcx = apexX - dx * size;
        double bcy = apexY - dy * size;
        // 垂直方向
        double px = -dy;
        double py = dx;
        double halfBase = size * 0.5;
        double leftX = bcx + px * halfBase;
        double leftY = bcy + py * halfBase;
        double rightX = bcx - px * halfBase;
        double rightY = bcy - py * halfBase;

        Path2D.Double tri = new Path2D.Double();
        tri.moveTo(apexX, apexY);
        tri.lineTo(leftX, leftY);
        tri.lineTo(rightX, rightY);
        tri.closePath();

        Color prev = g.getColor();
        g.setColor(color);
        g.fill(tri);
        g.setColor(prev);
    }

    /** 在 (cx, cy) 画一个实心圆 dot。半径含义见 {@link #dotRadius}。 */
    public static void drawDot(Graphics2D g, double cx, double cy, int radius, Color color) {
        if (radius <= 0) return;
        Ellipse2D.Double e = new Ellipse2D.Double(cx - radius, cy - radius,
                radius * 2.0, radius * 2.0);
        Color prev = g.getColor();
        g.setColor(color);
        g.fill(e);
        g.setColor(prev);
    }
}
