package moe.hikari.canvas.variable.plugin;

import moe.hikari.canvas.api.NamespaceInfo;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.provider.DeclaredKey;
import moe.hikari.canvas.variable.provider.VariableProvider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 0.4.0-P4-O：每个外部插件注册的 namespace 对应一个 {@link VariableProvider} 实例。
 *
 * <h2>用途</h2>
 *
 * <p>让 {@link moe.hikari.canvas.api.HikariCanvasAPI#declareKey} 加入的 keys 通过 P3-M
 * {@code /api/variable/list-all-namespaces} 端点暴露给前端 Picker。本 Provider 不调度
 * （{@link #refreshInterval()} = {@link Duration#ZERO}），写值走
 * {@link moe.hikari.canvas.api.HikariCanvasAPI#setVariable} 直接调
 * {@link moe.hikari.canvas.variable.VariableStore#setValue}（push 模式 / 事件驱动）。</p>
 *
 * <h2>线程模型</h2>
 *
 * <p>{@link #declareKey} 与 {@link #removeKey} 用 {@link ConcurrentHashMap}；
 * declaredKeys 返不可变快照——读多写少场景下足以。多 plugin push 不经过本类
 * （直接 store.setValue），本类只承担 key 元信息表。</p>
 *
 * <p>详见 {@code docs/dynamic-data.md §4 / §7}。</p>
 */
public final class PluginNamespaceProvider implements VariableProvider {

    private final String namespace;
    private final NamespaceInfo info;
    /** key → DeclaredKey（含 type / description / ttlMs）。 */
    private final ConcurrentHashMap<String, DeclaredKey> declared = new ConcurrentHashMap<>();

    public PluginNamespaceProvider(String namespace, NamespaceInfo info) {
        this.namespace = namespace;
        this.info = info;
    }

    @Override public String namespace() { return namespace; }

    @Override public String displayName() { return info.displayName(); }

    /**
     * 插件 push 是事件驱动 + 动态注册——任何 declared key 都是运行期通过
     * {@link moe.hikari.canvas.api.HikariCanvasAPI#declareKey} 加入。
     */
    @Override public boolean isDynamic() { return true; }

    /** 不调度（外部 push 模式），daemon 跳过 schedule。 */
    @Override public Duration refreshInterval() { return Duration.ZERO; }

    @Override public void initialize() { /* noop —— 元信息表运行期增量构建 */ }

    @Override public boolean refresh() { return true; /* noop —— refreshInterval=ZERO 不会被调度 */ }

    @Override
    public void shutdown() {
        declared.clear();
    }

    /**
     * 即使 {@link #isDynamic} = true，仍返实际声明过的 key 列表——比 {@link
     * moe.hikari.canvas.variable.provider.PapiVariableBridge} 的"tracked placeholders"
     * 语义稍强，因为 plugin declareKey 是<b>显式</b>声明（编辑器 Picker 可信源），不是被动 track。
     */
    @Override
    public List<DeclaredKey> declaredKeys() {
        return List.copyOf(declared.values());
    }

    /** 增量声明 key。同 key 重复 declare 会覆盖（用户可更新 hint / ttl）。 */
    public void declareKey(String key, VarType type, String description, long ttlMs) {
        declared.put(key, new DeclaredKey(key, type, description, ttlMs));
    }

    /**
     * 撤销 key 声明。unsetVariable 时不必删 declared（key 仍是合法声明，只是值缺）；
     * 仅 plugin disable 时由 {@link #shutdown} 一次性清空。
     */
    public void removeKey(String key) {
        declared.remove(key);
    }

    /** 测试 / 内省。 */
    public NamespaceInfo info() {
        return info;
    }

    /** 测试 / 内省。 */
    public int declaredCount() {
        return declared.size();
    }
}
