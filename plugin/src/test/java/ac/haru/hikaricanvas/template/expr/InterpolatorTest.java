package ac.haru.hikaricanvas.template.expr;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---------- 输出上限只算「膨胀」，不算模板自带的静态文本 ----------

    /**
     * zip 解包允许 project.json 到 10MB，而模板参数替换要把整份 JSON 过一遍插值。
     * 上限若把静态文本也计进去，一份 2MB 的工程只要含一个 {@code ${param}} 就必然导入失败，
     * 报的还是误导性的「格式错误」。
     */
    @Test
    void largeStaticTemplateWithOnePlaceholderIsNotRejected() {
        String big = "x".repeat(Interpolator.MAX_OUTPUT_LEN + 500_000);
        String out = Interpolator.interpolate(big + "${a}" + big, of("a", "-"));
        assertEquals(big.length() * 2 + 1, out.length());
    }

    /** 倍增展开仍然要挡住：净膨胀超过 1 MiB 即拒。 */
    @Test
    void runawayExpansionStillRejected() {
        String value = "y".repeat(Interpolator.MAX_VALUE_LEN);
        StringBuilder tpl = new StringBuilder();
        var params = of("a", value);
        // 100 × 16KiB = 1.6 MiB 净膨胀 > 1 MiB
        for (int i = 0; i < 100; i++) tpl.append("${a}");
        var ex = assertThrows(IllegalArgumentException.class,
                () -> Interpolator.interpolate(tpl.toString(), params));
        assertTrue(ex.getMessage().contains("exceeds limit"), ex.getMessage());
    }

    /** 单个替换值仍受 16 KiB 限制。 */
    @Test
    void oversizedSingleValueStillRejected() {
        var params = of("a", "z".repeat(Interpolator.MAX_VALUE_LEN + 1));
        var ex = assertThrows(IllegalArgumentException.class,
                () -> Interpolator.interpolate("${a}", params));
        assertTrue(ex.getMessage().contains("too large"), ex.getMessage());
    }
}
