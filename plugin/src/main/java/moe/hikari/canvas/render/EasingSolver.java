package moe.hikari.canvas.render;

import moe.hikari.canvas.state.Easing;
import moe.hikari.canvas.state.EasingType;

import java.util.List;

/**
 * 缓动求解器（0.6 P3）。数学权威定义见 {@code docs/rendering.md §9.3}——本类与前端
 * {@code web/src/timeline/easing.ts} 是同一份定义的双端实现，<b>逐位等价是硬纪律</b>：
 * 算法（WebKit UnitBezier 系数形式 + 固定步数牛顿迭代 + 二分兜底）、常量
 * （{@link #NEWTON_ITER} / {@link #BISECT_MAX} / {@link #EPS}）、边界捷径与预设控制点
 * 两端写死相同，由共享测试向量 {@code rendering-test/easing-vectors.json}（容差 1e-6）
 * 在两端 CI 各自校验。<b>禁引第三方 easing 库</b>（D8）。
 *
 * <p>{@code EASE_IN / EASE_OUT / EASE_IN_OUT} 取 CSS 同名关键字标准控制点（§9.3 表）。</p>
 */
public final class EasingSolver {

    /** 牛顿迭代固定步数（两端相同）。 */
    static final int NEWTON_ITER = 8;

    /** 二分兜底固定上限（两端相同）。 */
    static final int BISECT_MAX = 32;

    /** 求解终止阈值（两端相同）。 */
    static final double EPS = 1e-6;

    private EasingSolver() {}

    /**
     * 按 {@link Easing} 把线性局部进度 {@code local} 映射成 eased 进度。
     * {@code easing == null} / 坏 bezier（null 或长度 ≠ 4，持久化宽容路径漏进来的）
     * 按 LINEAR 求值——op 层校验已挡 WS 输入，这里只兜坏 blob。
     */
    public static double ease(Easing easing, double local) {
        if (easing == null) return local;
        EasingType type = easing.type();
        if (type == null || type == EasingType.LINEAR) return local;
        return switch (type) {
            case LINEAR -> local;
            case EASE_IN -> cubicBezier(0.42, 0.0, 1.0, 1.0, local);
            case EASE_OUT -> cubicBezier(0.0, 0.0, 0.58, 1.0, local);
            case EASE_IN_OUT -> cubicBezier(0.42, 0.0, 0.58, 1.0, local);
            case CUBIC_BEZIER -> {
                List<Double> b = easing.bezier();
                if (b == null || b.size() != 4) yield local;
                yield cubicBezier(b.get(0), b.get(1), b.get(2), b.get(3), local);
            }
        };
    }

    /**
     * 标准 CSS cubic-bezier 求值：端点 P0=(0,0)、P3=(1,1)，控制点 (x1,y1)、(x2,y2)，
     * 输入 {@code local} 为横轴（时间）进度。先解 {@code Bx(u) = local} 得参数 u，
     * 再求 {@code By(u)}。系数与迭代策略照 rendering.md §9.3 伪码逐行实现。
     */
    public static double cubicBezier(double x1, double y1, double x2, double y2, double local) {
        // 边界捷径（两端相同；§9.3）：保证端点精确
        if (local <= 0.0) return 0.0;
        if (local >= 1.0) return 1.0;

        double cx = 3.0 * x1;
        double bx = 3.0 * (x2 - x1) - cx;
        double ax = 1.0 - cx - bx;
        double cy = 3.0 * y1;
        double by = 3.0 * (y2 - y1) - cy;
        double ay = 1.0 - cy - by;

        double u = solveU(ax, bx, cx, local);
        return ((ay * u + by) * u + cy) * u;
    }

    /** {@code Bx(u) = ((ax·u + bx)·u + cx)·u}。 */
    private static double sampleX(double ax, double bx, double cx, double u) {
        return ((ax * u + bx) * u + cx) * u;
    }

    /** 解 {@code Bx(u) = x}：牛顿 {@link #NEWTON_ITER} 步 → 二分 {@link #BISECT_MAX} 步兜底。 */
    private static double solveU(double ax, double bx, double cx, double x) {
        double u = x;
        for (int i = 0; i < NEWTON_ITER; i++) {
            double d = sampleX(ax, bx, cx, u) - x;
            if (Math.abs(d) <= EPS) return u;
            double dx = (3.0 * ax * u + 2.0 * bx) * u + cx;
            if (Math.abs(dx) < 1e-6) break;   // 导数退化 → 转二分
            u = u - d / dx;
        }
        double lo = 0.0;
        double hi = 1.0;
        u = x;
        for (int i = 0; i < BISECT_MAX; i++) {
            double s = sampleX(ax, bx, cx, u);
            if (Math.abs(s - x) <= EPS) return u;
            if (s < x) {
                lo = u;
            } else {
                hi = u;
            }
            u = (lo + hi) / 2.0;
        }
        return u;
    }
}
