package ac.haru.hikaricanvas.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /api/font/metrics} 与 {@code GET /fonts/{id}.metrics.json} 都靠
 * {@link FontMetricsTable#serializeToJson} 输出，即使尚未渲染任何文字也必须按需加载内置字体的度量表。
 */
class FontMetricsTableLazyLoadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void resetCache() {
        FontMetricsTable.clearCacheForTest();
    }

    @Test
    void serializeToJson_loadsTableOnDemand_withoutAnyPriorRender() throws Exception {
        // 没有任何 advance() / preload() 调用（= 服务器刚起来、还没渲染过任何文字）
        String json = FontMetricsTable.serializeToJson("inter");
        assertNotNull(json, "端点必须能直接答出内置字体的 advance 表");

        JsonNode root = MAPPER.readTree(json);
        assertEquals("inter", root.path("fontId").asText());
        assertTrue(root.path("baseSize").asInt() > 0);
        assertTrue(root.path("advances").size() > 100, "表里得有实打实的字宽数据");
        // 与 advance() 读的是同一份表
        int fromTable = root.path("advances").path(String.valueOf((int) 'W')).asInt();
        assertEquals(fromTable, FontMetricsTable.advance("inter", 'W', root.path("baseSize").asInt()));
    }

    @Test
    void serializeToJson_unknownFont_returnsNull() {
        assertNull(FontMetricsTable.serializeToJson("no-such-font"));
        assertNull(FontMetricsTable.serializeToJson(null));
    }

    @Test
    void serializeToJson_userFontRegisteredAtRuntime_isServed() {
        // 用户字体没有 classpath JSON，只有 registerRuntime 建的内存表
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        FontMetricsTable.registerRuntime("my-user-font", font);
        String json = FontMetricsTable.serializeToJson("my-user-font");
        assertNotNull(json);
        assertTrue(json.contains("\"fontId\":\"my-user-font\""));
    }
}
