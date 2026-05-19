-- 0.4.0 P1：用户变量持久化（Tier 1 数据源）。详见 docs/dynamic-data.md §8.1 + docs/data-model.md §2.8。
--
-- 仅持久化 user/* namespace 的玩家手动变量；插件 / 系统 / PAPI 变量内存态重启不留。
-- 主键 = (wall_id, name)：user 变量是 per-wall scope（玩家在每个 wall 里独立管理自己的变量）。
-- 内存中 VariableStore 用 "user:<wallId>/<name>" 作 fullName，对外 placeholder 写
-- ${var:user/<name>} 时由 Compositor 注入当前 wall_id。
--
-- 级联删除：wall 删除时自动清掉它名下所有 user_variables。SQLite 需 PRAGMA foreign_keys=ON
-- （Database.java 已配 foreign_keys=true）。

CREATE TABLE IF NOT EXISTS user_variables (
    wall_id        TEXT    NOT NULL,
    name           TEXT    NOT NULL,
    type           TEXT    NOT NULL,           -- 'STRING' / 'NUMBER' / 'BOOLEAN' / 'COLOR'
    default_value  TEXT,                       -- 可空：fallback when push 失效 / 用户没设值
    current_value  TEXT,                       -- 当前值（手动设 / 插件 push 后写回）
    bound_to       TEXT,                       -- 绑定到的插件 namespace；NULL = 手动管理
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL,
    PRIMARY KEY (wall_id, name),
    FOREIGN KEY (wall_id) REFERENCES walls(wall_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_uvar_wall ON user_variables(wall_id);
CREATE INDEX IF NOT EXISTS idx_uvar_bound ON user_variables(bound_to) WHERE bound_to IS NOT NULL;
