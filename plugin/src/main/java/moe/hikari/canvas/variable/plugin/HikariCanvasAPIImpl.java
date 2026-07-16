package moe.hikari.canvas.variable.plugin;

import moe.hikari.canvas.api.HikariCanvasAPI;
import moe.hikari.canvas.api.NamespaceInfo;
import moe.hikari.canvas.api.PluginNamespaceException;
import moe.hikari.canvas.api.VarType;
import moe.hikari.canvas.api.VariableUpdate;
import moe.hikari.canvas.storage.AuditLog;
import moe.hikari.canvas.variable.Variable;
import moe.hikari.canvas.variable.VariableException;
import moe.hikari.canvas.variable.VariableStore;
import moe.hikari.canvas.variable.provider.VariableProviderDaemon;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link HikariCanvasAPI} 默认实现。
 *
 * <h2>装配</h2>
 *
 * <p>在 {@code HikariCanvas.onEnable} 中实例化 + 暴露：</p>
 * <pre>
 * this.apiImpl = new HikariCanvasAPIImpl(registry, store, daemon, limiter, auditLog);
 * Bukkit.getServicesManager().register(HikariCanvasAPI.class, apiImpl, this, ServicePriority.Normal);
 * </pre>
 *
 * <p>{@code PluginDisableEvent} listener：disable 触发
 * {@code registry.unregisterAllByPlugin(plugin)} → {@link #unregisterPluginProviders(List)}
 * → 30s 后调 {@link #purgeNamespaceData(String)} 清 store 变量（保留 30s 让引用方
 * 走 cached 平滑过渡）。</p>
 *
 * <h2>错误隔离</h2>
 *
 * <ul>
 *   <li>{@link #setVariable} catch {@link VariableException}：log warning + 返回 void；
 *       插件感知不到内部限流 / 配额 / TTL 校验错误，符合 {@code dynamic-data.md §4.3}。</li>
 *   <li>{@link #setVariable} catch 其他 Exception：log SEVERE + stack trace；保护 HikariCanvas
 *       不被插件 bug 拖垮。</li>
 *   <li>ACL 拒绝（{@link PluginNamespaceException}）<b>抛回</b>调用方——这是插件代码 bug
 *       （namespace spoof 或漏 registerNamespace），不应静默。</li>
 * </ul>
 *
 * @see HikariCanvasAPI
 */
public final class HikariCanvasAPIImpl implements HikariCanvasAPI {

    private static final Logger log = Logger.getLogger(HikariCanvasAPIImpl.class.getName());

    /** dynamic-data.md §16-9：Tier 2 默认 TTL 30s，防卡僵尸数据。 */
    private static final long DEFAULT_TTL_MS = 30_000L;

    /** declared key 校验正则，镜像 {@code VariableStore.KEY_RE}。 */
    private static final java.util.regex.Pattern KEY_RE =
            java.util.regex.Pattern.compile("[a-zA-Z0-9_.\\-]+");

    private final PluginNamespaceRegistry registry;
    private final VariableStore store;
    private final VariableProviderDaemon daemon;
    /**
     * 审计日志。{@link #checkAcl} 拒绝 namespace spoof / 未注册推送时记
     * {@code PLUGIN_NAMESPACE_DENIED}，对齐 {@code security.md §8.1} 对 PERMISSION_DENIED
     * 的强制审计要求。{@link AuditLog#record} 线程安全（内部 jdbi.useHandle + fallback log），
     * 可在调用插件的任意线程直接调，无需切主线程。
     */
    private final AuditLog auditLog;
    /**
     * 双层 push 限流（per-plugin 100/s + 全局 1000/s + 保护期 10s）。
     *
     * <p>volatile 让 {@code /canvas var reload} 触发的
     * {@link #setRateLimiter} 能热替换。读路径（{@link #setVariable} 等）每次 push 都
     * 重新拿引用——少量内存屏障开销换 reload 后立刻生效，无需重启。</p>
     */
    private volatile PushRateLimiter limiter;

    /** namespace → PluginNamespaceProvider（与 registry 同步生命周期）。 */
    private final ConcurrentHashMap<String, PluginNamespaceProvider> providers = new ConcurrentHashMap<>();

    public HikariCanvasAPIImpl(
            PluginNamespaceRegistry registry,
            VariableStore store,
            VariableProviderDaemon daemon,
            PushRateLimiter limiter,
            AuditLog auditLog
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.store = Objects.requireNonNull(store, "store");
        this.daemon = Objects.requireNonNull(daemon, "daemon");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
    }

    /**
     * 热替换 {@link PushRateLimiter}（{@code /canvas var reload} 调）。
     *
     * <p>仅替换引用——窗口数据不迁移（旧 limiter 内的 fixed-window counter 直接 GC）。
     * 替换瞬间到下个 1s 边界之间最坏出现一次窗口"重置"，但 1s 窗口本就是 lossy 抽样，
     * 这一次重置不破坏整体限流语义。多次调用幂等。</p>
     *
     * <p>package-private：只允许 {@link moe.hikari.canvas.HikariCanvas} 主类（同 plugin
     * package 链路）触发。外部不应有任何理由热替换 limiter——这是配置 reload 专用 hook。</p>
     */
    public void setRateLimiter(PushRateLimiter newLimiter) {
        this.limiter = Objects.requireNonNull(newLimiter, "newLimiter");
    }

    // ──────────────────────────────────────────────────────────────────
    //  HikariCanvasAPI 实现
    // ──────────────────────────────────────────────────────────────────

    @Override
    public void registerNamespace(Plugin plugin, String namespace, NamespaceInfo info) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(namespace, "namespace");  // 对齐 api.md §8.1 契约
        // PluginNamespaceRegistry.register 内部校验 namespace 格式 + 保留前缀 + ACL；
        // 抛 NamespaceConflictException / IllegalArgumentException → 直接传给调用方。
        registry.register(plugin, namespace, info);

        // 建立 PluginNamespaceProvider + register 到 daemon（让 declaredKeys 接 P3-M）
        PluginNamespaceProvider provider = providers.computeIfAbsent(namespace,
                ns -> new PluginNamespaceProvider(ns, info));
        try {
            daemon.register(provider);
        } catch (IllegalStateException e) {
            // 重复 register（同 plugin 二次注册或 daemon 已 shutdown）— 幂等 OK
            log.fine("PluginNamespaceProvider register skipped for '" + namespace
                    + "': " + e.getMessage());
        }
        log.info("[HikariCanvas] plugin '" + plugin.getName()
                + "' registered namespace '" + namespace + "'");
    }

    @Override
    public void declareKey(Plugin plugin, String namespace, String key,
                           VarType apiType, @Nullable String hint) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(apiType, "type");
        checkAcl(plugin, namespace);
        // key 格式校验（对齐 VariableStore.validateKey）。上游报错优于让坏 key
        // 进 declaredKeys → 污染前端 Picker。非法 key 抛 IllegalArgumentException，不静默吞。
        validateKeyFormat(key);

        PluginNamespaceProvider provider = providers.get(namespace);
        if (provider == null) {
            // namespace 已注册但 provider 缺失 —— 不该发生（registerNamespace 总是建 provider）；
            // 防御性抛 NAMESPACE_NOT_REGISTERED 让调用方重 register。
            throw new PluginNamespaceException(
                    PluginNamespaceException.Code.NAMESPACE_NOT_REGISTERED,
                    namespace, "provider missing for namespace '" + namespace + "'");
        }
        provider.declareKey(key, convertVarType(apiType), hint, DEFAULT_TTL_MS);
    }

    @Override
    public void setVariable(Plugin plugin, String namespace, String key, String value,
                            @Nullable Duration ttl) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(value, "value");
        checkAcl(plugin, namespace);
        // 限流检查（dynamic-data.md §10.2）。limiter 内部已 log；这里 drop。
        if (!limiter.tryAcquire(plugin)) {
            return;
        }
        try {
            doSetVariable(namespace, key, value, ttl, plugin.getName());
        } catch (VariableException e) {
            // 业务异常（QUOTA / NAME_INVALID / VALUE_TOO_LONG / TTL_INVALID）— 静默 + log
            log.log(Level.WARNING,
                    "[HikariCanvas] setVariable rejected: " + e.code()
                            + " (ns=" + namespace + " key=" + key + "): " + e.getMessage());
        } catch (Exception e) {
            // 未知异常 — 保护 HikariCanvas 主路径，吞掉
            log.log(Level.SEVERE,
                    "[HikariCanvas] setVariable unexpected error (ns=" + namespace
                            + " key=" + key + "): " + e.getMessage(), e);
        }
    }

    @Override
    public void setVariables(Plugin plugin, String namespace, Map<String, VariableUpdate> updates) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(updates, "updates");
        checkAcl(plugin, namespace);
        if (updates.isEmpty()) return;
        // 批量计费 — 一次性占 updates.size() token，要么全成要么全 reject。
        // 实际写入循环不再走 tryAcquire（已批量占用）。
        if (!limiter.tryAcquireBatch(plugin, updates.size())) {
            return;
        }
        // 不调 setVariable 是因为它会重复做 ACL 检查 —— 抽出 doSetVariable 复用。
        for (Map.Entry<String, VariableUpdate> e : updates.entrySet()) {
            VariableUpdate upd = e.getValue();
            if (upd == null) continue;
            try {
                doSetVariable(namespace, e.getKey(), upd.value(), upd.ttl(), plugin.getName());
            } catch (VariableException ex) {
                log.log(Level.WARNING,
                        "[HikariCanvas] setVariables entry rejected: " + ex.code()
                                + " (ns=" + namespace + " key=" + e.getKey() + ")");
            } catch (Exception ex) {
                log.log(Level.SEVERE,
                        "[HikariCanvas] setVariables entry unexpected error (ns=" + namespace
                                + " key=" + e.getKey() + "): " + ex.getMessage(), ex);
            }
        }
    }

    @Override
    public void unsetVariable(Plugin plugin, String namespace, String key) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(namespace, "namespace");
        checkAcl(plugin, namespace);
        try {
            String fullName = namespace + "/" + key;
            store.delete(fullName);
        } catch (VariableException e) {
            if (e.code() != VariableException.Code.VARIABLE_NOT_FOUND) {
                log.log(Level.WARNING,
                        "[HikariCanvas] unsetVariable: " + e.code()
                                + " (ns=" + namespace + " key=" + key + ")");
            }
            // VARIABLE_NOT_FOUND 静默——API 语义"不存在等同删完"。
        }
        // 不 removeKey：declared key 与变量值生命周期解耦（用户可能后续重 set）
    }

    // ──────────────────────────────────────────────────────────────────
    //  回调钩子（plugin disable 时调用）
    // ──────────────────────────────────────────────────────────────────

    /**
     * plugin disable 触发的 provider + daemon 即时清理。
     *
     * <p>对每个 namespace：从 providers 摘除 + 调 {@link VariableProviderDaemon#unregister}
     * （内部 cancel scheduled task + 调 provider.shutdown）。变量值不在此处删——
     * 由 {@link #purgeNamespaceData} 在 30s 延迟后调用。</p>
     *
     * @param namespaces 待清理 namespace 列表（来自 {@link PluginNamespaceRegistry#unregisterAllByPlugin}）
     */
    public void unregisterPluginProviders(List<String> namespaces) {
        if (namespaces == null) return;
        for (String ns : namespaces) {
            PluginNamespaceProvider provider = providers.remove(ns);
            if (provider != null) {
                daemon.unregister(ns);  // daemon.unregister 内会调 provider.shutdown
            }
        }
    }

    /**
     * 30s 延迟后调用，清除该 namespace 下所有变量。
     *
     * <p>触发链：plugin disable → unregisterAllByPlugin（registry 立即摘除）→ scheduler 30s
     * 后 purgeNamespaceData → store.delete 该 namespace 所有 fullName。</p>
     *
     * @param namespace 已 unregister 的 namespace（不再做 ACL 检查 —— 受信内部调用方）
     */
    public void purgeNamespaceData(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        List<Variable> toDelete = store.listByNamespace(namespace);
        for (Variable v : toDelete) {
            try {
                store.delete(v.namespace() + "/" + v.key());
            } catch (VariableException e) {
                // VARIABLE_NOT_FOUND 等被竞态删 —— 静默
                log.fine("purge variable failed (" + e.code() + "): " + v.namespace() + "/" + v.key());
            }
        }
        log.info("[HikariCanvas] purged " + toDelete.size()
                + " variables from namespace '" + namespace + "'");
    }

    // ──────────────────────────────────────────────────────────────────
    //  内部 helper
    // ──────────────────────────────────────────────────────────────────

    /**
     * declared key 格式校验，与 {@code VariableStore.validateKey} 一致
     * （{@code [a-zA-Z0-9_.-]+}，长度 ≤ {@link VariableStore#MAX_KEY_LENGTH}）。非法即抛
     * {@link IllegalArgumentException} —— 让插件 dev 立即发现，而非让坏 key 进 Picker。
     */
    private static void validateKeyFormat(String key) {
        if (key == null || key.isEmpty()
                || key.length() > VariableStore.MAX_KEY_LENGTH
                || !KEY_RE.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "invalid key (must match [a-zA-Z0-9_.-]+, length 1.."
                            + VariableStore.MAX_KEY_LENGTH + "): " + key);
        }
    }

    private void checkAcl(Plugin plugin, String namespace) {
        if (!registry.owns(plugin, namespace)) {
            Optional<PluginNamespaceRegistry.Registration> existing = registry.get(namespace);
            if (existing.isEmpty()) {
                // 未注册 namespace 推送也是 spoof 面（插件漏 registerNamespace 或
                // 抢 reserved namespace），留审计痕。
                recordNamespaceDenied(plugin, namespace,
                        PluginNamespaceException.Code.NAMESPACE_NOT_REGISTERED, null);
                throw new PluginNamespaceException(
                        PluginNamespaceException.Code.NAMESPACE_NOT_REGISTERED,
                        namespace,
                        "namespace '" + namespace + "' not registered; call registerNamespace first");
            }
            // namespace spoof（推他人 namespace）记审计——security.md §9.2 威胁模型。
            recordNamespaceDenied(plugin, namespace,
                    PluginNamespaceException.Code.NAMESPACE_ACL_DENIED, existing.get().pluginName());
            throw new PluginNamespaceException(
                    PluginNamespaceException.Code.NAMESPACE_ACL_DENIED,
                    namespace,
                    "plugin '" + plugin.getName() + "' does not own namespace '" + namespace
                            + "' (owned by '" + existing.get().pluginName() + "')");
        }
    }

    /**
     * 把 namespace spoof / 未注册推送拒绝写进 audit_log（事件
     * {@code PLUGIN_NAMESPACE_DENIED}）。actor 是<b>插件</b>非玩家，故 player/session/ip 字段留
     * null，spoof 上下文（来源插件 / 目标 namespace / 实际 owner / 拒绝码）全放 details。
     *
     * <p>跑在调用插件的任意线程；{@link AuditLog#record} 内部线程安全（jdbi.useHandle +
     * fallback log），不切主线程。</p>
     */
    private void recordNamespaceDenied(Plugin plugin, String namespace,
                                       PluginNamespaceException.Code code, @Nullable String actualOwner) {
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("sourcePlugin", plugin.getName());
        details.put("targetNamespace", namespace);
        details.put("denyCode", code.name());
        if (actualOwner != null) details.put("actualOwner", actualOwner);
        auditLog.record("PLUGIN_NAMESPACE_DENIED", null, null, null, null, details);
    }

    /**
     * setVariable / setVariables 共用核心：若 store 内无该变量则 create + setValue。
     *
     * <p>顺序：先 setValue 试探 → 若 {@link VariableException.Code#VARIABLE_NOT_FOUND}
     * 再 create + setValue。这种顺序保证<b>VALUE_TOO_LONG / TTL_INVALID 等校验失败时
     * 不残留 currentValue=null 的半态变量</b>——失败的 push 不污染 store。</p>
     */
    private void doSetVariable(String namespace, String key, String value,
                               @Nullable Duration ttl, String sourcePluginName) {
        Objects.requireNonNull(value, "value");
        String fullName = namespace + "/" + key;
        try {
            store.setValue(fullName, value, ttl);
            return;
        } catch (VariableException e) {
            if (e.code() != VariableException.Code.VARIABLE_NOT_FOUND) {
                throw e;  // QUOTA / VALUE_TOO_LONG / TTL_INVALID 等：原样抛给上层 catch
            }
        }
        // 不存在 → create + setValue。仍可能在 create 阶段抛 QUOTA / NAME_INVALID。
        moe.hikari.canvas.variable.VarType type = inferType(namespace, key);
        try {
            store.create(namespace, key, type, null, sourcePluginName);
        } catch (VariableException e) {
            // 两个线程并发首次 push 同 key → 都 setValue miss → 都 create，
            // 一个赢另一个拿 VARIABLE_EXISTS。create-or-retry-setValue
            // 闭环：捕获 VARIABLE_EXISTS → 对赢家刚建好的变量再 setValue 一次，败者的值不丢。
            // 其他 code（QUOTA / NAME_INVALID）仍原样抛给上层 catch。
            if (e.code() != VariableException.Code.VARIABLE_EXISTS) {
                throw e;
            }
        }
        store.setValue(fullName, value, ttl);
    }

    /** API VarType → 内部 VarType（1:1 enum mapping）。 */
    static moe.hikari.canvas.variable.VarType convertVarType(VarType apiType) {
        return switch (apiType) {
            case STRING -> moe.hikari.canvas.variable.VarType.STRING;
            case NUMBER -> moe.hikari.canvas.variable.VarType.NUMBER;
            case BOOLEAN -> moe.hikari.canvas.variable.VarType.BOOLEAN;
            case COLOR -> moe.hikari.canvas.variable.VarType.COLOR;
        };
    }

    /** 查 declared key 类型；未声明 fallback STRING。 */
    private moe.hikari.canvas.variable.VarType inferType(String namespace, String key) {
        PluginNamespaceProvider provider = providers.get(namespace);
        if (provider != null) {
            for (var dk : provider.declaredKeys()) {
                if (dk.key().equals(key)) return dk.type();
            }
        }
        return moe.hikari.canvas.variable.VarType.STRING;
    }

    // ──────────────────────────────────────────────────────────────────
    //  测试 / 内省
    // ──────────────────────────────────────────────────────────────────

    /** 已注册的 PluginNamespaceProvider 数量（应与 registry.size 一致）。 */
    public int registeredProviderCount() {
        return providers.size();
    }

    /** 测试用：取某 namespace 的 provider（若存在）。 */
    public Optional<PluginNamespaceProvider> providerOf(String namespace) {
        return Optional.ofNullable(providers.get(namespace));
    }
}
