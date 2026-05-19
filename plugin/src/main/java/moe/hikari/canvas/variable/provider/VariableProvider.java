package moe.hikari.canvas.variable.provider;

import java.time.Duration;

/**
 * 系统 / PAPI 等异步 provider 接口。0.4.0-P1-E 框架骨架——具体实现 P3 落地。
 *
 * <p>Provider 由 {@link VariableProviderDaemon} 调度，<b>不允许在主线程调 store</b>。
 * 每个 Provider 自己负责定时刷新（通过 daemon scheduleRefresh）+ push 当前值进 store。</p>
 *
 * <p>实现注意：</p>
 * <ul>
 *   <li>{@link #initialize()} 在 {@link VariableProviderDaemon#register} 内同步调用——一次性
 *       create 所有可用 key 到 store；失败抛异常会让 provider 注册失败但不影响 daemon。</li>
 *   <li>{@link #refresh()} 在 daemon scheduler 线程上跑（守护线程池），周期 = {@link #refreshInterval()}；
 *       内部 push 值进 store；抛异常会被 daemon 捕获 + log warning，不停止后续 refresh。</li>
 *   <li>{@link #shutdown()} 在 daemon 关停或 unregister 时调用——释放监听器 / 缓存等资源；
 *       不应抛但抛了也会被 daemon 捕获。</li>
 * </ul>
 *
 * <p>详见 {@code docs/dynamic-data.md §7 内置 Provider} / §10 性能。</p>
 */
public interface VariableProvider {

    /** 返回 namespace（如 "system" / "papi" / "scoreboard"）。daemon 用此做唯一性 key。 */
    String namespace();

    /** 返回 display name（编辑器 UI 显示）。 */
    String displayName();

    /**
     * 启动时调用一次：注册所有可用 key 到 store（{@code store.create} 调用），设默认 currentValue。
     *
     * <p>抛异常 → daemon 不注册该 provider（log warning + 不传播）。</p>
     */
    void initialize();

    /**
     * 由 daemon 定时调用，provider 自己负责更新 store。
     *
     * @return {@code true} = 本 tick 已 push 新值；{@code false} = fallback / 跳过（如外部数据源不可用）。
     *         daemon 仅记录返回值，不据此停止调度。
     */
    boolean refresh();

    /**
     * 调度间隔（每隔多少时间 refresh 一次）。{@link Duration#ZERO} 或负值 = 不调度
     * （provider 自己管理推送，daemon 只调 initialize / shutdown）。
     */
    Duration refreshInterval();

    /**
     * 关停（plugin disable 或 unregister 时）；释放资源（监听器 / 缓存等）。
     */
    void shutdown();
}
