package moe.hikari.canvas.storage;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * M13 {@code image_uploads} 表 DAO。按 sha256[:16] 内容寻址，跨 wall 引用同一 hash
 * 文件零重复存储。schema 见 {@code db-migrations/V007__image_uploads.sql}、
 * {@code docs/data-model.md §2.6.5}。
 *
 * <p>v1 简化：{@code refcount} 列保留但不做实时增减；LRU 候选由
 * {@code ImageStorage} 在 evict 时实时 sweep {@code walls.project_json} 算出。</p>
 */
public final class ImageUploadDao {

    /** image_uploads 表一行的内存视图。 */
    public record Row(
            String hash,
            long bytes,
            int width,
            int height,
            String mime,
            UUID uploaderUuid,
            long uploadedAt,
            long lastUsedAt,
            int refcount
    ) {}

    private final Logger log;
    private final Jdbi jdbi;

    public ImageUploadDao(Logger log, Jdbi jdbi) {
        this.log = log;
        this.jdbi = jdbi;
    }

    /**
     * 插入一条新上传记录。冲突（同 hash 已存在）由调用方提前 {@link #findByHash} 检查；
     * 内容寻址语义下相同 hash = 相同文件，重复上传应走 {@link #touchLastUsed} 而非 insert。
     *
     * @return true 表示插入成功；false = 主键冲突（同 hash 已存在）
     */
    public boolean insert(Row r) {
        try {
            int rows = jdbi.withHandle(h -> insertOn(h, r));
            return rows == 1;
        } catch (Exception e) {
            log.log(Level.WARNING, "ImageUploadDao.insert failed: " + r.hash, e);
            return false;
        }
    }

    /**
     * M16 P2.1：事务内执行 INSERT，不吞异常。调用方在 {@code jdbi.inTransaction} lambda
     * 内传入活动 {@link Handle}；冲突或异常上抛使外层事务能感知并回滚。
     *
     * @return 1 = 插入成功；0 = INSERT OR IGNORE 命中冲突（同 hash 已存在）
     */
    public int insertOn(Handle h, Row r) {
        return h.createUpdate(
                "INSERT OR IGNORE INTO image_uploads("
                        + "hash, bytes, width, height, mime, uploader_uuid, "
                        + "uploaded_at, last_used_at, refcount) "
                        + "VALUES(:hash, :bytes, :w, :h, :mime, :uploader, "
                        + ":uploaded, :lastUsed, :refcount)")
                .bind("hash", r.hash)
                .bind("bytes", r.bytes)
                .bind("w", r.width)
                .bind("h", r.height)
                .bind("mime", r.mime)
                .bind("uploader", r.uploaderUuid.toString())
                .bind("uploaded", r.uploadedAt)
                .bind("lastUsed", r.lastUsedAt)
                .bind("refcount", r.refcount)
                .execute();
    }

    public Optional<Row> findByHash(String hash) {
        try {
            return jdbi.withHandle(h -> findByHashOn(h, hash));
        } catch (Exception e) {
            log.log(Level.WARNING, "ImageUploadDao.findByHash failed: " + hash, e);
            return Optional.empty();
        }
    }

    /** Transaction-aware overload; throws on error so outer tx can rollback. */
    public Optional<Row> findByHashOn(Handle h, String hash) {
        return h.createQuery(
                "SELECT * FROM image_uploads WHERE hash = :h")
                .bind("h", hash)
                .map((rs, ctx) -> mapRow(rs))
                .findOne();
    }

    /** 引用图片时刷新 last_used_at（用于 LRU 排序）。 */
    public void touchLastUsed(String hash, long ts) {
        try {
            jdbi.useHandle(h -> touchLastUsedOn(h, hash, ts));
        } catch (Exception e) {
            log.log(Level.WARNING, "touchLastUsed failed: " + hash, e);
        }
    }

    /** Transaction-aware overload. */
    public void touchLastUsedOn(Handle h, String hash, long ts) {
        h.createUpdate(
                "UPDATE image_uploads SET last_used_at = :ts WHERE hash = :h")
                .bind("ts", ts)
                .bind("h", hash)
                .execute();
    }

    public void delete(String hash) {
        try {
            jdbi.useHandle(h -> deleteOn(h, hash));
        } catch (Exception e) {
            log.log(Level.WARNING, "ImageUploadDao.delete failed: " + hash, e);
        }
    }

    /** Transaction-aware overload; throws on error. */
    public int deleteOn(Handle h, String hash) {
        return h.createUpdate(
                "DELETE FROM image_uploads WHERE hash = :h")
                .bind("h", hash)
                .execute();
    }

    /** 玩家在 sinceTs 后的上传次数（per-player 24h 配额用）。 */
    public int countByUploaderSince(UUID uploaderUuid, long sinceTs) {
        try {
            return jdbi.withHandle(h -> countByUploaderSinceOn(h, uploaderUuid, sinceTs));
        } catch (Exception e) {
            log.log(Level.WARNING, "countByUploaderSince failed: " + uploaderUuid, e);
            return 0;
        }
    }

    /** Transaction-aware overload; throws on error. */
    public int countByUploaderSinceOn(Handle h, UUID uploaderUuid, long sinceTs) {
        return h.createQuery(
                "SELECT COUNT(*) FROM image_uploads "
                        + "WHERE uploader_uuid = :u AND uploaded_at >= :ts")
                .bind("u", uploaderUuid.toString())
                .bind("ts", sinceTs)
                .mapTo(Integer.class)
                .one();
    }

    /** 总磁盘字节数（total-disk-mb 配额用）。 */
    public long sumBytes() {
        try {
            return jdbi.withHandle(this::sumBytesOn);
        } catch (Exception e) {
            log.log(Level.WARNING, "sumBytes failed", e);
            return 0L;
        }
    }

    /** Transaction-aware overload; throws on error. */
    public long sumBytesOn(Handle h) {
        return h.createQuery(
                "SELECT COALESCE(SUM(bytes), 0) FROM image_uploads")
                .mapTo(Long.class)
                .one();
    }

    /**
     * LRU 候选：last_used_at 升序，前 limit 行。{@code excludeHashes} 内的 hash 被剔除
     * （v1：实时被 walls.project_json 引用的 image source，由 ImageStorage 计算后传入）。
     */
    public List<Row> pickLruCandidates(int limit, java.util.Set<String> excludeHashes) {
        try {
            return jdbi.withHandle(h -> pickLruCandidatesOn(h, limit, excludeHashes));
        } catch (Exception e) {
            log.log(Level.WARNING, "pickLruCandidates failed", e);
            return List.of();
        }
    }

    /** Transaction-aware overload; throws on error. */
    public List<Row> pickLruCandidatesOn(Handle h, int limit, java.util.Set<String> excludeHashes) {
        if (excludeHashes == null || excludeHashes.isEmpty()) {
            return h.createQuery(
                    "SELECT * FROM image_uploads ORDER BY last_used_at ASC LIMIT :n")
                    .bind("n", Math.max(limit, 1))
                    .map((rs, ctx) -> mapRow(rs))
                    .list();
        }
        // M15.4 P0-15：用 SQL NOT IN 替代内存 filter。否则攻击者上传 16 张图全引用 →
        // SQL 返 16 行、filter 全空 → break → 所有玩家永久 fail。
        return h.createQuery(
                "SELECT * FROM image_uploads "
                        + "WHERE hash NOT IN (<exc>) "
                        + "ORDER BY last_used_at ASC LIMIT :n")
                .bindList("exc", new java.util.ArrayList<>(excludeHashes))
                .bind("n", Math.max(limit, 1))
                .map((rs, ctx) -> mapRow(rs))
                .list();
    }

    public List<Row> listAll() {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM image_uploads ORDER BY uploaded_at DESC")
                    .map((rs, ctx) -> mapRow(rs))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "ImageUploadDao.listAll failed", e);
            return List.of();
        }
    }

    private static Row mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(
                rs.getString("hash"),
                rs.getLong("bytes"),
                rs.getInt("width"),
                rs.getInt("height"),
                rs.getString("mime"),
                UUID.fromString(rs.getString("uploader_uuid")),
                rs.getLong("uploaded_at"),
                rs.getLong("last_used_at"),
                rs.getInt("refcount"));
    }
}
