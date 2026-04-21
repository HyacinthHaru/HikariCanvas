package moe.hikari.canvas.session;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 每会话编辑 op 输入限流。契约见 {@code docs/protocol.md §9}：
 *
 * <table>
 *   <tr><th>维度</th><th>阈值</th></tr>
 *   <tr><td>单会话突发窗口</td><td>40 msg / 2s</td></tr>
 *   <tr><td>折合平均速率</td><td>≈ 20 msg/s</td></tr>
 * </table>
 *
 * <p>M3-T10 实装基本 40/2s 滑窗（固定窗口计数器，简单可靠）。
 * 协议 §9 的"重复触发 5 次 / 1min → close 1008"留 M7 polish。</p>
 *
 * <p>{@code ping} / {@code ack} 不计入限流（调用方自行控制传什么）。</p>
 */
public final class SessionRateLimiter {

    public static final int DEFAULT_BURST = 40;
    public static final long DEFAULT_WINDOW_MS = 2000L;

    private final int burst;
    private final long windowMs;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {
        long windowStart;
        int count;
    }

    public SessionRateLimiter() {
        this(DEFAULT_BURST, DEFAULT_WINDOW_MS);
    }

    public SessionRateLimiter(int burst, long windowMs) {
        if (burst <= 0 || windowMs <= 0) {
            throw new IllegalArgumentException("burst/windowMs must be positive");
        }
        this.burst = burst;
        this.windowMs = windowMs;
    }

    /**
     * @return {@code true} = 允许本次 op；{@code false} = 超限，应返回 {@code RATE_LIMITED}
     */
    public boolean allow(String sessionId) {
        Bucket b = buckets.computeIfAbsent(sessionId, k -> new Bucket());
        synchronized (b) {
            long now = System.currentTimeMillis();
            if (now - b.windowStart >= windowMs) {
                b.windowStart = now;
                b.count = 0;
            }
            if (b.count >= burst) return false;
            b.count++;
            return true;
        }
    }

    public void discardSession(String sessionId) {
        buckets.remove(sessionId);
    }

    public int windowCountFor(String sessionId) {
        Bucket b = buckets.get(sessionId);
        if (b == null) return 0;
        synchronized (b) {
            long now = System.currentTimeMillis();
            if (now - b.windowStart >= windowMs) return 0;
            return b.count;
        }
    }
}
