package moe.hikari.canvas.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;

import java.util.Map;

/**
 * {@code audit_log} 表的薄封装。事件清单见 {@code docs/security.md §11}。
 *
 * <p>插入是 fire-and-forget（失败时仅 log warn，不影响业务链路）；
 * 安全要求的 token / IP 原文绝不入 detail，只存 SHA-256。</p>
 */
public final class AuditLog {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Jdbi jdbi;

    public AuditLog(Jdbi jdbi) {
        this.jdbi = jdbi;
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
    }
}
