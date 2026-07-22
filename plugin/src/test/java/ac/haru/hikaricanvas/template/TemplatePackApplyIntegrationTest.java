package ac.haru.hikaricanvas.template;

import ac.haru.hikaricanvas.HikariCanvasConfig;
import ac.haru.hikaricanvas.canvasfile.AssetIngest;
import ac.haru.hikaricanvas.canvasfile.ProjectImporter;
import ac.haru.hikaricanvas.image.ImageQuotaService;
import ac.haru.hikaricanvas.image.ImageStorage;
import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionTestFactory;
import ac.haru.hikaricanvas.state.EditSession;
import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.StatePatch;
import ac.haru.hikaricanvas.state.TextElement;
import ac.haru.hikaricanvas.storage.AuditLog;
import ac.haru.hikaricanvas.storage.Database;
import ac.haru.hikaricanvas.storage.ImageUploadDao;
import ac.haru.hikaricanvas.storage.MigrationRunner;
import ac.haru.hikaricanvas.storage.WallRepo;
import ac.haru.hikaricanvas.web.OpPushCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 端到端接缝：{@link TemplateRegistry} 从磁盘加载一个 {@code .canvas} pack →
 * 取 {@link TemplateEntry#packBytes()} → 喂 {@link ProjectImporter#applyPack} → 参数替换 + 物化
 * 落到会话工程。两个 slice 各自单测了半边（registry 加载 / applyPack 独立跑），本用例是唯一把
 * <b>registry 存下来的那份字节真的走一遍套用</b>的验证——防接缝回归（entry 存了但 applyPack 拿不到 /
 * 字节形态对不上）。
 */
class TemplatePackApplyIntegrationTest {

    private static final Logger LOG = Logger.getLogger("template-pack-apply-e2e");

    @TempDir
    Path serverDir;

    private Path tmpDir;
    private Database database;
    private WallRepo wallRepo;
    private AssetIngest assetIngest;
    private AuditLog auditLog;
    private CapturingPush push;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hc-pack-e2e-");
        database = new Database(LOG, tmpDir.resolve("d.db"));
        new MigrationRunner(database.jdbi(), LOG).run();
        ImageUploadDao dao = new ImageUploadDao(LOG, database.jdbi());
        ImageStorage storage = new ImageStorage(LOG, tmpDir, dao);
        wallRepo = new WallRepo(LOG, database.jdbi());
        ImageQuotaService quota = new ImageQuotaService(dao, HikariCanvasConfig.ImageConfig.defaults());
        assetIngest = new AssetIngest(LOG, storage, quota, dao, wallRepo, database.jdbi());
        auditLog = new AuditLog(database.jdbi(), LOG);
        push = new CapturingPush();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) database.close();
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var paths = Files.walk(tmpDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort temp cleanup
                    }
                });
            }
        }
    }

    @Test
    void registryPackBytes_flowThroughApplyPack_substituteAndMaterialize() throws Exception {
        // 磁盘上放一个可参数化 pack：x 走 ${off}（int）、text 走 ${title}（string）。
        Files.write(serverDir.resolve("subway.canvas"), buildPack(
                "{\"spec\":1,\"kind\":\"pack\",\"created_at\":1,\"name\":\"Subway\","
                        + "\"wall\":{\"width\":2,\"height\":1}}",
                "[{\"id\":\"title\",\"type\":\"string\",\"default\":\"你好\",\"max_length\":8},"
                        + "{\"id\":\"off\",\"type\":\"int\",\"default\":10}]",
                projectWith("\"${off}\"", "\"${title}\"")));

        // registry 从磁盘加载 → 拿到 pack 条目
        TemplateRegistry reg = new TemplateRegistry(LOG, TemplatePackApplyIntegrationTest.class, serverDir);
        assertEquals(0, reg.reload().failed());
        TemplateEntry entry = reg.byId("subway");
        assertTrue(entry.isPack(), "应登记为 pack 条目");

        // registry 存下的那份字节 → applyPack（填自定义参数覆盖 default）
        Session session = SessionTestFactory.withWallAndProject(
                "sess-1", UUID.randomUUID(), "Steve", "wall-1", new ProjectState(2, 1));
        ProjectImporter importer = new ProjectImporter(HikariCanvasConfig.ImportConfig.defaults(),
                assetIngest, push, wallRepo, null, auditLog, null, null);

        ProjectImporter.ApplyResult r = importer.applyPack(
                session, entry.packBytes(), Map.of("title", "地铁", "off", 5), UUID.randomUUID());

        assertInstanceOf(EditSession.OpResult.OkSnapshot.class, r.result());
        Element el = session.projectState().layers().get(0).elements().get(0);
        TextElement t = assertInstanceOf(TextElement.class, el);
        assertEquals("地铁", t.text(), "字符串参数应替换进 text");
        assertEquals(5, t.x(), "数值参数应替换后 materialize 回 int");
    }

    private static String projectWith(String xExpr, String textExpr) {
        return "{\"version\":3,\"canvas\":{\"widthMaps\":2,\"heightMaps\":1,\"background\":\"#FFFFFF\"},"
                + "\"layers\":[{\"id\":\"l1\",\"name\":\"L\",\"visible\":true,\"locked\":false,\"opacity\":1.0,"
                + "\"blendMode\":\"normal\",\"elements\":[{\"id\":\"e1\",\"type\":\"text\",\"x\":" + xExpr
                + ",\"y\":0,\"w\":80,\"h\":40,\"rotation\":0,\"text\":" + textExpr
                + ",\"fontId\":\"ark_pixel\",\"fontSize\":24,\"color\":\"#000000\",\"align\":\"left\","
                + "\"letterSpacing\":0.0,\"lineHeight\":1.2,\"vertical\":false}]}],\"activeLayerId\":\"l1\"}";
    }

    private static byte[] buildPack(String manifest, String params, String project) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            writeEntry(z, "manifest.json", manifest);
            writeEntry(z, "params.json", params);
            writeEntry(z, "project.json", project);
        }
        return bos.toByteArray();
    }

    private static void writeEntry(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    /** 捕获 pushSnapshot 的 fake（同 ProjectImporterPackTest）。 */
    private static final class CapturingPush implements OpPushCallback {
        @Override
        public boolean pushSnapshot(String sessionId, ProjectState state) {
            return true;
        }

        @Override
        public boolean pushPatch(String sessionId, StatePatch patch) {
            return true;
        }
    }
}
