package ac.haru.hikaricanvas.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ISSUED 会话的超时起算点必须跟着「最近一次签发 token」走。
 *
 * <p>{@code /canvas open} 命中幂等重用分支时只 {@code touchActivity}，却在同一条路径上
 * <b>签发了全新 token</b>；而 reaper 判的是 {@code createdAt}。于是「open 之后没开浏览器，
 * 十几分钟后重跑 /canvas open 拿新链接」——最自然的自救动作——拿到的链接会在一两分钟后
 * 随 session 一起被收掉，玩家点进去只看到认证失败，日志里也没有任何线索。</p>
 */
class IssuedTimeoutTest {

    private static final long MIN = 60_000L;
    private static final long TIMEOUT = 15 * MIN;

    @Test
    void freshSession_notExpired() {
        long t0 = 1_000_000L;
        assertFalse(SessionManager.issuedExpired(t0 + MIN, t0, t0, TIMEOUT));
    }

    @Test
    void staleSession_expires() {
        long t0 = 1_000_000L;
        assertTrue(SessionManager.issuedExpired(t0 + 16 * MIN, t0, t0, TIMEOUT));
    }

    /** 核心用例：14 分钟后重跑 /canvas open（touchActivity + 新 token）→ 起算点重置。 */
    @Test
    void reopenRefreshesDeadline() {
        long created = 1_000_000L;
        long reopened = created + 14 * MIN;
        // 重开后 1 分钟：距 createdAt 已 15 分钟，但距重新签发只有 1 分钟 → 不该被收
        assertFalse(SessionManager.issuedExpired(reopened + MIN, created, reopened, TIMEOUT),
                "重新签发 token 之后应重新计时，否则新链接立刻作废");
        // 重开后 16 分钟：这次真的超时了
        assertTrue(SessionManager.issuedExpired(reopened + 16 * MIN, created, reopened, TIMEOUT));
    }

    /** lastActivityAt 早于 createdAt（不该发生）时退回按 createdAt 判，不放宽。 */
    @Test
    void lastActivityBeforeCreated_fallsBackToCreatedAt() {
        long created = 1_000_000L;
        assertTrue(SessionManager.issuedExpired(created + 16 * MIN, created, created - MIN, TIMEOUT));
    }
}
