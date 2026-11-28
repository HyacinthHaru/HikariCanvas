package ac.haru.hikaricanvas.api;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Map;

/**
 * 外部插件向 HikariCanvas 推送动态变量的公开 API。
 *
 * <h2>获取方式（推荐通过 Bukkit ServicesManager，零主类耦合）</h2>
 * <pre>
 * HikariCanvasAPI api = Bukkit.getServicesManager().load(HikariCanvasAPI.class);
 * </pre>
 *
 * <h2>典型生命周期</h2>
 * <pre>
 * onEnable:
 *   api.registerNamespace(this, "bedwars", new NamespaceInfo("BedWars", "BedWarsPlugin", "1.0"));
 *   api.declareKey(this, "bedwars", "red_score", VarType.NUMBER, "红队当前分数");
 *
 * 事件或定时任务触发:
 *   api.setVariable(this, "bedwars", "red_score", "5", Duration.ofMinutes(5));
 *
 * onDisable: 无需手动清理 —— HikariCanvas 监听 PluginDisableEvent 自动释放命名空间，
 *           并将已写入的变量值保留 30s 宽限期，避免插件热重载期间招牌闪烁为占位符。
 * </pre>
 *
 * <h2>线程安全与错误隔离</h2>
 * <ul>
 *   <li><b>任意线程可调</b>：本接口全部方法均为线程安全实现，可在服务器主线程或异步线程中直接调用，
 *       画布合成与发包在后台投影线程合并完成，不阻塞游戏主线程。</li>
 *   <li><b>业务容错</b>：{@link #setVariable} / {@link #setVariables} 遇到单值超长、键名格式非法、
 *       负有效期或超出命名空间键数上限时，内部记录警告日志并静默丢弃本次写入，不中断调用方业务（详见
 *       {@code docs/dynamic-data.md §5.3}）。</li>
 *   <li><b>速率限制</b>（{@code docs/dynamic-data.md §11.2}）：单插件推送频率超过阈值时自动拦截并记录警告，
 *       不向调用方抛异常。</li>
 *   <li><b>权限与契约异常直抛</b>：必填参数为 {@code null} 抛 {@link NullPointerException}；命名空间未注册或跨插件越权抛
 *       {@link PluginNamespaceException}，便于接入开发期立即定位调用错误。</li>
 * </ul>
 *
 * <h2>系统保留命名空间</h2>
 *
 * <p>{@code user / userglobal / system / papi / scoreboard / schedule} 由 HikariCanvas 内部使用，
 * 外部插件注册这些名称会抛 {@link IllegalArgumentException}。详见 {@code docs/api.md} 与
 * {@code docs/dynamic-data.md §5 / §10.2}。</p>
 */
public interface HikariCanvasAPI {

    /**
     * 注册插件专属命名空间。通常在插件 {@code onEnable} 时调用一次。
     *
     * <p>同一插件实例重复注册相同命名空间为幂等操作（原地刷新 {@link NamespaceInfo} 展示信息，
     * 保留首次注册时间与已声明键名）；其他插件抢注已占用的命名空间抛 {@link NamespaceConflictException}。</p>
     *
     * @param plugin    调用方插件实例（用于跨插件隔离校验及卸载时自动清理）
     * @param namespace 命名空间标识，须匹配 {@code [a-zA-Z_][a-zA-Z0-9_]{0,31}}
     * @param info      命名空间展示元信息（不可为 {@code null}）
     * @throws NullPointerException       任一参数为 {@code null}
     * @throws NamespaceConflictException 其他插件已占用该命名空间
     * @throws IllegalArgumentException   命名空间格式非法或与系统保留名称冲突
     */
    void registerNamespace(@NotNull Plugin plugin, @NotNull String namespace, @NotNull NamespaceInfo info);

    /**
     * 声明可用变量键名与类型说明（供网页编辑器变量选择器搜索与自动补全展示）。
     *
     * <p>可在任意时刻调用，重复声明同一键名会覆盖更新类型与提示文案。声明操作仅登记元数据，
     * <b>不会创建变量或写入变量值</b>；实际数据需通过 {@link #setVariable} 或 {@link #setVariables} 推送。</p>
     *
     * @param plugin    调用方插件实例（须与注册该命名空间时一致）
     * @param namespace 已注册的命名空间
     * @param key       变量键名，须匹配 {@code [a-zA-Z0-9_.-]+} 且长度 ≤ 64
     * @param type      变量类型提示（决定编辑器侧推荐输入控件）
     * @param hint      编辑器选择器中显示的可读说明；可为 {@code null}
     * @throws NullPointerException     除 {@code hint} 外的任一参数为 {@code null}
     * @throws IllegalArgumentException {@code key} 格式非法或超长
     * @throws PluginNamespaceException 命名空间未注册或不属于调用方插件
     */
    void declareKey(@NotNull Plugin plugin, @NotNull String namespace, @NotNull String key,
                    @NotNull VarType type, @Nullable String hint);

    /**
     * 撤销先前通过 {@link #declareKey} 登记的变量元数据声明。
     *
     * <p>适用于插件热重载子配置后移除废弃的编辑器自动补全项。仅移除选择器元数据，
     * <b>不会删除已写入的变量值</b>（如需同时清除当前值，请配合调用 {@link #unsetVariable}）；
     * 若该键原本未声明则静默返回。</p>
     *
     * @param plugin    调用方插件实例（须与注册该命名空间时一致）
     * @param namespace 已注册的命名空间
     * @param key       变量键名，须匹配 {@code [a-zA-Z0-9_.-]+} 且长度 ≤ 64
     * @throws NullPointerException     任一参数为 {@code null}
     * @throws IllegalArgumentException {@code key} 格式非法或超长
     * @throws PluginNamespaceException 命名空间未注册或不属于调用方插件
     */
    void undeclareKey(@NotNull Plugin plugin, @NotNull String namespace, @NotNull String key);

    /**
     * 推送单个变量的最新字符串值。若变量尚未存在则自动创建（类型优先取 {@link #declareKey} 声明值，未声明默认 {@link VarType#STRING}）。
     *
     * @param plugin    调用方插件实例（须与注册该命名空间时一致）
     * @param namespace 已注册的命名空间
     * @param key       变量键名（{@code [a-zA-Z0-9_.-]+}，长度 ≤ 64；非法格式记警告并丢弃本次写入）
     * @param value     字符串值（不可为 {@code null}，长度 ≤ 4096；允许空串 {@code ""}，渲染期空串继续走兜底链）
     * @param ttl       有效期：{@code null} = 沿用该变量原有效期（首次自动创建时默认永久）；
     *                  {@link Duration#ZERO} = 永久有效；正数低于 {@code 100ms}（含亚毫秒正数）自动向上钳制到
     *                  {@code 100ms}；负数记录警告并丢弃本次写入
     * @throws NullPointerException     除 {@code ttl} 外的任一参数为 {@code null}
     * @throws PluginNamespaceException 命名空间未注册或不属于调用方插件
     */
    void setVariable(@NotNull Plugin plugin, @NotNull String namespace, @NotNull String key,
                     @NotNull String value, @Nullable Duration ttl);

    /**
     * 批量推送同一命名空间下的多个变量。
     *
     * <p>相比循环调用 {@link #setVariable}，本方法仅执行一次命名空间权限校验与批量限流配额扣减
     * （按 {@code updates.size()} 扣减），受影响画板在后台投影节流器窗口内自动合并重绘。</p>
     *
     * @param plugin    调用方插件实例（须与注册该命名空间时一致）
     * @param namespace 已注册的命名空间
     * @param updates   键名到 {@link VariableUpdate} 的映射（Map 本身及其键值均不可为 {@code null}；空 Map 合法且无操作）
     * @throws NullPointerException     {@code plugin}、{@code namespace}、{@code updates} 或 Map 内任意键值为 {@code null}
     * @throws PluginNamespaceException 命名空间未注册或不属于调用方插件
     */
    void setVariables(@NotNull Plugin plugin, @NotNull String namespace,
                      @NotNull Map<String, VariableUpdate> updates);

    /**
     * 移除单个变量值（保留命名空间与已声明的选择器元数据）。
     *
     * <p>移除后画板上引用该变量的文本将按兜底顺序回退；若变量原本不存在则静默返回。</p>
     *
     * @param plugin    调用方插件实例（须与注册该命名空间时一致）
     * @param namespace 已注册的命名空间
     * @param key       变量键名（不可为 {@code null}）
     * @throws NullPointerException     任一参数为 {@code null}
     * @throws PluginNamespaceException 命名空间未注册或不属于调用方插件
     */
    void unsetVariable(@NotNull Plugin plugin, @NotNull String namespace, @NotNull String key);
}
