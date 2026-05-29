package moe.hikari.canvas.variable;

import moe.hikari.canvas.storage.UserGlobalVariableDao;
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
import java.util.UUID;
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

    /**
     * 0.4.3：全局用户变量 namespace。不带冒号 + wallId 后缀（与 {@link #USER_NAMESPACE_PREFIX}
     * 的 per-wall 区别）；fullName 形如 {@code userglobal/<key>}，全服共享。
     */
    public static final String USER_GLOBAL_NAMESPACE = "userglobal";

    /** 0.4.3 默认配额（{@code docs/dynamic-data.md §17.4}）。config.yml 可覆盖。 */
    public static final int DEFAULT_USERGLOBAL_PER_OWNER = 500;
    public static final int DEFAULT_USERGLOBAL_TOTAL = 10_000;

    /** fullName → Variable 主表。 */
    private final ConcurrentHashMap<String, Variable> store = new ConcurrentHashMap<>();
    /** namespace → fullName set（per-namespace 配额计数）。 */
    private final ConcurrentHashMap<String, Set<String>> byNamespace = new ConcurrentHashMap<>();
    /** wallId → fullName set（倒排索引；Compositor render 前 markWallReferences 维护）。 */
    private final ConcurrentHashMap<String, Set<String>> byWall = new ConcurrentHashMap<>();
    /**
     * 0.4.10 P2-23：markWallReferences 的 per-wallId 条带锁。
     *
     * <p>原 markWallReferences 的「快照 previous → 算 diff → 逐项写 bucket + referencedByWalls」
     * 三步非原子：同一 wall 的两个并发 markWallReferences（如 Compositor render 线程 + ready
     * payload 构造）交错执行会让 byWall 桶与各 Variable.referencedByWalls 互相不对称（一个有、
     * 另一个无）。用 per-wallId monitor 把整段串行化，对外原子可见。markWallReferences 调用
     * 频率低（仅渲染 / refresh 时），per-wall 锁开销可忽略。</p>
     */
    private final ConcurrentHashMap<String, Object> wallRefMonitors = new ConcurrentHashMap<>();

    private final UserVariableDao dao;
    /** 任意 wall dirty 通知（B 任务接到 ProjectionThrottler#dirty）；P1 阶段可为 noop。 */
    private final java.util.function.Consumer<String> wallDirtyCallback;

    // ── 0.4.3：全局用户变量持久化 + owner 信息（namespace = userglobal） ──

    /**
     * 0.4.3：{@code userglobal/*} 持久化 DAO。装配期由 {@link #configureUserGlobal} 注入；
     * 未注入时 {@link #createGlobal} 抛 INTERNAL；现有 user / 插件 / system / papi / schedule
     * 路径完全不受影响。
     */
    @Nullable
    private volatile UserGlobalVariableDao globalDao;

    private volatile int maxUserGlobalPerOwner = DEFAULT_USERGLOBAL_PER_OWNER;
    private volatile int maxUserGlobalTotal = DEFAULT_USERGLOBAL_TOTAL;

    /**
     * 0.4.10 P2-22 / P3-14：createGlobal 配额检查（per-owner + total）与后续 create→persist
     * 之间存在 TOCTOU 窗口——两个会话同一 owner 并发可双双过 count 检查越过上限。
     * createGlobal 频率极低（玩家手动建全局变量），用一把锁串行化整个 count→create→persist
     * 流程，强制 happens-before，开销可忽略。
     */
    private final java.util.concurrent.locks.ReentrantLock globalCreateLock =
            new java.util.concurrent.locks.ReentrantLock();

    /**
     * 0.4.3：全局变量 key → owner 信息（在内存）。
     *
     * <p>{@link Variable} record 的 {@code source} 字段已被 bind 语义占用（写 plugin namespace），
     * 不能复用承载 owner。owner 仅 {@code userglobal} namespace 需要——独立内存表代价更小，
     * 启动 {@link #loadGlobalsFromDb} 一次性填充，create / delete 主路径同步维护。</p>
     *
     * <p>读取入口：{@link #getGlobalOwner} —— 序列化路径（{@code VariableDto.from} /
     * {@code SessionManager.broadcastVariableChangeToAll}）调用注入 ownerUuid / ownerName 给前端。</p>
     */
    public record GlobalOwnerInfo(UUID ownerUuid, String ownerName) {}

    private final ConcurrentHashMap<String, GlobalOwnerInfo> globalOwners = new ConcurrentHashMap<>();

    /**
     * 0.4.0-P3-J：动态 namespace 注册 hook。
     *
     * <p>{@link VariableInterpolator} 在 {@code resolve} 时若 store 内查不到 fullName，会逐个
     * 调用已注册的 hook（BiConsumer 参数为 fullName + namespace）。动态 Provider（如
     * {@link moe.hikari.canvas.variable.provider.ScoreboardVariableProvider}）可在 hook 里
     * 自筛 namespace 匹配，决定是否自动 {@link #create} + 加入 refresh tracking。</p>
     *
     * <p>注意：hook 在调用线程同步执行——hook 实现应快速返回（异步处理走自己的 daemon），
     * 不阻塞 interpolate；hook 抛异常会被吞掉 + log warning（详见 {@link #notifyDynamicLookup}）。</p>
     */
    private final java.util.List<java.util.function.BiConsumer<String, String>> dynamicLookupHooks =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 0.4.0 bugfix3（Bug B）：变量变更广播 listener。
     *
     * <p>EditSession 走 OpResult.dirty 触发 pushPatch 给前端 mirror，但 Provider 直接调
     * {@link #create} / {@link #setValue} / {@link #bind} / {@link #delete} 不走该路径——
     * 前端 mirror 永远拿不到 Provider 自动更新的值（典型：ManualScheduleProvider 写
     * schedule:wallId/* 七变量 → editor 内 VariablePanel 全显 "—"，TextElement live preview
     * 显 "???"）。本 listener 钩子兜底：Provider 写值时主动推 state.patch。</p>
     *
     * <p>listener 在 mutation 临界区<b>之外</b>调（避免持锁回调）；单 listener 抛异常被吞掉
     * + log warning，不影响其他 listener / store 主路径。HikariCanvas onEnable 注入实际 listener
     * 把变更通过 SessionManager → OpPushCallback.pushPatch 广播给绑定该 wall 的所有 session。</p>
     */
    private final java.util.List<VariableChangeListener> changeListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public VariableStore(UserVariableDao dao,
                         java.util.function.Consumer<String> wallDirtyCallback) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.wallDirtyCallback = wallDirtyCallback == null ? wid -> {} : wallDirtyCallback;
    }

    /**
     * 0.4.0-P3-J：注册动态 namespace 查找 hook。
     *
     * <p>由 Provider 在 {@link moe.hikari.canvas.variable.provider.VariableProvider#initialize()}
     * 内调用一次；hook 在 {@link VariableInterpolator} 遇 missing 变量时同步被回调。</p>
     *
     * @param hook 接收 (fullName, namespace) 的回调；hook 自己负责按 namespace 自筛。
     */
    public void registerDynamicLookupHook(
            java.util.function.BiConsumer<String, String> hook) {
        Objects.requireNonNull(hook, "hook");
        dynamicLookupHooks.add(hook);
    }

    /**
     * 0.4.0-P3-J：由 {@link VariableInterpolator} 在 resolve miss 时调用，通知所有注册过的
     * 动态 namespace hook。本方法不阻塞调用线程语义之外：hook 抛异常被吞掉 + log warning。
     *
     * @param fullName missing 的 fullName（已注入 wallId 形态，如 {@code user:w-1/X}）
     */
    public void notifyDynamicLookup(String fullName) {
        if (fullName == null || dynamicLookupHooks.isEmpty()) return;
        // namespace 提取：优先 '/' 分隔（store 标准 fullName 形态 namespace/key）；
        // 若无 '/'，退而求其次按第一个 '.' 分（外部点分号 alias 形态如 scoreboard.<obj>.<player>）。
        int slash = fullName.indexOf('/');
        String namespace;
        if (slash >= 0) {
            namespace = fullName.substring(0, slash);
        } else {
            int dot = fullName.indexOf('.');
            namespace = dot > 0 ? fullName.substring(0, dot) : fullName;
        }
        for (var hook : dynamicLookupHooks) {
            try {
                hook.accept(fullName, namespace);
            } catch (Exception e) {
                java.util.logging.Logger.getLogger(VariableStore.class.getName())
                        .log(java.util.logging.Level.WARNING,
                                "dynamic lookup hook threw for " + fullName + ": " + e.getMessage(),
                                e);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  0.4.0 bugfix3（Bug B）：ChangeListener API
    // ──────────────────────────────────────────────────────────────────

    /**
     * 变更类型。Listener 据此决定如何构造 state.patch op。
     *
     * <p>{@link #WALL_REFS_UPDATED}（0.4.0 方案 B 自适应渲染）—— 一次 {@link #markWallReferences}
     * 调用后 wall 的引用集合发生过实际变化（增加 / 移除任意 fullName）。listener 收到时不该推
     * state.patch（fullName 字段语义占位为 {@code "<bulk_wall_refs>"}，variable 为 null），
     * 只用于触发"该 wall 高频状态可能改变了，重新评估 ProjectionThrottler 间隔"。</p>
     */
    public enum ChangeType { CREATED, UPDATED, VALUE_SET, BOUND, DELETED, WALL_REFS_UPDATED }

    /**
     * 变更事件。listener 拿此构造 RFC 6902 PatchOp 推送给前端 mirror。
     *
     * @param fullName          变更的 fullName（如 {@code schedule:w-1/eta_seconds}）
     * @param variable          mutation 之后的最新 Variable；DELETED 时为 null
     * @param type              变更类型
     * @param referencingWalls  该变量当前 referencedByWalls 的不可变快照
     *                          （DELETED 时取 mutation 前的快照——避免删除后无人能收到）
     */
    public record VariableChangeEvent(
            String fullName,
            @Nullable Variable variable,
            ChangeType type,
            Set<String> referencingWalls
    ) {
    }

    /** 变更监听器 — 由 HikariCanvas onEnable 注入实际广播逻辑。 */
    @FunctionalInterface
    public interface VariableChangeListener {
        void onChange(VariableChangeEvent event);
    }

    /** 注册 listener。多次注册 = 多 listener，按注册顺序触发。 */
    public void registerChangeListener(VariableChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        changeListeners.add(listener);
    }

    /**
     * 触发所有 listener。<b>不持锁调</b>——所有 mutation 方法在 compute*块之后调本方法。
     * 单 listener 抛异常被吞掉 + log warning，不影响其他 listener 或 store 主路径。
     */
    private void fireChange(String fullName, @Nullable Variable v, ChangeType type) {
        if (changeListeners.isEmpty()) return;
        Set<String> walls = v == null ? Set.of() : Set.copyOf(v.referencedByWalls());
        VariableChangeEvent event = new VariableChangeEvent(fullName, v, type, walls);
        for (VariableChangeListener listener : changeListeners) {
            try {
                listener.onChange(event);
            } catch (Exception e) {
                java.util.logging.Logger.getLogger(VariableStore.class.getName())
                        .log(java.util.logging.Level.WARNING,
                                "VariableChangeListener threw for " + fullName
                                        + " (" + type + "): " + e.getMessage(), e);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  0.4.3：全局用户变量装配 + API
    // ──────────────────────────────────────────────────────────────────

    /**
     * 0.4.3：注入 {@link UserGlobalVariableDao} + 配额参数。装配期 HikariCanvas onEnable 调用
     * 一次；如果不调 {@link #createGlobal} 直接抛 {@code QUOTA_EXCEEDED}，已有 user / 插件
     * / system / papi 路径完全不受影响。
     *
     * @param dao            user_global_variables DAO（非空）
     * @param maxPerOwner    每 owner 上限（默 {@link #DEFAULT_USERGLOBAL_PER_OWNER}）
     * @param maxTotal       全服总上限（默 {@link #DEFAULT_USERGLOBAL_TOTAL}）
     */
    public void configureUserGlobal(UserGlobalVariableDao dao,
                                    int maxPerOwner, int maxTotal) {
        this.globalDao = Objects.requireNonNull(dao, "globalDao");
        this.maxUserGlobalPerOwner = Math.max(1, maxPerOwner);
        // 注意：不强制 maxTotal ≥ maxPerOwner —— 让 admin 直接信任输入。
        // 若 admin 写反（total < per_owner），create 时 total 配额先触顶，per_owner 实际无效，
        // 这是合理的"管理员意图优先"语义。
        this.maxUserGlobalTotal = Math.max(1, maxTotal);
    }

    /**
     * 0.4.3：创建全局用户变量 {@code userglobal/<key>}。owner 信息（UUID + 名）记录到内存
     * {@link #globalOwners} 表 + 写 {@code user_global_variables} 表（持久化）。
     *
     * <p>配额检查（{@code docs/dynamic-data.md §17.4}）：</p>
     * <ul>
     *   <li>per-owner ≥ {@link #maxUserGlobalPerOwner} → {@code QUOTA_EXCEEDED}</li>
     *   <li>全服总数 ≥ {@link #maxUserGlobalTotal} → {@code QUOTA_EXCEEDED}</li>
     * </ul>
     *
     * @throws VariableException 名字非法 / 已存在 / 超配额 / 值过长 / dao 未装配
     */
    public Variable createGlobal(UUID ownerUuid, String ownerName,
                                 String key, VarType type, @Nullable String defaultValue) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(ownerName, "ownerName");
        if (globalDao == null) {
            throw new VariableException(VariableException.Code.QUOTA_EXCEEDED,
                    "user global variables not configured (call configureUserGlobal first)");
        }
        // 先做 key / type / value 校验（与 create 一致）
        validateKey(key);
        validateValueLength(defaultValue);
        if (type == null) {
            throw new VariableException(VariableException.Code.VARIABLE_TYPE_MISMATCH,
                    "type must not be null");
        }
        // 0.4.10 P2-22 / P3-14：整段 count→create→persist 在锁内串行，消除跨会话 TOCTOU。
        globalCreateLock.lock();
        try {
            // 配额：DB 查 count + compute 内还有全局 MAX_GLOBAL 兜底
            int ownerCount = globalDao.countByOwner(ownerUuid);
            if (ownerCount >= maxUserGlobalPerOwner) {
                throw new VariableException(VariableException.Code.QUOTA_EXCEEDED,
                        "userglobal quota exceeded for owner ("
                                + ownerCount + "/" + maxUserGlobalPerOwner + ")");
            }
            int totalCount = globalDao.countTotal();
            if (totalCount >= maxUserGlobalTotal) {
                throw new VariableException(VariableException.Code.QUOTA_EXCEEDED,
                        "userglobal quota exceeded globally ("
                                + totalCount + "/" + maxUserGlobalTotal + ")");
            }

            // 在 store.create 之前 put ownerInfo，让 persistIfUserGlobal 能读到 owner
            // 注意：putIfAbsent 防 race；如已存在 — VARIABLE_EXISTS 检查会在 create 内拒
            GlobalOwnerInfo info = new GlobalOwnerInfo(ownerUuid, ownerName);
            GlobalOwnerInfo prev = globalOwners.putIfAbsent(key, info);
            boolean ownerPlacedHere = (prev == null);
            try {
                // source = "manual" 同 user 变量；bind 后会改写
                // 0.4.10 P2-31：userglobal 跳过 per-namespace 配额（MAX_PER_NAMESPACE=1000），
                // 改由 maxUserGlobalTotal（默 10000，已在上方校验）封顶，让 config 的 total 真正可达。
                return create(USER_GLOBAL_NAMESPACE, key, type, defaultValue, "manual", true);
            } catch (RuntimeException e) {
                // create 失败回滚 owner 表（仅当本调用是首次 put 才回滚）
                if (ownerPlacedHere) globalOwners.remove(key, info);
                throw e;
            }
        } finally {
            globalCreateLock.unlock();
        }
    }

    /** 0.4.3：列所有 {@code userglobal/*} 变量（无序）。 */
    public List<Variable> listGlobals() {
        return listByNamespace(USER_GLOBAL_NAMESPACE);
    }

    /**
     * 0.4.3：查 userglobal 变量的 owner 信息。
     *
     * <p>序列化路径（{@code VariableDto.from} / patchOp builder）调用注入 ownerUuid /
     * ownerName 给前端。非 userglobal 变量 key 命中也可能返非空——调用方应先确认
     * variable.namespace == userglobal 再用。</p>
     *
     * @param key 变量 key（不含 namespace 前缀）
     */
    public Optional<GlobalOwnerInfo> getGlobalOwner(String key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(globalOwners.get(key));
    }

    /**
     * 0.4.3：启动期一次性把 {@code user_global_variables} 加载到内存（同
     * {@link #loadFromDb} 风格，绕过 create 配额校验，把 DB 视为合法基线）。
     */
    public void loadGlobalsFromDb() {
        if (globalDao == null) return;
        for (UserGlobalVariableDao.Row r : globalDao.loadAll()) {
            try {
                validateKey(r.name());
                validateValueLength(r.defaultValue());
                validateValueLength(r.currentValue());
            } catch (VariableException e) {
                continue;  // 跳过坏行
            }
            String fullName = USER_GLOBAL_NAMESPACE + "/" + r.name();
            globalOwners.put(r.name(), new GlobalOwnerInfo(r.ownerUuid(), r.ownerName()));
            Variable v = new Variable(USER_GLOBAL_NAMESPACE, r.name(), r.type(),
                    r.defaultValue(), r.currentValue(),
                    r.updatedAt(), 0L, r.boundTo(),
                    Collections.unmodifiableSet(new HashSet<>()));
            store.put(fullName, v);
            byNamespace.computeIfAbsent(USER_GLOBAL_NAMESPACE,
                            n -> ConcurrentHashMap.newKeySet())
                    .add(fullName);
        }
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
        return create(namespace, key, type, defaultValue, source, false);
    }

    /**
     * 0.4.10 P2-31：内部 create，{@code skipNsQuota=true} 时跳过 per-namespace 配额
     * （{@link #MAX_PER_NAMESPACE}）。仅 {@link #createGlobal}（userglobal namespace）用——
     * 它自己用 {@link #maxUserGlobalTotal} 封顶，否则 MAX_PER_NAMESPACE=1000 会硬封 config
     * 的 total（默 10000）使其不可达。全局 {@link #MAX_GLOBAL} 兜底始终生效。
     */
    private Variable create(String namespace, String key, VarType type,
                            @Nullable String defaultValue, @Nullable String source,
                            boolean skipNsQuota) {
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
            if (!skipNsQuota && nsBucket.size() >= MAX_PER_NAMESPACE) {
                throw new VariableException(VariableException.Code.QUOTA_EXCEEDED,
                        "namespace variable quota exceeded for " + namespace
                                + ": " + MAX_PER_NAMESPACE);
            }
            nsBucket.add(k);
            // 0.4.0 bugfix（Bug 2）：反查 byWall 倒排索引。Compositor 渲染早于 store.create
            // 时（典型场景：用户先在 wall 文本里写 ${var:schedule.X} 触发 markWallReferences，
            // 后 ManualScheduleProvider.ensureWallRegistered 才 create），addWallToReferencedSet
            // 之前因 store 不含变量被 noop 跳过。这里反查 byWall 把"已经引用我"的 wall 注入
            // 新 Variable.referencedByWalls — 后续 setValue 触发 notifyReferencingWalls 时能正确
            // 通知到这些 wall（否则 pushValues → setValue 仍把 wall 当未引用，永不重画）。
            //
            // 复杂度 O(W × N_per_wall)：wall 数量通常 ≪ 100，每个 wall 引用变量数 ≪ 50，
            // create 频率本身极低（仅启动期 + 注册时），总开销可忽略。
            Set<String> initialRefs = new HashSet<>();
            for (java.util.Map.Entry<String, Set<String>> wallEntry : byWall.entrySet()) {
                if (wallEntry.getValue().contains(k)) {
                    initialRefs.add(wallEntry.getKey());
                }
            }
            return new Variable(namespace, key, type, defaultValue, null,
                    now, 0L, source, Collections.unmodifiableSet(initialRefs));
        });

        persistIfUser(created, now, now);
        persistIfUserGlobal(created, now, now);
        // 0.4.0 bugfix3（Bug B）：广播 CREATED 给 listener（HikariCanvas 注入的 listener 推
        // state.patch 给前端 mirror）。fireChange 在 compute 之外调，避免持锁回调。
        fireChange(fullName, created, ChangeType.CREATED);
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
        persistIfUserGlobal(updated, createdAtHolder[0], now);
        notifyReferencingWalls(updated);
        // 0.4.0 bugfix3（Bug B）
        fireChange(fullName, updated, ChangeType.UPDATED);
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
        persistIfUserGlobal(updated, now, now);
        notifyReferencingWalls(updated);
        // 0.4.0 bugfix3（Bug B）：Provider 写值的核心路径——ManualScheduleProvider /
        // SystemVariableProvider / PapiVariableBridge / ScoreboardVariableProvider 都靠
        // 此 listener 才能让前端 mirror 同步显示 currentValue。
        fireChange(fullName, updated, ChangeType.VALUE_SET);
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
            // 0.4.10 P3-91(a)：桶空则原子移除空桶——否则 per-wall namespace（user:<wallId> /
            // schedule:<wallId> 等）的桶在 wall 删除后永久驻留（内存泄漏，long-running server
            // 上 byNamespace 单调增长）。用 2 参 remove(key, value) 防 race：仅当当前映射仍是
            // 这个空桶才移除——若有并发 create 已重新填入，2 参 remove 会因值不等而跳过。
            if (nsBucket.isEmpty()) {
                byNamespace.remove(removed.namespace(), nsBucket);
            }
        }
        // 0.4.0 bugfix3（Bug B）：删除前先抓住 referencedByWalls 快照——用于 fireChange 路由。
        // 接下来 byWall 清掉之后 removed.referencedByWalls() 仍是 mutation 前的 Set（record immutable），
        // 而 fireChange(... DELETED, removed) 用 removed.referencedByWalls() 作为 walls 列表，正确。
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
        // 0.4.3：全局用户变量同样清持久化 + 内存 owner 表
        if (USER_GLOBAL_NAMESPACE.equals(removed.namespace())) {
            globalOwners.remove(removed.key());
            if (globalDao != null) {
                globalDao.delete(removed.key());
            }
        }
        notifyReferencingWalls(removed);
        // 0.4.0 bugfix3（Bug B）：DELETED 事件的 variable=null，referencingWalls 取删除前的快照
        // 一并打到 event.referencingWalls 字段（listener 拿来路由）。
        Set<String> walls = Set.copyOf(removed.referencedByWalls());
        VariableChangeEvent event = new VariableChangeEvent(
                fullName, null, ChangeType.DELETED, walls);
        for (VariableChangeListener listener : changeListeners) {
            try {
                listener.onChange(event);
            } catch (Exception e) {
                java.util.logging.Logger.getLogger(VariableStore.class.getName())
                        .log(java.util.logging.Level.WARNING,
                                "VariableChangeListener threw for " + fullName
                                        + " (DELETED): " + e.getMessage(), e);
            }
        }
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
        persistIfUserGlobal(updated, now, now);
        notifyReferencingWalls(updated);
        // 0.4.0 bugfix3（Bug B）
        fireChange(fullName, updated, ChangeType.BOUND);
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

    /**
     * 0.4.0 bugfix（Bug 1）：列出该 wall <b>可能引用</b>的全部变量（不依赖 byWall 倒排索引，
     * 解决 ready payload "鸡生蛋"问题）。
     *
     * <p>ready payload 在 wall 刚 open 时构造，此时 Compositor 尚未对该 wall 执行渲染 →
     * {@link #markWallReferences} 未调 → {@link #listByWall} 返空表。前端 mirror 因此漏掉
     * system / schedule / scoreboard / papi / 插件 namespace 全部变量，
     * VariableInterpolator 把它们当 missing → live preview 出现"变量已删除"红色 banner。
     *
     * <p>本方法不依赖倒排索引，按 namespace 形态判定可见性：
     * <ul>
     *   <li>全局 namespace（namespace 不含 ':'，如 {@code system / papi / scoreboard /
     *       插件 namespace}）→ 全部包含</li>
     *   <li>per-wall namespace（namespace 形如 {@code X:wallId}，如 {@code user:wallId /
     *       system:wallId / schedule:wallId}）→ 只匹配本 wall</li>
     *   <li>其他 wall 的 per-wall namespace → 排除（防泄漏）</li>
     * </ul>
     *
     * <p>复杂度 O(N)，N = 全局变量数。N ≤ MAX_GLOBAL (10000) 实际玩家场景下不会超千。
     *
     * @param wallId 当前 wall 的 wall_id（{@code w-<8hex>}）
     * @return 该 wall 可能引用的变量集合（无序）；空 store 或非法 wallId 返空表
     */
    public List<Variable> listVisibleToWall(String wallId) {
        Objects.requireNonNull(wallId, "wallId");
        String wallSuffix = ":" + wallId;
        List<Variable> result = new ArrayList<>();
        for (Variable v : store.values()) {
            String ns = v.namespace();
            if (!ns.contains(":")) {
                // 全局 namespace（system / papi / scoreboard / 插件 namespace 等）
                result.add(v);
            } else if (ns.endsWith(wallSuffix)) {
                // per-wall namespace 匹配本 wall（user:wallId / system:wallId / schedule:wallId）
                result.add(v);
            }
            // 其他 wall 的 per-wall 变量跳过（防泄漏）
        }
        return result;
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

        boolean changed;
        // 0.4.10 P2-23：per-wallId 条带锁，把「快照→diff→双结构写」整段串行化。
        Object monitor = wallRefMonitors.computeIfAbsent(wallId, w -> new Object());
        synchronized (monitor) {
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
            changed = !removed.isEmpty() || !added.isEmpty();
        }

        // 0.4.0 方案 B 自适应渲染：引用集合有变化时通知 listener 重新评估高频状态。
        // 用 WALL_REFS_UPDATED 类型 + variable=null + fullName=占位符 让广播 listener 跳过 patch 推送
        // （SessionManager.broadcastVariableChangeToWall 拿到 null variable 时不构造 patch）；
        // HikariCanvas 注入的自适应 listener 据此调 ProjectionThrottler.setIntervalForSession。
        // fireWallRefsUpdated 在锁外调（避免持锁回调 listener）。
        if (changed) {
            fireWallRefsUpdated(wallId);
        }
    }

    /**
     * 0.4.0 方案 B 自适应渲染：发布 WALL_REFS_UPDATED 事件。
     *
     * <p>事件 fullName 字段使用占位符 {@code "<bulk_wall_refs>"}；variable 为 null；
     * referencingWalls 仅含触发 wallId。listener 必须自行识别此类型，跳过 state.patch 路径，
     * 仅做"该 wall 高频状态可能改变"的副作用（调 ProjectionThrottler.setIntervalForSession）。</p>
     */
    private void fireWallRefsUpdated(String wallId) {
        if (changeListeners.isEmpty()) return;
        VariableChangeEvent event = new VariableChangeEvent(
                "<bulk_wall_refs>", null, ChangeType.WALL_REFS_UPDATED, Set.of(wallId));
        for (VariableChangeListener listener : changeListeners) {
            try {
                listener.onChange(event);
            } catch (Exception e) {
                java.util.logging.Logger.getLogger(VariableStore.class.getName())
                        .log(java.util.logging.Level.WARNING,
                                "VariableChangeListener threw for WALL_REFS_UPDATED wall="
                                        + wallId + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Wall 删除时调用（B 任务在 walls 表 DELETE 后联动）：从所有变量的倒排索引清掉 wallId，
     * 并删除该 wall 的 {@code user:<wallId>/*} 变量（内存 + 持久化）。
     *
     * <p>0.4.10 P3-91(b)：原实现只清倒排索引，不删 {@code user:<wallId>/*} 内存变量——
     * wall 删除后这些变量在 store / byNamespace 永久驻留（DB FK CASCADE 清了行但内存不清，
     * long-running server 内存泄漏）。改为对每个 user 变量调 {@link #delete}（内含 per-key
     * {@code dao.delete}，与 FK CASCADE 幂等——walls 行已删时删 0 行无害），与
     * ManualScheduleProvider / RailScheduleProvider 的 unregisterWall 删 per-wall 变量路径对齐。
     * {@code schedule:/system:/rail} per-wall 变量由各自 provider 的 unregisterWall hook 清，
     * 不在此重复。</p>
     */
    public void clearWallReferences(String wallId) {
        Objects.requireNonNull(wallId, "wallId");
        // 0.4.10 P2-23：与 markWallReferences 共用 per-wall 锁，避免删除与 mark 交错。
        Object monitor = wallRefMonitors.computeIfAbsent(wallId, w -> new Object());
        synchronized (monitor) {
            Set<String> bucket = byWall.remove(wallId);
            if (bucket != null) {
                for (String fn : bucket) {
                    removeWallFromReferencedSet(fn, wallId);
                }
            }
        }
        wallRefMonitors.remove(wallId);

        // 0.4.10 P3-91(b)：删本 wall 的 user:<wallId>/* 内存变量（先快照 fullName 避免迭代时改集合）。
        String userNs = USER_NAMESPACE_PREFIX + ":" + wallId;
        Set<String> userBucket = byNamespace.get(userNs);
        if (userBucket != null) {
            for (String fn : new ArrayList<>(userBucket)) {
                try {
                    delete(fn);
                } catch (VariableException e) {
                    // 已被并发删 / 不存在 → 忽略；其他 code 不该出现在内存删除路径
                    if (e.code() != VariableException.Code.VARIABLE_NOT_FOUND) {
                        java.util.logging.Logger.getLogger(VariableStore.class.getName())
                                .log(java.util.logging.Level.WARNING,
                                        "clearWallReferences delete failed: " + fn, e);
                    }
                }
            }
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

    /**
     * 0.4.3：仅当 namespace = {@code userglobal} 时落库到 {@code user_global_variables} 表。
     * owner 信息从 {@link #globalOwners} 内存表查；create 主路径在 {@link #createGlobal}
     * 内先 put owner 再调 store.create，保证此时 globalOwners 一定含 key。
     *
     * <p>{@link #globalDao} 未注入（{@link #configureUserGlobal} 未调）时静默 noop —— 兼容
     * 测试期不传 dao 的场景 + create 路径在 dao 未配时已先抛 QUOTA_EXCEEDED，所以这里
     * 不会丢正常路径的数据。</p>
     */
    private void persistIfUserGlobal(Variable v, long createdAt, long updatedAt) {
        if (!USER_GLOBAL_NAMESPACE.equals(v.namespace())) return;
        UserGlobalVariableDao d = globalDao;
        if (d == null) return;
        GlobalOwnerInfo info = globalOwners.get(v.key());
        if (info == null) return;
        d.upsert(v.key(), info.ownerUuid(), info.ownerName(), v.type(),
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
        globalOwners.clear();
    }

    /** 测试 / 调试：当前 wall 引用集合的不可变快照。 */
    public Set<String> referencedFullNamesByWall(String wallId) {
        Set<String> bucket = byWall.get(wallId);
        if (bucket == null) return Set.of();
        return Set.copyOf(bucket);
    }

    // ──────────────────────────────────────────────────────────────────
    //  0.4.0 方案 B 自适应渲染：高频 wall 判断
    // ──────────────────────────────────────────────────────────────────

    /**
     * 高频 key 后缀（按 fullName 形如 {@code schedule:<wallId>/eta_seconds} 匹配）。
     * 任一变量 endsWith 命中即视为该 wall 含秒级变化变量。
     */
    private static final Set<String> HIGH_FREQ_KEY_SUFFIXES = Set.of(
            "/eta_seconds",
            "/eta_mmss",
            "/arrival_status",
            "/next2_eta_seconds",
            "/next2_eta_mmss",
            "/next2_arrival_status"
    );

    /** 全局高频 fullName（namespace + key 完全匹配；目前仅 system/server.tick）。 */
    private static final Set<String> HIGH_FREQ_FULL_NAMES = Set.of(
            "system/server.tick"
    );

    /**
     * 0.4.0 方案 B 自适应渲染：检查 wall 引用的变量是否含秒级变化的高频 key。
     *
     * <p>调用方（{@link moe.hikari.canvas.HikariCanvas} 注入的 ChangeListener）据此把
     * {@link moe.hikari.canvas.render.ProjectionThrottler} 该 session 的间隔从默认 200ms
     * （5 fps）切到 50ms（20 fps），让秒精度倒计时 / 服务端 tick 等场景看起来顺滑。</p>
     *
     * <p>判断依据 {@link #byWall} 倒排索引——本方法纯查内存，O(N) 其中 N 是该 wall 引用变量数
     * （通常 &lt; 50）。线程安全：byWall 是 ConcurrentHashMap，bucket 是 newKeySet 视图，
     * 迭代期允许并发修改。</p>
     *
     * @param wallId 目标 wall id；null / 未引用任何变量 → false
     * @return true 表示该 wall 至少引用了一个高频变量，应启用高 fps 渲染
     */
    public boolean isWallHighFreq(String wallId) {
        if (wallId == null) return false;
        Set<String> referenced = byWall.get(wallId);
        if (referenced == null || referenced.isEmpty()) return false;
        for (String fn : referenced) {
            if (fn == null) continue;
            if (HIGH_FREQ_FULL_NAMES.contains(fn)) return true;
            for (String suffix : HIGH_FREQ_KEY_SUFFIXES) {
                if (fn.endsWith(suffix)) return true;
            }
        }
        return false;
    }
}
