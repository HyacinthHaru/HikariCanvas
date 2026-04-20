-- HikariCanvas SQLite schema v1
-- 见 docs/data-model.md §2
-- 此脚本不含 schema_version 表（Database.java 会先建）

CREATE TABLE pool_maps (
    map_id        INTEGER PRIMARY KEY,
    state         TEXT    NOT NULL,
    reserved_by   TEXT,
    sign_id       TEXT,
    created_at    INTEGER NOT NULL,
    last_used_at  INTEGER NOT NULL,
    world         TEXT
);

CREATE INDEX idx_pool_state   ON pool_maps(state);
CREATE INDEX idx_pool_sign    ON pool_maps(sign_id);
CREATE INDEX idx_pool_session ON pool_maps(reserved_by);

CREATE TABLE sign_records (
    id                TEXT    PRIMARY KEY,
    owner_uuid        TEXT    NOT NULL,
    owner_name        TEXT    NOT NULL,
    world             TEXT    NOT NULL,
    origin_x          INTEGER NOT NULL,
    origin_y          INTEGER NOT NULL,
    origin_z          INTEGER NOT NULL,
    facing            TEXT    NOT NULL,
    width_maps        INTEGER NOT NULL,
    height_maps       INTEGER NOT NULL,
    map_ids           TEXT    NOT NULL,
    project_json      TEXT    NOT NULL,
    template_id       TEXT,
    template_version  INTEGER,
    created_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL,
    deleted_at        INTEGER
);

CREATE INDEX idx_sign_owner      ON sign_records(owner_uuid);
CREATE INDEX idx_sign_world_xyz  ON sign_records(world, origin_x, origin_z);
CREATE INDEX idx_sign_template   ON sign_records(template_id);
CREATE INDEX idx_sign_created    ON sign_records(created_at);

CREATE TABLE audit_log (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    ts           INTEGER NOT NULL,
    event        TEXT    NOT NULL,
    player_uuid  TEXT,
    player_name  TEXT,
    session_id   TEXT,
    ip_hash      TEXT,
    details      TEXT
);

CREATE INDEX idx_audit_ts     ON audit_log(ts);
CREATE INDEX idx_audit_player ON audit_log(player_uuid);
CREATE INDEX idx_audit_event  ON audit_log(event);

CREATE TABLE template_usage (
    template_id   TEXT    NOT NULL,
    player_uuid   TEXT    NOT NULL,
    use_count     INTEGER NOT NULL DEFAULT 0,
    last_used_at  INTEGER NOT NULL,
    PRIMARY KEY (template_id, player_uuid)
);

CREATE INDEX idx_usage_player ON template_usage(player_uuid, last_used_at DESC);
CREATE INDEX idx_usage_global ON template_usage(last_used_at DESC);
