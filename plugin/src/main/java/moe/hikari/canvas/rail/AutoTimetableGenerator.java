package moe.hikari.canvas.rail;

import org.jetbrains.annotations.Nullable;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 车次时刻表自动生成 helper（{@code docs/dynamic-data.md §18.5}）。
 *
 * <p>输入"首站发车时间 + 站间均匀秒数 + 停靠时长 + 跳过站集合 + 区间起止"，
 * 输出该车次每站的 arrival/departure/stops_here 列表。用户在前端"创建车次"弹
 * 自动生成对话框 → 此 helper 生成 rows → 预览后可逐站手调（{@code rail.run.timetable.set}
 * 走 {@link moe.hikari.canvas.storage.RailDao#replaceTimetable} 批量覆盖）。</p>
 *
 * <p>纯函数，不依赖 DB / Bukkit，单测覆盖 8+ case（首末站时间 / 跳站 / 区间车起止 / 边界）。</p>
 */
public final class AutoTimetableGenerator {

    /**
     * 自动生成的输入参数。
     *
     * @param firstDeparture        首站发车时间（区间车 = 区间起点发车时间）
     * @param travelSeconds         站间均匀时长（秒，含跳过的站）
     * @param dwellSeconds          每站停靠时长（秒；跳站不停 = 0）
     * @param skipStationIds        大站快车跳过的 station id 集合（不停车）；空 = 站站停
     * @param startStationId        区间起点 station id；null = 从线路首站发车
     * @param endStationId          区间终点 station id；null = 跑到线路末站
     */
    public record GenerateOptions(
            LocalTime firstDeparture,
            int travelSeconds,
            int dwellSeconds,
            @Nullable Set<String> skipStationIds,
            @Nullable String startStationId,
            @Nullable String endStationId
    ) {
        public GenerateOptions {
            if (firstDeparture == null) {
                throw new IllegalArgumentException("firstDeparture must not be null");
            }
            if (travelSeconds < 0) {
                throw new IllegalArgumentException("travelSeconds must be >= 0");
            }
            if (dwellSeconds < 0) {
                throw new IllegalArgumentException("dwellSeconds must be >= 0");
            }
        }
    }

    private AutoTimetableGenerator() {}

    /**
     * 按线路完整站点列表（按 direction 已排好序）+ 选项生成 timetable。
     *
     * <p>算法：</p>
     * <ol>
     *   <li>按 direction 把 stations 列表预排序（up = sort_order 递增；down = 递减）</li>
     *   <li>截取区间起止之间的子段（含起止）</li>
     *   <li>从首站发车时间起，对每站：
     *     <ul>
     *       <li>跳站（在 skipStationIds 内）：arrival = 当前 t，departure = 当前 t（不停），
     *           stops_here = false；累加 travelSeconds 走到下站</li>
     *       <li>正常停靠：arrival = 当前 t，departure = 当前 t + dwellSeconds，
     *           stops_here = true；累加 dwellSeconds + travelSeconds 走到下站</li>
     *     </ul>
     *   </li>
     *   <li>首站特殊：arrival = null（没"到达"），departure = firstDeparture</li>
     *   <li>末站特殊：departure = null（终点不再发车）</li>
     * </ol>
     *
     * @param runId         车次 id（写入每行）
     * @param sortedStations 线路完整站点列表，**已按 direction 排好序**（caller 责任）
     * @param opts          生成参数
     * @return 每个传入站点对应一行 {@link RailTimetableEntry}（保留同样顺序）
     */
    public static List<RailTimetableEntry> generate(
            String runId,
            List<RailStation> sortedStations,
            GenerateOptions opts) {
        if (runId == null || runId.isEmpty()) {
            throw new IllegalArgumentException("runId required");
        }
        if (sortedStations == null || sortedStations.isEmpty()) {
            return List.of();
        }

        // 截取区间起止之间的子段
        List<RailStation> segment = sliceSegment(sortedStations,
                opts.startStationId(), opts.endStationId());
        if (segment.isEmpty()) return List.of();

        Set<String> skips = opts.skipStationIds() == null
                ? Set.of() : new HashSet<>(opts.skipStationIds());

        List<RailTimetableEntry> out = new ArrayList<>(segment.size());
        LocalTime cursor = opts.firstDeparture();

        for (int i = 0; i < segment.size(); i++) {
            RailStation st = segment.get(i);
            boolean isFirst = i == 0;
            boolean isLast = i == segment.size() - 1;
            boolean skip = skips.contains(st.id());

            String arrival = null;
            String departure = null;
            boolean stopsHere;

            if (skip) {
                // 跳站：通过时刻仍记，方便 UI 灰显"通过 HH:mm:ss"；stops_here = false
                stopsHere = false;
                if (!isFirst) arrival = format(cursor);
                if (!isLast) departure = format(cursor);  // 不停车 → 同 arrival
                // 走到下站：仅累 travelSeconds（无停靠）
                cursor = cursor.plusSeconds(opts.travelSeconds());
            } else {
                stopsHere = true;
                if (isFirst) {
                    // 首站：无 arrival，departure = firstDeparture
                    departure = format(cursor);
                    cursor = cursor.plusSeconds(opts.travelSeconds());
                } else if (isLast) {
                    // 末站：arrival = 当前，departure = null
                    arrival = format(cursor);
                    // 末站后不再前进
                } else {
                    arrival = format(cursor);
                    LocalTime dep = cursor.plusSeconds(opts.dwellSeconds());
                    departure = format(dep);
                    cursor = dep.plusSeconds(opts.travelSeconds());
                }
            }

            out.add(new RailTimetableEntry(runId, st.id(), arrival, departure, stopsHere));
        }
        return out;
    }

    /**
     * 按 startStationId / endStationId 切区间。任一为 null 走线路首站 / 末站；
     * 找不到匹配 id（数据漂）时返完整 list（不静默丢段）。
     */
    private static List<RailStation> sliceSegment(
            List<RailStation> sorted,
            @Nullable String startId, @Nullable String endId) {
        int startIdx = -1, endIdx = -1;
        if (startId != null) {
            for (int i = 0; i < sorted.size(); i++) {
                if (startId.equals(sorted.get(i).id())) { startIdx = i; break; }
            }
        }
        if (endId != null) {
            for (int i = 0; i < sorted.size(); i++) {
                if (endId.equals(sorted.get(i).id())) { endIdx = i; break; }
            }
        }
        if (startIdx < 0) startIdx = 0;
        if (endIdx < 0) endIdx = sorted.size() - 1;
        if (startIdx > endIdx) {
            // 数据异常：起站在止站之后 → 返空（caller 应在 UI 层先校验，这里 defensive）
            return List.of();
        }
        return Collections.unmodifiableList(
                new ArrayList<>(sorted.subList(startIdx, endIdx + 1)));
    }

    /** HH:mm:ss 格式化（24 小时；与 schema timetable 字段约定一致）。 */
    private static String format(LocalTime t) {
        return String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
    }
}
