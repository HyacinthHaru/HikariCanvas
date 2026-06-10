package moe.hikari.canvas.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import io.javalin.testtools.Response;
import moe.hikari.canvas.HikariCanvasConfig.CommandTemplate;
import moe.hikari.canvas.HikariCanvasConfig.ParamSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0-P5-E（K-UI-10）：{@link CommandTemplateHandler} 单测。
 *
 * <p>覆盖：</p>
 * <ol>
 *   <li>未鉴权（sessionId 缺 / 空 / 不存在）→ 401</li>
 *   <li>有模板 → 返列表（含 id + params 的 name/type/maxLength），且 <b>response body 不含
 *       command 原文</b>（安全核心 — 见 {@code docs/scripting.md §5.2}）</li>
 *   <li>空配置 → {@code {"templates":[]}}</li>
 *   <li>params 形态（name/type/maxLength）正确</li>
 *   <li>{@code session} query 别名（K-UI-10 规格写法）也被接受</li>
 *   <li>template id + param name 按字母序稳定输出（Map.copyOf 不保证声明序）</li>
 *   <li>online-player 类型原样下发</li>
 * </ol>
 */
class CommandTemplateHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** announce 模板的命令原文 —— 多个断言反向核验它绝不出现在任何 response body。 */
    private static final String ANNOUNCE_COMMAND = "broadcast §e{msg}";
    /** kick 模板命令原文（含可能敏感的 op 语义），同样不得外泄。 */
    private static final String KICK_COMMAND = "minecraft:kick {target} {reason}";

    private Javalin buildApp(CommandTemplateHandler handler) {
        return Javalin.create(cfg -> cfg.routes.addEndpoint(
                new io.javalin.router.Endpoint(
                        io.javalin.http.HandlerType.GET,
                        "/api/script/command-templates",
                        handler::handle)));
    }

    private CommandTemplateHandler newHandler(Predicate<String> auth,
                                              Supplier<Map<String, CommandTemplate>> templates) {
        return new CommandTemplateHandler(auth, templates, mapper);
    }

    /** announce(msg:text/64) + kick(target:online-player/16, reason:text/100) 两模板。 */
    private static Map<String, CommandTemplate> twoTemplates() {
        Map<String, CommandTemplate> m = new LinkedHashMap<>();
        Map<String, ParamSpec> announceParams = new LinkedHashMap<>();
        announceParams.put("msg", new ParamSpec(64, ParamSpec.TYPE_TEXT));
        m.put("announce", new CommandTemplate(ANNOUNCE_COMMAND, announceParams));

        Map<String, ParamSpec> kickParams = new LinkedHashMap<>();
        kickParams.put("target", new ParamSpec(16, ParamSpec.TYPE_ONLINE_PLAYER));
        kickParams.put("reason", new ParamSpec(100, ParamSpec.TYPE_TEXT));
        m.put("kick", new CommandTemplate(KICK_COMMAND, kickParams));
        return m;
    }

    // ---------- 鉴权 ----------

    @Test
    void unauthorized_missing_session() {
        CommandTemplateHandler handler = newHandler(sid -> true, CommandTemplateHandlerTest::twoTemplates);
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get("/api/script/command-templates");
            assertEquals(401, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("UNAUTHORIZED"), body);
            // 即便 401 也绝不泄 command 原文
            assertFalse(body.contains(ANNOUNCE_COMMAND), "401 body must not leak command");
            assertFalse(body.contains(KICK_COMMAND), "401 body must not leak command");
        });
    }

    @Test
    void unauthorized_blank_session() {
        CommandTemplateHandler handler = newHandler(sid -> true, CommandTemplateHandlerTest::twoTemplates);
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get("/api/script/command-templates?sessionId=");
            assertEquals(401, resp.code());
        });
    }

    @Test
    void unauthorized_unknown_session() {
        // auth predicate 一律拒
        CommandTemplateHandler handler = newHandler(sid -> false, CommandTemplateHandlerTest::twoTemplates);
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get("/api/script/command-templates?sessionId=bad-sid");
            assertEquals(401, resp.code());
        });
    }

    // ---------- 有模板：列表 + 不泄 command ----------

    @Test
    void authenticated_returnsTemplates_withoutCommandText() {
        CommandTemplateHandler handler =
                newHandler(sid -> "valid".equals(sid), CommandTemplateHandlerTest::twoTemplates);
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get("/api/script/command-templates?sessionId=valid");
            assertEquals(200, resp.code());
            String body = resp.body().string();

            // 安全核心：整个 response body 不含任一 command 原文（连片段也不行）
            assertFalse(body.contains(ANNOUNCE_COMMAND),
                    "response leaked announce command text: " + body);
            assertFalse(body.contains(KICK_COMMAND),
                    "response leaked kick command text: " + body);
            assertFalse(body.contains("broadcast"),
                    "response leaked command fragment 'broadcast': " + body);
            assertFalse(body.contains("\"command\""),
                    "response must not contain a 'command' field: " + body);

            JsonNode root = mapper.readTree(body);
            JsonNode templates = root.get("templates");
            assertNotNull(templates);
            assertEquals(2, templates.size());
            // id 存在
            JsonNode first = templates.get(0);
            assertNotNull(first.get("id"));
            assertNotNull(first.get("params"));
        });
    }

    @Test
    void params_shape_nameTypeMaxLength() {
        CommandTemplateHandler handler =
                newHandler(sid -> "v".equals(sid), CommandTemplateHandlerTest::twoTemplates);
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get("/api/script/command-templates?sessionId=v");
            assertEquals(200, resp.code());
            JsonNode root = mapper.readTree(resp.body().string());
            JsonNode templates = root.get("templates");

            // 字母序：announce 在 kick 前
            assertEquals("announce", templates.get(0).get("id").asText());
            assertEquals("kick", templates.get(1).get("id").asText());

            // announce.params = [{name:msg, type:text, maxLength:64}]
            JsonNode announceParams = templates.get(0).get("params");
            assertEquals(1, announceParams.size());
            JsonNode msg = announceParams.get(0);
            assertEquals("msg", msg.get("name").asText());
            assertEquals("text", msg.get("type").asText());
            assertEquals(64, msg.get("maxLength").asInt());

            // kick.params 按 param name 字母序：reason 在 target 前
            JsonNode kickParams = templates.get(1).get("params");
            assertEquals(2, kickParams.size());
            assertEquals("reason", kickParams.get(0).get("name").asText());
            assertEquals("text", kickParams.get(0).get("type").asText());
            assertEquals(100, kickParams.get(0).get("maxLength").asInt());
            // target 是 online-player 类型，原样下发
            assertEquals("target", kickParams.get(1).get("name").asText());
            assertEquals("online-player", kickParams.get(1).get("type").asText());
            assertEquals(16, kickParams.get(1).get("maxLength").asInt());
        });
    }

    // ---------- session 别名 ----------

    @Test
    void sessionAliasParam_accepted() {
        CommandTemplateHandler handler =
                newHandler(sid -> "v".equals(sid), CommandTemplateHandlerTest::twoTemplates);
        JavalinTest.test(buildApp(handler), (server, client) -> {
            // K-UI-10 规格写的是 ?session=<sid>，应被接受
            Response resp = client.get("/api/script/command-templates?session=v");
            assertEquals(200, resp.code());
            JsonNode root = mapper.readTree(resp.body().string());
            assertEquals(2, root.get("templates").size());
        });
    }

    // ---------- 空配置 ----------

    @Test
    void emptyConfig_returnsEmptyArray() {
        CommandTemplateHandler handler = newHandler(sid -> "v".equals(sid), Map::of);
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get("/api/script/command-templates?sessionId=v");
            assertEquals(200, resp.code());
            JsonNode root = mapper.readTree(resp.body().string());
            JsonNode templates = root.get("templates");
            assertNotNull(templates);
            assertTrue(templates.isArray());
            assertEquals(0, templates.size());
        });
    }

    @Test
    void nullSupplier_treatedAsEmpty() {
        // 供给返 null（防御性）→ 空数组，不 500
        CommandTemplateHandler handler = newHandler(sid -> "v".equals(sid), () -> null);
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get("/api/script/command-templates?sessionId=v");
            assertEquals(200, resp.code());
            JsonNode root = mapper.readTree(resp.body().string());
            assertEquals(0, root.get("templates").size());
        });
    }

    // ---------- 无参数模板 ----------

    @Test
    void templateWithNoParams_emptyParamsArray() {
        Map<String, CommandTemplate> m = new LinkedHashMap<>();
        m.put("day", new CommandTemplate("time set day", Map.of()));
        CommandTemplateHandler handler = newHandler(sid -> "v".equals(sid), () -> m);
        JavalinTest.test(buildApp(handler), (server, client) -> {
            Response resp = client.get("/api/script/command-templates?sessionId=v");
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertFalse(body.contains("time set day"), "command text leaked: " + body);
            JsonNode root = mapper.readTree(body);
            JsonNode tpl = root.get("templates").get(0);
            assertEquals("day", tpl.get("id").asText());
            assertTrue(tpl.get("params").isArray());
            assertEquals(0, tpl.get("params").size());
        });
    }

    // ---------- buildJson 直测（不经 HTTP）----------

    @Test
    void buildJson_directlyExcludesCommand() throws Exception {
        CommandTemplateHandler handler =
                newHandler(sid -> true, CommandTemplateHandlerTest::twoTemplates);
        String json = handler.buildJson();
        assertFalse(json.contains(ANNOUNCE_COMMAND), json);
        assertFalse(json.contains(KICK_COMMAND), json);
        assertFalse(json.contains("\"command\""), json);
        JsonNode root = mapper.readTree(json);
        assertEquals(2, root.get("templates").size());
    }
}
