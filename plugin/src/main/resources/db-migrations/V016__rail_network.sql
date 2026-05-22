-- 0.4.4 P1：铁路网络 schema。详见 docs/dynamic-data.md §18。
--
-- 5 张表 + FK 级联：
--   rail_lines        线路（owner / 颜色 / 短代号）
--   rail_stations     站点（含 sort_order + is_terminus）
--   rail_runs         ★车次（run_number + service_type + cars + 区间 + notes）
--   rail_timetable    ★每车次每站精确到秒的到/发时刻（支持跳站、不均站距、区间车）
--   wall_rail_bindings  wall → 线路 + 本站 + 方向
--
-- 与 0.4.0 V012 wall_schedules 共存：wall_rail_bindings.line_id IS NULL 时，
-- RailScheduleProvider 走 fallback 到旧 ManualScheduleProvider（per-wall 时刻表）。
--
-- 关键设计：rail_timetable 每行存"该车次到该站"的精确时刻（不是按 travel_seconds
-- 均匀推算）。支持：站间不均、大站快车跳站（stops_here=0）、区间车不到全线。
--
-- service_type：4 内置 enum 值（local / express / section / limited）+
-- 自定义字符串兜底（admin / owner 设的任意值）。i18n 由 RailScheduleProvider
-- 按 owner locale 查 next_service_type_text 时回填。
--
-- 级联：
--   * walls 删除 → wall_rail_bindings 删（FK CASCADE）
--   * rail_lines 删除 → stations / runs（CASCADE）→ timetable（CASCADE 链式）
--   * 删 station 但保 run（区间车起止 SET NULL）

CREATE TABLE IF NOT EXISTS rail_lines (
    id           TEXT    PRIMARY KEY,    -- 形如 "line-<8hex>" 或玩家命名
    name         TEXT    NOT NULL,
    code         TEXT,                   -- 短代号（"L1" / "M2"）；可空
    color        TEXT,                   -- "#RRGGBB"；可空
    owner_uuid   TEXT    NOT NULL,
    owner_name   TEXT    NOT NULL,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rail_lines_owner ON rail_lines(owner_uuid);

CREATE TABLE IF NOT EXISTS rail_stations (
    id           TEXT    PRIMARY KEY,
    line_id      TEXT    NOT NULL,
    name         TEXT    NOT NULL,
    code         TEXT,
    sort_order   INTEGER NOT NULL,
    is_terminus  INTEGER NOT NULL DEFAULT 0,
    created_at   INTEGER NOT NULL,
    FOREIGN KEY (line_id) REFERENCES rail_lines(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_rail_stations_line ON rail_stations(line_id);

CREATE TABLE IF NOT EXISTS rail_runs (
    id                TEXT    PRIMARY KEY,
    line_id           TEXT    NOT NULL,
    run_number        TEXT    NOT NULL,
    direction         TEXT    NOT NULL,    -- "up" / "down"
    service_type      TEXT    NOT NULL DEFAULT 'local',  -- 4 内置 + 自定义字符串
    cars              INTEGER,             -- 编组节数；可空
    start_station_id  TEXT,                -- null = 线路首站
    end_station_id    TEXT,                -- null = 线路末站
    notes             TEXT,
    created_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL,
    UNIQUE (line_id, run_number),
    FOREIGN KEY (line_id) REFERENCES rail_lines(id) ON DELETE CASCADE,
    FOREIGN KEY (start_station_id) REFERENCES rail_stations(id) ON DELETE SET NULL,
    FOREIGN KEY (end_station_id) REFERENCES rail_stations(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_rail_runs_line ON rail_runs(line_id);

CREATE TABLE IF NOT EXISTS rail_timetable (
    run_id          TEXT    NOT NULL,
    station_id      TEXT    NOT NULL,
    arrival_time    TEXT,                  -- HH:mm:ss；首站可空
    departure_time  TEXT,                  -- HH:mm:ss；末站可空
    stops_here      INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (run_id, station_id),
    FOREIGN KEY (run_id) REFERENCES rail_runs(id) ON DELETE CASCADE,
    FOREIGN KEY (station_id) REFERENCES rail_stations(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_rail_timetable_run ON rail_timetable(run_id);
CREATE INDEX IF NOT EXISTS idx_rail_timetable_station ON rail_timetable(station_id);

CREATE TABLE IF NOT EXISTS wall_rail_bindings (
    wall_id      TEXT    PRIMARY KEY,
    line_id      TEXT,                     -- null = 未绑定（fallback ManualSchedule）
    station_id   TEXT,
    direction    TEXT,                     -- "up" / "down" / "both"
    updated_at   INTEGER NOT NULL,
    FOREIGN KEY (wall_id) REFERENCES walls(wall_id) ON DELETE CASCADE,
    FOREIGN KEY (line_id) REFERENCES rail_lines(id) ON DELETE SET NULL,
    FOREIGN KEY (station_id) REFERENCES rail_stations(id) ON DELETE SET NULL
);
