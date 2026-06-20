package moe.hikari.canvas.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.javalin.router.Endpoint;
import io.javalin.testtools.JavalinTest;
import moe.hikari.canvas.HikariCanvasConfig;
import moe.hikari.canvas.canvasfile.AssetIngest;
import moe.hikari.canvas.canvasfile.ProjectImporter;
import moe.hikari.canvas.image.ImageQuotaService;
import moe.hikari.canvas.image.ImageStorage;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionTestFactory;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.StatePatch;
import moe.hikari.canvas.storage.AuditLog;
import moe.hikari.canvas.storage.Database;
import moe.hikari.canvas.storage.ImageUploadDao;
import moe.hikari.canvas.storage.MigrationRunner;
import moe.hikari.canvas.storage.WallRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8-A2 Task 13：{@link ProjectImportHandler} 端点 e2e（JavalinTest，仿
 * {@code CommandTemplateHandlerTest}）。
 *
 * <p>鉴权 / 权限经注入 seam（无须 MockBukkit）；importer 用真 {@link ProjectImporter}（temp DB
 * 全装配），故覆盖的是<b>端点 ↔ 编排 ↔ 错误码映射</b>的全链，而非 mock。multipart 经
 * {@code java.net.http} 手搓 {@code multipart/form-data} body（Javalin 7 testtools 基于
 * native HttpClient，无 OkHttp）。</p>
 */
class ProjectImportHandlerTest {

    private static final Logger LOG = Logger.getLogger("project-import-handler-test");
    private final ObjectMapper mapper = new ObjectMapper();

    private Path tmpDir;
    private Database database;
    private WallRepo wallRepo;
    private AssetIngest assetIngest;
    private AuditLog auditLog;
    private CapturingPush push;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hc-import-handler-");
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
                        // best-effort
                    }
                });
            }
        }
    }

    private ProjectImporter importer(HikariCanvasConfig.ImportConfig cfg) {
        // throttler 传 null：端点 e2e 只验状态码 / 错误码映射，投影副作用不在覆盖面内。
        // scriptImporter 传 null：本组不带 scripts.json，脚本导入覆盖见 ScriptImporterTest。
        // scanner 传 null：缺资源扫描覆盖见 MissingResourceScannerTest / ProjectImporterTest。
        return new ProjectImporter(cfg, assetIngest, push, wallRepo, null, auditLog, null, null);
    }

    private Session sessionWithProject(int w, int h) {
        return SessionTestFactory.withWallAndProject(
                "sess-1", UUID.randomUUID(), "Steve", "wall-1", new ProjectState(w, h));
    }

    /** 装一个端点 app：注入 sessionLookup + 权限 predicate + importer。 */
    private Javalin buildApp(Function<String, Session> lookup,
                             Predicate<Session> perm,
                             ProjectImporter imp) {
        ProjectImportHandler h = new ProjectImportHandler(lookup, perm, imp);
        return Javalin.create(cfg -> cfg.routes.addEndpoint(
                new Endpoint(HandlerType.POST, "/api/project/import", h::handleImport)));
    }

    // ---------- 鉴权 ----------

    @Test
    void missingSession_returns401() {
        Javalin app = buildApp(sid -> null, s -> true,
                importer(HikariCanvasConfig.ImportConfig.defaults()));
        JavalinTest.test(app, (server, client) -> {
            // 无 sessionId（裸 POST，无 multipart）→ 401
            var resp = client.post("/api/project/import", "");
            assertEquals(401, resp.code());
            assertTrue(resp.body().string().contains("NO_SESSION"));
        });
    }

    @Test
    void unknownSession_returns401() throws Exception {
        Javalin app = buildApp(sid -> null, s -> true,
                importer(HikariCanvasConfig.ImportConfig.defaults()));
        byte[] body = multipart(BOUNDARY, "sess-x", validCanvas(2, 1));
        JavalinTest.test(app, (server, client) -> {
            var resp = client.request("/api/project/import", req -> req
                    .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                    .post(HttpRequest.BodyPublishers.ofByteArray(body)));
            assertEquals(401, resp.code());
        });
    }

    @Test
    void missingPermission_returns403() throws Exception {
        Session session = sessionWithProject(2, 1);
        Javalin app = buildApp(sid -> session, s -> false,   // 权限拒
                importer(HikariCanvasConfig.ImportConfig.defaults()));
        byte[] body = multipart(BOUNDARY, "sess-1", validCanvas(2, 1));
        JavalinTest.test(app, (server, client) -> {
            var resp = client.request("/api/project/import", req -> req
                    .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                    .post(HttpRequest.BodyPublishers.ofByteArray(body)));
            assertEquals(403, resp.code());
            assertTrue(resp.body().string().contains("FORBIDDEN"));
        });
    }

    // ---------- 成功 ----------

    @Test
    void validImport_returns200WithWarnings() throws Exception {
        Session session = sessionWithProject(2, 1);
        Javalin app = buildApp(sid -> session, s -> true,
                importer(HikariCanvasConfig.ImportConfig.defaults()));
        byte[] body = multipart(BOUNDARY, "sess-1", validCanvas(2, 1));
        JavalinTest.test(app, (server, client) -> {
            var resp = client.request("/api/project/import", req -> req
                    .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                    .post(HttpRequest.BodyPublishers.ofByteArray(body)));
            assertEquals(200, resp.code());
            JsonNode root = mapper.readTree(resp.body().string());
            assertTrue(root.get("ok").asBoolean());
            assertTrue(root.get("warnings").isArray());
            assertEquals(0, root.get("warnings").size());
        });
        assertTrue(push.snapshotPushed, "成功导入必须广播 snapshot");
    }

    // ---------- 错误码 → status ----------

    @Test
    void malformedZip_returns400() throws Exception {
        Session session = sessionWithProject(2, 1);
        Javalin app = buildApp(sid -> session, s -> true,
                importer(HikariCanvasConfig.ImportConfig.defaults()));
        byte[] body = multipart(BOUNDARY, "sess-1", new byte[]{0, 1, 2, 3});   // 非 zip
        JavalinTest.test(app, (server, client) -> {
            var resp = client.request("/api/project/import", req -> req
                    .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                    .post(HttpRequest.BodyPublishers.ofByteArray(body)));
            assertEquals(400, resp.code());
            assertTrue(resp.body().string().contains("IMPORT_MALFORMED"));
        });
    }

    @Test
    void oversizedProject_returns409SizeMismatch() throws Exception {
        Session session = sessionWithProject(2, 1);   // 墙 2x1
        Javalin app = buildApp(sid -> session, s -> true,
                importer(HikariCanvasConfig.ImportConfig.defaults()));
        // 工程 9x9 > 墙 2x1 → IMPORT_SIZE_MISMATCH → 409
        byte[] canvas = canvasOf(
                "{\"spec\":1,\"kind\":\"project\",\"created_at\":1,\"wall\":{\"width\":9,\"height\":9}}",
                "{\"version\":3,\"canvas\":{\"widthMaps\":9,\"heightMaps\":9,\"background\":\"#FFFFFF\"},"
                        + "\"layers\":[{\"id\":\"l1\",\"name\":\"L\",\"visible\":true,\"locked\":false,\"opacity\":1.0,"
                        + "\"blendMode\":\"normal\",\"elements\":[]}],\"activeLayerId\":\"l1\"}");
        byte[] body = multipart(BOUNDARY, "sess-1", canvas);
        JavalinTest.test(app, (server, client) -> {
            var resp = client.request("/api/project/import", req -> req
                    .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                    .post(HttpRequest.BodyPublishers.ofByteArray(body)));
            assertEquals(409, resp.code());
            assertTrue(resp.body().string().contains("IMPORT_SIZE_MISMATCH"));
        });
    }

    @Test
    void oversizedZip_returns413() throws Exception {
        Session session = sessionWithProject(2, 1);
        // 1MB 包上限（ImportConfig 单位 MB），喂一个 >1MB 的不可压缩 zip → IMPORT_ZIP_TOO_LARGE → 413
        Javalin app = buildApp(sid -> session, s -> true,
                importer(new HikariCanvasConfig.ImportConfig(1, 1, 1)));
        byte[] bigZip = incompressibleZipOver(1_200_000);
        assertTrue(bigZip.length > 1024L * 1024L, "前置：构造的 zip 须 > 1MB 才能触发闸");
        byte[] body = multipart(BOUNDARY, "sess-1", bigZip);
        JavalinTest.test(app, (server, client) -> {
            var resp = client.request("/api/project/import", req -> req
                    .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                    .post(HttpRequest.BodyPublishers.ofByteArray(body)));
            assertEquals(413, resp.code());
            assertTrue(resp.body().string().contains("IMPORT_ZIP_TOO_LARGE"));
        });
    }

    // ---------- helpers ----------

    private static final String BOUNDARY = "----hcImportBoundary7MA4YWxkTrZu0gW";

    private static byte[] validCanvas(int w, int h) throws IOException {
        return canvasOf(
                "{\"spec\":1,\"kind\":\"project\",\"created_at\":1,\"wall\":{\"width\":" + w + ",\"height\":" + h + "}}",
                "{\"version\":3,\"canvas\":{\"widthMaps\":" + w + ",\"heightMaps\":" + h + ",\"background\":\"#FFFFFF\"},"
                        + "\"layers\":[{\"id\":\"l1\",\"name\":\"L\",\"visible\":true,\"locked\":false,\"opacity\":1.0,"
                        + "\"blendMode\":\"normal\",\"elements\":[]}],\"activeLayerId\":\"l1\"}");
    }

    private static byte[] canvasOf(String manifest, String project) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("manifest.json"));
            z.write(manifest.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("project.json"));
            z.write(project.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return bos.toByteArray();
    }

    /** 造一个解压后 > {@code n} 字节、且因内容随机几乎不可压缩的 zip（用于撑爆 zip 大小闸）。 */
    private static byte[] incompressibleZipOver(int n) throws IOException {
        byte[] rnd = new byte[n];
        new java.util.Random(42).nextBytes(rnd);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            // 仍带 manifest/project 让结构看似合法（闸在读条目前就拦，但保持构造真实）
            z.putNextEntry(new ZipEntry("manifest.json"));
            z.write("{\"spec\":1,\"kind\":\"project\",\"wall\":{\"width\":1,\"height\":1}}"
                    .getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("project.json"));
            z.write("{\"version\":3}".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("assets/deadbeefdeadbeef.png"));
            z.write(rnd);
            z.closeEntry();
        }
        return bos.toByteArray();
    }

    /**
     * 手搓 {@code multipart/form-data}：一个 {@code sessionId} 文本字段 + 一个 {@code file} 文件字段
     * （filename 任意，content-type application/octet-stream）。
     */
    private static byte[] multipart(String boundary, String sessionId, byte[] fileBytes)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String dashBoundary = "--" + boundary + "\r\n";

        // sessionId 文本部分
        out.write(dashBoundary.getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"sessionId\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(sessionId.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));

        // file 文件部分
        out.write(dashBoundary.getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"x.canvas\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(fileBytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));

        // 收尾边界
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** 捕获 pushSnapshot 的 fake。 */
    private static final class CapturingPush implements OpPushCallback {
        volatile boolean snapshotPushed = false;
        final List<ProjectState> snapshots = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public boolean pushSnapshot(String sessionId, ProjectState state) {
            snapshotPushed = true;
            snapshots.add(state);
            return true;
        }

        @Override
        public boolean pushPatch(String sessionId, StatePatch patch) {
            return true;
        }
    }
}
