package moe.hikari.canvas.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M6-E：5 个内置模板的回归测试。
 *
 * <p>每个模板用其 {@code min_maps} 作 wall 尺寸 + 全默认 param 走一遍 instantiate，
 * 保证：所有 builtin 都能加载 + 默认值合法 + 至少产生 1 个 element。</p>
 */
class BuiltinTemplatesTest {

    /** 与 docs/template-spec.md §1 加载顺序、{@code _index.txt} 顺序保持一致。 */
    private static Stream<Object[]> builtinTemplates() {
        return Stream.of(
                new Object[]{"hello_world",     1, 1, 2},   // bg rect + text
                new Object[]{"subway_station",  3, 1, 3},   // line bar + name + english
                new Object[]{"shop_sign",       3, 1, 3},   // shop + item + price
                new Object[]{"welcome_banner",  4, 2, 2},   // title + subtitle（footer 默认隐藏）
                new Object[]{"bulletin_board",  3, 1, 3},   // title + divider + body（落款默认空隐藏）
                new Object[]{"nameplate",       2, 1, 3},   // border + house_no + owner
                new Object[]{"info_panel",      3, 1, 3}    // icon + title + body (M7 新增)
        );
    }

    @ParameterizedTest(name = "{0} ({1}x{2}) → {3} element(s)")
    @MethodSource("builtinTemplates")
    void instantiatesWithDefaults(String id, int wallW, int wallH, int expectedElements) {
        TemplateSpec spec = parse(id);
        var r = new TemplateInstantiator().instantiate(spec, Map.of(), wallW, wallH);
        if (r instanceof TemplateInstantiator.Result.Failed f) {
            fail(id + " failed " + f.code() + ": " + f.errors());
        }
        var ok = (TemplateInstantiator.Result.Ok) r;
        assertEquals(wallW, ok.widthMaps());
        assertEquals(wallH, ok.heightMaps());
        assertNotNull(ok.backgroundColor());
        assertEquals(expectedElements, ok.elements().size(),
                id + " default render element count mismatch");
    }

    @Test
    void registryLoadsAllFive(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp);
        TemplateRegistry reg = new TemplateRegistry(
                Logger.getLogger("test"), BuiltinTemplatesTest.class, tmp);
        TemplateRegistry.ReloadStats stats = reg.reload();
        assertEquals(0, stats.failed(), "reload failures: " + stats.failures());
        List<String> required = List.of("hello_world", "subway_station",
                "shop_sign", "welcome_banner", "bulletin_board", "nameplate", "info_panel");
        for (String id : required) {
            TemplateEntry e = reg.byId(id);
            assertNotNull(e, "registry missing builtin id '" + id + "'");
            assertEquals(TemplateSource.BUILTIN, e.source());
        }
        // builtin 数应 ≥ 6（5 个 v1 + hello_world demo）
        assertTrue(stats.builtinLoaded() >= 6,
                "expected ≥ 6 builtin, got " + stats.builtinLoaded());
    }

    @Test
    void welcomeBannerShowsFooterWhenEnabled() {
        var r = new TemplateInstantiator().instantiate(parse("welcome_banner"),
                Map.of("show_footer", true, "footer", "v1.0"), 4, 2);
        var ok = (TemplateInstantiator.Result.Ok) r;
        assertEquals(3, ok.elements().size());
    }

    @Test
    void shopSignCanHideItemRow() {
        var r = new TemplateInstantiator().instantiate(parse("shop_sign"),
                Map.of("show_item", false), 3, 1);
        var ok = (TemplateInstantiator.Result.Ok) r;
        // shop_name + price，无 item 行
        assertEquals(2, ok.elements().size());
    }

    @Test
    void bulletinBoardCanHideDivider() {
        var r = new TemplateInstantiator().instantiate(parse("bulletin_board"),
                Map.of("show_divider", false), 3, 1);
        var ok = (TemplateInstantiator.Result.Ok) r;
        // 隐藏分隔线 → 2 element（title + body；落款默认空也隐藏）
        assertEquals(2, ok.elements().size());
    }

    @Test
    void nameplateOmitsBorderWhenAsked() {
        var r = new TemplateInstantiator().instantiate(parse("nameplate"),
                Map.of("show_border", false), 2, 1);
        var ok = (TemplateInstantiator.Result.Ok) r;
        // border 关 → 2 element（house_no + owner）
        assertEquals(2, ok.elements().size());
    }

    // ---------------- helper ----------------

    private static TemplateSpec parse(String id) {
        String path = "/templates/" + id + ".yml";
        try (InputStream in = BuiltinTemplatesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing classpath resource " + path);
            TemplateLoader.Result r = new TemplateLoader().load(in);
            if (r instanceof TemplateLoader.Result.Failed f) {
                fail(id + " parse failed: " + f.detail());
            }
            return ((TemplateLoader.Result.Ok) r).spec();
        } catch (IOException e) {
            fail(e);
            return null;
        }
    }
}
