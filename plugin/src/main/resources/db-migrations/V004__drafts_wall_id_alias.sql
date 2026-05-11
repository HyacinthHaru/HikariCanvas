-- M5-D8：每个墙面预留稳定的 wall_id 和可选 alias。未来网页首页用 wall_id 列出"近期项目"，
-- 玩家用 alias 给画起易记的名字（如 "subway-ikebukuro"）。
-- 命令 / 网页 UI 后续添加；此处仅做 schema 预留，让数据从现在开始累积。

ALTER TABLE drafts ADD COLUMN wall_id TEXT;
ALTER TABLE drafts ADD COLUMN alias TEXT;

CREATE UNIQUE INDEX idx_drafts_wall_id ON drafts(wall_id) WHERE wall_id IS NOT NULL;
CREATE INDEX idx_drafts_alias ON drafts(alias) WHERE alias IS NOT NULL;
