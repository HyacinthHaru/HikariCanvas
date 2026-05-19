/**
 * 列车 / 公交时刻表前端类型（0.4.0-P3-L）。
 *
 * <p>与后端 {@code moe.hikari.canvas.schedule.WallSchedule / ScheduleEntry} record 对齐，
 * 字段一一映射。仅 modal / ScheduleStore 使用——schedule 不影响 ProjectState，不进 protocol.ts。</p>
 *
 * @see /Users/haru/.../docs/protocol.md §5.12
 */

/** 单条时刻表 entry。`id < 0` 表示草稿尚未提交。 */
export interface ScheduleEntry {
    id: number;
    departureTime: string;       // "HH:mm" 24h
    destination?: string | null;
    sortOrder: number;
}

/** 一面 wall 的完整时刻表。 */
export interface WallSchedule {
    wallId: string;
    stationName?: string | null;
    updatedAt: number;
    entries: ScheduleEntry[];
}

// ── WS op ack payloads ───────────────────────────────────────

/** {@code schedule.list} ack。null = wall_schedules 元数据行不存在。 */
export interface ScheduleListAck {
    schedule: WallSchedule | null;
}

/** {@code schedule.upsert} ack。 */
export interface ScheduleUpsertAck {
    stationName: string | null;
}

/** {@code schedule.entry.add / update} ack。 */
export interface ScheduleEntryAck {
    id: number;
    wallId: string;
    departureTime: string;
    destination: string | null;
    sortOrder: number;
}

/** {@code schedule.entry.delete} ack。 */
export interface ScheduleEntryDeleteAck {
    id: number;
}
