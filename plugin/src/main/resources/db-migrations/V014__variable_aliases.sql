-- 0.4.2：变量别名（per-wall，全 namespace 通用）。详见 docs/variables.md §1.12。
--
-- 每个 wall 给某个 fullName 可起一个别名（短易记名），让 VariablePicker / VariablePanel /
-- VariableChipEditor 展示时优先用 alias 代替原始 fullName。
--
-- 主键 (wall_id, full_name)：同一 wall 内一个变量只一个别名；同一 fullName 在不同 wall
-- 可有不同别名（个性化）。alias 字段不要求 wall 内唯一——允许重复（用户自己负责，UI 显示
-- 警告但不拒）。
--
-- fullName 含完整 namespace（如 user:w-abc/red / schedule:w-abc/eta_seconds /
-- system/server.time）；插件 / 系统 / PAPI / schedule / user 变量均可被起别名。
--
-- 级联删除：wall 删除时自动清掉所有 variable_aliases。SQLite 需 PRAGMA foreign_keys=ON
-- （Database.java 已配 foreign_keys=true）。

CREATE TABLE IF NOT EXISTS variable_aliases (
    wall_id     TEXT    NOT NULL,
    full_name   TEXT    NOT NULL,
    alias       TEXT    NOT NULL,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    PRIMARY KEY (wall_id, full_name),
    FOREIGN KEY (wall_id) REFERENCES walls(wall_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_variable_aliases_wall ON variable_aliases(wall_id);
