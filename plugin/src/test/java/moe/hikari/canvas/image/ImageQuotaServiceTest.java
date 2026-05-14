package moe.hikari.canvas.image;

import moe.hikari.canvas.HikariCanvasConfig;
import moe.hikari.canvas.storage.Database;
import moe.hikari.canvas.storage.ImageUploadDao;
import moe.hikari.canvas.storage.MigrationRunner;
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
 * M13-B：{@link ImageQuotaService} 三层配额边界 + bypass 路径。
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
                    uploader, now - 1000L, now - 1000L, 1));
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
                uploader, oldTs, oldTs, 1));
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
                uploader, now, now, 1));
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

    @Test
    void remainingSummaryReturnsConfigLimitsAndUsage() {
        UUID uploader = UUID.randomUUID();
        long now = System.currentTimeMillis();
        dao.insert(new ImageUploadDao.Row(
                "abcdef0123456789", 300_000, 50, 50, "image/png",
                uploader, now, now, 1));
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
