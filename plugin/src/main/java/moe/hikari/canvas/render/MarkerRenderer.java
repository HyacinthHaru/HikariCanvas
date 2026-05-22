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

    /** arrow size 上限（像素）。超过此值视觉上箭头过大，与直线粗细不成比例。 */
    public static final int MAX_ARROW_SIZE = 40;
    /** dot radius 上限。 */
    public static final int MAX_DOT_RADIUS = 16;
    /**
     * marker 占用 element 对角线的最大比例。0.4 = 至少留 60% 给直线段，
     * 避免 stroke 极粗时短箭头被 marker 完全吃掉。
     */
    public static final double MARKER_MAX_RATIO_OF_LENGTH = 0.4;

    /**
     * arrow size 公式（0.4.7 修）。
     *
     * <p>原 {@code max(6, stroke × 3)} 在 stroke 大时无上限增长，stroke=20 → 60，
     * stroke=30 → 90，导致短箭头被 marker 完全吞没（用户拖 50px 直线 + stroke 20 →
     * arrow 60 比直线还长）。</p>
     *
     * <p>新公式：{@code clamp(6, stroke × 2.5 + 4, 40)} —— 平滑增长 + 40px 硬上限。
     * stroke 1..20 平滑过渡到 6..54 → clamp 到 40；视觉上箭头大小与直线粗细保持
     * 美观比例（约 2-4 倍粗细），不会无限放大。</p>
     */
    public static int arrowSize(int strokeWidth) {
        int raw = (int) Math.round(strokeWidth * 2.5 + 4);
        return Math.max(6, Math.min(MAX_ARROW_SIZE, raw));
    }

    /**
     * 0.4.7：element-aware cap 重载。
     * caller 传 element 对角线（{@code hypot(p.w, p.h)}），marker 自动收缩不超过
     * 长度的 {@link #MARKER_MAX_RATIO_OF_LENGTH}，保证短箭头不被吞没。
     * 仍保留 {@code MIN=6} 让极短元素的箭头也可见（虽然占比可能 > 40%）。
     */
    public static int arrowSize(int strokeWidth, double elementDiagonal) {
        int base = arrowSize(strokeWidth);
        if (elementDiagonal <= 0) return base;
        int byLength = (int) Math.round(elementDiagonal * MARKER_MAX_RATIO_OF_LENGTH);
        return Math.max(6, Math.min(base, byLength));
    }

    /**
     * dot radius 公式（0.4.7 修）。
     *
     * <p>原 {@code max(2, stroke + 1)} 同样无上限。改 {@code clamp(2, stroke+1, 16)}
     * 让 stroke 极粗时 dot 半径锁定在 16px（直径 32px），与 arrow 上限协调。</p>
     */
    public static int dotRadius(int strokeWidth) {
        return Math.max(2, Math.min(MAX_DOT_RADIUS, strokeWidth + 1));
    }

    /** 0.4.7：element-aware cap 重载，同 arrowSize。 */
    public static int dotRadius(int strokeWidth, double elementDiagonal) {
        int base = dotRadius(strokeWidth);
        if (elementDiagonal <= 0) return base;
        // dot 半径 cap 取对角线的一半再乘比例（dot 直径 = 半径 × 2，所以 cap 是 ratio/2）
        int byLength = (int) Math.round(elementDiagonal * MARKER_MAX_RATIO_OF_LENGTH * 0.5);
        return Math.max(2, Math.min(base, byLength));
    }

    /**
     * 构造 arrow 三角形几何（不绘制）。供 {@link #drawArrow} 用，也供 CanvasCompositor 在
     * 描边前从 stroke clip 中 subtract，避免「粗 stroke 在 arrow 锥尖处突破 arrow 边界」
     * （2026-05-15 修 Bug）。
     */
    public static Path2D.Double arrowShape(double apexX, double apexY,
                                           double dirX, double dirY, int size) {
        Path2D.Double tri = new Path2D.Double();
        double len = Math.hypot(dirX, dirY);
        if (len < 1e-9) return tri;
        double dx = dirX / len;
        double dy = dirY / len;
        double bcx = apexX - dx * size;
        double bcy = apexY - dy * size;
        double px = -dy;
        double py = dx;
        double halfBase = size * 0.5;
        double leftX = bcx + px * halfBase;
        double leftY = bcy + py * halfBase;
        double rightX = bcx - px * halfBase;
        double rightY = bcy - py * halfBase;
        tri.moveTo(apexX, apexY);
        tri.lineTo(leftX, leftY);
        tri.lineTo(rightX, rightY);
        tri.closePath();
        return tri;
    }

    /**
     * 在 (apexX, apexY) 画一个朝 (dirX, dirY) 方向的实心三角形 arrow。
     * 调用方应预先 setColor；本方法只用 {@link Graphics2D#fill}。
     */
    public static void drawArrow(Graphics2D g, double apexX, double apexY,
                                 double dirX, double dirY, int size, Color color) {
        if (Math.hypot(dirX, dirY) < 1e-9) return;
        Path2D.Double tri = arrowShape(apexX, apexY, dirX, dirY, size);
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
