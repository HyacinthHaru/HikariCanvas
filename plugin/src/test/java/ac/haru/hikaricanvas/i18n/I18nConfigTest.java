package ac.haru.hikaricanvas.i18n;
import static org.junit.jupiter.api.Assertions.*;
import ac.haru.hikaricanvas.HikariCanvasConfig.I18nConfig;
import org.junit.jupiter.api.Test;

class I18nConfigTest {
    @Test void defaults_areEnUs() {
        assertEquals("en_us", I18nConfig.defaults().defaultLocale());
    }
}
