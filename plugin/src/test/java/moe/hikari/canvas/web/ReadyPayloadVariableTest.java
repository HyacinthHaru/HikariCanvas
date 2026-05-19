package moe.hikari.canvas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import moe.hikari.canvas.storage.UserVariableDao;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.Variable;
import moe.hikari.canvas.variable.VariableDto;
import moe.hikari.canvas.variable.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.4.0-P2-F：验证 ready payload 携带 wall 变量快照的 wire 行为。
 *
 * <p>本测不 boot Javalin / WebServer 全栈（太重；变量注入是 5 行纯 mapping 代码），
 * 而是测产出 ready payload 的等价 slice：</p>
 *
 * <ol>
 *   <li>{@link VariableStore#listByWall} + {@link VariableDto#from} 映射正确</li>
 *   <li>Jackson 序列化 {@link VariableDto} 不暴露 {@code referencedByWalls}</li>
 *   <li>wall 之间隔离（wallA 的 ready 不含 wallB 的变量）</li>
 *   <li>空 store / 未 mark 引用的 wall 序列化为空数组</li>
 *   <li>{@link VarType} 枚举默认 name() 字符串序列化</li>
 * </ol>
 *
 * <p>WebServer 内部 payload 构造代码（{@code WebServer.java} 中 handleAuth 段）只是把
 * {@code listByWall(wallId).stream().map(VariableDto::from).toList()} put 进 LinkedHashMap，
 * 模拟构造同样的 map → 序列化 → 断言更经济，且 ObjectMapper 配置（NON_NULL inclusion）
 * 与 WebServer 主线一致。</p>
 */
class ReadyPayloadVariableTest {

    private FakeUserVariableDao fakeDao;
    private VariableStore store;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        fakeDao = new FakeUserVariableDao();
        store = new VariableStore(fakeDao, w -> { });
        // 与 WebServer.start() 里 JavalinJackson 同款配置：NON_NULL inclusion
        mapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    // ──────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────

    /** 模拟 WebServer.handleAuth 末尾构造 ready payload variables 数组的逻辑。 */
    private List<VariableDto> buildVariablesSection(String wallId) {
        List<Variable> vars = store.listByWall(wallId);
        List<VariableDto> dtos = new ArrayList<>(vars.size());
        for (Variable v : vars) dtos.add(VariableDto.from(v));
        return dtos;
    }

    /** 模拟 ready payload 的完整 map（只含 P2-F 关注的 wallId + variables 字段）。 */
    private Map<String, Object> buildReadyPayload(String wallId) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("wallId", wallId);
        payload.put("variables", buildVariablesSection(wallId));
        return payload;
    }

    // ──────────────────────────────────────────────────────────
    //  Cases
    // ──────────────────────────────────────────────────────────

    @Test
    void readyContainsAllUserVariablesForWall() throws Exception {
        // 给 wall-A 创建 3 个 user 变量
        store.create("user:w-aaaa1111", "red_score", VarType.NUMBER, "0", "manual");
        store.create("user:w-aaaa1111", "blue_score", VarType.NUMBER, "0", "manual");
        store.create("user:w-aaaa1111", "headline", VarType.STRING, "Welcome", "manual");
        // 关键：必须先 markWallReferences 才能从 byWall 倒排索引拿到
        store.markWallReferences("w-aaaa1111", Set.of(
                "user:w-aaaa1111/red_score",
                "user:w-aaaa1111/blue_score",
                "user:w-aaaa1111/headline"
        ));

        Map<String, Object> payload = buildReadyPayload("w-aaaa1111");
        String json = mapper.writeValueAsString(payload);
        JsonNode root = mapper.readTree(json);
        JsonNode variables = root.get("variables");

        assertNotNull(variables, "payload must contain variables array");
        assertTrue(variables.isArray());
        assertEquals(3, variables.size());

        // 收集所有 key 验证内容（顺序由 ConcurrentHashMap 决定，不强约束）
        List<String> keys = new ArrayList<>();
        for (JsonNode v : variables) keys.add(v.get("key").asText());
        assertTrue(keys.contains("red_score"));
        assertTrue(keys.contains("blue_score"));
        assertTrue(keys.contains("headline"));
    }

    @Test
    void readyJsonDoesNotLeakReferencedByWalls() throws Exception {
        // referencedByWalls 是 VariableStore 内部倒排索引；任何路径下都不能进 wire
        store.create("user:w-bbbb2222", "x", VarType.STRING, "v", null);
        store.markWallReferences("w-bbbb2222", Set.of("user:w-bbbb2222/x"));
        // 再让另一个 wall 也引用，扩大 referencedByWalls 集合到 2 个 wall——
        // 真要泄露的话会很显眼
        store.markWallReferences("w-cccc3333", Set.of("user:w-bbbb2222/x"));

        Map<String, Object> payload = buildReadyPayload("w-bbbb2222");
        String json = mapper.writeValueAsString(payload);

        assertFalse(json.contains("referencedByWalls"),
                "wire JSON must NOT contain referencedByWalls field; got: " + json);
        assertFalse(json.contains("w-cccc3333"),
                "wire JSON must NOT leak peer wall IDs from inverted index; got: " + json);

        // 再校验 DTO 自身的字段集——确切的 wire 字段名
        JsonNode dto = mapper.readTree(json).get("variables").get(0);
        List<String> fieldNames = new ArrayList<>();
        for (Iterator<String> it = dto.fieldNames(); it.hasNext(); ) fieldNames.add(it.next());
        assertTrue(fieldNames.contains("namespace"));
        assertTrue(fieldNames.contains("key"));
        assertTrue(fieldNames.contains("type"));
        assertTrue(fieldNames.contains("updatedAt"));
        assertTrue(fieldNames.contains("ttl"));
        // defaultValue / currentValue / source 走 NON_NULL：只在非 null 时存在
        assertFalse(fieldNames.contains("referencedByWalls"));
    }

    @Test
    void readyDoesNotMixVariablesFromOtherWalls() throws Exception {
        // wall-A 引用 a/x；wall-B 引用 b/y。各自 ready 必须只见自己的。
        store.create("user:w-aaaa1111", "x", VarType.STRING, "vx", null);
        store.create("user:w-bbbb2222", "y", VarType.STRING, "vy", null);
        store.markWallReferences("w-aaaa1111", Set.of("user:w-aaaa1111/x"));
        store.markWallReferences("w-bbbb2222", Set.of("user:w-bbbb2222/y"));

        String jsonA = mapper.writeValueAsString(buildReadyPayload("w-aaaa1111"));
        String jsonB = mapper.writeValueAsString(buildReadyPayload("w-bbbb2222"));

        assertTrue(jsonA.contains("\"x\""));
        assertFalse(jsonA.contains("\"y\""),
                "wall-A ready must not include wall-B variables; got: " + jsonA);
        assertFalse(jsonA.contains("user:w-bbbb2222"));

        assertTrue(jsonB.contains("\"y\""));
        assertFalse(jsonB.contains("\"x\""),
                "wall-B ready must not include wall-A variables; got: " + jsonB);
        assertFalse(jsonB.contains("user:w-aaaa1111"));
    }

    @Test
    void emptyStoreYieldsEmptyVariablesArray() throws Exception {
        Map<String, Object> payload = buildReadyPayload("w-empty00");
        String json = mapper.writeValueAsString(payload);
        JsonNode variables = mapper.readTree(json).get("variables");

        assertNotNull(variables, "variables field must always exist");
        assertTrue(variables.isArray());
        assertEquals(0, variables.size());
        assertTrue(json.contains("\"variables\":[]"));
    }

    @Test
    void varTypeSerializedAsEnumName() throws Exception {
        store.create("user:w-dddd4444", "k_str", VarType.STRING, null, null);
        store.create("user:w-dddd4444", "k_num", VarType.NUMBER, "0", null);
        store.create("user:w-dddd4444", "k_bool", VarType.BOOLEAN, "false", null);
        store.create("user:w-dddd4444", "k_color", VarType.COLOR, "#ffffff", null);
        store.markWallReferences("w-dddd4444", Set.of(
                "user:w-dddd4444/k_str",
                "user:w-dddd4444/k_num",
                "user:w-dddd4444/k_bool",
                "user:w-dddd4444/k_color"
        ));

        String json = mapper.writeValueAsString(buildReadyPayload("w-dddd4444"));

        // Jackson 默认枚举走 name() —— STRING/NUMBER/BOOLEAN/COLOR
        assertTrue(json.contains("\"type\":\"STRING\""), json);
        assertTrue(json.contains("\"type\":\"NUMBER\""), json);
        assertTrue(json.contains("\"type\":\"BOOLEAN\""), json);
        assertTrue(json.contains("\"type\":\"COLOR\""), json);
    }

    @Test
    void dtoPreservesFieldsFromVariable() {
        // 不走 JSON，直接断言 mapping 字段一一对应
        store.create("plugin_ns", "k", VarType.NUMBER, "42", "PluginX");
        store.markWallReferences("w-eeee5555", Set.of("plugin_ns/k"));

        List<VariableDto> dtos = buildVariablesSection("w-eeee5555");
        assertEquals(1, dtos.size());
        VariableDto dto = dtos.get(0);
        assertEquals("plugin_ns", dto.namespace());
        assertEquals("k", dto.key());
        assertEquals(VarType.NUMBER, dto.type());
        assertEquals("42", dto.defaultValue());
        // create 后 currentValue 为 null（未 setValue）
        assertEquals(null, dto.currentValue());
        assertEquals("PluginX", dto.source());
        assertEquals(0L, dto.ttl());
        assertTrue(dto.updatedAt() > 0L);
    }

    @Test
    void unreferencedWallReturnsEmpty() throws Exception {
        // 变量存在但没 mark 到这个 wall → listByWall 返空
        store.create("user:w-ffff6666", "x", VarType.STRING, "v", null);
        // markWallReferences 给了别的 wall
        store.markWallReferences("w-other000", Set.of("user:w-ffff6666/x"));

        String json = mapper.writeValueAsString(buildReadyPayload("w-ffff6666"));
        JsonNode variables = mapper.readTree(json).get("variables");
        assertNotNull(variables);
        assertEquals(0, variables.size(),
                "wall without markWallReferences entry should yield empty variables");
    }

    // ──────────────────────────────────────────────────────────
    //  Helper：与 VariableStoreTest / VariableInterpolatorTest 同构 fake DAO
    // ──────────────────────────────────────────────────────────

    private static final class FakeUserVariableDao extends UserVariableDao {
        FakeUserVariableDao() {
            super(Logger.getLogger("test"), null);
        }

        @Override
        public void upsert(String wallId, String name, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {
            // no-op
        }

        @Override
        public void delete(String wallId, String name) {
            // no-op
        }

        @Override
        public List<Row> loadAll() {
            return new ArrayList<>();
        }
    }
}
