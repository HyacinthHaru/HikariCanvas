package ac.haru.hikaricanvas.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Ramer-Douglas-Peucker 折线简化。
 *
 * <p>经典算法：找出"端点连线"的最远点，若距离 > epsilon 则保留并继续处理两侧；
 * 否则该段整体压缩为 (start, end) 两点。{@code O(n log n)} 平均、{@code O(n²)} 最坏。</p>
 *
 * <p><b>笔触特化：</b> {@link BrushPoint} 含 {@code pressure} 字段；RDP 仅按 (x, y) 计算距离，
 * 保留点的原始 pressure（不平均、不插值），这样 brush.end 后简化产物仍带原始压感数据。</p>
 *
 * <p><b>用显式栈防 SOF。</b> 原递归实现最坏栈深 ≈ n；MAX_BRUSH_POINTS_PER_STROKE=5000
 * 时配合 JVM 512KB 栈约 ~2500 帧后 StackOverflowError。改用 {@link ArrayDeque} 显式栈
 * 模拟递归，算法等价（同输入同输出），栈在堆上不受 JVM 线程栈大小限制。</p>
 */
public final class RdpSimplifier {

    private RdpSimplifier() {}

    /**
     * 对 {@code points} 跑 RDP 简化。{@code epsilon <= 0} 时不简化（返回输入引用）。
     *
     * @param points  原始点列表（顺序敏感）；空 / 单点 / 双点 直接返回原列表（无简化空间）
     * @param epsilon 距离阈值（px）；点到端点连线垂距 < epsilon 时被丢弃
     * @return 简化后的不可变列表；保留首尾点
     */
    public static List<BrushPoint> simplify(List<BrushPoint> points, double epsilon) {
        if (points == null) return List.of();
        int n = points.size();
        if (n < 3 || epsilon <= 0) return points;
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        simplifyIterative(points, 0, n - 1, epsilon * epsilon, keep);
        List<BrushPoint> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) out.add(points.get(i));
        }
        return out;
    }

    /**
     * 迭代版 RDP：用 ArrayDeque 模拟递归调用栈。算法语义与递归版完全一致——
     * 每个 [lo, hi] 区间找最远点，距离 > epsilon 则保留并把两个子区间压栈；否则丢弃整段中间点。
     * 比较的是平方距离，避免 sqrt（{@code epsilon2 = epsilon × epsilon}）。
     */
    private static void simplifyIterative(List<BrushPoint> pts, int lo, int hi,
                                          double epsilon2, boolean[] keep) {
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{lo, hi});
        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            int s = range[0], e = range[1];
            if (e <= s + 1) continue;
            BrushPoint a = pts.get(s);
            BrushPoint b = pts.get(e);
            double maxDist2 = 0;
            int maxIdx = -1;
            for (int i = s + 1; i < e; i++) {
                double d2 = perpendicularDistanceSquared(pts.get(i), a, b);
                if (d2 > maxDist2) {
                    maxDist2 = d2;
                    maxIdx = i;
                }
            }
            if (maxDist2 > epsilon2 && maxIdx > 0) {
                keep[maxIdx] = true;
                stack.push(new int[]{s, maxIdx});
                stack.push(new int[]{maxIdx, e});
            }
        }
    }

    /**
     * 点 {@code p} 到线段 (a, b) 的垂距平方。
     * 退化情形（a == b）：返回 |p - a|²。
     */
    private static double perpendicularDistanceSquared(BrushPoint p, BrushPoint a, BrushPoint b) {
        double ax = a.x(), ay = a.y();
        double bx = b.x(), by = b.y();
        double dx = bx - ax;
        double dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        if (lenSq == 0) {
            double ex = p.x() - ax;
            double ey = p.y() - ay;
            return ex * ex + ey * ey;
        }
        // 投影到线段：t ∈ ℝ，无需 clamp（计算垂距即可）
        double t = ((p.x() - ax) * dx + (p.y() - ay) * dy) / lenSq;
        double projX = ax + t * dx;
        double projY = ay + t * dy;
        double ex = p.x() - projX;
        double ey = p.y() - projY;
        return ex * ex + ey * ey;
    }
}
