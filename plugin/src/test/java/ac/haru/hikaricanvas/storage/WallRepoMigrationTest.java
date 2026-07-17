package ac.haru.hikaricanvas.storage;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ac.haru.hikaricanvas.state.ProjectState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8-B：{@link WallRepo#migrateProjectJsonV1ToV2} + {@link WallRepo#isV1Form} 的形态校验。
 *
 * <p>不接数据库，只测两个静态方法的纯函数行为：</p>
 * <ul>
 *   <li>v1 形态（顶层 {@code elements}）→ migrate 后含 {@code layers / activeLayerId}</li>
 *   <li>v2 形态（顶层 {@code layers}）→ isV1Form 返 false（不会被 migrate 覆盖）</li>
 *   <li>原 element 全部落到 Default Layer，顺序保持</li>
 *   <li>canvas.gridSize / guides / activeLayerId 字段在迁移后存在</li>
 * </ul>
 */
class WallRepoMigrationTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private static final String V1_JSON = """
            {
              "version": 7,
              "canvas": {"widthMaps": 2, "heightMaps": 1, "background": "#FFFFFF"},
              "elements": [
                {
                  "type": "text",
                  "id": "e-1",
                  "x": 10, "y": 20, "w": 100, "h": 30,
                  "rotation": 0, "locked": false, "visible": true,
                  "text": "hello",
                  "fontId": "ark_pixel",
                  "fontSize": 12,
                  "color": "#000000",
                  "align": "left",
                  "letterSpacing": 0.0,
                  "lineHeight": 1.2,
                  "vertical": false
                },
                {
                  "type": "rect",
                  "id": "e-2",
                  "x": 0, "y": 0, "w": 50, "h": 50,
                  "rotation": 0, "locked": false, "visible": true,
                  "fill": "#FF0000"
                }
              ],
              "history": {"undoDepth": 0, "redoDepth": 0}
            }
            """;

    @Test
    void isV1FormDetectsV1() {
        assertTrue(WallRepo.isV1Form(mapper, V1_JSON));
    }

    @Test
    void isV1FormRejectsV2() throws Exception {
        // 把 V1 先 migrate 一遍拿到 v2 JSON，再喂回 isV1Form
        String v2 = WallRepo.migrateProjectJsonV1ToV2(mapper, V1_JSON);
        assertFalse(WallRepo.isV1Form(mapper, v2));
    }

    @Test
    void isV1FormSafeOnNullAndBlank() {
        assertFalse(WallRepo.isV1Form(mapper, null));
        assertFalse(WallRepo.isV1Form(mapper, ""));
        assertFalse(WallRepo.isV1Form(mapper, "   "));
    }

    @Test
    void isV1FormSafeOnGarbage() {
        assertFalse(WallRepo.isV1Form(mapper, "not-json"));
        assertFalse(WallRepo.isV1Form(mapper, "{\"unrelated\":\"data\"}"));
    }

    @Test
    void migratedJsonHasLayersAndActiveLayerId() throws Exception {
        String v2 = WallRepo.migrateProjectJsonV1ToV2(mapper, V1_JSON);
        JsonNode root = mapper.readTree(v2);

        assertTrue(root.has("layers"), "missing layers");
        assertTrue(root.has("activeLayerId"), "missing activeLayerId");
        assertTrue(root.has("protocolVersion"), "missing protocolVersion");
        // 0.6 v3：migrate 走 ProjectState round-trip 序列化，protocolVersion 跟随
        // ProjectState.PROTOCOL_VERSION（v2→v3 干净切换）；引常量避免下次升版再扫。
        assertEquals(ProjectState.PROTOCOL_VERSION, root.get("protocolVersion").asInt());
        assertEquals(1, root.get("layers").size(), "should wrap into single Default Layer");
    }

    @Test
    void migratedJsonPreservesElements() throws Exception {
        String v2 = WallRepo.migrateProjectJsonV1ToV2(mapper, V1_JSON);
        JsonNode root = mapper.readTree(v2);

        JsonNode elements = root.get("layers").get(0).get("elements");
        assertEquals(2, elements.size());
        assertEquals("e-1", elements.get(0).get("id").asText());
        assertEquals("text", elements.get(0).get("type").asText());
        assertEquals("e-2", elements.get(1).get("id").asText());
        assertEquals("rect", elements.get(1).get("type").asText());
    }

    @Test
    void migratedLayerIsDefaultAndUnlocked() throws Exception {
        String v2 = WallRepo.migrateProjectJsonV1ToV2(mapper, V1_JSON);
        JsonNode layer = mapper.readTree(v2).get("layers").get(0);

        assertEquals("Default Layer", layer.get("name").asText());
        assertTrue(layer.get("visible").asBoolean());
        assertFalse(layer.get("locked").asBoolean());
        assertEquals(1.0, layer.get("opacity").asDouble(), 0.0001);
        assertEquals("normal", layer.get("blendMode").asText());
        assertTrue(layer.get("id").asText().startsWith("l-"));
    }

    @Test
    void activeLayerIdPointsToDefaultLayer() throws Exception {
        String v2 = WallRepo.migrateProjectJsonV1ToV2(mapper, V1_JSON);
        JsonNode root = mapper.readTree(v2);
        String activeId = root.get("activeLayerId").asText();
        String layerId = root.get("layers").get(0).get("id").asText();
        assertEquals(layerId, activeId);
    }

    @Test
    void migratedProjectStateDeserializesCleanly() throws Exception {
        String v2 = WallRepo.migrateProjectJsonV1ToV2(mapper, V1_JSON);
        ProjectState ps = mapper.readValue(v2, ProjectState.class);

        assertEquals(7, ps.version());
        assertEquals(2, ps.canvas().widthMaps());
        assertEquals(1, ps.canvas().heightMaps());
        assertEquals(1, ps.layers().size());
        assertEquals(2, ps.activeLayer().elements().size());
        assertEquals("e-1", ps.activeLayer().elements().get(0).id());
        // 通过兼容视图也能拿到
        assertEquals(2, ps.elements().size());
        assertEquals("e-1", ps.elements().get(0).id());
    }

    @Test
    void roundtripOnV2IsIdempotent() throws Exception {
        // 先升一次
        String v2 = WallRepo.migrateProjectJsonV1ToV2(mapper, V1_JSON);
        // 再升一次（模拟"已是 v2 被错误重跑"场景）
        String v2Again = WallRepo.migrateProjectJsonV1ToV2(mapper, v2);

        // 两次结果反解析得到等价 ProjectState（不要求字符串完全一致，因为 UUID 会变？
        // 注意：v2 JSON 里 layer id 是已固化的字符串，roundtrip 不应再生成新 id）
        ProjectState a = mapper.readValue(v2, ProjectState.class);
        ProjectState b = mapper.readValue(v2Again, ProjectState.class);
        assertEquals(a.layers().get(0).id(), b.layers().get(0).id(),
                "layer id should survive idempotent migration");
        assertEquals(a.activeLayerId(), b.activeLayerId());
        assertEquals(a.elements().size(), b.elements().size());
    }

    @Test
    void emptyV1ElementsArrayBecomesEmptyDefaultLayer() throws Exception {
        String emptyV1 = """
                {
                  "version": 0,
                  "canvas": {"widthMaps": 1, "heightMaps": 1, "background": "#FFFFFF"},
                  "elements": [],
                  "history": {"undoDepth": 0, "redoDepth": 0}
                }
                """;
        String v2 = WallRepo.migrateProjectJsonV1ToV2(mapper, emptyV1);
        JsonNode root = mapper.readTree(v2);
        assertEquals(1, root.get("layers").size());
        assertEquals(0, root.get("layers").get(0).get("elements").size());
        assertNotNull(root.get("activeLayerId"));
    }
}
