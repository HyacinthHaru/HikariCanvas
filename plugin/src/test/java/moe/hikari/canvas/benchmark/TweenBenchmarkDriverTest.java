package moe.hikari.canvas.benchmark;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** TweenBenchmarkDriver smoke test（不实际测性能，只验驱动逻辑不崩溃、行数/数值合理）。 */
class TweenBenchmarkDriverTest {

    @Test
    void measure_singleWall_producesOneRow() {
        List<BenchRow> rows = TweenBenchmarkDriver.measure(List.of(1), 1, 2);
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).n());
        assertNotNull(rows.get(0).p());
    }

    @Test
    void measure_multipleWallCounts_allRowsPresent() {
        List<Integer> wallCounts = List.of(1, 4);
        List<BenchRow> rows = TweenBenchmarkDriver.measure(wallCounts, 1, 2);
        assertEquals(wallCounts, rows.stream().map(BenchRow::n).toList());
    }

    @Test
    void measure_p50IsNonNegative() {
        List<BenchRow> rows = TweenBenchmarkDriver.measure(List.of(1), 1, 5);
        assertTrue(rows.get(0).p().p50() >= 0, "p50 应 >= 0");
    }
}
