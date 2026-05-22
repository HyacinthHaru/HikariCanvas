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

    /**
     * marker 占用 element 对角线的最大比例。0.5 = 让 marker 最多占元素的一半，
     * 防短元素 + 粗 stroke 时 marker 完全溢出 element bbox。
     */
    public static final double MARKER_MAX_RATIO_OF_LENGTH = 0.5;
    /** 最小 arrow size（让小 stroke 时箭头也可见）。 */
    public static final int MIN_ARROW_SIZE = 8;
    /** 最小 dot radius。 */
    public static final int MIN_DOT_RADIUS = 3;

    /**
     * arrow size 公式（0.4.7 第二次修）。
     *
     * <p>关键比例：{@code base width / stroke width ≥ 3}，否则视觉上箭头 V 头跟
     * 直线粗矩形粘连难辨。stroke=20 → arrow=60 (base 是 stroke 3 倍 = "明显比直线宽")，
     * stroke=30 → arrow=90，让粗 stroke 时箭头随之放大保持清晰比例。</p>
     *
     * <p>不带硬上限——硬限 40 在 stroke=30 时让 base=40/stroke=30 比例仅 1.33×，糊得
     * 没法看；element-aware cap 由调用方传 element 对角线限制 marker 不溢出。</p>
     */
    public static int arrowSize(int strokeWidth) {
        return Math.max(MIN_ARROW_SIZE, strokeWidth * 3);
    }

    /**
     * 0.4.7：element-aware cap 重载。
     *
     * <p>caller 传 element 对角线，marker 自动 cap 到 {@code diag × MARKER_MAX_RATIO_OF_LENGTH}。
     * 但**仍保证 base ≥ stroke × 2**（视觉清晰度底线，比 element 比例优先级高）——若元素
     * 过短装不下，宁可让箭头略微溢出 bbox 也别糊掉。</p>
     */
    public static int arrowSize(int strokeWidth, double elementDiagonal) {
        int base = arrowSize(strokeWidth);
        if (elementDiagonal <= 0) return base;
        int byLength = (int) Math.round(elementDiagonal * MARKER_MAX_RATIO_OF_LENGTH);
        // 视觉清晰底线：base 至少 stroke × 2（否则跟直线视觉粘连）。优先级 > element cap
        int minByStroke = Math.max(MIN_ARROW_SIZE, strokeWidth * 2);
        return Math.max(minByStroke, Math.min(base, byLength));
    }

    /**
     * dot radius 公式（0.4.7 第二次修）。
     *
     * <p>{@code stroke + 1} 让 dot 比直线略粗（明显），无硬上限——粗 stroke 时 dot 跟着
     * 长大才能视觉对应。极短元素 + 粗 stroke 由 element-aware 重载处理。</p>
     */
    public static int dotRadius(int strokeWidth) {
        return Math.max(MIN_DOT_RADIUS, strokeWidth + 1);
    }

    /** 0.4.7：element-aware cap，同 arrowSize。 */
    public static int dotRadius(int strokeWidth, double elementDiagonal) {
        int base = dotRadius(strokeWidth);
        if (elementDiagonal <= 0) return base;
        // dot 直径 = 半径 × 2，所以 cap 是 ratio/2
        int byLength = (int) Math.round(elementDiagonal * MARKER_MAX_RATIO_OF_LENGTH * 0.5);
        int minByStroke = Math.max(MIN_DOT_RADIUS, strokeWidth);
        return Math.max(minByStroke, Math.min(base, byLength));
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
