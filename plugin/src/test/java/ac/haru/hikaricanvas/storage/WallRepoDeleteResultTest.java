package ac.haru.hikaricanvas.storage;

import ac.haru.hikaricanvas.session.WallKey;
import ac.haru.hikaricanvas.state.ProjectState;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code WallRepo.delete} 必须把"到底删没删掉"告诉调用方。
 *
 * <p>原实现是 void + catch 后只写日志。删 wall 的流程是<b>先</b>把地图释放回 FREE 池、
 * <b>后</b>删 walls 行，所以删行悄悄失败（比如 {@code SQLITE_BUSY} 5 秒没抢到写锁）会留下最坏
 * 的组合：玩家看得到的 walls 行还在，它引用的地图却已经能被下一面墙借走 → 两面墙共用一张
 * 地图互相覆盖像素。调用方至少要能知道这次没删成。</p>
 */
class WallRepoDeleteResultTest {

    private static final Logger LOG = Logger.getLogger(WallRepoDeleteResultTest.class.getName());

    private Path tmpDir;
    private Database database;
    private WallRepo repo;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-wallrepo-delete-");
        database = new Database(LOG, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), LOG).run();
        repo = new WallRepo(LOG, database.jdbi());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) database.close();
        if (tmpDir != null) {
            try (var s = Files.walk(tmpDir)) {
                s.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }

    private String createWall() {
        return repo.createWithMapIds(
                new WallKey("world_a", 0, 64, 0, BlockFace.NORTH),
                new ProjectState(1, 1, "#FFFFFF"),
                List.of(1, 2), 1, 1, UUID.randomUUID(), "tester");
    }

    @Test
    void delete_existingWall_reportsSuccessAndRemovesRow() {
        String wallId = createWall();
        assertTrue(repo.loadById(wallId).isPresent(), "前提：行确实建出来了");

        assertTrue(repo.delete(wallId), "删掉了就要报 true");
        assertTrue(repo.loadById(wallId).isEmpty(), "行确实没了");
    }

    @Test
    void delete_missingWall_reportsFailure() {
        assertFalse(repo.delete("w-nonexistent"),
                "本来就没有这一行 → 不能报成功（调用方据此判断要不要回滚地图释放）");
    }

    @Test
    void delete_afterClose_reportsFailureInsteadOfSwallowing() throws Exception {
        String wallId = createWall();
        database.close();   // 模拟数据库写不进去（连接池已关 / SQLITE_BUSY 超时）

        assertFalse(repo.delete(wallId),
                "写失败必须报 false —— 静默吞掉会让调用方在地图已释放的情况下报删除成功");

        database = null;    // tearDown 不用再关一次
    }
}
