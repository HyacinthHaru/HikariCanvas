package ac.haru.hikaricanvas.image;

import ac.haru.hikaricanvas.storage.Database;
import ac.haru.hikaricanvas.storage.ImageUploadDao;
import ac.haru.hikaricanvas.storage.MigrationRunner;
import ac.haru.hikaricanvas.storage.WallRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageStorage} 文件层、尺寸解码守卫、DAO 与 LRU 行为测试。
 */
class ImageStorageTest {

    @TempDir
    Path tmpDir;

    private Database database;
    private ImageUploadDao dao;
    private ImageStorage storage;

    @BeforeEach
    void setUp() {
        Path dbFile = tmpDir.resolve("data.db");
        Logger log = Logger.getLogger("test");
        database = new Database(log, dbFile);
        new MigrationRunner(database.jdbi(), log).run();
        dao = new ImageUploadDao(log, database.jdbi());
        storage = new ImageStorage(log, tmpDir, dao);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) storage.shutdown();
        if (database != null) database.close();
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

    private static byte[] encodePng(int w, int h, Color fill) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(fill);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private static byte[] makeMinimalPngWithDimensions(int width, int height) throws IOException {
        byte[] result = encodePng(1, 1, Color.GREEN).clone();
        writeInt32BE(result, 16, width);
        writeInt32BE(result, 20, height);
        return result;
    }

    private static void writeInt32BE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) (value >>> 24);
        buf[offset + 1] = (byte) (value >>> 16);
        buf[offset + 2] = (byte) (value >>> 8);
        buf[offset + 3] = (byte)  value;
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
    void loadNormalSizePngWrittenAtomicallyLoadsSuccessfully() throws Exception {
        byte[] pngBytes = encodePng(8, 8, Color.BLUE);
        String hash = ImageStorage.sha256Hex16(pngBytes);
        storage.writeFileAtomic(hash, pngBytes);

        BufferedImage result = storage.load(hash);
        assertNotNull(result);
        assertEquals(8, result.getWidth());
        assertEquals(8, result.getHeight());
    }

    @Test
    void loadOversizedDeclaredDimensionsReturnsNullWithoutOom() throws Exception {
        byte[] bigDeclaredPng = makeMinimalPngWithDimensions(9000, 9000);
        String fakeHash = "abcdef1234567890";
        Files.createDirectories(storage.uploadsDir());
        Files.write(storage.uploadsDir().resolve(fakeHash + ".png"), bigDeclaredPng);

        BufferedImage result = storage.load(fakeHash);
        assertNull(result, "PNG declaring dimensions > BBOX_MAX_EDGE(8192) must fail-soft to null");
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
        assertNull(storage.load("UPPERCASE0123456"));
    }

    @Test
    void readPngBytesReturnsRawFile() throws Exception {
        BufferedImage img = sampleImage(4, 4, 0xFFFFFF);
        ImageStorage.StoreResult r = storage.putIfAbsent(img, UUID.randomUUID());
        byte[] bytes = storage.readPngBytes(r.hash());
        assertNotNull(bytes);
        assertTrue(bytes.length > 8);
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

    // ---------- LRU evict ----------

    @Test
    void evictLruDeletesOrphanWhenOverCapacity() throws Exception {
        ImageStorage.StoreResult a = storage.putIfAbsent(sampleImage(8, 8, 0xAA0000), UUID.randomUUID());
        Thread.sleep(2);
        storage.putIfAbsent(sampleImage(8, 8, 0x00BB00), UUID.randomUUID());
        Thread.sleep(2);
        storage.putIfAbsent(sampleImage(8, 8, 0x0000CC), UUID.randomUUID());

        long total = dao.sumBytes();
        WallRepo emptyRepo = new WallRepo(Logger.getLogger("test"), database.jdbi());
        int evicted = storage.evictLruUntilUnder(0, total - a.bytes() - 1, emptyRepo);

        assertTrue(evicted >= 1, "should evict at least the oldest orphan");
        assertTrue(dao.findByHash(a.hash()).isEmpty());
    }

    @Test
    void evictLruRespectsReferencedHashes() throws Exception {
        ImageStorage.StoreResult a = storage.putIfAbsent(sampleImage(8, 8, 0xAA0000), UUID.randomUUID());
        ImageStorage.StoreResult b = storage.putIfAbsent(sampleImage(8, 8, 0x00BB00), UUID.randomUUID());

        var candidates = dao.pickLruCandidates(10, Set.of(b.hash()));
        assertEquals(1, candidates.size());
        assertEquals(a.hash(), candidates.get(0).hash());
    }

    @Test
    void evictLruNoopWhenUnderQuota() {
        WallRepo emptyRepo = new WallRepo(Logger.getLogger("test"), database.jdbi());
        int evicted = storage.evictLruUntilUnder(0, 1024 * 1024, emptyRepo);
        assertEquals(0, evicted);
    }

    @Test
    void evictLruNoopWhenMaxIsZero() {
        WallRepo emptyRepo = new WallRepo(Logger.getLogger("test"), database.jdbi());
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
