package ac.haru.hikaricanvas.variable.plugin;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PushRateLimiter} 内部 1s 固定窗口计数器的语义。
 *
 * <p>窗口计数以前是两个独立 AtomicLong：先 CAS 换 windowSec、赢家 {@code count.set(0)}，
 * 输家直接 {@code count.addAndGet}。javadoc 写着「输家的 add 落到新 window，无丢失」，
 * 实际交错是输家先 add、赢家随后 set(0) 把它抹掉 —— 并发性质与注释相反。
 * 现在 {@code (windowSec, count)} 打包进一个 AtomicLong 走单次 CAS，
 * 窗口切换与计数是同一个原子动作。</p>
 *
 * <p><b>说明：</b>「窗口切换那一瞬的丢失」本身是个调度竞态，没法写成稳定复现的用例；
 * 这里守的是可确定断言的部分 —— 同窗口内高并发不丢更新、跨窗口正确归零、饱和不回绕。
 * 无丢失性质本身由「单个 CAS」的实现形态保证，可由代码审阅确认。</p>
 */
class PushRateLimiterWindowTest {

    /** 只用到 {@code getName()}，不拉 Bukkit 全装配。 */
    private static org.bukkit.plugin.Plugin fakePlugin(String name) {
        return (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                PushRateLimiterWindowTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.plugin.Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "toString" -> "FakePlugin(" + name + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static PushRateLimiter limiter(AtomicLong clock, int perPlugin, int global) {
        return new PushRateLimiter(
                new PushRateLimiter.Config(perPlugin, global, 10_000L), clock::get);
    }

    @Test
    void countsAccumulateWithinSameWindow() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        PushRateLimiter rl = limiter(clock, 100, 1000);
        var p = fakePlugin("Alpha");

        for (int i = 0; i < 10; i++) assertTrue(rl.tryAcquire(p));
        assertEquals(10, rl.currentPluginCount("Alpha"));
        assertEquals(10, rl.currentGlobalCount());
    }

    @Test
    void countsResetOnNewWindow() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        PushRateLimiter rl = limiter(clock, 100, 1000);
        var p = fakePlugin("Alpha");

        for (int i = 0; i < 10; i++) rl.tryAcquire(p);
        clock.addAndGet(1000L);   // 进入下一秒
        assertEquals(0, rl.currentPluginCount("Alpha"), "跨窗口应归零");
        assertEquals(0, rl.currentGlobalCount());

        rl.tryAcquire(p);
        assertEquals(1, rl.currentPluginCount("Alpha"), "新窗口从 delta 起算，不叠加旧值");
    }

    /**
     * 同一窗口内高并发不丢更新：40 线程各 25 次，计数必须精确等于 1000。
     * 计数器一旦退化成「读-改-写」而非原子累加，这条会红。
     */
    @Test
    void concurrentAcquiresWithinOneWindow_loseNothing() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        PushRateLimiter rl = limiter(clock, 100_000, 100_000);
        var p = fakePlugin("Alpha");

        int threads = 40;
        int perThread = 25;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger granted = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (rl.tryAcquire(p)) granted.incrementAndGet();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            th.setDaemon(true);
            th.start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发用例超时");

        assertEquals(threads * perThread, granted.get(), "配额充足时不该有拒绝");
        assertEquals(threads * perThread, rl.currentPluginCount("Alpha"),
                "同窗口内的计数必须一条不丢");
        assertEquals(threads * perThread, rl.currentGlobalCount());
    }

    /** 计数饱和到 Integer.MAX_VALUE，不回绕成负数（负计数会让限流器整个失效）。 */
    @Test
    void countSaturatesInsteadOfWrapping() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        PushRateLimiter rl = limiter(clock, Integer.MAX_VALUE, Integer.MAX_VALUE);
        var p = fakePlugin("Alpha");

        for (int i = 0; i < 3; i++) rl.tryAcquireBatch(p, Integer.MAX_VALUE - 1);
        assertTrue(rl.currentPluginCount("Alpha") > 0,
                "计数不能回绕成负数，实际 " + rl.currentPluginCount("Alpha"));
        assertTrue(rl.currentGlobalCount() > 0);
    }
}
