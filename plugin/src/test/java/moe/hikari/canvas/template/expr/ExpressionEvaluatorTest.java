package moe.hikari.canvas.template.expr;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionEvaluatorTest {

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionEvaluator eval = new ExpressionEvaluator();

    private static Map<String, Object> of(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private boolean run(String src, Map<String, Object> params) {
        return eval.evalBoolean(parser.parse(src), params);
    }

    @Test
    void boolIdentifierTruthy() {
        assertTrue(run("flag", of("flag", true)));
        assertFalse(run("flag", of("flag", false)));
        assertFalse(run("flag", of("flag", null)));
    }

    @Test
    void stringTruthyByEmpty() {
        assertTrue(run("name", of("name", "Hikari")));
        assertFalse(run("name", of("name", "")));
    }

    @Test
    void numberTruthyByZero() {
        assertTrue(run("n", of("n", 3)));
        assertFalse(run("n", of("n", 0)));
    }

    @Test
    void equalityBoolean() {
        assertTrue(run("flag == true", of("flag", true)));
        assertFalse(run("flag == true", of("flag", false)));
    }

    @Test
    void equalityNumber() {
        assertTrue(run("a == b", of("a", 3, "b", 3.0)));
        assertFalse(run("a == b", of("a", 3, "b", 4)));
    }

    @Test
    void equalityString() {
        assertTrue(run("s == \"yes\"", of("s", "yes")));
        assertTrue(run("s != \"yes\"", of("s", "no")));
    }

    @Test
    void notOperator() {
        assertTrue(run("!flag", of("flag", false)));
        assertFalse(run("!flag", of("flag", true)));
    }

    @Test
    void shortCircuitAndOr() {
        // 若 && 短路，则即使 right 引用未声明的 param 也不应抛
        assertFalse(run("flag && undefined_one == 1", of("flag", false, "undefined_one", 0)));
        assertTrue(run("flag || undefined_one == 1", of("flag", true, "undefined_one", 0)));
    }

    @Test
    void specCanonicalCases() {
        // show_english == true && name != ""
        assertTrue(run("show_english == true && name != \"\"",
                of("show_english", true, "name", "Hikari")));
        assertFalse(run("show_english == true && name != \"\"",
                of("show_english", false, "name", "Hikari")));
        assertFalse(run("show_english == true && name != \"\"",
                of("show_english", true, "name", "")));
    }

    @Test
    void undeclaredIdentifierThrows() {
        assertThrows(ExpressionEvaluator.UndeclaredParamException.class,
                () -> run("missing", of()));
    }

    @Test
    void parensControlPrecedence() {
        // (a || b) && c: a=false, b=true, c=false → false
        assertFalse(run("(a || b) && c",
                of("a", false, "b", true, "c", false)));
        // a || (b && c): true
        assertTrue(run("a || (b && c)",
                of("a", true, "b", false, "c", false)));
    }

    @Test
    void evalReturnsRawWhenLiteral() {
        assertEquals(7.0, eval.eval(parser.parse("7"), of()));
        assertEquals("hi", eval.eval(parser.parse("\"hi\""), of()));
        assertEquals(Boolean.TRUE, eval.eval(parser.parse("true"), of()));
    }

    // ──────────────────────────────────────────────────────────
    //  0.7.0-P2(K2/K3)：比较 / 算术 / var() 求值语义
    // ──────────────────────────────────────────────────────────

    /** 带 resolver 的求值器（K3 注入;map miss → null）。 */
    private boolean runVar(String src, Map<String, String> vars) {
        ExpressionEvaluator withResolver = new ExpressionEvaluator(vars::get, null);
        return withResolver.evalBoolean(parser.parse(src), of());
    }

    @Test
    void varResolvesThroughInjectedResolver() {
        assertTrue(runVar("var(\"user/score\") >= 10", Map.of("user/score", "10")));
        assertFalse(runVar("var(\"user/score\") >= 10", Map.of("user/score", "9")));
        // 字符串值参与等值
        assertTrue(runVar("var(\"phase\") == \"RUNNING\"", Map.of("phase", "RUNNING")));
    }

    @Test
    void varMissEvaluatesToNullFalsy() {
        // resolver 返 null → eval null → truthy=false
        assertFalse(runVar("var(\"user/missing\")", Map.of()));
        // 比较里 null 强转 0.0:0 >= 10 false / 0 < 10 true
        assertFalse(runVar("var(\"user/missing\") >= 10", Map.of()));
        assertTrue(runVar("var(\"user/missing\") < 10", Map.of()));
    }

    @Test
    void varWithoutResolverThrows() {
        // 模板路径(无 resolver)不该出现 var()
        assertThrows(ExpressionEvaluator.VarUnsupportedException.class,
                () -> eval.evalBoolean(parser.parse("var(\"x\")"), of()));
    }

    @Test
    void numericStringComparesNumerically() {
        // "10" vs 9:任一侧数值形态 → 双侧 StrictNumber 数值比较(字典序会得 "10"<"9")
        assertTrue(run("s > 9", of("s", "10")));
        assertTrue(runVar("var(\"x\") > 9", Map.of("x", "10")));
        // 两侧都是数值字符串同理
        assertTrue(runVar("var(\"x\") > var(\"y\")", Map.of("x", "10", "y", "9")));
    }

    @Test
    void nonNumericStringsFallBackToCompareTo() {
        assertTrue(run("a < b", of("a", "apple", "b", "banana")));
        assertFalse(run("a > b", of("a", "apple", "b", "banana")));
        assertTrue(run("a <= b", of("a", "same", "b", "same")));
    }

    @Test
    void arithmeticPrecedenceEndToEnd() {
        assertTrue(run("1 + 2 * 3 == 7", of()));
        assertFalse(run("(1 + 2) * 3 == 7", of()));
        assertTrue(run("(1 + 2) * 3 == 9", of()));
    }

    @Test
    void divisionByZeroYieldsZeroNotThrow() {
        // K2:除零 → 0.0,不抛;0.0 falsy
        assertFalse(run("1 / 0", of()));
        assertTrue(run("1 / 0 == 0", of()));
        assertTrue(run("10 / n == 0", of("n", 0)));
    }

    @Test
    void plusCoercesToNumberNeverConcatenates() {
        // K2:+ 恒数值,非数字侧 StrictNumber 强转为 0("a"+5 = 5,不是 "a5")
        assertTrue(run("s + 5 == 5", of("s", "abc")));
        assertTrue(runVar("var(\"x\") + 1 == 13", Map.of("x", "12")));
    }

    @Test
    void negativeLiteralsAndUnaryMinus() {
        assertTrue(run("-3 < 0", of()));
        assertTrue(run("-3 + 4 == 1", of()));
        // -var(x):x="5" → -5
        assertTrue(runVar("-var(\"x\") < 0", Map.of("x", "5")));
        assertTrue(runVar("-var(\"x\") == -5", Map.of("x", "5")));
        // x="0" → -0 归一为 0,不得 < 0
        assertFalse(runVar("-var(\"x\") < 0", Map.of("x", "0")));
    }

    @Test
    void arithmeticResultIsTruthyByZero() {
        assertFalse(run("2 - 2", of()));
        assertTrue(run("2 - 1", of()));
    }

    @Test
    void equalityRegressionUnchangedByExtension() {
        // 回归红线:== / != 语义不动(数字宽容等值 / Boolean truthy 等值 / 字符串等值);
        // 数值形态字符串在 == 下仍走 toString 等值(不是 StrictNumber):"3.50" != "3.5"
        assertTrue(run("a == b", of("a", 3, "b", 3.0)));
        assertTrue(run("flag == true", of("flag", true)));
        assertTrue(run("s == \"yes\"", of("s", "yes")));
        assertFalse(run("s == 3.5", of("s", "3.50")));
    }
}
