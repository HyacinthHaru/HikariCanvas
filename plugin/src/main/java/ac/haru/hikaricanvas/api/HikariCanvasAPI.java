package ac.haru.hikaricanvas.api;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Map;

/**
 * 外部插件向 HikariCanvas 推变量的公开 API。
 *
 * <h2>两种获取入口</h2>
 * <pre>
 * // A: 通过 getAPI() （需 import HikariCanvas 主类）
 * HikariCanvasAPI api = ((HikariCanvas) Bukkit.getPluginManager().getPlugin("HikariCanvas")).getAPI();
 *
 * // B: 通过 Bukkit ServicesManager （推荐，零编译耦合）
 * HikariCanvasAPI api = Bukkit.getServicesManager().load(HikariCanvasAPI.class);
 * </pre>
 *
 * <h2>典型生命周期</h2>
 * <pre>
 * onEnable:
 *   api.registerNamespace(this, "bedwars", new NamespaceInfo("BedWars", "BedWarsPlugin", "1.0"));
 *   api.declareKey(this, "bedwars", "red_score", VarType.NUMBER, "红队当前分数");
 *
 * 事件触发:
 *   api.setVariable(this, "bedwars", "red_score", "5", Duration.ofMinutes(5));
 *
 * onDisable: 不需要手动 unregister —— HikariCanvas 自动监听 PluginDisableEvent 清理
 *           （30s 后变量从 store 清除，期间引用方走 fallback 链）。
 * </pre>
 *
 * <h2>错误隔离</h2>
 *
 * <p>插件 setVariable 抛业务异常 → API 内部 log + 该次操作丢弃，不影响 HikariCanvas
 * 本身（详见 {@code docs/dynamic-data.md §4.3}）。</p>
 *
 * <p>限流（{@code dynamic-data.md §10.2}）：单 plugin 推送频率 > 100/s 由 P 任务接入的
 * PushRateLimiter 拦截 + log warning，不抛异常给调用方。</p>
 *
 * <h2>保留 namespace</h2>
 *
 * <p>{@code user / system / papi / scoreboard / schedule} 由 HikariCanvas 内部使用，
 * 外部插件 registerNamespace 这些名字会抛 {@link IllegalArgumentException}。</p>
 *
 * @see docs/dynamic-data.md §4 Plugin Push API
 * @see docs/dynamic-data.md §9.2 插件 namespace ACL
 */
public interface HikariCanvasAPI {

    /**
     * 注册 namespace。每个插件 onEnable 时调用一次。
     *
     * <p>同插件重复调用相同 namespace 幂等（覆盖更新 {@link NamespaceInfo}）；
     * 其他插件调用已注册 namespace 抛 {@link NamespaceConflictException}。</p>
     *
     * @param plugin    调用方 Plugin 实例（用于 ACL spoof 防御 + plugin disable 时自动清理）
     * @param namespace {@code [a-zA-Z_][a-zA-Z0-9_]*} ≤ 32 char，必须唯一
     * @param info      namespace 元信息（编辑器 UI 显示用）
     * @throws NamespaceConflictException 其他插件已注册该 namespace
     * @throws IllegalArgumentException   namespace 格式非法 / 与保留前缀冲突
     */
    void registerNamespace(Plugin plugin, String namespace, NamespaceInfo info);

    /**
     * 声明可用 key（编辑器 Variable Picker 自动补全用）。
     *
     * <p>可在任意时刻调，多次调相同 key 会更新 hint。声明 key 不创建变量 / 不写值——
     * 仅供编辑器列出可选项；实际值需 {@link #setVariable} 推送。</p>
     *
     * @param plugin    调用方（ACL 校验，必须与 registerNamespace 时一致）
     * @param namespace 必须先 registerNamespace
     * @param key       {@code [a-zA-Z0-9_.-]+} ≤ 64 char
     * @param type      类型 hint，给编辑器选输入控件
     * @param hint      Picker 显示的人类可读描述；可空
     * @throws PluginNamespaceException namespace 未注册 / 不属于调用方 plugin
     */
    void declareKey(Plugin plugin, String namespace, String key, VarType type, @Nullable String hint);

    /**
     * 推送变量当前值。变量不存在时自动 create（type 取 declareKey 声明，未声明默认 STRING）。
     *
     * <p>HikariCanvas 自动：</p>
     * <ol>
     *   <li>写入 VariableStore</li>
     *   <li>查倒排索引找引用该变量的 wall</li>
     *   <li>mark wall dirty → 下次 ProjectionThrottler tick 重画</li>
     * </ol>
     *
     * @param plugin    调用方（ACL 校验，必须与 registerNamespace 时一致）
     * @param namespace 已 registerNamespace 的 namespace
     * @param key       {@code [a-zA-Z0-9_.-]+} ≤ 64 char
     * @param value     字符串值（非 null；空串合法；长度 ≤ 4096 char）
     * @param ttl       TTL；{@code null} = 沿用原 TTL；{@link Duration#ZERO} = 永久；
     *                  {@code >0} 必须 ≥ 100ms。建议合理值如 30s 防卡僵尸数据
     * @throws PluginNamespaceException namespace 未注册 / 不属于调用方
     */
    void setVariable(Plugin plugin, String namespace, String key, String value, @Nullable Duration ttl);

    /**
     * 批量推送。性能上比循环单次调更优（内部可做 dirty wall merge，避免重复触发
     * ProjectionThrottler）。
     *
     * @param plugin    调用方（ACL 校验）
     * @param namespace 已 registerNamespace 的 namespace
     * @param updates   key → VariableUpdate；map 不可为 null（空 map 合法 = noop）
     * @throws PluginNamespaceException namespace 未注册 / 不属于调用方
     */
    void setVariables(Plugin plugin, String namespace, Map<String, VariableUpdate> updates);

    /**
     * 删除单个变量（不删 namespace 本身）。
     *
     * <p>变量不存在静默返回（不抛 VariableException）。</p>
     *
     * @param plugin    调用方（ACL 校验）
     * @param namespace 已 registerNamespace 的 namespace
     * @param key       变量 key
     * @throws PluginNamespaceException namespace 未注册 / 不属于调用方
     */
    void unsetVariable(Plugin plugin, String namespace, String key);
}
