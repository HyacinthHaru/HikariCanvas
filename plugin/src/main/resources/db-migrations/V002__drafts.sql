-- 草稿持久化（M5-D6）：同一墙面的编辑内容在服务器重启 / session 结束后仍保留。
-- 下次对该墙面打开编辑器时恢复 ProjectState。
-- 设计见 docs/data-model.md（后补）。commit（发布为 sign_record）留到 M7。

CREATE TABLE drafts (
    world            TEXT    NOT NULL,
    origin_x         INTEGER NOT NULL,
    origin_y         INTEGER NOT NULL,
    origin_z         INTEGER NOT NULL,
    facing           TEXT    NOT NULL,
    project_json     TEXT    NOT NULL,
    updated_at       INTEGER NOT NULL,
    PRIMARY KEY (world, origin_x, origin_y, origin_z, facing)
);

CREATE INDEX idx_drafts_updated ON drafts(updated_at);
