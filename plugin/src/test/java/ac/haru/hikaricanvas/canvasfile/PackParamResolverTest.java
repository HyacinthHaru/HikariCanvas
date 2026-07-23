package ac.haru.hikaricanvas.canvasfile;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PackParamResolver} 纯逻辑单测：{@code params.json} 解析 + 类型校验 + {@code ${param}} 替换。
 *
 * <p>校验语义 port 自 {@code TemplateInstantiator.coerceAndValidate}，故覆盖各类型的
 * 缺省回填 / 越界 / 错类型 / 累计报错，以及数值参数「toString 即 JSON 字面量」的关键契约（D3）。</p>
 */
class PackParamResolverTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---------- parse ----------

    @Test
    void parse_validArray_readsIdAndParam() throws Exception {
        List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                "[{\"id\":\"title\",\"type\":\"string\",\"label\":\"标题\",\"default\":\"你好\",\"max_length\":8},"
                        + "{\"id\":\"bg\",\"type\":\"color\",\"label\":\"背景\",\"default\":\"#112233\"}]"));

        assertEquals(2, decls.size());
        assertEquals("title", decls.get(0).id());
        assertEquals("string", decls.get(0).param().type());
        assertEquals(8, decls.get(0).param().maxLength());
        assertEquals("bg", decls.get(1).id());
        assertEquals("color", decls.get(1).param().type());
        assertEquals("#112233", decls.get(1).param().defaultValue());
    }

    @Test
    void parse_missingId_throwsMalformed() {
        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> PackParamResolver.parse(utf8("[{\"type\":\"string\",\"label\":\"x\"}]")));
        assertEquals("IMPORT_MALFORMED", ex.code());
    }

    @Test
    void parse_blankId_throwsMalformed() {
        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> PackParamResolver.parse(utf8("[{\"id\":\"  \",\"type\":\"string\"}]")));
        assertEquals("IMPORT_MALFORMED", ex.code());
    }

    @Test
    void parse_notArray_throwsMalformed() {
        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> PackParamResolver.parse(utf8("{\"id\":\"x\"}")));
        assertEquals("IMPORT_MALFORMED", ex.code());
    }

    @Test
    void parse_garbageJson_throwsMalformed() {
        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> PackParamResolver.parse(utf8("not json")));
        assertEquals("IMPORT_MALFORMED", ex.code());
    }

    // ---------- resolve：缺省 / 覆盖 ----------

    @Test
    void resolve_noUserInput_usesDefaults() throws Exception {
        List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                "[{\"id\":\"title\",\"type\":\"string\",\"default\":\"你好\"}]"));
        Map<String, Object> values = PackParamResolver.resolve(decls, Map.of());
        assertEquals("你好", values.get("title"));
    }

    @Test
    void resolve_userInputOverridesDefault() throws Exception {
        List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                "[{\"id\":\"title\",\"type\":\"string\",\"default\":\"你好\"}]"));
        Map<String, Object> values = PackParamResolver.resolve(decls, Map.of("title", "世界"));
        assertEquals("世界", values.get("title"));
    }

    @Test
    void resolve_missingRequiredNoDefault_throwsInvalidParam() {
        CanvasImportException ex = assertThrows(CanvasImportException.class, () -> {
            List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                    "[{\"id\":\"title\",\"type\":\"string\",\"required\":true}]"));
            PackParamResolver.resolve(decls, Map.of());
        });
        assertEquals("IMPORT_INVALID_PARAM", ex.code());
        assertTrue(ex.getMessage().contains("title"));
    }

    @Test
    void resolve_optionalNoValue_leavesNull() throws Exception {
        // 非必填、无 default、无填值 → 留 null（Interpolator 后续替换为空串）
        List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                "[{\"id\":\"subtitle\",\"type\":\"string\"}]"));
        Map<String, Object> values = PackParamResolver.resolve(decls, Map.of());
        assertTrue(values.containsKey("subtitle"));
        assertNull(values.get("subtitle"));
    }

    // ---------- resolve：类型 coercion（toString 即字面量，D3） ----------

    @Test
    void resolve_intFloatBool_coerceToLiteralToString() throws Exception {
        List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                "[{\"id\":\"off\",\"type\":\"int\",\"default\":5},"
                        + "{\"id\":\"ratio\",\"type\":\"float\",\"default\":45.0},"
                        + "{\"id\":\"flag\",\"type\":\"bool\",\"default\":true}]"));
        Map<String, Object> values = PackParamResolver.resolve(decls, Map.of());

        // 关键：拼进 JSON 文本的是 value.toString()——int→"5" / float→"45.0" / bool→"true"
        assertEquals(Integer.valueOf(5), values.get("off"));
        assertEquals("5", String.valueOf(values.get("off")));
        assertEquals("45.0", String.valueOf(values.get("ratio")));
        assertEquals("true", String.valueOf(values.get("flag")));
    }

    @Test
    void resolve_intFromStringInput_parses() throws Exception {
        List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                "[{\"id\":\"off\",\"type\":\"int\"}]"));
        Map<String, Object> values = PackParamResolver.resolve(decls, Map.of("off", "12"));
        assertEquals("12", String.valueOf(values.get("off")));
    }

    // ---------- resolve：校验失败 ----------

    @Test
    void resolve_stringTooLong_throwsInvalidParam() {
        CanvasImportException ex = assertThrows(CanvasImportException.class, () -> {
            List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                    "[{\"id\":\"title\",\"type\":\"string\",\"max_length\":3}]"));
            PackParamResolver.resolve(decls, Map.of("title", "toolong"));
        });
        assertEquals("IMPORT_INVALID_PARAM", ex.code());
    }

    @Test
    void resolve_invalidColor_throwsInvalidParam() {
        CanvasImportException ex = assertThrows(CanvasImportException.class, () -> {
            List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                    "[{\"id\":\"bg\",\"type\":\"color\"}]"));
            PackParamResolver.resolve(decls, Map.of("bg", "not-a-color"));
        });
        assertEquals("IMPORT_INVALID_PARAM", ex.code());
    }

    @Test
    void resolve_intOutOfRange_throwsInvalidParam() {
        CanvasImportException ex = assertThrows(CanvasImportException.class, () -> {
            List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                    "[{\"id\":\"off\",\"type\":\"int\",\"min\":0,\"max\":10}]"));
            PackParamResolver.resolve(decls, Map.of("off", 99));
        });
        assertEquals("IMPORT_INVALID_PARAM", ex.code());
    }

    @Test
    void resolve_enumNotInOptions_throwsInvalidParam() {
        CanvasImportException ex = assertThrows(CanvasImportException.class, () -> {
            List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                    "[{\"id\":\"mode\",\"type\":\"enum\",\"options\":[{\"label\":\"A\",\"value\":\"a\"}]}]"));
            PackParamResolver.resolve(decls, Map.of("mode", "z"));
        });
        assertEquals("IMPORT_INVALID_PARAM", ex.code());
    }

    @Test
    void resolve_multipleErrors_allJoined() {
        CanvasImportException ex = assertThrows(CanvasImportException.class, () -> {
            List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                    "[{\"id\":\"a\",\"type\":\"string\",\"required\":true},"
                            + "{\"id\":\"b\",\"type\":\"color\"}]"));
            PackParamResolver.resolve(decls, Map.of("b", "xyz"));
        });
        assertEquals("IMPORT_INVALID_PARAM", ex.code());
        // 累计报错：两个问题都在消息里
        assertTrue(ex.getMessage().contains("a"));
        assertTrue(ex.getMessage().contains("b"));
    }

    // ---------- substitute ----------

    @Test
    void substitute_replacesPlaceholders() throws Exception {
        String out = PackParamResolver.substitute(
                "{\"text\":\"${title}\",\"fill\":\"${bg}\"}",
                Map.of("title", "你好", "bg", "#112233"));
        assertEquals("{\"text\":\"你好\",\"fill\":\"#112233\"}", out);
    }

    @Test
    void substitute_numericPlaceholderBecomesStringLiteral() throws Exception {
        // 数值字段以字符串占位符写，替换后是带引号的字符串字面量 "10"，交给 materialize 的 Jackson 再 coerce
        String out = PackParamResolver.substitute("{\"x\":\"${off}\"}", Map.of("off", 10));
        assertEquals("{\"x\":\"10\"}", out);
    }

    @Test
    void substitute_undeclaredParam_throwsMalformed() {
        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> PackParamResolver.substitute("{\"text\":\"${nope}\"}", Map.of()));
        assertEquals("IMPORT_MALFORMED", ex.code());
        assertTrue(ex.getMessage().contains("nope"));
    }

    @Test
    void substitute_runtimeVarWithColon_untouched() throws Exception {
        // ${var:X} 有冒号——参数正则不吃，原样穿透到运行期解析（D4 共存）
        String out = PackParamResolver.substitute("{\"text\":\"${var:online}\"}", Map.of());
        assertEquals("{\"text\":\"${var:online}\"}", out);
    }

    // ---------- substitute：JSON 特殊字符转义（P2 存为模板会把任意用户文本捕获成 default） ----------

    @Test
    void substitute_valueWithQuotes_staysValidJson() throws Exception {
        // 值含引号——须 JSON 转义，否则破坏结构。解析回来 text 应等于原值。
        String out = PackParamResolver.substitute("{\"text\":\"${t}\"}", Map.of("t", "say \"hi\""));
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out);
        assertEquals("say \"hi\"", node.get("text").asText());
    }

    @Test
    void substitute_valueWithBackslashAndNewline_staysValidJson() throws Exception {
        String raw = "line1\nline2\\end";   // 多行招牌文本 + 反斜杠
        String out = PackParamResolver.substitute("{\"text\":\"${t}\"}", Map.of("t", raw));
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out);
        assertEquals(raw, node.get("text").asText());
    }

    @Test
    void substitute_embeddedPlaceholderWithSpecialChars_staysValidJson() throws Exception {
        // 占位符嵌在更大字符串里 + 值含引号
        String out = PackParamResolver.substitute("{\"text\":\"标题：${t}！\"}", Map.of("t", "a\"b"));
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out);
        assertEquals("标题：a\"b！", node.get("text").asText());
    }
}
