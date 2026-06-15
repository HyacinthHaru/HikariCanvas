package moe.hikari.canvas.variable.provider;

import java.time.Duration;
import java.util.List;

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

    /**
     * 0.7.4：返回该 provider 写入 {@link moe.hikari.canvas.variable.VariableStore} 时实际使用的
     * namespace 前缀——即编辑器 VariablePicker 应展示、且占位符 {@code ${var:<prefix>/key}} 能被
     * {@code VariableInterpolator} 注入 wallId 解析到的形态。
     *
     * <p>绝大多数 provider 的 {@link #namespace()}（daemon 唯一性 key）与 store 写入前缀一致，
     * 默认实现直接返 {@link #namespace()}。例外是 {@link RailScheduleProvider}：它与
     * {@link ManualScheduleProvider} 共享 {@code schedule:<wallId>/*} store namespace（让旧
     * wall 文本无感升级），但在 daemon 内部必须用不同的唯一 key（{@code "schedule_rail"}）避免
     * {@code VariableProviderDaemon.register} 因 namespace 撞名抛异常。该 provider 覆写本方法返
     * {@code "schedule"}，让 {@code VariableMetadataHandler} 下发给前端 picker 的 namespace 是
     * 真实 store 前缀，而非 daemon 内部 key——否则 picker 会显示一堆 {@code schedule_rail/*} 幽灵
     * 变量（store 无对应、值恒空），且选中插入的 {@code ${var:schedule_rail/X}} 因 interpolator
     * 不识别 {@code schedule_rail} 前缀而 resolve miss（误报"已删除"）。</p>
     *
     * @return store 写入 / 前端展示用的 namespace 前缀；默认 = {@link #namespace()}
     */
    default String storeNamespacePrefix() {
        return namespace();
    }

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

    /**
     * 0.4.0-P3-J：Provider 声明可用 key（编辑器自动补全用）。
     *
     * <p>静态 namespace（{@link #isDynamic()} = false）返完整 key 列表；动态 namespace
     * （{@link #isDynamic()} = true）返空列表，由 namespace 自身说明 + 模板字符串向用户提示
     * 引用方式（如 {@code scoreboard.<obj>.<player>}）。</p>
     *
     * <p>默认实现返空列表——兼容 P1-E 已有 Provider 不强制改造。P3-M {@code GET
     * /api/variable/list-all-namespaces} 端点会调本方法做序列化。</p>
     */
    default List<DeclaredKey> declaredKeys() {
        return List.of();
    }

    /**
     * 0.4.0-P3-J：此 namespace 是否动态注册（玩家引用任意 key 都自动创建）。
     *
     * <p>动态 namespace 示例：{@code scoreboard} —— 任何 {@code scoreboard.<obj>.<player>}
     * 第一次被引用时自动 register 进 store + 加入 refresh 列表。{@link VariableStore} 暴露的
     * {@code registerDynamicLookupHook} 是动态 namespace 的标准接入点。</p>
     *
     * <p>默认 {@code false}（静态 namespace）。</p>
     */
    default boolean isDynamic() {
        return false;
    }
}
