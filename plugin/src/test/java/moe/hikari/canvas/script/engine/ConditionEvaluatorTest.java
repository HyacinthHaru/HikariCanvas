package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.storage.UserVariableDao;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.VariableStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0-P2(T2)：{@link ConditionEvaluator} 单测——parse 缓存（含负缓存）/ 坏条件
 * false + 单次 WARN / wallId 注入（{@code user/X} → {@code user:<wallId>/X}）/
 * VariableStore 装配路径（cached fresh → default → null fallback 链）。
 */
class ConditionEvaluatorTest {

    /** 捕获 WARNING 的 logger（每实例独立命名,防跨测试串扰）。 */
    private static final class CapturingLogger {
        final Logger logger;
        final List<LogRecord> warnings = new ArrayList<>();

        CapturingLogger(String name) {
            logger = Logger.getLogger("test.condeval." + name);
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord r) {
                    if (r.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(r);
                }
                @Override public void flush() {}
                @Override public void close() {}
            });
        }
    }

    // ──────────────────────────────────────────────────────────
    //  基础求值 + wallId 注入
    // ──────────────────────────────────────────────────────────

    @Test
    void evalResolvesVarWithWallIdInjection() {
        // lookup 收到的必须是 resolveFullName 注入后的内部形态
        List<String> seen = new ArrayList<>();
        Map<String, String> values = Map.of("user:w-1/score", "12");
        ConditionEvaluator ce = new ConditionEvaluator(
                Logger.getLogger("test"), fullName -> {
                    seen.add(fullName);
                    return values.get(fullName);
                });

        assertTrue(ce.eval("var(\"user/score\") >= 10", "w-1"));
        assertEquals(List.of("user:w-1/score"), seen, "user/X 应注入 wallId");
        // 同条件不同 wall:lookup 收到不同 fullName(resolver 不跨 wall 复用)
        assertFalse(ce.eval("var(\"user/score\") >= 10", "w-2"));
        assertEquals("user:w-2/score", seen.get(1));
    }

    @Test
    void plainConditionsWithoutVariables() {
        ConditionEvaluator ce = new ConditionEvaluator(
                Logger.getLogger("test"), (VariableStore) null);
        assertTrue(ce.eval("1 + 2 * 3 == 7", "w-1"));
        assertFalse(ce.eval("1 > 2", "w-1"));
        assertTrue(ce.eval("\"a\" < \"b\"", "w-1"));
    }

    @Test
    void nullOrBlankConditionIsFalse() {
        CapturingLogger cap = new CapturingLogger("blank");
        ConditionEvaluator ce = new ConditionEvaluator(cap.logger, (VariableStore) null);
        assertFalse(ce.eval(null, "w-1"));
        assertFalse(ce.eval("", "w-1"));
        assertFalse(ce.eval("   ", "w-1"));
        assertTrue(cap.warnings.isEmpty(), "空白条件是兜底路径,不该刷 WARN");
    }

    @Test
    void varMissYieldsFalse() {
        // store=null → lookup 为 null → var() 求值抛 VarUnsupported → false(不炸链)
        CapturingLogger cap = new CapturingLogger("nostore");
        ConditionEvaluator noStore = new ConditionEvaluator(cap.logger, (VariableStore) null);
        assertFalse(noStore.eval("var(\"user/x\") > 0", "w-1"));
        assertEquals(1, cap.warnings.size());

        // lookup 注入但变量 miss(返 null)→ truthy false / 比较侧强转 0.0,不 WARN
        // (注意 == 保持旧语义:null == 0 是 false,不做 K2 强转——回归红线)
        CapturingLogger cap2 = new CapturingLogger("miss");
        ConditionEvaluator missing = new ConditionEvaluator(cap2.logger, fullName -> null);
        assertFalse(missing.eval("var(\"user/x\")", "w-1"));
        assertFalse(missing.eval("var(\"user/x\") > 0", "w-1"));
        assertTrue(missing.eval("var(\"user/x\") <= 0", "w-1"));
        assertFalse(missing.eval("var(\"user/x\") == 0", "w-1"));
        assertTrue(cap2.warnings.isEmpty(), "变量 miss 是正常 fallback,不该 WARN");
    }

    // ──────────────────────────────────────────────────────────
    //  parse 缓存（含负缓存）+ 单次 WARN
    // ──────────────────────────────────────────────────────────

    @Test
    void parseCacheAvoidsReparse() {
        ConditionEvaluator ce = new ConditionEvaluator(
                Logger.getLogger("test"), (VariableStore) null);
        assertEquals(0, ce.parseCountForTest());
        ce.eval("1 < 2", "w-1");
        ce.eval("1 < 2", "w-1");
        ce.eval("1 < 2", "w-2"); // 换 wall 不换 parse(缓存按条件串)
        assertEquals(1, ce.parseCountForTest(), "同条件串只 parse 一次");
        ce.eval("2 < 3", "w-1");
        assertEquals(2, ce.parseCountForTest());
    }

    @Test
    void badConditionIsFalseCachedNegativelyAndWarnsOnce() {
        CapturingLogger cap = new CapturingLogger("bad");
        ConditionEvaluator ce = new ConditionEvaluator(cap.logger, (VariableStore) null);

        assertFalse(ce.eval("1 <", "w-1"));
        assertFalse(ce.eval("1 <", "w-1"));
        assertFalse(ce.eval("1 <", "w-1"));
        // 负缓存:坏条件只 parse 一次
        assertEquals(1, ce.parseCountForTest(), "parse 失败应缓存负结果");
        // 防刷屏:只 WARN 一次
        assertEquals(1, cap.warnings.size(), "同条件串只 WARN 一次: " + cap.warnings);
        assertTrue(cap.warnings.get(0).getMessage().contains("1 <"));

        // 另一条坏条件独立计数
        assertFalse(ce.eval("var(", "w-1"));
        assertEquals(2, ce.parseCountForTest());
        assertEquals(2, cap.warnings.size());
    }

    @Test
    void evalFailureWarnsOnceAndReturnsFalse() {
        // 裸标识符在脚本条件里(params 恒空)→ UndeclaredParamException → false + 单次 WARN
        CapturingLogger cap = new CapturingLogger("ident");
        ConditionEvaluator ce = new ConditionEvaluator(cap.logger, (VariableStore) null);
        assertFalse(ce.eval("some_param == 1", "w-1"));
        assertFalse(ce.eval("some_param == 1", "w-1"));
        assertEquals(1, cap.warnings.size(), "eval 失败同样只 WARN 一次");
    }

    // ──────────────────────────────────────────────────────────
    //  VariableStore 装配路径（fallback 链:cached fresh → default → null）
    // ──────────────────────────────────────────────────────────

    @Test
    void storeLookupFollowsFallbackChain() {
        VariableStore store = new VariableStore(new FakeUserVariableDao(), w -> {});
        ConditionEvaluator ce = new ConditionEvaluator(Logger.getLogger("test"), store);

        // cached 值(== 旧语义是 toString 等值:变量值是字符串,等值要么引号要么走比较)
        store.create("bedwars", "score", VarType.NUMBER, null, null);
        store.setValue("bedwars/score", "42", null);
        assertTrue(ce.eval("var(\"bedwars/score\") == \"42\"", "w-1"));
        assertTrue(ce.eval("var(\"bedwars/score\") >= 42 && var(\"bedwars/score\") <= 42", "w-1"));
        assertTrue(ce.eval("var(\"bedwars/score\") >= 40 && var(\"bedwars/score\") < 50", "w-1"));

        // 无 cached 值 → defaultValue
        store.create("bedwars", "phase", VarType.STRING, "LOBBY", null);
        assertTrue(ce.eval("var(\"bedwars/phase\") == \"LOBBY\"", "w-1"));

        // 不存在 → null → falsy
        assertFalse(ce.eval("var(\"bedwars/nope\")", "w-1"));
    }

    @Test
    void storeLookupTriggersDynamicLookupOnMiss() {
        VariableStore store = new VariableStore(new FakeUserVariableDao(), w -> {});
        List<String> dynamicLookups = new ArrayList<>();
        store.registerDynamicLookupHook((fullName, namespace) -> dynamicLookups.add(fullName));
        ConditionEvaluator ce = new ConditionEvaluator(Logger.getLogger("test"), store);

        assertFalse(ce.eval("var(\"scoreboard.kills.Steve\") > 0", "w-1"));
        // scoreboard.<obj>.<player> alias → scoreboard/<obj>.<player>;miss 应触发动态注册 hook
        assertEquals(List.of("scoreboard/kills.Steve"), dynamicLookups);
    }

    @Test
    void cacheClearsWhenOverCapacity() {
        ConditionEvaluator ce = new ConditionEvaluator(
                Logger.getLogger("test"), (VariableStore) null);
        // 直接灌到上限再 eval 新条件:不抛、结果正确(整体 clear 重建)
        for (int i = 0; i < ConditionEvaluator.CACHE_MAX; i++) {
            ce.eval("1 == " + i, "w-1");
        }
        assertTrue(ce.eval("7 == 7", "w-1"), "缓存超限后求值仍正确");
    }

    // ──────────────────────────────────────────────────────────
    //  0.7.0-P3 A2（K16）：checkSyntax 保存期预 parse
    // ──────────────────────────────────────────────────────────

    @Test
    void checkSyntax_validConditions_empty() {
        assertTrue(ConditionEvaluator.checkSyntax("1 == 1").isEmpty());
        assertTrue(ConditionEvaluator.checkSyntax("var(\"user/score\") >= 10").isEmpty());
        assertTrue(ConditionEvaluator.checkSyntax(
                "var(\"user/a\") == 1 && var(\"user/b\") != 2").isEmpty());
    }

    @Test
    void checkSyntax_badConditions_firstLineMessage() {
        var err = ConditionEvaluator.checkSyntax("((((");
        assertTrue(err.isPresent(), "坏条件保存期必须拒");
        assertFalse(err.get().contains("\n"), "外发信息只留首行");
        assertTrue(ConditionEvaluator.checkSyntax("&&&&").isPresent());
        assertTrue(ConditionEvaluator.checkSyntax("1 ==").isPresent());
    }

    @Test
    void checkSyntax_nullOrBlank_empty_validatorOwnsNonBlank() {
        // 非空校验是 ScriptRuleValidator 的职责——这里不重复拒
        assertTrue(ConditionEvaluator.checkSyntax(null).isEmpty());
        assertTrue(ConditionEvaluator.checkSyntax("   ").isEmpty());
    }

    // ──────────────────────────────────────────────────────────
    //  fakes
    // ──────────────────────────────────────────────────────────

    /** 纯内存 dao（照 ResolveAsNumberTest 同款）。 */
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
