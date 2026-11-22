package ac.haru.hikaricanvas.variable.provider;

import ac.haru.hikaricanvas.HikariCanvasConfig;
import ac.haru.hikaricanvas.storage.RailDao;
import ac.haru.hikaricanvas.storage.ScheduleDao;
import ac.haru.hikaricanvas.storage.WallRepo;
import ac.haru.hikaricanvas.variable.VariableStore;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * VariableProviderDaemon 装配入口。
 *
 * <p>注册 {@link SystemVariableProvider} + {@link ScoreboardVariableProvider}；
 * {@link PapiVariableBridge}（PAPI 未装时自动 noop）；
 * {@link ManualScheduleProvider}（兜底列车时刻表）。</p>
 *
 * <p>{@link ManualScheduleProvider} 可注入 {@link HikariCanvasConfig.ScheduleConfig}
 * 配阈值 / 文案。</p>
 *
 * <p>详见 {@code docs/dynamic-data.md §7 内置 Provider}。</p>
 */
public final class ProviderBootstrap {

    private ProviderBootstrap() {}

    /**
     * 创建并装配 {@link VariableProviderDaemon}。
     *
     * <p>调用方负责把返回的 daemon 实例保留到 plugin 字段，并在 {@code onDisable} 调
     * {@link VariableProviderDaemon#shutdown()}。</p>
     *
     * <p>装配顺序：</p>
     * <ol>
     *   <li>注册 system / scoreboard / papi / ManualScheduleProvider</li>
     *   <li>注册 RailScheduleProvider（共享 schedule namespace）</li>
     *   <li>给 ManualScheduleProvider 注入 skipWallPredicate = railProvider.hasWallBinding
     *       让 rail-bound wall 跳过 manual push</li>
     * </ol>
     *
     * @param store          VariableStore；provider 通过 store push 值
     * @param plugin         插件实例（Bukkit scheduler 切主线程等）
     * @param wallRepo       wall 元数据 DAO（{@link SystemVariableProvider} 注册 per-wall {@code wall.*}）
     * @param scheduleDao    时刻表 DAO（{@link ManualScheduleProvider} 启动期 loadAll + per-wall 注册）；
     *                       传 {@code null} 时跳过 ManualScheduleProvider 注册
     * @param scheduleConfig 兜底列车配置（阈值 / 文案）；传 {@code null} 走默认值
     * @param railDao        铁路网络 DAO；传 {@code null} 时不注册 RailScheduleProvider
     * @return 已创建的 daemon（never null）
     */
    public static VariableProviderDaemon initialize(VariableStore store,
                                                    JavaPlugin plugin,
                                                    WallRepo wallRepo,
                                                    @Nullable ScheduleDao scheduleDao,
                                                    @Nullable HikariCanvasConfig.ScheduleConfig scheduleConfig,
                                                    @Nullable RailDao railDao) {
        VariableProviderDaemon daemon = new VariableProviderDaemon();
        // system + scoreboard
        daemon.register(new SystemVariableProvider(store, plugin, wallRepo));
        daemon.register(new ScoreboardVariableProvider(store, plugin));
        // PAPI 桥接（软依赖；未装时 refreshInterval=ZERO，daemon 不调度）
        daemon.register(new PapiVariableBridge(store, plugin));
        HikariCanvasConfig.ScheduleConfig cfg = scheduleConfig == null
                ? HikariCanvasConfig.ScheduleConfig.defaults() : scheduleConfig;
        // 兜底列车时刻表（per-wall manual）
        ManualScheduleProvider manualProvider = null;
        if (scheduleDao != null) {
            manualProvider = new ManualScheduleProvider(store, scheduleDao, cfg);
            daemon.register(manualProvider);
        }
        // 铁路网络（线路 + 站点 + 车次 + timetable）
        if (railDao != null) {
            RailScheduleProvider railProvider = new RailScheduleProvider(
                    store, railDao, cfg, Locale.SIMPLIFIED_CHINESE);
            daemon.register(railProvider);
            // 让 ManualSchedule 跳过 rail-bound wall — 避免双写同 namespace
            if (manualProvider != null) {
                manualProvider.setSkipWallPredicate(railProvider::hasWallBinding);
            }
        }
        return daemon;
    }
}
