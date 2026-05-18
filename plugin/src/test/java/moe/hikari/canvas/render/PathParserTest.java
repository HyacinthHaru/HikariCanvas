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

    // ---------- M26-C: H / V / S / T / A 新命令 ----------

    @Test
    void horizontalLineAbsolute() {
        // M 0 0 H 50 → lineTo(50, 0)
        PathParser.Result r = PathParser.parse("M 0 0 H 50");
        assertEquals(50.0, r.endX(), EPS);
        assertEquals(0.0, r.endY(), EPS);
        assertEquals(1.0, r.endTangentX(), EPS);
        assertEquals(0.0, r.endTangentY(), EPS);
        assertTrue(r.hasSegments());
    }

    @Test
    void horizontalLineRelative() {
        // M 10 5 h 30 → lineTo(40, 5)
        PathParser.Result r = PathParser.parse("M 10 5 h 30");
        assertEquals(40.0, r.endX(), EPS);
        assertEquals(5.0, r.endY(), EPS);
    }

    @Test
    void horizontalLineNegative() {
        // M 100 50 H 20 → 向左
        PathParser.Result r = PathParser.parse("M 100 50 H 20");
        assertEquals(20.0, r.endX(), EPS);
        assertEquals(50.0, r.endY(), EPS);
        assertEquals(-1.0, r.endTangentX(), EPS);
    }

    @Test
    void verticalLineAbsolute() {
        // M 0 0 V 80 → lineTo(0, 80)
        PathParser.Result r = PathParser.parse("M 0 0 V 80");
        assertEquals(0.0, r.endX(), EPS);
        assertEquals(80.0, r.endY(), EPS);
        assertEquals(0.0, r.endTangentX(), EPS);
        assertEquals(1.0, r.endTangentY(), EPS);
    }

    @Test
    void verticalLineRelative() {
        // M 10 10 v 25 → lineTo(10, 35)
        PathParser.Result r = PathParser.parse("M 10 10 v 25");
        assertEquals(10.0, r.endX(), EPS);
        assertEquals(35.0, r.endY(), EPS);
    }

    @Test
    void horizontalThenVerticalChained() {
        // 复合：M 0 0 H 10 V 20 → (0,0) → (10,0) → (10,20)
        PathParser.Result r = PathParser.parse("M 0 0 H 10 V 20");
        assertEquals(10.0, r.endX(), EPS);
        assertEquals(20.0, r.endY(), EPS);
    }

    // ---------- S 反射 ----------

    @Test
    void smoothCubicReflectsAfterC() {
        // M 0 0 C 0 -50 50 -50 50 0 S 100 50 100 0
        // 第一 C 的 c2 = (50, -50)
        // S 反射 c1 = curPos*2 - c2 = (50*2 - 50, 0*2 - (-50)) = (50, 50)
        // S 整段：c1=(50,50), c2=(100,50), end=(100,0)
        PathParser.Result r = PathParser.parse("M 0 0 C 0 -50 50 -50 50 0 S 100 50 100 0");
        assertEquals(100.0, r.endX(), EPS);
        assertEquals(0.0, r.endY(), EPS);
        // end tangent = end - c2 = (100-100, 0-50) = (0, -50) → (0, -1)
        assertEquals(0.0, r.endTangentX(), EPS);
        assertEquals(-1.0, r.endTangentY(), EPS);
    }

    @Test
    void smoothCubicAfterNonCubicC1EqualsCur() {
        // M 0 0 L 10 0 S 30 -10 40 0：前一是 L，不是 C/S → c1 = cur = (10, 0)
        // S 整段：start=(10,0), c1=(10,0), c2=(30,-10), end=(40,0)
        // end tangent = (40-30, 0-(-10)) = (10, 10) → (√0.5, √0.5)
        PathParser.Result r = PathParser.parse("M 0 0 L 10 0 S 30 -10 40 0");
        assertEquals(40.0, r.endX(), EPS);
        assertEquals(0.0, r.endY(), EPS);
        assertEquals(Math.sqrt(0.5), r.endTangentX(), EPS);
        assertEquals(Math.sqrt(0.5), r.endTangentY(), EPS);
    }

    @Test
    void smoothCubicChained() {
        // 连续 S：M 0 0 C 0 -10 10 -10 10 0 S 30 10 30 0 S 50 -10 50 0
        // 每个 S 把前 c2 反射，链式
        PathParser.Result r = PathParser.parse("M 0 0 C 0 -10 10 -10 10 0 S 30 10 30 0 S 50 -10 50 0");
        assertEquals(50.0, r.endX(), EPS);
        assertEquals(0.0, r.endY(), EPS);
        assertTrue(r.hasSegments());
    }

    @Test
    void smoothCubicRelative() {
        // M 0 0 c 0 -10 10 -10 10 0 s 20 10 20 0
        // 第一 c：abs c1=(0,-10), c2=(10,-10), end=(10,0)；prev c2 = (10,-10)
        // s 反射 c1 = (10*2-10, 0*2-(-10)) = (10, 10)；c2_abs = (10+20, 0+10) = (30, 10)，end_abs = (30, 0)
        PathParser.Result r = PathParser.parse("M 0 0 c 0 -10 10 -10 10 0 s 20 10 20 0");
        assertEquals(30.0, r.endX(), EPS);
        assertEquals(0.0, r.endY(), EPS);
    }

    // ---------- T 反射 ----------

    @Test
    void smoothQuadraticReflectsAfterQ() {
        // M 0 0 Q 25 -50 50 0 T 100 0
        // 前 Q c = (25, -50)；T 反射 c = cur*2 - prevQc = (50*2-25, 0*2-(-50)) = (75, 50)
        // T 整段：start=(50,0), c=(75,50), end=(100,0)
        PathParser.Result r = PathParser.parse("M 0 0 Q 25 -50 50 0 T 100 0");
        assertEquals(100.0, r.endX(), EPS);
        assertEquals(0.0, r.endY(), EPS);
        // T end tangent = end - c = (100-75, 0-50) = (25, -50) → norm
        double len = Math.hypot(25, 50);
        assertEquals(25.0 / len, r.endTangentX(), EPS);
        assertEquals(-50.0 / len, r.endTangentY(), EPS);
    }

    @Test
    void smoothQuadraticAfterNonQuadCEqualsCur() {
        // M 0 0 L 50 0 T 100 0：前 L 不是 Q/T → c = cur = (50, 0)
        // 退化：c == start，二次贝塞尔退化为直线 → end tangent = end - c = (50, 0) → (1, 0)
        PathParser.Result r = PathParser.parse("M 0 0 L 50 0 T 100 0");
        assertEquals(100.0, r.endX(), EPS);
        assertEquals(0.0, r.endY(), EPS);
    }

    // ---------- A 椭圆弧 ----------

    @Test
    void arcSimpleSemicircle() {
        // M 0 0 A 50 50 0 0 1 100 0：半径 50 圆，sweep 顺时针（在 +Y down 坐标系下），
        // 半圆从 (0,0) → (100,0)；最高点应该 ≈ (50, -50) 或 (50, +50) 取决于 sweep flag
        PathParser.Result r = PathParser.parse("M 0 0 A 50 50 0 0 1 100 0");
        assertEquals(100.0, r.endX(), 0.001);
        assertEquals(0.0, r.endY(), 0.001);
        assertTrue(r.hasSegments());
        // path bbox 高度 ≈ 50（半圆）
        java.awt.geom.Rectangle2D bbox = r.path().getBounds2D();
        assertEquals(0.0, bbox.getMinX(), 0.1);
        assertEquals(100.0, bbox.getMaxX(), 0.5);
        // 半径 50，高度应 ≈ 50
        assertTrue(Math.abs(bbox.getHeight() - 50.0) < 1.0,
                "expected height ≈ 50, got " + bbox.getHeight());
    }

    @Test
    void arcDegenerateZeroRadius() {
        // rx=0 → 退化为直线
        PathParser.Result r = PathParser.parse("M 0 0 A 0 50 0 0 1 100 0");
        assertEquals(100.0, r.endX(), EPS);
        assertEquals(0.0, r.endY(), EPS);
        assertTrue(r.hasSegments());
    }

    @Test
    void arcDegenerateSameEndpoint() {
        // start == end → 整段跳过（SVG spec）
        PathParser.Result r = PathParser.parse("M 50 50 A 30 30 0 0 1 50 50");
        assertEquals(50.0, r.endX(), EPS);
        assertEquals(50.0, r.endY(), EPS);
        // 跳过 → 不算 segment
    }

    @Test
    void arcSweepFlagDirection() {
        // 同样起终点 + 同半径 + 不同 sweep flag → 弧应在不同方向（一上一下）
        // sweep=1：方向 1；sweep=0：方向 2，bbox 中心相反
        PathParser.Result r1 = PathParser.parse("M 0 0 A 50 50 0 0 1 100 0");
        PathParser.Result r0 = PathParser.parse("M 0 0 A 50 50 0 0 0 100 0");
        java.awt.geom.Rectangle2D b1 = r1.path().getBounds2D();
        java.awt.geom.Rectangle2D b0 = r0.path().getBounds2D();
        // 两条弧 minY / maxY 应相反符号
        // sweep=1：弧在 y > 0 区域 → maxY > 40
        // sweep=0：弧在 y < 0 区域 → minY < -40
        // （或反之，与坐标系 / 方向约定有关；关键是两者不同）
        assertTrue(Math.abs(b1.getMinY() - b0.getMinY()) > 30.0,
                "sweep flag should produce arcs in different half-planes");
    }

    @Test
    void arcLargeArcFlag() {
        // large-arc=1 → 取大弧（> 180°）；half-circle 边界 = large 0 / 1 都覆盖半圆
        // 用更小的角度区分：M 0 0 A 50 50 0 [large] 1 50 50（90° vs 270°）
        PathParser.Result rSmall = PathParser.parse("M 0 0 A 50 50 0 0 1 50 50");
        PathParser.Result rLarge = PathParser.parse("M 0 0 A 50 50 0 1 1 50 50");
        java.awt.geom.Rectangle2D bSmall = rSmall.path().getBounds2D();
        java.awt.geom.Rectangle2D bLarge = rLarge.path().getBounds2D();
        // 大弧 bbox 应更大（围绕半径 50 的圆）
        assertTrue(bLarge.getWidth() > bSmall.getWidth() + 10
                        || bLarge.getHeight() > bSmall.getHeight() + 10,
                "large arc should have a larger bbox than small");
    }

    @Test
    void arcRelative() {
        // 相对版半圆：从 (10, 20) → 终点 (10+100, 20+0) = (110, 20)
        PathParser.Result r = PathParser.parse("M 10 20 a 50 50 0 0 1 100 0");
        assertEquals(110.0, r.endX(), 0.001);
        assertEquals(20.0, r.endY(), 0.001);
    }

    @Test
    void arcFlagsConsecutiveNoSeparator() {
        // SVG 允许 flag 紧贴：A 50 50 0 01 100 0 = A 50 50 0 0 1 100 0
        PathParser.Result r = PathParser.parse("M 0 0 A 50 50 0 01 100 0");
        assertEquals(100.0, r.endX(), 0.001);
        assertEquals(0.0, r.endY(), 0.001);
    }

    // ---------- FA icon 实测 ----------

    @Test
    void faSolidCirclePathParsesCorrectly() {
        // FA solid "circle"：M256 512A256 256 0 1 0 256 0a256 256 0 1 0 0 512z
        // 两段半圆拼成全圆（半径 256，圆心 (256, 256)，viewBox 0 0 512 512）
        String d = "M256 512A256 256 0 1 0 256 0a256 256 0 1 0 0 512z";
        PathParser.Result r = PathParser.parse(d);
        assertTrue(r.hasSegments(), "FA circle path should produce segments");
        java.awt.geom.Rectangle2D bbox = r.path().getBounds2D();
        // 圆心 (256, 256)，半径 256 → bbox 应该 ≈ 0..512 × 0..512
        assertTrue(Math.abs(bbox.getMinX() - 0) < 2.0, "minX ≈ 0, got " + bbox.getMinX());
        assertTrue(Math.abs(bbox.getMaxX() - 512) < 2.0, "maxX ≈ 512, got " + bbox.getMaxX());
        assertTrue(Math.abs(bbox.getMinY() - 0) < 2.0, "minY ≈ 0, got " + bbox.getMinY());
        assertTrue(Math.abs(bbox.getMaxY() - 512) < 2.0, "maxY ≈ 512, got " + bbox.getMaxY());
    }

    @Test
    void faSolidHeartPathParsesCorrectly() {
        // FA solid "heart" 含 S 命令
        String d = "M47.6 300.4L228.3 469.1c7.5 7 17.4 10.9 27.7 10.9s20.2-3.9 27.7-10.9L464.4 300.4c30.4-28.3 47.6-68 47.6-109.5v-5.8c0-69.9-50.5-129.5-119.4-141C347.8 41 314.1 53.4 288 75.9c-7.2 6.2-13.8 13-19.8 20.5l-12.2 15.4-12.2-15.4c-6-7.5-12.6-14.4-19.8-20.5C197.9 53.4 164.2 41 119.4 30C50.5 41.5 0 101.1 0 171v5.8c0 41.5 17.2 81.2 47.6 109.5z";
        PathParser.Result r = PathParser.parse(d);
        assertTrue(r.hasSegments(), "FA heart path should produce segments");
        java.awt.geom.Rectangle2D bbox = r.path().getBounds2D();
        // viewBox 是 0 0 512 512；heart 大致占据中心区域，宽接近 512
        assertTrue(bbox.getWidth() > 400, "heart width ≈ 512, got " + bbox.getWidth());
        assertTrue(bbox.getHeight() > 400, "heart height ≈ 470, got " + bbox.getHeight());
        assertTrue(bbox.getMinX() >= 0, "minX ≥ 0, got " + bbox.getMinX());
        assertTrue(bbox.getMaxX() <= 512, "maxX ≤ 512, got " + bbox.getMaxX());
    }

    @Test
    void faSolidStarPathParsesCorrectly() {
        // FA solid "star" 含 S + l(相对 lineto)
        String d = "M316.9 18C311.6 7 300.4 0 288.1 0s-23.4 7-28.8 18L195 150.3 51.4 171.5c-12 1.8-22 10.2-25.7 21.7s-.7 24.2 7.9 32.7L137.8 328 113.2 474.7c-2 12 3 24.2 12.9 31.3s23 8 33.8 2.3l128.3-68.5 128.3 68.5c10.8 5.7 23.9 4.9 33.8-2.3s14.9-19.3 12.9-31.3L438.5 328 552.3 225.9c8.6-8.5 11.7-21.2 7.9-32.7s-13.7-19.9-25.7-21.7L391 150.3 316.9 18z";
        PathParser.Result r = PathParser.parse(d);
        assertTrue(r.hasSegments(), "FA star path should produce segments");
        java.awt.geom.Rectangle2D bbox = r.path().getBounds2D();
        // star viewBox 0 0 576 512；bbox 大致填满
        assertTrue(bbox.getWidth() > 500, "star width ≈ 576, got " + bbox.getWidth());
        assertTrue(bbox.getHeight() > 400, "star height ≈ 508, got " + bbox.getHeight());
    }
}
