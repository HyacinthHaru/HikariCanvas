package moe.hikari.canvas.variable.provider;

import moe.hikari.canvas.storage.WallRepo;
import moe.hikari.canvas.variable.VariableStore;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 0.4.0-P1-E：VariableProviderDaemon 装配入口。
 *
 * <p>0.4.0-P3-J 起注册 {@link SystemVariableProvider} + {@link ScoreboardVariableProvider}。
 * P3-K 后加 PapiVariableBridge；P3-L 后加 ManualScheduleProvider。</p>
 *
 * <p>详见 {@code docs/dynamic-data.md §7 内置 Provider}。</p>
 */
public final class ProviderBootstrap {

    private ProviderBootstrap() {}

    /**
     * 创建 + 装配 {@link VariableProviderDaemon}。
     *
     * <p>0.4.0-P3-J 注册 system + scoreboard 两个 Provider；后续 phase 在 method body 继续
     * 加 {@code daemon.register(...)}。调用方负责把返回的 daemon 实例保留到 plugin 字段，并在
     * {@code onDisable} 调 {@link VariableProviderDaemon#shutdown()}。</p>
     *
     * @param store    P1-A 交付的 VariableStore；provider 通过 store push 值
     * @param plugin   插件实例（{@link SystemVariableProvider} / {@link ScoreboardVariableProvider}
     *                 用于 Bukkit scheduler 切主线程）
     * @param wallRepo wall 元数据 DAO（{@link SystemVariableProvider} 注册 per-wall {@code wall.*}）
     * @return 已创建的 daemon（never null）
     */
    public static VariableProviderDaemon initialize(VariableStore store,
                                                    JavaPlugin plugin,
                                                    WallRepo wallRepo) {
        VariableProviderDaemon daemon = new VariableProviderDaemon();
        // P3-J：system + scoreboard
        daemon.register(new SystemVariableProvider(store, plugin, wallRepo));
        daemon.register(new ScoreboardVariableProvider(store, plugin));
        // P3-K 待加：daemon.register(new PapiVariableBridge(store, plugin));
        // P3-L 待加：daemon.register(new ManualScheduleProvider(store, plugin, scheduleDao));
        return daemon;
    }
}
