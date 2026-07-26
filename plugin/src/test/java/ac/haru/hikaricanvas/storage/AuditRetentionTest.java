package ac.haru.hikaricanvas.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code audit_log} 保留策略（{@code docs/security.md §8.2} / {@code data-model.md §2.6.4}）。
 *
 * <p>此前 {@link AuditLog} 全文只有一条 INSERT，没有任何 DELETE 也没有 reaper —— 30 多种事件
 * 全往同一张表塞，而它与 walls / 变量共用一个 data.db 文件，无界增长是拖累整库的。
 * 两份契约文档却都写着「默认保留 90 天，定期 DELETE」。</p>
 */
class AuditRetentionTest {

    private static final Logger LOG = Logger.getLogger("audit-retention-test");
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private Path tmpDir;
    private Database database;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hk-audit-retention");
        database = new Database(LOG, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), LOG, false, tmpDir.resolve("data.db")).run();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) database.close();
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var s = Files.walk(tmpDir)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private void insertAt(long ts, String event) {
        database.jdbi().useHandle(h -> h.execute(
                "INSERT INTO audit_log (ts, event, player_uuid, player_name, session_id, ip_hash, details)"
                        + " VALUES (?, ?, NULL, NULL, NULL, NULL, NULL)", ts, event));
    }

    private int countRows() {
        return database.jdbi().withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM audit_log").mapTo(Integer.class).one());
    }

    @Test
    void purge_deletesRowsOlderThanRetention_keepsRecentOnes() {
        long now = System.currentTimeMillis();
        insertAt(now - 100 * DAY_MS, "OLD_A");
        insertAt(now - 91 * DAY_MS, "OLD_B");
        insertAt(now - 89 * DAY_MS, "RECENT_A");
        insertAt(now, "RECENT_B");

        AuditLog audit = new AuditLog(database.jdbi(), LOG, 90);
        assertEquals(2, audit.purgeOlderThan(now), "应删掉 2 条超期记录");
        assertEquals(2, countRows(), "保留期内的记录必须留着");
    }

    @Test
    void purge_zeroRetention_keepsEverything() {
        long now = System.currentTimeMillis();
        insertAt(now - 3650 * DAY_MS, "ANCIENT");
        AuditLog audit = new AuditLog(database.jdbi(), LOG, 0);
        assertEquals(0, audit.purgeOlderThan(now), "0 = 永久保留，一条都不能删");
        assertEquals(1, countRows());
    }

    /** 默认（双参构造器）就是文档承诺的 90 天，不需要额外装配也生效。 */
    @Test
    void defaultRetention_is90Days() {
        assertEquals(90, AuditLog.DEFAULT_RETENTION_DAYS);
        assertEquals(90, new AuditLog(database.jdbi(), LOG).retentionDays());
    }

    /** 第一次写审计就会顺带清一次（相当于启动期清理），不需要独立调度器。 */
    @Test
    void firstRecord_triggersPurge() {
        long now = System.currentTimeMillis();
        insertAt(now - 200 * DAY_MS, "ANCIENT");
        assertEquals(1, countRows());

        AuditLog audit = new AuditLog(database.jdbi(), LOG, 90);
        audit.record("AUTH_OK", null, null, null, null, Map.of());

        assertEquals(1, countRows(), "超期那条应被清掉，只剩刚写的这条");
    }

    /** 清理有 6 小时间隔闸：紧接着的第二次写审计不会再跑一次 DELETE。 */
    @Test
    void subsequentRecords_doNotRepurgeWithinInterval() {
        AuditLog audit = new AuditLog(database.jdbi(), LOG, 90);
        audit.record("AUTH_OK", null, null, null, null, Map.of());

        long now = System.currentTimeMillis();
        insertAt(now - 200 * DAY_MS, "ANCIENT_ADDED_AFTER");
        audit.record("AUTH_OK", null, null, null, null, Map.of());

        assertEquals(0, audit.maybePurge(now + 1000L), "间隔未到，不该再清");
        assertTrue(countRows() >= 3, "间隔闸内新塞的超期记录还应在表里");
        // 把时钟推过 6 小时闸就会清
        assertEquals(1, audit.maybePurge(now + AuditLog.PURGE_INTERVAL_MS + 1000L));
    }
}
