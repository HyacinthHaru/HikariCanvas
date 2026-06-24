package moe.hikari.canvas.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.3 Task 3：{@link SessionRateLimiter} 反复超限回调语义单测。
 *
 * <p>用低阈值（burst=2 / window=10s / violationThreshold=3 / violationWindow=60s）+
 * 捕获回调入参的方式断言：跨阈值触发一次、同窗口内不重复触发、会话间互不影响、
 * allow() 返回值不受影响。窗口靠时钟过期的复位路径不在此单测（limiter 无时钟 seam，
 * 与既有实现一致），交由人工 / 集成验证。</p>
 */
class SessionRateLimiterTest {

    private SessionRateLimiter newLimiter(List<String> fired) {
        SessionRateLimiter l = new SessionRateLimiter(2, 10_000L, 3, 60_000L);
        l.setOnRepeatedViolation(fired::add);
        return l;
    }

    @Test
    void firesOnceWhenViolationsReachThreshold() {
        List<String> fired = new ArrayList<>();
        SessionRateLimiter l = newLimiter(fired);

        assertTrue(l.allow("s1"), "第 1 次允许");
        assertTrue(l.allow("s1"), "第 2 次允许");
        assertFalse(l.allow("s1"), "第 3 次拒绝（violation #1）");
        assertTrue(fired.isEmpty(), "未到阈值不触发: " + fired);
        assertFalse(l.allow("s1"), "第 4 次拒绝（violation #2）");
        assertTrue(fired.isEmpty(), "仍未到阈值: " + fired);
        assertFalse(l.allow("s1"), "第 5 次拒绝（violation #3 = 阈值）");

        assertEquals(List.of("s1"), fired, "到阈值触发一次，入参是 sessionId");
    }

    @Test
    void doesNotRefireAfterThreshold() {
        List<String> fired = new ArrayList<>();
        SessionRateLimiter l = newLimiter(fired);
        // 打满到触发
        l.allow("s1"); l.allow("s1");
        l.allow("s1"); l.allow("s1"); l.allow("s1");
        assertEquals(1, fired.size(), "首次触发");
        // 继续超限不应重复触发（同一 violation 窗口内只报一次）
        l.allow("s1"); l.allow("s1"); l.allow("s1");
        assertEquals(1, fired.size(), "同窗口内不重复触发: " + fired);
    }

    @Test
    void sessionsAreIsolated() {
        List<String> fired = new ArrayList<>();
        SessionRateLimiter l = newLimiter(fired);
        // s1 打到触发，s2 只用掉 burst 不超限
        l.allow("s1"); l.allow("s1");
        l.allow("s1"); l.allow("s1"); l.allow("s1");
        assertTrue(l.allow("s2"), "s2 独立计数，第 1 次允许");
        assertTrue(l.allow("s2"), "s2 第 2 次允许");
        assertEquals(List.of("s1"), fired, "只有 s1 触发: " + fired);
    }

    @Test
    void nullHookIsSafe() {
        SessionRateLimiter l = new SessionRateLimiter(2, 10_000L, 3, 60_000L);
        l.setOnRepeatedViolation(null); // 不应 NPE；默认 no-op
        l.allow("s1"); l.allow("s1");
        l.allow("s1"); l.allow("s1"); l.allow("s1"); // 触发但回调是 no-op
        // 没崩即通过
        assertEquals(0, l.windowCountFor("s1") < 0 ? 1 : 0, "占位断言：无异常");
    }
}
