package ac.haru.hikaricanvas.canvasfile;

import ac.haru.hikaricanvas.HikariCanvasConfig;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pack（{@code manifest.kind="pack"}）套用管线端到端：{@link ProjectImporter#applyPack} 走
 * unpack → manifest → 参数校验 + {@code ${param}} 替换 → materialize → replaceProject 的 build 段，
 * 不跑 propagate（调用方接尾段）。
 *
 * <p>装配与 {@code ProjectImporterTest} 一致（真 WallRepo / AssetIngest / AuditLog + 捕获式 push）。
 * fixture 构造 helper 从 {@code ProjectImporterTest} 搬来并加 {@code buildPack}（含 params.json）。</p>
 */
class ProjectImporterPackTest {

    private static final Logger LOG = Logger.getLogger("project-importer-pack-test");

    private Path tmpDir;
    private Database database;
    private WallRepo wallRepo;
    private AssetIngest assetIngest;
    private AuditLog auditLog;
    private CapturingPush push;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hc-pack-importer-");
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

    private ProjectImporter newImporter() {
        return new ProjectImporter(HikariCanvasConfig.ImportConfig.defaults(),
                assetIngest, push, wallRepo, null, auditLog, null, null);
    }

    private Session freshSession(int w, int h) {
        return SessionTestFactory.withWallAndProject(
                "sess-1", UUID.randomUUID(), "Steve", "wall-1", new ProjectState(w, h));
    }

    private static final String PACK_MANIFEST =
            "{\"spec\":1,\"kind\":\"pack\",\"created_at\":1,\"name\":\"Demo\",\"wall\":{\"width\":2,\"height\":1}}";

    /** project.json 模板：{@code x} / {@code text} 走占位符（{@code %s} 占位供各用例填不同 params.json）。 */
    private static String projectWith(String xExpr, String textExpr) {
        return "{\"version\":3,\"canvas\":{\"widthMaps\":2,\"heightMaps\":1,\"background\":\"#FFFFFF\"},"
                + "\"layers\":[{\"id\":\"l1\",\"name\":\"L\",\"visible\":true,\"locked\":false,\"opacity\":1.0,"
                + "\"blendMode\":\"normal\",\"elements\":[{\"id\":\"e1\",\"type\":\"text\",\"x\":" + xExpr
                + ",\"y\":0,\"w\":80,\"h\":40,\"rotation\":0,\"text\":" + textExpr
                + ",\"fontId\":\"ark_pixel\",\"fontSize\":24,\"color\":\"#000000\",\"align\":\"left\","
                + "\"letterSpacing\":0.0,\"lineHeight\":1.2,\"vertical\":false}]}],\"activeLayerId\":\"l1\"}";
    }

    private static TextElement firstText(Session session) {
        Element el = session.projectState().layers().get(0).elements().get(0);
        return assertInstanceOf(TextElement.class, el);
    }

    // ---------- D3 风险点：数值字段占位符端到端解析回 int ----------

    @Test
    void applyPack_numericFieldPlaceholder_materializesInt() throws Exception {
        Session session = freshSession(2, 1);
        // x 以字符串占位符 "${off}" 写；off 是 int 参数、default 10。替换成 "10" 后 materialize 的
        // Jackson coercion 解析回 int 10。
        byte[] pack = buildPack(PACK_MANIFEST,
                "[{\"id\":\"off\",\"type\":\"int\",\"label\":\"偏移\",\"default\":10},"
                        + "{\"id\":\"title\",\"type\":\"string\",\"label\":\"标题\",\"default\":\"你好\",\"max_length\":8}]",
                projectWith("\"${off}\"", "\"${title}\""));

        ProjectImporter.ApplyResult r = newImporter().applyPack(session, pack, Map.of(), UUID.randomUUID());

        assertInstanceOf(EditSession.OpResult.OkSnapshot.class, r.result());
        TextElement t = firstText(session);
        assertEquals(10, t.x(), "数值占位符 ${off} 应 materialize 回 int 10");
        assertEquals("你好", t.text(), "字符串占位符 ${title} 应替换为 default");
    }

    @Test
    void applyPack_userParamOverridesNumericDefault() throws Exception {
        Session session = freshSession(2, 1);
        byte[] pack = buildPack(PACK_MANIFEST,
                "[{\"id\":\"off\",\"type\":\"int\",\"default\":10}]",
                projectWith("\"${off}\"", "\"hi\""));

        newImporter().applyPack(session, pack, Map.of("off", 25), UUID.randomUUID());

        assertEquals(25, firstText(session).x(), "用户填的 off=25 应覆盖 default 并解析回 int");
    }

    // ---------- 参数替换 + 不 propagate ----------

    @Test
    void applyPack_returnsOkSnapshot_andDoesNotPropagate() throws Exception {
        Session session = freshSession(2, 1);
        byte[] pack = buildPack(PACK_MANIFEST,
                "[{\"id\":\"title\",\"type\":\"string\",\"default\":\"你好\"}]",
                projectWith("0", "\"${title}\""));

        ProjectImporter.ApplyResult r = newImporter().applyPack(session, pack, Map.of(), UUID.randomUUID());

        assertInstanceOf(EditSession.OpResult.OkSnapshot.class, r.result());
        // 替换后的内容落到会话工程
        assertEquals("你好", firstText(session).text());
        // applyPack 不跑 propagate——push/persist 由调用方接，故这里 push 未被调用
        assertFalse(push.snapshotPushed, "applyPack 不得自行广播 snapshot（propagate 交调用方）");
    }

    // ---------- 校验失败路径 ----------

    @Test
    void applyPack_undeclaredPlaceholder_throwsMalformed() {
        Session session = freshSession(2, 1);
        // project.json 引用 ${nope}，但 params.json 没声明 nope → IMPORT_MALFORMED
        CanvasImportException ex = assertThrows(CanvasImportException.class, () -> {
            byte[] pack = buildPack(PACK_MANIFEST,
                    "[{\"id\":\"title\",\"type\":\"string\",\"default\":\"你好\"}]",
                    projectWith("0", "\"${nope}\""));
            newImporter().applyPack(session, pack, Map.of(), UUID.randomUUID());
        });
        assertEquals("IMPORT_MALFORMED", ex.code());
        assertFalse(push.snapshotPushed);
    }

    @Test
    void applyPack_missingRequiredParam_throwsInvalidParam() {
        Session session = freshSession(2, 1);
        CanvasImportException ex = assertThrows(CanvasImportException.class, () -> {
            byte[] pack = buildPack(PACK_MANIFEST,
                    "[{\"id\":\"title\",\"type\":\"string\",\"required\":true}]",
                    projectWith("0", "\"${title}\""));
            newImporter().applyPack(session, pack, Map.of(), UUID.randomUUID());
        });
        assertEquals("IMPORT_INVALID_PARAM", ex.code());
        assertFalse(push.snapshotPushed);
    }

    // ---------- manifest 接受 pack ----------

    @Test
    void packManifest_acceptedByManifestParse() throws Exception {
        CanvasManifest m = CanvasManifest.parse(PACK_MANIFEST.getBytes(), ProjectImporter.CANVAS_SPEC_MAX);
        assertEquals("pack", m.kind());
    }

    // ---------- importInto 对 pack 的宽容路径（default 收敛 + 正常 propagate） ----------

    @Test
    void importInto_packWithDefaults_substitutesAndPropagates() throws Exception {
        Session session = freshSession(2, 1);
        byte[] pack = buildPack(PACK_MANIFEST,
                "[{\"id\":\"off\",\"type\":\"int\",\"default\":7},"
                        + "{\"id\":\"title\",\"type\":\"string\",\"default\":\"默认标题\",\"max_length\":8}]",
                projectWith("\"${off}\"", "\"${title}\""));

        ImportResult r = newImporter().importInto(session, pack, UUID.randomUUID());

        assertTrue(r.warnings().isEmpty());
        // importInto 走完整管线：default 替换 + 正常广播 snapshot
        assertTrue(push.snapshotPushed, "importInto 对 pack 走完整 propagate");
        TextElement t = firstText(session);
        assertEquals(7, t.x());
        assertEquals("默认标题", t.text());
    }

    // ---------- helpers ----------

    private static byte[] buildPack(String manifest, String params, String project) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("manifest.json"));
            z.write(manifest.getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("params.json"));
            z.write(params.getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("project.json"));
            z.write(project.getBytes());
            z.closeEntry();
        }
        return bos.toByteArray();
    }

    /** 捕获 pushSnapshot 调用的 fake（同 ProjectImporterTest）。 */
    private static final class CapturingPush implements OpPushCallback {
        boolean snapshotPushed = false;
        String lastSessionId = null;

        @Override
        public boolean pushSnapshot(String sessionId, ProjectState state) {
            snapshotPushed = true;
            lastSessionId = sessionId;
            return true;
        }

        @Override
        public boolean pushPatch(String sessionId, StatePatch patch) {
            return true;
        }
    }
}
