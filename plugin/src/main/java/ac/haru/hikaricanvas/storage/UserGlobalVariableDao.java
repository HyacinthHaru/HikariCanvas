package ac.haru.hikaricanvas.storage;

import ac.haru.hikaricanvas.variable.VarType;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code user_global_variables} 表 DAO。schema 见
 * {@code db-migrations/V015__user_global_variables.sql} + {@code docs/dynamic-data.md §17}。
 *
 * <p><b>与 {@link UserVariableDao} 关键区别</b>：</p>
 * <ul>
 *   <li>{@code user_variables} = per-wall（主键 = (wall_id, name)，wall 删则 FK CASCADE）</li>
 *   <li>{@code user_global_variables} = 全服唯一（主键 = name 单字段，跨 wall 共享）</li>
 * </ul>
 *
 * <p><b>ACL</b>：owner 改自己走 {@code canvas.var.global.write.own}；改他人走
 * {@code .any}（默 op）。详见 {@code docs/dynamic-data.md §17.3}。</p>
 *
 * <p><b>配额</b>：per-owner 500 + 全服 10000（{@link #countByOwner} / {@link #countTotal}），
 * config.yml 可调；具体校验在 {@code VariableStore.createGlobal} 路径。</p>
 */
public class UserGlobalVariableDao {

    /** user_global_variables 表一行的内存视图。 */
    public record Row(
            String name,
            UUID ownerUuid,
            String ownerName,
            VarType type,
            @Nullable String defaultValue,
            @Nullable String currentValue,
            @Nullable String boundTo,
            long createdAt,
            long updatedAt
    ) {}

    private final Logger log;
    private final Jdbi jdbi;

    public UserGlobalVariableDao(Logger log, Jdbi jdbi) {
        this.log = log;
        this.jdbi = jdbi;
    }

    /**
     * Upsert（INSERT 或 UPDATE）。VariableStore 的 createGlobal / update / setValue / bind 路径
     * 统一调它——内存状态先变更，DB 是 follower。owner_uuid / owner_name 仅首次插入时写；
     * 后续 update 保留原值（ON CONFLICT 不覆盖 owner_uuid / owner_name / created_at）。
     */
    public void upsert(String name, UUID ownerUuid, String ownerName, VarType type,
                       @Nullable String defaultValue,
                       @Nullable String currentValue,
                       @Nullable String boundTo,
                       long createdAt, long updatedAt) {
        try {
            jdbi.useHandle(h -> upsertOn(h, name, ownerUuid, ownerName, type,
                    defaultValue, currentValue, boundTo, createdAt, updatedAt));
        } catch (Exception e) {
            log.log(Level.WARNING, "UserGlobalVariableDao.upsert failed: " + name, e);
        }
    }

    /** 事务感知重载；异常上抛由外层事务回滚。 */
    public void upsertOn(Handle h, String name, UUID ownerUuid, String ownerName, VarType type,
                         @Nullable String defaultValue,
                         @Nullable String currentValue,
                         @Nullable String boundTo,
                         long createdAt, long updatedAt) {
        h.createUpdate(
                "INSERT INTO user_global_variables("
                        + "name, owner_uuid, owner_name, type, default_value, current_value, "
                        + "bound_to, created_at, updated_at) "
                        + "VALUES(:name, :owner, :ownerName, :type, :def, :cur, :bound, :created, :updated) "
                        + "ON CONFLICT(name) DO UPDATE SET "
                        + "type = excluded.type, "
                        + "default_value = excluded.default_value, "
                        + "current_value = excluded.current_value, "
                        + "bound_to = excluded.bound_to, "
                        + "updated_at = excluded.updated_at")
                .bind("name", name)
                .bind("owner", ownerUuid.toString())
                .bind("ownerName", ownerName)
                .bind("type", type.name())
                .bind("def", defaultValue)
                .bind("cur", currentValue)
                .bind("bound", boundTo)
                .bind("created", createdAt)
                .bind("updated", updatedAt)
                .execute();
    }

    public void delete(String name) {
        try {
            jdbi.useHandle(h -> deleteOn(h, name));
        } catch (Exception e) {
            log.log(Level.WARNING, "UserGlobalVariableDao.delete failed: " + name, e);
        }
    }

    public int deleteOn(Handle h, String name) {
        return h.createUpdate(
                "DELETE FROM user_global_variables WHERE name = :n")
                .bind("n", name)
                .execute();
    }

    /** 启动期一次性加载所有 user_global_variables 到内存 store。 */
    public List<Row> loadAll() {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM user_global_variables ORDER BY name")
                    .map((rs, ctx) -> mapRow(rs))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "UserGlobalVariableDao.loadAll failed", e);
            return List.of();
        }
    }

    /** 按 owner 列出。Admin Panel / `/canvas var list userglobal --owner=X` 用。 */
    public List<Row> listByOwner(UUID ownerUuid) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM user_global_variables WHERE owner_uuid = :o ORDER BY name")
                    .bind("o", ownerUuid.toString())
                    .map((rs, ctx) -> mapRow(rs))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "UserGlobalVariableDao.listByOwner failed: " + ownerUuid, e);
            return List.of();
        }
    }

    /** 配额检查：该 owner 已有多少全局变量。{@code create} 路径前置查。 */
    public int countByOwner(UUID ownerUuid) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT COUNT(*) FROM user_global_variables WHERE owner_uuid = :o")
                    .bind("o", ownerUuid.toString())
                    .mapTo(Integer.class)
                    .one());
        } catch (Exception e) {
            log.log(Level.WARNING, "UserGlobalVariableDao.countByOwner failed: " + ownerUuid, e);
            return 0;
        }
    }

    /** 配额检查：全服总数。{@code create} 路径前置查。 */
    public int countTotal() {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT COUNT(*) FROM user_global_variables")
                    .mapTo(Integer.class)
                    .one());
        } catch (Exception e) {
            log.log(Level.WARNING, "UserGlobalVariableDao.countTotal failed", e);
            return 0;
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
        UUID ownerUuid;
        try {
            ownerUuid = UUID.fromString(rs.getString("owner_uuid"));
        } catch (IllegalArgumentException ex) {
            // 极少见：DB owner_uuid 非合法 UUID（外部 SQL 乱写）；填全 0 UUID + skip 走 caller 决定。
            ownerUuid = new UUID(0L, 0L);
        }
        return new Row(
                rs.getString("name"),
                ownerUuid,
                rs.getString("owner_name"),
                type,
                rs.getString("default_value"),
                rs.getString("current_value"),
                rs.getString("bound_to"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
