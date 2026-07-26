package ac.haru.hikaricanvas.variable.provider;

import ac.haru.hikaricanvas.variable.VarType;
import ac.haru.hikaricanvas.variable.VariableException;
import ac.haru.hikaricanvas.variable.VariableStore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PlaceholderAPI 桥接（Tier 4）。
 *
 * <p>软依赖：PAPI 未装时 {@link #initialize()} 自动 noop，整个 {@code papi/*} namespace
 * 不出现；{@link #refreshInterval()} 返 {@link Duration#ZERO}，daemon 不调度——零开销。</p>
 *
 * <p>装了 PAPI 时通过 {@link PapiAccessor} 调
 * {@code PlaceholderAPI.setPlaceholders(null, "%placeholder%")}。PAPI 内部多数 placeholder
 * 是 stateless 查询型，社区主流实现 thread-safe；本类 refresh 跑在 daemon 线程，失败 → log
 * + 不写 value（保留旧 cached，让 fallback 链工作）。</p>
 *
 * <h2>动态注册</h2>
 *
 * <p>玩家在 textarea 输入 <code>${var:papi:%player_name%}</code> → {@link
 * ac.haru.hikaricanvas.variable.VariableInterpolator} resolve miss → {@link
 * VariableStore#notifyDynamicLookup(String)} → 本桥接 hook 触发：自动 register key +
 * 启动定时 refresh（5s TTL）。首次渲染走 fallback "???"，约 5s 内补齐。</p>
 *
 * <h2>fullName 形态 / 编码层</h2>
 *
 * <p>PAPI placeholder 含 {@code %} 字符，而 {@link VariableStore} key 校验正则
 * {@code [a-zA-Z0-9_.-]+} <b>不</b>允许 {@code %}。因此本桥接对 key 做编码：原文
 * {@code %xxx%} ↔ store 内 key {@code pct_xxx_pct}（绕开正则限制）。handleDynamic 收到
 * {@code papi/%xxx%} 或 {@code papi.%xxx%} 形态时解析出真实 placeholder，store key 编码为
 * {@code pct_xxx_pct}，内部 {@code placeholderByKey} map 记录 {@code pct_xxx_pct → %xxx%}；
 * refresh 按原文调 PAPI，按 encoded key 写 store。docs §7.2 记录该语法约定。</p>
 *
 * @see ac.haru.hikaricanvas.variable.VariableInterpolator
 */
public final class PapiVariableBridge implements VariableProvider {

    private static final Logger log = Logger.getLogger(PapiVariableBridge.class.getName());

    public static final String NAMESPACE = "papi";
    public static final long REFRESH_INTERVAL_MS = 5_000L;
    /**
     * 变量 TTL = 刷新周期 × 2。
     *
     * <p>与刷新周期取同一个数会让每轮都出现一段"值刚过期、下一轮还没写进来"的空窗
     * （调度抖动 + 主线程 hop），墙上闪 {@code ???}。留一倍宽限，只有连续两轮没刷上
     * （PAPI 掉了）才走 fallback。</p>
     */
    public static final long TTL_MS = REFRESH_INTERVAL_MS * 2;

    private static final String PAPI_PLUGIN_NAME = "PlaceholderAPI";

    private final VariableStore store;
    private final PapiAccessor accessor;

    /**
     * encoded key (store-safe) → 原文 PAPI placeholder（含 %）。例：
     * {@code "pct_player_name_pct" → "%player_name%"}。
     */
    private final java.util.Map<String, String> placeholderByKey = new ConcurrentHashMap<>();

    /**
     * 生产构造：注入 plugin 用以惰性加载 {@link ReflectionPapiAccessor}。
     */
    public PapiVariableBridge(VariableStore store, JavaPlugin plugin) {
        this(store, new ReflectionPapiAccessor(plugin));
    }

    /**
     * 测试构造：注入 mock {@link PapiAccessor}。
     */
    public PapiVariableBridge(VariableStore store, PapiAccessor accessor) {
        this.store = Objects.requireNonNull(store, "store");
        this.accessor = Objects.requireNonNull(accessor, "accessor");
    }

    @Override public String namespace() { return NAMESPACE; }
    @Override public String displayName() { return "PlaceholderAPI"; }
    @Override public boolean isDynamic() { return true; }

    /**
     * PAPI 未装时返 {@link Duration#ZERO} 让 daemon 不调度（零开销）；装了走 5s。
     */
    @Override
    public Duration refreshInterval() {
        return accessor.isAvailable() ? Duration.ofMillis(REFRESH_INTERVAL_MS) : Duration.ZERO;
    }

    /**
     * 动态注册：declared keys 返当前已 tracked 的 placeholder 列表（PAPI 装了时）/ 空（未装）。
     * Picker 可借此显示用户当前引用过的 PAPI placeholder。
     */
    @Override
    public List<DeclaredKey> declaredKeys() {
        if (!accessor.isAvailable()) return List.of();
        return placeholderByKey.entrySet().stream()
                .map(e -> new DeclaredKey(
                        e.getKey(),
                        VarType.STRING,
                        "PAPI: " + e.getValue(),
                        TTL_MS))
                .toList();
    }

    @Override
    public void initialize() {
        accessor.initialize();
        if (!accessor.isAvailable()) {
            log.info("[HikariCanvas] PlaceholderAPI not found; papi/* namespace disabled");
            return;
        }
        log.info("[HikariCanvas] PlaceholderAPI bridge ready");
        store.registerDynamicLookupHook((fullName, namespace) -> {
            if (NAMESPACE.equals(namespace)) {
                handleDynamic(fullName);
            }
        });
    }

    /**
     * 由 store dynamic lookup hook 调用。接受多种 fullName 形态：
     * <ul>
     *   <li>{@code papi/pct_xxx_pct}（已 encoded） — 已 store-safe，直接 create</li>
     *   <li>{@code papi/%xxx%}（store key 含 %，原文）— 编码为 {@code pct_xxx_pct} 后 create</li>
     *   <li>{@code papi.%xxx%} / {@code papi.pct_xxx_pct}（dot alias 形态） — 同上解析</li>
     * </ul>
     *
     * <p>PAPI 未装时 hook 不会注册，因此 accessor.isAvailable() 一定为 true（防御性也短路）。</p>
     */
    void handleDynamic(String rawFullName) {
        if (rawFullName == null) return;
        if (!accessor.isAvailable()) return;
        ParsedKey parsed = parseFullName(rawFullName);
        if (parsed == null) {
            log.fine("[HikariCanvas] papi: invalid fullName: " + rawFullName);
            return;
        }
        // 已跟踪 → 幂等
        String previous = placeholderByKey.putIfAbsent(parsed.encodedKey, parsed.placeholder);
        if (previous != null) return;
        try {
            store.create(NAMESPACE, parsed.encodedKey, VarType.STRING, null, "papi-bridge");
            log.fine("[HikariCanvas] tracking PAPI placeholder: " + parsed.placeholder);
        } catch (VariableException e) {
            if (e.code() == VariableException.Code.VARIABLE_EXISTS) {
                // 重启 + DB 持久化路径不会经过 papi（papi 非 user namespace）；保留 tracking。
                return;
            }
            // INVALID / QUOTA 等：撤销 tracking 让用户能重试
            placeholderByKey.remove(parsed.encodedKey);
            log.log(Level.FINE,
                    "[HikariCanvas] papi create skipped: " + parsed.placeholder
                            + " (" + e.code() + ")", e);
        }
    }

    @Override
    public boolean refresh() {
        if (!accessor.isAvailable() || placeholderByKey.isEmpty()) return false;
        // PAPI placeholder 多数 stateless thread-safe；直接在 daemon 线程跑。
        // 不切主线程的原因：(a) PAPI 桥接量大时切主线程会拖 TPS；(b) 社区主流 placeholder
        //   stateless；(c) 失败 → catch + log，旧 cached 留着，不污染。
        boolean anyPushed = false;
        for (var entry : placeholderByKey.entrySet()) {
            String encodedKey = entry.getKey();
            String placeholder = entry.getValue();
            String result;
            try {
                result = accessor.resolve(placeholder);
            } catch (Exception e) {
                log.log(Level.FINE,
                        "[HikariCanvas] PAPI resolve failed for "
                                + placeholder + ": " + e.getMessage(), e);
                continue;
            }
            if (result == null) continue;
            try {
                store.setValue(NAMESPACE + "/" + encodedKey, result,
                        Duration.ofMillis(TTL_MS));
                anyPushed = true;
            } catch (VariableException e) {
                if (e.code() == VariableException.Code.VARIABLE_NOT_FOUND) {
                    placeholderByKey.remove(encodedKey);
                } else {
                    log.log(Level.FINE,
                            "[HikariCanvas] papi setValue failed for "
                                    + placeholder + ": " + e.getMessage(), e);
                }
            }
        }
        return anyPushed;
    }

    @Override
    public void shutdown() {
        placeholderByKey.clear();
        accessor.shutdown();
    }

    /** 测试 / 调试：当前已跟踪 placeholder 集合的不可变快照（store-encoded key）。 */
    public Set<String> trackedKeysSnapshot() {
        return Set.copyOf(placeholderByKey.keySet());
    }

    /** 测试 / 调试：encoded key → 原文 placeholder 的不可变快照。 */
    public java.util.Map<String, String> placeholderMappingSnapshot() {
        return java.util.Map.copyOf(placeholderByKey);
    }

    // ──────────────────────────────────────────────────────────
    //  解析 / 编码
    // ──────────────────────────────────────────────────────────

    /** 解析 fullName，返回 (encodedKey, originalPlaceholder)。无效返 null。 */
    static @Nullable ParsedKey parseFullName(String rawFullName) {
        // 拆 namespace
        String tail;
        if (rawFullName.startsWith(NAMESPACE + "/")) {
            tail = rawFullName.substring(NAMESPACE.length() + 1);
        } else if (rawFullName.startsWith(NAMESPACE + ".")) {
            tail = rawFullName.substring(NAMESPACE.length() + 1);
        } else {
            return null;
        }
        if (tail.isEmpty()) return null;
        // tail 形态可能是：%xxx%（原文）/ pct_xxx_pct（已编码）
        String placeholder;
        String encodedKey;
        if (tail.startsWith("%") && tail.endsWith("%") && tail.length() >= 3) {
            placeholder = tail;
            encodedKey = encodeKey(tail);
        } else if (tail.startsWith("pct_") && tail.endsWith("_pct") && tail.length() >= 9) {
            encodedKey = tail;
            placeholder = decodeKey(tail);
        } else {
            return null;
        }
        if (encodedKey == null || placeholder == null) return null;
        return new ParsedKey(encodedKey, placeholder);
    }

    /** {@code "%player_name%"} → {@code "pct_player_name_pct"}。非法格式返 null。 */
    static @Nullable String encodeKey(String placeholder) {
        if (placeholder == null || placeholder.length() < 3) return null;
        if (!placeholder.startsWith("%") || !placeholder.endsWith("%")) return null;
        String inner = placeholder.substring(1, placeholder.length() - 1);
        // 内层只允许 store key 合法字符（不含 _pct_）。
        if (!inner.matches("[a-zA-Z0-9_.\\-]+")) return null;
        return "pct_" + inner + "_pct";
    }

    /** {@code "pct_player_name_pct"} → {@code "%player_name%"}。非法格式返 null。 */
    static @Nullable String decodeKey(String encodedKey) {
        if (encodedKey == null || encodedKey.length() < 9) return null;
        if (!encodedKey.startsWith("pct_") || !encodedKey.endsWith("_pct")) return null;
        String inner = encodedKey.substring(4, encodedKey.length() - 4);
        if (inner.isEmpty()) return null;
        return "%" + inner + "%";
    }

    /** 解析结果。 */
    record ParsedKey(String encodedKey, String placeholder) {}

    // ──────────────────────────────────────────────────────────
    //  PapiAccessor 抽象（reflection 隔离 + 测试友好）
    // ──────────────────────────────────────────────────────────

    /**
     * PAPI 访问抽象。生产用 {@link ReflectionPapiAccessor}（reflection 加载，软依赖）；
     * 测试用 fake 实现。
     */
    public interface PapiAccessor {
        /** 一次性 setup（检测 PAPI / 加载 reflection method）；多次调用幂等。 */
        void initialize();
        /** 是否可用（PAPI 装了 + reflection 成功）。 */
        boolean isAvailable();
        /** 解析 placeholder（如 {@code "%player_name%"}）；失败 / 不可用返 null。 */
        @Nullable String resolve(String placeholder);
        /** 释放资源（可选）。 */
        default void shutdown() {}
    }

    /**
     * 生产 PAPI 实现。通过 {@code Class.forName("me.clip.placeholderapi.PlaceholderAPI")}
     * + {@code Method.invoke(null, null, placeholder)} 调用——避免 build.gradle 加 PAPI 依赖。
     *
     * <p>{@code null} player 参数 = server-wide 解析（不依赖在线玩家）。</p>
     */
    static final class ReflectionPapiAccessor implements PapiAccessor {
        private static final String PAPI_CLASS_NAME = "me.clip.placeholderapi.PlaceholderAPI";

        private final JavaPlugin plugin;
        /**
         * 把原 {@code available}（boolean）+ {@code setPlaceholdersMethod}
         * （Method）两个独立 volatile 合成<b>单一</b> volatile 引用：{@code Method} 引用本身
         * 不可变，{@code null} 即表示禁用（未装 PAPI / 反射失败 / shutdown 后）。{@link #resolve}
         * 一次读到局部变量再判空 + invoke，消除"先读 available=true，再读 method 已被 shutdown
         * 置 null"的撕裂读 + 半态可见。
         */
        private volatile @Nullable Method setPlaceholdersMethod = null;

        ReflectionPapiAccessor(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void initialize() {
            if (setPlaceholdersMethod != null) return; // 幂等
            try {
                if (Bukkit.getPluginManager().getPlugin(PAPI_PLUGIN_NAME) == null) {
                    setPlaceholdersMethod = null;
                    return;
                }
            } catch (Exception e) {
                // Bukkit 未启动（测试 / shutdown 路径）→ 当作未装
                setPlaceholdersMethod = null;
                return;
            }
            try {
                Class<?> papiClass = Class.forName(PAPI_CLASS_NAME);
                Method m = papiClass.getMethod(
                        "setPlaceholders",
                        org.bukkit.OfflinePlayer.class,
                        String.class);
                this.setPlaceholdersMethod = m; // 单次 volatile write 发布"可用 + method"原子状态
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                log.log(Level.WARNING,
                        "[HikariCanvas] PlaceholderAPI present but reflection failed; bridge disabled",
                        e);
                setPlaceholdersMethod = null;
            }
        }

        @Override
        public boolean isAvailable() {
            return setPlaceholdersMethod != null;
        }

        @Override
        public @Nullable String resolve(String placeholder) {
            // 单次读到局部变量，避免两次独立 volatile 读之间被 shutdown 置 null。
            Method m = setPlaceholdersMethod;
            if (m == null) return null;
            try {
                Object out = m.invoke(null, null, placeholder);
                return (out == null) ? null : out.toString();
            } catch (Exception e) {
                log.log(Level.FINE,
                        "[HikariCanvas] PAPI invoke failed for " + placeholder
                                + ": " + e.getMessage(), e);
                return null;
            }
        }

        @Override
        public void shutdown() {
            setPlaceholdersMethod = null;
        }
    }
}
