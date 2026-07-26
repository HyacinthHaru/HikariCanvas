package ac.haru.hikaricanvas.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link WebServer#guessMime} 静态资源 MIME 映射表校验。
 *
 * <p>加固点：确保新增扩展名（.map / .wasm / 字体 / .ico / .txt）有正确 MIME，
 * 且未覆盖扩展名维持 null 契约（由 {@code serveClasspath} 兜底为
 * {@code application/octet-stream}）。</p>
 *
 * <p>回归保护：钉住既有 .js / .css / .html / .json / .svg / .png / .woff2 映射不被误改。</p>
 */
class WebServerMimeTest {

    // ---------- 文档 / 脚本 / 样式 ----------

    @Test
    void html() {
        // charset 一并携带；断言前缀即可，别被 charset 变动误伤
        assertEquals("text/html; charset=utf-8", WebServer.guessMime("web/index.html"));
    }

    @Test
    void js() {
        assertEquals("application/javascript", WebServer.guessMime("web/assets/app-abc123.js"));
    }

    @Test
    void mjs() {
        assertEquals("application/javascript", WebServer.guessMime("web/assets/mod.mjs"));
    }

    @Test
    void css() {
        assertEquals("text/css", WebServer.guessMime("web/assets/app-abc123.css"));
    }

    @Test
    void json() {
        assertEquals("application/json", WebServer.guessMime("web/assets/data.json"));
    }

    // ---------- 加固新增 ----------

    @Test
    void sourceMapIsJson() {
        assertEquals("application/json", WebServer.guessMime("web/assets/app-abc123.js.map"));
    }

    @Test
    void wasm() {
        assertEquals("application/wasm", WebServer.guessMime("web/assets/lib.wasm"));
    }

    @Test
    void txt() {
        assertEquals("text/plain; charset=utf-8", WebServer.guessMime("web/robots.txt"));
    }

    @Test
    void fonts() {
        assertEquals("font/woff2", WebServer.guessMime("web/fonts/inter.woff2"));
        assertEquals("font/woff", WebServer.guessMime("web/fonts/inter.woff"));
        assertEquals("font/ttf", WebServer.guessMime("web/fonts/inter.ttf"));
        assertEquals("font/otf", WebServer.guessMime("web/fonts/inter.otf"));
    }

    @Test
    void ico() {
        assertEquals("image/x-icon", WebServer.guessMime("web/favicon.ico"));
    }

    // ---------- 图片（回归） ----------

    @Test
    void images() {
        assertEquals("image/svg+xml", WebServer.guessMime("web/logo.svg"));
        assertEquals("image/png", WebServer.guessMime("web/logo.png"));
    }

    // ---------- null 契约 ----------

    @Test
    void unknownExtensionReturnsNull() {
        // guessMime 保持“猜不到返 null”契约；兜底 application/octet-stream 在 serveClasspath。
        assertNull(WebServer.guessMime("web/mystery.xyz"));
        assertNull(WebServer.guessMime("web/noext"));
    }

    // ---------- /fonts/{file}（字体 advance 表） ----------

    /**
     * 生产环境前端首选通道就是 {@code /fonts/{id}.metrics.json}。web/public 不进仓库、
     * jar 里也没有这条静态路径，以前线上必 404；现在由这条路由直读 jar 内 /fonts/。
     */
    @Test
    void metricsFontId_acceptsValidName() {
        assertEquals("inter", WebServer.metricsFontIdOrNull("inter.metrics.json"));
        assertEquals("source_han_sans",
                WebServer.metricsFontIdOrNull("source_han_sans.metrics.json"));
        assertEquals("my-user-font1", WebServer.metricsFontIdOrNull("my-user-font1.metrics.json"));
    }

    @Test
    void metricsFontId_rejectsNonMetricsFile() {
        assertNull(WebServer.metricsFontIdOrNull("Inter-Regular.otf"), "字体二进制走 /api/font/file");
        assertNull(WebServer.metricsFontIdOrNull("inter.json"));
        assertNull(WebServer.metricsFontIdOrNull(""));
        assertNull(WebServer.metricsFontIdOrNull(null));
    }

    @Test
    void metricsFontId_rejectsTraversalAndWeirdNames() {
        assertNull(WebServer.metricsFontIdOrNull("../secret.metrics.json"));
        assertNull(WebServer.metricsFontIdOrNull("a/b.metrics.json"));
        assertNull(WebServer.metricsFontIdOrNull(".metrics.json"), "空 id 不放行");
        assertNull(WebServer.metricsFontIdOrNull("in ter.metrics.json"));
    }
}
