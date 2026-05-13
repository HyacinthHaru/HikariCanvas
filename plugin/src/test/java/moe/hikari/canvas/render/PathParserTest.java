package moe.hikari.canvas.render;

import org.junit.jupiter.api.Test;

import java.awt.geom.PathIterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M9-B：{@link PathParser} 解析与切线方向校验。
 *
 * <p>核心断言：</p>
 * <ul>
 *   <li>简单 M / L / Q / C / Z 都能跑通，path 内含正确数量 segment</li>
 *   <li>大小写 = 绝对 / 相对坐标，叠加效果正确</li>
 *   <li>隐式 lineto（M 多组参数）</li>
 *   <li>起点 / 终点切线方向单位向量正确</li>
 *   <li>Z 闭合后当前点回到 subpath 起点</li>
 *   <li>空串 / null 返回 empty 但不抛</li>
 * </ul>
 */
class PathParserTest {

    private static final double EPS = 1e-6;

    private static int countSegments(java.awt.geom.Path2D path) {
        int n = 0;
        double[] buf = new double[6];
        PathIterator it = path.getPathIterator(null);
        while (!it.isDone()) {
            it.currentSegment(buf);
            n++;
            it.next();
        }
        return n;
    }

    // ---------- 空 / 退化 ----------

    @Test
    void emptyStringReturnsEmpty() {
        PathParser.Result r = PathParser.parse("");
        assertFalse(r.hasSegments());
        assertEquals(0, countSegments(r.path()));
    }

    @Test
    void nullStringReturnsEmpty() {
        PathParser.Result r = PathParser.parse(null);
        assertFalse(r.hasSegments());
    }

    @Test
    void onlyMoveTo() {
        PathParser.Result r = PathParser.parse("M 10 20");
        assertEquals(10.0, r.startX(), EPS);
        assertEquals(20.0, r.startY(), EPS);
        assertEquals(10.0, r.endX(), EPS);
        assertEquals(20.0, r.endY(), EPS);
        assertFalse(r.hasSegments()); // 仅一个 moveto 不算 segment
        assertEquals(1, countSegments(r.path()));  // SEG_MOVETO
    }

    // ---------- 简单 L ----------

    @Test
    void simpleLineEndpointsCorrect() {
        PathParser.Result r = PathParser.parse("M 0 0 L 100 0");
        assertEquals(0.0, r.startX(), EPS);
        assertEquals(0.0, r.startY(), EPS);
        assertEquals(100.0, r.endX(), EPS);
        assertEquals(0.0, r.endY(), EPS);
        assertTrue(r.hasSegments());
    }

    @Test
    void simpleLineTangentRight() {
        // 水平向右 → 切线 (1, 0)
        PathParser.Result r = PathParser.parse("M 0 0 L 100 0");
        assertEquals(1.0, r.endTangentX(), EPS);
        assertEquals(0.0, r.endTangentY(), EPS);
        assertEquals(1.0, r.startTangentX(), EPS);
        assertEquals(0.0, r.startTangentY(), EPS);
    }

    @Test
    void simpleLineTangentDown() {
        // 垂直向下 → 切线 (0, 1)
        PathParser.Result r = PathParser.parse("M 0 0 L 0 100");
        assertEquals(0.0, r.endTangentX(), EPS);
        assertEquals(1.0, r.endTangentY(), EPS);
    }

    // ---------- 相对坐标 ----------

    @Test
    void lowercaseRelative() {
        // m 10 10 = moveTo(10, 10); l 20 0 = lineTo(30, 10)
        PathParser.Result r = PathParser.parse("m 10 10 l 20 0");
        assertEquals(10.0, r.startX(), EPS);
        assertEquals(10.0, r.startY(), EPS);
        assertEquals(30.0, r.endX(), EPS);
        assertEquals(10.0, r.endY(), EPS);
    }

    @Test
    void mixedAbsoluteRelative() {
        // M 50 50 → l 10 10 (相对：到 60, 60) → L 100 100 (绝对)
        PathParser.Result r = PathParser.parse("M 50 50 l 10 10 L 100 100");
        assertEquals(100.0, r.endX(), EPS);
        assertEquals(100.0, r.endY(), EPS);
    }

    // ---------- 隐式 lineto after M ----------

    @Test
    void moveToWithExtraCoordsIsImplicitLineTo() {
        // M 0 0 10 10 20 20 = moveTo(0,0) + lineTo(10,10) + lineTo(20,20)
        PathParser.Result r = PathParser.parse("M 0 0 10 10 20 20");
        assertEquals(0.0, r.startX(), EPS);
        assertEquals(20.0, r.endX(), EPS);
        assertEquals(20.0, r.endY(), EPS);
        // 末段方向 (10, 10) 归一化 ≈ (0.707, 0.707)
        assertEquals(Math.sqrt(0.5), r.endTangentX(), EPS);
        assertEquals(Math.sqrt(0.5), r.endTangentY(), EPS);
    }

    // ---------- Q ----------

    @Test
    void quadraticEndpoint() {
        // M 0 0 Q 50 -50 100 0：起点 (0,0)，终点 (100, 0)
        PathParser.Result r = PathParser.parse("M 0 0 Q 50 -50 100 0");
        assertEquals(0.0, r.startX(), EPS);
        assertEquals(0.0, r.startY(), EPS);
        assertEquals(100.0, r.endX(), EPS);
        assertEquals(0.0, r.endY(), EPS);
    }

    @Test
    void quadraticEndTangent() {
        // Q (50, -50) → (100, 0)：t=1 切线 = P2 - P1 = (50, 50) → 归一化 (sqrt(0.5), sqrt(0.5))
        PathParser.Result r = PathParser.parse("M 0 0 Q 50 -50 100 0");
        assertEquals(Math.sqrt(0.5), r.endTangentX(), EPS);
        assertEquals(Math.sqrt(0.5), r.endTangentY(), EPS);
    }

    @Test
    void quadraticStartTangent() {
        // Q t=0 切线 = P1 - P0 = (50, -50) → 归一化 (sqrt(0.5), -sqrt(0.5))
        PathParser.Result r = PathParser.parse("M 0 0 Q 50 -50 100 0");
        assertEquals(Math.sqrt(0.5), r.startTangentX(), EPS);
        assertEquals(-Math.sqrt(0.5), r.startTangentY(), EPS);
    }

    // ---------- C ----------

    @Test
    void cubicEndpointAndTangent() {
        // M 0 0 C 10 -20 30 -20 40 0：起点 (0,0)，终点 (40,0)
        // t=1 切线 = P3 - C2 = (40-30, 0-(-20)) = (10, 20) → normalized
        PathParser.Result r = PathParser.parse("M 0 0 C 10 -20 30 -20 40 0");
        assertEquals(40.0, r.endX(), EPS);
        assertEquals(0.0, r.endY(), EPS);
        double len = Math.hypot(10, 20);
        assertEquals(10.0 / len, r.endTangentX(), EPS);
        assertEquals(20.0 / len, r.endTangentY(), EPS);
    }

    // ---------- Z 闭合 ----------

    @Test
    void closePathReturnsToSubpathStart() {
        // M 10 10 L 90 10 L 90 90 Z：Z 后当前点回到 (10, 10)
        PathParser.Result r = PathParser.parse("M 10 10 L 90 10 L 90 90 Z");
        assertEquals(10.0, r.endX(), EPS);
        assertEquals(10.0, r.endY(), EPS);
    }

    @Test
    void closePathTangentTowardSubpathStart() {
        // Z 末段：从 (90, 90) 指向 subpath 起点 (10, 10) = (-80, -80) → 归一化 (-√0.5, -√0.5)
        PathParser.Result r = PathParser.parse("M 10 10 L 90 10 L 90 90 Z");
        assertEquals(-Math.sqrt(0.5), r.endTangentX(), EPS);
        assertEquals(-Math.sqrt(0.5), r.endTangentY(), EPS);
    }

    // ---------- 退化 ----------

    @Test
    void zeroLengthLineHasNoTangent() {
        // L 起终点重合 → 切线不更新；hasSegments 仍 false（因为没有真正画过段）
        PathParser.Result r = PathParser.parse("M 50 50 L 50 50");
        assertFalse(r.hasSegments());
    }

    @Test
    void degenerateQuadraticFallsBackToOtherEnd() {
        // Q 控制点与起点重合：起点切线退化；P1-P0=(0,0)，用 P2-P0
        PathParser.Result r = PathParser.parse("M 0 0 Q 0 0 100 0");
        // 起点切线应回退到 P2 - P0 = (1, 0)
        assertEquals(1.0, r.startTangentX(), EPS);
        assertEquals(0.0, r.startTangentY(), EPS);
    }

    // ---------- comma / 空白容差 ----------

    @Test
    void commaSeparatorOk() {
        PathParser.Result r = PathParser.parse("M 0,0 L 10,10");
        assertEquals(10.0, r.endX(), EPS);
        assertEquals(10.0, r.endY(), EPS);
    }

    @Test
    void mixedWhitespaceOk() {
        PathParser.Result r = PathParser.parse("M\n0\t0  L  10\r10");
        assertEquals(10.0, r.endX(), EPS);
    }
}
