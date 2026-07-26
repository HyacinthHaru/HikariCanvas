package ac.haru.hikaricanvas.canvasfile;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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

    /**
     * 缺 type 的 pack 以前能一路注册进 Gallery，直到套用时才在类型分支上 NPE ——
     * HTTP 导入退化成 500，WS {@code template.apply} 更糟（一帧回音都收不到，前端挂等超时）。
     */
    @Test
    void parse_missingType_throwsMalformed() {
        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> PackParamResolver.parse(utf8("[{\"id\":\"title\",\"label\":\"标题\"}]")));
        assertEquals("IMPORT_MALFORMED", ex.code());
        assertTrue(ex.getMessage().contains("title"), "报错要说清是哪个参数: " + ex.getMessage());
    }

    @Test
    void parse_unknownType_throwsMalformed() {
        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> PackParamResolver.parse(utf8("[{\"id\":\"when\",\"type\":\"date\"}]")));
        assertEquals("IMPORT_MALFORMED", ex.code());
    }

    /** 类型体系是冻结契约：8 种取值一个不少、一个不多。 */
    @Test
    void parse_acceptsExactlyTheFrozenTypeSet() throws Exception {
        for (String t : List.of("string", "text", "int", "float", "bool", "color", "enum", "font")) {
            List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(
                    utf8("[{\"id\":\"p\",\"type\":\"" + t + "\"}]"));
            assertEquals(t, decls.get(0).param().type());
        }
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

    // ---------- pattern：不可信正则不能把线程钉死（ReDoS） ----------

    /**
     * 灾难回溯：{@code (.*a){25}} 配上一个末尾不匹配的长串，回溯次数随长度指数增长。
     * 实测在 JDK 25 上这一句用普通 {@code String} 跑起来就<b>回不来了</b>（&gt;8 秒仍未结束）。
     * 正则和被匹配串<b>双双来自不可信 pack</b>——default 值就够了，光是导入就能触发，
     * 于是 Jetty 工作线程被钉死、CPU 打满，重复几次就把编辑器拖垮。
     */
    @Test
    void resolve_catastrophicPattern_failsFastInsteadOfHanging() {
        String evil = "a".repeat(38) + "b";
        List<PackParamResolver.ParamDef> decls = assertDoesNotThrow(() -> PackParamResolver.parse(utf8(
                "[{\"id\":\"s\",\"type\":\"string\",\"label\":\"x\",\"default\":\"" + evil
                        + "\",\"pattern\":\"(.*a){25}\"}]")));

        CanvasImportException ex = assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                () -> assertThrows(CanvasImportException.class,
                        () -> PackParamResolver.resolve(decls, Map.of())),
                "不可信正则必须撞上步数闸快速失败，不能一直跑");
        assertEquals("IMPORT_INVALID_PARAM", ex.code());
        assertTrue(ex.getMessage().contains("too expensive"),
                "报错要说清是正则太贵，而不是值不匹配: " + ex.getMessage());
    }

    /** 正常正则照常工作——步数闸不能把合法校验一起挡了。 */
    @Test
    void resolve_normalPattern_stillValidatesBothWays() throws Exception {
        List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                "[{\"id\":\"s\",\"type\":\"string\",\"label\":\"x\",\"default\":\"abc\","
                        + "\"pattern\":\"^[a-z]+$\"}]"));
        assertEquals("abc", PackParamResolver.resolve(decls, Map.of()).get("s"));

        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> PackParamResolver.resolve(decls, Map.of("s", "ABC")));
        assertTrue(ex.getMessage().contains("doesn't match pattern"), ex.getMessage());
    }

    /** 超长正则连编译都不给编译。 */
    @Test
    void resolve_oversizedPattern_rejected() throws Exception {
        String longPattern = "^(?:" + "a|".repeat(200) + "b)$";
        List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                "[{\"id\":\"s\",\"type\":\"string\",\"label\":\"x\",\"default\":\"a\","
                        + "\"pattern\":\"" + longPattern + "\"}]"));
        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> PackParamResolver.resolve(decls, Map.of()));
        assertTrue(ex.getMessage().contains("pattern too long"), ex.getMessage());
    }

    // ---------- NaN / Infinity / 显式 null ----------

    /**
     * {@code NaN < min} 与 {@code NaN > max} 同时为假，min/max 就静默失效了，NaN 还会一路
     * 替换进 project.json。必须当"不是 float"拒掉。
     */
    @Test
    void resolve_nanAndInfinity_rejectedInsteadOfBypassingRange() throws Exception {
        List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                "[{\"id\":\"f\",\"type\":\"float\",\"label\":\"x\",\"default\":1.0,"
                        + "\"min\":0,\"max\":10}]"));

        for (Object bad : List.of("NaN", "Infinity", "-Infinity", Double.NaN,
                Double.POSITIVE_INFINITY)) {
            CanvasImportException ex = assertThrows(CanvasImportException.class,
                    () -> PackParamResolver.resolve(decls, Map.of("f", bad)),
                    "应拒绝非有限值: " + bad);
            assertTrue(ex.getMessage().contains("is not float"), ex.getMessage());
        }
        // 正常值不受影响
        assertEquals(5.0, PackParamResolver.resolve(decls, Map.of("f", 5.0)).get("f"));
    }

    /**
     * 前端表单没填的字段常直接发 {@code null}。按 containsKey 判断的话默认值和全部校验一起被跳过，
     * 最后替换成空串——用户看到的是"什么都没了"。
     */
    @Test
    void resolve_explicitNull_fallsBackToDefault() throws Exception {
        List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                "[{\"id\":\"n\",\"type\":\"int\",\"label\":\"x\",\"default\":7,\"min\":1,\"max\":9}]"));

        Map<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("n", null);
        assertEquals(7, PackParamResolver.resolve(decls, withNull).get("n"));
    }

    /** 显式 null 同样不能绕过 required。 */
    @Test
    void resolve_explicitNullOnRequiredWithoutDefault_stillErrors() throws Exception {
        List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(utf8(
                "[{\"id\":\"n\",\"type\":\"string\",\"label\":\"x\",\"required\":true}]"));
        Map<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("n", null);
        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> PackParamResolver.resolve(decls, withNull));
        assertTrue(ex.getMessage().contains("required"), ex.getMessage());
    }
}
