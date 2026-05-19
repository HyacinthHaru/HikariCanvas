package moe.hikari.canvas.schedule;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 0.4.0-P3-L：一面 wall 的时刻表完整视图——元数据 + entries 列表。
 *
 * <p>{@link moe.hikari.canvas.storage.ScheduleDao#loadByWall} 一次 join 拿到。Provider /
 * 编辑器 modal 都用此结构。entries 已按 {@code sort_order ASC, departure_time ASC} 预排好。</p>
 *
 * @param wallId       所属 wall
 * @param stationName  站名（可空，玩家命名）
 * @param updatedAt    wall_schedules.updated_at（ms epoch）
 * @param entries      时刻表条目（≥ 0 个，已排序）
 */
public record WallSchedule(
        String wallId,
        @Nullable String stationName,
        long updatedAt,
        List<ScheduleEntry> entries
) {}
