package moe.hikari.canvas.schedule;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 一面 wall 的时刻表完整视图——元数据 + entries 列表。
 *
 * <p>{@link moe.hikari.canvas.storage.ScheduleDao#loadByWall} 一次 join 拿到。Provider /
 * 编辑器 modal 都用此结构。entries 已按 {@code sort_order ASC, departure_time ASC} 预排好。</p>
 *
 * <p>{@code precision} 字段（{@code "minute"} / {@code "second"}）控制 ManualScheduleProvider
 * 刷新频率 + departure_time 接受的格式。schema V013 引入，现有 wall 默认 {@code "minute"}
 * （无 migration data loss）。</p>
 *
 * @param wallId       所属 wall
 * @param stationName  站名（可空，玩家命名）
 * @param updatedAt    wall_schedules.updated_at（ms epoch）
 * @param precision    时间精度：{@code "minute"}（30s 刷新 / HH:mm） / {@code "second"}（1s 刷新 /
 *                     HH:mm[:ss]）。null 视同 minute 默认值
 * @param entries      时刻表条目（≥ 0 个，已排序）
 */
public record WallSchedule(
        String wallId,
        @Nullable String stationName,
        long updatedAt,
        String precision,
        List<ScheduleEntry> entries
) {
    /** 默认精度常量。 */
    public static final String PRECISION_MINUTE = "minute";
    public static final String PRECISION_SECOND = "second";

    /** 规范化 precision 输入：null / 非法值 → "minute"；大小写不敏感。 */
    public static String normalizePrecision(@Nullable String raw) {
        if (raw == null) return PRECISION_MINUTE;
        String lower = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (PRECISION_SECOND.equals(lower)) return PRECISION_SECOND;
        return PRECISION_MINUTE;
    }
}
