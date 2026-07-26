package ac.haru.hikaricanvas.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code audit_log} 表的薄封装。事件清单见 {@code docs/security.md §8}。
 *
 * <p>插入是 fire-and-forget（失败时仅 log warn，不影响业务链路）；
 * 安全要求的 token / IP 原文绝不入 detail，只存 SHA-256。</p>
 *
 * <p>DB 插入失败时 fallback 到 server log，至少给运维留痕；
 * 不让异常向上冒泡静默丢失。</p>
 *
 * <p><b>保留策略</b>（{@code docs/security.md §8.2} / {@code data-model.md §2.6.4}）：
 * 默认 90 天，{@code database.audit-retention-days} 可配，{@code 0} = 永久保留。
 * 30 多种事件类型全往同一张表里塞，没有清理就是无界增长——而 audit_log 与 walls / 变量
 * 共用一个 data.db 文件，涨起来是拖累整库。清理由 {@link #record} 顺带驱动：
 * 距上次清理超过 {@link #PURGE_INTERVAL_MS} 才跑一次
 * {@code DELETE FROM audit_log WHERE ts < ?}（走 V001 建的 {@code idx_audit_ts} 索引）。
 * 不起独立调度线程，与 {@code BackupReaper}「顺带清一次、不常驻」同一思路。</p>
 */
public final class AuditLog {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 默认保留天数（docs/security.md §8.2 承诺的 90 天）。 */
    public static final int DEFAULT_RETENTION_DAYS = 90;

    /** 两次清理之间的最小间隔：6 小时。保留期是「天」量级，没必要更勤。 */
    static final long PURGE_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private final Jdbi jdbi;
    private final Logger log;
    /** ≤0 = 不清理（永久保留）。 */
    private final int retentionDays;
    /**
     * 上次清理时刻；{@code 0} = 还没清过。用 CAS 保证并发 record 只有一个线程真去 DELETE。
     * 初值 0 意味着进程内第一条审计就会顺带清一次（相当于「启动期清理」）。
     */
    private final java.util.concurrent.atomic.AtomicLong lastPurgeAt =
            new java.util.concurrent.atomic.AtomicLong(0L);

    /** 兼容老 API：没有 logger 时 fallback 路径只能吞错。建议用双参构造器。 */
    public AuditLog(Jdbi jdbi) {
        this(jdbi, Logger.getLogger(AuditLog.class.getName()));
    }

    public AuditLog(Jdbi jdbi, Logger log) {
        this(jdbi, log, DEFAULT_RETENTION_DAYS);
    }

    public AuditLog(Jdbi jdbi, Logger log, int retentionDays) {
        this.jdbi = jdbi;
        this.log = log;
        this.retentionDays = retentionDays;
    }

    /** 当前生效的保留天数（≤0 = 永久保留）。 */
    public int retentionDays() {
        return retentionDays;
    }

    public void record(
            String event,
            String playerUuid,
            String playerName,
            String sessionId,
            String ipHash,
            Map<String, Object> details
    ) {
        String detailsJson;
        try {
            detailsJson = details == null || details.isEmpty()
                    ? null
                    : JSON.writeValueAsString(details);
        } catch (Exception e) {
            // details 序列化失败不能静默——补 WARNING 日志（对齐本方法 DB-insert catch
            // 与 insertSignRecord 既有 fallback 约定）；写可追溯 marker 而非纯 null，保留审计上下文。
            log.log(Level.WARNING, "AuditLog details serialization failed for event=" + event, e);
            detailsJson = "{\"_serialization_failed\":true}";
        }
        final String finalDetails = detailsJson;
        try {
            jdbi.useHandle(h -> h.execute(
                    "INSERT INTO audit_log (ts, event, player_uuid, player_name, session_id, ip_hash, details) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    System.currentTimeMillis(),
                    event,
                    playerUuid,
                    playerName,
                    sessionId,
                    ipHash,
                    finalDetails));
        } catch (Exception e) {
            // DB 失败 fallback 到 server log，至少留痕；
            // 安全事件不能因 DB 异常静默丢失。severe + 带异常对象（log.log）让 ops
            // 工具链能拿到 stack trace。
            log.log(Level.SEVERE, String.format(
                    "[AUDIT FALLBACK] event=%s player=%s session=%s wall=- details=%s reason=%s",
                    event, playerUuid, sessionId, finalDetails, e.getMessage()), e);
        }
        maybePurge(System.currentTimeMillis());
    }

    /**
     * 距上次清理超过 {@link #PURGE_INTERVAL_MS} 就跑一次保留期清理。CAS 抢占，
     * 并发 record 只会有一个线程真去 DELETE；其余直接返回。
     *
     * @return 实际删除的行数；本次没轮到清理 / 已禁用时返 0
     */
    int maybePurge(long nowMillis) {
        if (retentionDays <= 0 || jdbi == null) return 0;
        long last = lastPurgeAt.get();
        if (last != 0L && nowMillis - last < PURGE_INTERVAL_MS) return 0;
        if (!lastPurgeAt.compareAndSet(last, nowMillis)) return 0;   // 别的线程抢到了
        return purgeOlderThan(nowMillis);
    }

    /**
     * 删除早于保留期的审计记录。走 {@code idx_audit_ts}（V001 建），单条 DELETE。
     * 失败只 warning——审计清理不是业务链路，不能因为它把 record 搞挂。
     *
     * <p>package-private 供测试直接驱动，绕开 {@link #PURGE_INTERVAL_MS} 的时间闸。</p>
     *
     * @return 删除的行数；保留期 ≤0（永久保留）或无 jdbi 时返 0
     */
    int purgeOlderThan(long nowMillis) {
        if (retentionDays <= 0 || jdbi == null) return 0;
        long cutoff = nowMillis - (long) retentionDays * 24L * 60L * 60L * 1000L;
        try {
            int deleted = jdbi.withHandle(h ->
                    h.createUpdate("DELETE FROM audit_log WHERE ts < :cutoff")
                            .bind("cutoff", cutoff)
                            .execute());
            if (deleted > 0) {
                log.info("Audit retention: deleted " + deleted
                        + " audit_log row(s) older than " + retentionDays + " days");
            }
            return deleted;
        } catch (Exception e) {
            log.log(Level.WARNING, "Audit retention purge failed", e);
            return 0;
        }
    }
}
