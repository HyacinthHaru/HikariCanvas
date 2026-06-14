package moe.hikari.canvas.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TweenBenchmarkDriver smoke test（不实际测性能，只验驱动逻辑不崩溃、输出格式合理）。
 *
 * <p>刻意用极少轮数（1 预热 + 2 测量）以保持测试快速（<1s）。</p>
 */
class TweenBenchmarkDriverTest {

    @Test
    void run_singleWall_producesNonEmptySummary() {
        String summary = TweenBenchmarkDriver.run(List.of(1), 1, 2);

        assertNotNull(summary);
        assertFalse(summary.isBlank(), "摘要不应为空");
        assertTrue(summary.contains("活跃墙"), "摘要应包含表头「活跃墙」");
        assertTrue(summary.contains("p50"), "摘要应包含 p50 列标题");
    }

    @Test
    void run_multipleWallCounts_allRowsPresent() {
        List<Integer> wallCounts = List.of(1, 4);
        String summary = TweenBenchmarkDriver.run(wallCounts, 1, 2);

        // 每个 N 值应对应一行数据行
        for (int n : wallCounts) {
            assertTrue(summary.contains(String.valueOf(n)),
                    "摘要应包含 N=" + n + " 的数据行");
        }
    }

    @Test
    void run_summaryContainsKnownLimitations() {
        String summary = TweenBenchmarkDriver.run(List.of(1), 0, 1);
        assertTrue(summary.contains("已知局限"), "摘要应包含已知局限说明");
    }

    @Test
    void run_p50IsNonNegative() {
        String summary = TweenBenchmarkDriver.run(List.of(1), 1, 5);
        // 提取第一个数据行（跳过标题行 + 分隔线）
        String[] lines = summary.split("\n");
        // 找到第一个数字开头的行
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.matches("^\\d.*")) {
                // 解析 p50（第 2 列）
                String[] cols = trimmed.split("\\s+");
                if (cols.length >= 2) {
                    double p50 = Double.parseDouble(cols[1]);
                    assertTrue(p50 >= 0, "p50 应 >= 0，实际=" + p50);
                }
                break;
            }
        }
    }
}
