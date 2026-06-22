package moe.hikari.canvas.i18n;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;

/** i18n 文案中枢：YamlConfiguration 持各 locale 文案，MiniMessage 渲染。无 Bukkit 主线程依赖，可单测。 */
public final class Messages {
    private final Logger log;
    private final Map<String, YamlConfiguration> byLocale = new ConcurrentHashMap<>();
    private final Set<String> warnedMissing = ConcurrentHashMap.newKeySet();
    private volatile String defaultLocale = "en_us";

    public Messages(Logger log) { this.log = log; }

    private static String norm(String id) {
        return id == null ? "" : id.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    public void putLocale(String localeId, YamlConfiguration cfg) {
        byLocale.put(norm(localeId), cfg);
    }

    public void setDefaultLocale(String localeId) {
        String n = norm(localeId);
        if (!byLocale.containsKey(n)) {
            log.warning("[i18n] setDefaultLocale: unknown locale '" + n + "', keeping en_us");
        }
        this.defaultLocale = byLocale.containsKey(n) ? n : "en_us";
    }

    public String rawOrNull(String localeId, String key) {
        YamlConfiguration cfg = byLocale.get(norm(localeId));
        return cfg == null ? null : cfg.getString(key);
    }

    public Component get(String localeId, String key, TagResolver... resolvers) {
        String raw = rawOrNull(localeId, key);
        if (raw == null) raw = rawOrNull(defaultLocale, key);
        if (raw == null) {
            if (warnedMissing.add(key)) log.warning("[i18n] missing key: " + key);
            return Component.text(key);
        }
        return MiniMessage.miniMessage().deserialize(raw, resolvers);
    }

    public int size() { return byLocale.size(); }
}
