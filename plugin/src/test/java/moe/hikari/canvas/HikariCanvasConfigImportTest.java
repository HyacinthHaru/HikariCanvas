package moe.hikari.canvas;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HikariCanvasConfigImportTest {
    @Test
    void importConfigDefaults_areSane() {
        HikariCanvasConfig.ImportConfig c = HikariCanvasConfig.ImportConfig.defaults();
        assertEquals(10, c.canvasMaxMb());
        assertEquals(10, c.canvasMaxEntryMb());
        assertEquals(50, c.canvasMaxTotalMb());
    }

    @Test
    void importConfigDefaults_clampToPositive() {
        // defaults 必须为正，供解包闸使用（0/负会让闸失效）
        HikariCanvasConfig.ImportConfig c = HikariCanvasConfig.ImportConfig.defaults();
        assertTrue(c.canvasMaxMb() > 0 && c.canvasMaxEntryMb() > 0 && c.canvasMaxTotalMb() > 0);
    }
}
