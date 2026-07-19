-- 图片上传配额表。
-- 按 sha256[:16] 内容寻址；磁盘上对应 plugins/HikariCanvas/uploads/<hash>.png；
-- 多个 ImageElement.source 可指同一 hash（跨 wall 引用零重复存储）。
--
-- 详见 docs/data-model.md §2.6.5、docs/architecture.md §3.7。
--
-- v1 简化：refcount 字段保留以与文档/前向兼容，但 v1 不做实时增减；LRU 清理时实时
-- sweep 所有 walls.project_json 收集仍被引用的 hash 集合做剔除。

CREATE TABLE image_uploads (
    hash            TEXT    PRIMARY KEY,
    bytes           INTEGER NOT NULL,
    width           INTEGER NOT NULL,
    height          INTEGER NOT NULL,
    mime            TEXT    NOT NULL,
    uploader_uuid   TEXT    NOT NULL,
    uploaded_at     INTEGER NOT NULL,
    last_used_at    INTEGER NOT NULL,
    refcount        INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_uploads_uploader ON image_uploads(uploader_uuid, uploaded_at DESC);

CREATE INDEX idx_uploads_lru ON image_uploads(last_used_at);
