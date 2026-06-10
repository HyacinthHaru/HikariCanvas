-- 0.7.0 P1:墙脚本(视觉运行时)。契约 docs/scripting.md §2。
-- rule_json = ScriptRule 整体 Jackson 序列化(trigger/actions/blockLayout 全部在内,
-- 照 project_json 范式不拆列);坏 blob 加载期跳过 + SEVERE log,不拖垮整墙。
-- enabled 双写:列是查询真相,load 时以列值覆写 rule_json 内的值,避免两处漂移。
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
