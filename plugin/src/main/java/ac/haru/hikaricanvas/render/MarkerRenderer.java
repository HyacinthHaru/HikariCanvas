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
 *   <li>{@code arrow}：底边中心（base center）落在 path 端点上，尖端（tip）沿切线向外延伸
 *       {@code +size}；底边两顶点 = base center ± perpendicular × (size/2)，实心填充 stroke color。</li>
 *   <li>{@code dot}：圆心落在 path 端点上；半径计算见 {@link #dotRadius}。</li>
 * </ul>
 *
 * <p><b>方向：</b> {@code dirX/dirY} 是 path 端点向外的切向向量。markerEnd 时
 * = path 末段切线（沿 path 走向）；markerStart 时 = 起段切线的反向。
 * {@link #drawArrow} / {@link #arrowShape} 内部将方向向量归一化。</p>
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
     * arrow size（含 element-aware cap）：基础值 {@code max(MIN_ARROW_SIZE, strokeWidth × 2 + 4)}，
     * 再按元素对角线 cap 到 {@code diag × MARKER_MAX_RATIO_OF_LENGTH}。
     *
     * <p>arrow 是三角形（面积 ∝ size²），{@code × 2 + 4} 让细 stroke (1-3) 几乎不变、粗 stroke
     * 增速线性平缓。仍保证 {@code ≥ max(MIN_ARROW_SIZE, stroke × 2)}（视觉清晰度底线优先于
     * element 比例限制——若元素过短装不下，宁可让箭头略微溢出 bbox 也别糊掉）。</p>
     */
    public static int arrowSize(int strokeWidth, double elementDiagonal) {
        int base = Math.max(MIN_ARROW_SIZE, strokeWidth * 2 + 4);
        if (elementDiagonal <= 0) return base;
        int byLength = (int) Math.round(elementDiagonal * MARKER_MAX_RATIO_OF_LENGTH);
        // 视觉清晰底线：base 至少 stroke × 2（否则跟直线视觉粘连）。优先级 > element cap
        int minByStroke = Math.max(MIN_ARROW_SIZE, strokeWidth * 2);
        return Math.max(minByStroke, Math.min(base, byLength));
    }

    /**
     * dot radius（含 element-aware cap）：基础值 {@code max(MIN_DOT_RADIUS, strokeWidth / 2 + 3)}
     * （整数除法 = floor），再按元素对角线 cap（dot 直径 = 半径 × 2，故 cap 取 {@code ratio / 2}）。
     */
    public static int dotRadius(int strokeWidth, double elementDiagonal) {
        int base = Math.max(MIN_DOT_RADIUS, strokeWidth / 2 + 3);
        if (elementDiagonal <= 0) return base;
        // dot 直径 = 半径 × 2，所以 cap 是 ratio/2
        int byLength = (int) Math.round(elementDiagonal * MARKER_MAX_RATIO_OF_LENGTH * 0.5);
        int minByStroke = Math.max(MIN_DOT_RADIUS, strokeWidth);
        return Math.max(minByStroke, Math.min(base, byLength));
    }

    /**
     * 构造 arrow 三角形几何（不绘制）。
     *
     * <p><b>几何（SVG marker-end 标准做法）</b>：{@code (baseCenterX, baseCenterY)} 为 path 端点（即底边中心），
     * 尖端沿 {@code dir} 方向向外延伸 {@code size}。这样描边配合 {@code BUTT} lineCap 干净截止于底边中心，
     * 箭头三角形完整落在 path 端点外侧，V 头清晰突出。</p>
     *
     * <p><b>side effect</b>：arrow 位于端点外侧，可能略微伸出 element bbox 边界（{@link DirtyRegion}
     * 已按 marker 尺寸外扩）。</p>
     */
    public static Path2D.Double arrowShape(double baseCenterX, double baseCenterY,
                                           double dirX, double dirY, int size) {
        Path2D.Double tri = new Path2D.Double();
        double len = Math.hypot(dirX, dirY);
        if (len < 1e-9) return tri;
        double dx = dirX / len;
        double dy = dirY / len;
        double tipX = baseCenterX + dx * size;
        double tipY = baseCenterY + dy * size;
        double px = -dy;
        double py = dx;
        double halfBase = size * 0.5;
        double leftX = baseCenterX + px * halfBase;
        double leftY = baseCenterY + py * halfBase;
        double rightX = baseCenterX - px * halfBase;
        double rightY = baseCenterY - py * halfBase;
        tri.moveTo(tipX, tipY);
        tri.lineTo(leftX, leftY);
        tri.lineTo(rightX, rightY);
        tri.closePath();
        return tri;
    }

    /**
     * 以 {@code (baseCenterX, baseCenterY)} 为底边中心，画一个朝 {@code (dirX, dirY)} 延伸
     * {@code size} 的实心三角形 arrow。
     */
    public static void drawArrow(Graphics2D g, double baseCenterX, double baseCenterY,
                                 double dirX, double dirY, int size, Color color) {
        if (Math.hypot(dirX, dirY) < 1e-9) return;
        Path2D.Double tri = arrowShape(baseCenterX, baseCenterY, dirX, dirY, size);
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
