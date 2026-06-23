package moe.hikari.canvas.i18n;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

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

    /** 当前默认 locale id（小写规范化）。用于"不分玩家"的输出（如磁盘报告文件）。 */
    public String defaultLocale() { return defaultLocale; }

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

    public String resolveLocaleId(String rawClientLocale) {
        String n = norm(rawClientLocale);
        return byLocale.containsKey(n) ? n : defaultLocale;
    }

    public String localeId(CommandSender sender) {
        if (sender instanceof Player p) return resolveLocaleId(p.getLocale());
        return defaultLocale;
    }

    public void send(CommandSender to, String key, TagResolver... resolvers) {
        to.sendMessage(get(localeId(to), key, resolvers));
    }

    public void sendActionBar(Player to, String key, TagResolver... resolvers) {
        to.sendActionBar(get(localeId(to), key, resolvers));
    }

    public int size() { return byLocale.size(); }

    private static final List<String> BUNDLED = List.of("en_us", "zh_cn");

    public void loadBuiltIn() {
        for (String id : BUNDLED) {
            String res = "/lang/" + id + ".yml";
            try (InputStream in = Messages.class.getResourceAsStream(res)) {
                if (in == null) { log.warning("[i18n] bundled lang missing: " + res); continue; }
                try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    byLocale.put(id, YamlConfiguration.loadConfiguration(reader));
                }
            } catch (Exception e) {
                log.warning("[i18n] failed to load " + res + ": " + e.getMessage());
            }
        }
    }

    public void loadExternal(Path langDir) {
        if (langDir == null || !Files.isDirectory(langDir)) return;
        try (Stream<Path> s = Files.list(langDir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
             .forEach(p -> {
                 String fn = p.getFileName().toString();
                 String id = norm(fn.substring(0, fn.length() - 4));
                 try {
                     byLocale.put(id, YamlConfiguration.loadConfiguration(p.toFile()));
                 } catch (Exception e) {
                     log.warning("[i18n] failed to load " + fn + ": " + e.getMessage());
                 }
             });
        } catch (Exception e) {
            log.warning("[i18n] failed to scan lang dir: " + e.getMessage());
        }
    }

    public void reload(Path langDir) {
        byLocale.clear();
        warnedMissing.clear();
        loadBuiltIn();
        loadExternal(langDir);
    }
}
