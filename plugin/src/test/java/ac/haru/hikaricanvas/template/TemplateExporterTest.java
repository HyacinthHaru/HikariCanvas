package ac.haru.hikaricanvas.template;

import ac.haru.hikaricanvas.canvasfile.CanvasArchive;
import ac.haru.hikaricanvas.canvasfile.CanvasManifest;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.TextElement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TemplateExporter}：当前 {@link ProjectState} → {@code .canvas} pack。
 *
 * <p>验证 pack 三件（manifest kind=pack + 自声明 id / params.json 数组 / project.json 里 text 换
 * {@code ${param}}）、drop / rename 参数决策、以及含 JSON 特殊字符的文本能通过自校验 roundtrip
 * （替换值 JSON 转义）。</p>
 */
class TemplateExporterTest {

    private final TemplateExporter exporter = new TemplateExporter();
    private static final ObjectMapper M = new ObjectMapper();

    private static ProjectState withTexts(String... texts) {
        ProjectState s = new ProjectState(2, 1);
        int i = 1;
        for (String t : texts) {
            s.addElement(new TextElement("e" + i, 5, 5, 100, 20, 0, false, true,
                    t, "ark_pixel", 12, "#000000", "left", 0f, 1.2f, false,
                    null, null, null, null, null, null));
            i++;
        }
        return s;
    }

    private static Map<String, byte[]> unpack(byte[] pack) throws Exception {
        return CanvasArchive.unpack(pack, new CanvasArchive.Limits(
                64L * 1024 * 1024, 64L * 1024 * 1024, 256L * 1024 * 1024));
    }

    private static String expectedId(UUID owner, String slug) {
        return "user-" + owner.toString().replace("-", "").substring(0, 8) + "-" + slug;
    }

    @Test
    void export_defaultKeepsAllText_producesPackWithParams() throws Exception {
        UUID owner = UUID.randomUUID();
        TemplateExporter.Result r = exporter.export(owner, "Steve", "mysign", "My Sign", "desc",
                TemplateExporter.ParamConfig.empty(), withTexts("Hello", "World"));

        TemplateExporter.ExportResult ok = assertInstanceOf(TemplateExporter.Result.Ok.class, r).result();
        assertTrue(ok.packRelativePath().endsWith("mysign.canvas"));
        assertEquals(expectedId(owner, "mysign"), ok.templateId());

        Map<String, byte[]> entries = unpack(ok.packBytes());
        // manifest：kind=pack + 自声明 id（= templateId，与注册表条目 key 对齐）
        CanvasManifest m = CanvasManifest.parse(entries.get("manifest.json"), 1);
        assertEquals("pack", m.kind());
        assertEquals(expectedId(owner, "mysign"), m.id());
        assertEquals("My Sign", m.name());
        assertEquals(2, m.wallWidth());
        assertEquals(1, m.wallHeight());

        // params.json：text_1 / text_2，default = 原文
        JsonNode params = M.readTree(entries.get("params.json"));
        assertTrue(params.isArray());
        assertEquals(2, params.size());
        assertEquals("text_1", params.get(0).get("id").asText());
        assertEquals("text", params.get(0).get("type").asText());
        assertEquals("Hello", params.get(0).get("default").asText());
        assertEquals("text_2", params.get(1).get("id").asText());
        assertEquals("World", params.get(1).get("default").asText());

        // project.json：两个 text 字段替换成 ${text_1} / ${text_2}
        JsonNode els = M.readTree(entries.get("project.json"))
                .get("layers").get(0).get("elements");
        assertEquals("${text_1}", els.get(0).get("text").asText());
        assertEquals("${text_2}", els.get(1).get("text").asText());
    }

    @Test
    void export_dropAction_leavesTextStatic() throws Exception {
        // text_1 选 drop → 保持静态原文；text_2 仍参数化
        Map<String, TemplateExporter.AutoTextAction> actions = Map.of(
                "text_1", new TemplateExporter.AutoTextAction("drop", null, null, null));
        TemplateExporter.Result r = exporter.export(UUID.randomUUID(), "Steve", "s2", "S2", null,
                new TemplateExporter.ParamConfig(actions), withTexts("Static", "Dynamic"));

        TemplateExporter.ExportResult ok = assertInstanceOf(TemplateExporter.Result.Ok.class, r).result();
        Map<String, byte[]> entries = unpack(ok.packBytes());
        JsonNode params = M.readTree(entries.get("params.json"));
        assertEquals(1, params.size());
        assertEquals("text_2", params.get(0).get("id").asText());
        JsonNode els = M.readTree(entries.get("project.json")).get("layers").get(0).get("elements");
        assertEquals("Static", els.get(0).get("text").asText());     // 未参数化，原文保留
        assertEquals("${text_2}", els.get(1).get("text").asText());
    }

    @Test
    void export_renameParam_usesFinalIdAndLabel() throws Exception {
        Map<String, TemplateExporter.AutoTextAction> actions = Map.of(
                "text_1", new TemplateExporter.AutoTextAction("keep", "station", "站名", null));
        TemplateExporter.Result r = exporter.export(UUID.randomUUID(), "Steve", "s3", "S3", null,
                new TemplateExporter.ParamConfig(actions), withTexts("人民广场"));

        TemplateExporter.ExportResult ok = assertInstanceOf(TemplateExporter.Result.Ok.class, r).result();
        JsonNode p0 = M.readTree(unpack(ok.packBytes()).get("params.json")).get(0);
        assertEquals("station", p0.get("id").asText());
        assertEquals("站名", p0.get("label").asText());
        assertEquals("人民广场", p0.get("default").asText());
    }

    @Test
    void export_textWithSpecialChars_roundtripsOk() throws Exception {
        // 多行 + 引号文本作 default —— 自校验 roundtrip（默认参数替换 + materialize）须通过
        // （替换值 JSON 转义生效，否则 project.json 结构被破坏 → ROUNDTRIP_FAILED）
        TemplateExporter.Result r = exporter.export(UUID.randomUUID(), "Steve", "s4", "S4", null,
                TemplateExporter.ParamConfig.empty(), withTexts("line1\nline2 \"quoted\" \\end"));
        assertInstanceOf(TemplateExporter.Result.Ok.class, r);
    }

    @Test
    void export_noText_omitsParamsJson() throws Exception {
        TemplateExporter.Result r = exporter.export(UUID.randomUUID(), "Steve", "s5", "S5", null,
                TemplateExporter.ParamConfig.empty(), new ProjectState(2, 1));
        TemplateExporter.ExportResult ok = assertInstanceOf(TemplateExporter.Result.Ok.class, r).result();
        Map<String, byte[]> entries = unpack(ok.packBytes());
        assertFalse(entries.containsKey("params.json"), "无参数 pack 应省略 params.json");
        assertTrue(entries.containsKey("project.json"));
    }

    @Test
    void export_invalidSlug_fails() {
        TemplateExporter.Result r = exporter.export(UUID.randomUUID(), "Steve", "Bad Slug!", "X", null,
                TemplateExporter.ParamConfig.empty(), withTexts("hi"));
        TemplateExporter.Result.Failed f = assertInstanceOf(TemplateExporter.Result.Failed.class, r);
        assertEquals("INVALID_SLUG", f.code());
    }
}
