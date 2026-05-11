-- M5.5 路线修正（2026-04-27）：合并 drafts + sign_records → 单一 walls 表；
-- pool_maps.state 由三态收为两态（FREE/RESERVED）；废止 PERMANENT + commit。
--
-- 详见 docs/architecture.md §3-4、docs/data-model.md §2.3-2.4。
-- 决策：drop + recreate（M5.5 阶段无生产数据，<50 行老数据）。
-- SQLite 不支持 DROP COLUMN，pool_maps 走 recreate；新表沿用 map_id 主键。

DROP TABLE IF EXISTS sign_records;

DROP TABLE IF EXISTS drafts;

DROP INDEX IF EXISTS idx_pool_sign;

DROP INDEX IF EXISTS idx_pool_session;

CREATE TABLE pool_maps_new (
    map_id        INTEGER PRIMARY KEY,
    state         TEXT    NOT NULL,
    reserved_by   TEXT,
    created_at    INTEGER NOT NULL,
    last_used_at  INTEGER NOT NULL,
    world         TEXT
);

INSERT INTO pool_maps_new (map_id, state, reserved_by, created_at, last_used_at, world)
SELECT map_id,
       CASE state
            WHEN 'PERMANENT' THEN 'RESERVED'
            ELSE state
       END,
       reserved_by,
       created_at,
       last_used_at,
       world
FROM pool_maps;

DROP TABLE pool_maps;

ALTER TABLE pool_maps_new RENAME TO pool_maps;

CREATE INDEX idx_pool_state ON pool_maps(state);

CREATE INDEX idx_pool_owner ON pool_maps(reserved_by);

CREATE TABLE walls (
    wall_id       TEXT    PRIMARY KEY,
    world         TEXT    NOT NULL,
    origin_x      INTEGER NOT NULL,
    origin_y      INTEGER NOT NULL,
    origin_z      INTEGER NOT NULL,
    facing        TEXT    NOT NULL,
    width_maps    INTEGER NOT NULL,
    height_maps   INTEGER NOT NULL,
    map_ids       TEXT    NOT NULL,
    project_json  TEXT    NOT NULL,
    owner_uuid    TEXT    NOT NULL,
    owner_name    TEXT    NOT NULL,
    alias         TEXT,
    published_at  INTEGER,
    template_id   TEXT,
    template_version INTEGER,
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL
);

CREATE UNIQUE INDEX idx_walls_location ON walls(world, origin_x, origin_y, origin_z, facing);

CREATE UNIQUE INDEX idx_walls_alias ON walls(alias) WHERE alias IS NOT NULL;

CREATE INDEX idx_walls_owner ON walls(owner_uuid);

CREATE INDEX idx_walls_published ON walls(published_at) WHERE published_at IS NOT NULL;

CREATE INDEX idx_walls_updated ON walls(updated_at DESC);
