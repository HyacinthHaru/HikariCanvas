package ac.haru.hikaricanvas.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code wall.refresh} 的 per-wall 冷却守卫。
 *
 * <p>{@code WallOpDispatcher} 此前<b>完全不限流</b>（security.md §3.3 原把 {@code wall.*}
 * 列为豁免，理由「各有 ACL + DB 写锁兜底」）。该理由对 {@code wall.refresh} 不成立：它每收到
 * 一条消息就往主线程 {@code runTask} 一次 {@code FrameDeployer.repairFor}，内部是
 * {@code world.getEntitiesByClass(ItemFrame.class)} <b>全世界实体扫描 + 逐格补方块</b>，
 * 既不落 DB 也没有写锁。任意持 {@code canvas.edit}（default=true）的玩家开一个 session 后
 * 即可让主线程每秒跑上百次全世界扫描把 TPS 打崩。</p>
 *
 * <p>0.9.17 起 {@code wall.*} 纳入 {@code SessionRateLimiter}，且 refresh 另加
 * per-wall 1 次/秒冷却——40msg/2s 的通用窗口对这种量级仍然过宽。</p>
 */
class WallRefreshCooldownTest {

    /** 只驱动冷却闸，不需要 SessionManager / FrameDeployer 等重装配。 */
    private static WallOpDispatcher bare() {
        return new WallOpDispatcher(null, null, null, null, null);
    }

    private static long msToNanos(long ms) {
        return ms * 1_000_000L;
    }

    @Test
    void firstRefresh_allowed() {
        assertTrue(bare().allowRefresh("w-1", msToNanos(0)));
    }

    @Test
    void secondRefreshWithinCooldown_blocked() {
        WallOpDispatcher d = bare();
        assertTrue(d.allowRefresh("w-1", msToNanos(0)));
        assertFalse(d.allowRefresh("w-1", msToNanos(1)), "1ms 后立刻再来 → 拒");
        assertFalse(d.allowRefresh("w-1", msToNanos(999)), "冷却窗内 → 拒");
    }

    @Test
    void refreshAfterCooldown_allowedAgain() {
        WallOpDispatcher d = bare();
        assertTrue(d.allowRefresh("w-1", msToNanos(0)));
        assertTrue(d.allowRefresh("w-1", msToNanos(WallOpDispatcher.REFRESH_COOLDOWN_MS)),
                "满 1s → 放行");
    }

    /** 冷却按 wall 记而非按 session——换 session 不能绕过同一面墙的冷却。 */
    @Test
    void cooldownIsPerWall_notShared() {
        WallOpDispatcher d = bare();
        assertTrue(d.allowRefresh("w-1", msToNanos(0)));
        assertTrue(d.allowRefresh("w-2", msToNanos(1)), "另一面墙不受影响");
        assertFalse(d.allowRefresh("w-1", msToNanos(2)), "同一面墙仍在冷却");
    }

    /** 被拒时不刷新时间基，否则连续快打会把冷却窗无限后延（也不该被拒方拖长）。 */
    @Test
    void blockedAttemptDoesNotExtendWindow() {
        WallOpDispatcher d = bare();
        assertTrue(d.allowRefresh("w-1", msToNanos(0)));
        assertFalse(d.allowRefresh("w-1", msToNanos(500)));
        assertFalse(d.allowRefresh("w-1", msToNanos(900)));
        assertTrue(d.allowRefresh("w-1", msToNanos(1000)),
                "起点仍是首次放行的 0ms，不因中途被拒而后延");
    }
}
