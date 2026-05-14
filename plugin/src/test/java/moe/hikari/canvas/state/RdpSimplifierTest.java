package moe.hikari.canvas.state;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M12-B：{@link RdpSimplifier} pure-helper 测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>直线（所有中间点共线）→ epsilon &gt; 0 时压缩为两端点</li>
 *   <li>带噪声曲线 → 保留拐点，丢弃直线段</li>
 *   <li>epsilon=0 → 返回原序列（不简化）</li>
 *   <li>点数 &lt; 3 → 原样返回</li>
 *   <li>退化（首尾同点）→ 仍能工作</li>
 *   <li>压感保留（不被算法触碰）</li>
 * </ul>
 */
class RdpSimplifierTest {

    private static BrushPoint pt(double x, double y, double p) {
        return new BrushPoint(x, y, p);
    }

    @Test
    void emptyAndSmallReturnAsIs() {
        assertEquals(0, RdpSimplifier.simplify(List.of(), 1.0).size());
        List<BrushPoint> one = List.of(pt(0, 0, 0.5));
        assertSame(one, RdpSimplifier.simplify(one, 1.0));
        List<BrushPoint> two = List.of(pt(0, 0, 0.5), pt(10, 10, 0.5));
        assertSame(two, RdpSimplifier.simplify(two, 1.0));
    }

    @Test
    void epsilonZeroReturnsInput() {
        List<BrushPoint> ten = List.of(
                pt(0, 0, 0.5), pt(1, 1, 0.5), pt(2, 2, 0.5), pt(3, 3, 0.5), pt(4, 4, 0.5));
        assertSame(ten, RdpSimplifier.simplify(ten, 0));
    }

    @Test
    void perfectLineCollapsesToEndpoints() {
        // y=x 直线上 10 个点，epsilon=1 → 应只剩首尾
        List<BrushPoint> line = List.of(
                pt(0, 0, 0.5),
                pt(1, 1, 0.5),
                pt(2, 2, 0.5),
                pt(3, 3, 0.5),
                pt(4, 4, 0.5),
                pt(5, 5, 0.5),
                pt(6, 6, 0.5),
                pt(7, 7, 0.5),
                pt(8, 8, 0.5),
                pt(9, 9, 0.5)
        );
        List<BrushPoint> r = RdpSimplifier.simplify(line, 1.0);
        assertEquals(2, r.size());
        assertEquals(0.0, r.get(0).x());
        assertEquals(9.0, r.get(1).x());
    }

    @Test
    void cornerPointPreserved() {
        // L 形：(0,0) - (10,0) - (10,10)，拐点 (10,0) 距 (0,0)-(10,10) 连线很远 → 必保留
        List<BrushPoint> ell = List.of(
                pt(0, 0, 0.5),
                pt(5, 0, 0.5),
                pt(10, 0, 0.5),   // 拐点
                pt(10, 5, 0.5),
                pt(10, 10, 0.5)
        );
        List<BrushPoint> r = RdpSimplifier.simplify(ell, 1.0);
        // 至少应有 3 点（含拐点 (10,0)）
        assertTrue(r.size() >= 3);
        boolean hasCorner = r.stream().anyMatch(p -> p.x() == 10.0 && p.y() == 0.0);
        assertTrue(hasCorner, "corner point (10, 0) should be preserved");
    }

    @Test
    void noisyLineSimplifiedToFewerPoints() {
        // 直线上加小噪声 → 较大 epsilon 应大幅压缩
        List<BrushPoint> noisy = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            // y = x + 微噪声（< 0.5）
            noisy.add(pt(i, i + (i % 3 == 0 ? 0.3 : -0.2), 0.5));
        }
        List<BrushPoint> r = RdpSimplifier.simplify(noisy, 1.0);
        assertTrue(r.size() < noisy.size(), "noisy line should simplify");
    }

    @Test
    void pressurePreservedThroughSimplification() {
        // 拐点压感 1.0，其余 0.3；简化后拐点必保留 → 压感 1.0 应在输出里
        List<BrushPoint> ell = List.of(
                pt(0, 0, 0.3),
                pt(5, 0, 0.3),
                pt(10, 0, 1.0),
                pt(10, 5, 0.3),
                pt(10, 10, 0.3)
        );
        List<BrushPoint> r = RdpSimplifier.simplify(ell, 1.0);
        boolean hasHighPressure = r.stream().anyMatch(p -> p.pressure() == 1.0);
        assertTrue(hasHighPressure, "high-pressure corner point should be preserved with its pressure");
    }

    @Test
    void degenerateSameStartEnd() {
        // 首尾同点：算法 fallback 用 |p - start| 距离，不应崩
        List<BrushPoint> pts = List.of(
                pt(0, 0, 0.5),
                pt(5, 5, 0.5),
                pt(0, 0, 0.5)
        );
        List<BrushPoint> r = RdpSimplifier.simplify(pts, 1.0);
        // 至少包含首尾
        assertEquals(0.0, r.get(0).x());
        assertEquals(0.0, r.get(r.size() - 1).x());
        // 中间点 (5,5) 距 (0,0)-(0,0) 退化线段 ≈ 7.07，> 1 → 应保留
        assertTrue(r.size() >= 3);
    }
}
