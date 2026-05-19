package moe.hikari.canvas.variable.provider;

import moe.hikari.canvas.variable.VariableStore;

/**
 * 0.4.0-P1-E：VariableProviderDaemon 装配入口。
 *
 * <p>P1 阶段：仅创建 daemon 实例，不注册任何 provider。P3 阶段在此处加：</p>
 * <pre>
 *   daemon.register(new SystemVariableProvider(store, ...));
 *   daemon.register(new PapiVariableBridge(store, ...));
 *   daemon.register(new ScoreboardProvider(store, ...));
 *   daemon.register(new ManualScheduleProvider(store, ...));
 * </pre>
 *
 * <p>详见 {@code docs/dynamic-data.md §7 内置 Provider}。</p>
 */
public final class ProviderBootstrap {

    private ProviderBootstrap() {}

    /**
     * 创建 + 装配 {@link VariableProviderDaemon}。
     *
     * <p>P1 阶段不注册任何 provider；P3 在此 method body 加 {@code daemon.register(...)} 调用。
     * 调用方负责把返回的 daemon 实例保留到 plugin 字段，并在 {@code onDisable} 调
     * {@link VariableProviderDaemon#shutdown()}。</p>
     *
     * @param store P1-A 交付的 VariableStore；provider 通过 store push 值
     * @return 已创建的 daemon（never null）
     */
    public static VariableProviderDaemon initialize(VariableStore store) {
        VariableProviderDaemon daemon = new VariableProviderDaemon();
        // P3 留位：daemon.register(new SystemVariableProvider(store));
        // P3 留位：daemon.register(new PapiVariableBridge(store));
        // P3 留位：daemon.register(new ScoreboardProvider(store));
        // P3 留位：daemon.register(new ManualScheduleProvider(store));
        return daemon;
    }
}
