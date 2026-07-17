package ac.haru.hikaricanvas.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** 0.9.1：WAL 安全 auto-backup —— 备份在迁移前发生、含已提交（可能仍在 WAL）的数据、是 pre-migration schema。 */
class MigrationRunnerBackupTest {

    private Path tmpDir;
    private Database live;

    @AfterEach
    void tearDown() throws Exception {
        if (live != null) live.close();
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var s = Files.walk(tmpDir)) {
                s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    @Test
    void autoBackup_capturesPreMigrationStateIncludingWalData() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-backup-test-");
        Logger log = Logger.getLogger("test");
        Path dbFile = tmpDir.resolve("data.db");
        live = new Database(log, dbFile);

        // 跑到 V016（wall_scripts 表尚不存在），插入一个 wall（已提交，小数据通常仍在 WAL 未 checkpoint）。
        new MigrationRunner(live.jdbi(), log, false, dbFile).runUpTo(16);
        live.jdbi().useHandle(h -> h.execute(
                "INSERT INTO walls (wall_id, world, origin_x, origin_y, origin_z, facing, "
                        + "width_maps, height_maps, map_ids, project_json, owner_uuid, owner_name, "
                        + "protocol_version, created_at, updated_at) VALUES "
                        + "('w-bak', 'w', 0,0,0,'NORTH', 1,1, '1', '{}', "
                        + "'00000000-0000-0000-0000-000000000001', 'P', 1, 0, 0)"));

        // 开 auto-backup，跑 V017 —— tryBackup 应 checkpoint + 备份 pre-V017 状态。
        new MigrationRunner(live.jdbi(), log, true, dbFile).runUpTo(17);

        Path bak = tmpDir.resolve("data.db.pre-V017.bak");
        assertTrue(Files.exists(bak), "应生成 pre-V017 备份");

        // 打开备份：① 含已提交的 wall（证明 WAL 已被 checkpoint 进主库）② 无 wall_scripts 表（pre-migration）。
        try (Database backup = new Database(log, bak)) {
            int wallCount = backup.jdbi().withHandle(h -> h.createQuery(
                            "SELECT COUNT(*) FROM walls WHERE wall_id='w-bak'")
                    .mapTo(Integer.class).one());
            assertEquals(1, wallCount, "备份须含已提交的 wall（WAL checkpoint 生效）");

            int scriptsTbl = backup.jdbi().withHandle(h -> h.createQuery(
                            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='wall_scripts'")
                    .mapTo(Integer.class).one());
            assertEquals(0, scriptsTbl, "备份应是 pre-V017 状态（无 wall_scripts 表）");
        }

        // live DB 已应用 V017。
        int liveScriptsTbl = live.jdbi().withHandle(h -> h.createQuery(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='wall_scripts'")
                .mapTo(Integer.class).one());
        assertEquals(1, liveScriptsTbl, "live DB 应已建 wall_scripts 表");
    }
}
