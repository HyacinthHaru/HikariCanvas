-- M8-B（2026-05-13）：给 walls 行加 protocol_version 列，标记 project_json 的形态。
--
-- 1 = v1 扁平 elements（M5.5~M7 期间写入）
-- 2 = v2 layered（M8 起）
--
-- 已有行因 DEFAULT 1 自动落到 v1；启动期 WallRepo.migrateAllToV2 扫表把 v1 行
-- 反序列化（ProjectState.JsonCreator 自动包成 Default Layer）+ 重新序列化回写，
-- 同时把本列 UPDATE 到 2。详见 docs/data-model.md §2.4.1。
--
-- SQLite 不支持 ALTER TABLE ADD COLUMN ... CHECK，所以不加显式范围校验；
-- 值约定靠插件代码保证。

ALTER TABLE walls ADD COLUMN protocol_version INTEGER NOT NULL DEFAULT 1;

CREATE INDEX idx_walls_protocol_version ON walls(protocol_version);
