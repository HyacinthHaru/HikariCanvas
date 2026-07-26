package ac.haru.hikaricanvas.canvasfile;

import ac.haru.hikaricanvas.HikariCanvasConfig;
import ac.haru.hikaricanvas.image.ImageQuotaService;
import ac.haru.hikaricanvas.image.ImageStorage;
import ac.haru.hikaricanvas.render.DirtyRegion;
import ac.haru.hikaricanvas.render.FontRegistry;
import ac.haru.hikaricanvas.render.ProjectionThrottler;
import ac.haru.hikaricanvas.script.ScriptRule;
import ac.haru.hikaricanvas.script.ScriptStore;
import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionTestFactory;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.StatePatch;
import ac.haru.hikaricanvas.storage.AuditLog;
import ac.haru.hikaricanvas.storage.Database;
import ac.haru.hikaricanvas.storage.ImageUploadDao;
import ac.haru.hikaricanvas.storage.MigrationRunner;
import ac.haru.hikaricanvas.storage.WallRepo;
import ac.haru.hikaricanvas.web.OpPushCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8-A2 Task 12：{@link ProjectImporter} 编排（解包 → manifest → 物化 → assets →
 * 孤儿轨清理 → replaceProject → pushSnapshot → persist → audit）。
 *
 * <p>装配照真实架构：真 {@link WallRepo}（temp DB）做持久化、真 {@link AssetIngest} 做图片摄入、
 * 捕获式 {@link OpPushCallback} 验证 snapshot 广播、真 {@link AuditLog} 写 {@code PROJECT_IMPORT}。
 * session 经 {@link SessionTestFactory#withWallAndProject} 构造（projectState 与 editSession.state()
 * 共享引用，与 SessionManager confirm 路径一致）。</p>
 */
class ProjectImporterTest {

    private static final Logger LOG = Logger.getLogger("project-importer-test");

    private Path tmpDir;
    private Database database;
    private WallRepo wallRepo;
    private AssetIngest assetIngest;
    private AuditLog auditLog;
    private CapturingPush push;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hc-importer-");
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
        // throttler 传 null：投影是 best-effort 副作用，不影响 replaceProject / snapshot / 持久化主链。
        // scriptImporter 传 null：本组用例不带 scripts.json；脚本导入单测见 ScriptImporterTest，
        // 含 scripts.json 的编排用例见 importInto_withScripts_persistsToScriptStore。
        return new ProjectImporter(HikariCanvasConfig.ImportConfig.defaults(),
                assetIngest, push, wallRepo, null, auditLog, null, null);
    }

    /** 造一个绑定 {@code w}×{@code h} 墙的 session（初始单层空工程）。 */
    private Session freshSession(int w, int h) {
        return SessionTestFactory.withWallAndProject(
                "sess-1", UUID.randomUUID(), "Steve", "wall-1", new ProjectState(w, h));
    }

    // ---------- 正常导入 ----------

    @Test
    void importInto_validCanvas_replacesProjectAndPushesSnapshot() throws Exception {
        Session session = freshSession(2, 1);
        byte[] canvas = buildCanvas(
                "{\"spec\":1,\"kind\":\"project\",\"created_at\":1,\"wall\":{\"width\":2,\"height\":1}}",
                "{\"version\":3,\"canvas\":{\"widthMaps\":2,\"heightMaps\":1,\"background\":\"#FFFFFF\"},"
                        + "\"layers\":[{\"id\":\"l1\",\"name\":\"L\",\"visible\":true,\"locked\":false,\"opacity\":1.0,"
                        + "\"blendMode\":\"normal\",\"elements\":[]}],\"activeLayerId\":\"l1\"}");

        ImportResult r = newImporter().importInto(session, canvas, UUID.randomUUID());

        assertNotNull(r);
        assertTrue(r.warnings().isEmpty(), "干净导入应无 warning");
        assertTrue(push.snapshotPushed, "导入成功必须广播 state.snapshot");
        assertEquals("sess-1", push.lastSessionId);
        assertEquals(1, session.projectState().layers().size());
    }

    @Test
    void importInto_multiLayer_preservesAllLayers() throws Exception {
        Session session = freshSession(2, 1);
        byte[] canvas = buildCanvas(
                "{\"spec\":1,\"kind\":\"project\",\"created_at\":1,\"wall\":{\"width\":2,\"height\":1}}",
                "{\"version\":3,\"canvas\":{\"widthMaps\":2,\"heightMaps\":1,\"background\":\"#FFFFFF\"},"
                        + "\"layers\":["
                        + "{\"id\":\"a\",\"name\":\"A\",\"visible\":true,\"locked\":false,\"opacity\":1.0,\"blendMode\":\"normal\",\"elements\":[]},"
                        + "{\"id\":\"b\",\"name\":\"B\",\"visible\":true,\"locked\":false,\"opacity\":1.0,\"blendMode\":\"normal\",\"elements\":[]},"
                        + "{\"id\":\"c\",\"name\":\"C\",\"visible\":true,\"locked\":false,\"opacity\":1.0,\"blendMode\":\"normal\",\"elements\":[]}"
                        + "],\"activeLayerId\":\"a\"}");

        newImporter().importInto(session, canvas, UUID.randomUUID());

        assertEquals(3, session.projectState().layers().size(), "导入的 3 层必须保留，不被拍平");
    }

    // ---------- 游戏内投影刷新 ----------

    @Test
    void importInto_submitsFullCanvasRedrawToThrottler() throws Exception {
        // 照 EditOpDispatcher OkSnapshot 分支：导入成功后必须 throttler.submit(sid, dirty)
        // 给游戏内地图排队全画布重绘（dirty = DirtyRegion.fullCanvas，非空），否则玩家在游戏里
        // 看不到新内容、要等墙重载。这里用记录型 fake throttler 捕获那一次 submit。
        Session session = freshSession(2, 1);
        RecordingThrottler throttler = new RecordingThrottler();
        ProjectImporter importer = new ProjectImporter(HikariCanvasConfig.ImportConfig.defaults(),
                assetIngest, push, wallRepo, null, auditLog, throttler, null);
        byte[] canvas = buildCanvas(
                "{\"spec\":1,\"kind\":\"project\",\"created_at\":1,\"wall\":{\"width\":2,\"height\":1}}",
                "{\"version\":3,\"canvas\":{\"widthMaps\":2,\"heightMaps\":1,\"background\":\"#FFFFFF\"},"
                        + "\"layers\":[{\"id\":\"l1\",\"name\":\"L\",\"visible\":true,\"locked\":false,\"opacity\":1.0,"
                        + "\"blendMode\":\"normal\",\"elements\":[]}],\"activeLayerId\":\"l1\"}");

        importer.importInto(session, canvas, UUID.randomUUID());

        assertEquals(1, throttler.submitCount, "导入成功必须排队一次游戏内全画布重绘");
        assertEquals("sess-1", throttler.lastSessionId, "submit 的 sessionId 应是被导入会话");
        assertNotNull(throttler.lastDirty, "submit 的 dirty（fullCanvas）必须非空");
    }

    // ---------- 尺寸不匹配 ----------

    @Test
    void importInto_oversizedProject_throwsSizeMismatch() throws Exception {
        Session session = freshSession(2, 1);
        byte[] canvas = buildCanvas(
                "{\"spec\":1,\"kind\":\"project\",\"created_at\":1,\"wall\":{\"width\":9,\"height\":9}}",
                "{\"version\":3,\"canvas\":{\"widthMaps\":9,\"heightMaps\":9,\"background\":\"#FFFFFF\"},"
                        + "\"layers\":[{\"id\":\"l1\",\"name\":\"L\",\"visible\":true,\"locked\":false,\"opacity\":1.0,"
                        + "\"blendMode\":\"normal\",\"elements\":[]}],\"activeLayerId\":\"l1\"}");

        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> newImporter().importInto(session, canvas, UUID.randomUUID()));
        assertEquals("IMPORT_SIZE_MISMATCH", ex.code());
        assertFalse(push.snapshotPushed, "校验失败不得推 snapshot");
    }

    // ---------- spec 不兼容 ----------

    @Test
    void importInto_unsupportedSpec_throwsAndDoesNotPush() throws Exception {
        Session session = freshSession(2, 1);
        byte[] canvas = buildCanvas(
                "{\"spec\":99,\"kind\":\"project\",\"created_at\":1,\"wall\":{\"width\":2,\"height\":1}}",
                "{\"version\":3,\"canvas\":{\"widthMaps\":2,\"heightMaps\":1,\"background\":\"#FFFFFF\"},"
                        + "\"layers\":[{\"id\":\"l1\",\"name\":\"L\",\"visible\":true,\"locked\":false,\"opacity\":1.0,"
                        + "\"blendMode\":\"normal\",\"elements\":[]}],\"activeLayerId\":\"l1\"}");

        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> newImporter().importInto(session, canvas, UUID.randomUUID()));
        assertEquals("IMPORT_SPEC_UNSUPPORTED", ex.code());
        assertFalse(push.snapshotPushed);
    }

    // ---------- 孤儿关键帧轨丢弃 ----------

    @Test
    void importInto_orphanTrack_droppedWithWarning() throws Exception {
        Session session = freshSession(2, 1);
        // timeline 的 track key="ghost" 指向不存在的元素 → 应被丢弃 + warn；
        // track key="e1" 指向真实元素 → 应保留。
        String project = "{\"version\":3,\"canvas\":{\"widthMaps\":2,\"heightMaps\":1,\"background\":\"#FFFFFF\"},"
                + "\"layers\":[{\"id\":\"l1\",\"name\":\"L\",\"visible\":true,\"locked\":false,\"opacity\":1.0,"
                + "\"blendMode\":\"normal\",\"elements\":[{\"id\":\"e1\",\"type\":\"rect\",\"x\":0,\"y\":0,\"w\":8,\"h\":8,"
                + "\"rotation\":0,\"opacity\":1.0,\"fill\":\"#FF0000\"}]}],\"activeLayerId\":\"l1\","
                + "\"timelines\":[{\"id\":\"t1\",\"name\":\"T\",\"durationMs\":1000,\"fps\":20,\"loopMode\":\"loop\",\"tracks\":{"
                + "\"e1\":[{\"id\":\"k1\",\"timeMs\":0,\"property\":\"opacity\",\"value\":1.0}],"
                + "\"ghost\":[{\"id\":\"k2\",\"timeMs\":0,\"property\":\"opacity\",\"value\":0.5}]"
                + "}}],\"activeTimelineId\":\"t1\"}";
        byte[] canvas = buildCanvas(
                "{\"spec\":1,\"kind\":\"project\",\"created_at\":1,\"wall\":{\"width\":2,\"height\":1}}",
                project);

        ImportResult r = newImporter().importInto(session, canvas, UUID.randomUUID());

        assertTrue(push.snapshotPushed);
        List<ImportWarning> orphan = r.warnings().stream()
                .filter(w -> "orphan-track-dropped".equals(w.kind())).toList();
        assertEquals(1, orphan.size(), "应正好一条孤儿轨 warning");
        assertEquals("ghost", orphan.get(0).detail());
        // 真实墙 state 里 ghost 轨已被剔除，e1 轨保留
        var tracks = session.projectState().timelines().get(0).tracks();
        assertTrue(tracks.containsKey("e1"));
        assertFalse(tracks.containsKey("ghost"), "孤儿轨必须从导入工程中清掉");
    }

    // ---------- 缺资源扫描（0.8 Part A review：missing-font / missing-icon / missing-variable） ----------

    @Test
    void importInto_referencesMissingFont_warnsMissingFont() throws Exception {
        // 装真 FontRegistry（loadBuiltIn 拿到内置 id 全集），导入引用一个本服没有的字体 →
        // warnings 含 missing-font（端到端验 scanner 已接入 importInto 编排）。
        Session session = freshSession(2, 1);
        FontRegistry fonts = new FontRegistry(LOG);
        fonts.loadBuiltIn();
        MissingResourceScanner scanner = new MissingResourceScanner(fonts, null, null);
        ProjectImporter importer = new ProjectImporter(HikariCanvasConfig.ImportConfig.defaults(),
                assetIngest, push, wallRepo, null, auditLog, null, scanner);

        // 一个引用 fontId="totally_missing_font" 的 text 元素。
        String project = "{\"version\":3,\"canvas\":{\"widthMaps\":2,\"heightMaps\":1,\"background\":\"#FFFFFF\"},"
                + "\"layers\":[{\"id\":\"l1\",\"name\":\"L\",\"visible\":true,\"locked\":false,\"opacity\":1.0,"
                + "\"blendMode\":\"normal\",\"elements\":[{\"id\":\"e1\",\"type\":\"text\",\"x\":0,\"y\":0,\"w\":80,\"h\":40,"
                + "\"rotation\":0,\"text\":\"hi\",\"fontId\":\"totally_missing_font\",\"fontSize\":24,"
                + "\"color\":\"#000000\",\"align\":\"left\",\"letterSpacing\":0.0,\"lineHeight\":1.2,\"vertical\":false}]}],"
                + "\"activeLayerId\":\"l1\"}";
        byte[] canvas = buildCanvas(
                "{\"spec\":1,\"kind\":\"project\",\"created_at\":1,\"wall\":{\"width\":2,\"height\":1}}",
                project);

        ImportResult r = importer.importInto(session, canvas, UUID.randomUUID());

        assertTrue(push.snapshotPushed, "工程本体照常导入");
        List<ImportWarning> missing = r.warnings().stream()
                .filter(w -> "missing-font".equals(w.kind())).toList();
        assertEquals(1, missing.size(), "应正好一条 missing-font warning");
        assertEquals("totally_missing_font", missing.get(0).detail());
    }

    // ---------- 持久化 ----------

    @Test
    void importInto_persistsToWallRepo() throws Exception {
        // 先建一行 walls（wall-1），导入后用 loadById 验 project_json 已更新为 3 层。
        seedWall("wall-1", 2, 1);
        Session session = freshSession(2, 1);
        byte[] canvas = buildCanvas(
                "{\"spec\":1,\"kind\":\"project\",\"created_at\":1,\"wall\":{\"width\":2,\"height\":1}}",
                "{\"version\":3,\"canvas\":{\"widthMaps\":2,\"heightMaps\":1,\"background\":\"#FFFFFF\"},"
                        + "\"layers\":["
                        + "{\"id\":\"a\",\"name\":\"A\",\"visible\":true,\"locked\":false,\"opacity\":1.0,\"blendMode\":\"normal\",\"elements\":[]},"
                        + "{\"id\":\"b\",\"name\":\"B\",\"visible\":true,\"locked\":false,\"opacity\":1.0,\"blendMode\":\"normal\",\"elements\":[]}"
                        + "],\"activeLayerId\":\"a\"}");

        newImporter().importInto(session, canvas, UUID.randomUUID());

        WallRepo.Wall reloaded = wallRepo.loadById("wall-1").orElseThrow();
        assertEquals(2, reloaded.state().layers().size(), "导入工程必须落库（project_json 更新）");
    }

    // ---------- scripts.json 编排（0.8-A4 Task 18） ----------

    @Test
    void importInto_withScripts_persistsToScriptStoreWithReboundWallId() throws Exception {
        Session session = freshSession(2, 1);   // wallId = "wall-1"
        ScriptStore scriptStore = new ScriptStore(LOG, null, 16);
        ScriptImporter scriptImporter = new ScriptImporter(scriptStore, java.util.Map::of);
        ProjectImporter importer = new ProjectImporter(HikariCanvasConfig.ImportConfig.defaults(),
                assetIngest, push, wallRepo, scriptImporter, auditLog, null, null);

        // 规则 wallId="w-old" 应被重绑到目标墙 "wall-1"；空动作会被 validate 拒，故带一个 log。
        String scriptsJson = "[{\"id\":\"sr-old\",\"wallId\":\"w-old\",\"enabled\":true,\"name\":\"r\","
                + "\"trigger\":{\"type\":\"wallReady\"},"
                + "\"actions\":[{\"type\":\"log\",\"message\":\"hi\"}],\"blockLayout\":\"{}\"}]";
        byte[] canvas = buildCanvasWithScripts(
                "{\"spec\":1,\"kind\":\"project\",\"created_at\":1,\"wall\":{\"width\":2,\"height\":1}}",
                "{\"version\":3,\"canvas\":{\"widthMaps\":2,\"heightMaps\":1,\"background\":\"#FFFFFF\"},"
                        + "\"layers\":[{\"id\":\"l1\",\"name\":\"L\",\"visible\":true,\"locked\":false,\"opacity\":1.0,"
                        + "\"blendMode\":\"normal\",\"elements\":[]}],\"activeLayerId\":\"l1\"}",
                scriptsJson);

        ImportResult r = importer.importInto(session, canvas, UUID.randomUUID());

        assertTrue(r.warnings().isEmpty(), "干净脚本不应产生 warning");
        List<ScriptRule> stored = scriptStore.listByWall("wall-1");
        assertEquals(1, stored.size(), "脚本必须落到目标墙的 ScriptStore");
        assertEquals("wall-1", stored.get(0).wallId(), "wallId 已重绑到目标墙");
        assertTrue(push.snapshotPushed, "工程本体照常广播 snapshot");
    }

    // ---------- helpers ----------

    private void seedWall(String wallId, int w, int h) {
        // 直接 INSERT 一行 walls（避开 WallKey/MapPool 全装配），让 updateState 的 UPDATE 命中。
        long now = System.currentTimeMillis();
        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(new ProjectState(w, h));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        database.jdbi().useHandle(handle -> handle.createUpdate(
                "INSERT INTO walls(wall_id, world, origin_x, origin_y, origin_z, facing, "
                        + "width_maps, height_maps, map_ids, project_json, owner_uuid, owner_name, "
                        + "alias, published_at, template_id, template_version, created_at, updated_at) "
                        + "VALUES(:id,'world',0,0,0,'NORTH',:w,:h,'',:json,:owner,'Steve',"
                        + "NULL,NULL,NULL,NULL,:now,:now)")
                .bind("id", wallId)
                .bind("w", w)
                .bind("h", h)
                .bind("json", json)
                .bind("owner", UUID.randomUUID().toString())
                .bind("now", now)
                .execute());
    }

    // ---------- 图片引用：重编码漂移修正 ----------

    /**
     * 摄入会解码 + 重新编码再算 hash，得出的指纹常与导出方写在文件名里的那个不同
     * （第三方工具产出的包、不同 JDK）。元素引用的是文件名那个，不改写的话图片全指向不存在的
     * 文件——渲染出来是占位，而且一句提示都没有。
     */
    @Test
    void importInto_recodedAssetHash_rewritesElementSourceAndWarns() throws Exception {
        Session session = freshSession(2, 1);
        String declared = "deadbeefdeadbeef";
        String project = "{\"version\":3,\"canvas\":{\"widthMaps\":2,\"heightMaps\":1,\"background\":\"#FFFFFF\"},"
                + "\"layers\":[{\"id\":\"l1\",\"name\":\"L\",\"visible\":true,\"locked\":false,\"opacity\":1.0,"
                + "\"blendMode\":\"normal\",\"elements\":["
                + "{\"type\":\"image\",\"id\":\"e1\",\"x\":0,\"y\":0,\"w\":8,\"h\":8,"
                + "\"source\":\"" + declared + "\"}]}],\"activeLayerId\":\"l1\"}";

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("manifest.json"));
            z.write("{\"spec\":1,\"kind\":\"project\",\"created_at\":1,\"wall\":{\"width\":2,\"height\":1}}".getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("project.json"));
            z.write(project.getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("assets/" + declared + ".png"));
            z.write(png(8, 8));
            z.closeEntry();
        }

        ImportResult r = newImporter().importInto(session, bos.toByteArray(), UUID.randomUUID());

        var el = session.projectState().layers().get(0).elements().get(0);
        String source = ((ac.haru.hikaricanvas.state.ImageElement) el).source();
        assertFalse(declared.equals(source),
                "声明 hash 与实际落盘 hash 不同时，元素引用必须被改写");
        assertTrue(r.warnings().stream().anyMatch(w -> "asset-rehashed".equals(w.kind())),
                "改写要给用户一条提示: " + r.warnings());
    }

    // ---------- 结构数量闸（导入侧 replaceProject 零计数校验的补集） ----------

    @Test
    void importInto_tooManyDistinctImages_rejected() throws Exception {
        Session session = freshSession(2, 1);
        StringBuilder els = new StringBuilder();
        // ImageConfig.defaults() 的 max-per-wall = 16，这里放 20 个不同 source
        for (int i = 0; i < 20; i++) {
            if (i > 0) els.append(',');
            els.append("{\"type\":\"image\",\"id\":\"e").append(i)
               .append("\",\"x\":0,\"y\":0,\"w\":8,\"h\":8,\"source\":\"")
               .append(String.format("%016x", i)).append("\"}");
        }
        byte[] canvas = buildCanvas(
                "{\"spec\":1,\"kind\":\"project\",\"created_at\":1,\"wall\":{\"width\":2,\"height\":1}}",
                "{\"version\":3,\"canvas\":{\"widthMaps\":2,\"heightMaps\":1,\"background\":\"#FFFFFF\"},"
                        + "\"layers\":[{\"id\":\"l1\",\"name\":\"L\",\"visible\":true,\"locked\":false,"
                        + "\"opacity\":1.0,\"blendMode\":\"normal\",\"elements\":[" + els + "]}],"
                        + "\"activeLayerId\":\"l1\"}");

        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> newImporter().importInto(session, canvas, UUID.randomUUID()));
        assertEquals("IMPORT_MALFORMED", ex.code());
        assertTrue(ex.getMessage().contains("引用图片数"), ex.getMessage());
        assertFalse(push.snapshotPushed, "被闸拒的导入不该动会话工程");
    }

    private static byte[] buildCanvas(String manifest, String project) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("manifest.json"));
            z.write(manifest.getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("project.json"));
            z.write(project.getBytes());
            z.closeEntry();
        }
        return bos.toByteArray();
    }

    private static byte[] buildCanvasWithScripts(String manifest, String project, String scripts)
            throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("manifest.json"));
            z.write(manifest.getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("project.json"));
            z.write(project.getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("scripts.json"));
            z.write(scripts.getBytes());
            z.closeEntry();
        }
        return bos.toByteArray();
    }

    @SuppressWarnings("unused")
    private static byte[] png(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    /**
     * 记录型 fake：捕获 {@link ProjectionThrottler#submit} 调用，验证导入成功后排队了游戏内
     * 全画布重绘。重写 submit 不调 super——真 submit 的 flush 路径要 Bukkit scheduler /
     * SessionManager（生产装配），本测只关心「submit 被调一次且 dirty 非空」这一编排契约。
     * 构造期传 null 三件套（与 {@code ProjectionThrottlerTest} 同范式，不触发任何 Bukkit 路径）。
     */
    private static final class RecordingThrottler extends ProjectionThrottler {
        int submitCount = 0;
        String lastSessionId = null;
        DirtyRegion lastDirty = null;

        RecordingThrottler() {
            super(null, null, null, ProjectionThrottler.DEFAULT_MIN_INTERVAL_MS);
        }

        @Override
        public void submit(String sessionId, DirtyRegion region) {
            submitCount++;
            lastSessionId = sessionId;
            lastDirty = region;
        }
    }

    /** 捕获 pushSnapshot 调用的 fake，验证导入成功后广播了全量快照。 */
    private static final class CapturingPush implements OpPushCallback {
        boolean snapshotPushed = false;
        String lastSessionId = null;
        final List<ProjectState> snapshots = new ArrayList<>();

        @Override
        public boolean pushSnapshot(String sessionId, ProjectState state) {
            snapshotPushed = true;
            lastSessionId = sessionId;
            snapshots.add(state);
            return true;
        }

        @Override
        public boolean pushPatch(String sessionId, StatePatch patch) {
            return true;
        }
    }
}
