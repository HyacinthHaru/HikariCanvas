-- 模板元数据表（详见 docs/template-pack.md 与 docs/data-model.md §2.6）。
-- 模板包归档存于磁盘 / jar 资源（历史列名 yaml_path 现保存 .canvas 模板包路径）；
-- 本表保存元数据（owner / featured / 排序）供注册表索引与筛选。
-- builtin=1 时 owner_uuid / owner_name 为 NULL，启动期同步写入。

CREATE TABLE templates (
    template_id     TEXT    PRIMARY KEY,
    owner_uuid      TEXT,
    owner_name      TEXT,
    display_name    TEXT    NOT NULL,
    description     TEXT,
    yaml_path       TEXT    NOT NULL,
    builtin         INTEGER NOT NULL DEFAULT 0,
    featured        INTEGER NOT NULL DEFAULT 0,
    download_count  INTEGER NOT NULL DEFAULT 0,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);

CREATE INDEX idx_templates_owner ON templates(owner_uuid, created_at DESC);

CREATE INDEX idx_templates_marketplace ON templates(featured DESC, created_at DESC);

CREATE UNIQUE INDEX idx_templates_yaml_path ON templates(yaml_path);
