package moe.hikari.canvas.rail;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 0.4.4 P1：ServiceType enum / i18n 单测。
 */
class ServiceTypeTest {

    @Test
    void parseOrCustom_recognizesBuiltins() {
        assertEquals(ServiceType.LOCAL, ServiceType.parseOrCustom("local"));
        assertEquals(ServiceType.EXPRESS, ServiceType.parseOrCustom("EXPRESS"));
        assertEquals(ServiceType.SECTION, ServiceType.parseOrCustom("Section"));
        assertEquals(ServiceType.LIMITED, ServiceType.parseOrCustom("limited"));
    }

    @Test
    void parseOrCustom_returnsNullForCustom() {
        assertNull(ServiceType.parseOrCustom("通勤特急"));
        assertNull(ServiceType.parseOrCustom(""));
        assertNull(ServiceType.parseOrCustom(null));
    }

    @Test
    void displayText_zhCnLocale() {
        assertEquals("站站停", ServiceType.displayText("local", Locale.SIMPLIFIED_CHINESE));
        assertEquals("大站快车", ServiceType.displayText("express", Locale.SIMPLIFIED_CHINESE));
        assertEquals("区间车", ServiceType.displayText("section", Locale.SIMPLIFIED_CHINESE));
        assertEquals("特快", ServiceType.displayText("limited", Locale.SIMPLIFIED_CHINESE));
    }

    @Test
    void displayText_englishLocale() {
        assertEquals("Local", ServiceType.displayText("local", Locale.US));
        assertEquals("Express", ServiceType.displayText("express", Locale.ENGLISH));
    }

    @Test
    void displayText_customStringPassesThrough() {
        assertEquals("通勤特急",
                ServiceType.displayText("通勤特急", Locale.SIMPLIFIED_CHINESE));
        assertEquals("RUSH-HOUR-FAST",
                ServiceType.displayText("RUSH-HOUR-FAST", Locale.US));
    }

    @Test
    void displayText_emptyOrNull_returnsEmpty() {
        assertEquals("", ServiceType.displayText("", Locale.SIMPLIFIED_CHINESE));
        assertEquals("", ServiceType.displayText(null, Locale.SIMPLIFIED_CHINESE));
    }

    @Test
    void dbValue_lowerCase() {
        assertEquals("local", ServiceType.LOCAL.dbValue());
        assertEquals("limited", ServiceType.LIMITED.dbValue());
    }
}
