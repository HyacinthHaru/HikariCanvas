package ac.haru.hikaricanvas.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** 0.9.1 BackupReaper：启动期清理过期 migration 备份文件。 */
class BackupReaperTest {

    private Path tmpDir;
    private final Logger log = Logger.getLogger("BackupReaperTest");

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hk-reaper-test-");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var s = Files.walk(tmpDir)) {
                s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    @Test
    void reap_deletesOldBackupsAndKeepsNewAndUnrelated() throws Exception {
        long now = System.currentTimeMillis();
        long old = now - 40L * 24 * 60 * 60 * 1000; // 40 天前

        // 旧备份（应被删除）
        Path oldBak    = createFile("data.db.pre-V016.bak",     old);
        Path oldBakWal = createFile("data.db.pre-V016.bak-wal", old);
        // 新备份（应保留）
        Path newBak    = createFile("data.db.pre-V017.bak",     now);
        // 不相关文件（应保留）
        Path dataDb    = createFile("data.db",                  old);
        Path unrelated = createFile("unrelated.txt",            old);

        BackupReaper reaper = new BackupReaper(log);
        int deleted = reaper.reap(tmpDir, "data.db", 30, now);

        assertEquals(2, deleted, "应删除 2 个过期备份");
        assertFalse(Files.exists(oldBak),    "旧 .bak 应被删除");
        assertFalse(Files.exists(oldBakWal), "旧 .bak-wal 应被删除");
        assertTrue(Files.exists(newBak),     "新 .bak 应保留");
        assertTrue(Files.exists(dataDb),     "data.db 不应被动");
        assertTrue(Files.exists(unrelated),  "unrelated.txt 不应被动");
    }

    @Test
    void reap_retentionZero_disablesCleanup() throws Exception {
        long now = System.currentTimeMillis();
        long old = now - 40L * 24 * 60 * 60 * 1000;

        Path oldBak = createFile("data.db.pre-V016.bak", old);

        BackupReaper reaper = new BackupReaper(log);
        int deleted = reaper.reap(tmpDir, "data.db", 0, now);

        assertEquals(0, deleted, "retentionDays=0 应禁用清理");
        assertTrue(Files.exists(oldBak), "0 天保留模式下文件不应被删除");
    }

    @Test
    void reap_retentionNegative_disablesCleanup() throws Exception {
        long now = System.currentTimeMillis();
        long old = now - 40L * 24 * 60 * 60 * 1000;

        Path oldBak = createFile("data.db.pre-V099.bak", old);

        BackupReaper reaper = new BackupReaper(log);
        int deleted = reaper.reap(tmpDir, "data.db", -1, now);

        assertEquals(0, deleted, "retentionDays<0 应禁用清理");
        assertTrue(Files.exists(oldBak), "负保留天数下文件不应被删除");
    }

    @Test
    void reap_exactlyAtCutoff_keepsFile() throws Exception {
        long now = System.currentTimeMillis();
        // 恰好在截止点：30天 * 86400000ms = 正好 cutoff（不含等号，应保留）
        long atCutoff = now - 30L * 24 * 60 * 60 * 1000;

        Path bak = createFile("data.db.pre-V010.bak", atCutoff);

        BackupReaper reaper = new BackupReaper(log);
        int deleted = reaper.reap(tmpDir, "data.db", 30, now);

        // mtime == cutoff（不严格早于），文件应保留
        assertEquals(0, deleted, "恰在截止点的文件不应被删除");
        assertTrue(Files.exists(bak));
    }

    @Test
    void reap_bakshmSuffix_alsoDeleted() throws Exception {
        long now = System.currentTimeMillis();
        long old = now - 35L * 24 * 60 * 60 * 1000;

        Path bakShm = createFile("data.db.pre-V015.bak-shm", old);

        BackupReaper reaper = new BackupReaper(log);
        int deleted = reaper.reap(tmpDir, "data.db", 30, now);

        assertEquals(1, deleted, ".bak-shm 也应被清理");
        assertFalse(Files.exists(bakShm));
    }

    // ---- 辅助 ----

    private Path createFile(String name, long lastModifiedMillis) throws Exception {
        Path f = tmpDir.resolve(name);
        Files.writeString(f, "");
        Files.setLastModifiedTime(f, FileTime.fromMillis(lastModifiedMillis));
        return f;
    }
}
