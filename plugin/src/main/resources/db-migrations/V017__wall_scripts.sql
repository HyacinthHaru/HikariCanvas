-- 墙脚本持久化表（详见 docs/scripting.md §2）。
-- rule_json 保存 ScriptRule 整体 Jackson 序列化（含 trigger / actions / blockLayout）；
-- 损坏记录在加载期跳过并记录 SEVERE 日志，不阻塞整面墙恢复。
-- enabled 双写：以独立列作为查询权威，加载时以列值覆写 rule_json 内的字段。
CREATE TABLE IF NOT EXISTS wall_scripts (
    id         TEXT    PRIMARY KEY,                -- "sr-<8hex>"
    wall_id    TEXT    NOT NULL REFERENCES walls(wall_id) ON DELETE CASCADE,
    enabled    INTEGER NOT NULL DEFAULT 1,
    name       TEXT    NOT NULL,
    rule_json  TEXT    NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_wall_scripts_wall ON wall_scripts(wall_id);
