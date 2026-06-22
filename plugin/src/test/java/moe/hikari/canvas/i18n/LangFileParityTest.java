package moe.hikari.canvas.i18n;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LangFileParityTest {
    private Set<String> leafKeys(String name) {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/lang/" + name), StandardCharsets.UTF_8));
        Set<String> keys = new TreeSet<>(c.getKeys(true));
        // 仅留叶子（无子节点的路径），排除 _meta
        keys.removeIf(k -> c.isConfigurationSection(k) || k.equals("_meta") || k.startsWith("_meta."));
        return keys;
    }

    @Test
    void bundledLangFiles_haveIdenticalLeafKeys() {
        Set<String> en = leafKeys("en_us.yml");
        Set<String> zh = leafKeys("zh_cn.yml");
        Set<String> enOnly = new TreeSet<>(en); enOnly.removeAll(zh);
        Set<String> zhOnly = new TreeSet<>(zh); zhOnly.removeAll(en);
        assertTrue(enOnly.isEmpty(), "keys in en_us but not zh_cn: " + enOnly);
        assertTrue(zhOnly.isEmpty(), "keys in zh_cn but not en_us: " + zhOnly);
    }
}
