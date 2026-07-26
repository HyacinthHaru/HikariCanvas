package ac.haru.hikaricanvas.session;

import ac.haru.hikaricanvas.storage.AuditLog;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重连 token 的 TTL 必须覆盖会话可能的存活时间。
 *
 * <p>{@link TokenService#rotate} 只在每次 auth 成功时发生一次，签发时刻就是「上次 auth 时刻」，
 * 此后整个会话期间不再刷新。它以前与首发 token 共用 15 分钟 TTL，而会话本身可以活得久得多
 * （ws-grace 5 分钟 + idle 30 分钟）。于是「编辑超过 15 分钟 → 网络闪断 → 前端拿着早就过期的
 * reconnectToken 去 auth」必被拒，玩家只能回游戏重跑 {@code /canvas open} ——
 * 而这恰恰是长时间创作最容易碰上的场景。</p>
 */
class TokenServiceReconnectTtlTest {

    private static final Logger LOG = Logger.getLogger("token-ttl-test");
    private static final long MIN = 60_000L;

    private static TokenService svc(long tokenTtlMs, long sessionLifetimeMs) {
        return new TokenService(new AuditLog(null, LOG), LOG, tokenTtlMs, sessionLifetimeMs);
    }

    @Test
    void reconnectTtl_coversSessionLifetime_notJustTokenTtl() {
        TokenService s = svc(15 * MIN, 35 * MIN);   // 默认 idle 30min + grace 5min
        assertEquals(35 * MIN, s.reconnectTtlMillis(),
                "重连 token 必须活到会话可能的最长存活时间");
    }

    @Test
    void reconnectTtl_neverShorterThanIssueTtl() {
        TokenService s = svc(60 * MIN, 5 * MIN);   // 服主把首发 TTL 配得比会话寿命还长
        assertEquals(60 * MIN, s.reconnectTtlMillis(),
                "取 max，不能因为会话寿命短就把重连 token 削得比首发还短");
    }

    /** idle-minutes: 0（永不超时）会算出一个百年的寿命，必须被 24h 硬顶挡住。 */
    @Test
    void reconnectTtl_cappedAt24h() {
        TokenService s = svc(15 * MIN, 100L * 365 * 24 * 60 * MIN);
        assertEquals(TokenService.MAX_RECONNECT_TTL_MILLIS, s.reconnectTtlMillis());
    }

    /** 三参构造器（当前装配路径）也必须给出够长的重连 TTL，不能退回 15 分钟。 */
    @Test
    void threeArgCtor_stillGivesGenerousReconnectTtl() {
        TokenService s = new TokenService(new AuditLog(null, LOG), LOG, 15 * MIN);
        assertTrue(s.reconnectTtlMillis() >= 35 * MIN,
                "默认装配下重连 TTL 也要盖住 idle 30min + grace 5min，实际 "
                        + s.reconnectTtlMillis() + "ms");
        assertEquals(TokenService.DEFAULT_SESSION_LIFETIME_MILLIS, s.reconnectTtlMillis());
    }

    /** rotate 出来的 token 在首发 TTL 早就过期的时刻仍然可用。 */
    @Test
    void rotatedToken_survivesBeyondIssueTtl() {
        // 首发 TTL 设成 0 分钟不现实，改用"签发后立刻 peek"验证两条路都能用；
        // 真正的时间维度由 reconnectTtlMillis 的单测覆盖（Record 内是绝对时刻，无时钟注入 seam）
        TokenService s = svc(15 * MIN, 35 * MIN);
        UUID p = UUID.randomUUID();
        String issued = s.issue(p, "Alice", "sess-1");
        String rotated = s.rotate(p, "Alice", "sess-1");

        assertTrue(s.peek(issued) instanceof TokenService.ValidateResult.Ok);
        assertTrue(s.peek(rotated) instanceof TokenService.ValidateResult.Ok);
        assertTrue(s.reconnectTtlMillis() > 15 * MIN,
                "rotate 用的 TTL 必须严格长于首发 TTL");
    }
}
