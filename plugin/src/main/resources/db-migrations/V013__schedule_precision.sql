-- 0.4.0 bugfix（Bug 4）：per-wall schedule 精度（分钟 / 秒）。
--
-- precision='minute'（default，向下兼容现有行）：
--   - departure_time 为 "HH:mm"
--   - eta_minutes 主用；eta_seconds 同步暴露但分辨率 = 60s
--   - ManualScheduleProvider refresh interval = 30s
-- precision='second'：
--   - departure_time 可为 "HH:mm" 或 "HH:mm:ss"
--   - eta_seconds 主用；eta_minutes 仍暴露（向下兼容）
--   - ManualScheduleProvider refresh interval = 1s
--
-- ADD COLUMN ... NOT NULL DEFAULT 'minute' 让现有 wall_schedules 行无缝升级，不需要回填脚本。
-- xerial sqlite-jdbc 3.53 (build.gradle.kts) 原生支持。

ALTER TABLE wall_schedules ADD COLUMN precision TEXT NOT NULL DEFAULT 'minute';
