package ac.haru.hikaricanvas.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code wall.refresh} 的 per-wall 1 次/秒冷却守卫单测（防止通过高频触发
 * {@code FrameDeployer.repairFor} 全世界实体扫描拖垮主线程 TPS）。
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
