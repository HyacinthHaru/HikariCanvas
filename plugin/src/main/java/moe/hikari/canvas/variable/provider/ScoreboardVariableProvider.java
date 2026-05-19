package moe.hikari.canvas.variable.provider;

import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.VariableException;
import moe.hikari.canvas.variable.VariableStore;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 0.4.0-P3-J：Bukkit scoreboard 桥接 Provider（Tier 3）。
 *
 * <p>动态 namespace = {@code scoreboard}。引用模式：
 * <code>${var:scoreboard.&lt;objective&gt;.&lt;player&gt;}</code>。第一次被 interpolator
 * 引用时通过 {@link VariableStore#notifyDynamicLookup(String)} → 本 Provider 注册的 hook
 * 触发：自动 {@link VariableStore#create} 变量 + 加入内部 {@link #trackedKeys}，下一次 refresh
 * 起就有数据。首次渲染走 fallback {@code "???"}（约 10s 内补齐）。</p>
 *
 * <h2>线程模型</h2>
 *
 * <p>{@link #refresh()} 在 daemon 线程跑（10s 周期），实际 scoreboard 读必须切主线程
 * （{@code Bukkit.getScoreboardManager()} 严格 main-thread-only）。值写回 store 不限线程
 * （store ConcurrentHashMap）。</p>
 *
 * <p>失败容忍：objective / player 不存在 → 不 setValue（让 fallback 链兜底，避免误覆盖
 * 上次正确值为 "0"）。</p>
 */
public final class ScoreboardVariableProvider implements VariableProvider {

    private static final Logger log =
            Logger.getLogger(ScoreboardVariableProvider.class.getName());

    public static final String NAMESPACE = "scoreboard";
    public static final long REFRESH_INTERVAL_MS = 10_000L;
    public static final long TTL_MS = 10_000L;

    private final VariableStore store;
    private final DataSource dataSource;

    /** 已注册的 key 集合（不含 namespace 前缀，形如 {@code <obj>.<player>}）。 */
    private final Set<String> trackedKeys = ConcurrentHashMap.newKeySet();

    public ScoreboardVariableProvider(VariableStore store, JavaPlugin plugin) {
        this(store, new BukkitDataSource(plugin));
    }

    public ScoreboardVariableProvider(VariableStore store, DataSource dataSource) {
        this.store = Objects.requireNonNull(store, "store");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override public String namespace() { return NAMESPACE; }
    @Override public String displayName() { return "Bukkit Scoreboard"; }
    @Override public Duration refreshInterval() { return Duration.ofMillis(REFRESH_INTERVAL_MS); }
    @Override public boolean isDynamic() { return true; }

    /** 动态注册：declared keys 为空（玩家引用任意 {@code scoreboard.<obj>.<player>} 即创建）。 */
    @Override public List<DeclaredKey> declaredKeys() { return List.of(); }

    @Override
    public void initialize() {
        store.registerDynamicLookupHook((fullName, namespace) -> {
            if (NAMESPACE.equals(namespace)) {
                handleDynamic(fullName);
            }
        });
    }

    /**
     * 由 store dynamic lookup hook 调用。fullName 形如 {@code scoreboard/<obj>.<player>}
     * （store 内部 fullName 编码是 namespace + "/" + key；外部占位符语法是
     * {@code scoreboard.<obj>.<player>}，interpolator 把它当字面 rawName 查 store，
     * 所以这里收到的 fullName 是 {@code scoreboard.<obj>.<player>} 字面——不带 / 分隔）。
     *
     * <p>因此我们需要解析：去掉 {@code "scoreboard."} 前缀，剩下是 {@code <obj>.<player>}。
     * 再拆 obj 和 player（最后一个 {@code .} 为分隔，允许 obj 内部含 {@code .}）。</p>
     */
    void handleDynamic(String rawFullName) {
        if (rawFullName == null) return;
        String key = parseKey(rawFullName);
        if (key == null) return;
        if (!trackedKeys.add(key)) return; // 已跟踪
        try {
            store.create(NAMESPACE, key, VarType.STRING, null, "scoreboard-provider");
        } catch (VariableException e) {
            if (e.code() != VariableException.Code.VARIABLE_EXISTS) {
                log.log(Level.WARNING,
                        "scoreboard create failed for key=" + key + ": " + e.getMessage(), e);
                trackedKeys.remove(key);
                return;
            }
            // 已存在 → 继续跟踪
        }
    }

    /**
     * 解析 dynamic lookup 上来的 fullName，返回 store 内部 key（不含 namespace）。
     * 接受两种形态：
     * <ul>
     *   <li>{@code scoreboard.<obj>.<player>} —— rawName 直接当 fullName 时（interpolator 字面查）</li>
     *   <li>{@code scoreboard/<obj>.<player>} —— store fullName 编码（namespace+/+key）</li>
     * </ul>
     */
    private static @Nullable String parseKey(String raw) {
        if (raw.startsWith(NAMESPACE + "/")) {
            return raw.substring(NAMESPACE.length() + 1);
        }
        if (raw.startsWith(NAMESPACE + ".")) {
            String tail = raw.substring(NAMESPACE.length() + 1);
            // 必须形如 <obj>.<player>（至少一个 .）
            int dot = tail.lastIndexOf('.');
            if (dot <= 0) return null;
            return tail;
        }
        return null;
    }

    @Override
    public boolean refresh() {
        if (trackedKeys.isEmpty()) return false;
        // scoreboard 读 main-thread-only
        dataSource.scheduleMainThread(() -> {
            for (String key : trackedKeys) {
                int lastDot = key.lastIndexOf('.');
                if (lastDot <= 0 || lastDot >= key.length() - 1) continue;
                String objName = key.substring(0, lastDot);
                String playerName = key.substring(lastDot + 1);
                Integer score = dataSource.readScore(objName, playerName);
                if (score == null) continue; // 不写值，让 fallback 兜底
                try {
                    store.setValue(NAMESPACE + "/" + key, score.toString(),
                            Duration.ofMillis(TTL_MS));
                } catch (VariableException e) {
                    if (e.code() == VariableException.Code.VARIABLE_NOT_FOUND) {
                        // 被外部删了 → 从跟踪移除
                        trackedKeys.remove(key);
                    } else {
                        log.log(Level.WARNING,
                                "scoreboard setValue failed for " + key + ": " + e.getMessage(), e);
                    }
                }
            }
        });
        return true;
    }

    @Override
    public void shutdown() {
        trackedKeys.clear();
    }

    /** 当前已跟踪的 key 集合的不可变快照（测试 / 调试用）。 */
    public Set<String> trackedKeysSnapshot() {
        return Set.copyOf(trackedKeys);
    }

    /**
     * Bukkit scoreboard 访问抽象。生产用 {@link BukkitDataSource}；测试注入 mock。
     */
    public interface DataSource {
        void scheduleMainThread(Runnable r);
        /** 读 main scoreboard 的 objective + player score；不存在返 null。 */
        @Nullable Integer readScore(String objective, String player);
    }

    /** 生产 Bukkit 实现。 */
    private static final class BukkitDataSource implements DataSource {
        private final JavaPlugin plugin;

        BukkitDataSource(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void scheduleMainThread(Runnable r) {
            try {
                if (Bukkit.isPrimaryThread()) {
                    r.run();
                } else {
                    Bukkit.getScheduler().runTask(plugin, r);
                }
            } catch (Exception e) {
                log.log(Level.WARNING, "scoreboard scheduleMainThread dispatch failed", e);
            }
        }

        @Override
        public @Nullable Integer readScore(String objectiveName, String playerName) {
            try {
                Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
                Objective obj = sb.getObjective(objectiveName);
                if (obj == null) return null;
                OfflinePlayer op = Bukkit.getOfflinePlayer(playerName);
                Score sc = obj.getScore(op);
                if (sc == null) return null;
                return sc.getScore();
            } catch (Exception e) {
                log.log(Level.WARNING,
                        "scoreboard readScore failed for " + objectiveName
                                + "." + playerName + ": " + e.getMessage(), e);
                return null;
            }
        }
    }
}
