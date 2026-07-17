package ac.haru.hikaricanvas.template.expr;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InterpolatorTest {

    private static Map<String, Object> of(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void substitutesSingleRef() {
        assertEquals("人民广场 站",
                Interpolator.interpolate("${name} 站", of("name", "人民广场")));
    }

    @Test
    void substitutesMultipleRefs() {
        assertEquals("red on white",
                Interpolator.interpolate("${a} on ${b}", of("a", "red", "b", "white")));
    }

    @Test
    void leavesPlainStringsAlone() {
        String s = "no placeholders here";
        // §6.1 性能优化路径：不含 ${ 直接返回原引用
        assertSame(s, Interpolator.interpolate(s, of()));
    }

    @Test
    void passesNullThrough() {
        assertNull(Interpolator.interpolate(null, of("a", "1")));
    }

    @Test
    void missingParamThrows() {
        var ex = assertThrows(Interpolator.MissingParamException.class,
                () -> Interpolator.interpolate("${unknown}", of()));
        assertEquals("unknown", ex.paramName());
    }

    @Test
    void nullValueBecomesEmpty() {
        // map.containsKey == true，但 value 是 null
        var params = new LinkedHashMap<String, Object>();
        params.put("a", null);
        assertEquals("[]", Interpolator.interpolate("[${a}]", params));
    }

    @Test
    void numericValueStringified() {
        assertEquals("48px",
                Interpolator.interpolate("${size}px", of("size", 48)));
    }

    @Test
    void doesNotRecurseIntoSubstitutedValue() {
        // ${a} 替换出 "${b}" 字符串后不再二次插值 —— 防止循环 / 注入
        assertEquals("${b}",
                Interpolator.interpolate("${a}", of("a", "${b}", "b", "FAIL")));
    }
}
