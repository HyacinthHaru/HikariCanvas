package moe.hikari.canvas.canvasfile;

import moe.hikari.canvas.HikariCanvasConfig;
import moe.hikari.canvas.image.ImageQuotaService;
import moe.hikari.canvas.image.ImageStorage;
import moe.hikari.canvas.storage.Database;
import moe.hikari.canvas.storage.ImageUploadDao;
import moe.hikari.canvas.storage.MigrationRunner;
import moe.hikari.canvas.storage.WallRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8-A2 Task 11：{@link AssetIngest} 图片摄入校验。
 *
 * <p>不 mock：临时目录 + SQLite 真跑迁移 + 真磁盘 IO（仿 {@code ImageStorageTest} 装配）。
 * 每张 {@code assets/<hash>.png} 走 magic 校验 + ImageIO 隔离解码 + 配额事务 + 按内容重算 hash 落盘。</p>
 */
class AssetIngestTest {

    private Path tmpDir;
    private Database database;
    private ImageStorage storage;
    private AssetIngest ingest;
    private final UUID uploader = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hc-asset-ingest-");
        Path dbFile = tmpDir.resolve("data.db");
        Logger log = Logger.getLogger("test");
        database = new Database(log, dbFile);
        new MigrationRunner(database.jdbi(), log).run();
        ImageUploadDao dao = new ImageUploadDao(log, database.jdbi());
        storage = new ImageStorage(log, tmpDir, dao);
        WallRepo wallRepo = new WallRepo(log, database.jdbi());
        ImageQuotaService quota = new ImageQuotaService(dao, HikariCanvasConfig.ImageConfig.defaults());
        ingest = new AssetIngest(log, storage, quota, dao, wallRepo, database.jdbi());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) database.close();
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var s = Files.walk(tmpDir)) {
                s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    @Test
    void ingest_validPng_storesAndReturnsHash() throws Exception {
        Map<String, byte[]> assets = Map.of("assets/deadbeefdeadbeef.png", png(8, 8));
        Set<String> stored = ingest.ingestAll(assets, uploader);
        assertEquals(1, stored.size(), "应落盘 1 张并返回其按内容重算的 hash");
        // 落盘 hash 按内容重算，不信文件名里的 hash
        String hash = stored.iterator().next();
        assertFalse(hash.equals("deadbeefdeadbeef"), "返回的应是按内容重算的 hash，不是文件名");
        assertTrue(storage.readPngBytes(hash) != null, "对应文件应已落盘可读回");
    }

    @Test
    void ingest_nonPngBytes_skippedNotStored() throws Exception {
        Map<String, byte[]> assets = Map.of("assets/x.png", new byte[]{1, 2, 3, 4});
        assertTrue(ingest.ingestAll(assets, uploader).isEmpty(),
                "magic 不符 → 跳过该张，不抛异常");
    }

    @Test
    void ingest_corruptPngHeaderButValidMagic_skipped() throws Exception {
        // 伪造合法 PNG magic 但后续字节是垃圾 → 解码必失败 → 跳过
        byte[] fake = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0, 0, 0, 0, 0};
        Map<String, byte[]> assets = Map.of("assets/bad.png", fake);
        assertTrue(ingest.ingestAll(assets, uploader).isEmpty(),
                "magic 像 PNG 但解码失败 → 跳过");
    }

    @Test
    void ingest_mixedBatch_onlyValidStored_deduped() throws Exception {
        byte[] valid = png(4, 4);
        // 用 LinkedHashMap 保序；两个不同 key 指向同一图内容 → 内容寻址去重为 1
        Map<String, byte[]> assets = new LinkedHashMap<>();
        assets.put("assets/a.png", valid);
        assets.put("assets/b.png", valid.clone());
        assets.put("assets/junk.png", new byte[]{9, 9, 9, 9});
        Set<String> stored = ingest.ingestAll(assets, uploader);
        assertEquals(1, stored.size(), "两张相同内容去重 + 1 张垃圾跳过 → 落盘 1 个 hash");
    }

    @Test
    void ingest_nonAssetKeys_ignored() throws Exception {
        Map<String, byte[]> assets = new LinkedHashMap<>();
        assets.put("project.json", "{}".getBytes());          // 非 assets/ → 跳过
        assets.put("assets/icons/x.svg", "<svg/>".getBytes()); // 非 .png → 跳过
        assets.put("assets/ok.png", png(2, 2));
        Set<String> stored = ingest.ingestAll(assets, uploader);
        assertEquals(1, stored.size(), "只处理 assets/*.png，其余忽略");
    }

    @Test
    void shutdown_repeatedCalls_idempotentNoThrow() {
        // R3-assetingest-leak：onDisable 关停 decoderPool；cleanupResources 与 onEnable-fail
        // catch 共用，须可多次安全调用（shutdownNow 幂等）。
        ingest.shutdown();
        ingest.shutdown();
        ingest.shutdown();
    }
}
