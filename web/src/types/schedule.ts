/**
 * 列车 / 公交时刻表前端类型。
 *
 * <p>与后端 {@code ac.haru.hikaricanvas.schedule.WallSchedule / ScheduleEntry} record 对齐，
 * 字段一一映射。仅 modal / ScheduleStore 使用——schedule 不影响 ProjectState，不进 protocol.ts。</p>
 *
 * <p>precision 字段（"minute" / "second"）控制时间精度。</p>
 *
 * @see docs/protocol.md §5.12
 */

/** 时间精度。{@code "minute"} → departureTime 为 HH:mm，刷新 30s 一次；{@code "second"} → 可为 HH:mm:ss，1s 刷新。 */
export type SchedulePrecision = 'minute' | 'second';

/** 单条时刻表 entry。`id < 0` 表示草稿尚未提交。departureTime 是 HH:mm 或 HH:mm:ss（按 wall precision）。 */
export interface ScheduleEntry {
    id: number;
    departureTime: string;       // "HH:mm" 或 "HH:mm:ss"（24h）
    destination?: string | null;
    sortOrder: number;
}

/** 一面 wall 的完整时刻表。 */
export interface WallSchedule {
    wallId: string;
    stationName?: string | null;
    updatedAt: number;
    precision?: SchedulePrecision;  // 缺省视同 "minute"
    entries: ScheduleEntry[];
}

// ── WS op ack payloads ───────────────────────────────────────

/** {@code schedule.list} ack。null = wall_schedules 元数据行不存在。 */
export interface ScheduleListAck {
    schedule: WallSchedule | null;
}

/** {@code schedule.upsert} ack。携带 precision。 */
export interface ScheduleUpsertAck {
    stationName: string | null;
    precision?: SchedulePrecision;
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
