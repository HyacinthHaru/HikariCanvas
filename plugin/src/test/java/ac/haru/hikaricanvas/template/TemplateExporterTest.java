package ac.haru.hikaricanvas.template;

import ac.haru.hikaricanvas.canvasfile.CanvasArchive;
import ac.haru.hikaricanvas.canvasfile.CanvasManifest;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.TextElement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /** 单个带明确样式（fontId / fontSize / color）的 TextElement 工程，用于字段标记测试。 */
    private static ProjectState withStyledText(String text, String fontId, int fontSize, String color) {
        ProjectState s = new ProjectState(2, 1);
        s.addElement(new TextElement("e1", 5, 5, 100, 20, 0, false, true,
                text, fontId, fontSize, color, "left", 0f, 1.2f, false,
                null, null, null, null, null, null));
        return s;
    }

    /** 在 params.json 数组里按 id 找参数（顺序无关，避免 index 脆性）。 */
    private static JsonNode findParam(JsonNode arr, String id) {
        for (JsonNode n : arr) {
            if (id.equals(n.path("id").asText())) return n;
        }
        return null;
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

    // ---------------- MVP-1：text 元素 color / fontSize / fontId 字段参数化 ----------------

    @Test
    void export_fieldMarks_parameterizeStyleFields() throws Exception {
        ProjectState state = withStyledText("Platform 1", "ark_pixel", 20, "#123456");
        // textActions 空 → text_1 内容默认 keep；再标记三个样式字段
        TemplateExporter.ParamConfig cfg = new TemplateExporter.ParamConfig(
                Map.of(),
                List.of(
                        new TemplateExporter.FieldMark("text_1", "color"),
                        new TemplateExporter.FieldMark("text_1", "fontSize"),
                        new TemplateExporter.FieldMark("text_1", "fontId")));
        TemplateExporter.Result r = exporter.export(UUID.randomUUID(), "Steve", "styled", "Styled", null,
                cfg, state);

        // ③ roundtrip 不失败（Result.Ok 即证明自校验链跑通）
        TemplateExporter.ExportResult ok = assertInstanceOf(TemplateExporter.Result.Ok.class, r).result();
        Map<String, byte[]> entries = unpack(ok.packBytes());

        // ① params.json 含 3 个新 param，type 分别 color / int / font，default = 元素原值
        JsonNode params = M.readTree(entries.get("params.json"));
        JsonNode colorP = findParam(params, "text_1_color");
        assertNotNull(colorP, "缺 text_1_color 参数");
        assertEquals("color", colorP.get("type").asText());
        assertEquals("#123456", colorP.get("default").asText());
        JsonNode sizeP = findParam(params, "text_1_fontsize");
        assertNotNull(sizeP, "缺 text_1_fontsize 参数");
        assertEquals("int", sizeP.get("type").asText());
        assertEquals(20, sizeP.get("default").asInt());
        JsonNode fontP = findParam(params, "text_1_font");
        assertNotNull(fontP, "缺 text_1_font 参数");
        assertEquals("font", fontP.get("type").asText());
        assertEquals("ark_pixel", fontP.get("default").asText());

        // ② project.json 里该元素的 color / fontSize / fontId 值都变成 ${...}
        JsonNode el = M.readTree(entries.get("project.json"))
                .get("layers").get(0).get("elements").get(0);
        assertEquals("${text_1_color}", el.get("color").asText());
        assertEquals("${text_1_fontsize}", el.get("fontSize").asText());
        assertEquals("${text_1_font}", el.get("fontId").asText());
        // text 内容也默认参数化
        assertEquals("${text_1}", el.get("text").asText());
    }

    @Test
    void export_fieldMark_derivesFromRenamedTextFinalId() throws Exception {
        ProjectState state = withStyledText("人民广场", "ark_pixel", 16, "#ABCDEF");
        // text_1 内容 keep + 改名 title → 颜色字段派生 id 应以 title 为前缀
        Map<String, TemplateExporter.AutoTextAction> actions = Map.of(
                "text_1", new TemplateExporter.AutoTextAction("keep", "title", "站名", null));
        TemplateExporter.ParamConfig cfg = new TemplateExporter.ParamConfig(
                actions, List.of(new TemplateExporter.FieldMark("text_1", "color")));
        TemplateExporter.Result r = exporter.export(UUID.randomUUID(), "Steve", "renamed", "R", null,
                cfg, state);

        TemplateExporter.ExportResult ok = assertInstanceOf(TemplateExporter.Result.Ok.class, r).result();
        JsonNode params = M.readTree(unpack(ok.packBytes()).get("params.json"));
        assertNotNull(findParam(params, "title"), "内容参数应用改名 title");
        JsonNode colorP = findParam(params, "title_color");
        assertNotNull(colorP, "颜色参数应派生自 finalId 前缀 → title_color");
        assertEquals("color", colorP.get("type").asText());
        assertEquals("#ABCDEF", colorP.get("default").asText());
    }

    @Test
    void export_duplicateFieldMark_failsDuplicateParamId() {
        ProjectState state = withStyledText("X", "ark_pixel", 12, "#000000");
        // 同一元素同一字段标两次 → 派生 id 撞车
        TemplateExporter.ParamConfig cfg = new TemplateExporter.ParamConfig(
                Map.of(),
                List.of(
                        new TemplateExporter.FieldMark("text_1", "color"),
                        new TemplateExporter.FieldMark("text_1", "color")));
        TemplateExporter.Result r = exporter.export(UUID.randomUUID(), "Steve", "dup", "Dup", null,
                cfg, state);
        TemplateExporter.Result.Failed f = assertInstanceOf(TemplateExporter.Result.Failed.class, r);
        assertEquals("DUPLICATE_PARAM_ID", f.code());
    }

    // ---------- pack 要自带图片与脚本（docs/template-pack.md §3） ----------

    /**
     * 只写 manifest / params / project 三件的话：模板引用的原图一旦被 LRU 驱逐，
     * 套用出来就是空白；墙上的积木脚本更是彻底丢掉。
     */
    @Test
    void export_bundlesAssetsAndScripts() throws Exception {
        ProjectState state = withTexts("Hi");
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        byte[] scripts = "[{\"id\":\"r1\",\"wallId\":\"w-1\",\"enabled\":true,\"name\":\"n\"}]"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        TemplateExporter.Result r = exporter.export(UUID.randomUUID(), "Steve", "bundle", "Bundle",
                null, TemplateExporter.ParamConfig.empty(), state,
                Map.of("00000000000000ab", png), scripts);

        var entries = unpack(assertInstanceOf(TemplateExporter.Result.Ok.class, r).result().packBytes());
        assertTrue(entries.containsKey("assets/00000000000000ab.png"), "图片应打进 assets/");
        assertArrayEquals(png, entries.get("assets/00000000000000ab.png"));
        assertTrue(entries.containsKey("scripts.json"), "脚本应打进 scripts.json");
        assertArrayEquals(scripts, entries.get("scripts.json"));
    }

    /** 没有图片 / 脚本时不写空条目——保持与既有 pack 逐字节一致。 */
    @Test
    void export_withoutAssetsOrScripts_keepsThreeEntries() throws Exception {
        TemplateExporter.Result r = exporter.export(UUID.randomUUID(), "Steve", "plain", "Plain",
                null, TemplateExporter.ParamConfig.empty(), withTexts("Hi"), Map.of(), null);
        var entries = unpack(assertInstanceOf(TemplateExporter.Result.Ok.class, r).result().packBytes());
        assertFalse(entries.containsKey("scripts.json"));
        assertTrue(entries.keySet().stream().noneMatch(k -> k.startsWith("assets/")));
    }

    /** 文件名必须是内容 hash 形态，否则导入侧的条目名校验会把整包拒掉。 */
    @Test
    void export_rejectsAssetKeysThatAreNotContentHashes() throws Exception {
        java.util.Map<String, byte[]> bad = new java.util.LinkedHashMap<>();
        bad.put("../evil", new byte[]{1});
        bad.put("NOTAHASH", new byte[]{1});
        bad.put("00000000000000ab", new byte[]{1});

        TemplateExporter.Result r = exporter.export(UUID.randomUUID(), "Steve", "guard", "Guard",
                null, TemplateExporter.ParamConfig.empty(), withTexts("Hi"), bad, null);
        var entries = unpack(assertInstanceOf(TemplateExporter.Result.Ok.class, r).result().packBytes());
        assertEquals(1, entries.keySet().stream().filter(k -> k.startsWith("assets/")).count());
        assertTrue(entries.containsKey("assets/00000000000000ab.png"));
    }

    @Test
    void export_derivedIdTooLong_failsInvalidParamId() {
        ProjectState state = withStyledText("X", "ark_pixel", 12, "#000000");
        // 内容改名 28 字符（本身合法 ≤32），+ "_color"(6) = 34 > 32 → 派生 id 非法
        String longName = "a".repeat(28);
        Map<String, TemplateExporter.AutoTextAction> actions = Map.of(
                "text_1", new TemplateExporter.AutoTextAction("keep", longName, null, null));
        TemplateExporter.ParamConfig cfg = new TemplateExporter.ParamConfig(
                actions, List.of(new TemplateExporter.FieldMark("text_1", "color")));
        TemplateExporter.Result r = exporter.export(UUID.randomUUID(), "Steve", "toolong", "L", null,
                cfg, state);
        TemplateExporter.Result.Failed f = assertInstanceOf(TemplateExporter.Result.Failed.class, r);
        assertEquals("INVALID_PARAM_ID", f.code());
    }
}
