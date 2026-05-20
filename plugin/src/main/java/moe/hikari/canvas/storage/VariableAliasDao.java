package moe.hikari.canvas.storage;

import moe.hikari.canvas.variable.VariableAlias;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code variable_aliases} 表 DAO（0.4.2）。schema 见
 * {@code db-migrations/V014__variable_aliases.sql} + {@code docs/variables.md §1.12}。
 *
 * <p><b>per-wall</b>：alias 是 wall 局部状态——同一 fullName 在不同 wall 可起不同别名；
 * 同一 wall 内一个 fullName 只一个别名（主键 = wall_id + full_name）。alias 字段不要求
 * wall 内唯一（业务允许重复，UI 自行警告）。</p>
 *
 * <p><b>命名约束（校验在 dispatcher 层）</b>：alias 1..64 字符；fullName ≤ 256 字符；wallId
 * 非空。本类不重复校验，靠调用方保证（dispatcher 层走 stringOrNull + length check）。</p>
 */
public class VariableAliasDao {

    /** alias / fullName 最大长度（与 dispatcher 校验对齐；DB 不强制）。 */
    public static final int ALIAS_MAX_LEN = 64;
    public static final int FULL_NAME_MAX_LEN = 256;

    private final Logger log;
    private final Jdbi jdbi;

    public VariableAliasDao(Logger log, Jdbi jdbi) {
        this.log = log;
        this.jdbi = jdbi;
    }

    /**
     * 创建或更新一行别名。{@code created_at} 仅首次插入时写；后续 update 只刷
     * {@code alias} + {@code updated_at}。
     */
    public void upsert(String wallId, String fullName, String alias, long now) {
        try {
            jdbi.useHandle(h -> upsertOn(h, wallId, fullName, alias, now));
        } catch (Exception e) {
            log.log(Level.WARNING, "VariableAliasDao.upsert failed: "
                    + wallId + "/" + fullName, e);
        }
    }

    /** 事务感知重载；异常上抛由外层事务回滚。 */
    public void upsertOn(Handle h, String wallId, String fullName, String alias, long now) {
        h.createUpdate(
                "INSERT INTO variable_aliases("
                        + "wall_id, full_name, alias, created_at, updated_at) "
                        + "VALUES(:wall, :full, :alias, :now, :now) "
                        + "ON CONFLICT(wall_id, full_name) DO UPDATE SET "
                        + "alias = excluded.alias, "
                        + "updated_at = excluded.updated_at")
                .bind("wall", wallId)
                .bind("full", fullName)
                .bind("alias", alias)
                .bind("now", now)
                .execute();
    }

    /** 删除单行别名。返受影响行数（0 = 不存在）。 */
    public int delete(String wallId, String fullName) {
        try {
            return jdbi.withHandle(h -> deleteOn(h, wallId, fullName));
        } catch (Exception e) {
            log.log(Level.WARNING, "VariableAliasDao.delete failed: "
                    + wallId + "/" + fullName, e);
            return 0;
        }
    }

    public int deleteOn(Handle h, String wallId, String fullName) {
        return h.createUpdate(
                "DELETE FROM variable_aliases WHERE wall_id = :w AND full_name = :f")
                .bind("w", wallId)
                .bind("f", fullName)
                .execute();
    }

    /**
     * Wall 删除时显式级联清理。FK CASCADE 已配置，显式调用让业务侧不依赖 PRAGMA 时序。
     */
    public int deleteByWall(String wallId) {
        try {
            return jdbi.withHandle(h -> deleteByWallOn(h, wallId));
        } catch (Exception e) {
            log.log(Level.WARNING, "VariableAliasDao.deleteByWall failed: " + wallId, e);
            return 0;
        }
    }

    public int deleteByWallOn(Handle h, String wallId) {
        return h.createUpdate(
                "DELETE FROM variable_aliases WHERE wall_id = :w")
                .bind("w", wallId)
                .execute();
    }

    /**
     * 加载一个 wall 的所有别名为 {@code Map<fullName, alias>}。Ready payload 用这个
     * 直接序列化成 JSON。返回 {@link LinkedHashMap} 以保留稳定的 ORDER BY full_name 顺序。
     */
    public Map<String, String> loadByWall(String wallId) {
        try {
            return jdbi.withHandle(h -> {
                Map<String, String> out = new LinkedHashMap<>();
                h.createQuery(
                        "SELECT full_name, alias FROM variable_aliases "
                                + "WHERE wall_id = :w ORDER BY full_name")
                        .bind("w", wallId)
                        .map((rs, ctx) -> new String[]{
                                rs.getString("full_name"),
                                rs.getString("alias")
                        })
                        .forEach(row -> out.put(row[0], row[1]));
                return out;
            });
        } catch (Exception e) {
            log.log(Level.WARNING, "VariableAliasDao.loadByWall failed: " + wallId, e);
            return Map.of();
        }
    }

    /** 启动期或测试用一次性加载所有别名。 */
    public List<VariableAlias> loadAll() {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT wall_id, full_name, alias, created_at, updated_at "
                            + "FROM variable_aliases ORDER BY wall_id, full_name")
                    .map((rs, ctx) -> new VariableAlias(
                            rs.getString("wall_id"),
                            rs.getString("full_name"),
                            rs.getString("alias"),
                            rs.getLong("created_at"),
                            rs.getLong("updated_at")))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "VariableAliasDao.loadAll failed", e);
            return List.of();
        }
    }
}
