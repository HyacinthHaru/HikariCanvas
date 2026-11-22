-- 全局用户变量持久化。详见 docs/dynamic-data.md §17。
--
-- 与 V011 user_variables（按墙隔离）的区别：
--   - user_variables 主键 (wall_id, name)：变量随 wall 走，删墙时通过外键级联清理
--   - user_global_variables 主键 name（全服唯一）：跨墙共享，删墙不影响全局变量
--
-- 命名空间编码：内存中 VariableStore 用裸字符串 "userglobal" 作 namespace
-- （不含冒号、不带 wallId 后缀），fullName 形如 "userglobal/<name>"。
--
-- 权限控制（详见 docs/dynamic-data.md §17.3）：
--   - 持 canvas.var.global.create 的玩家可创建
--   - 作者修改自己的变量：canvas.var.global.write.own（默认 true）
--   - 修改他人变量：canvas.var.global.write.any（默认 op）
--   - 删除同理：canvas.var.global.delete.own / delete.any
--   - 外部插件禁推：'userglobal' 列入 PluginNamespaceRegistry.RESERVED_NAMESPACES
--
-- 配额：每作者上限 500、全服上限 10000（config.yml 可调）。
--
-- 不 FK 引用 walls 表：全局变量不属于任何 wall。owner 离开服务器后变量永久保留（同
-- user_variables 现状），admin 可手动 /canvas var delete 清。

CREATE TABLE IF NOT EXISTS user_global_variables (
    name           TEXT    PRIMARY KEY,        -- 不含 userglobal/ 前缀；全服唯一
    owner_uuid     TEXT    NOT NULL,           -- 创建者
    owner_name     TEXT    NOT NULL,           -- 创建时玩家名（UI 显示用，不强一致）
    type           TEXT    NOT NULL,           -- 'STRING' / 'NUMBER' / 'BOOLEAN' / 'COLOR'
    default_value  TEXT,                       -- fallback 链中段
    current_value  TEXT,                       -- 当前值
    bound_to       TEXT,                       -- 绑定的插件 namespace（可空）
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ugvar_owner ON user_global_variables(owner_uuid);
CREATE INDEX IF NOT EXISTS idx_ugvar_bound ON user_global_variables(bound_to) WHERE bound_to IS NOT NULL;
