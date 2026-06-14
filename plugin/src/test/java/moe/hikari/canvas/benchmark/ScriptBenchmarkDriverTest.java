package moe.hikari.canvas.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ScriptBenchmarkDriver smoke test（不实际测性能，只验驱动逻辑不崩溃、输出格式合理）。
 *
 * <p>刻意用极少轮数（1 预热 + 2 测量）以保持测试快速（<1s）。</p>
 */
class ScriptBenchmarkDriverTest {

    @Test
    void run_singleActionCount_producesNonEmptySummary() {
        String summary = ScriptBenchmarkDriver.run(List.of(1), 1, 2);

        assertNotNull(summary);
        assertFalse(summary.isBlank(), "摘要不应为空");
        assertTrue(summary.contains("动作数"), "摘要应包含表头「动作数」");
        assertTrue(summary.contains("p50"), "摘要应包含 p50 列标题");
    }

    @Test
    void run_multipleActionCounts_allRowsPresent() {
        List<Integer> counts = List.of(1, 10);
        String summary = ScriptBenchmarkDriver.run(counts, 1, 2);

        for (int n : counts) {
            assertTrue(summary.contains(String.valueOf(n)),
                    "摘要应包含 actionCount=" + n + " 的数据行");
        }
    }

    @Test
    void run_summaryContainsKnownLimitations() {
        String summary = ScriptBenchmarkDriver.run(List.of(1), 0, 1);
        assertTrue(summary.contains("已知局限"), "摘要应包含已知局限说明");
    }

    @Test
    void run_p50IsNonNegative() {
        String summary = ScriptBenchmarkDriver.run(List.of(5), 1, 5);
        String[] lines = summary.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.matches("^\\d.*")) {
                String[] cols = trimmed.split("\\s+");
                if (cols.length >= 2) {
                    double p50 = Double.parseDouble(cols[1]);
                    assertTrue(p50 >= 0, "p50 应 >= 0，实际=" + p50);
                }
                break;
            }
        }
    }

    @Test
    void run_nearMaxActions_doesNotThrow() {
        // 逼近默认 max-actions-per-run=50（budget 设为 actionCount+1=51，不应被熔断）
        String summary = ScriptBenchmarkDriver.run(List.of(49), 1, 2);
        assertNotNull(summary);
        assertTrue(summary.contains("49"), "摘要应包含 actionCount=49");
    }
}
