package ac.haru.hikaricanvas.image;

import ac.haru.hikaricanvas.HikariCanvasConfig;
import ac.haru.hikaricanvas.storage.Database;
import ac.haru.hikaricanvas.storage.ImageUploadDao;
import ac.haru.hikaricanvas.storage.MigrationRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageQuotaService} 三层配额边界 + bypass 路径。
 */
class ImageQuotaServiceTest {

    private Path tmpDir;
    private Database database;
    private ImageUploadDao dao;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-quota-test-");
        Logger log = Logger.getLogger("test");
        database = new Database(log, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), log).run();
        dao = new ImageUploadDao(log, database.jdbi());
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

    private static HikariCanvasConfig.ImageConfig cfg(int perWall, int perDay, int totalMb) {
        return new HikariCanvasConfig.ImageConfig(
                2048, List.of("image/png"), 1024, perWall, perDay, totalMb);
    }

    @Test
    void okWhenAllLimitsZeroMeansUnlimited() {
        var svc = new ImageQuotaService(dao, cfg(0, 0, 0));
        assertInstanceOf(ImageQuotaService.CheckResult.Ok.class,
                svc.check(UUID.randomUUID(), 999, 999_999_999L, false));
    }

    @Test
    void perWallLimitRejects() {
        var svc = new ImageQuotaService(dao, cfg(4, 0, 0));
        ImageQuotaService.CheckResult r = svc.check(UUID.randomUUID(), 4, 1, false);
        ImageQuotaService.CheckResult.Rejected rj =
                assertInstanceOf(ImageQuotaService.CheckResult.Rejected.class, r);
        assertEquals("QUOTA_PER_WALL", rj.code());
    }

    @Test
    void perWallLimitOkWhenUnderQuota() {
        var svc = new ImageQuotaService(dao, cfg(4, 0, 0));
        assertInstanceOf(ImageQuotaService.CheckResult.Ok.class,
                svc.check(UUID.randomUUID(), 3, 1, false));
    }

    @Test
    void perDayLimitRejects() {
        UUID uploader = UUID.randomUUID();
        long now = System.currentTimeMillis();
        // 在 24h 内塞 2 条
        for (int i = 0; i < 2; i++) {
            dao.insert(new ImageUploadDao.Row(
                    String.format("%016d", i), 100, 10, 10, "image/png",
                    uploader, now - 1000L, now - 1000L));
        }
        var svc = new ImageQuotaService(dao, cfg(0, 2, 0));
        ImageQuotaService.CheckResult r = svc.check(uploader, 0, 1, false);
        ImageQuotaService.CheckResult.Rejected rj =
                assertInstanceOf(ImageQuotaService.CheckResult.Rejected.class, r);
        assertEquals("QUOTA_PER_DAY", rj.code());
    }

    @Test
    void perDayLimitIgnoresOldUploads() {
        UUID uploader = UUID.randomUUID();
        long oldTs = System.currentTimeMillis() - 2 * 86_400_000L; // 2 天前
        dao.insert(new ImageUploadDao.Row(
                "ffffffffffffffff", 100, 10, 10, "image/png",
                uploader, oldTs, oldTs));
        var svc = new ImageQuotaService(dao, cfg(0, 1, 0));
        // 24h 内没有上传 → OK
        assertInstanceOf(ImageQuotaService.CheckResult.Ok.class,
                svc.check(uploader, 0, 1, false));
    }

    @Test
    void totalDiskRetrunsNeedsEviction() {
        UUID uploader = UUID.randomUUID();
        long now = System.currentTimeMillis();
        // 塞 500 KB
        dao.insert(new ImageUploadDao.Row(
                "0123456789abcdef", 500_000, 100, 100, "image/png",
                uploader, now, now));
        // 1 MB 限额 + 即将上传 800 KB → 总 = 1.3 MB > 1 MB
        var svc = new ImageQuotaService(dao, cfg(0, 0, 1));
        ImageQuotaService.CheckResult r = svc.check(uploader, 0, 800_000, false);
        ImageQuotaService.CheckResult.NeedsEviction ne =
                assertInstanceOf(ImageQuotaService.CheckResult.NeedsEviction.class, r);
        assertTrue(ne.bytesToFree() > 0);
    }

    @Test
    void bypassSkipsAllChecks() {
        UUID uploader = UUID.randomUUID();
        var svc = new ImageQuotaService(dao, cfg(1, 1, 1));
        // 极小配额 + 即将占用很多 → 正常会拒，但 bypass=true
        assertInstanceOf(ImageQuotaService.CheckResult.Ok.class,
                svc.check(uploader, 999, 999_999_999L, true));
    }

    // ---------- 事务化 tryReserveQuotaOn ----------

    private ImageUploadDao.Row row(String hash, long bytes, UUID uploader, long ts) {
        return new ImageUploadDao.Row(hash, bytes, 10, 10, "image/png", uploader, ts, ts);
    }

    /**
     * 磁盘满 + LRU 也腾不出空间时，事务里已经 DELETE 掉的 victim 必须交给调用方去删文件。
     *
     * <p>jdbi 只在抛异常时回滚——返回一个"拒绝"结果照样 COMMIT。以前 {@code DeniedDiskAfterLru}
     * 不带 evicted 列表，于是 DB 行没了、磁盘文件还在，{@code sumBytes()} 从此低估真实占用，
     * 每拒一次漂移一点，最后把 {@code max-total-storage-mb} 这道闸整个架空。</p>
     */
    @Test
    void deniedDiskAfterLruReportsEvictedVictimsForFileCleanup() {
        UUID uploader = UUID.randomUUID();
        long now = System.currentTimeMillis();
        // 1 MB 上限。aa 是无引用老图（可被 LRU 挑走），bb 被墙引用（挑不走，占死空间）。
        // 新图 900KB：删光 aa 之后仍有 400KB + 900KB = 1.3MB > 1MB，最终只能拒。
        dao.insert(row("00000000000000aa", 600_000, uploader, now - 10_000));
        dao.insert(row("00000000000000bb", 400_000, uploader, now - 5_000));
        var svc = new ImageQuotaService(dao, cfg(0, 0, 1));

        var result = database.jdbi().inTransaction(
                org.jdbi.v3.core.transaction.TransactionIsolationLevel.SERIALIZABLE,
                h -> svc.tryReserveQuotaOn(h, uploader, row("00000000000000cc", 900_000, uploader, now),
                        900_000, false,
                        // bb 被墙引用 → LRU 不能挑；只有 aa 可删，删完 400_000+900_000 仍 >1MB
                        () -> java.util.Set.of("00000000000000bb"), false));

        var denied = assertInstanceOf(ImageQuotaService.DeniedDiskAfterLru.class, result);
        assertEquals(List.of("00000000000000aa"), denied.evictedHashes(),
                "被 evict 的 victim 必须报给调用方，否则文件成物理孤儿");
        assertTrue(dao.findByHash("00000000000000aa").isEmpty(), "victim 的 DB 行确实已被删除");
    }

    /**
     * 引用集要惰性求值：算它得全表扫 walls 并逐行反序列化 project_json。导入一次几百张图时
     * 每张都扫一遍会把 SQLite 写锁按住不放，编辑 op 的落库全被堵住。
     */
    @Test
    void referencedHashesNotScannedWhenNoEvictionNeeded() {
        UUID uploader = UUID.randomUUID();
        long now = System.currentTimeMillis();
        var svc = new ImageQuotaService(dao, cfg(0, 0, 1024));   // 1GB 上限，绰绰有余
        int[] calls = {0};

        var result = database.jdbi().inTransaction(
                org.jdbi.v3.core.transaction.TransactionIsolationLevel.SERIALIZABLE,
                h -> svc.tryReserveQuotaOn(h, uploader, row("00000000000000dd", 1_000, uploader, now),
                        1_000, false,
                        () -> { calls[0]++; return java.util.Set.of(); }, false));

        assertInstanceOf(ImageQuotaService.Reserved.class, result);
        assertEquals(0, calls[0], "空间够用时不该去扫 walls 引用集");
    }

    /** hash 已存在（重复上传 / 导入里重复的图）只 touch，同样不该扫引用集。 */
    @Test
    void referencedHashesNotScannedWhenHashAlreadyExists() {
        UUID uploader = UUID.randomUUID();
        long now = System.currentTimeMillis();
        dao.insert(row("00000000000000ee", 1_000, uploader, now - 1000));
        var svc = new ImageQuotaService(dao, cfg(0, 0, 1));
        int[] calls = {0};

        var result = database.jdbi().inTransaction(
                org.jdbi.v3.core.transaction.TransactionIsolationLevel.SERIALIZABLE,
                h -> svc.tryReserveQuotaOn(h, uploader, row("00000000000000ee", 1_000, uploader, now),
                        1_000, true,
                        () -> { calls[0]++; return java.util.Set.of(); }, false));

        assertInstanceOf(ImageQuotaService.Reserved.class, result);
        assertEquals(0, calls[0], "已存在的 hash 只 touch，不该扫引用集");
    }

    /** 真需要 evict 时引用集必须被求值，且被引用的 hash 不能当 victim。 */
    @Test
    void referencedHashesScannedAndHonouredWhenEvicting() {
        UUID uploader = UUID.randomUUID();
        long now = System.currentTimeMillis();
        dao.insert(row("00000000000000a1", 400_000, uploader, now - 10_000)); // 最老，可删
        dao.insert(row("00000000000000a2", 400_000, uploader, now - 5_000));  // 被引用，不可删
        var svc = new ImageQuotaService(dao, cfg(0, 0, 1));
        int[] calls = {0};

        var result = database.jdbi().inTransaction(
                org.jdbi.v3.core.transaction.TransactionIsolationLevel.SERIALIZABLE,
                h -> svc.tryReserveQuotaOn(h, uploader, row("00000000000000a3", 400_000, uploader, now),
                        400_000, false,
                        () -> { calls[0]++; return java.util.Set.of("00000000000000a2"); }, false));

        var ok = assertInstanceOf(ImageQuotaService.Reserved.class, result);
        assertEquals(1, calls[0], "需要 evict 时必须扫一次引用集");
        assertEquals(List.of("00000000000000a1"), ok.evictedHashes());
        assertTrue(dao.findByHash("00000000000000a2").isPresent(), "被引用的图不能被 LRU 挑走");
    }

    @Test
    void remainingSummaryReturnsConfigLimitsAndUsage() {
        UUID uploader = UUID.randomUUID();
        long now = System.currentTimeMillis();
        dao.insert(new ImageUploadDao.Row(
                "abcdef0123456789", 300_000, 50, 50, "image/png",
                uploader, now, now));
        var svc = new ImageQuotaService(dao, cfg(8, 50, 1024));
        ImageQuotaService.Summary s = svc.remaining(uploader, 2);
        assertEquals(8, s.perWallLimit());
        assertEquals(2, s.perWallUsed());
        assertEquals(50, s.perDayLimit());
        assertEquals(1, s.perDayUsed());
        assertEquals(1024, s.totalDiskMbLimit());
        assertEquals(300_000L, s.totalDiskBytesUsed());
    }
}
