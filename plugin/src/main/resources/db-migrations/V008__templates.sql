-- M14 创意工坊：模板元数据表。
-- YAML spec 真相仍在 user-templates/<uuid>/<slug>.yml；本表只存元数据（owner / featured /
-- 排序用），让 registry 扫起来快 + curation/统计简单。
--
-- builtin=1 时 owner_uuid/owner_name 为 NULL；yaml_path 是 jar 内 classpath 路径
-- (templates/<id>.yml)。builtin 行启动期同步写入；运行期不可改 featured。
--
-- 详见 docs/architecture.md 3.8（待补）、docs/data-model.md 2.6.6（待补）。
--
-- 注意：MigrationRunner 按行 split 注释；只支持整行 -- 注释，不能行内 --。

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
