package moe.hikari.canvas.state;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * M11-A：FillValidator pure-helper 单测。
 *
 * <p>覆盖 SolidFill / LinearGradient / RadialGradient 三个子类的字段边界，
 * 以及 stops 列表的数量 / 位置单调 / 颜色合法性校验。</p>
 */
class FillValidatorTest {

    private static Stop stop(double pos, String color) {
        return new Stop(pos, color);
    }

    // ---------- SolidFill ----------

    @Test
    void solidValidRgb() {
        assertDoesNotThrow(() -> FillValidator.validate(new SolidFill("#FF0000")));
    }

    @Test
    void solidValidRgba() {
        assertDoesNotThrow(() -> FillValidator.validate(new SolidFill("#FF0000AA")));
    }

    @Test
    void solidRejectShortHex() {
        assertThrows(RuntimeException.class,
                () -> FillValidator.validate(new SolidFill("#F00")));
    }

    @Test
    void solidRejectNullColor() {
        assertThrows(RuntimeException.class,
                () -> FillValidator.validate(new SolidFill(null)));
    }

    @Test
    void solidRejectBadFormat() {
        assertThrows(RuntimeException.class,
                () -> FillValidator.validate(new SolidFill("red")));
    }

    @Test
    void validateNullFillIsNoOp() {
        // EditSession 在 fill = null 时不调 validate；helper 也应允许 null（短路返回）
        assertDoesNotThrow(() -> FillValidator.validate(null));
    }

    // ---------- LinearGradient ----------

    @Test
    void linearValid() {
        LinearGradient g = new LinearGradient(45.0,
                List.of(stop(0.0, "#FF0000"), stop(1.0, "#0000FF")));
        assertDoesNotThrow(() -> FillValidator.validate(g));
    }

    @Test
    void linearRejectAngle360() {
        // 上界开区间 [0, 360)
        LinearGradient g = new LinearGradient(360.0,
                List.of(stop(0.0, "#FF0000"), stop(1.0, "#0000FF")));
        assertThrows(RuntimeException.class, () -> FillValidator.validate(g));
    }

    @Test
    void linearRejectAngleNegative() {
        LinearGradient g = new LinearGradient(-1.0,
                List.of(stop(0.0, "#FF0000"), stop(1.0, "#0000FF")));
        assertThrows(RuntimeException.class, () -> FillValidator.validate(g));
    }

    @Test
    void linearRejectAngleNaN() {
        LinearGradient g = new LinearGradient(Double.NaN,
                List.of(stop(0.0, "#FF0000"), stop(1.0, "#0000FF")));
        assertThrows(RuntimeException.class, () -> FillValidator.validate(g));
    }

    @Test
    void linearAcceptAngleZero() {
        LinearGradient g = new LinearGradient(0.0,
                List.of(stop(0.0, "#FF0000"), stop(1.0, "#0000FF")));
        assertDoesNotThrow(() -> FillValidator.validate(g));
    }

    // ---------- RadialGradient ----------

    @Test
    void radialValid() {
        RadialGradient g = new RadialGradient(0.5, 0.5, 1.0,
                List.of(stop(0.0, "#FF0000"), stop(1.0, "#0000FF")));
        assertDoesNotThrow(() -> FillValidator.validate(g));
    }

    @Test
    void radialRejectCxOutOfRange() {
        RadialGradient g = new RadialGradient(1.5, 0.5, 1.0,
                List.of(stop(0.0, "#FF0000"), stop(1.0, "#0000FF")));
        assertThrows(RuntimeException.class, () -> FillValidator.validate(g));
    }

    @Test
    void radialRejectCyNegative() {
        RadialGradient g = new RadialGradient(0.5, -0.1, 1.0,
                List.of(stop(0.0, "#FF0000"), stop(1.0, "#0000FF")));
        assertThrows(RuntimeException.class, () -> FillValidator.validate(g));
    }

    @Test
    void radialRejectRZero() {
        RadialGradient g = new RadialGradient(0.5, 0.5, 0.0,
                List.of(stop(0.0, "#FF0000"), stop(1.0, "#0000FF")));
        assertThrows(RuntimeException.class, () -> FillValidator.validate(g));
    }

    @Test
    void radialRejectROver2() {
        RadialGradient g = new RadialGradient(0.5, 0.5, 2.1,
                List.of(stop(0.0, "#FF0000"), stop(1.0, "#0000FF")));
        assertThrows(RuntimeException.class, () -> FillValidator.validate(g));
    }

    @Test
    void radialAcceptR2Exactly() {
        RadialGradient g = new RadialGradient(0.5, 0.5, 2.0,
                List.of(stop(0.0, "#FF0000"), stop(1.0, "#0000FF")));
        assertDoesNotThrow(() -> FillValidator.validate(g));
    }

    // ---------- Stops ----------

    @Test
    void stopsRejectSingle() {
        LinearGradient g = new LinearGradient(0.0,
                List.of(stop(0.5, "#FF0000")));
        assertThrows(RuntimeException.class, () -> FillValidator.validate(g));
    }

    @Test
    void stopsRejectNineMax() {
        LinearGradient g = new LinearGradient(0.0,
                List.of(
                        stop(0.0, "#000"), // not relevant for count check
                        stop(0.1, "#000"), stop(0.2, "#000"),
                        stop(0.3, "#000"), stop(0.4, "#000"),
                        stop(0.5, "#000"), stop(0.6, "#000"),
                        stop(0.7, "#000"), stop(0.8, "#000")));
        // 9 > MAX_STOPS=8 → 数量校验先于颜色校验触发
        assertThrows(RuntimeException.class, () -> FillValidator.validate(g));
    }

    @Test
    void stopsRejectNonMonotonic() {
        LinearGradient g = new LinearGradient(0.0,
                List.of(stop(0.5, "#FF0000"), stop(0.2, "#0000FF")));
        assertThrows(RuntimeException.class, () -> FillValidator.validate(g));
    }

    @Test
    void stopsAcceptEqualPositions() {
        // 单调"非递减"，允许相等（"硬切色"语义，CSS gradient 行为一致）
        LinearGradient g = new LinearGradient(0.0,
                List.of(stop(0.5, "#FF0000"), stop(0.5, "#0000FF")));
        assertDoesNotThrow(() -> FillValidator.validate(g));
    }

    @Test
    void stopsRejectPositionOver1() {
        LinearGradient g = new LinearGradient(0.0,
                List.of(stop(0.0, "#FF0000"), stop(1.1, "#0000FF")));
        assertThrows(RuntimeException.class, () -> FillValidator.validate(g));
    }

    @Test
    void stopsRejectPositionNegative() {
        LinearGradient g = new LinearGradient(0.0,
                List.of(stop(-0.1, "#FF0000"), stop(1.0, "#0000FF")));
        assertThrows(RuntimeException.class, () -> FillValidator.validate(g));
    }

    @Test
    void stopsRejectBadColor() {
        LinearGradient g = new LinearGradient(0.0,
                List.of(stop(0.0, "red"), stop(1.0, "#0000FF")));
        assertThrows(RuntimeException.class, () -> FillValidator.validate(g));
    }

    // ---------- parseFillNullable 协议严格性（R12：未知字段拒绝）----------

    @Test
    void parseFillAcceptsValidLinearObject() {
        // 含合法 type/angle/stops 的渐变对象应正常解析（type 字段不应被严格 mapper 误判为未知）。
        Map<String, Object> linear = Map.of(
                "type", "linear",
                "angle", 45.0,
                "stops", List.of(
                        Map.of("position", 0.0, "color", "#FF0000"),
                        Map.of("position", 1.0, "color", "#0000FF")));
        assertDoesNotThrow(() -> ElementValidator.parseFillNullable(linear));
    }

    @Test
    void parseFillRejectsUnknownKeyInLinear() {
        // R12：协议入口渐变对象的未知字段应被拒（FILL_MAPPER_STRICT，不再静默忽略）。
        Map<String, Object> linear = Map.of(
                "type", "linear",
                "angle", 45.0,
                "stops", List.of(
                        Map.of("position", 0.0, "color", "#FF0000"),
                        Map.of("position", 1.0, "color", "#0000FF")),
                "bogusField", 123);
        assertThrows(RuntimeException.class, () -> ElementValidator.parseFillNullable(linear));
    }

    @Test
    void parseFillStillAcceptsStringSolid() {
        // 字符串形态（M0–M10 兼容）不受协议严格化影响。
        assertDoesNotThrow(() -> ElementValidator.parseFillNullable("#FF0000"));
    }
}
