/**
 * 0.4.4：铁路网络前端类型镜像（与后端 {@code moe.hikari.canvas.rail.*} record 对齐）。
 *
 * <p>schema 见 {@code db-migrations/V016__rail_network.sql}；协议见
 * {@code docs/dynamic-data.md §18.7}。</p>
 */

/** 铁路线路。 */
export interface RailLine {
    id: string;
    name: string;
    code?: string | null;
    color?: string | null;
    ownerUuid: string;
    ownerName: string;
    createdAt: number;
    updatedAt: number;
}

/** 站点（属于某线路，按 sortOrder 排序）。 */
export interface RailStation {
    id: string;
    lineId: string;
    name: string;
    code?: string | null;
    sortOrder: number;
    isTerminus: boolean;
    createdAt: number;
}

/** 车次方向。 */
export type RailDirection = 'up' | 'down';

/**
 * 内置 4 服务类型；自定义字符串（如 "通勤特急"）也允许。
 * UI 显示时走 i18n（next_service_type_text 变量直接是友好文本）。
 */
export type ServiceTypeEnum = 'local' | 'express' | 'section' | 'limited';
export const SERVICE_TYPE_ENUMS: ReadonlyArray<ServiceTypeEnum> =
    ['local', 'express', 'section', 'limited'];

/** 车次（一趟具体的车）。 */
export interface RailRun {
    id: string;
    lineId: string;
    runNumber: string;
    direction: RailDirection;
    /** 4 内置 enum 或自定义字符串。 */
    serviceType: string;
    cars?: number | null;
    startStationId?: string | null;
    endStationId?: string | null;
    notes?: string | null;
    createdAt: number;
    updatedAt: number;
}

/** 车次时刻表的一行（精确到秒）。 */
export interface RailTimetableEntry {
    runId: string;
    stationId: string;
    /** HH:mm:ss；首站可空。 */
    arrival?: string | null;
    /** HH:mm:ss；末站可空。 */
    departure?: string | null;
    /** false = 大站快车跳过此站。 */
    stopsHere: boolean;
}

/** wall → 铁路绑定。 */
export interface WallRailBinding {
    wallId: string;
    lineId?: string | null;
    stationId?: string | null;
    /** "up" / "down" / "both"；null 视同 "both"。 */
    direction?: string | null;
    updatedAt: number;
}

/** rail.run.create 的自动生成 timetable 选项。 */
export interface AutoTimetableOptions {
    /** HH:mm 或 HH:mm:ss。 */
    firstDeparture: string;
    travelSeconds: number;
    dwellSeconds: number;
    skipStationIds?: string[];
}
