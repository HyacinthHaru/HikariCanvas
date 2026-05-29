package moe.hikari.canvas.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import io.javalin.testtools.Response;
import moe.hikari.canvas.storage.UserVariableDao;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.VariableStore;
import moe.hikari.canvas.variable.provider.DeclaredKey;
import moe.hikari.canvas.variable.provider.VariableProvider;
import moe.hikari.canvas.variable.provider.VariableProviderDaemon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-P3-M：{@link VariableMetadataHandler} 单测。
 *
 * <p>覆盖：</p>
 * <ol>
 *   <li>未鉴权（sessionId 缺 / 空 / 不存在）→ 401</li>
 *   <li>已鉴权 + 无 wallId → 仅返 provider declaredKeys 聚合；不含 user namespace</li>
 *   <li>已鉴权 + wallId → user namespace 排首位 + 含该 wall user 变量</li>
 *   <li>cache：无 wallId 连续两次只调一次 declaredKeys（5s TTL 内）</li>
 *   <li>cache：带 wallId 走实时算（不命中 cache）</li>
 *   <li>Provider declaredKeys() 抛异常 → 不影响其他 provider；本 provider 仍下发 namespace + 空 keys</li>
 *   <li>动态 namespace declaredKeys 空 → dynamic=true + keys=[]</li>
 *   <li>cross-wall user 变量隔离：wallId=A 不返 wallId=B 的 user 变量</li>
 * </ol>
 */
class VariableMetadataHandlerTest {

    private VariableStore store;
    private VariableProviderDaemon daemon;
    private ObjectMapper mapper;

    @BeforeEach
    void setup() {
        store = new VariableStore(new FakeDao(), wid -> {});
        daemon = new VariableProviderDaemon();
        mapper = new ObjectMapper();
    }

    private Javalin buildApp(VariableMetadataHandler handler) {
        return Javalin.create(cfg -> cfg.routes.addEndpoint(
                new io.javalin.router.Endpoint(
                        io.javalin.http.HandlerType.GET,
                        "/api/variable/list-all-namespaces",
                        handler::handle)));
    }

    // ---------- 鉴权 ----------

    @Test
    void unauthorized_missing_sessionId() {
        VariableMetadataHandler handler = newHandler(sid -> true);
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get("/api/variable/list-all-namespaces");
            assertEquals(401, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("UNAUTHORIZED"), body);
        });
    }

    @Test
    void unauthorized_blank_sessionId() {
        VariableMetadataHandler handler = newHandler(sid -> true);
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get("/api/variable/list-all-namespaces?sessionId=");
            assertEquals(401, resp.code());
        });
    }

    @Test
    void unauthorized_unknown_sessionId() {
        VariableMetadataHandler handler = newHandler(sid -> false);
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get("/api/variable/list-all-namespaces?sessionId=bad-sid");
            assertEquals(401, resp.code());
        });
    }

    // ---------- 静态 provider 聚合 ----------

    @Test
    void authenticated_noWallId_aggregatesProviderDeclaredKeys() {
        daemon.register(new FakeStaticProvider("system", "系统变量",
                List.of(
                        new DeclaredKey("server.time", VarType.STRING, "当前时间", 60_000L),
                        new DeclaredKey("server.online", VarType.NUMBER, "在线人数", 30_000L)
                )));

        VariableMetadataHandler handler = newHandler(sid -> "valid-sid".equals(sid));
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get("/api/variable/list-all-namespaces?sessionId=valid-sid");
            assertEquals(200, resp.code());
            JsonNode root = mapper.readTree(resp.body().string());
            JsonNode namespaces = root.get("namespaces");
            assertNotNull(namespaces);
            assertEquals(1, namespaces.size(), "no wallId → no user ns");
            JsonNode ns0 = namespaces.get(0);
            assertEquals("system", ns0.get("namespace").asText());
            assertEquals("系统变量", ns0.get("displayName").asText());
            assertFalse(ns0.get("dynamic").asBoolean());
            JsonNode keys = ns0.get("keys");
            assertEquals(2, keys.size());
            assertEquals("server.time", keys.get(0).get("key").asText());
            assertEquals("STRING", keys.get(0).get("type").asText());
            assertEquals("当前时间", keys.get(0).get("description").asText());
            assertEquals(60_000L, keys.get(0).get("ttlMs").asLong());
        });
    }

    @Test
    void wallId_addsUserNamespaceAtFront_withUserVars() {
        // 注册一个 system provider 做 baseline
        daemon.register(new FakeStaticProvider("system", "系统变量",
                List.of(new DeclaredKey("server.time", VarType.STRING, null, 60_000L))));
        // wall A 上创建 2 个 user 变量
        store.create("user:w-aaa", "red_score", VarType.NUMBER, "0", "manual");
        store.create("user:w-aaa", "blue_score", VarType.NUMBER, "0", "manual");
        // wall B 的 user 变量不应出现在 wallId=w-aaa 的响应里
        store.create("user:w-bbb", "other_score", VarType.NUMBER, "0", "manual");

        // P2-3：session 服务端绑定到 w-aaa；即便客户端 query 传 w-bbb 也只返 w-aaa 的变量
        VariableMetadataHandler handler = newHandler(sid -> "v".equals(sid),
                sid -> "v".equals(sid) ? "w-aaa" : null);
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get(
                    "/api/variable/list-all-namespaces?sessionId=v&wallId=w-bbb");
            assertEquals(200, resp.code());
            JsonNode root = mapper.readTree(resp.body().string());
            JsonNode namespaces = root.get("namespaces");
            assertEquals(2, namespaces.size());
            JsonNode userNs = namespaces.get(0);
            assertEquals("user:w-aaa", userNs.get("namespace").asText(),
                    "user ns 首位 + 用 session 绑定 wall（忽略客户端 query wallId）");
            assertEquals("我的变量", userNs.get("displayName").asText());
            assertFalse(userNs.get("dynamic").asBoolean());
            JsonNode userKeys = userNs.get("keys");
            assertEquals(2, userKeys.size(), "wall A 的 2 个 user 变量");
            Set<String> keyNames = new HashSet<>();
            for (JsonNode k : userKeys) keyNames.add(k.get("key").asText());
            assertTrue(keyNames.contains("red_score"));
            assertTrue(keyNames.contains("blue_score"));
            assertFalse(keyNames.contains("other_score"), "wall B 的变量不应出现");
            // system 仍在
            assertEquals("system", namespaces.get(1).get("namespace").asText());
        });
    }

    @Test
    void wallId_withNoUserVars_returnsEmptyKeys() {
        daemon.register(new FakeStaticProvider("system", "系统", List.of()));
        VariableMetadataHandler handler = newHandler(sid -> "v".equals(sid),
                sid -> "v".equals(sid) ? "w-empty" : null);
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get(
                    "/api/variable/list-all-namespaces?sessionId=v&wallId=w-empty");
            assertEquals(200, resp.code());
            JsonNode root = mapper.readTree(resp.body().string());
            JsonNode userNs = root.get("namespaces").get(0);
            assertEquals("user:w-empty", userNs.get("namespace").asText());
            assertEquals(0, userNs.get("keys").size());
        });
    }

    // ---------- cache ----------

    @Test
    void noWallId_cacheHit_secondCall_reusesJson() {
        AtomicInteger declaredCalls = new AtomicInteger(0);
        daemon.register(new CountingStaticProvider(
                "system", "系统",
                List.of(new DeclaredKey("server.time", VarType.STRING, null, 1000L)),
                declaredCalls));

        VariableMetadataHandler handler = newHandler(sid -> "v".equals(sid));
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response r1 = client.get("/api/variable/list-all-namespaces?sessionId=v");
            assertEquals(200, r1.code());
            Response r2 = client.get("/api/variable/list-all-namespaces?sessionId=v");
            assertEquals(200, r2.code());
            assertEquals(1, declaredCalls.get(),
                    "cache hit：5s TTL 内 declaredKeys() 只调一次");
            // 缓存里有 JSON
            assertNotNull(handler.cachedJsonForTest());
        });
    }

    @Test
    void wallId_doesNotUseCache_alwaysRecomputes() {
        AtomicInteger declaredCalls = new AtomicInteger(0);
        daemon.register(new CountingStaticProvider(
                "system", "系统",
                List.of(new DeclaredKey("server.time", VarType.STRING, null, 1000L)),
                declaredCalls));
        VariableMetadataHandler handler = newHandler(sid -> "v".equals(sid),
                sid -> "v".equals(sid) ? "w-a" : null);

        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response r1 = client.get(
                    "/api/variable/list-all-namespaces?sessionId=v&wallId=w-a");
            assertEquals(200, r1.code());
            Response r2 = client.get(
                    "/api/variable/list-all-namespaces?sessionId=v&wallId=w-a");
            assertEquals(200, r2.code());
            assertEquals(2, declaredCalls.get(),
                    "wallId 路径每次实时算（不命中 cache）");
            assertNull(handler.cachedJsonForTest(), "wallId 路径不写 cache");
        });
    }

    // ---------- declaredKeys() 抛异常隔离 ----------

    @Test
    void providerDeclaredKeysThrows_otherProvidersStillReturn() {
        daemon.register(new FakeStaticProvider("good", "Good",
                List.of(new DeclaredKey("a", VarType.STRING, null, 0L))));
        daemon.register(new VariableProvider() {
            @Override public String namespace() { return "bad"; }
            @Override public String displayName() { return "Bad"; }
            @Override public void initialize() {}
            @Override public boolean refresh() { return false; }
            @Override public Duration refreshInterval() { return Duration.ZERO; }
            @Override public void shutdown() {}
            @Override public List<DeclaredKey> declaredKeys() {
                throw new RuntimeException("boom");
            }
        });

        VariableMetadataHandler handler = newHandler(sid -> "v".equals(sid));
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get(
                    "/api/variable/list-all-namespaces?sessionId=v");
            assertEquals(200, resp.code());
            JsonNode root = mapper.readTree(resp.body().string());
            JsonNode namespaces = root.get("namespaces");
            assertEquals(2, namespaces.size());
            // good 与 bad 都出现；bad 的 keys 空
            Map<String, JsonNode> byNs = new java.util.HashMap<>();
            namespaces.forEach(n -> byNs.put(n.get("namespace").asText(), n));
            assertNotNull(byNs.get("good"));
            assertEquals(1, byNs.get("good").get("keys").size());
            assertNotNull(byNs.get("bad"));
            assertEquals(0, byNs.get("bad").get("keys").size(),
                    "throwing provider 的 keys 应为空但 namespace 仍下发");
        });
    }

    // ---------- 动态 namespace ----------

    @Test
    void dynamicNamespace_isDynamicTrue_keysEmpty() {
        daemon.register(new VariableProvider() {
            @Override public String namespace() { return "scoreboard"; }
            @Override public String displayName() { return "记分板"; }
            @Override public void initialize() {}
            @Override public boolean refresh() { return false; }
            @Override public Duration refreshInterval() { return Duration.ofSeconds(5); }
            @Override public void shutdown() {}
            @Override public boolean isDynamic() { return true; }
            // declaredKeys 走默认返 List.of()
        });

        VariableMetadataHandler handler = newHandler(sid -> "v".equals(sid));
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get(
                    "/api/variable/list-all-namespaces?sessionId=v");
            assertEquals(200, resp.code());
            JsonNode root = mapper.readTree(resp.body().string());
            JsonNode ns = root.get("namespaces").get(0);
            assertEquals("scoreboard", ns.get("namespace").asText());
            assertTrue(ns.get("dynamic").asBoolean());
            assertEquals(0, ns.get("keys").size());
        });
    }

    // ---------- 辅助 ----------

    private VariableMetadataHandler newHandler(java.util.function.Predicate<String> auth) {
        return new VariableMetadataHandler(store, daemon, auth, mapper);
    }

    /** P2-3：注入 sessionId→wallId 解析器（模拟 session 服务端绑定的 wall）。 */
    private VariableMetadataHandler newHandler(java.util.function.Predicate<String> auth,
                                               java.util.function.Function<String, String> wallResolver) {
        return new VariableMetadataHandler(store, daemon, auth, wallResolver, mapper);
    }

    /** 静态 namespace fake provider。 */
    private static class FakeStaticProvider implements VariableProvider {
        private final String ns;
        private final String display;
        private final List<DeclaredKey> keys;

        FakeStaticProvider(String ns, String display, List<DeclaredKey> keys) {
            this.ns = ns;
            this.display = display;
            this.keys = keys;
        }

        @Override public String namespace() { return ns; }
        @Override public String displayName() { return display; }
        @Override public void initialize() {}
        @Override public boolean refresh() { return false; }
        @Override public Duration refreshInterval() { return Duration.ZERO; }
        @Override public void shutdown() {}
        @Override public List<DeclaredKey> declaredKeys() { return keys; }
    }

    /** 子类型：每次 declaredKeys 调用计数 + 1（cache 验证用）。 */
    private static final class CountingStaticProvider extends FakeStaticProvider {
        private final AtomicInteger counter;
        CountingStaticProvider(String ns, String display, List<DeclaredKey> keys,
                               AtomicInteger counter) {
            super(ns, display, keys);
            this.counter = counter;
        }
        @Override
        public List<DeclaredKey> declaredKeys() {
            counter.incrementAndGet();
            return super.declaredKeys();
        }
    }

    private static final class FakeDao extends UserVariableDao {
        FakeDao() {
            super(java.util.logging.Logger.getLogger("test"), null);
        }
        @Override
        public void upsert(String wallId, String name, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {}
        @Override
        public void delete(String wallId, String name) {}
        @Override
        public int deleteByWall(String wallId) { return 0; }
        @Override
        public List<UserVariableDao.Row> loadAll() { return List.of(); }
    }
}
