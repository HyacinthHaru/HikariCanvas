package ac.haru.hikaricanvas.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TemplateRegistry} 对 {@code .canvas} pack 的加载（Slice 2 P1）。
 *
 * <p>验证：server 目录里的 {@code *.canvas}（{@code manifest.kind="pack"}）被登记为 pack 条目
 * （id = 文件名去后缀 / {@link TemplateEntry#isPack()} / {@code packBytes} 原样存 / 合成
 * {@link TemplateSpec} 带 params）；同目录里 {@code kind="project"} 的普通工程 {@code .canvas} 被跳过
 * （非模板），且既有 YAML 加载不受影响。</p>
 */
class TemplateRegistryPackTest {

    private static final Logger LOG = Logger.getLogger("template-registry-pack-test");

    private static final String PACK_MANIFEST =
            "{\"spec\":1,\"kind\":\"pack\",\"created_at\":1,\"name\":\"Subway Sign\","
                    + "\"wall\":{\"width\":2,\"height\":1}}";
    private static final String PACK_PARAMS =
            "[{\"id\":\"title\",\"type\":\"string\",\"label\":\"标题\",\"default\":\"你好\",\"max_length\":8},"
                    + "{\"id\":\"off\",\"type\":\"int\",\"label\":\"偏移\",\"default\":10}]";
    /** 注册期不物化 project.json，最小占位即可（unpack 只要求条目存在）。 */
    private static final String MINIMAL_PROJECT = "{}";
    private static final String PROJECT_MANIFEST =
            "{\"spec\":1,\"kind\":\"project\",\"created_at\":1,\"name\":\"Plain\","
                    + "\"wall\":{\"width\":2,\"height\":1}}";

    @Test
    void serverPackIsRegisteredAsPackEntry(@TempDir Path serverDir) throws IOException {
        Files.write(serverDir.resolve("subway.canvas"),
                buildPack(PACK_MANIFEST, PACK_PARAMS, MINIMAL_PROJECT));

        TemplateRegistry reg = new TemplateRegistry(LOG, TemplateRegistryPackTest.class, serverDir);
        TemplateRegistry.ReloadStats stats = reg.reload();

        assertEquals(0, stats.failed(), "reload failures: " + stats.failures());

        // id = 文件名去 .canvas 后缀
        TemplateEntry entry = reg.byId("subway");
        assertNotNull(entry, "pack 应以文件名 stem 'subway' 登记");
        assertEquals(TemplateSource.SERVER, entry.source());
        assertTrue(entry.isPack(), "kind=pack 条目 isPack() 应为 true");
        assertNotNull(entry.packBytes(), "pack 条目应保留原始字节");
        assertTrue(entry.packBytes().length > 0);

        // 合成 spec 供 UI 统一读：id / name / spec / params 填齐，画布字段留 null
        TemplateSpec spec = entry.spec();
        assertEquals("subway", spec.id());
        assertEquals("Subway Sign", spec.name(), "name 取自 manifest.name");
        assertEquals(1, spec.spec());
        assertNotNull(spec.params());
        assertTrue(spec.params().containsKey("title"));
        assertTrue(spec.params().containsKey("off"));
        assertEquals("string", spec.params().get("title").type());
        assertEquals(8, spec.params().get("title").maxLength(), "params.json 字段应映射进 TemplateParam");
        assertEquals("int", spec.params().get("off").type());
        assertFalse(spec.isRawStateMode(), "pack 合成 spec 非 rawState 模式");

        assertEquals(1, stats.serverLoaded(), "只有 pack 计入 server（普通工程被跳过、不计数）");
    }

    @Test
    void plainProjectCanvasIsSkipped(@TempDir Path serverDir) throws IOException {
        Files.write(serverDir.resolve("subway.canvas"),
                buildPack(PACK_MANIFEST, PACK_PARAMS, MINIMAL_PROJECT));
        // kind=project（普通工程，非模板）——同目录，应被跳过，不登记、不计失败
        Files.write(serverDir.resolve("plainproj.canvas"),
                buildProject(PROJECT_MANIFEST, MINIMAL_PROJECT));

        TemplateRegistry reg = new TemplateRegistry(LOG, TemplateRegistryPackTest.class, serverDir);
        TemplateRegistry.ReloadStats stats = reg.reload();

        assertNull(reg.byId("plainproj"), "kind=project 的 .canvas 不是模板，不应登记");
        assertNotNull(reg.byId("subway"), "同目录的 pack 仍正常登记");
        assertEquals(0, stats.failed(), "跳过普通工程不算失败: " + stats.failures());
        assertEquals(1, stats.serverLoaded(), "仅 pack 计入 server");
    }

    @Test
    void malformedPackIsSkippedWithoutAbortingReload(@TempDir Path serverDir) throws IOException {
        // 好 pack + 坏 pack（manifest 非法 JSON）同目录：坏的记失败并跳过，好的照常登记
        Files.write(serverDir.resolve("subway.canvas"),
                buildPack(PACK_MANIFEST, PACK_PARAMS, MINIMAL_PROJECT));
        Files.write(serverDir.resolve("broken.canvas"),
                buildPack("{ not json", PACK_PARAMS, MINIMAL_PROJECT));

        TemplateRegistry reg = new TemplateRegistry(LOG, TemplateRegistryPackTest.class, serverDir);
        TemplateRegistry.ReloadStats stats = reg.reload();

        assertNotNull(reg.byId("subway"), "坏包不应影响好包登记");
        assertNull(reg.byId("broken"));
        assertEquals(1, stats.failed(), "坏包计一次失败");
        assertTrue(stats.failures().stream().anyMatch(s -> s.contains("broken.canvas")));
    }

    // ---------- helpers ----------

    private static byte[] buildPack(String manifest, String params, String project) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            writeEntry(z, "manifest.json", manifest);
            writeEntry(z, "params.json", params);
            writeEntry(z, "project.json", project);
        }
        return bos.toByteArray();
    }

    private static byte[] buildProject(String manifest, String project) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            writeEntry(z, "manifest.json", manifest);
            writeEntry(z, "project.json", project);
        }
        return bos.toByteArray();
    }

    private static void writeEntry(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
