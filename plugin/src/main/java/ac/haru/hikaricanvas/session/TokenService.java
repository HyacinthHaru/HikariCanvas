package ac.haru.hikaricanvas.session;

import ac.haru.hikaricanvas.storage.AuditLog;

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
 *   <li>TTL：首发 token 默认 15 分钟，可配置（{@code session.token-ttl-minutes}）；
 *       {@link #rotate} 出的重连 token 走 {@link #reconnectTtlMillis()}，必须覆盖会话存活上限</li>
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
    /** 重连 token TTL 的硬顶（24h）。挡住 idle-minutes: 0（永不超时）算出的百年 TTL。 */
    static final long MAX_RECONNECT_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    /**
     * 没告诉本类会话存活上限时，重连 token 用的兜底 TTL（12 小时）。
     * 足够盖住默认的 idle 30min + grace 5min 以及绝大多数服主的加长配置。
     */
    static final long DEFAULT_SESSION_LIFETIME_MILLIS = 12L * 60L * 60L * 1000L;

    private final AuditLog auditLog;
    private final Logger log;
    private final long defaultTtlMillis;
    /** 会话可能的最长存活时间（idle + ws-grace），供 {@link #reconnectTtlMillis()} 推算。 */
    private final long sessionLifetimeMillis;

    /** 常用装配：会话存活上限走 {@link #DEFAULT_SESSION_LIFETIME_MILLIS} 兜底值。 */
    public TokenService(AuditLog auditLog, Logger log, long defaultTtlMillis) {
        this(auditLog, log, defaultTtlMillis, DEFAULT_SESSION_LIFETIME_MILLIS);
    }

    /**
     * @param defaultTtlMillis      首发 token TTL（{@code session.token-ttl-minutes}）
     * @param sessionLifetimeMillis 会话存活上限（{@code idle-minutes + ws-grace-minutes}），
     *                              用于给重连 token 定 TTL，见 {@link #reconnectTtlMillis()}
     */
    public TokenService(AuditLog auditLog, Logger log,
                        long defaultTtlMillis, long sessionLifetimeMillis) {
        this.auditLog = auditLog;
        this.log = log;
        this.defaultTtlMillis = defaultTtlMillis;
        this.sessionLifetimeMillis = sessionLifetimeMillis;
    }

    /**
     * 首次签发 token（/canvas confirm 流程）。原文只返回给调用方一次；
     * 审计事件 {@code AUTH_ISSUED}。
     *
     * @return 43 字符的 URL-safe base64 token
     */
    public String issue(UUID playerUuid, String playerName, String sessionId) {
        return issueInternal(playerUuid, playerName, sessionId, "AUTH_ISSUED");
    }

    /**
     * WS auth 成功后 rotate 签发新 token 供后续断线重连使用（{@code docs/security.md §2.2}）。
     * 语义与 {@link #issue} 相同，只是审计事件改为 {@code AUTH_ROTATED} 以便溯源
     * 「初次签发 vs rotate 签发」，且 <b>TTL 走 {@link #reconnectTtlMillis()}</b>。
     *
     * <p><b>为什么重连 token 不能跟首发 token 共用 15 分钟：</b> rotate 只在每次 auth 成功
     * 时发生一次，签发时刻就是「上次 auth 时刻」，此后整个会话期间不再刷新。而会话本身
     * 可以活得久得多（ws-grace 5 分钟 + idle 30 分钟）。于是「编辑超过 15 分钟 → 网络闪断 →
     * 前端拿着早就过期的 reconnectToken 去 auth」必被拒，玩家只能回游戏重跑 /canvas open——
     * 而这恰恰是长时间创作最容易碰上的场景。首发 token 是「换页面用的一次性凭证」，
     * 短 TTL 合理；重连 token 是「会话存活期内的续命凭证」，TTL 必须覆盖会话可能的存活上限。</p>
     */
    public String rotate(UUID playerUuid, String playerName, String sessionId) {
        return issueInternal(playerUuid, playerName, sessionId, "AUTH_ROTATED", reconnectTtlMillis());
    }

    /**
     * 重连 token 的 TTL：至少覆盖 {@code session.idle-minutes} 上限，再留一点余量。
     *
     * <p>没有独立配置项——它不是给服主调的旋钮，而是「会话还活着，重连凭证就该还有效」
     * 这条不变量的实现细节。取 {@code max(首发 TTL, 会话存活上限)} 并封顶 24 小时
     * （idle-minutes 配 0 = 永不超时时会得到一个百年的 Duration，不能照搬）。</p>
     *
     * <p><b>拉长 TTL 不放大攻击面</b>：token 仍是一次性的，且必须指向一个还活着、
     * 非 CLOSING 的 session，还要过会话 IP 绑定。真正的有效期是
     * {@code min(token TTL, 会话剩余寿命)}——会话一 cancel / forget，token 立刻作废。
     * 所以让 token TTL 宽一点、由会话寿命当真正的闸，才是对的分工。</p>
     */
    long reconnectTtlMillis() {
        long floor = sessionLifetimeMillis;
        long capped = Math.min(floor, MAX_RECONNECT_TTL_MILLIS);
        return Math.max(defaultTtlMillis, capped);
    }

    private String issueInternal(UUID playerUuid, String playerName, String sessionId, String auditEvent) {
        return issueInternal(playerUuid, playerName, sessionId, auditEvent, defaultTtlMillis);
    }

    private String issueInternal(UUID playerUuid, String playerName, String sessionId,
                                 String auditEvent, long ttlMillis) {
        byte[] bytes = new byte[32];
        rng.nextBytes(bytes);
        String token = encoder.encodeToString(bytes);

        long now = System.currentTimeMillis();
        tokens.put(token, new Record(playerUuid, sessionId, now, ttlMillis, false));

        auditLog.record(
                auditEvent,
                playerUuid.toString(),
                playerName,
                sessionId,
                null,
                Map.of(
                        "token_sha256", sha256Hex(token),
                        "ttl_ms", ttlMillis));

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
