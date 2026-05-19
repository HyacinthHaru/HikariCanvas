-- 0.4.0 P3-L：兜底列车 Schedule 持久化（per-wall 时刻表）。详见 docs/dynamic-data.md §7.3。
--
-- 一 wall 一份 schedule 元数据 + N 条 entry。ManualScheduleProvider 启动期 loadAll 拉所有
-- schedule，按 wall 注册 4 个 schedule:<wallId>/* 变量（next_departure / next_destination /
-- eta_minutes / is_arriving）。
--
-- entries.departure_time 用 "HH:mm" 24h 字符串存储（不存 epoch / Date 类型）——MC 服器时钟
-- 漂移 + 跨日处理在 Provider 计算 next_* 时统一应用。
--
-- 级联删除：wall 删除时自动清掉 schedule 元数据 + entries。FK CASCADE 依赖
-- Database.java 已配的 PRAGMA foreign_keys=ON。

CREATE TABLE IF NOT EXISTS wall_schedules (
    wall_id        TEXT    PRIMARY KEY,
    station_name   TEXT,                       -- 玩家命名站点（可空，编辑器可暴露给变量但 P3-L 不强制）
    updated_at     INTEGER NOT NULL,
    FOREIGN KEY (wall_id) REFERENCES walls(wall_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS schedule_entries (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    wall_id         TEXT    NOT NULL,
    departure_time  TEXT    NOT NULL,          -- "HH:mm" 24h
    destination     TEXT,                       -- 终点站名（可空）
    sort_order      INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (wall_id) REFERENCES walls(wall_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_schedule_entries_wall ON schedule_entries(wall_id);
