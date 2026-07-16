package moe.hikari.canvas.schedule;

import org.jetbrains.annotations.Nullable;

/**
 * 列车 / 公交时刻表一条记录。schema 见
 * {@code db-migrations/V012__wall_schedules.sql}。
 *
 * @param id             自增主键；新建 entry 时调用方传 0，{@link moe.hikari.canvas.storage.ScheduleDao#insertEntry}
 *                       返回真实 id
 * @param wallId         所属 wall（{@code w-<8hex>}）
 * @param departureTime  "HH:mm" 24h 字符串（{@code 08:30}）；Provider 解析为 LocalTime 计算 next_*
 * @param destination    终点站名（可空，编辑器允许空 entry 表示运行间隔）
 * @param sortOrder      同时刻表内排序权重（小在前）；多 entry 同 departureTime 时用 sortOrder 决胜负
 */
public record ScheduleEntry(
        long id,
        String wallId,
        String departureTime,
        @Nullable String destination,
        int sortOrder
) {}
