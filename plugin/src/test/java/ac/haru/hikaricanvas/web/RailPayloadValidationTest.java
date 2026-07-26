package ac.haru.hikaricanvas.web;

import ac.haru.hikaricanvas.rail.RailTimetableEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code rail.run.*} / {@code rail.timetable.set} 的 payload 校验守卫。
 *
 * <h2>为什么要有这条</h2>
 *
 * <p>① {@code rail.run.update} 当初照抄了 create 的骨架，却少了三项校验（direction 白名单、
 * runNumber / serviceType 长度）。direction 一旦被写成 {@code "up "} / {@code "UP"} / 中文，
 * {@code RailDao.listStationStops} 的 {@code AND r.direction = :dir} 就永远命中不了——
 * 这趟车会从<b>所有站牌上静默消失</b>，界面什么都不提示。create 与 update 现在共用同一个
 * 校验函数，两边不可能再分叉。</p>
 *
 * <p>② 时刻表整表写入过去对"不是对象的行 / 缺 stationId 的行"直接 {@code continue} 丢掉，
 * ack 还照样回"写了 N 行"；重复站则要等撞主键、整事务回滚，只回一句 DB_FAILED，
 * 谁都猜不到真实原因。现在两类都在解析期显式报错。</p>
 */
class RailPayloadValidationTest {

    // ──────────────────────────────────────────────────────────
    //  车次字段（create / update 共用）
    // ──────────────────────────────────────────────────────────

    @Test
    void direction_onlyUpOrDown() {
        assertNull(RailOpDispatcher.validateRunFields("1001", "up", "local", null, null));
        assertNull(RailOpDispatcher.validateRunFields("1001", "down", "local", null, null));
        for (String bad : List.of("UP", "up ", "上行", "left", "")) {
            String err = RailOpDispatcher.validateRunFields("1001", bad, "local", null, null);
            assertNotNull(err, "非法 direction 必须拒: " + bad);
            assertTrue(err.contains("direction"), err);
        }
    }

    /** update 语义：不传的字段 = 不改，跳过校验。 */
    @Test
    void nullFieldsMeanUnchanged() {
        assertNull(RailOpDispatcher.validateRunFields(null, null, null, null, null));
    }

    @Test
    void lengthAndRangeLimits() {
        String long65 = "x".repeat(65);
        assertNotNull(RailOpDispatcher.validateRunFields(long65, null, null, null, null));
        assertNotNull(RailOpDispatcher.validateRunFields(null, null, long65, null, null));
        assertNotNull(RailOpDispatcher.validateRunFields(null, null, null, 0, null));
        assertNotNull(RailOpDispatcher.validateRunFields(null, null, null, 33, null));
        assertNotNull(RailOpDispatcher.validateRunFields(null, null, null, null, "y".repeat(257)));
        assertNull(RailOpDispatcher.validateRunFields("x".repeat(64), "up", "y".repeat(64),
                32, "z".repeat(256)));
    }

    // ──────────────────────────────────────────────────────────
    //  时刻表整表写入
    // ──────────────────────────────────────────────────────────

    @Test
    void timetable_validRowsParsed() {
        var parse = RailOpDispatcher.parseTimetableEntries("run-1", List.of(
                Map.of("stationId", "st-1", "departure", "08:00"),
                Map.of("stationId", "st-2", "arrival", "08:10:30", "stopsHere", false)));

        assertNull(parse.error());
        assertEquals(2, parse.entries().size());
        RailTimetableEntry first = parse.entries().get(0);
        assertEquals("run-1", first.runId());
        assertEquals("st-1", first.stationId());
        assertEquals("08:00:00", first.departureTime(), "HH:mm 应补秒");
        assertTrue(first.stopsHere());
        assertEquals(false, parse.entries().get(1).stopsHere());
    }

    @Test
    void timetable_malformedRowIsRejectedNotDropped() {
        var parse = RailOpDispatcher.parseTimetableEntries("run-1", List.of(
                Map.of("stationId", "st-1", "departure", "08:00"),
                "我不是对象"));

        assertNotNull(parse.error(), "非法行必须报错而不是被悄悄丢掉");
        assertTrue(parse.error().contains("entries[1]"), parse.error());
        assertTrue(parse.entries().isEmpty());
    }

    @Test
    void timetable_missingStationIdIsRejected() {
        var parse = RailOpDispatcher.parseTimetableEntries("run-1", List.of(
                Map.of("departure", "08:00")));

        assertNotNull(parse.error());
        assertTrue(parse.error().contains("stationId"), parse.error());
    }

    /** 重复站要在解析期点名，别等撞主键回滚后只回一句 DB_FAILED。 */
    @Test
    void timetable_duplicateStationIsNamedUpFront() {
        var parse = RailOpDispatcher.parseTimetableEntries("run-1", List.of(
                Map.of("stationId", "st-1", "departure", "08:00"),
                Map.of("stationId", "st-1", "departure", "09:00")));

        assertNotNull(parse.error());
        assertTrue(parse.error().contains("duplicate"), parse.error());
        assertTrue(parse.error().contains("st-1"), parse.error());
    }

    @Test
    void timetable_badTimeFormatRejected() {
        var parse = RailOpDispatcher.parseTimetableEntries("run-1", List.of(
                Map.of("stationId", "st-1", "departure", "8点半")));

        assertNotNull(parse.error());
        assertTrue(parse.error().contains("departure"), parse.error());
    }
}
