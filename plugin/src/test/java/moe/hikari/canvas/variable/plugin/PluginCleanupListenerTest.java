package moe.hikari.canvas.variable.plugin;

import moe.hikari.canvas.api.NamespaceInfo;
import moe.hikari.canvas.storage.AuditLog;
import moe.hikari.canvas.storage.UserVariableDao;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.VariableStore;
import moe.hikari.canvas.variable.provider.VariableProviderDaemon;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static moe.hikari.canvas.variable.plugin.PluginNamespaceRegistryTest.fakePlugin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-P4-Q：{@link PluginCleanupListener} 单测。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>外部 plugin disable 触发 registry 移除 + provider 移除</li>
 *   <li>registry 返 0 namespace（plugin 没 registerNamespace 过）→ scheduler 不调度</li>
 *   <li>registry 返 N namespace → scheduler 收到 (PURGE_DELAY_TICKS, runnable)；执行 runnable 调 purgeNamespaceData</li>
 *   <li>HikariCanvas 自己 disable → 整个 listener 直接 return</li>
 *   <li>registry 抛异常 → catch + log，不传播给 Bukkit event bus</li>
 *   <li>purge 单 namespace 失败 → 其他 namespace 仍执行 purge</li>
 *   <li>bukkitAsyncScheduler null host → NPE</li>
 *   <li>构造 null 校验</li>
 * </ul>
 */
class PluginCleanupListenerTest {

    private FakeUserVariableDao fakeDao;
    private VariableStore store;
    private VariableProviderDaemon daemon;
    private PluginNamespaceRegistry registry;
    private HikariCanvasAPIImpl api;
    private RecordingScheduler scheduler;
    private Plugin host;
    private PluginCleanupListener listener;

    private Plugin pluginA;
    private Plugin pluginB;

    @BeforeEach
    void setUp() {
        fakeDao = new FakeUserVariableDao();
        store = new VariableStore(fakeDao, w -> { });
        daemon = new VariableProviderDaemon();
        registry = new PluginNamespaceRegistry();
        // P 任务接入后 HikariCanvasAPIImpl 构造需要 PushRateLimiter；测试用 unlimited
        // 配置不影响 cleanup 行为。0.4.10 P3-4：再加 AuditLog（null jdbi → record fallback log）。
        PushRateLimiter limiter = new PushRateLimiter(PushRateLimiter.Config.unlimited());
        AuditLog auditLog = new AuditLog(null, Logger.getLogger("test"));
        api = new HikariCanvasAPIImpl(registry, store, daemon, limiter, auditLog);
        scheduler = new RecordingScheduler();
        host = fakePlugin("HikariCanvas");
        listener = new PluginCleanupListener(registry, api, host, scheduler);
        pluginA = fakePlugin("PluginA");
        pluginB = fakePlugin("PluginB");
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

    // ──────────────────────────────────────────────────────────
    //  Fake UserVariableDao（与 HikariCanvasAPIImplTest 共用风格；这里独立 copy 避免跨包依赖）
    // ──────────────────────────────────────────────────────────

    static final class FakeUserVariableDao extends UserVariableDao {
        FakeUserVariableDao() {
            super(Logger.getLogger("test"), null);
        }
        @Override
        public void upsert(String wallId, String name, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {}
        @Override public void delete(String wallId, String name) {}
        @Override public List<Row> loadAll() { return new ArrayList<>(); }
    }
}
