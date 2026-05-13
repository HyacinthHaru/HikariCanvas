package moe.hikari.canvas.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M9-A：{@link PathDValidator} 词法校验边界覆盖。
 *
 * <p>不验证 path 绘制语义，只验证：</p>
 * <ul>
 *   <li>合法命令组合通过（含小写相对、Q/C 多参、Z 闭合、空白容差、逗号分隔）</li>
 *   <li>首命令非 M 拒</li>
 *   <li>非法字母拒</li>
 *   <li>不完整参数拒</li>
 *   <li>非有限数 / 溢出值拒</li>
 *   <li>超长 / 空串拒</li>
 * </ul>
 */
class PathDValidatorTest {

    private static void assertOk(String d) {
        PathDValidator.Result r = PathDValidator.validate(d);
        assertTrue(r.ok(), "expected OK but got: " + r.reason());
    }

    private static void assertFail(String d, String reasonContains) {
        PathDValidator.Result r = PathDValidator.validate(d);
        assertFalse(r.ok(), "expected fail for: " + d);
        assertTrue(r.reason() != null && r.reason().contains(reasonContains),
                "reason '" + r.reason() + "' should contain '" + reasonContains + "'");
    }

    // ---------- 合法 ----------

    @Test
    void simpleMoveLine() {
        assertOk("M 10 20 L 30 40");
    }

    @Test
    void multipleLineSegments() {
        assertOk("M 0 0 L 10 10 L 20 0 L 30 10");
    }

    @Test
    void closedPath() {
        assertOk("M 0 0 L 10 0 L 10 10 L 0 10 Z");
    }

    @Test
    void quadraticBezier() {
        assertOk("M 0 0 Q 50 -50 100 0");
    }

    @Test
    void cubicBezier() {
        assertOk("M 0 0 C 10 -20 30 -20 40 0");
    }

    @Test
    void lowercaseRelativeCommands() {
        assertOk("m 0 0 l 10 10 q 5 -5 10 0 z");
    }

    @Test
    void commaSeparated() {
        assertOk("M 0,0 L 10,10 Q 5,-5,15,0");
    }

    @Test
    void noSpaceBetweenCommandAndNumber() {
        assertOk("M0 0L10 10Z");
    }

    @Test
    void implicitLineAfterMoveto() {
        // M 后跟多组坐标 = 第一组 moveto + 后续隐式 lineto
        assertOk("M 0 0 10 10 20 20");
    }

    @Test
    void scientificNotation() {
        assertOk("M 0 0 L 1e2 1.5e1");
    }

    @Test
    void negativeNumbers() {
        assertOk("M -10 -20 L -30 -40 Q -5 -5 0 0");
    }

    @Test
    void decimalNumbers() {
        assertOk("M 0.5 1.25 L 10.5 20.75");
    }

    // ---------- 非法 ----------

    @Test
    void nullRejected() {
        assertFail(null, "null");
    }

    @Test
    void emptyRejected() {
        assertFail("", "empty");
    }

    @Test
    void onlyWhitespaceRejected() {
        assertFail("   ", "no command");
    }

    @Test
    void mustStartWithM() {
        assertFail("L 10 10", "must start with M");
        assertFail("Q 0 0 10 10", "must start with M");
        assertFail("Z", "must start with M");
    }

    @Test
    void invalidCommandLetter() {
        // A/H/V/S/T 等 SVG 命令在我们子集外
        assertFail("M 0 0 A 5 5 0 0 1 10 10", "unexpected");
        assertFail("M 0 0 H 10", "unexpected");
        assertFail("M 0 0 V 10", "unexpected");
    }

    @Test
    void incompleteMoveto() {
        assertFail("M 10", "incomplete");
    }

    @Test
    void incompleteLineto() {
        assertFail("M 0 0 L 10", "incomplete");
    }

    @Test
    void incompleteQuadratic() {
        assertFail("M 0 0 Q 10 10 20", "incomplete");
    }

    @Test
    void incompleteCubic() {
        assertFail("M 0 0 C 10 10 20 20 30", "incomplete");
    }

    @Test
    void zWithParamsRejected() {
        // Z 之后跟数字 = 不属于 Z 的参数，被下一个 case 当作 "number before any command"
        // 因为 Z 完成后 curCmd 被清 0
        assertFail("M 0 0 Z 10 10", "before any command");
    }

    @Test
    void numberOutOfRange() {
        assertFail("M 0 0 L 200000 0", "out of range");
        assertFail("M 0 0 L -200000 0", "out of range");
    }

    @Test
    void tooLong() {
        StringBuilder sb = new StringBuilder("M 0 0");
        while (sb.length() < PathDValidator.MAX_LEN + 10) sb.append(" L 1 1");
        assertFail(sb.toString(), "length");
    }

    @Test
    void atMaxLenStillOk() {
        StringBuilder sb = new StringBuilder("M 0 0");
        while (sb.length() < PathDValidator.MAX_LEN - 10) sb.append(" L 1 1");
        assertOk(sb.toString());
    }

    @Test
    void doubleSignRejected() {
        // "--10" 不是合法数字：scanNumber 收符号后下一字符必须 digit/'.'
        assertFail("M 0 0 L --10 0", "malformed");
    }

    @Test
    void scientificWithoutExponentDigits() {
        // "1e" 后无 digit；scanNumber 会退回 'e' 之前，剩下 'e' 触发"unexpected character"
        assertFail("M 0 0 L 1e 0", "unexpected");
    }

    // ---------- normalize ----------

    @Test
    void normalizeRemovesExtraWhitespace() {
        assertEquals("m 0 0 l 10 10", PathDValidator.normalize("M  0\t0\n L 10\r10"));
    }

    @Test
    void normalizeOnNull() {
        assertEquals("", PathDValidator.normalize(null));
    }
}
