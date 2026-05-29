package moe.hikari.canvas.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code audit_log} 表的薄封装。事件清单见 {@code docs/security.md §11}。
 *
 * <p>插入是 fire-and-forget（失败时仅 log warn，不影响业务链路）；
 * 安全要求的 token / IP 原文绝不入 detail，只存 SHA-256。</p>
 *
 * <p>M15.4 P0-33：DB 插入失败时 fallback 到 server log，至少给运维留痕；
 * 不再让异常向上冒泡静默丢失。</p>
 */
public final class AuditLog {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Jdbi jdbi;
    private final Logger log;

    /** 兼容老 API：没有 logger 时 fallback 路径只能吞错。建议用双参构造器。 */
    public AuditLog(Jdbi jdbi) {
        this(jdbi, Logger.getLogger(AuditLog.class.getName()));
    }

    public AuditLog(Jdbi jdbi, Logger log) {
        this.jdbi = jdbi;
        this.log = log;
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
            // P3-83：details 序列化失败不能静默——补 WARNING 日志（对齐本方法 DB-insert catch
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
            // M15.4 P0-33 / M16 P6.4：DB 失败 fallback 到 server log，至少留痕；
            // 安全事件不能因 DB 异常静默丢失。severe + 带异常对象（log.log）让 ops
            // 工具链能拿到 stack trace。
            log.log(Level.SEVERE, String.format(
                    "[AUDIT FALLBACK] event=%s player=%s session=%s wall=- details=%s reason=%s",
                    event, playerUuid, sessionId, finalDetails, e.getMessage()), e);
        }
    }
}
