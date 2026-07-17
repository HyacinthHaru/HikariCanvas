package ac.haru.hikaricanvas.template;

import ac.haru.hikaricanvas.state.RectElement;
import ac.haru.hikaricanvas.state.TextElement;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M6-D：内置 hello_world.yml 端到端走通——loader 接受 + 默认 param 走通 + 自定义 param
 * 透传。模拟 WebServer.template.apply 把 hello_world 当真正的 YAML 模板处理的路径。
 */
class HelloWorldYamlTest {

    @Test
    void resourceIsLoadable() {
        // classpath 里能取到 hello_world.yml
        try (InputStream in = getClass().getResourceAsStream("/templates/hello_world.yml")) {
            assertNotNull(in, "resources/templates/hello_world.yml not on classpath");
        } catch (IOException e) {
            fail(e);
        }
    }

    @Test
    void parsesAndInstantiatesWithDefaults() {
        TemplateSpec spec = parseFromResource();
        var r = new TemplateInstantiator().instantiate(spec, Map.of(), 4, 1);
        var ok = assertOk(r);
        assertEquals("hello_world", spec.id());
        assertEquals("#4A90E2", ok.backgroundColor());
        assertEquals(2, ok.elements().size());
        RectElement rect = assertInstanceOf(RectElement.class, ok.elements().get(0));
        TextElement text = assertInstanceOf(TextElement.class, ok.elements().get(1));
        // free 布局 + padding 8 + 100% → contentW=4*128-16=496, contentH=128-16=112
        assertEquals(496, rect.w());
        assertEquals(112, rect.h());
        assertEquals("HELLO WORLD", text.text());
        assertEquals("#FFFFFF", text.color());
    }

    @Test
    void overridesParams() {
        TemplateSpec spec = parseFromResource();
        var params = params("text", "HEY", "bg_color", "#000000", "text_color", "#FFFF00");
        var ok = assertOk(new TemplateInstantiator().instantiate(spec, params, 4, 1));
        assertEquals("#000000", ok.backgroundColor());
        TextElement text = (TextElement) ok.elements().get(1);
        assertEquals("HEY", text.text());
        assertEquals("#FFFF00", text.color());
        RectElement rect = (RectElement) ok.elements().get(0);
        assertEquals("#FFFF00", rect.stroke().color());
    }

    @Test
    void registryLoadsHelloWorld(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        // 用空的 server templates 目录，registry 应只加载 builtin
        Files.createDirectories(tmp);
        TemplateRegistry registry = new TemplateRegistry(
                Logger.getLogger("test"), HelloWorldYamlTest.class, tmp);
        TemplateRegistry.ReloadStats stats = registry.reload();
        assertTrue(stats.builtinLoaded() >= 1,
                "expected hello_world.yml (and possibly subway_station.yml) on classpath");
        TemplateEntry entry = registry.byId("hello_world");
        assertNotNull(entry, "registry missing hello_world after reload");
        assertEquals(TemplateSource.BUILTIN, entry.source());
    }

    // ---------------- helpers ----------------

    private static TemplateSpec parseFromResource() {
        try (InputStream in = HelloWorldYamlTest.class.getResourceAsStream(
                "/templates/hello_world.yml")) {
            assertNotNull(in);
            TemplateLoader.Result r = new TemplateLoader().load(in);
            if (r instanceof TemplateLoader.Result.Failed f) {
                fail("hello_world.yml parse failed: " + f.detail());
            }
            return ((TemplateLoader.Result.Ok) r).spec();
        } catch (IOException e) {
            fail(e);
            return null;
        }
    }

    private static TemplateInstantiator.Result.Ok assertOk(TemplateInstantiator.Result r) {
        if (r instanceof TemplateInstantiator.Result.Failed f) {
            fail("instantiate failed " + f.code() + ": " + f.errors());
        }
        return (TemplateInstantiator.Result.Ok) r;
    }

    private static Map<String, Object> params(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
