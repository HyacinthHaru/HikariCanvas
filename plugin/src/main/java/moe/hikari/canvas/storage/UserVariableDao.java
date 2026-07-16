package moe.hikari.canvas.storage;

import moe.hikari.canvas.variable.VarType;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code user_variables} 表 DAO。schema 见
 * {@code db-migrations/V011__user_variables.sql} + {@code docs/data-model.md §2.8}。
 *
 * <p><b>持久化范围</b>：仅 {@code user/*} namespace 的玩家手动变量；插件 / 系统 / PAPI
 * 变量内存态重启不留——这是 Push 模式的自然属性
 * （{@code docs/dynamic-data.md §16-3/§16-4}）。</p>
 *
 * <p><b>per-wall</b>：user 变量主键 = (wall_id, name)；wall 删除时 SQLite FK CASCADE
 * 自动清理。{@link #deleteByWall} 提供显式接口以备业务侧需要立即清（如 {@code /canvas delete}
 * 走业务路径时同步触发，不依赖 FK 触发时机）。</p>
 */
public class UserVariableDao {

    /** user_variables 表一行的内存视图。 */
    public record Row(
            String wallId,
            String name,
            VarType type,
            @Nullable String defaultValue,
            @Nullable String currentValue,
            @Nullable String boundTo,
            long createdAt,
            long updatedAt
    ) {}

    private final Logger log;
    private final Jdbi jdbi;

    public UserVariableDao(Logger log, Jdbi jdbi) {
        this.log = log;
        this.jdbi = jdbi;
    }

    /**
     * Upsert（INSERT 或 UPDATE）。VariableStore 的 create / update / setValue / bind 路径
     * 统一调它——内存状态先变更，DB 是 follower。
     */
    public void upsert(String wallId, String name, VarType type,
                       @Nullable String defaultValue,
                       @Nullable String currentValue,
                       @Nullable String boundTo,
                       long createdAt, long updatedAt) {
        try {
            jdbi.useHandle(h -> upsertOn(h, wallId, name, type, defaultValue,
                    currentValue, boundTo, createdAt, updatedAt));
        } catch (Exception e) {
            log.log(Level.WARNING, "UserVariableDao.upsert failed: "
                    + wallId + "/" + name, e);
        }
    }

    /** 事务感知重载；异常上抛由外层事务回滚。 */
    public void upsertOn(Handle h, String wallId, String name, VarType type,
                         @Nullable String defaultValue,
                         @Nullable String currentValue,
                         @Nullable String boundTo,
                         long createdAt, long updatedAt) {
        h.createUpdate(
                "INSERT INTO user_variables("
                        + "wall_id, name, type, default_value, current_value, "
                        + "bound_to, created_at, updated_at) "
                        + "VALUES(:wall, :name, :type, :def, :cur, :bound, :created, :updated) "
                        + "ON CONFLICT(wall_id, name) DO UPDATE SET "
                        + "type = excluded.type, "
                        + "default_value = excluded.default_value, "
                        + "current_value = excluded.current_value, "
                        + "bound_to = excluded.bound_to, "
                        + "updated_at = excluded.updated_at")
                .bind("wall", wallId)
                .bind("name", name)
                .bind("type", type.name())
                .bind("def", defaultValue)
                .bind("cur", currentValue)
                .bind("bound", boundTo)
                .bind("created", createdAt)
                .bind("updated", updatedAt)
                .execute();
    }

    public void delete(String wallId, String name) {
        try {
            jdbi.useHandle(h -> deleteOn(h, wallId, name));
        } catch (Exception e) {
            log.log(Level.WARNING, "UserVariableDao.delete failed: "
                    + wallId + "/" + name, e);
        }
    }

    public int deleteOn(Handle h, String wallId, String name) {
        return h.createUpdate(
                "DELETE FROM user_variables WHERE wall_id = :w AND name = :n")
                .bind("w", wallId)
                .bind("n", name)
                .execute();
    }

    /**
     * Wall 删除时显式级联清理。
     *
     * <p>FK CASCADE 已声明，理论上 walls 表 DELETE 后自动触发；但显式调用让业务侧不必
     * 依赖 PRAGMA / 时序，更稳。也用于"清空我的变量"批量操作。</p>
     */
    public int deleteByWall(String wallId) {
        try {
            return jdbi.withHandle(h -> deleteByWallOn(h, wallId));
        } catch (Exception e) {
            log.log(Level.WARNING, "UserVariableDao.deleteByWall failed: " + wallId, e);
            return 0;
        }
    }

    public int deleteByWallOn(Handle h, String wallId) {
        return h.createUpdate(
                "DELETE FROM user_variables WHERE wall_id = :w")
                .bind("w", wallId)
                .execute();
    }

    /** 启动期一次性加载所有 user_variables 到内存 store。 */
    public List<Row> loadAll() {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM user_variables ORDER BY wall_id, name")
                    .map((rs, ctx) -> mapRow(rs))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "UserVariableDao.loadAll failed", e);
            return List.of();
        }
    }

    public List<Row> listByWall(String wallId) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM user_variables WHERE wall_id = :w ORDER BY name")
                    .bind("w", wallId)
                    .map((rs, ctx) -> mapRow(rs))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "UserVariableDao.listByWall failed: " + wallId, e);
            return List.of();
        }
    }

    private static Row mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String typeStr = rs.getString("type");
        VarType type;
        try {
            type = VarType.valueOf(typeStr);
        } catch (IllegalArgumentException ex) {
            // DB 里出现未知类型——大概率 schema 漂或第三方乱改；降级 STRING 而非失败启动。
            type = VarType.STRING;
        }
        return new Row(
                rs.getString("wall_id"),
                rs.getString("name"),
                type,
                rs.getString("default_value"),
                rs.getString("current_value"),
                rs.getString("bound_to"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
