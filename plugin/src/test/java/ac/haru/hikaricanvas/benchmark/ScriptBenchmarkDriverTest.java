package ac.haru.hikaricanvas.benchmark;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** ScriptBenchmarkDriver smoke test（不实际测性能，只验驱动逻辑不崩溃、行数/数值合理）。 */
class ScriptBenchmarkDriverTest {

    @Test
    void measure_singleActionCount_producesOneRow() {
        List<BenchRow> rows = ScriptBenchmarkDriver.measure(List.of(1), 1, 2);
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).n());
        assertNotNull(rows.get(0).p());
    }

    @Test
    void measure_multipleActionCounts_allRowsPresent() {
        List<Integer> counts = List.of(1, 10);
        List<BenchRow> rows = ScriptBenchmarkDriver.measure(counts, 1, 2);
        assertEquals(counts, rows.stream().map(BenchRow::n).toList());
    }

    @Test
    void measure_p50IsNonNegative() {
        List<BenchRow> rows = ScriptBenchmarkDriver.measure(List.of(5), 1, 5);
        assertTrue(rows.get(0).p().p50() >= 0, "p50 应 >= 0");
    }

    @Test
    void measure_nearMaxActions_doesNotThrow() {
        // 逼近默认 max-actions-per-run=50（budget 设为 actionCount+1=51，不应被熔断）
        List<BenchRow> rows = ScriptBenchmarkDriver.measure(List.of(49), 1, 2);
        assertEquals(49, rows.get(0).n());
    }
}
