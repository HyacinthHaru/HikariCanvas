package ac.haru.hikaricanvas.i18n;

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
import net.kyori.adventure.text.TextComponent;
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

    /**
     * 渲染成纯文本 String（WS error message 字段用；WS 的 message 字段是 String，
     * validation 文案无格式，纯文本即可）。{@link #get} 出 Component 后递归抽取
     * TextComponent 的 content 拼接；找不到 key 时 get() 已回退 defaultLocale / key 名。
     *
     * <p><b>不用 PlainTextComponentSerializer</b>：其 static init 在测试 classpath 上
     * （Paper devbundle + MockBukkit 双份 {@code PlainTextComponentSerializer$Provider}）
     * 抛 {@code ExceptionInInitializerError}（"found multiple"）——{@code MessagesTest}
     * 已为此手写同款递归 flatten。本项目所有 YML 文案经 MiniMessage 反序列化后均为纯
     * {@link TextComponent} 树（无 {@code <lang:…>} / {@code <keybind:…>} 等非文本节点），
     * 故递归拼接 content 与 PlainTextComponentSerializer 对这些字符串输出等价，且零 SPI 依赖。</p>
     */
    public String plain(String localeId, String key, TagResolver... resolvers) {
        StringBuilder sb = new StringBuilder();
        flattenPlain(get(localeId, key, resolvers), sb);
        return sb.toString();
    }

    /**
     * 递归把 {@link Component} 树的文本内容拼进 {@code sb}。仅支持纯
     * {@link TextComponent}（本项目 MiniMessage 对 YML 字符串的产物形态）；遇到非
     * TextComponent 节点（如 TranslatableComponent）保守跳过其自身内容但仍递归子节点，
     * 避免抛异常打断 WS 错误帧渲染。
     */
    private static void flattenPlain(Component c, StringBuilder sb) {
        if (c instanceof TextComponent tc) {
            sb.append(tc.content());
        }
        for (Component child : c.children()) {
            flattenPlain(child, sb);
        }
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

    /**
     * 加载服主放在 {@code plugins/HikariCanvas/lang/} 下的外部文案，**按键合并**到已加载的内置文案上：
     * 同名键用服主的，外部文件没写的键保留内置值。
     *
     * <p>此前是整份 {@code put} 替换。那样一旦服主改过某个语言文件，插件升级后新增的键在他的文件里
     * 不存在，而兜底链的 defaultLocale 指向的也是这份被替换掉的外部文案 —— 结果玩家直接看到
     * {@code canvas.error.foo} 这种原始键名。键级合并让"改了几句话"不再等于"锁死整套文案的版本"。</p>
     */
    public void loadExternal(Path langDir) {
        if (langDir == null || !Files.isDirectory(langDir)) return;
        try (Stream<Path> s = Files.list(langDir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
             .forEach(p -> {
                 String fn = p.getFileName().toString();
                 String id = norm(fn.substring(0, fn.length() - 4));
                 try {
                     byLocale.merge(id, YamlConfiguration.loadConfiguration(p.toFile()),
                             Messages::mergeOverKeys);
                 } catch (Exception e) {
                     log.warning("[i18n] failed to load " + fn + ": " + e.getMessage());
                 }
             });
        } catch (Exception e) {
            log.warning("[i18n] failed to scan lang dir: " + e.getMessage());
        }
    }

    /**
     * 把 {@code external} 的每个叶子键覆盖到 {@code builtIn} 的副本上（外部优先，缺的键回落内置）。
     *
     * <p>用 {@code getKeys(true)} 拿全部深层路径，只搬叶子（非 ConfigurationSection）——
     * 直接搬中间节点会把内置的同级兄弟键整段挤掉，那就退化回整份替换了。</p>
     */
    private static YamlConfiguration mergeOverKeys(YamlConfiguration builtIn, YamlConfiguration external) {
        YamlConfiguration merged = new YamlConfiguration();
        for (String key : builtIn.getKeys(true)) {
            if (builtIn.isConfigurationSection(key)) continue;
            merged.set(key, builtIn.get(key));
        }
        for (String key : external.getKeys(true)) {
            if (external.isConfigurationSection(key)) continue;
            merged.set(key, external.get(key));
        }
        return merged;
    }

    public void reload(Path langDir) {
        byLocale.clear();
        warnedMissing.clear();
        loadBuiltIn();
        loadExternal(langDir);
    }
}
