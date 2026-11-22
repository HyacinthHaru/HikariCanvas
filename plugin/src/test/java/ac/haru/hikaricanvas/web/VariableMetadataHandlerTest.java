package ac.haru.hikaricanvas.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import io.javalin.testtools.Response;
import ac.haru.hikaricanvas.variable.NoopUserVariableDao;
import ac.haru.hikaricanvas.variable.VarType;
import ac.haru.hikaricanvas.variable.VariableStore;
import ac.haru.hikaricanvas.variable.provider.DeclaredKey;
import ac.haru.hikaricanvas.variable.provider.VariableProvider;
import ac.haru.hikaricanvas.variable.provider.VariableProviderDaemon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VariableMetadataHandler} 鉴权、缓存、跨墙隔离与命名空间聚合单测。
 */
class VariableMetadataHandlerTest {

    private VariableStore store;
    private VariableProviderDaemon daemon;
    private ObjectMapper mapper;

    @BeforeEach
    void setup() {
        store = new VariableStore(new NoopUserVariableDao(), wid -> {});
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
        daemon.register(new FakeStaticProvider("system", "System Variables",
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
            assertEquals("System Variables", ns0.get("displayName").asText());
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
        daemon.register(new FakeStaticProvider("system", "System Variables",
                List.of(new DeclaredKey("server.time", VarType.STRING, null, 60_000L))));
        store.create("user:w-aaa", "red_score", VarType.NUMBER, "0", "manual");
        store.create("user:w-aaa", "blue_score", VarType.NUMBER, null, "manual");
        store.create("user:w-bbb", "other_score", VarType.NUMBER, "0", "manual");

        // session 服务端绑定到 w-aaa；即便客户端 query 传 w-bbb 也只返 w-aaa 的变量
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
            assertEquals("User Variables", userNs.get("displayName").asText());
            assertFalse(userNs.get("dynamic").asBoolean());
            JsonNode userKeys = userNs.get("keys");
            assertEquals(2, userKeys.size(), "wall A 的 2 个 user 变量");
            Set<String> keyNames = new HashSet<>();
            for (JsonNode k : userKeys) {
                keyNames.add(k.get("key").asText());
                if ("red_score".equals(k.get("key").asText())) {
                    assertEquals("Default: 0", k.get("description").asText());
                }
            }
            assertTrue(keyNames.contains("red_score"));
            assertTrue(keyNames.contains("blue_score"));
            assertFalse(keyNames.contains("other_score"), "wall B 的变量不应出现");
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
            Map<String, JsonNode> byNs = new HashMap<>();
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

    // ---------- storeNamespacePrefix 下发 ----------

    @Test
    void usesStoreNamespacePrefix_notDaemonKey() {
        daemon.register(new PrefixOverrideProvider("schedule_rail", "schedule", "Rail Schedule",
                List.of(new DeclaredKey("next_cars", VarType.STRING,
                        "[铁路网络] 下一班编组节数", 1000L))));

        VariableMetadataHandler handler = newHandler(sid -> "v".equals(sid));
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get("/api/variable/list-all-namespaces?sessionId=v");
            assertEquals(200, resp.code());
            JsonNode root = mapper.readTree(resp.body().string());
            JsonNode ns0 = root.get("namespaces").get(0);
            assertEquals("schedule", ns0.get("namespace").asText(),
                    "下发 store 前缀 schedule，而非 daemon key schedule_rail");
            assertEquals("Rail Schedule", ns0.get("displayName").asText());
            JsonNode keys = ns0.get("keys");
            assertEquals(1, keys.size());
            assertEquals("next_cars", keys.get(0).get("key").asText());
            assertEquals("[铁路网络] 下一班编组节数", keys.get(0).get("description").asText(),
                    "rail 专属 key 描述带 [铁路网络] 前缀");
        });
    }

    @Test
    void defaultProvider_storeNamespacePrefix_equalsNamespace() {
        daemon.register(new FakeStaticProvider("system", "System Variables",
                List.of(new DeclaredKey("server.time", VarType.STRING, null, 60_000L))));
        VariableMetadataHandler handler = newHandler(sid -> "v".equals(sid));
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get("/api/variable/list-all-namespaces?sessionId=v");
            JsonNode ns0 = mapper.readTree(resp.body().string()).get("namespaces").get(0);
            assertEquals("system", ns0.get("namespace").asText());
        });
    }

    // ---------- 辅助 ----------

    private VariableMetadataHandler newHandler(Predicate<String> auth) {
        return new VariableMetadataHandler(store, daemon, auth, mapper);
    }

    private VariableMetadataHandler newHandler(Predicate<String> auth,
                                               Function<String, String> wallResolver) {
        return new VariableMetadataHandler(store, daemon, auth, wallResolver, mapper);
    }

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

    private static final class PrefixOverrideProvider implements VariableProvider {
        private final String daemonKey;
        private final String storePrefix;
        private final String display;
        private final List<DeclaredKey> keys;

        PrefixOverrideProvider(String daemonKey, String storePrefix, String display,
                               List<DeclaredKey> keys) {
            this.daemonKey = daemonKey;
            this.storePrefix = storePrefix;
            this.display = display;
            this.keys = keys;
        }

        @Override public String namespace() { return daemonKey; }
        @Override public String storeNamespacePrefix() { return storePrefix; }
        @Override public String displayName() { return display; }
        @Override public void initialize() {}
        @Override public boolean refresh() { return false; }
        @Override public Duration refreshInterval() { return Duration.ZERO; }
        @Override public void shutdown() {}
        @Override public List<DeclaredKey> declaredKeys() { return keys; }
    }

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
}
