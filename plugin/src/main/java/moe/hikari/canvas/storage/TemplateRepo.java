package moe.hikari.canvas.storage;

import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * M14 模板元数据 DAO（创意工坊）。YAML spec 仍是真相；本表存 owner / featured / 排序用
 * 元数据。schema 见 {@code db-migrations/V008__templates.sql}。
 *
 * <ul>
 *   <li>builtin 模板：启动期由 {@link moe.hikari.canvas.template.TemplateRegistry#reload}
 *       同步入库；owner_uuid NULL；featured 不可改</li>
 *   <li>玩家发布模板：通过 {@code template.save} WS op 调 {@link #insert}；hard delete</li>
 *   <li>排序：精选优先 + 时间倒序（{@link #listMarketplace}）</li>
 * </ul>
 */
public final class TemplateRepo {

    /** 内存视图。 */
    public record Row(
            String templateId,
            UUID ownerUuid,        // builtin 时 null
            String ownerName,
            String displayName,
            String description,
            String yamlPath,
            boolean builtin,
            boolean featured,
            long downloadCount,
            long createdAt,
            long updatedAt
    ) {}

    private final Logger log;
    private final Jdbi jdbi;

    public TemplateRepo(Logger log, Jdbi jdbi) {
        this.log = log;
        this.jdbi = jdbi;
    }

    /** 插入或更新（同 template_id UPSERT）。{@code featured} 仅在 builtin=false 时由调用方控制。 */
    public boolean upsert(Row r) {
        try {
            int rows = jdbi.withHandle(h -> h.createUpdate(
                    "INSERT INTO templates(template_id, owner_uuid, owner_name, display_name, "
                            + "description, yaml_path, builtin, featured, download_count, "
                            + "created_at, updated_at) "
                            + "VALUES(:id, :ouuid, :oname, :dname, :desc, :yaml, :builtin, "
                            + ":feat, :dl, :created, :updated) "
                            + "ON CONFLICT(template_id) DO UPDATE SET "
                            + "display_name=excluded.display_name, "
                            + "description=excluded.description, "
                            + "yaml_path=excluded.yaml_path, "
                            + "updated_at=excluded.updated_at")
                    .bind("id", r.templateId)
                    .bind("ouuid", r.ownerUuid == null ? null : r.ownerUuid.toString())
                    .bind("oname", r.ownerName)
                    .bind("dname", r.displayName)
                    .bind("desc", r.description)
                    .bind("yaml", r.yamlPath)
                    .bind("builtin", r.builtin ? 1 : 0)
                    .bind("feat", r.featured ? 1 : 0)
                    .bind("dl", r.downloadCount)
                    .bind("created", r.createdAt)
                    .bind("updated", r.updatedAt)
                    .execute());
            return rows > 0;
        } catch (Exception e) {
            log.log(Level.WARNING, "TemplateRepo.upsert failed: " + r.templateId, e);
            return false;
        }
    }

    public Optional<Row> findById(String templateId) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM templates WHERE template_id = :id")
                    .bind("id", templateId)
                    .map((rs, ctx) -> mapRow(rs))
                    .findOne());
        } catch (Exception e) {
            log.log(Level.WARNING, "TemplateRepo.findById failed: " + templateId, e);
            return Optional.empty();
        }
    }

    /** Marketplace 排序：featured 优先 + 时间倒序。{@code limit} 0 = 全部。 */
    public List<Row> listMarketplace(int limit) {
        try {
            String sql = "SELECT * FROM templates ORDER BY featured DESC, created_at DESC"
                    + (limit > 0 ? " LIMIT " + limit : "");
            return jdbi.withHandle(h -> h.createQuery(sql)
                    .map((rs, ctx) -> mapRow(rs))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "TemplateRepo.listMarketplace failed", e);
            return List.of();
        }
    }

    public List<Row> listForOwner(UUID ownerUuid) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM templates WHERE owner_uuid = :u "
                            + "ORDER BY created_at DESC")
                    .bind("u", ownerUuid.toString())
                    .map((rs, ctx) -> mapRow(rs))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "TemplateRepo.listForOwner failed: " + ownerUuid, e);
            return List.of();
        }
    }

    /** 配额查询：某玩家已发布数量（不含 builtin）。 */
    public int countByOwner(UUID ownerUuid) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT COUNT(*) FROM templates "
                            + "WHERE owner_uuid = :u AND builtin = 0")
                    .bind("u", ownerUuid.toString())
                    .mapTo(Integer.class)
                    .one());
        } catch (Exception e) {
            log.log(Level.WARNING, "TemplateRepo.countByOwner failed: " + ownerUuid, e);
            return 0;
        }
    }

    /** admin 精选 toggle。{@code builtin} 模板永远 featured=1，不允许 unfeature（数据库不强制，调用方校验）。 */
    public boolean setFeatured(String templateId, boolean featured) {
        try {
            int rows = jdbi.withHandle(h -> h.createUpdate(
                    "UPDATE templates SET featured = :f, updated_at = :now "
                            + "WHERE template_id = :id AND builtin = 0")
                    .bind("f", featured ? 1 : 0)
                    .bind("now", System.currentTimeMillis())
                    .bind("id", templateId)
                    .execute());
            return rows == 1;
        } catch (Exception e) {
            log.log(Level.WARNING, "setFeatured failed: " + templateId, e);
            return false;
        }
    }

    public void incrementDownload(String templateId) {
        try {
            jdbi.useHandle(h -> h.createUpdate(
                    "UPDATE templates SET download_count = download_count + 1 "
                            + "WHERE template_id = :id")
                    .bind("id", templateId)
                    .execute());
        } catch (Exception e) {
            log.log(Level.WARNING, "incrementDownload failed: " + templateId, e);
        }
    }

    /** Hard delete：DB 行。文件 / 缩略图删除由上层 TemplatePublisher 协调。 */
    public void delete(String templateId) {
        try {
            jdbi.useHandle(h -> h.createUpdate(
                    "DELETE FROM templates WHERE template_id = :id")
                    .bind("id", templateId)
                    .execute());
        } catch (Exception e) {
            log.log(Level.WARNING, "TemplateRepo.delete failed: " + templateId, e);
        }
    }

    private static Row mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String ouuid = rs.getString("owner_uuid");
        return new Row(
                rs.getString("template_id"),
                ouuid == null ? null : UUID.fromString(ouuid),
                rs.getString("owner_name"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("yaml_path"),
                rs.getInt("builtin") != 0,
                rs.getInt("featured") != 0,
                rs.getLong("download_count"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
