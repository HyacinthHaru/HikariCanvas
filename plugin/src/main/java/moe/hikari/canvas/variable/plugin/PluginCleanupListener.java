package moe.hikari.canvas.variable.plugin;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 0.4.0-P4-Q：监听 {@link PluginDisableEvent}，外部插件 disable 时清理它在 HikariCanvas
 * 注册的 namespace。
 *
 * <h2>三阶段清理</h2>
 *
 * <ol>
 *   <li><b>立刻</b>（同步，主线程 monitor event）：从 {@link PluginNamespaceRegistry}
 *       移除该 plugin 持有的 namespace（让其他插件能立即抢用同名 namespace）。</li>
 *   <li><b>立刻</b>（同步）：调用 {@link HikariCanvasAPIImpl#unregisterPluginProviders}
 *       移除 daemon 内的 {@link PluginNamespaceProvider} + 关闭其调度（虽然
 *       PluginNamespaceProvider 本身是 push 型不调度，daemon.unregister 仍会调
 *       provider.shutdown 释放资源）。</li>
 *   <li><b>30s 延迟后</b>（async via Bukkit scheduler）：调用
 *       {@link HikariCanvasAPIImpl#purgeNamespaceData} 把该 namespace 的所有变量值从
 *       {@link moe.hikari.canvas.variable.VariableStore} 删掉。保留 30s 让引用方走 cached
 *       value 平滑过渡（用户决策：避免"插件重启 → wall 立刻闪烁占位符"的体验问题）。</li>
 * </ol>
 *
 * <h2>自我跳过</h2>
 *
 * <p>HikariCanvas 自己 disable 时 PluginDisableEvent 也会派发；本 listener 检测
 * {@code event.getPlugin() == host} 直接 return，避免 self-cleanup 与 onDisable
 * 顺序竞争（onDisable 已通过 {@code variableProviderDaemon.shutdown()} 统一关闭）。</p>
 *
 * <h2>异常隔离</h2>
 *
 * <p>{@link #onPluginDisable} 内任何步骤抛异常都 catch + log WARNING；HikariCanvas
 * 不能因单个外部插件 disable 时抛错而整体崩盘。延迟 purge task 内部也有 try/catch
 * 单 namespace 级别保护——一个 namespace 清理失败不影响其他。</p>
 *
 * <h2>可测试性</h2>
 *
 * <p>30s 延迟调度通过构造时注入的 {@link DelayedScheduler} 抽象，生产实现
 * {@link #bukkitAsyncScheduler} 调 {@code Bukkit.getScheduler().runTaskLaterAsynchronously}，
 * 测试可注入同步 / 立即 / 抓 task 的 fake。</p>
 *
 * @see HikariCanvasAPIImpl#unregisterPluginProviders(List)
 * @see HikariCanvasAPIImpl#purgeNamespaceData(String)
 * @see PluginNamespaceRegistry#unregisterAllByPlugin(Plugin)
 */
public final class PluginCleanupListener implements Listener {

    private static final Logger log = Logger.getLogger(PluginCleanupListener.class.getName());

    /** 30 秒 grace period（dynamic-data.md §4.3 决策）。 */
    static final long PURGE_DELAY_TICKS = 20L * 30;

    /**
     * 延迟调度抽象。生产用 {@link #bukkitAsyncScheduler}；测试可注入同步执行 fake。
     *
     * <p>{@code runLater} 不返回 task handle —— 当前用法不需要 cancel，且把简单接口
     * 改成 BukkitTask 反而拉低可测试性。</p>
     */
    @FunctionalInterface
    public interface DelayedScheduler {
        /**
         * 安排 {@code task} 在 {@code delayTicks} 个 tick 后执行。
         *
         * @param delayTicks Minecraft tick（20 tick/s）；&lt;= 0 视为立即
         * @param task       要执行的任务；抛异常由调度器内部 catch
         */
        void runLater(long delayTicks, Runnable task);
    }

    /**
     * 标准生产实现：{@code Bukkit.getScheduler().runTaskLaterAsynchronously}。
     *
     * <p>用 async 是因为 purge 只动 in-memory store + DB（异步 OK；store.delete 内部
     * 自己持锁），不涉及任何主线程独占资源（如 entities / packets）。</p>
     */
    public static DelayedScheduler bukkitAsyncScheduler(Plugin host) {
        Objects.requireNonNull(host, "host");
        return (delayTicks, task) ->
                Bukkit.getScheduler().runTaskLaterAsynchronously(host, task, Math.max(1L, delayTicks));
    }


    private final PluginNamespaceRegistry registry;
    private final HikariCanvasAPIImpl apiImpl;
    private final Plugin host;
    private final DelayedScheduler scheduler;

    /**
     * 生产构造器：用 {@link #bukkitAsyncScheduler}。
     *
     * @param host HikariCanvas 主插件实例（生产 = {@code this}）。仅做 identity 比较 +
     *             传给 scheduler；不调 host 任何方法。
     */
    public PluginCleanupListener(
            PluginNamespaceRegistry registry,
            HikariCanvasAPIImpl apiImpl,
            Plugin host
    ) {
        this(registry, apiImpl, host, bukkitAsyncScheduler(host));
    }

    /**
     * 测试构造器：注入自定义 scheduler。
     */
    public PluginCleanupListener(
            PluginNamespaceRegistry registry,
            HikariCanvasAPIImpl apiImpl,
            Plugin host,
            DelayedScheduler scheduler
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.apiImpl = Objects.requireNonNull(apiImpl, "apiImpl");
        this.host = Objects.requireNonNull(host, "host");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent ev) {
        handleDisable(ev.getPlugin());
    }

    /**
     * 核心清理逻辑（package-private，便于测试无需构造 PluginDisableEvent —
     * Bukkit 的 ServerEvent 构造需 Bukkit.server 单例，单测环境没装）。
     *
     * @param disabled 被 disable 的插件实例
     */
    void handleDisable(Plugin disabled) {
        // 跳过 HikariCanvas 自己：onDisable 已统一关 daemon + DB
        if (disabled == host) return;

        try {
            List<String> removed = registry.unregisterAllByPlugin(disabled);
            if (removed.isEmpty()) return;

            // 立即 unregister provider + daemon（释放 namespace 让别人能抢用）
            apiImpl.unregisterPluginProviders(removed);

            log.info("[HikariCanvas] plugin '" + disabled.getName()
                    + "' disabled; unregistered " + removed.size()
                    + " namespace(s): " + removed
                    + " (cached values retained for 30s)");

            // 30s 后清 store 数据
            scheduler.runLater(PURGE_DELAY_TICKS, () -> purgeAfterGrace(disabled.getName(), removed));
        } catch (Exception e) {
            // 防御：listener 不应因单 plugin 清理失败拖崩 HikariCanvas
            log.log(Level.WARNING,
                    "[HikariCanvas] PluginDisableEvent cleanup failed for '"
                            + disabled.getName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * 30s 延迟任务体。单 namespace 抛异常隔离 —— 其他 namespace 仍要清理。
     */
    private void purgeAfterGrace(String pluginName, List<String> namespaces) {
        int purged = 0;
        for (String ns : namespaces) {
            try {
                apiImpl.purgeNamespaceData(ns);
                purged++;
            } catch (Exception e) {
                log.log(Level.WARNING,
                        "[HikariCanvas] purge namespace '" + ns + "' failed (plugin='"
                                + pluginName + "'): " + e.getMessage(), e);
            }
        }
        log.info("[HikariCanvas] purged " + purged + "/" + namespaces.size()
                + " orphan namespace(s) after 30s grace period (plugin='"
                + pluginName + "')");
    }
}
