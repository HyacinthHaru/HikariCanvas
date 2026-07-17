package ac.haru.hikaricanvas.rail;

import org.jetbrains.annotations.Nullable;

/**
 * 车次时刻表的一行——某车次到达某站的精确到达 / 发车时刻。
 *
 * <p>schema：{@code rail_timetable}，主键 ({@link #runId}, {@link #stationId})。
 * 关键设计：每站时刻**精确从此表读**，不再走"travel_seconds 均匀推算"，
 * 因此支持站间不均、大站快车跳站（{@link #stopsHere} = false）、区间车不到全线。</p>
 *
 * @param runId          所属车次（FK CASCADE）
 * @param stationId      所属站点（FK CASCADE）
 * @param arrivalTime    HH:mm:ss 到达时刻；首站 / 中间不停的过路点可空
 * @param departureTime  HH:mm:ss 发车时刻；末站 / 不停的过路点可空
 * @param stopsHere      true = 此站停靠；false = 大站快车跳过此站（不停车，
 *                       arrival/departure 表示通过时间，UI 可灰显或不显示）
 */
public record RailTimetableEntry(
        String runId,
        String stationId,
        @Nullable String arrivalTime,
        @Nullable String departureTime,
        boolean stopsHere
) {
}
