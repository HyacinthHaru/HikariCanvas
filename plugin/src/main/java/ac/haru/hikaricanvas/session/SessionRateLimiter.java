package ac.haru.hikaricanvas.session;

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
 * <p>基本 40/2s 滑窗（固定窗口计数器）。"反复超限 → 断连"：窗内连续被拒达
 * {@code violationThreshold} 次 / {@code violationWindowMs} 即触发注入的
 * {@code onRepeatedViolation} 回调（由 WebServer 关连接 close 1008）。协议 §9 契约。</p>
 *
 * <p>{@code ping} / {@code ack} 不计入限流（调用方自行控制传什么）。</p>
 */
public final class SessionRateLimiter {

    public static final int DEFAULT_BURST = 40;
    public static final long DEFAULT_WINDOW_MS = 2000L;
    public static final int DEFAULT_VIOLATION_THRESHOLD = 5;
    public static final long DEFAULT_VIOLATION_WINDOW_MS = 60_000L;

    private final int burst;
    private final long windowMs;
    private final int violationThreshold;
    private final long violationWindowMs;
    private volatile java.util.function.Consumer<String> onRepeatedViolation = sid -> {};
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {
        long windowStart;
        int count;
        // 反复超限计数（独立 60s 窗口，与突发窗口分离）
        long violationWindowStart;
        int violationCount;
        boolean violationReported;
    }

    public SessionRateLimiter() {
        this(DEFAULT_BURST, DEFAULT_WINDOW_MS);
    }

    public SessionRateLimiter(int burst, long windowMs) {
        this(burst, windowMs, DEFAULT_VIOLATION_THRESHOLD, DEFAULT_VIOLATION_WINDOW_MS);
    }

    public SessionRateLimiter(int burst, long windowMs, int violationThreshold, long violationWindowMs) {
        if (burst <= 0 || windowMs <= 0) {
            throw new IllegalArgumentException("burst/windowMs must be positive");
        }
        if (violationThreshold <= 0 || violationWindowMs <= 0) {
            throw new IllegalArgumentException("violationThreshold/violationWindowMs must be positive");
        }
        this.burst = burst;
        this.windowMs = windowMs;
        this.violationThreshold = violationThreshold;
        this.violationWindowMs = violationWindowMs;
    }

    /**
     * 注册"会话反复超限"回调。某会话在 {@code violationWindowMs} 内累计
     * {@code violationThreshold} 次 {@code allow()} 拒绝时触发一次（同窗口内不重复），
     * 入参是 sessionId；回调应主动关该 WS 连接（close 1008）。
     * 默认 no-op，旧测试 / 未接线路径不受影响。{@code null} 视作 no-op。
     */
    public void setOnRepeatedViolation(java.util.function.Consumer<String> hook) {
        this.onRepeatedViolation = (hook == null) ? sid -> {} : hook;
    }

    /**
     * @return {@code true} = 允许本次 op；{@code false} = 超限，应返回 {@code RATE_LIMITED}
     */
    public boolean allow(String sessionId) {
        Bucket b = buckets.computeIfAbsent(sessionId, k -> new Bucket());
        boolean allowed;
        boolean fireViolation = false;
        synchronized (b) {
            long now = System.currentTimeMillis();
            if (now - b.windowStart >= windowMs) {
                b.windowStart = now;
                b.count = 0;
            }
            if (b.count >= burst) {
                allowed = false;
                // 本次拒绝计入反复超限窗口（独立 violationWindowMs）
                if (now - b.violationWindowStart >= violationWindowMs) {
                    b.violationWindowStart = now;
                    b.violationCount = 0;
                    b.violationReported = false;
                }
                b.violationCount++;
                if (b.violationCount >= violationThreshold && !b.violationReported) {
                    b.violationReported = true;
                    fireViolation = true;
                }
            } else {
                b.count++;
                allowed = true;
            }
        }
        // 回调在锁外触发：它会回到 WebServer 关连接，持 bucket 锁回调有重入 / 死锁风险
        if (fireViolation) {
            onRepeatedViolation.accept(sessionId);
        }
        return allowed;
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
