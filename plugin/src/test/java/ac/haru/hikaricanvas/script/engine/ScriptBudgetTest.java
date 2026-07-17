package ac.haru.hikaricanvas.script.engine;

import ac.haru.hikaricanvas.HikariCanvasConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0-P2-3：{@link ScriptBudget} 三闸 + K5 audit 限频。clock 注入全确定性，不 sleep。
 */
class ScriptBudgetTest {

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private ScriptBudget budget() {
        return new ScriptBudget(HikariCanvasConfig.ScriptsConfig.defaults(), clock::get);
    }

    private ScriptBudget budget(int actions, int runs, int chain) {
        return new ScriptBudget(
                new HikariCanvasConfig.ScriptsConfig(16, actions, runs, chain), clock::get);
    }

    // ---------- runs/s 固定窗 ----------

    @Test
    void runsPerSecond_windowBoundary_9_10_11() {
        ScriptBudget b = budget(); // 默认 10/s
        for (int i = 0; i < 9; i++) {
            assertTrue(b.tryAcquireRun("w-1:r-1"), "第 " + (i + 1) + " 次应放行");
        }
        assertTrue(b.tryAcquireRun("w-1:r-1"), "第 10 次（窗满前最后一次）放行");
        assertFalse(b.tryAcquireRun("w-1:r-1"), "第 11 次拒绝");
        assertFalse(b.tryAcquireRun("w-1:r-1"), "第 12 次仍拒绝（同窗）");
    }

    @Test
    void runsPerSecond_newWindowResets() {
        ScriptBudget b = budget(16, 2, 8);
        assertTrue(b.tryAcquireRun("k"));
        assertTrue(b.tryAcquireRun("k"));
        assertFalse(b.tryAcquireRun("k"), "窗满拒绝");
        clock.addAndGet(999L);
        assertFalse(b.tryAcquireRun("k"), "999ms 仍在同窗内");
        clock.addAndGet(1L); // 距窗起点恰 1000ms → 新窗
        assertTrue(b.tryAcquireRun("k"), "1s 后新窗放行");
    }

    @Test
    void runsPerSecond_perRuleIsolated() {
        ScriptBudget b = budget(16, 1, 8);
        assertTrue(b.tryAcquireRun("w-1:a"));
        assertFalse(b.tryAcquireRun("w-1:a"));
        assertTrue(b.tryAcquireRun("w-1:b"), "不同规则独立计窗");
        assertTrue(b.tryAcquireRun("w-2:a"), "不同墙同名规则独立计窗");
    }

    @Test
    void runsPerSecond_clockRollback_reopensWindow() {
        ScriptBudget b = budget(16, 1, 8);
        assertTrue(b.tryAcquireRun("k"));
        clock.addAndGet(-5_000L); // 时钟回拨
        assertTrue(b.tryAcquireRun("k"), "回拨重开窗，不永久卡死");
    }

    // ---------- chain depth ----------

    @Test
    void chainDepth_boundary() {
        ScriptBudget b = budget(); // 默认 max 8
        assertFalse(b.chainDepthExceeded(0));
        assertFalse(b.chainDepthExceeded(7), "7 < 8 放行");
        assertTrue(b.chainDepthExceeded(8), "8 ≥ 8 掐断");
        assertTrue(b.chainDepthExceeded(9));
    }

    // ---------- actions/run ----------

    @Test
    void actions_boundary() {
        ScriptBudget b = budget(); // 默认 max 50
        assertFalse(b.actionsExceeded(50), "恰 50 不超");
        assertTrue(b.actionsExceeded(51), "51 超");
    }

    // ---------- K5 audit 限频 ----------

    @Test
    void auditBlock_rateLimited_10sWindow() {
        ScriptBudget b = budget();
        assertTrue(b.shouldAuditBlock("k"), "首条放行");
        assertFalse(b.shouldAuditBlock("k"), "同窗第二条拒");
        clock.addAndGet(9_999L);
        assertFalse(b.shouldAuditBlock("k"), "9.999s 仍在窗内");
        clock.addAndGet(1L);
        assertTrue(b.shouldAuditBlock("k"), "10s 后放行新一条");
    }

    @Test
    void auditBlock_perRuleIsolated() {
        ScriptBudget b = budget();
        assertTrue(b.shouldAuditBlock("a"));
        assertTrue(b.shouldAuditBlock("b"), "不同 ruleKey 独立限频");
    }

    // ---------- 热更 ----------

    @Test
    void applyConfig_hotReload() {
        ScriptBudget b = budget(16, 1, 8);
        assertTrue(b.tryAcquireRun("k"));
        assertFalse(b.tryAcquireRun("k"));
        b.applyConfig(new HikariCanvasConfig.ScriptsConfig(16, 50, 5, 3));
        assertEquals(50, b.maxActionsPerRun());
        assertTrue(b.chainDepthExceeded(3), "新链深上限生效");
        // 同窗内余量按新上限放行（count=1 < 5）
        assertTrue(b.tryAcquireRun("k"), "热更后同窗按新上限继续计数");
    }
}
