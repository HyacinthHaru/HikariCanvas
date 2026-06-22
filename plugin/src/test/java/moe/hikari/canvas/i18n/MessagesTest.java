package moe.hikari.canvas.i18n;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagesTest {
    private Messages messages;

    /**
     * Extract plain text from a Component by recursively concatenating content() of all
     * TextComponent nodes. Avoids PlainTextComponentSerializer which triggers Adventure SPI
     * ServiceLoader conflict on test classpath (Paper devbundle + MockBukkit both register
     * PlainTextComponentSerializer$Provider → IllegalStateException "found multiple").
     *
     * NOTE: only supports pure TextComponent trees (the output shape MiniMessage produces for
     * this project's YML strings). Non-TextComponent nodes (e.g. TranslatableComponent from
     * <lang:…>, KeybindComponent from <keybind:…>) are explicitly rejected so misuse surfaces
     * immediately rather than silently dropping content.
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
    private YamlConfiguration res(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/lang-test/" + name), StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() {
        messages = new Messages(Logger.getLogger("test"));
        messages.putLocale("en_us", res("en_us.yml"));
        messages.putLocale("zh_cn", res("zh_cn.yml"));
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
    void loadExternal_overridesBuiltIn(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("en_us.yml"), "command:\n  no-session: \"OVERRIDDEN\"\n");
        Messages m = new Messages(Logger.getLogger("t"));
        m.loadBuiltIn();
        m.loadExternal(dir);
        assertEquals("OVERRIDDEN", m.rawOrNull("en_us", "command.no-session"));
    }
}
