package ac.haru.hikaricanvas.variable.plugin;

import ac.haru.hikaricanvas.api.NamespaceConflictException;
import ac.haru.hikaricanvas.api.NamespaceInfo;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * plugin namespace 注册表。
 *
 * <p>承担：</p>
 * <ul>
 *   <li>namespace 校验（正则 + 保留前缀）</li>
 *   <li>原子注册（防 race 条件下双注册）</li>
 *   <li>ACL 拒绝：跨 plugin 篡改其他人的 namespace 时抛 {@link NamespaceConflictException}</li>
 *   <li>plugin disable 时一次性移除该 plugin 所有 namespace</li>
 * </ul>
 *
 * <p>线程安全：内部 {@link ConcurrentHashMap}；register 用 {@code putIfAbsent} CAS 保证原子。</p>
 *
 * <p>详见 {@code docs/dynamic-data.md §4 / §9.2}。</p>
 */
public final class PluginNamespaceRegistry {

    /**
     * 保留 namespace：HikariCanvas 内部使用，外部插件禁止注册。
     *
     * <p>{@code userglobal} 保留：防插件抢用绕 ACL / 配额（插件想全服共享应用自己 namespace，
     * 如 {@code bedwars/score}）。详见 {@code docs/dynamic-data.md §17.3}。</p>
     */
    public static final Set<String> RESERVED_NAMESPACES =
            Set.of("user", "userglobal", "system", "papi", "scoreboard", "schedule");

    /** namespace 校验正则：{@code [a-zA-Z_][a-zA-Z0-9_]{0,31}}。与内部 VariableStore 一致，
     * 但<b>不允许</b> {@code :} {@code -}（这两个保留给 HikariCanvas 内部 user:&lt;wallId&gt; 形态用）。 */
    private static final Pattern NS_REGEX = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,31}$");

    /** 注册信息记录。 */
    public record Registration(
            Plugin plugin,
            String pluginName,
            NamespaceInfo info,
            long registeredAt
    ) {}

    private final ConcurrentHashMap<String, Registration> registrations = new ConcurrentHashMap<>();

    /**
     * 注册 namespace。
     *
     * <p>同插件重复 register 同 namespace 幂等：覆盖更新 {@code Registration.info}，
     * 但保留 {@code registeredAt} 不变（防误改首次注册时间）。</p>
     *
     * @return 注册结果
     * @throws NamespaceConflictException 已被其他插件注册
     * @throws IllegalArgumentException   namespace 格式非法 / 保留前缀
     */
    public Registration register(Plugin plugin, String namespace, NamespaceInfo info) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(info, "info");

        // 1. 校验格式
        if (!NS_REGEX.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "namespace must match [a-zA-Z_][a-zA-Z0-9_]{0,31}: " + namespace);
        }
        // 2. 保留前缀（防外部插件占用 user / system / papi / scoreboard / schedule）
        if (RESERVED_NAMESPACES.contains(namespace)) {
            throw new IllegalArgumentException(
                    "namespace '" + namespace + "' is reserved by HikariCanvas");
        }

        long now = System.currentTimeMillis();
        Registration candidate = new Registration(plugin, plugin.getName(), info, now);

        // 3. 原子 CAS 注册：putIfAbsent 拿到旧值后判 plugin 是否同 instance
        Registration prev = registrations.putIfAbsent(namespace, candidate);
        if (prev == null) {
            return candidate;
        }
        // 已存在：同 plugin → 幂等覆盖 info（保留旧 registeredAt）；他 plugin → 冲突
        if (!prev.plugin().equals(plugin)) {
            throw new NamespaceConflictException(namespace, prev.pluginName());
        }
        Registration updated = new Registration(plugin, plugin.getName(), info, prev.registeredAt());
        registrations.put(namespace, updated);
        return updated;
    }

    /**
     * ACL 检查：plugin 是否拥有该 namespace？
     *
     * @return true = 该 namespace 已注册且属于 plugin；false = 未注册或被他人持有
     */
    public boolean owns(Plugin plugin, String namespace) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(namespace, "namespace");
        Registration r = registrations.get(namespace);
        return r != null && r.plugin().equals(plugin);
    }

    /** 查询 namespace 当前注册信息。 */
    public Optional<Registration> get(String namespace) {
        return Optional.ofNullable(registrations.get(namespace));
    }

    /**
     * plugin disable 时调用：移除该 plugin 所有 namespace。
     *
     * <p>注意此方法仅清 registry；变量本身的延迟清理（30s 后 store.delete）由
     * {@code HikariCanvasAPIImpl.purgeNamespaceData} 配合 scheduler 执行。</p>
     *
     * @return 实际移除的 namespace 列表（顺序无保证，仅用于后续调用 purge）
     */
    public List<String> unregisterAllByPlugin(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        List<String> removed = new ArrayList<>();
        registrations.forEach((ns, r) -> {
            if (r.plugin().equals(plugin)) {
                removed.add(ns);
            }
        });
        for (String ns : removed) {
            // 二次校验：避免 race condition 下移除已被替换的 namespace
            registrations.computeIfPresent(ns, (k, v) -> v.plugin().equals(plugin) ? null : v);
        }
        return removed;
    }

    /** 所有注册的 namespace 快照（不可变）。 */
    public Collection<Registration> all() {
        return List.copyOf(registrations.values());
    }

    /** 当前已注册 namespace 数量。 */
    public int size() {
        return registrations.size();
    }

    /** 测试用：清空（不影响外部状态）。 */
    public void clear() {
        registrations.clear();
    }
}
