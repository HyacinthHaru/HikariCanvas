package ac.haru.hikaricanvas.template.preview;

import ac.haru.hikaricanvas.render.CanvasCompositor;
import ac.haru.hikaricanvas.render.FontRegistry;
import ac.haru.hikaricanvas.render.PaletteLut;
import ac.haru.hikaricanvas.template.TemplateRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 缩略图端点的跨用户隔离。
 *
 * <p>缩略图是模板画布内容的<b>完整渲染图</b>——不按调用方身份过滤的话，任何人拿到 templateId
 * 就能把别人私有模板的画面整张拉走，{@code template.apply} 那边的隔离就白做了。</p>
 */
class TemplatePreviewAclTest {

    private static final Logger LOG = Logger.getLogger("template-preview-acl-test");

    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID STRANGER = UUID.fromString("99999999-8888-7777-6666-555555555555");

    private static final String PROJECT_JSON =
            "{\"version\":3,\"canvas\":{\"widthMaps\":1,\"heightMaps\":1,\"background\":\"#FFFFFF\"},"
                    + "\"layers\":[{\"id\":\"l0\",\"name\":\"L\",\"visible\":true,\"locked\":false,"
                    + "\"opacity\":1.0,\"blendMode\":\"normal\",\"elements\":[]}],\"activeLayerId\":\"l0\"}";

    private static String manifest(String name) {
        return "{\"spec\":1,\"kind\":\"pack\",\"created_at\":1,\"name\":\"" + name + "\","
                + "\"wall\":{\"width\":1,\"height\":1}}";
    }

    private TemplatePreviewService service;

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        Path serverDir = root.resolve("templates");
        Path userDir = root.resolve("user-templates");
        Files.createDirectories(serverDir);
        Files.createDirectories(userDir.resolve(OWNER.toString()));

        Files.write(serverDir.resolve("public.canvas"), pack(manifest("Public")));
        Files.write(userDir.resolve(OWNER.toString()).resolve("mine.canvas"), pack(manifest("Mine")));

        TemplateRegistry registry = new TemplateRegistry(
                LOG, TemplatePreviewAclTest.class, serverDir, userDir);
        registry.reload();

        PaletteLut palette = PaletteLut.loadFromClasspath("/palette.json");
        FontRegistry fonts = new FontRegistry(LOG);
        fonts.loadBuiltIn();
        service = new TemplatePreviewService(LOG, registry, new CanvasCompositor(palette, fonts, LOG));
    }

    @Test
    void ownerSeesOwnTemplateThumbnail() {
        assertNotNull(service.pngFor("mine", OWNER, false), "owner 应该能看自己模板的缩略图");
    }

    @Test
    void strangerGetsNothingForSomeoneElsesTemplate() {
        assertNull(service.pngFor("mine", STRANGER, false),
                "别人的私有模板缩略图不能给出去");
    }

    /** 端点上解析不出身份（没带 sessionId / session 已失效）时按最保守处理。 */
    @Test
    void anonymousGetsNothingForUserTemplate() {
        assertNull(service.pngFor("mine", null, false),
                "解析不出调用方身份时只放行 builtin / server 模板");
    }

    @Test
    void bypassPermissionSeesEverything() {
        assertNotNull(service.pngFor("mine", STRANGER, true),
                "持 canvas.template.use-others 时可见");
    }

    @Test
    void serverTemplateIsVisibleToEveryoneIncludingAnonymous() {
        assertNotNull(service.pngFor("public", STRANGER, false));
        assertNotNull(service.pngFor("public", null, false));
    }

    /** 越权与不存在返回同一个结果——否则这个端点就成了"枚举别人有哪些模板"的探针。 */
    @Test
    void unknownIdAndForbiddenIdAreIndistinguishable() {
        assertNull(service.pngFor("no-such-template", STRANGER, false));
        assertNull(service.pngFor("mine", STRANGER, false));
    }

    // ---------- helpers ----------

    private static byte[] pack(String manifestJson) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            entry(z, "manifest.json", manifestJson);
            entry(z, "project.json", PROJECT_JSON);
        }
        return bos.toByteArray();
    }

    private static void entry(ZipOutputStream z, String name, String body) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(body.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
