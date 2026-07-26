package ac.haru.hikaricanvas.template.asset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 找不到的图标要被记住，别每帧重查。
 *
 * <p>元素校验只看 source 的字符格式、不查图标存不存在，所以任意名字都能进画布；渲染器每帧调
 * {@code loadIcon}。以前用 {@code computeIfAbsent} + 返回 null —— 按 JDK 语义 map 根本不写表，
 * 于是每帧一次 classpath 查找 + 一次磁盘 stat + 一行 WARNING 刷屏。</p>
 */
class TemplateAssetServiceTest {

    private RecordingHandler handler;
    private Logger log;

    @BeforeEach
    void setUp() {
        handler = new RecordingHandler();
        log = Logger.getLogger("TemplateAssetServiceTest-" + System.nanoTime());
        log.setUseParentHandlers(false);
        log.addHandler(handler);
    }

    @Test
    void missingIcon_looksUpOnceThenRemembers(@TempDir Path dataFolder) {
        TemplateAssetService svc = new TemplateAssetService(log, dataFolder);

        for (int i = 0; i < 5; i++) {
            assertNull(svc.loadIcon("definitely-not-here"));
        }
        assertEquals(1, handler.warnings().size(),
                "5 次调用只该查一次、只该告警一次，实际: " + handler.warnings());
    }

    @Test
    void missingIcon_pngEndpointAlsoRemembers(@TempDir Path dataFolder) {
        TemplateAssetService svc = new TemplateAssetService(log, dataFolder);

        for (int i = 0; i < 5; i++) {
            assertNull(svc.iconPng("definitely-not-here"));
        }
        assertEquals(1, handler.warnings().size(),
                "HTTP 端点可被反复打，同样只查一次: " + handler.warnings());
    }

    @Test
    void invalidate_clearsNegativeCache_soAddedIconIsPickedUp(@TempDir Path dataFolder) {
        TemplateAssetService svc = new TemplateAssetService(log, dataFolder);
        assertNull(svc.loadIcon("definitely-not-here"));
        assertEquals(1, handler.warnings().size());

        // 服主补了图标后 /canvas reload → invalidate；"找不到"的记录也要一起清掉
        svc.invalidate();
        assertNull(svc.loadIcon("definitely-not-here"));
        assertEquals(2, handler.warnings().size(), "清缓存后应重新查一次");
    }

    @Test
    void invalidName_rejectedWithoutTouchingCache(@TempDir Path dataFolder) {
        TemplateAssetService svc = new TemplateAssetService(log, dataFolder);
        assertNull(svc.loadIcon("../../etc/passwd"));
        assertNull(svc.iconPng(null));
        assertEquals(0, handler.warnings().size(), "非法名在正则那关就挡了，不查盘不告警");
    }

    private static final class RecordingHandler extends Handler {
        private final List<String> warnings = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record.getMessage());
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        List<String> warnings() {
            return warnings;
        }
    }
}
