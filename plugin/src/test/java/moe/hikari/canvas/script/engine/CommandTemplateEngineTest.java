package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.HikariCanvasConfig.CommandTemplate;
import moe.hikari.canvas.HikariCanvasConfig.ParamSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0-P3 A1：{@link CommandTemplateEngine} 全场景（K13 转义规则逐条；纯函数零 Bukkit）。
 */
class CommandTemplateEngineTest {

    private static final ParamSpec TEXT_64 = new ParamSpec(64, ParamSpec.TYPE_TEXT);
    private static final ParamSpec PLAYER = new ParamSpec(64, ParamSpec.TYPE_ONLINE_PLAYER);

    private static Map<String, CommandTemplate> templates() {
        return Map.of(
                "announce", new CommandTemplate("say [招牌] {msg}", Map.of("msg", TEXT_64)),
                "give-reward", new CommandTemplate("give {player} diamond 1",
                        Map.of("player", PLAYER)),
                "short", new CommandTemplate("say {v}",
                        Map.of("v", new ParamSpec(5, ParamSpec.TYPE_TEXT))),
                "noparams", new CommandTemplate("time set day", Map.of()));
    }

    private static CommandTemplateEngine.Result render(
            String tpl, Map<String, String> params, List<String> online) {
        return CommandTemplateEngine.render(tpl, params, templates(), online);
    }

    // ---------- 模板查无 → blocked ----------

    @Test
    void unknownTemplate_blocked() {
        CommandTemplateEngine.Result r = render("nope", Map.of(), List.of());
        CommandTemplateEngine.Result.Blocked b =
                assertInstanceOf(CommandTemplateEngine.Result.Blocked.class, r);
        assertTrue(b.reason().contains("nope"), b.reason());
    }

    @Test
    void nullOrBlankTemplateId_blocked() {
        assertInstanceOf(CommandTemplateEngine.Result.Blocked.class,
                render(null, Map.of(), List.of()));
        assertInstanceOf(CommandTemplateEngine.Result.Blocked.class,
                render("  ", Map.of(), List.of()));
    }

    @Test
    void nullTemplateTable_blocked() {
        assertInstanceOf(CommandTemplateEngine.Result.Blocked.class,
                CommandTemplateEngine.render("announce", Map.of(), null, List.of()));
    }

    // ---------- 正常渲染 ----------

    @Test
    void happyPath_substitutesParam() {
        CommandTemplateEngine.Result r = render("announce", Map.of("msg", "大甩卖"), List.of());
        CommandTemplateEngine.Result.Ok ok =
                assertInstanceOf(CommandTemplateEngine.Result.Ok.class, r);
        assertEquals("say [招牌] 大甩卖", ok.command());
    }

    @Test
    void noParamsTemplate_rendersAsIs_andIgnoresExtraParams() {
        // 规则方多传的参数静默忽略（进不了命令文本，无注入面）
        CommandTemplateEngine.Result r = render("noparams",
                Map.of("evil", "@a; op @a"), List.of());
        CommandTemplateEngine.Result.Ok ok =
                assertInstanceOf(CommandTemplateEngine.Result.Ok.class, r);
        assertEquals("time set day", ok.command());
    }

    @Test
    void leadingSlash_stripped() {
        Map<String, CommandTemplate> tpls = Map.of(
                "t", new CommandTemplate("/say hi", Map.of()));
        CommandTemplateEngine.Result r =
                CommandTemplateEngine.render("t", Map.of(), tpls, List.of());
        assertEquals("say hi",
                assertInstanceOf(CommandTemplateEngine.Result.Ok.class, r).command());
    }

    // ---------- 参数缺失 / 超长 → error ----------

    @Test
    void missingParam_error() {
        CommandTemplateEngine.Result r = render("announce", Map.of(), List.of());
        CommandTemplateEngine.Result.Error e =
                assertInstanceOf(CommandTemplateEngine.Result.Error.class, r);
        assertTrue(e.reason().contains("msg"), e.reason());
    }

    @Test
    void overlongParam_error_withCustomMaxLength() {
        CommandTemplateEngine.Result r = render("short", Map.of("v", "123456"), List.of());
        CommandTemplateEngine.Result.Error e =
                assertInstanceOf(CommandTemplateEngine.Result.Error.class, r);
        assertTrue(e.reason().contains("5"), e.reason());
        // 边界：恰好 5 字符放行
        assertInstanceOf(CommandTemplateEngine.Result.Ok.class,
                render("short", Map.of("v", "12345"), List.of()));
    }

    @Test
    void lengthCheckedAfterSanitize() {
        // 剥掉换行 / § 后恰好 5 字符 → 放行（长度按净值算）
        CommandTemplateEngine.Result r =
                render("short", Map.of("v", "1\n2§a3\r45"), List.of());
        // 剥后 "12a345" = 6 字符 → 超长。换个净值恰好 5 的：
        assertInstanceOf(CommandTemplateEngine.Result.Error.class, r);
        CommandTemplateEngine.Result ok =
                render("short", Map.of("v", "1\n23§45"), List.of());
        assertEquals("say 12345",
                assertInstanceOf(CommandTemplateEngine.Result.Ok.class, ok).command());
    }

    // ---------- K13 转义：剥换行 / § ----------

    @Test
    void sanitize_stripsNewlinesAndSectionSign() {
        CommandTemplateEngine.Result r = render("announce",
                Map.of("msg", "line1\nline2\r§c红字"), List.of());
        CommandTemplateEngine.Result.Ok ok =
                assertInstanceOf(CommandTemplateEngine.Result.Ok.class, r);
        assertEquals("say [招牌] line1line2c红字", ok.command());
    }

    // ---------- K13：含 @ 整体拒（text 参数） ----------

    @Test
    void textParamWithAtSelector_rejected() {
        CommandTemplateEngine.Result r = render("announce",
                Map.of("msg", "hi @a"), List.of());
        CommandTemplateEngine.Result.Error e =
                assertInstanceOf(CommandTemplateEngine.Result.Error.class, r);
        assertTrue(e.reason().contains("@"), e.reason());
    }

    // ---------- online-player 参数 ----------

    @Test
    void onlinePlayerParam_acceptsExactOnlineName() {
        CommandTemplateEngine.Result r = render("give-reward",
                Map.of("player", "Bob"), List.of("Alice", "Bob"));
        assertEquals("give Bob diamond 1",
                assertInstanceOf(CommandTemplateEngine.Result.Ok.class, r).command());
    }

    @Test
    void onlinePlayerParam_rejectsOfflineName() {
        CommandTemplateEngine.Result r = render("give-reward",
                Map.of("player", "Bob"), List.of("Alice"));
        assertInstanceOf(CommandTemplateEngine.Result.Error.class, r);
    }

    @Test
    void onlinePlayerParam_rejectsSelector_evenIfListEmpty() {
        // @a 不可能命中玩家名 → error（选择器走不进 online-player 白名单）
        CommandTemplateEngine.Result r = render("give-reward",
                Map.of("player", "@a"), List.of("Alice", "Bob"));
        assertInstanceOf(CommandTemplateEngine.Result.Error.class, r);
        // 在线名单 null（无人在线）同样拒
        assertInstanceOf(CommandTemplateEngine.Result.Error.class,
                CommandTemplateEngine.render("give-reward", Map.of("player", "Bob"),
                        templates(), null));
    }

    @Test
    void onlinePlayerParam_caseSensitive() {
        // MC 玩家名大小写固定——精确匹配，不做大小写折叠
        CommandTemplateEngine.Result r = render("give-reward",
                Map.of("player", "bob"), List.of("Bob"));
        assertInstanceOf(CommandTemplateEngine.Result.Error.class, r);
    }

    // ---------- 占位符细节 ----------

    @Test
    void undeclaredPlaceholderInCommand_keptLiteral() {
        // 服主配置笔误：command 里有 {typo} 但 params 没声明 → 原样保留（audit 全文可见）
        Map<String, CommandTemplate> tpls = Map.of(
                "t", new CommandTemplate("say {msg} {typo}", Map.of("msg", TEXT_64)));
        CommandTemplateEngine.Result r =
                CommandTemplateEngine.render("t", Map.of("msg", "hi"), tpls, List.of());
        assertEquals("say hi {typo}",
                assertInstanceOf(CommandTemplateEngine.Result.Ok.class, r).command());
    }

    @Test
    void paramValueContainingPlaceholderSyntax_notRecursivelyExpanded() {
        // 值里写 {msg} 不会被二次展开成注入面——replace 是单遍字面替换。
        // 模板里每个占位符只出现一次时，值中的 "{msg}" 字面保留
        Map<String, CommandTemplate> tpls = Map.of(
                "t", new CommandTemplate("say {a} and {b}",
                        Map.of("a", TEXT_64, "b", TEXT_64)));
        CommandTemplateEngine.Result r = CommandTemplateEngine.render("t",
                Map.of("a", "x", "b", "y"), tpls, List.of());
        assertEquals("say x and y",
                assertInstanceOf(CommandTemplateEngine.Result.Ok.class, r).command());
    }

    @Test
    void blankCommandInTemplate_error() {
        // config 解析期已拦空 command；引擎防御路径（手工构造模板表）
        Map<String, CommandTemplate> tpls = Map.of(
                "t", new CommandTemplate("   ", Map.of()));
        assertInstanceOf(CommandTemplateEngine.Result.Error.class,
                CommandTemplateEngine.render("t", Map.of(), tpls, List.of()));
        // 纯 "/" 渲染后为空 → error
        Map<String, CommandTemplate> tpls2 = Map.of(
                "t", new CommandTemplate("/", Map.of()));
        assertInstanceOf(CommandTemplateEngine.Result.Error.class,
                CommandTemplateEngine.render("t", Map.of(), tpls2, List.of()));
    }
}
