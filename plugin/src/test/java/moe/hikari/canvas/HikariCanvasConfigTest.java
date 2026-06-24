package moe.hikari.canvas;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.9.3 Task 2：{@link HikariCanvasConfig#warnIfPublicBind} 公网绑定告警纯逻辑单测。 */
class HikariCanvasConfigTest {

    /** 捕获指定 logger 在 WARNING 及以上级别打出的所有消息。 */
    private static List<String> captureWarnings(String host) {
        Logger logger = Logger.getLogger("cfg-test-" + host + "-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        List<String> out = new ArrayList<>();
        Handler h = new Handler() {
            @Override public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) out.add(r.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(h);
        HikariCanvasConfig.warnIfPublicBind(host, logger);
        return out;
    }

    @Test
    void bind_0000_warnsThreeLines() {
        List<String> w = captureWarnings("0.0.0.0");
        assertEquals(3, w.size(), "0.0.0.0 应打 3 行警告: " + w);
        assertTrue(String.join("\n", w).contains("0.0.0.0"), "警告应回显 host: " + w);
        assertTrue(String.join("\n", w).toLowerCase().contains("reverse proxy"),
                "警告应提示反代 + TLS: " + w);
    }

    @Test
    void bind_ipv6Wildcard_warns() {
        assertEquals(3, captureWarnings("::").size(), ":: 应打 3 行警告");
        assertEquals(3, captureWarnings("[::]").size(), "[::] 应打 3 行警告");
    }

    @Test
    void bind_loopback_silent() {
        assertTrue(captureWarnings("127.0.0.1").isEmpty(), "127.0.0.1 不应告警");
        assertTrue(captureWarnings("localhost").isEmpty(), "localhost 不应告警");
        assertTrue(captureWarnings("192.168.1.50").isEmpty(), "私网具体地址不应告警");
    }
}
