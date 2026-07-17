package ac.haru.hikaricanvas.variable;

import ac.haru.hikaricanvas.storage.UserVariableDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 0.6 P3（rendering.md §9.5）：{@link VariableInterpolator#resolveAsNumber} 单测。
 *
 * <p>数值关键帧轨的 {@code ${var:X}} / 数字字符串求值。fallback 链 4 档（cached → inline
 * fallback → defaultValue → "???"）resolve 后解析为 double；非数值（含 "???"）/ 非有限值 /
 * 解析失败一律落到链终点 {@code 0.0}。</p>
 *
 * <p>与 {@link VariableInterpolatorTest} 同构 fake DAO；wallId 注入与 fallback 链本身已由
 * 该测覆盖，本类只聚焦 resolveAsNumber 的「字符串 → double 落点」语义。</p>
 */
class ResolveAsNumberTest {

    private VariableStore store;
    private VariableInterpolator interp;

    @BeforeEach
    void setUp() {
        FakeUserVariableDao fakeDao = new FakeUserVariableDao();
        store = new VariableStore(fakeDao, w -> { });
        interp = new VariableInterpolator(store);
    }

    @Test
    void plainNumberString() {
        assertEquals(42.0, interp.resolveAsNumber("42", "w-1"), 1e-9, "纯整数字符串");
        assertEquals(-3.5, interp.resolveAsNumber("-3.5", "w-1"), 1e-9, "负小数字符串");
        assertEquals(7.0, interp.resolveAsNumber("  7  ", "w-1"), 1e-9, "带空白 → trim 后解析");
    }

    @Test
    void variableCachedNumericValue() {
        store.create("bedwars", "score", VarType.NUMBER, null, null);
        store.setValue("bedwars/score", "123", null);
        assertEquals(123.0, interp.resolveAsNumber("${var:bedwars/score}", "w-1"), 1e-9,
                "cached 命中数值 → 该值");
    }

    @Test
    void variableCachedNonNumericFallsToZero() {
        store.create("bedwars", "phase", VarType.STRING, null, null);
        store.setValue("bedwars/phase", "RUNNING", null);
        assertEquals(0.0, interp.resolveAsNumber("${var:bedwars/phase}", "w-1"), 0.0,
                "cached 命中非数值 → 链终点 0");
    }

    @Test
    void inlineFallbackNumeric() {
        // 变量不存在 → 用 inline fallback；fallback 是数值
        assertEquals(9.0, interp.resolveAsNumber("${var:bedwars/missing|fallback=9}", "w-1"), 1e-9,
                "变量不存在 → inline fallback 数值");
    }

    @Test
    void missingNoFallbackResolvesToUnresolvedThenZero() {
        // 变量不存在无 fallback → "???" → 非数值 → 0
        assertEquals(0.0, interp.resolveAsNumber("${var:bedwars/missing}", "w-1"), 0.0,
                "变量不存在无 fallback → ??? → 0");
    }

    @Test
    void nullAndEmptyToZero() {
        assertEquals(0.0, interp.resolveAsNumber(null, "w-1"), 0.0, "null → 0");
        assertEquals(0.0, interp.resolveAsNumber("", "w-1"), 0.0, "空串 → 0");
    }

    @Test
    void infinityAndNanStringsToZero() {
        // Double.parseDouble 接受 "Infinity"/"NaN"，但非有限值落 0（§9.5）
        assertEquals(0.0, interp.resolveAsNumber("Infinity", "w-1"), 0.0, "Infinity 字符串 → 0");
        assertEquals(0.0, interp.resolveAsNumber("-Infinity", "w-1"), 0.0, "-Infinity 字符串 → 0");
        assertEquals(0.0, interp.resolveAsNumber("NaN", "w-1"), 0.0, "NaN 字符串 → 0");
    }

    @Test
    void strictGrammarRejectsHostLenientForms() {
        // P3 审查 #1：resolveAsNumber 改走 StrictNumber.parse（不私自 parseDouble），否则变量
        // resolve 出 "0x1p4" / "5d" 会被接受（16.0 / 5.0）而与 TS 端 + 字面值路径分叉
        store.create("bedwars", "raw", VarType.STRING, null, null);
        store.setValue("bedwars/raw", "0x1p4", null);
        assertEquals(0.0, interp.resolveAsNumber("${var:bedwars/raw}", "w-1"), 0.0,
                "变量 resolve 出 0x1p4 → 严格文法拒 → 0（非 16.0）");
        store.setValue("bedwars/raw", "5d", null);
        assertEquals(0.0, interp.resolveAsNumber("${var:bedwars/raw}", "w-1"), 0.0,
                "尾随 d → 0（非 5.0）");
        assertEquals(0.0, interp.resolveAsNumber("0x1p4", "w-1"), 0.0, "字面 0x1p4 → 0");
        assertEquals(0.0, interp.resolveAsNumber("12abc", "w-1"), 0.0, "字面 12abc → 0");
    }

    @Test
    void trimStripsOnlyAsciiWhitespace() {
        // P3 审查 #3：trim 剥 ≤U+0020；NBSP 不剥 → 文法不匹配 → 0（与 TS 端 [U+0000..U+0020] strip 对齐）
        assertEquals(0.0, interp.resolveAsNumber(" 12", "w-1"), 0.0, "NBSP+12 → 0");
        assertEquals(5.0, interp.resolveAsNumber("\t5\n", "w-1"), 1e-9, "ASCII 制表/换行 → 5");
    }

    @Test
    void mixedTemplateNonNumericToZero() {
        // 含模板的混合串：resolve 后是 "abc<value>" 形态，非纯数值 → 0
        store.create("bedwars", "x", VarType.NUMBER, null, null);
        store.setValue("bedwars/x", "5", null);
        assertEquals(0.0, interp.resolveAsNumber("abc${var:bedwars/x}", "w-1"), 0.0,
                "混合串 resolve 出 abc5 非纯数值 → 0");
    }

    @Test
    void mixedTemplateResolvingToPureNumberParses() {
        // 混合串 resolve 后恰为纯数值串则可解析（前缀变量值 + 字面数字拼成数字）
        store.create("bedwars", "tens", VarType.NUMBER, null, null);
        store.setValue("bedwars/tens", "1", null);
        // "${var:bedwars/tens}0" → "10" → 10.0
        assertEquals(10.0, interp.resolveAsNumber("${var:bedwars/tens}0", "w-1"), 1e-9,
                "resolve 后恰为纯数值串 → 解析");
    }

    @Test
    void userNamespaceWallIdInjectedNumeric() {
        // user/X 经 wallId 注入命中数值
        store.create("user:w-7", "hp", VarType.NUMBER, null, null);
        store.setValue("user:w-7/hp", "88", null);
        assertEquals(88.0, interp.resolveAsNumber("${var:user/hp}", "w-7"), 1e-9,
                "user/X wallId 注入后数值命中");
    }

    // ──────────────────────────────────────────────────────────
    //  Helper：与 VariableInterpolatorTest 同构的 fake DAO
    // ──────────────────────────────────────────────────────────

    private static final class FakeUserVariableDao extends UserVariableDao {
        FakeUserVariableDao() {
            super(Logger.getLogger("test"), null);
        }

        @Override
        public void upsert(String wallId, String name, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {
            // no-op
        }

        @Override
        public void delete(String wallId, String name) {
            // no-op
        }

        @Override
        public List<Row> loadAll() {
            return new ArrayList<>();
        }
    }
}
