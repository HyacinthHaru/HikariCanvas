package moe.hikari.canvas.variable;

import moe.hikari.canvas.storage.UserVariableDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-P1-C：{@link VariableInterpolator} 单测。
 *
 * <p>覆盖 fallback 链 4 档（cached → inline fallback → defaultValue → "???"）+ user namespace
 * wallId 注入 + 多占位符 + {@code $} / {@code \} 转义防注入 + referenced 集合正确性。</p>
 */
class VariableInterpolatorTest {

    private FakeUserVariableDao fakeDao;
    private VariableStore store;
    private VariableInterpolator interp;

    @BeforeEach
    void setUp() {
        fakeDao = new FakeUserVariableDao();
        store = new VariableStore(fakeDao, w -> { });
        interp = new VariableInterpolator(store);
    }

    // ──────────────────────────────────────────────────────────
    //  passthrough / 边界
    // ──────────────────────────────────────────────────────────

    @Test
    void nullText_passthrough() {
        var r = interp.interpolate(null, "w-1");
        assertNull(r.text());
        assertTrue(r.referencedFullNames().isEmpty());
    }

    @Test
    void emptyText_passthrough() {
        var r = interp.interpolate("", "w-1");
        assertEquals("", r.text());
        assertTrue(r.referencedFullNames().isEmpty());
    }

    @Test
    void plainText_passthroughWithoutAllocation() {
        String src = "Hello, World — 纯文本无占位符";
        var r = interp.interpolate(src, "w-1");
        assertSame(src, r.text(), "纯文本应短路返回原引用");
        assertTrue(r.referencedFullNames().isEmpty());
    }

    // ──────────────────────────────────────────────────────────
    //  user namespace wallId 注入
    // ──────────────────────────────────────────────────────────

    @Test
    void userNamespace_injectsWallIdAndResolvesCurrentValue() {
        store.create("user:w-3a17", "red_score", VarType.NUMBER, "0", null);
        store.setValue("user:w-3a17/red_score", "42", null);

        var r = interp.interpolate("红队 ${var:user/red_score} 分", "w-3a17");
        assertEquals("红队 42 分", r.text());
        assertTrue(r.referencedFullNames().contains("user:w-3a17/red_score"),
                "user/X 应被注入成 user:<wallId>/X");
    }

    @Test
    void userNamespace_nullWallIdFallsBackLiterally() {
        // wallId 为 null 时 user/X 走字面查 store；store 内没有 "user/X" → fallback 链
        var r = interp.interpolate("${var:user/x}", null);
        assertEquals(VariableInterpolator.UNRESOLVED, r.text());
        assertTrue(r.referencedFullNames().contains("user/x"));
    }

    // ──────────────────────────────────────────────────────────
    //  插件 / 系统 namespace 字面解析
    // ──────────────────────────────────────────────────────────

    @Test
    void pluginNamespace_literalLookup() {
        store.create("bedwars", "score", VarType.NUMBER, "0", "BedWars");
        store.setValue("bedwars/score", "10", null);

        var r = interp.interpolate("${var:bedwars/score}", "w-1");
        assertEquals("10", r.text());
        assertTrue(r.referencedFullNames().contains("bedwars/score"));
    }

    @Test
    void systemDottedAlias_literalLookup() {
        // 点分号 alias 视为字面 fullName 查 store——P3 Provider 注册同名 fullName
        store.create("server", "time", VarType.STRING, "00:00:00", "system");
        store.setValue("server/time", "14:30:00", null);

        // ${var:server.time} 字面 → "server.time" namespace 不存在
        // 但项目语义上点分号是 alias；P1 阶段我们按字面 fullName 查（store 里没有 "server.time"）
        var r1 = interp.interpolate("${var:server.time}", "w-1");
        assertEquals(VariableInterpolator.UNRESOLVED, r1.text(),
                "点分号 alias 在 P1 按字面查；未注册则走兜底");
        assertTrue(r1.referencedFullNames().contains("server.time"));

        // ${var:server/time} 走 fullName 路径 → 命中
        var r2 = interp.interpolate("${var:server/time}", "w-1");
        assertEquals("14:30:00", r2.text());
        assertTrue(r2.referencedFullNames().contains("server/time"));
    }

    // ──────────────────────────────────────────────────────────
    //  fallback 链 4 档
    // ──────────────────────────────────────────────────────────

    @Test
    void fallback_noVariable_noInline_returnsUnresolved() {
        var r = interp.interpolate("${var:bedwars/missing}", "w-1");
        assertEquals(VariableInterpolator.UNRESOLVED, r.text());
        assertTrue(r.referencedFullNames().contains("bedwars/missing"));
    }

    @Test
    void fallback_noVariable_withInline_usesInline() {
        var r = interp.interpolate("${var:bedwars/missing|fallback=N/A}", "w-1");
        assertEquals("N/A", r.text());
    }

    @Test
    void fallback_currentEmptyWithInline_usesInline() {
        store.create("bedwars", "score", VarType.NUMBER, "DEFAULT", null);
        // currentValue 留 null（create 不设 current）；fallback 链：current empty → inline
        var r = interp.interpolate("${var:bedwars/score|fallback=zero}", "w-1");
        assertEquals("zero", r.text(),
                "current 空时 inline fallback 优先于 defaultValue");
    }

    @Test
    void fallback_currentEmptyNoInline_usesDefault() {
        store.create("bedwars", "score", VarType.NUMBER, "DEFAULT", null);
        var r = interp.interpolate("${var:bedwars/score}", "w-1");
        assertEquals("DEFAULT", r.text(),
                "current 空 + 无 inline fallback → defaultValue");
    }

    @Test
    void fallback_currentEmptyNoInlineNoDefault_returnsUnresolved() {
        store.create("bedwars", "score", VarType.NUMBER, null, null);
        var r = interp.interpolate("${var:bedwars/score}", "w-1");
        assertEquals(VariableInterpolator.UNRESOLVED, r.text(),
                "所有 fallback 档都空 → ???");
    }

    @Test
    void fallback_currentSetTakesPrecedence() {
        store.create("bedwars", "score", VarType.NUMBER, "DEFAULT", null);
        store.setValue("bedwars/score", "99", null);
        // current 非空 → 即使 inline fallback 存在也用 current
        var r = interp.interpolate("${var:bedwars/score|fallback=NO}", "w-1");
        assertEquals("99", r.text());
    }

    @Test
    void fallback_inlineEmptyString_isValidFallback() {
        // ${var:X|fallback=} 显式空 fallback —— 视为合法 "" 值，优于 default / ???
        var r = interp.interpolate("[${var:missing|fallback=}]", "w-1");
        assertEquals("[]", r.text(),
                "显式空 fallback 是合法值，不退 default / ???");
    }

    // ──────────────────────────────────────────────────────────
    //  多占位符 / 混合文本
    // ──────────────────────────────────────────────────────────

    @Test
    void multiplePlaceholders_resolvedIndependently() {
        store.create("user:w-1", "red", VarType.NUMBER, "0", null);
        store.create("user:w-1", "blue", VarType.NUMBER, "0", null);
        store.setValue("user:w-1/red", "10", null);
        store.setValue("user:w-1/blue", "7", null);

        var r = interp.interpolate(
                "红队 ${var:user/red} 分，蓝队 ${var:user/blue} 分",
                "w-1");
        assertEquals("红队 10 分，蓝队 7 分", r.text());
        assertEquals(2, r.referencedFullNames().size());
        assertTrue(r.referencedFullNames().contains("user:w-1/red"));
        assertTrue(r.referencedFullNames().contains("user:w-1/blue"));
    }

    @Test
    void mixedResolveAndFallback() {
        store.create("bedwars", "phase", VarType.STRING, null, null);
        store.setValue("bedwars/phase", "RUNNING", null);
        // phase 已设；score 不存在 → 走 inline fallback
        var r = interp.interpolate(
                "${var:bedwars/phase}: ${var:bedwars/score|fallback=--}",
                "w-1");
        assertEquals("RUNNING: --", r.text());
        assertEquals(2, r.referencedFullNames().size());
    }

    // ──────────────────────────────────────────────────────────
    //  转义防注入
    // ──────────────────────────────────────────────────────────

    @Test
    void dollarSignInValue_doesNotTriggerBackReference() {
        // 变量值含 $ 不应被 Matcher.appendReplacement 解释成 $1 反向引用
        store.create("bedwars", "msg", VarType.STRING, null, null);
        store.setValue("bedwars/msg", "$100 USD", null);
        var r = interp.interpolate("Price: ${var:bedwars/msg}", "w-1");
        assertEquals("Price: $100 USD", r.text());
    }

    @Test
    void backslashInValue_isLiteral() {
        store.create("bedwars", "path", VarType.STRING, null, null);
        store.setValue("bedwars/path", "C:\\Users\\Foo", null);
        var r = interp.interpolate("路径=${var:bedwars/path}", "w-1");
        assertEquals("路径=C:\\Users\\Foo", r.text());
    }

    // ──────────────────────────────────────────────────────────
    //  referenced 集合精确性
    // ──────────────────────────────────────────────────────────

    @Test
    void referencedFullNames_includesAllPlaceholdersEvenWhenUnresolved() {
        var r = interp.interpolate(
                "${var:a/x} ${var:b/y|fallback=Y} ${var:user/z}",
                "w-1");
        // 全部 placeholder 应该都在 referenced 里——即使变量不存在
        assertTrue(r.referencedFullNames().contains("a/x"));
        assertTrue(r.referencedFullNames().contains("b/y"));
        assertTrue(r.referencedFullNames().contains("user:w-1/z"));
        assertEquals(3, r.referencedFullNames().size());
    }

    @Test
    void rawNameWhitespaceTrimmed() {
        // 容错：${var: bedwars/score } 视为 bedwars/score
        store.create("bedwars", "score", VarType.NUMBER, null, null);
        store.setValue("bedwars/score", "5", null);
        var r = interp.interpolate("${var: bedwars/score }", "w-1");
        assertEquals("5", r.text());
        assertTrue(r.referencedFullNames().contains("bedwars/score"));
    }

    // ──────────────────────────────────────────────────────────
    //  渲染期不引发副作用
    // ──────────────────────────────────────────────────────────

    @Test
    void interpolate_doesNotMutateStore() {
        store.create("bedwars", "x", VarType.STRING, null, null);
        long updatedBefore = store.get("bedwars/x").orElseThrow().updatedAt();
        interp.interpolate("${var:bedwars/x|fallback=Z}", "w-1");
        long updatedAfter = store.get("bedwars/x").orElseThrow().updatedAt();
        assertEquals(updatedBefore, updatedAfter,
                "interpolate 是只读操作，不应修改 store 状态");
    }

    @Test
    void interpolate_doesNotTouchInvertedIndex() {
        // referencedByWalls 维护由 store.markWallReferences 显式做；interpolate 只返集合，不写
        store.create("bedwars", "y", VarType.STRING, null, null);
        interp.interpolate("${var:bedwars/y}", "w-1");
        assertTrue(store.referencedFullNamesByWall("w-1").isEmpty(),
                "interpolate 不写倒排索引（由 Compositor 末尾 markWallReferences 写）");
    }

    // ──────────────────────────────────────────────────────────
    //  Helper：与 VariableStoreTest 同构的 fake DAO
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
