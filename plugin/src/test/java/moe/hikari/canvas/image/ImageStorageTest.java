package moe.hikari.canvas.image;

import moe.hikari.canvas.storage.Database;
import moe.hikari.canvas.storage.ImageUploadDao;
import moe.hikari.canvas.storage.MigrationRunner;
import moe.hikari.canvas.storage.WallRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13-B：{@link ImageStorage} 文件层 + DAO + LRU 行为校验。
 *
 * <p>不 mock：用临时目录 + SQLite 真跑迁移 + 真磁盘 IO。</p>
 */
class ImageStorageTest {

    private Path tmpDir;
    private Database database;
    private ImageUploadDao dao;
    private ImageStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-image-test-");
        Path dbFile = tmpDir.resolve("data.db");
        Logger log = Logger.getLogger("test");
        database = new Database(log, dbFile);
        new MigrationRunner(database.jdbi(), log).run();
        dao = new ImageUploadDao(log, database.jdbi());
        storage = new ImageStorage(log, tmpDir, dao);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) database.close();
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var s = Files.walk(tmpDir)) {
                s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    private static BufferedImage sampleImage(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(rgb));
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        return img;
    }

    // ---------- putIfAbsent / load ----------

    @Test
    void putIfAbsentWritesNewFile() throws Exception {
        BufferedImage img = sampleImage(8, 8, 0x336699);
        UUID uploader = UUID.randomUUID();

        ImageStorage.StoreResult result = storage.putIfAbsent(img, uploader);
        assertTrue(result.isNew());
        assertEquals(16, result.hash().length());
        assertTrue(result.hash().matches("^[0-9a-f]{16}$"));
        assertEquals(8, result.width());
        assertEquals(8, result.height());

        Path file = storage.uploadsDir().resolve(result.hash() + ".png");
        assertTrue(Files.isRegularFile(file));
        assertTrue(Files.size(file) > 0);

        assertTrue(dao.findByHash(result.hash()).isPresent());
    }

    @Test
    void putIfAbsentDedupesSameContent() throws Exception {
        BufferedImage img = sampleImage(4, 4, 0xABCDEF);
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();

        ImageStorage.StoreResult r1 = storage.putIfAbsent(img, u1);
        ImageStorage.StoreResult r2 = storage.putIfAbsent(img, u2);

        assertTrue(r1.isNew());
        assertFalse(r2.isNew());
        assertEquals(r1.hash(), r2.hash());
        assertEquals(1, dao.listAll().size());
    }

    @Test
    void loadRoundtripsBufferedImage() throws Exception {
        BufferedImage img = sampleImage(16, 16, 0x223344);
        ImageStorage.StoreResult r = storage.putIfAbsent(img, UUID.randomUUID());
        storage.clearMemCacheForTest();
        BufferedImage loaded = storage.load(r.hash());
        assertNotNull(loaded);
        assertEquals(16, loaded.getWidth());
        assertEquals(16, loaded.getHeight());
    }

    @Test
    void loadReturnsNullForMissingHash() {
        assertNull(storage.load("0123456789abcdef"));
    }

    @Test
    void loadReturnsNullForInvalidHashFormat() {
        assertNull(storage.load("not-a-real-hash"));
        assertNull(storage.load(null));
        assertNull(storage.load(""));
        assertNull(storage.load("UPPERCASE0123456"));  // 大写非法
    }

    @Test
    void readPngBytesReturnsRawFile() throws Exception {
        BufferedImage img = sampleImage(4, 4, 0xFFFFFF);
        ImageStorage.StoreResult r = storage.putIfAbsent(img, UUID.randomUUID());
        byte[] bytes = storage.readPngBytes(r.hash());
        assertNotNull(bytes);
        assertTrue(bytes.length > 8);
        // PNG magic
        assertEquals((byte) 0x89, bytes[0]);
        assertEquals((byte) 0x50, bytes[1]);
    }

    @Test
    void deleteHashRemovesFileAndDaoRow() throws Exception {
        BufferedImage img = sampleImage(2, 2, 0x111111);
        ImageStorage.StoreResult r = storage.putIfAbsent(img, UUID.randomUUID());
        Path file = storage.uploadsDir().resolve(r.hash() + ".png");
        assertTrue(Files.exists(file));

        storage.deleteHash(r.hash());
        assertFalse(Files.exists(file));
        assertTrue(dao.findByHash(r.hash()).isEmpty());
    }

    // ---------- LRU evict（v1: sweep walls 模式） ----------

    @Test
    void evictLruDeletesOrphanWhenOverCapacity() throws Exception {
        // 3 张图都不被 walls 引用 → 全是 orphan
        ImageStorage.StoreResult a = storage.putIfAbsent(sampleImage(8, 8, 0xAA0000), UUID.randomUUID());
        Thread.sleep(2);
        ImageStorage.StoreResult b = storage.putIfAbsent(sampleImage(8, 8, 0x00BB00), UUID.randomUUID());
        Thread.sleep(2);
        ImageStorage.StoreResult c = storage.putIfAbsent(sampleImage(8, 8, 0x0000CC), UUID.randomUUID());

        long total = dao.sumBytes();
        // 配额刚好少于现有 → 触发 evict
        WallRepo emptyRepo = new WallRepo(Logger.getLogger("test"), database.jdbi());
        int evicted = storage.evictLruUntilUnder(0, total - a.bytes() - 1, emptyRepo);

        assertTrue(evicted >= 1, "should evict at least the oldest orphan");
        // 最老（a）应被删
        assertTrue(dao.findByHash(a.hash()).isEmpty());
    }

    @Test
    void evictLruRespectsReferencedHashes() throws Exception {
        // 假设 b 被某 wall 引用——我们传入的 wallRepo 是空的，所以走 collectReferencedHashes
        // 直接返回空集合。这个测试只确认 referenced 排除逻辑在 dao.pickLruCandidates 中生效。
        ImageStorage.StoreResult a = storage.putIfAbsent(sampleImage(8, 8, 0xAA0000), UUID.randomUUID());
        ImageStorage.StoreResult b = storage.putIfAbsent(sampleImage(8, 8, 0x00BB00), UUID.randomUUID());

        // 直接调 pickLruCandidates 传 excludeHashes 验证逻辑
        var candidates = dao.pickLruCandidates(10, Set.of(b.hash()));
        assertEquals(1, candidates.size());
        assertEquals(a.hash(), candidates.get(0).hash());
    }

    @Test
    void evictLruNoopWhenUnderQuota() {
        WallRepo emptyRepo = new WallRepo(Logger.getLogger("test"), database.jdbi());
        // dao 是空的；任意 maxTotalBytes > 0 都应无操作
        int evicted = storage.evictLruUntilUnder(0, 1024 * 1024, emptyRepo);
        assertEquals(0, evicted);
    }

    @Test
    void evictLruNoopWhenMaxIsZero() {
        WallRepo emptyRepo = new WallRepo(Logger.getLogger("test"), database.jdbi());
        // maxTotalBytes = 0 → 不限磁盘，直接返回
        int evicted = storage.evictLruUntilUnder(999_999_999L, 0, emptyRepo);
        assertEquals(0, evicted);
    }

    // ---------- sha256[:16] 稳定性 ----------

    @Test
    void sameInputProducesSameHash() {
        byte[] data = new byte[]{1, 2, 3, 4, 5};
        assertEquals(ImageStorage.sha256Hex16(data), ImageStorage.sha256Hex16(data));
    }

    @Test
    void hashFormat16LowerHex() {
        String h = ImageStorage.sha256Hex16(new byte[]{0});
        assertEquals(16, h.length());
        assertTrue(h.matches("^[0-9a-f]{16}$"));
    }
}
