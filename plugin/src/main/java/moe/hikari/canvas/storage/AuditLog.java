package moe.hikari.canvas.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;

import java.util.Map;
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
            detailsJson = null;
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
            // M15.4 P0-33：DB 失败 fallback 到 server log，至少留痕；
            // 安全事件不能因 DB 异常静默丢失。
            log.severe(String.format(
                    "[AUDIT FALLBACK] event=%s player=%s session=%s wall=- details=%s reason=%s",
                    event, playerUuid, sessionId, finalDetails, e.getMessage()));
        }
    }
}
