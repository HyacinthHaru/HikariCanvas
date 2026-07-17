package ac.haru.hikaricanvas.web;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-05-25：{@link TokenRateLimiter} 行为校验。
 *
 * <p>覆盖：</p>
 * <ol>
 *   <li>构造参数校验</li>
 *   <li>桶填满 / drain 跨窗口重置</li>
 *   <li>跨 IP 互不影响</li>
 *   <li>null / empty IP 都走 "unknown" 桶</li>
 *   <li>clearFor / reset 行为</li>
 *   <li>currentCountFor 观察值正确</li>
 * </ol>
 */
class TokenRateLimiterTest {

    /** 可手动推进时钟，方便单测确定性。 */
    private static final class FakeClock extends Clock {
        final AtomicLong now;
        FakeClock(long startMs) { this.now = new AtomicLong(startMs); }
        void advance(long deltaMs) { now.addAndGet(deltaMs); }
        @Override public long millis() { return now.get(); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    }

    @Test
    void rejectsNonPositiveArgs() {
        assertThrows(IllegalArgumentException.class, () -> new TokenRateLimiter(0, 1000, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new TokenRateLimiter(-1, 1000, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new TokenRateLimiter(10, 0, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new TokenRateLimiter(10, -1, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new TokenRateLimiter(10, 1000, null));
    }

    @Test
    void allowsUpToBudgetThenRejects() {
        FakeClock c = new FakeClock(0L);
        TokenRateLimiter lim = new TokenRateLimiter(3, 60_000L, c);
        assertTrue(lim.tryConsume("1.2.3.4"));
        assertTrue(lim.tryConsume("1.2.3.4"));
        assertTrue(lim.tryConsume("1.2.3.4"));
        // 第 4 次超限
        assertFalse(lim.tryConsume("1.2.3.4"));
        assertFalse(lim.tryConsume("1.2.3.4"));
        assertEquals(3, lim.currentCountFor("1.2.3.4"));
    }

    @Test
    void newWindowResetsCount() {
        FakeClock c = new FakeClock(0L);
        TokenRateLimiter lim = new TokenRateLimiter(2, 60_000L, c);
        assertTrue(lim.tryConsume("ip"));
        assertTrue(lim.tryConsume("ip"));
        assertFalse(lim.tryConsume("ip"));  // 桶已满

        // 推进 60s — 进入新窗口
        c.advance(60_000L);
        assertEquals(0, lim.currentCountFor("ip"));
        assertTrue(lim.tryConsume("ip"));
        assertTrue(lim.tryConsume("ip"));
        assertFalse(lim.tryConsume("ip"));
    }

    @Test
    void partialWindowDoesNotReset() {
        FakeClock c = new FakeClock(0L);
        TokenRateLimiter lim = new TokenRateLimiter(2, 60_000L, c);
        assertTrue(lim.tryConsume("ip"));
        c.advance(30_000L);  // 半个窗口
        assertTrue(lim.tryConsume("ip"));
        assertFalse(lim.tryConsume("ip"));  // 仍超限
    }

    @Test
    void ipsAreIsolated() {
        FakeClock c = new FakeClock(0L);
        TokenRateLimiter lim = new TokenRateLimiter(2, 60_000L, c);
        assertTrue(lim.tryConsume("1.1.1.1"));
        assertTrue(lim.tryConsume("1.1.1.1"));
        assertFalse(lim.tryConsume("1.1.1.1"));
        // 另一个 IP 不受影响
        assertTrue(lim.tryConsume("2.2.2.2"));
        assertTrue(lim.tryConsume("2.2.2.2"));
        assertFalse(lim.tryConsume("2.2.2.2"));
    }

    @Test
    void nullAndEmptyIpsShareUnknownBucket() {
        FakeClock c = new FakeClock(0L);
        TokenRateLimiter lim = new TokenRateLimiter(2, 60_000L, c);
        assertTrue(lim.tryConsume(null));
        assertTrue(lim.tryConsume(""));
        // 第三次（同 unknown 桶）超限
        assertFalse(lim.tryConsume(null));
        assertEquals(2, lim.currentCountFor(""));
        assertEquals(2, lim.currentCountFor(null));
    }

    @Test
    void clearForRemovesBucket() {
        FakeClock c = new FakeClock(0L);
        TokenRateLimiter lim = new TokenRateLimiter(2, 60_000L, c);
        assertTrue(lim.tryConsume("ip"));
        assertTrue(lim.tryConsume("ip"));
        lim.clearFor("ip");
        assertEquals(0, lim.currentCountFor("ip"));
        // 清空后又能用
        assertTrue(lim.tryConsume("ip"));
    }

    @Test
    void resetClearsAllBuckets() {
        FakeClock c = new FakeClock(0L);
        TokenRateLimiter lim = new TokenRateLimiter(1, 60_000L, c);
        assertTrue(lim.tryConsume("a"));
        assertTrue(lim.tryConsume("b"));
        lim.reset();
        assertTrue(lim.tryConsume("a"));
        assertTrue(lim.tryConsume("b"));
    }

    @Test
    void currentCountForUnknownIpZero() {
        FakeClock c = new FakeClock(0L);
        TokenRateLimiter lim = new TokenRateLimiter(10, 60_000L, c);
        assertEquals(0, lim.currentCountFor("never-seen"));
    }

    @Test
    void perMinuteOneEdgeCase() {
        FakeClock c = new FakeClock(0L);
        TokenRateLimiter lim = new TokenRateLimiter(1, 60_000L, c);
        assertTrue(lim.tryConsume("ip"));
        assertFalse(lim.tryConsume("ip"));
        c.advance(59_999L);  // 差 1ms 没到窗口结尾
        assertFalse(lim.tryConsume("ip"));
        c.advance(2L);  // 越过窗口
        assertTrue(lim.tryConsume("ip"));
    }

    @Test
    void defaultConstructorUsesDefaults() {
        TokenRateLimiter lim = new TokenRateLimiter();
        assertEquals(TokenRateLimiter.DEFAULT_PER_MINUTE, lim.perMinute());
        assertEquals(TokenRateLimiter.DEFAULT_WINDOW_MS, lim.windowMs());
    }
}
