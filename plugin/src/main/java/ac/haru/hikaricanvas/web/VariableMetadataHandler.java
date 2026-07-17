package ac.haru.hikaricanvas.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import ac.haru.hikaricanvas.session.SessionManager;
import ac.haru.hikaricanvas.variable.Variable;
import ac.haru.hikaricanvas.variable.VariableStore;
import ac.haru.hikaricanvas.variable.provider.DeclaredKey;
import ac.haru.hikaricanvas.variable.provider.VariableProvider;
import ac.haru.hikaricanvas.variable.provider.VariableProviderDaemon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code GET /api/variable/list-all-namespaces} 端点 handler。
 *
 * <p>聚合所有已注册 {@link VariableProvider} 的 {@link VariableProvider#declaredKeys() declared keys}
 * + 调用 wall 的 user 变量（仅当 query string 带 {@code wallId} 时），下发给编辑器 VariablePicker
 * 做自动补全骨架。即使后端尚未 push currentValue（动态 namespace 第一次引用、PAPI 桥接异步等），
 * 编辑器也能看到所有 declared key。</p>
 *
 * <h2>路由约定</h2>
 *
 * <pre>{@code GET /api/variable/list-all-namespaces?sessionId=<id>&wallId=<wallId>}</pre>
 *
 * <ul>
 *   <li>{@code sessionId}（必需）：通过 WS auth 拿到，参考 {@code docs/protocol.md §3}。校验失败 401。
 *       同 {@link ac.haru.hikaricanvas.image.UploadHandler#handleDownload(Context)} 的 sessionId 鉴权一致。</li>
 *   <li>{@code wallId}（可选）：当前编辑的 wall id；提供时 response 内追加
 *       {@code user:<wallId>} namespace + 该 wall 的 user 变量列表（自动补全用），
 *       并把它排在首位。</li>
 * </ul>
 *
 * <h2>cache</h2>
 *
 * <p>5s server-side cache（{@link #CACHE_TTL_MS}）；仅当请求 <b>不带 wallId</b> 时命中
 * （wallId 路径每次实时算——user 变量增删频繁，cache 收益有限且容易出错）。</p>
 *
 * <h2>注册</h2>
 *
 * <p>本类不主动注册路由：由 {@link WebServer} 装配时调
 * {@code app.get("/api/variable/list-all-namespaces", handler::handle)} 一行接入。</p>
 *
 * @see docs/dynamic-data.md §3.3 / §6.3
 */
public final class VariableMetadataHandler {

    private static final Logger log = Logger.getLogger(VariableMetadataHandler.class.getName());

    /** Cache TTL（仅 wallId 缺省路径有效）。 */
    static final long CACHE_TTL_MS = 5_000L;

    private final VariableStore variableStore;
    private final VariableProviderDaemon daemon;
    /** 鉴权检查：sessionId 是否对应活 session。生产传 {@code sessionManager::byId 非空判定}；测试注入 fake。 */
    private final Predicate<String> sessionAuthCheck;
    /**
     * sessionId → 该 session 服务端绑定的 wallId（未绑定/无 session 返 null）。
     * 生产由 {@code sessionManager.byId(sid).wallId()} 解析；测试注入 fake。
     * <p>per-wall 隔离的唯一权威来源——客户端传的 {@code wallId} query param 一律忽略，
     * 防 IDOR 跨 wall 枚举他人 user 变量元数据（与 WebServer ready payload 用 session 绑定 wallId 一致）。</p>
     */
    private final Function<String, String> sessionWallResolver;
    private final ObjectMapper jackson;

    /** 单 thread cache：JSON + 写入时刻。{@code volatile} 让读取者跨 thread 立即可见。 */
    private volatile String cachedJson = null;
    private final AtomicLong cachedAt = new AtomicLong(0L);

    /**
     * 生产构造：通过 {@link SessionManager#byId(String)} 非空判定鉴权。
     */
    public VariableMetadataHandler(VariableStore variableStore,
                                   VariableProviderDaemon daemon,
                                   SessionManager sessionManager,
                                   ObjectMapper jackson) {
        this(variableStore, daemon,
                sid -> Objects.requireNonNull(sessionManager, "sessionManager").byId(sid) != null,
                sid -> {
                    ac.haru.hikaricanvas.session.Session s =
                            Objects.requireNonNull(sessionManager, "sessionManager").byId(sid);
                    return s == null ? null : s.wallId();
                },
                jackson);
    }

    /**
     * 测试构造（旧 4-arg）：注入自定义鉴权 predicate。wall 解析默认返回 null（无 user namespace），
     * 仅适用于不依赖 user namespace 的 provider 聚合 / cache 测试。需要 user namespace 的测试改用
     * 5-arg 构造显式注入 {@code sessionWallResolver}。
     */
    VariableMetadataHandler(VariableStore variableStore,
                             VariableProviderDaemon daemon,
                             Predicate<String> sessionAuthCheck,
                             ObjectMapper jackson) {
        this(variableStore, daemon, sessionAuthCheck, sid -> null, jackson);
    }

    /**
     * 测试构造（5-arg）：注入鉴权 predicate + sessionId→wallId 解析器。
     */
    VariableMetadataHandler(VariableStore variableStore,
                             VariableProviderDaemon daemon,
                             Predicate<String> sessionAuthCheck,
                             Function<String, String> sessionWallResolver,
                             ObjectMapper jackson) {
        this.variableStore = Objects.requireNonNull(variableStore, "variableStore");
        this.daemon = Objects.requireNonNull(daemon, "daemon");
        this.sessionAuthCheck = Objects.requireNonNull(sessionAuthCheck, "sessionAuthCheck");
        this.sessionWallResolver = Objects.requireNonNull(sessionWallResolver, "sessionWallResolver");
        this.jackson = Objects.requireNonNull(jackson, "jackson");
    }

    /**
     * 入口：处理 {@code GET /api/variable/list-all-namespaces}。
     *
     * <p>返 JSON 形态：</p>
     * <pre>{@code
     * {
     *   "namespaces": [
     *     {
     *       "namespace": "user:<wallId>",
     *       "displayName": "我的变量",
     *       "dynamic": false,
     *       "keys": [{"key":"red_score","type":"NUMBER","description":"默认值: 0","ttlMs":0}]
     *     },
     *     {"namespace":"system", "displayName":"系统变量", "dynamic":false, "keys":[...]},
     *     ...
     *   ]
     * }
     * }</pre>
     *
     * <p>失败：</p>
     * <ul>
     *   <li>401 {@code {"error":"UNAUTHORIZED"}}（sessionId 缺失 / 不存在 / 已过期）</li>
     *   <li>500 {@code {"error":"INTERNAL"}}（序列化失败等）</li>
     * </ul>
     */
    public void handle(Context ctx) {
        String sessionId = ctx.queryParam("sessionId");
        if (sessionId == null || sessionId.isBlank()
                || !sessionAuthCheck.test(sessionId)) {
            ctx.status(401).contentType("application/json")
                    .result("{\"error\":\"UNAUTHORIZED\"}");
            return;
        }
        // IDOR 修复：忽略客户端传的 wallId query param，改用 session 服务端绑定的 wallId。
        // 这样任意已认证玩家无法把 wallId 改为他人 wall 枚举其 user 变量元数据，
        // 与 WebServer ready payload 路径（用 session.wallId()）的 per-wall 隔离一致。
        String wallId = sessionWallResolver.apply(sessionId);
        // 无绑定 wall 路径可命中 5s cache（避免高频前端轮询大压力）
        boolean cacheable = wallId == null || wallId.isBlank();
        if (cacheable) {
            long now = System.currentTimeMillis();
            String snap = cachedJson;
            if (snap != null && now - cachedAt.get() < CACHE_TTL_MS) {
                ctx.contentType("application/json").result(snap);
                return;
            }
        }
        try {
            String json = buildJson(cacheable ? null : wallId);
            if (cacheable) {
                cachedJson = json;
                cachedAt.set(System.currentTimeMillis());
            }
            ctx.contentType("application/json").result(json);
        } catch (Exception e) {
            log.log(Level.WARNING,
                    "list-all-namespaces serialization failed: " + e.getMessage(), e);
            ctx.status(500).contentType("application/json")
                    .result("{\"error\":\"INTERNAL\"}");
        }
    }

    /** 装测试 / 缓存检查辅助；外部不调。 */
    String buildJson(String wallId) throws Exception {
        List<Map<String, Object>> namespaces = new ArrayList<>();

        // user namespace：仅当 wallId 提供且不空时插入到首位
        if (wallId != null && !wallId.isBlank()) {
            Map<String, Object> userNs = buildUserNamespace(wallId);
            namespaces.add(userNs);
        }

        for (VariableProvider p : daemon.registeredProviders()) {
            Map<String, Object> ns = new LinkedHashMap<>();
            // 下发 store namespace 前缀（前端展示 + interpolator 解析用），而非 daemon
            // 唯一性 key。绝大多数 provider 两者相同；RailScheduleProvider 例外（daemon key
            // = "schedule_rail" 但 store 前缀 = "schedule"），避免 picker 出现 schedule_rail/*
            // 幽灵变量 + 选中后 resolve miss。详见 VariableProvider.storeNamespacePrefix()。
            ns.put("namespace", safe(p.storeNamespacePrefix()));
            ns.put("displayName", safe(p.displayName()));
            ns.put("dynamic", p.isDynamic());
            List<Map<String, Object>> keys = new ArrayList<>();
            try {
                List<DeclaredKey> declared = p.declaredKeys();
                if (declared != null) {
                    for (DeclaredKey k : declared) {
                        if (k == null) continue;
                        Map<String, Object> keyMap = new LinkedHashMap<>();
                        keyMap.put("key", k.key());
                        keyMap.put("type", k.type() == null ? "STRING" : k.type().name());
                        if (k.description() != null) {
                            keyMap.put("description", k.description());
                        }
                        keyMap.put("ttlMs", k.ttlMs());
                        keys.add(keyMap);
                    }
                }
            } catch (Exception e) {
                log.log(Level.WARNING,
                        "provider " + p.namespace() + " declaredKeys() threw: " + e.getMessage(), e);
                // 不影响其他 provider；本 provider 仍下发 namespace 元数据但 keys 为空
            }
            ns.put("keys", keys);
            namespaces.add(ns);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("namespaces", namespaces);
        return jackson.writeValueAsString(root);
    }

    /**
     * 给定 wall 的 user namespace 描述：取 {@link VariableStore#listByWall(String)} 内所有
     * {@code user:} 前缀变量。{@code listByWall} 依赖 Compositor 已 mark 引用——但 user 变量是
     * 玩家手动 create 出来的、未必在文本里引用过；故此处取 store.listAll 然后按 namespace 严格匹配
     * {@code user:<wallId>}，保证所有 user 变量（即使尚未引用）都出现在 Picker 内。
     */
    private Map<String, Object> buildUserNamespace(String wallId) {
        String expectedNamespace = VariableStore.USER_NAMESPACE_PREFIX + ":" + wallId;
        Map<String, Object> userNs = new LinkedHashMap<>();
        userNs.put("namespace", expectedNamespace);
        userNs.put("displayName", "我的变量");
        userNs.put("dynamic", false);
        List<Map<String, Object>> keys = new ArrayList<>();
        try {
            for (Variable v : variableStore.listAll()) {
                if (!expectedNamespace.equals(v.namespace())) continue;
                Map<String, Object> keyMap = new LinkedHashMap<>();
                keyMap.put("key", v.key());
                keyMap.put("type", v.type() == null ? "STRING" : v.type().name());
                if (v.defaultValue() != null) {
                    keyMap.put("description", "默认值: " + v.defaultValue());
                }
                keyMap.put("ttlMs", v.ttl());
                keys.add(keyMap);
            }
        } catch (Exception e) {
            log.log(Level.WARNING,
                    "user namespace enumeration failed for wallId=" + wallId
                            + ": " + e.getMessage(), e);
        }
        userNs.put("keys", keys);
        return userNs;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ---------- 测试 hooks ----------

    /** 测试用：清空 cache（强制下次重算）。 */
    void clearCacheForTest() {
        cachedJson = null;
        cachedAt.set(0L);
    }

    /** 测试用：当前 cache JSON（不触发刷新）。 */
    String cachedJsonForTest() {
        return cachedJson;
    }
}
