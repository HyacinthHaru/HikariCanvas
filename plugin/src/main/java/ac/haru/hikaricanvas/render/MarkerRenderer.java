package ac.haru.hikaricanvas.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

/**
 * marker（path 端点装饰）渲染。支持 {@code arrow} 三角形与 {@code dot} 圆点。
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
     * arrow size 公式 = {@code max(MIN_ARROW_SIZE, strokeWidth × 2 + 4)}。
     *
     * <p>arrow 是三角形（面积 ∝ size²），若让 size 随 stroke 线性放大过快，粗 stroke 时
     * arrow 视觉面积会按平方膨胀；{@code × 2 + 4} 让细 stroke (1-3) 几乎不变、粗 stroke 增速
     * 线性平缓。</p>
     *
     * <p>不带硬上限——element-aware cap 由调用方传 element 对角线限制 marker 不溢出。</p>
     */
    public static int arrowSize(int strokeWidth) {
        return Math.max(MIN_ARROW_SIZE, strokeWidth * 2 + 4);
    }

    /**
     * element-aware cap 重载。
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
     * dot radius 公式 = {@code max(MIN_DOT_RADIUS, strokeWidth / 2 + 3)}（Java 整数除法 = floor）。
     *
     * <p>dot 是圆（面积 ∝ r²）；{@code / 2 + 3} 让粗 stroke 时圆形 marker 面积不按平方膨胀、
     * 不抢直线视觉。</p>
     */
    public static int dotRadius(int strokeWidth) {
        return Math.max(MIN_DOT_RADIUS, strokeWidth / 2 + 3);
    }

    /** element-aware cap，同 arrowSize。 */
    public static int dotRadius(int strokeWidth, double elementDiagonal) {
        int base = dotRadius(strokeWidth);
        if (elementDiagonal <= 0) return base;
        // dot 直径 = 半径 × 2，所以 cap 是 ratio/2
        int byLength = (int) Math.round(elementDiagonal * MARKER_MAX_RATIO_OF_LENGTH * 0.5);
        int minByStroke = Math.max(MIN_DOT_RADIUS, strokeWidth);
        return Math.max(minByStroke, Math.min(base, byLength));
    }

    /**
     * 构造 arrow 三角形几何（不绘制）。
     *
     * <p><b>几何（SVG marker-end 标准做法）</b>：path end 当 <b>base center</b>，apex
     * 在 path end <b>朝 dir 方向延伸 size</b>。这样 stroke 干净截止于 base center
     * （配合 BUTT lineCap），arrow 三角形完全在 path end 之外，V 头清晰突出。若把 path end
     * 当 apex、base 朝 path 内部退 size，则粗 strokeWidth 的 stroke 矩形带会超出三角形边缘
     * （等腰三角形从 apex 朝外 d 距离处宽度仅 d），视觉上直线横穿 V 头。</p>
     *
     * <p><b>参数 {@code apexX, apexY} 仍命名为 apex</b>（向下兼容 caller）—— 实际传入的
     * 是 path end，函数内部把它当 base center 用，并算出真 apex = path end + dir × size。</p>
     *
     * <p><b>side effect</b>：arrow 画在 element bbox 之外（编辑器视觉上略溢出 bbox
     * border，但 marker 本就是 path end 之外的装饰，是 SVG 标准行为）。</p>
     */
    public static Path2D.Double arrowShape(double apexX, double apexY,
                                           double dirX, double dirY, int size) {
        Path2D.Double tri = new Path2D.Double();
        double len = Math.hypot(dirX, dirY);
        if (len < 1e-9) return tri;
        double dx = dirX / len;
        double dy = dirY / len;
        // apexX/Y 现在是"path end" = base center；真 apex 在 path end + dir × size
        double bcx = apexX;
        double bcy = apexY;
        double realApexX = apexX + dx * size;
        double realApexY = apexY + dy * size;
        double px = -dy;
        double py = dx;
        double halfBase = size * 0.5;
        double leftX = bcx + px * halfBase;
        double leftY = bcy + py * halfBase;
        double rightX = bcx - px * halfBase;
        double rightY = bcy - py * halfBase;
        tri.moveTo(realApexX, realApexY);
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
