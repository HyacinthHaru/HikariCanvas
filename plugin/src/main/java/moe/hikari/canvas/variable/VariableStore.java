package moe.hikari.canvas.variable;

import moe.hikari.canvas.storage.UserVariableDao;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 全局变量内存存储。0.4.0-P1 核心类。
 *
 * <p>详见 {@code docs/dynamic-data.md §2.2}。本类承担：
 * <ul>
 *   <li>四类数据源（user / plugin / system / papi）共用的内存表</li>
 *   <li>原子读写（{@link ConcurrentHashMap#compute}）</li>
 *   <li>per-wall 倒排索引：知道哪些 wall 引用了变量 → 变量值变时只 mark 那些 wall dirty</li>
 *   <li>用户变量持久化：写穿到 {@link UserVariableDao}（仅 user namespace）</li>
 *   <li>启动期 {@link #loadFromDb} 把 DB user 变量加载回内存</li>
 * </ul>
 *
 * <p><b>fullName 编码</b>（见 {@link Variable}）：user 变量 namespace 形如
 * {@code user:<wallId>}，整体作为 namespace 字段，避免与普通插件 {@code user/X} 冲突。
 * 持久化时拆出 wallId 写到 {@code user_variables.wall_id} 列。</p>
 *
 * <p><b>线程安全</b>：所有变更走 {@code store.compute} / {@code computeIfPresent}；
 * byWall 用 {@code newKeySet} 视图。读取多份字段时建议先 {@link #get} 拿快照再处理。</p>
 */
public final class VariableStore {

    /**
     * namespace 校验：{@code [a-zA-Z_][a-zA-Z0-9_:-]*}
     * （冒号支持 user:<wallId> 形式；连字符兼容 wallId 形如 {@code w-3a17b2c1}）。
     */
    private static final Pattern NAMESPACE_RE = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_:\\-]*");
    /** key 校验：{@code [a-zA-Z0-9_.-]+}。 */
    private static final Pattern KEY_RE = Pattern.compile("[a-zA-Z0-9_.\\-]+");

    /** 边界（dynamic-data.md §9.4 + §16-9）。 */
    public static final int MAX_NAMESPACE_LENGTH = 32;
    public static final int MAX_KEY_LENGTH = 64;
    public static final int MAX_VALUE_LENGTH = 4096;
    public static final int MAX_PER_NAMESPACE = 1000;
    public static final int MAX_GLOBAL = 10000;
    public static final long MIN_TTL_MS = 100L;

    /** {@code "user"} = 用户变量保留 namespace 前缀；user:<wallId> 用冒号拓展。 */
    public static final String USER_NAMESPACE_PREFIX = "user";

    /** fullName → Variable 主表。 */
    private final ConcurrentHashMap<String, Variable> store = new ConcurrentHashMap<>();
    /** namespace → fullName set（per-namespace 配额计数）。 */
    private final ConcurrentHashMap<String, Set<String>> byNamespace = new ConcurrentHashMap<>();
    /** wallId → fullName set（倒排索引；Compositor render 前 markWallReferences 维护）。 */
    private final ConcurrentHashMap<String, Set<String>> byWall = new ConcurrentHashMap<>();

    private final UserVariableDao dao;
    /** 任意 wall dirty 通知（B 任务接到 ProjectionThrottler#dirty）；P1 阶段可为 noop。 */
    private final java.util.function.Consumer<String> wallDirtyCallback;

    public VariableStore(UserVariableDao dao,
                         java.util.function.Consumer<String> wallDirtyCallback) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.wallDirtyCallback = wallDirtyCallback == null ? wid -> {} : wallDirtyCallback;
    }

    // ──────────────────────────────────────────────────────────────────
    //  增删改查
    // ──────────────────────────────────────────────────────────────────

    /**
     * 创建变量。namespace 形如 {@code "bedwars"} 或 {@code "user:<wallId>"}。
     *
     * @throws VariableException 名字非法 / 已存在 / 超配额 / 值过长 / TTL 非法
     */
    public Variable create(String namespace, String key, VarType type,
                           @Nullable String defaultValue, @Nullable String source) {
        validateNamespace(namespace);
        validateKey(key);
        validateValueLength(defaultValue);
        if (type == null) {
            throw new VariableException(VariableException.Code.VARIABLE_TYPE_MISMATCH,
                    "type must not be null");
        }

        String fullName = namespace + "/" + key;
        long now = System.currentTimeMillis();

        // 全局配额（先检查不抢锁；后续在 compute 内做最终防 race 检查）
        if (store.size() >= MAX_GLOBAL) {
            throw new VariableException(VariableException.Code.QUOTA_EXCEEDED,
                    "global variable quota exceeded: " + MAX_GLOBAL);
        }

        Variable created = store.compute(fullName, (k, existing) -> {
            if (existing != null) {
                throw new VariableException(VariableException.Code.VARIABLE_EXISTS,
                        "variable already exists: " + k);
            }
            if (store.size() >= MAX_GLOBAL) {
                throw new VariableException(VariableException.Code.QUOTA_EXCEEDED,
                        "global variable quota exceeded: " + MAX_GLOBAL);
            }
            Set<String> nsBucket = byNamespace.computeIfAbsent(namespace,
                    n -> ConcurrentHashMap.newKeySet());
            if (nsBucket.size() >= MAX_PER_NAMESPACE) {
                throw new VariableException(VariableException.Code.QUOTA_EXCEEDED,
                        "namespace variable quota exceeded for " + namespace
                                + ": " + MAX_PER_NAMESPACE);
            }
            nsBucket.add(k);
            return new Variable(namespace, key, type, defaultValue, null,
                    now, 0L, source, Collections.unmodifiableSet(new HashSet<>()));
        });

        persistIfUser(created, now, now);
        return created;
    }

    /**
     * 改 type / defaultValue（patch 部分应用）。currentValue 走 {@link #setValue}。
     */
    public Variable update(String fullName, VariablePatch patch) {
        Objects.requireNonNull(patch, "patch");
        long now = System.currentTimeMillis();
        Variable[] resultHolder = new Variable[1];
        long[] createdAtHolder = new long[1];

        store.computeIfPresent(fullName, (k, existing) -> {
            VarType newType = patch.type() != null ? patch.type() : existing.type();
            String newDefault = patch.defaultValue() != null
                    ? patch.defaultValue() : existing.defaultValue();
            validateValueLength(newDefault);
            Variable updated = new Variable(existing.namespace(), existing.key(),
                    newType, newDefault, existing.currentValue(),
                    now, existing.ttl(), existing.source(),
                    existing.referencedByWalls());
            resultHolder[0] = updated;
            // createdAt 不在 Variable record 里——用 updatedAt 作 createdAt 兜底（仅写库用）；
            // upsert 会保持已存在 wall+name 的 created_at 不动（DO UPDATE 不写 created_at）。
            createdAtHolder[0] = now;
            return updated;
        });

        Variable updated = resultHolder[0];
        if (updated == null) {
            throw new VariableException(VariableException.Code.VARIABLE_NOT_FOUND,
                    "variable not found: " + fullName);
        }

        persistIfUser(updated, createdAtHolder[0], now);
        notifyReferencingWalls(updated);
        return updated;
    }

    /**
     * 设当前值（手动 set / 插件 push 共用）。
     *
     * @param ttl null 沿用原 TTL；{@link Duration#ZERO} = 永久；&gt;0 必须 ≥ {@link #MIN_TTL_MS}
     */
    public void setValue(String fullName, @Nullable String value, @Nullable Duration ttl) {
        validateValueLength(value);
        long ttlMs = validateTtl(ttl); // returns -1 sentinel for "no change"
        long now = System.currentTimeMillis();

        Variable[] resultHolder = new Variable[1];
        store.computeIfPresent(fullName, (k, existing) -> {
            long effectiveTtl = ttlMs < 0 ? existing.ttl() : ttlMs;
            Variable updated = new Variable(existing.namespace(), existing.key(),
                    existing.type(), existing.defaultValue(), value,
                    now, effectiveTtl, existing.source(),
                    existing.referencedByWalls());
            resultHolder[0] = updated;
            return updated;
        });

        Variable updated = resultHolder[0];
        if (updated == null) {
            throw new VariableException(VariableException.Code.VARIABLE_NOT_FOUND,
                    "variable not found: " + fullName);
        }

        persistIfUser(updated, now, now);
        notifyReferencingWalls(updated);
    }

    public void delete(String fullName) {
        Variable removed = store.remove(fullName);
        if (removed == null) {
            throw new VariableException(VariableException.Code.VARIABLE_NOT_FOUND,
                    "variable not found: " + fullName);
        }
        Set<String> nsBucket = byNamespace.get(removed.namespace());
        if (nsBucket != null) {
            nsBucket.remove(fullName);
            // 不主动清空 nsBucket：留住 namespace 桶给将来同 ns 创建复用 set 对象。
        }
        // 清掉所有 wall 倒排索引里的引用
        for (Set<String> wallBucket : byWall.values()) {
            wallBucket.remove(fullName);
        }
        // 持久化清理（仅 user 变量）
        if (removed.namespace().startsWith(USER_NAMESPACE_PREFIX + ":")) {
            String wallId = parseWallIdFromUserNamespace(removed.namespace());
            if (wallId != null) {
                dao.delete(wallId, removed.key());
            }
        }
        notifyReferencingWalls(removed);
    }

    /**
     * 绑定到插件 namespace 或解绑（null）。bind 不改 type / value，只更新
     * {@code source}（同时持久化 user 变量的 {@code bound_to}）。
     */
    public void bind(String fullName, @Nullable String pluginNamespace) {
        long now = System.currentTimeMillis();
        Variable[] resultHolder = new Variable[1];

        store.computeIfPresent(fullName, (k, existing) -> {
            Variable updated = new Variable(existing.namespace(), existing.key(),
                    existing.type(), existing.defaultValue(), existing.currentValue(),
                    now, existing.ttl(), pluginNamespace,
                    existing.referencedByWalls());
            resultHolder[0] = updated;
            return updated;
        });

        Variable updated = resultHolder[0];
        if (updated == null) {
            throw new VariableException(VariableException.Code.VARIABLE_NOT_FOUND,
                    "variable not found: " + fullName);
        }

        persistIfUser(updated, now, now);
        notifyReferencingWalls(updated);
    }

    public Optional<Variable> get(String fullName) {
        return Optional.ofNullable(store.get(fullName));
    }

    public List<Variable> listAll() {
        return new ArrayList<>(store.values());
    }

    public List<Variable> listByNamespace(String namespace) {
        Set<String> bucket = byNamespace.get(namespace);
        if (bucket == null || bucket.isEmpty()) return List.of();
        List<Variable> out = new ArrayList<>(bucket.size());
        for (String fn : bucket) {
            Variable v = store.get(fn);
            if (v != null) out.add(v);
        }
        return out;
    }

    /**
     * 列出 wall 当前引用的变量。注意：依赖 {@link #markWallReferences} 已被 Compositor
     * 调用过。未引用过任何变量的 wall 返空表。
     */
    public List<Variable> listByWall(String wallId) {
        Set<String> bucket = byWall.get(wallId);
        if (bucket == null || bucket.isEmpty()) return List.of();
        List<Variable> out = new ArrayList<>(bucket.size());
        for (String fn : bucket) {
            Variable v = store.get(fn);
            if (v != null) out.add(v);
        }
        return out;
    }

    // ──────────────────────────────────────────────────────────────────
    //  倒排索引维护
    // ──────────────────────────────────────────────────────────────────

    /**
     * Compositor 渲染前调用：把 {@code wallId} 当前实际引用的变量集合写入倒排索引。
     *
     * <p>本方法会把"上次记录引用、但本次不在集合内"的变量从 wall 的 {@code referencedByWalls}
     * 反向索引中移除——保持索引精确。</p>
     */
    public void markWallReferences(String wallId, Set<String> referencedFullNames) {
        Objects.requireNonNull(wallId, "wallId");
        Set<String> normalized = referencedFullNames == null
                ? Set.of() : Set.copyOf(referencedFullNames);

        Set<String> bucket = byWall.computeIfAbsent(wallId,
                w -> ConcurrentHashMap.newKeySet());

        // diff：先算出离开的 + 新加入的，逐项更新 store 内 Variable.referencedByWalls
        Set<String> previous = new HashSet<>(bucket);
        Set<String> removed = new HashSet<>(previous);
        removed.removeAll(normalized);
        Set<String> added = new HashSet<>(normalized);
        added.removeAll(previous);

        for (String fn : removed) {
            bucket.remove(fn);
            removeWallFromReferencedSet(fn, wallId);
        }
        for (String fn : added) {
            bucket.add(fn);
            addWallToReferencedSet(fn, wallId);
        }
    }

    /**
     * Wall 删除时调用（B 任务在 walls 表 DELETE 后联动）：从所有变量的倒排索引清掉 wallId。
     * 同时不清持久化 user_variables，由外部走 {@link UserVariableDao#deleteByWall} 或 FK CASCADE。
     */
    public void clearWallReferences(String wallId) {
        Set<String> bucket = byWall.remove(wallId);
        if (bucket == null) return;
        for (String fn : bucket) {
            removeWallFromReferencedSet(fn, wallId);
        }
    }

    private void addWallToReferencedSet(String fullName, String wallId) {
        store.computeIfPresent(fullName, (k, existing) -> {
            HashSet<String> newSet = new HashSet<>(existing.referencedByWalls());
            newSet.add(wallId);
            return new Variable(existing.namespace(), existing.key(), existing.type(),
                    existing.defaultValue(), existing.currentValue(),
                    existing.updatedAt(), existing.ttl(), existing.source(),
                    Collections.unmodifiableSet(newSet));
        });
    }

    private void removeWallFromReferencedSet(String fullName, String wallId) {
        store.computeIfPresent(fullName, (k, existing) -> {
            if (!existing.referencedByWalls().contains(wallId)) return existing;
            HashSet<String> newSet = new HashSet<>(existing.referencedByWalls());
            newSet.remove(wallId);
            return new Variable(existing.namespace(), existing.key(), existing.type(),
                    existing.defaultValue(), existing.currentValue(),
                    existing.updatedAt(), existing.ttl(), existing.source(),
                    Collections.unmodifiableSet(newSet));
        });
    }

    // ──────────────────────────────────────────────────────────────────
    //  启动加载
    // ──────────────────────────────────────────────────────────────────

    /**
     * 启动期一次性把 DB 内 user_variables 加载到内存。
     *
     * <p>遇到 namespace / key 校验失败的行不抛——log + skip 让其他变量正常加载（防一坏全坏）。</p>
     */
    public void loadFromDb() {
        for (UserVariableDao.Row r : dao.loadAll()) {
            String namespace = USER_NAMESPACE_PREFIX + ":" + r.wallId();
            try {
                validateNamespace(namespace);
                validateKey(r.name());
                validateValueLength(r.defaultValue());
                validateValueLength(r.currentValue());
            } catch (VariableException e) {
                // 跳过坏行
                continue;
            }
            String fullName = namespace + "/" + r.name();
            Variable v = new Variable(namespace, r.name(), r.type(),
                    r.defaultValue(), r.currentValue(),
                    r.updatedAt(), 0L, r.boundTo(),
                    Collections.unmodifiableSet(new HashSet<>()));
            // 直接绕过 create 的全局配额校验（DB 里的数据视为合法基线）
            store.put(fullName, v);
            byNamespace.computeIfAbsent(namespace, n -> ConcurrentHashMap.newKeySet())
                    .add(fullName);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  内部 helper
    // ──────────────────────────────────────────────────────────────────

    /**
     * 触发 wall dirty。<b>仅</b>对当前 referencedByWalls 集合内的 wall 触发；create 时
     * 集合为空（变量刚建无 referencer），不触发——这是按任务要求设计的。
     */
    private void notifyReferencingWalls(Variable v) {
        for (String wallId : v.referencedByWalls()) {
            try {
                wallDirtyCallback.accept(wallId);
            } catch (Exception ignored) {
                // callback 抛不该拖垮 store 主路径
            }
        }
    }

    /** 仅当 namespace 形如 {@code user:<wallId>} 时落库；其他 namespace 内存态。 */
    private void persistIfUser(Variable v, long createdAt, long updatedAt) {
        if (!v.namespace().startsWith(USER_NAMESPACE_PREFIX + ":")) return;
        String wallId = parseWallIdFromUserNamespace(v.namespace());
        if (wallId == null) return;
        dao.upsert(wallId, v.key(), v.type(),
                v.defaultValue(), v.currentValue(), v.source(),
                createdAt, updatedAt);
    }

    /** {@code "user:w-3a17"} → {@code "w-3a17"}；非法格式返 null。 */
    private static @Nullable String parseWallIdFromUserNamespace(String namespace) {
        int colon = namespace.indexOf(':');
        if (colon < 0 || colon == namespace.length() - 1) return null;
        return namespace.substring(colon + 1);
    }

    private static void validateNamespace(String namespace) {
        if (namespace == null || namespace.isEmpty()
                || namespace.length() > MAX_NAMESPACE_LENGTH
                || !NAMESPACE_RE.matcher(namespace).matches()) {
            throw new VariableException(VariableException.Code.VARIABLE_NAME_INVALID,
                    "invalid namespace: " + namespace);
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty()
                || key.length() > MAX_KEY_LENGTH
                || !KEY_RE.matcher(key).matches()) {
            throw new VariableException(VariableException.Code.VARIABLE_NAME_INVALID,
                    "invalid key: " + key);
        }
    }

    private static void validateValueLength(@Nullable String value) {
        if (value != null && value.length() > MAX_VALUE_LENGTH) {
            throw new VariableException(VariableException.Code.VARIABLE_VALUE_TOO_LONG,
                    "value exceeds " + MAX_VALUE_LENGTH + " chars (got "
                            + value.length() + ")");
        }
    }

    /**
     * @return -1 = "no change"（ttl 入参 null）；0 = 永久；>0 = TTL ms（≥ MIN_TTL_MS）
     */
    private static long validateTtl(@Nullable Duration ttl) {
        if (ttl == null) return -1L;
        long ms = ttl.toMillis();
        if (ms < 0) {
            throw new VariableException(VariableException.Code.TTL_INVALID,
                    "ttl must be >= 0 (got " + ms + "ms)");
        }
        if (ms > 0 && ms < MIN_TTL_MS) {
            throw new VariableException(VariableException.Code.TTL_INVALID,
                    "ttl must be 0 (永久) or >= " + MIN_TTL_MS + "ms (got " + ms + "ms)");
        }
        return ms;
    }

    // ──────────────────────────────────────────────────────────────────
    //  测试 / 调试 hooks
    // ──────────────────────────────────────────────────────────────────

    /** 当前 store 内变量总数。 */
    public int size() {
        return store.size();
    }

    /**
     * 测试用：清空所有内存状态（不动 DB）。生产代码不要调。
     */
    void clearForTest() {
        store.clear();
        byNamespace.clear();
        byWall.clear();
    }

    /** 测试 / 调试：当前 wall 引用集合的不可变快照。 */
    public Set<String> referencedFullNamesByWall(String wallId) {
        Set<String> bucket = byWall.get(wallId);
        if (bucket == null) return Set.of();
        return Set.copyOf(bucket);
    }
}
