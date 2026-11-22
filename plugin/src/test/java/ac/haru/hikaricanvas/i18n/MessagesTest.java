package ac.haru.hikaricanvas.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MessagesTest {
    private static final String EN_US_FIXTURE = """
            command:
              no-session: "<gray>No active session.</gray>"
              open-editor: "<gray>Open editor: <url></gray>"
            """;

    private static final String ZH_CN_FIXTURE = """
            command:
              no-session: "<gray>当前没有进行中的会话。</gray>"
            """;

    private Messages messages;

    /**
     * Extract plain text from a Component by recursively concatenating content() of all
     * TextComponent nodes. Avoids PlainTextComponentSerializer which triggers Adventure SPI
     * ServiceLoader conflict on test classpath (Paper devbundle + MockBukkit both register
     * PlainTextComponentSerializer$Provider → IllegalStateException "found multiple").
     */
    private static String plain(Component c) {
        StringBuilder sb = new StringBuilder();
        plainInto(c, sb);
        return sb.toString();
    }

    private static void plainInto(Component c, StringBuilder sb) {
        if (c instanceof TextComponent tc) {
            sb.append(tc.content());
        } else {
            throw new AssertionError("unexpected Component type: " + c.getClass().getName());
        }
        for (Component child : c.children()) plainInto(child, sb);
    }

    private static YamlConfiguration yaml(String content) {
        return YamlConfiguration.loadConfiguration(new StringReader(content));
    }

    @BeforeEach
    void setUp() {
        messages = new Messages(Logger.getLogger("test"));
        messages.putLocale("en_us", yaml(EN_US_FIXTURE));
        messages.putLocale("zh_cn", yaml(ZH_CN_FIXTURE));
        messages.setDefaultLocale("en_us");
    }

    @Test
    void get_returnsLocaleString() {
        assertEquals("No active session.", plain(messages.get("en_us", "command.no-session")));
        assertEquals("当前没有进行中的会话。", plain(messages.get("zh_cn", "command.no-session")));
    }

    @Test
    void get_injectsPlaceholder() {
        Component c = messages.get("en_us", "command.open-editor", Placeholder.unparsed("url", "http://x/1"));
        assertEquals("Open editor: http://x/1", plain(c));
    }

    @Test
    void get_fallsBackToDefaultLocale_whenKeyMissing() {
        // zh 缺 open-editor → 回退 en_us
        Component c = messages.get("zh_cn", "command.open-editor", Placeholder.unparsed("url", "u"));
        assertEquals("Open editor: u", plain(c));
    }

    @Test
    void get_returnsKeyLiteral_whenMissingEverywhere() {
        assertEquals("command.nope", plain(messages.get("en_us", "command.nope")));
    }

    @Test
    void resolveLocaleId_exactMatch() {
        assertEquals("zh_cn", messages.resolveLocaleId("zh_CN"));
        assertEquals("en_us", messages.resolveLocaleId("en_us"));
    }

    @Test
    void resolveLocaleId_unsupported_fallsBackToDefault() {
        assertEquals("en_us", messages.resolveLocaleId("fr_fr")); // default en_us
        assertEquals("en_us", messages.resolveLocaleId(null));
    }

    @Test
    void loadBuiltIn_loadsBundledLocales() {
        Messages m = new Messages(Logger.getLogger("t"));
        m.loadBuiltIn();
        assertEquals(2, m.size());
        assertEquals("English (US)", m.rawOrNull("en_us", "_meta.name"));
    }

    @Test
    void plain_rendersNamedParam_asPlainText() {
        String s = messages.plain("en_us", "command.open-editor",
                Placeholder.unparsed("url", "http://x/42"));
        assertEquals("Open editor: http://x/42", s);
        assertFalse(s.contains("<"), "不应残留 MiniMessage 标签: " + s);
    }

    @Test
    void loadExternal_overridesBuiltIn(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("en_us.yml"), "command:\n  no-session: \"OVERRIDDEN\"\n");
        Messages m = new Messages(Logger.getLogger("t"));
        m.loadBuiltIn();
        m.loadExternal(dir);
        assertEquals("OVERRIDDEN", m.rawOrNull("en_us", "command.no-session"));
    }

    /**
     * 外部文件是按键合并，不是整份替换。服主只改了一句话，其余全部保留内置值 ——
     * 否则插件升级后新增的键在他那份文件里不存在，玩家直接看到 {@code canvas.error.foo} 这种键名。
     */
    @Test
    void loadExternal_mergesPerKey_keepsUntouchedBuiltInKeys(@TempDir Path dir) throws Exception {
        Messages builtInOnly = new Messages(Logger.getLogger("t"));
        builtInOnly.loadBuiltIn();
        String otherKeyBefore = builtInOnly.rawOrNull("en_us", "command.no-wall-session");
        assertNotNull(otherKeyBefore, "前提：内置 en_us 里有 command.no-wall-session");

        Files.writeString(dir.resolve("en_us.yml"), "command:\n  no-session: \"OVERRIDDEN\"\n");
        Messages m = new Messages(Logger.getLogger("t"));
        m.loadBuiltIn();
        m.loadExternal(dir);

        assertEquals("OVERRIDDEN", m.rawOrNull("en_us", "command.no-session"), "改过的键用服主的");
        assertEquals(otherKeyBefore, m.rawOrNull("en_us", "command.no-wall-session"),
                "没改的键保留内置值，不能因为外部文件里没有就消失");
    }

    /** 兜底链的终点 defaultLocale 也必须是合并后的，否则缺键仍然会露出原始 key 名。 */
    @Test
    void loadExternal_defaultLocaleFallbackStillResolves(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("en_us.yml"), "command:\n  no-session: \"OVERRIDDEN\"\n");
        Messages m = new Messages(Logger.getLogger("t"));
        m.loadBuiltIn();
        m.loadExternal(dir);
        m.setDefaultLocale("en_us");
        // 服主没写进外部文件的键，回退 defaultLocale 时仍要拿到内置文案而不是 key 名本身
        String key = "command.no-wall-session";
        assertNotEquals(key, plain(m.get("en_us", key)));
    }

    /** 服主新增一个内置没有的语言文件，照常整份生效（合并函数只在已有内置时才介入）。 */
    @Test
    void loadExternal_newLocale_loadsAsIs(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("fr_fr.yml"), "command:\n  no-session: \"Aucune session\"\n");
        Messages m = new Messages(Logger.getLogger("t"));
        m.loadBuiltIn();
        m.loadExternal(dir);
        assertEquals("Aucune session", m.rawOrNull("fr_fr", "command.no-session"));
    }
}
