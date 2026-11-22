package ac.haru.hikaricanvas.variable.plugin;

import ac.haru.hikaricanvas.api.NamespaceInfo;
import ac.haru.hikaricanvas.storage.AuditLog;
import ac.haru.hikaricanvas.variable.NoopUserVariableDao;
import ac.haru.hikaricanvas.variable.VariableStore;
import ac.haru.hikaricanvas.variable.provider.VariableProviderDaemon;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static ac.haru.hikaricanvas.variable.plugin.PluginNamespaceRegistryTest.fakePlugin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginCleanupListener} 外部插件卸载回收与宽限期清理单测。
 */
class PluginCleanupListenerTest {

    private VariableStore store;
    private VariableProviderDaemon daemon;
    private PluginNamespaceRegistry registry;
    private HikariCanvasAPIImpl api;
    private RecordingScheduler scheduler;
    private Plugin host;
    private PluginCleanupListener listener;

    private Plugin pluginA;

    @BeforeEach
    void setUp() {
        store = new VariableStore(new NoopUserVariableDao(), w -> { });
        daemon = new VariableProviderDaemon();
        registry = new PluginNamespaceRegistry();
        PushRateLimiter limiter = new PushRateLimiter(PushRateLimiter.Config.unlimited());
        AuditLog auditLog = new AuditLog(null, Logger.getLogger("test"));
        api = new HikariCanvasAPIImpl(registry, store, daemon, limiter, auditLog);
        scheduler = new RecordingScheduler();
        host = fakePlugin("HikariCanvas");
        listener = new PluginCleanupListener(registry, api, host, scheduler);
        pluginA = fakePlugin("PluginA");
    }

    @AfterEach
    void tearDown() {
        daemon.shutdown();
    }

    // ──────────────────────────────────────────────────────────
    //  正常路径
    // ──────────────────────────────────────────────────────────

    @Test
    void onPluginDisable_externalPluginWithNamespaces_removesAndSchedulesPurge() {
        // PluginA 注册 2 namespace + 推 2 个变量
        api.registerNamespace(pluginA, "bedwars", new NamespaceInfo("BW", "PluginA", "1.0"));
        api.registerNamespace(pluginA, "tnt", new NamespaceInfo("TNT", "PluginA", "1.0"));
        api.setVariable(pluginA, "bedwars", "score", "5", null);
        api.setVariable(pluginA, "tnt", "stock", "100", null);
        assertEquals(2, registry.size());
        assertEquals(2, store.size());
        assertEquals(2, api.registeredProviderCount());

        listener.handleDisable(pluginA);

        // 立即效果：registry + provider 都摘了
        assertEquals(0, registry.size());
        assertEquals(0, api.registeredProviderCount());
        // 但 store 数据仍在（30s grace）
        assertEquals(2, store.size());

        // scheduler 收到一条延迟任务
        assertEquals(1, scheduler.scheduledTasks.size());
        RecordingScheduler.ScheduledTask task = scheduler.scheduledTasks.get(0);
        assertEquals(PluginCleanupListener.PURGE_DELAY_TICKS, task.delayTicks);

        // 跑 task 后 store 被清
        task.task.run();
        assertEquals(0, store.size());
    }

    @Test
    void onPluginDisable_externalPluginWithoutNamespaces_skipsSchedule() {
        // PluginA 没 register 过任何 namespace
        listener.handleDisable(pluginA);

        // registry 没动；scheduler 也没收任务
        assertEquals(0, registry.size());
        assertTrue(scheduler.scheduledTasks.isEmpty(),
                "no namespaces removed should mean no scheduled purge");
    }

    // ──────────────────────────────────────────────────────────
    //  自我跳过：HikariCanvas 自己 disable
    // ──────────────────────────────────────────────────────────

    @Test
    void onPluginDisable_hostPluginItself_isSkipped() {
        // 注册一个 namespace（假装是 HikariCanvas 自己用？正常不会发生，但测试 listener 不动）
        api.registerNamespace(pluginA, "ns", new NamespaceInfo("n", "PluginA", "1.0"));
        assertEquals(1, registry.size());

        // 派发的事件的 plugin == host
        listener.handleDisable(host);

        // 啥都没动
        assertEquals(1, registry.size());
        assertEquals(1, api.registeredProviderCount());
        assertTrue(scheduler.scheduledTasks.isEmpty());
    }

    // ──────────────────────────────────────────────────────────
    //  异常隔离
    // ──────────────────────────────────────────────────────────

    @Test
    void onPluginDisable_schedulerThrows_doesNotPropagate() {
        // PluginA register 1 namespace 让 listener 走完到 scheduler.runLater 那步
        api.registerNamespace(pluginA, "ns", new NamespaceInfo("n", "PluginA", "1.0"));
        // 注入一个 schedule 时抛异常的 scheduler；listener 的 outer try/catch 应吞掉它
        PluginCleanupListener.DelayedScheduler throwingScheduler = (delay, task) -> {
            throw new RuntimeException("simulated scheduler failure");
        };
        PluginCleanupListener throwingListener = new PluginCleanupListener(
                registry, api, host, throwingScheduler);

        // 不抛
        throwingListener.handleDisable(pluginA);

        // registry 在 schedule 之前已被移除（同步阶段成功）；scheduler 失败被吞
        assertEquals(0, registry.size(),
                "registry removal happens before schedule; failure in schedule still leaves registry cleared");
    }

    @Test
    void purgeTask_singleNamespaceFailure_othersStillPurged() {
        // 注册 3 namespace；其中 "ns2" 不通过 api.setVariable（让 store 不知道），其他 2 个塞变量
        api.registerNamespace(pluginA, "ns1", new NamespaceInfo("a", "PluginA", "1.0"));
        api.registerNamespace(pluginA, "ns2", new NamespaceInfo("b", "PluginA", "1.0"));
        api.registerNamespace(pluginA, "ns3", new NamespaceInfo("c", "PluginA", "1.0"));
        api.setVariable(pluginA, "ns1", "k", "v1", null);
        api.setVariable(pluginA, "ns3", "k", "v3", null);
        assertEquals(2, store.size());

        // 触发 listener
        listener.handleDisable(pluginA);
        assertEquals(1, scheduler.scheduledTasks.size());

        // 跑 purge task：ns2 没有变量但 purgeNamespaceData 仍是空操作，3 个 namespace 都不应抛
        // （证明 purge 阶段对"namespace 无变量"鲁棒）
        scheduler.scheduledTasks.get(0).task.run();
        assertEquals(0, store.size(),
                "all variables across all 3 namespaces should be purged regardless of size");
    }

    // ──────────────────────────────────────────────────────────
    //  构造 / helper 防御
    // ──────────────────────────────────────────────────────────

    @Test
    void constructor_nullArgs_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> new PluginCleanupListener(null, api, host, scheduler));
        assertThrows(NullPointerException.class,
                () -> new PluginCleanupListener(registry, null, host, scheduler));
        assertThrows(NullPointerException.class,
                () -> new PluginCleanupListener(registry, api, null, scheduler));
        assertThrows(NullPointerException.class,
                () -> new PluginCleanupListener(registry, api, host, null));
    }

    @Test
    void bukkitAsyncScheduler_nullHost_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> PluginCleanupListener.bukkitAsyncScheduler(null));
    }

    // ──────────────────────────────────────────────────────────
    //  Recording scheduler — 测试 seam
    // ──────────────────────────────────────────────────────────

    /**
     * 抓 scheduler 调用：记录所有 schedule 请求；不真跑，让测试手动调 task.task.run()。
     */
    static final class RecordingScheduler implements PluginCleanupListener.DelayedScheduler {
        final List<ScheduledTask> scheduledTasks = new ArrayList<>();

        @Override
        public void runLater(long delayTicks, Runnable task) {
            scheduledTasks.add(new ScheduledTask(delayTicks, task));
        }

        record ScheduledTask(long delayTicks, Runnable task) {}
    }
}
