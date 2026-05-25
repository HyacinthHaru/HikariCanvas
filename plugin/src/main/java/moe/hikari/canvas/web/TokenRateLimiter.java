package moe.hikari.canvas.web;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Token 暴力枚举防御限流器（M16 留下的待办，2026-05-25 实施）。
 *
 * <p><b>定位</b>：和 {@link moe.hikari.canvas.session.SessionRateLimiter}（每会话编辑 op
 * 限流，40/2s）正交。本类针对 <b>WS auth 帧 token 校验前</b> 按 IP 做尝试次数限流，
 * 防御场景：攻击者拿到 sessionId 后循环试随机 token 暴破，或对 reconnect token 撞库。</p>
 *
 * <p><b>算法</b>：固定窗口计数器 per-IP（不是 token bucket）。理由：</p>
 * <ul>
 *   <li>固定窗口实现简单 + 内存 O(activeIp)，并发只持桶内 lock</li>
 *   <li>"10 次/分钟"语义清晰，对合法用户友好（断线 retry 5 次 + 重连 1-2 次远低于阈值）</li>
 *   <li>窗口结尾流量翻倍不是问题：超限即 close，攻击者每分钟只能试 ~20 次（vs 不限流可
 *       每秒上千次）</li>
 * </ul>
 *
 * <p><b>线程模型</b>：{@link ConcurrentHashMap} + 桶内 {@code synchronized}；
 * Jetty WS worker 线程从 {@link WebServer#handleAuth} 主路径调用 {@link #tryConsume}。</p>
 *
 * <p><b>Clock 注入</b>：单测确定性需要操控时间；{@link #TokenRateLimiter(int, long, Clock)}
 * 接受任意 Clock，生产用 {@link Clock#systemUTC()}。</p>
 *
 * <p>契约见 {@code docs/security.md §2.4} token 暴力枚举防御。</p>
 */
public final class TokenRateLimiter {

    /** 默认窗口：60s。 */
    public static final long DEFAULT_WINDOW_MS = 60_000L;
    /** 默认配额：10 次 / 窗口。合法用户 retry/reconnect 远低于此阈值。 */
    public static final int DEFAULT_PER_MINUTE = 10;

    private final int perMinute;
    private final long windowMs;
    private final Clock clock;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {
        long windowStart;
        int count;
    }

    public TokenRateLimiter() {
        this(DEFAULT_PER_MINUTE, DEFAULT_WINDOW_MS, Clock.systemUTC());
    }

    public TokenRateLimiter(int perMinute) {
        this(perMinute, DEFAULT_WINDOW_MS, Clock.systemUTC());
    }

    public TokenRateLimiter(int perMinute, long windowMs, Clock clock) {
        if (perMinute <= 0 || windowMs <= 0) {
            throw new IllegalArgumentException("perMinute / windowMs must be positive");
        }
        if (clock == null) throw new IllegalArgumentException("clock must not be null");
        this.perMinute = perMinute;
        this.windowMs = windowMs;
        this.clock = clock;
    }

    /**
     * 记录一次 token 尝试；返回是否放行。
     *
     * @param ip 来源 IP；null / 空串视作 "unknown"（仍然限流）
     * @return {@code true} = 放行；{@code false} = 超限，调用方应 close 4429 + audit
     */
    public boolean tryConsume(String ip) {
        String key = (ip == null || ip.isEmpty()) ? "unknown" : ip;
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket());
        synchronized (b) {
            long now = clock.millis();
            if (now - b.windowStart >= windowMs) {
                b.windowStart = now;
                b.count = 0;
            }
            if (b.count >= perMinute) return false;
            b.count++;
            return true;
        }
    }

    /** 测试用：当前窗口内已记录的尝试次数（窗口过期返 0）。 */
    public int currentCountFor(String ip) {
        String key = (ip == null || ip.isEmpty()) ? "unknown" : ip;
        Bucket b = buckets.get(key);
        if (b == null) return 0;
        synchronized (b) {
            long now = clock.millis();
            if (now - b.windowStart >= windowMs) return 0;
            return b.count;
        }
    }

    /** 测试 / reload：清空所有桶。 */
    public void reset() {
        buckets.clear();
    }

    /** 测试用：单独清除某 IP 的桶。 */
    public void clearFor(String ip) {
        if (ip == null) return;
        buckets.remove(ip);
    }

    public int perMinute() { return perMinute; }
    public long windowMs() { return windowMs; }
}
