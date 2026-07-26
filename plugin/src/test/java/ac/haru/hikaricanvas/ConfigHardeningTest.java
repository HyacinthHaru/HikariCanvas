package ac.haru.hikaricanvas;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * config.yml 解析的三处防呆：
 *
 * <ul>
 *   <li>{@code network.allowed-origins} 列表元素校验 —— YAML 笔误不该炸掉整个插件</li>
 *   <li>{@code session.token-ttl-minutes} / {@code ws-grace-minutes} 下限钳位 ——
 *       服主照着隔壁 idle-minutes 写 0 会静默瘫痪确认流程</li>
 *   <li>{@code throttle.projection-fps} 接线 —— 它以前是死配置，deployment.md 还在教服主调它</li>
 * </ul>
 */
class ConfigHardeningTest {

    private static Logger quietLogger(List<String> sink) {
        Logger logger = Logger.getLogger("cfg-hardening-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) sink.add(r.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        });
        return logger;
    }

    private static YamlConfiguration yaml(String body) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(body);
        } catch (Exception e) {
            throw new AssertionError("测试用 YAML 写错了: " + e, e);
        }
        return y;
    }

    // ---------- allowed-origins 元素校验 ----------

    @Test
    void allowedOrigins_wellFormedList_isKept() {
        List<String> warns = new ArrayList<>();
        List<String> out = HikariCanvasConfig.readAllowedOrigins(yaml("""
                network:
                  allowed-origins:
                    - "https://canvas.example.com"
                    - "http://127.0.0.1:8877"
                """), quietLogger(warns));
        assertEquals(List.of("https://canvas.example.com", "http://127.0.0.1:8877"), out);
        assertTrue(warns.isEmpty());
    }

    /**
     * 空项 / {@code - ~} 会让列表含 null，老实现 {@code List.copyOf} 直接 NPE ——
     * 既炸 onEnable，也炸运行时 {@code /canvas reload config}。
     */
    @Test
    void allowedOrigins_nullEntry_isSkippedNotFatal() {
        List<String> warns = new ArrayList<>();
        List<String> out = HikariCanvasConfig.readAllowedOrigins(yaml("""
                network:
                  allowed-origins:
                    - "https://ok.example.com"
                    - ~
                """), quietLogger(warns));
        assertEquals(List.of("https://ok.example.com"), out);
        assertEquals(1, warns.size(), "非法项要留一条 warning 让服主看得见");
    }

    /**
     * {@code - 443} 之类非字符串项在老实现里能一路混进 List&lt;String&gt;，
     * 到 {@code for (String allowed : allowedOrigins)} 才隐式转型抛 CCE ——
     * 表现是每次 WS 升级 500，离配置文件十万八千里。
     */
    @Test
    void allowedOrigins_nonStringEntry_isSkipped() {
        List<String> warns = new ArrayList<>();
        List<String> out = HikariCanvasConfig.readAllowedOrigins(yaml("""
                network:
                  allowed-origins:
                    - 443
                    - "https://ok.example.com"
                """), quietLogger(warns));
        assertEquals(List.of("https://ok.example.com"), out);
        assertEquals(1, warns.size());
        // 返回的必须是货真价实的 String，遍历不会 CCE
        for (Object o : out) assertTrue(o instanceof String);
    }

    @Test
    void allowedOrigins_missingOrEmpty_returnsEmptyList() {
        List<String> warns = new ArrayList<>();
        assertTrue(HikariCanvasConfig.readAllowedOrigins(yaml("network:\n  port: 8877\n"),
                quietLogger(warns)).isEmpty());
        assertTrue(warns.isEmpty(), "缺字段是正常的，不该告警");
    }

    // ---------- token-ttl / ws-grace 下限钳位 ----------

    @Test
    void sessionMinutes_zeroIsClampedWithWarning() {
        List<String> warns = new ArrayList<>();
        long v = HikariCanvasConfig.clampMinutes(
                yaml("session:\n  token-ttl-minutes: 0\n"),
                "session.token-ttl-minutes", 15, quietLogger(warns));
        assertEquals(15, v, "0 不是「永不超时」，必须钳回默认值");
        assertEquals(1, warns.size(), "钳位必须留痕，否则服主完全不知道为什么打不开编辑器");
        assertTrue(warns.get(0).contains("idle-minutes"),
                "warning 要点明「跟 idle-minutes 不是一个语义」：" + warns.get(0));
    }

    @Test
    void sessionMinutes_negativeIsClamped() {
        List<String> warns = new ArrayList<>();
        assertEquals(5, HikariCanvasConfig.clampMinutes(
                yaml("session:\n  ws-grace-minutes: -3\n"),
                "session.ws-grace-minutes", 5, quietLogger(warns)));
        assertEquals(1, warns.size());
    }

    @Test
    void sessionMinutes_validValuesPassThroughSilently() {
        List<String> warns = new ArrayList<>();
        assertEquals(1, HikariCanvasConfig.clampMinutes(
                yaml("session:\n  token-ttl-minutes: 1\n"),
                "session.token-ttl-minutes", 15, quietLogger(warns)));
        assertEquals(60, HikariCanvasConfig.clampMinutes(
                yaml("session:\n  token-ttl-minutes: 60\n"),
                "session.token-ttl-minutes", 15, quietLogger(warns)));
        assertEquals(15, HikariCanvasConfig.clampMinutes(
                yaml("session:\n  other: 1\n"),
                "session.token-ttl-minutes", 15, quietLogger(warns)));
        assertTrue(warns.isEmpty());
    }

    // ---------- projection-fps 真的接上了 ----------

    /** 整段缺失时按 projection-fps 推算，默认 5fps 仍是历史上的 200ms。 */
    @Test
    void projectionFps_derivesAdaptiveDefaultInterval() {
        assertEquals(200L, HikariCanvasConfig.resolveAdaptiveFps(null, 5).defaultMinIntervalMs());
        assertEquals(500L, HikariCanvasConfig.resolveAdaptiveFps(null, 2).defaultMinIntervalMs(),
                "把 projection-fps 调到 2 必须真的把间隔拉到 500ms，否则又是死配置");
    }

    /** 显式写了 default-min-interval-ms 就以它为准，projection-fps 不再参与。 */
    @Test
    void explicitAdaptiveInterval_winsOverProjectionFps() {
        var sec = yaml("""
                rendering:
                  adaptive-fps:
                    default-min-interval-ms: 120
                """).getConfigurationSection("rendering.adaptive-fps");
        assertEquals(120L, HikariCanvasConfig.resolveAdaptiveFps(sec, 2).defaultMinIntervalMs());
    }

    /** 段存在但没写 default-min-interval-ms 时，仍走 projection-fps 推算。 */
    @Test
    void partialAdaptiveSection_stillDerivesFromProjectionFps() {
        var sec = yaml("""
                rendering:
                  adaptive-fps:
                    push-packets-enabled: false
                """).getConfigurationSection("rendering.adaptive-fps");
        var cfg = HikariCanvasConfig.resolveAdaptiveFps(sec, 4);
        assertEquals(250L, cfg.defaultMinIntervalMs());
        assertFalse(cfg.pushPacketsEnabled());
    }

    /** 高频间隔不该比默认还大；交叉配置时取小者（既有行为，别改坏）。 */
    @Test
    void highFreqIntervalNeverExceedsDefault() {
        var cfg = HikariCanvasConfig.resolveAdaptiveFps(null, 30);
        assertTrue(cfg.highFreqMinIntervalMs() <= cfg.defaultMinIntervalMs(),
                Arrays.toString(new long[]{cfg.highFreqMinIntervalMs(), cfg.defaultMinIntervalMs()}));
        assertTrue(cfg.defaultMinIntervalMs() >= 33L, "间隔下限 33ms 不能被推算绕过");
    }
}
