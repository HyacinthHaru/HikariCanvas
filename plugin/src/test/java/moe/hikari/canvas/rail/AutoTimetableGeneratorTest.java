package moe.hikari.canvas.rail;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.4 P1：AutoTimetableGenerator 单测（8+ case）。
 */
class AutoTimetableGeneratorTest {

    private static RailStation st(String id, int sort) {
        return new RailStation(id, "line-A", "Stop-" + sort, null, sort, false, 0L);
    }

    @Test
    void generate_basic5Stations() {
        List<RailStation> stations = List.of(st("s1", 0), st("s2", 1), st("s3", 2),
                st("s4", 3), st("s5", 4));
        var opts = new AutoTimetableGenerator.GenerateOptions(
                LocalTime.of(6, 0, 0), 90, 30, null, null, null);
        List<RailTimetableEntry> out = AutoTimetableGenerator.generate("run1", stations, opts);

        assertEquals(5, out.size());
        // 首站：无 arrival，departure = 06:00:00
        assertEquals("run1", out.get(0).runId());
        assertEquals("s1", out.get(0).stationId());
        assertNull(out.get(0).arrivalTime());
        assertEquals("06:00:00", out.get(0).departureTime());
        assertTrue(out.get(0).stopsHere());

        // 第二站：arrival = 06:01:30，departure = 06:02:00
        assertEquals("06:01:30", out.get(1).arrivalTime());
        assertEquals("06:02:00", out.get(1).departureTime());
        // 第三站：arrival = 06:03:30，departure = 06:04:00
        assertEquals("06:03:30", out.get(2).arrivalTime());

        // 末站：arrival 非空，departure null
        assertNotNull(out.get(4).arrivalTime());
        assertNull(out.get(4).departureTime());
    }

    @Test
    void generate_emptyStations_returnsEmpty() {
        var opts = new AutoTimetableGenerator.GenerateOptions(
                LocalTime.MIDNIGHT, 60, 30, null, null, null);
        assertTrue(AutoTimetableGenerator.generate("run1", List.of(), opts).isEmpty());
    }

    @Test
    void generate_singleStation_onlyFirstStop() {
        List<RailStation> stations = List.of(st("s1", 0));
        var opts = new AutoTimetableGenerator.GenerateOptions(
                LocalTime.of(8, 0, 0), 90, 30, null, null, null);
        List<RailTimetableEntry> out = AutoTimetableGenerator.generate("run1", stations, opts);
        assertEquals(1, out.size());
        // 唯一站 = 首站 + 末站；按 first-station 分支：arrival null + departure not null
        assertEquals("s1", out.get(0).stationId());
        assertNull(out.get(0).arrivalTime());
        assertEquals("08:00:00", out.get(0).departureTime());
    }

    @Test
    void generate_expressSkipsMiddle() {
        // 5 站，s3 跳过（大站快车），s2/s4 正常停靠
        List<RailStation> stations = List.of(st("s1", 0), st("s2", 1), st("s3", 2),
                st("s4", 3), st("s5", 4));
        var opts = new AutoTimetableGenerator.GenerateOptions(
                LocalTime.of(6, 0, 0), 60, 30, Set.of("s3"), null, null);
        List<RailTimetableEntry> out = AutoTimetableGenerator.generate("run1", stations, opts);
        assertEquals(5, out.size());
        // s3 stops_here = false（不停车 / 大站快车）
        assertEquals("s3", out.get(2).stationId());
        assertFalse(out.get(2).stopsHere());
        // s4 仍停靠
        assertTrue(out.get(3).stopsHere());
    }

    @Test
    void generate_sectionRun_s2ToS4Only() {
        // 5 站完整列表；区间车从 s2 到 s4，s1 / s5 不在 timetable 内
        List<RailStation> stations = List.of(st("s1", 0), st("s2", 1), st("s3", 2),
                st("s4", 3), st("s5", 4));
        var opts = new AutoTimetableGenerator.GenerateOptions(
                LocalTime.of(6, 0, 0), 60, 30, null, "s2", "s4");
        List<RailTimetableEntry> out = AutoTimetableGenerator.generate("run1", stations, opts);
        // 仅 s2/s3/s4 三站
        assertEquals(3, out.size());
        assertEquals("s2", out.get(0).stationId());
        assertEquals("s3", out.get(1).stationId());
        assertEquals("s4", out.get(2).stationId());
        // s2 是该 run 的首站 = 区间起点：arrival null + departure = 06:00:00
        assertNull(out.get(0).arrivalTime());
        assertEquals("06:00:00", out.get(0).departureTime());
        // s4 是末站：arrival 非空 + departure null
        assertNotNull(out.get(2).arrivalTime());
        assertNull(out.get(2).departureTime());
    }

    @Test
    void generate_invalidStartAfterEnd_returnsEmpty() {
        List<RailStation> stations = List.of(st("s1", 0), st("s2", 1));
        var opts = new AutoTimetableGenerator.GenerateOptions(
                LocalTime.MIDNIGHT, 60, 30, null, "s2", "s1");
        assertTrue(AutoTimetableGenerator.generate("run1", stations, opts).isEmpty());
    }

    @Test
    void generate_unknownStartId_fallsBackToLineStart() {
        // start id 不在 stations 列表 → 走 fallback startIdx=0
        List<RailStation> stations = List.of(st("s1", 0), st("s2", 1), st("s3", 2));
        var opts = new AutoTimetableGenerator.GenerateOptions(
                LocalTime.of(6, 0, 0), 60, 30, null, "unknown", null);
        List<RailTimetableEntry> out = AutoTimetableGenerator.generate("run1", stations, opts);
        assertEquals(3, out.size());
        assertEquals("s1", out.get(0).stationId());  // 没漂走第一站
    }

    @Test
    void generate_validatesOptionsConstructor() {
        assertThrows(IllegalArgumentException.class, () ->
                new AutoTimetableGenerator.GenerateOptions(null, 60, 30, null, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                new AutoTimetableGenerator.GenerateOptions(LocalTime.MIDNIGHT, -1, 30,
                        null, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                new AutoTimetableGenerator.GenerateOptions(LocalTime.MIDNIGHT, 60, -1,
                        null, null, null));
    }

    @Test
    void generate_runIdInjectedToAllEntries() {
        List<RailStation> stations = List.of(st("s1", 0), st("s2", 1));
        var opts = new AutoTimetableGenerator.GenerateOptions(
                LocalTime.MIDNIGHT, 60, 30, null, null, null);
        List<RailTimetableEntry> out = AutoTimetableGenerator.generate("MY-RUN", stations, opts);
        for (RailTimetableEntry e : out) {
            assertEquals("MY-RUN", e.runId());
        }
    }
}
