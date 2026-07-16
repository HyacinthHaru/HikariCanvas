package moe.hikari.canvas.rail;

import org.jetbrains.annotations.Nullable;

/**
 * 铁路车次（一趟具体的车）。含车次号 / 服务类型 / 编组 / 区间 / 备注。
 *
 * <p>车次时刻表（每站精确时刻）放在 {@link RailTimetableEntry} 表，按 {@link #id}
 * 关联。{@link RailScheduleProvider} 按 wall 绑定的 line + station + direction 查出
 * 下两班车次 + 各自精确到站时刻。</p>
 *
 * <p>schema：{@code db-migrations/V016__rail_network.sql} → {@code rail_runs} 表。
 * {@code UNIQUE (line_id, run_number)} 防同线撞车次号。</p>
 *
 * @param id              车次 id（形如 {@code "run-<8hex>"}）
 * @param lineId          所属线路
 * @param runNumber       车次号（"A01" / "B02"，同线唯一）
 * @param direction       方向：{@code "up"}（站点 sort_order 递增）/ {@code "down"}（递减）
 * @param serviceType     服务类型 DB string（{@code local} / {@code express} /
 *                        {@code section} / {@code limited} 或 admin/owner 自定义）。
 *                        与 {@link ServiceType#dbValue()} 兼容；i18n 显示走
 *                        {@link ServiceType#displayText}。
 * @param cars            编组节数；可空表示未指定
 * @param startStationId  区间起点；null = 线路首站
 * @param endStationId    区间终点；null = 线路末站
 * @param notes           备注（"末班车" / "节假日加开"）；可空
 * @param createdAt       epoch ms
 * @param updatedAt       epoch ms
 */
public record RailRun(
        String id,
        String lineId,
        String runNumber,
        String direction,
        String serviceType,
        @Nullable Integer cars,
        @Nullable String startStationId,
        @Nullable String endStationId,
        @Nullable String notes,
        long createdAt,
        long updatedAt
) {
    /** 上行 direction 常量。 */
    public static final String DIRECTION_UP = "up";
    /** 下行 direction 常量。 */
    public static final String DIRECTION_DOWN = "down";
}
