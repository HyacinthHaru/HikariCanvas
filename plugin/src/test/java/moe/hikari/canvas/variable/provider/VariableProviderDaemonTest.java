package moe.hikari.canvas.variable.provider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-P1-E：VariableProviderDaemon 框架单测。
 *
 * <p>用 mock {@link FakeProvider} 验证：注册流程、调度、异常隔离、unregister 取消任务、
 * shutdown 全清。守护线程池，所有 schedule task 在 daemon shutdown 后被 awaitTermination
 * 清掉，单测跑 &lt; 1s。</p>
 */
class VariableProviderDaemonTest {

    private VariableProviderDaemon daemon;

    @BeforeEach
    void setUp() {
        daemon = new VariableProviderDaemon();
    }

    @AfterEach
    void tearDown() {
        if (daemon != null && !daemon.isShutdown()) {
            daemon.shutdown();
        }
    }

    // ──────────────────────────────────────────────────────────
    //  1. register 成功路径
    // ──────────────────────────────────────────────────────────

    @Test
    void register_callsInitializeAndAddsToRegistry() {
        FakeProvider p = new FakeProvider("system", Duration.ZERO); // 不调度
        daemon.register(p);

        assertEquals(1, p.initializeCount.get(), "initialize 应被调用 1 次");
        List<VariableProvider> all = daemon.registeredProviders();
        assertEquals(1, all.size());
        assertSame(p, all.get(0));
    }

    // ──────────────────────────────────────────────────────────
    //  2. 同 namespace 重复 register 抛 IllegalStateException
    // ──────────────────────────────────────────────────────────

    @Test
    void register_duplicateNamespace_throws() {
        daemon.register(new FakeProvider("dup", Duration.ZERO));
        FakeProvider second = new FakeProvider("dup", Duration.ZERO);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> daemon.register(second));
        assertTrue(ex.getMessage().contains("dup"),
                "异常 message 应含 namespace 名");
        // 第二个 provider 的 initialize 不能被调到（短路在 putIfAbsent 阶段）
        assertEquals(0, second.initializeCount.get());
    }

    // ──────────────────────────────────────────────────────────
    //  3. initialize 抛异常 → 不注册（不传播）
    // ──────────────────────────────────────────────────────────

    @Test
    void register_initializeThrows_doesNotRegister() {
        FakeProvider p = new FakeProvider("bad", Duration.ZERO);
        p.initializeThrows = true;

        // 不应传播
        daemon.register(p);

        assertTrue(daemon.registeredProviders().isEmpty(),
                "initialize 抛异常 → provider 应被回滚");
        // 但 initialize 本身被调用过
        assertEquals(1, p.initializeCount.get());
    }

    // ──────────────────────────────────────────────────────────
    //  4. 定时 refresh 真的被调用（interval > 0）
    // ──────────────────────────────────────────────────────────

    @Test
    void register_schedulesRefresh() throws InterruptedException {
        // interval 100ms，等 350ms 应 fire ≥ 2 次
        CountDownLatch latch = new CountDownLatch(2);
        FakeProvider p = new FakeProvider("ticker", Duration.ofMillis(100));
        p.onRefresh = () -> latch.countDown();

        daemon.register(p);

        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "refresh 应在 2s 内至少 fire 2 次");
        assertTrue(p.refreshCount.get() >= 2,
                "refreshCount 应 ≥ 2 (got " + p.refreshCount.get() + ")");
    }

    // ──────────────────────────────────────────────────────────
    //  5. refresh 抛异常 → 不停止后续调度
    // ──────────────────────────────────────────────────────────

    @Test
    void refresh_throwsException_continuesScheduling() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        FakeProvider p = new FakeProvider("flaky", Duration.ofMillis(50));
        p.onRefresh = () -> {
            int n = callCount.incrementAndGet();
            if (n <= 2) throw new RuntimeException("simulated refresh failure #" + n);
        };

        daemon.register(p);
        // 等 ≥ 5 个 tick (250ms+)；前 2 个抛，后续应该继续跑
        Thread.sleep(400);

        assertTrue(callCount.get() >= 4,
                "前 2 次抛异常后调度仍应继续 (got " + callCount.get() + ")");
    }

    // ──────────────────────────────────────────────────────────
    //  6. unregister 调用 shutdown() + 取消任务
    // ──────────────────────────────────────────────────────────

    @Test
    void unregister_callsShutdownAndCancelsTask() throws InterruptedException {
        FakeProvider p = new FakeProvider("temp", Duration.ofMillis(50));
        daemon.register(p);

        // 让它跑一会儿
        Thread.sleep(120);
        int countBefore = p.refreshCount.get();
        assertTrue(countBefore >= 1, "unregister 前应至少 fire 1 次");

        daemon.unregister("temp");
        assertEquals(1, p.shutdownCount.get(), "shutdown 应被调用 1 次");
        assertTrue(daemon.registeredProviders().isEmpty());

        // 任务取消后 refreshCount 不应继续增加
        Thread.sleep(200);
        int countAfter = p.refreshCount.get();
        assertTrue(countAfter - countBefore <= 1,
                "unregister 后 refresh 应停止 (before=" + countBefore + " after=" + countAfter + ")");
    }

    // ──────────────────────────────────────────────────────────
    //  7. unregister 未知 namespace 静默返回
    // ──────────────────────────────────────────────────────────

    @Test
    void unregister_unknownNamespace_isNoop() {
        // 不抛
        daemon.unregister("nonexistent");
        assertTrue(daemon.registeredProviders().isEmpty());
    }

    // ──────────────────────────────────────────────────────────
    //  8. shutdown 全部 unregister + scheduler 关闭
    // ──────────────────────────────────────────────────────────

    @Test
    void shutdown_unregistersAllAndClosesScheduler() {
        FakeProvider a = new FakeProvider("a", Duration.ofMillis(100));
        FakeProvider b = new FakeProvider("b", Duration.ofMillis(100));
        FakeProvider c = new FakeProvider("c", Duration.ZERO); // 不调度
        daemon.register(a);
        daemon.register(b);
        daemon.register(c);
        assertEquals(3, daemon.registeredProviders().size());

        daemon.shutdown();

        assertTrue(daemon.isShutdown());
        assertTrue(daemon.registeredProviders().isEmpty());
        assertEquals(1, a.shutdownCount.get());
        assertEquals(1, b.shutdownCount.get());
        assertEquals(1, c.shutdownCount.get());
    }

    // ──────────────────────────────────────────────────────────
    //  9. shutdown 幂等 + shutdown 后 register 抛
    // ──────────────────────────────────────────────────────────

    @Test
    void shutdown_isIdempotent_andRejectsLaterRegister() {
        FakeProvider p = new FakeProvider("x", Duration.ZERO);
        daemon.register(p);

        daemon.shutdown();
        daemon.shutdown(); // 第二次幂等
        assertEquals(1, p.shutdownCount.get(),
                "重复 shutdown 不应重复调 provider.shutdown");

        // shutdown 后 register 应抛
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> daemon.register(new FakeProvider("late", Duration.ZERO)));
        assertTrue(ex.getMessage().contains("shut down"));
    }

    // ──────────────────────────────────────────────────────────
    //  10. provider.shutdown() 抛异常被 daemon 吞掉
    // ──────────────────────────────────────────────────────────

    @Test
    void unregister_providerShutdownThrows_isSwallowed() {
        FakeProvider p = new FakeProvider("crash", Duration.ZERO);
        p.shutdownThrows = true;
        daemon.register(p);

        // 不应传播
        daemon.unregister("crash");
        assertEquals(1, p.shutdownCount.get());
        assertTrue(daemon.registeredProviders().isEmpty());
    }

    // ──────────────────────────────────────────────────────────
    //  Test fixture
    // ──────────────────────────────────────────────────────────

    /** 内联 mock VariableProvider：计数器 + 异常注入 + 自定义 onRefresh 回调。 */
    private static final class FakeProvider implements VariableProvider {
        final String namespace;
        final Duration interval;
        final AtomicInteger initializeCount = new AtomicInteger();
        final AtomicInteger refreshCount = new AtomicInteger();
        final AtomicInteger shutdownCount = new AtomicInteger();
        volatile boolean initializeThrows = false;
        volatile boolean shutdownThrows = false;
        volatile Runnable onRefresh = null;

        FakeProvider(String namespace, Duration interval) {
            this.namespace = namespace;
            this.interval = interval;
        }

        @Override public String namespace() { return namespace; }
        @Override public String displayName() { return "Fake " + namespace; }
        @Override public Duration refreshInterval() { return interval; }

        @Override
        public void initialize() {
            initializeCount.incrementAndGet();
            if (initializeThrows) {
                throw new RuntimeException("simulated initialize failure");
            }
        }

        @Override
        public boolean refresh() {
            refreshCount.incrementAndGet();
            Runnable cb = onRefresh;
            if (cb != null) cb.run();
            return true;
        }

        @Override
        public void shutdown() {
            shutdownCount.incrementAndGet();
            if (shutdownThrows) {
                throw new RuntimeException("simulated shutdown failure");
            }
        }
    }
}
