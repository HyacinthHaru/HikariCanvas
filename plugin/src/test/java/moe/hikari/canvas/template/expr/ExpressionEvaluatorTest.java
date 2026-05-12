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
}
