package moe.hikari.canvas.variable.provider;

import moe.hikari.canvas.storage.ScheduleDao;
import moe.hikari.canvas.storage.WallRepo;
import moe.hikari.canvas.variable.VariableStore;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/**
 * 0.4.0-P1-E：VariableProviderDaemon 装配入口。
 *
 * <p>0.4.0-P3-J 起注册 {@link SystemVariableProvider} + {@link ScoreboardVariableProvider}；
 * 0.4.0-P3-K 起加 {@link PapiVariableBridge}（PAPI 未装时自动 noop）；
 * 0.4.0-P3-L 起加 {@link ManualScheduleProvider}（兜底列车时刻表）。</p>
 *
 * <p>详见 {@code docs/dynamic-data.md §7 内置 Provider}。</p>
 */
public final class ProviderBootstrap {

    private ProviderBootstrap() {}

    /**
     * 创建 + 装配 {@link VariableProviderDaemon}。
     *
     * <p>0.4.0-P3-J 注册 system + scoreboard；0.4.0-P3-K 加 papi（PAPI 未装时
     * {@code refreshInterval=ZERO} → daemon 不调度，零开销）；0.4.0-P3-L 加 manual schedule
     * （ScheduleDao 必传——schema V012 已 enforce 表存在）。调用方负责把返回的 daemon 实例
     * 保留到 plugin 字段，并在 {@code onDisable} 调 {@link VariableProviderDaemon#shutdown()}。</p>
     *
     * @param store       P1-A 交付的 VariableStore；provider 通过 store push 值
     * @param plugin      插件实例（{@link SystemVariableProvider} / {@link ScoreboardVariableProvider} /
     *                    {@link PapiVariableBridge} 用于 Bukkit scheduler 切主线程 / reflection load）
     * @param wallRepo    wall 元数据 DAO（{@link SystemVariableProvider} 注册 per-wall {@code wall.*}）
     * @param scheduleDao 时刻表 DAO（{@link ManualScheduleProvider} 启动期 loadAll + per-wall 注册）；
     *                    传 {@code null} 时跳过 ManualScheduleProvider 注册（仅测试 / 极简启动用）
     * @return 已创建的 daemon（never null）
     */
    public static VariableProviderDaemon initialize(VariableStore store,
                                                    JavaPlugin plugin,
                                                    WallRepo wallRepo,
                                                    @Nullable ScheduleDao scheduleDao) {
        VariableProviderDaemon daemon = new VariableProviderDaemon();
        // P3-J：system + scoreboard
        daemon.register(new SystemVariableProvider(store, plugin, wallRepo));
        daemon.register(new ScoreboardVariableProvider(store, plugin));
        // P3-K：PAPI 桥接（软依赖；未装时 refreshInterval=ZERO，daemon 不调度）
        daemon.register(new PapiVariableBridge(store, plugin));
        // P3-L：兜底列车时刻表
        if (scheduleDao != null) {
            daemon.register(new ManualScheduleProvider(store, plugin, scheduleDao));
        }
        return daemon;
    }

    /**
     * 兼容旧调用方（K 阶段及之前）的 3 参数 overload。新代码请传 scheduleDao。
     */
    public static VariableProviderDaemon initialize(VariableStore store,
                                                    JavaPlugin plugin,
                                                    WallRepo wallRepo) {
        return initialize(store, plugin, wallRepo, null);
    }
}
