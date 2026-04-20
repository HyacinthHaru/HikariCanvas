package moe.hikari.canvas.session;

import moe.hikari.canvas.storage.AuditLog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

/**
 * 一次性 token 签发 & 校验，契约见 {@code docs/security.md §2}。
 *
 * <ul>
 *   <li>随机源：{@link SecureRandom}</li>
 *   <li>长度：32 字节 → URL-safe base64 无 padding，43 字符</li>
 *   <li>主存：内存 {@link ConcurrentMap}；原文绝不落盘</li>
 *   <li>审计：SHA-256 入 {@link AuditLog}，配合 {@code AUTH_ISSUED} / {@code AUTH_OK} / {@code AUTH_FAILED} 事件</li>
 *   <li>TTL：默认 15 分钟，可配置</li>
 *   <li>单次使用：{@link #consume(String)} 成功即永久 mark used</li>
 * </ul>
 *
 * <p>限流（§2.4）与 WS 握手时的 Origin 校验在 T10 实装；本类只管核心生命周期。</p>
 */
public final class TokenService {

    /** security.md §2.2：43 字符 URL-safe base64（32 字节无 padding）。 */
    public static final int TOKEN_LENGTH = 43;

    public enum RejectReason { INVALID_FORMAT, NOT_FOUND, ALREADY_USED, EXPIRED }

    public sealed interface ValidateResult {
        record Ok(UUID playerUuid, String sessionId) implements ValidateResult {}
        record Rejected(RejectReason reason) implements ValidateResult {}
    }

    private final SecureRandom rng = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder decoder = Base64.getUrlDecoder();
    private final ConcurrentMap<String, Record> tokens = new ConcurrentHashMap<>();
    private final AuditLog auditLog;
    private final Logger log;
    private final long defaultTtlMillis;

    public TokenService(AuditLog auditLog, Logger log, long defaultTtlMillis) {
        this.auditLog = auditLog;
        this.log = log;
        this.defaultTtlMillis = defaultTtlMillis;
    }

    /**
     * 为玩家 + 会话签发新 token。原文只返回给调用方一次。
     *
     * @return 43 字符的 URL-safe base64 token
     */
    public String issue(UUID playerUuid, String playerName, String sessionId) {
        byte[] bytes = new byte[32];
        rng.nextBytes(bytes);
        String token = encoder.encodeToString(bytes);

        long now = System.currentTimeMillis();
        tokens.put(token, new Record(playerUuid, sessionId, now, defaultTtlMillis, false));

        auditLog.record(
                "AUTH_ISSUED",
                playerUuid.toString(),
                playerName,
                sessionId,
                null,
                Map.of(
                        "token_sha256", sha256Hex(token),
                        "ttl_ms", defaultTtlMillis));

        return token;
    }

    /** 只校验不消耗（HTTP 预握手 {@code GET /api/session/:token} 用）。 */
    public ValidateResult peek(String token) {
        return evaluate(token, false);
    }

    /** 校验并立即标记为 used（WS {@code auth} 帧消费用）。 */
    public ValidateResult consume(String token) {
        return evaluate(token, true);
    }

    private ValidateResult evaluate(String token, boolean consume) {
        if (token == null || token.length() != TOKEN_LENGTH) {
            return new ValidateResult.Rejected(RejectReason.INVALID_FORMAT);
        }
        try {
            decoder.decode(token);
        } catch (IllegalArgumentException e) {
            return new ValidateResult.Rejected(RejectReason.INVALID_FORMAT);
        }

        Record rec = tokens.get(token);
        if (rec == null) {
            return new ValidateResult.Rejected(RejectReason.NOT_FOUND);
        }
        if (rec.used) {
            return new ValidateResult.Rejected(RejectReason.ALREADY_USED);
        }
        long now = System.currentTimeMillis();
        if (now > rec.issuedAt + rec.ttlMillis) {
            return new ValidateResult.Rejected(RejectReason.EXPIRED);
        }

        if (consume) {
            // 原子 CAS：被并发成功消耗的另一方胜出
            Record marked = new Record(rec.playerUuid, rec.sessionId, rec.issuedAt, rec.ttlMillis, true);
            if (!tokens.replace(token, rec, marked)) {
                return new ValidateResult.Rejected(RejectReason.ALREADY_USED);
            }
        }
        return new ValidateResult.Ok(rec.playerUuid, rec.sessionId);
    }

    /**
     * 清理过期 / 已用 token，返回移除数量。
     * 建议周期性调用（例如每 5 分钟一次）。
     */
    public int purgeExpired() {
        long now = System.currentTimeMillis();
        int before = tokens.size();
        tokens.entrySet().removeIf(e -> {
            Record r = e.getValue();
            return r.used || now > r.issuedAt + r.ttlMillis;
        });
        int removed = before - tokens.size();
        if (removed > 0) {
            log.fine("TokenService purged " + removed + " token(s)");
        }
        return removed;
    }

    public int activeCount() {
        return tokens.size();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private record Record(
            UUID playerUuid,
            String sessionId,
            long issuedAt,
            long ttlMillis,
            boolean used
    ) {}
}
