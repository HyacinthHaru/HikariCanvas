package ac.haru.hikaricanvas.variable.plugin;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static ac.haru.hikaricanvas.variable.plugin.PluginNamespaceRegistryTest.fakePlugin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-P4-P：{@link PushRateLimiter} 单测。
 *
 * <p>用注入的虚拟时钟（{@link AtomicLong}）控制"当前时间"，
 * 避免 {@code Thread.sleep} 与窗口边界 race。</p>
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>单次 tryAcquire allow</li>
 *   <li>per-plugin 100/s 边界（第 101 次 drop）</li>
 *   <li>跨秒自动重置</li>
 *   <li>全局 1000/s → 触发 circuit break</li>
 *   <li>circuit break 期内 reject 全部插件</li>
 *   <li>circuit break 过期后恢复 allow</li>
 *   <li>批量 tryAcquireBatch 计费</li>
 *   <li>批量超 per-plugin → 全 reject</li>
 *   <li>多 plugin 隔离（A 满，B 仍 allow，受限于全局上限）</li>
 *   <li>currentPluginCount / currentGlobalCount / isGlobalCircuitBroken</li>
 *   <li>Config 校验非法参数</li>
 *   <li>非法 count（0 / 负数）抛 IAE</li>
 *   <li>resetForTest 清零</li>
 * </ul>
 */
class PushRateLimiterTest {

    /** 注入 AtomicLong 当虚拟时钟 — 不用 Thread.sleep。 */
    private static AtomicLong clock(long initialMs) {
        return new AtomicLong(initialMs);
    }

    private static PushRateLimiter newLimiter(PushRateLimiter.Config c, AtomicLong clock) {
        return new PushRateLimiter(c, clock::get);
    }

    // ──────────────────────────────────────────────────────────
    //  Single tryAcquire
    // ──────────────────────────────────────────────────────────

    @Test
    void tryAcquire_singleCall_allow() {
        AtomicLong c = clock(1_000_000L);
        PushRateLimiter limiter = newLimiter(PushRateLimiter.Config.defaults(), c);
        Plugin p = fakePlugin("Foo");

        assertTrue(limiter.tryAcquire(p));
        assertEquals(1, limiter.currentPluginCount("Foo"));
        assertEquals(1, limiter.currentGlobalCount());
        assertFalse(limiter.isGlobalCircuitBroken());
    }

    @Test
    void tryAcquire_perPluginBoundary_dropAt101() {
        AtomicLong c = clock(1_000_000L);
        // global 设到足够大，避免污染 per-plugin 边界判断
        PushRateLimiter limiter = newLimiter(new PushRateLimiter.Config(100, 100_000, 10_000L), c);
        Plugin p = fakePlugin("Foo");

        for (int i = 1; i <= 100; i++) {
            assertTrue(limiter.tryAcquire(p), "call #" + i + " should be allowed");
        }
        // 101st → drop
        assertFalse(limiter.tryAcquire(p));
        // 后续也 drop（同窗口）
        assertFalse(limiter.tryAcquire(p));
    }

    @Test
    void tryAcquire_crossSecondReset() {
        AtomicLong c = clock(1_000_000L);
        PushRateLimiter limiter = newLimiter(new PushRateLimiter.Config(100, 100_000, 10_000L), c);
        Plugin p = fakePlugin("Foo");

        // 灌满
        for (int i = 0; i < 100; i++) limiter.tryAcquire(p);
        assertFalse(limiter.tryAcquire(p));

        // 时钟前进 1s → 新窗口
        c.addAndGet(1_000L);
        assertTrue(limiter.tryAcquire(p), "new window should allow again");
        assertEquals(1, limiter.currentPluginCount("Foo"));
    }

    // ──────────────────────────────────────────────────────────
    //  Global circuit break
    // ──────────────────────────────────────────────────────────

    @Test
    void global_exceedsLimit_triggersCircuitBreak() {
        AtomicLong c = clock(1_000_000L);
        // 全局 1000；per-plugin 设大避免先 drop
        PushRateLimiter limiter = newLimiter(new PushRateLimiter.Config(100_000, 1000, 10_000L), c);
        Plugin p = fakePlugin("Foo");

        // 用 batch 一次到位省循环
        assertTrue(limiter.tryAcquireBatch(p, 1000));
        assertFalse(limiter.isGlobalCircuitBroken());
        // 1001st → 触发
        assertFalse(limiter.tryAcquire(p));
        assertTrue(limiter.isGlobalCircuitBroken());
    }

    @Test
    void global_circuitBreak_rejectsAllPlugins() {
        AtomicLong c = clock(1_000_000L);
        PushRateLimiter limiter = newLimiter(new PushRateLimiter.Config(100_000, 1000, 10_000L), c);
        Plugin a = fakePlugin("PluginA");
        Plugin b = fakePlugin("PluginB");

        // A 单独把全局打爆
        assertTrue(limiter.tryAcquireBatch(a, 1000));
        assertFalse(limiter.tryAcquire(a));
        assertTrue(limiter.isGlobalCircuitBroken());

        // B 也被一起 reject（保护期内全 plugin 拒绝）
        assertFalse(limiter.tryAcquire(b));
    }

    @Test
    void global_circuitBreak_clearsAfterTimeout() {
        AtomicLong c = clock(1_000_000L);
        PushRateLimiter limiter = newLimiter(new PushRateLimiter.Config(100_000, 1000, 10_000L), c);
        Plugin p = fakePlugin("Foo");

        // 打爆 + 触发
        assertTrue(limiter.tryAcquireBatch(p, 1000));
        assertFalse(limiter.tryAcquire(p));
        assertTrue(limiter.isGlobalCircuitBroken());

        // 时钟前进 9.999s → 仍在保护期
        c.addAndGet(9_999L);
        assertFalse(limiter.tryAcquire(p));
        assertTrue(limiter.isGlobalCircuitBroken());

        // 时钟前进至 10_000ms 边界后（now >= breakUntil → 离开保护期）
        c.addAndGet(2L);
        assertFalse(limiter.isGlobalCircuitBroken());
        assertTrue(limiter.tryAcquire(p), "recovered after circuit break window");
    }

    // ──────────────────────────────────────────────────────────
    //  Batch
    // ──────────────────────────────────────────────────────────

    @Test
    void tryAcquireBatch_50x5_thenSixthRejected() {
        AtomicLong c = clock(1_000_000L);
        // per-plugin 100；5 次 50 一共 250 → 第一次 50 后已经 50；第二次 50 后 100；第三次 50 → 150 超 → reject
        PushRateLimiter limiter = newLimiter(new PushRateLimiter.Config(100, 100_000, 10_000L), c);
        Plugin p = fakePlugin("Foo");

        assertTrue(limiter.tryAcquireBatch(p, 50));   // 50
        assertTrue(limiter.tryAcquireBatch(p, 50));   // 100
        assertFalse(limiter.tryAcquireBatch(p, 50));  // 150 > 100 → reject
    }

    @Test
    void tryAcquireBatch_exceedsPerPlugin_allOrNothingFromCallerView() {
        AtomicLong c = clock(1_000_000L);
        PushRateLimiter limiter = newLimiter(new PushRateLimiter.Config(100, 100_000, 10_000L), c);
        Plugin p = fakePlugin("Foo");

        // 单次 batch 直接超 — false（caller drop 整批）
        assertFalse(limiter.tryAcquireBatch(p, 200));
        // 之后即使 1 次也 drop（同窗口 count 已被 add 进去）
        assertFalse(limiter.tryAcquire(p));
        // 新窗口恢复
        c.addAndGet(1_000L);
        assertTrue(limiter.tryAcquire(p));
    }

    @Test
    void tryAcquireBatch_invalidCount_throws() {
        AtomicLong c = clock(1_000_000L);
        PushRateLimiter limiter = newLimiter(PushRateLimiter.Config.defaults(), c);
        Plugin p = fakePlugin("Foo");
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquireBatch(p, 0));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquireBatch(p, -1));
    }

    // ──────────────────────────────────────────────────────────
    //  Per-plugin isolation
    // ──────────────────────────────────────────────────────────

    @Test
    void multiPlugin_isolation_globalAccumulates() {
        AtomicLong c = clock(1_000_000L);
        // per-plugin 100；global 100_000 → A 占满 100，B 仍能各自 100
        PushRateLimiter limiter = newLimiter(new PushRateLimiter.Config(100, 100_000, 10_000L), c);
        Plugin a = fakePlugin("PluginA");
        Plugin b = fakePlugin("PluginB");

        for (int i = 0; i < 100; i++) assertTrue(limiter.tryAcquire(a));
        assertFalse(limiter.tryAcquire(a), "A is full");

        // B 不受 A 影响
        for (int i = 0; i < 100; i++) assertTrue(limiter.tryAcquire(b), "B call #" + i);
        assertFalse(limiter.tryAcquire(b));

        // fixed-window 语义：reject 的尝试 add 仍写入 count（无 rollback），
        // 所以 A=101（100 allow + 1 reject）/ B=101（同上）。
        assertEquals(101, limiter.currentPluginCount("PluginA"));
        assertEquals(101, limiter.currentPluginCount("PluginB"));
        // global = 101 + 101 = 202（远未到 100_000）
        assertEquals(202, limiter.currentGlobalCount());
        assertFalse(limiter.isGlobalCircuitBroken());
    }

    // ──────────────────────────────────────────────────────────
    //  Config / null
    // ──────────────────────────────────────────────────────────

    @Test
    void config_invalidArgs_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new PushRateLimiter.Config(0, 1000, 10_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new PushRateLimiter.Config(-1, 1000, 10_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new PushRateLimiter.Config(100, 0, 10_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new PushRateLimiter.Config(100, 1000, -1L));
    }

    @Test
    void config_defaults_matchSpec() {
        PushRateLimiter.Config d = PushRateLimiter.Config.defaults();
        assertEquals(100, d.perPluginPerSecond());
        assertEquals(1000, d.globalPerSecond());
        assertEquals(10_000L, d.globalCircuitBreakMs());
    }

    @Test
    void config_unlimited_alwaysAllows() {
        AtomicLong c = clock(1_000_000L);
        PushRateLimiter limiter = newLimiter(PushRateLimiter.Config.unlimited(), c);
        Plugin p = fakePlugin("Foo");
        // 10_000 次任意调 — 全 allow（per-plugin = Integer.MAX_VALUE）
        for (int i = 0; i < 10_000; i++) assertTrue(limiter.tryAcquire(p));
        assertFalse(limiter.isGlobalCircuitBroken());
    }

    @Test
    void resetForTest_clearsEverything() {
        AtomicLong c = clock(1_000_000L);
        // 用 per-plugin 100_000 让 batch(1001) 跳过 per-plugin 限制，触发 global circuit break
        PushRateLimiter limiter = newLimiter(new PushRateLimiter.Config(100_000, 1000, 10_000L), c);
        Plugin p = fakePlugin("Foo");
        limiter.tryAcquireBatch(p, 1001);  // 触发 global circuit break（1001 > 1000）
        assertTrue(limiter.isGlobalCircuitBroken());

        limiter.resetForTest();
        assertEquals(0, limiter.currentPluginCount("Foo"));
        assertEquals(0, limiter.currentGlobalCount());
        assertFalse(limiter.isGlobalCircuitBroken());
        // reset 后能继续工作
        assertTrue(limiter.tryAcquire(p));
    }

    @Test
    void tryAcquire_nullPlugin_throws() {
        AtomicLong c = clock(1_000_000L);
        PushRateLimiter limiter = newLimiter(PushRateLimiter.Config.defaults(), c);
        assertThrows(NullPointerException.class, () -> limiter.tryAcquire(null));
        assertThrows(NullPointerException.class, () -> limiter.tryAcquireBatch(null, 1));
    }
}
