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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.17：{@code walls} 表单行损坏时的读取行为（必修 Bug #3 的根修）。
 *
 * <p>{@code mapRow} 只对 {@code owner_uuid} 做单行降级；{@code project_json} 反序列化失败或
 * {@code facing} 非法（{@code BlockFace.valueOf}）会抛 SQLException 冒泡到 {@code loadAll}，
 * 原实现一律 catch 后返回<b>空表</b>。而 5 分钟周期任务把 {@code loadAll} 的结果当 live wall
 * <b>全集</b>喂给 {@code MapPool.detectLeaks} —— 空集合会让全服在用地图被判泄漏、强制 FREE
 * 并落盘，随后被新墙复用 → 旧墙 ItemFrame 显示新墙像素（跨墙串台）。同一空表还让启动期
 * {@code WallRestorer} 恢复 0 面墙且不报错。</p>
 */
class WallRepoBadRowTest {

    private static final Logger LOG = Logger.getLogger(WallRepoBadRowTest.class.getName());

    private Path tmpDir;
    private Database database;
    private WallRepo repo;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-wallrepo-badrow-");
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

    private String createGoodWall(String world, int x) {
        return repo.createWithMapIds(
                new WallKey(world, x, 64, 0, BlockFace.NORTH),
                new ProjectState(1, 1, "#FFFFFF"),
                List.of(1, 2), 1, 1, UUID.randomUUID(), "tester");
    }

    /** 直接改 DB 把某行的 project_json 写坏（模拟外部损坏 / 半写）。 */
    private void corruptProjectJson(String wallId) {
        database.jdbi().useHandle(h -> h
                .createUpdate("UPDATE walls SET project_json = :j WHERE wall_id = :id")
                .bind("j", "{ this is not valid json")
                .bind("id", wallId).execute());
    }

    /** 把 facing 写成非法枚举值（BlockFace.valueOf 会抛）。 */
    private void corruptFacing(String wallId) {
        database.jdbi().useHandle(h -> h
                .createUpdate("UPDATE walls SET facing = :f WHERE wall_id = :id")
                .bind("f", "SIDEWAYS").bind("id", wallId).execute());
    }

    // ---------- loadAll：单行坏数据不再毒化整表 ----------

    @Test
    void loadAll_badProjectJson_skipsOnlyThatRow() {
        String good1 = createGoodWall("world", 10);
        String bad = createGoodWall("world", 20);
        String good2 = createGoodWall("world", 30);
        corruptProjectJson(bad);

        List<WallRepo.Wall> walls = repo.loadAll();

        assertEquals(2, walls.size(), "只跳过坏行，好行必须照常返回");
        Set<String> ids = Set.of(walls.get(0).wallId(), walls.get(1).wallId());
        assertTrue(ids.contains(good1) && ids.contains(good2));
        assertFalse(ids.contains(bad));
    }

    @Test
    void loadAll_badFacing_skipsOnlyThatRow() {
        String good = createGoodWall("world", 10);
        String bad = createGoodWall("world", 20);
        corruptFacing(bad);

        List<WallRepo.Wall> walls = repo.loadAll();

        assertEquals(1, walls.size());
        assertEquals(good, walls.get(0).wallId());
    }

    // ---------- loadAllWallIds：泄漏检测专用，坏行完全不影响 ----------

    /**
     * 泄漏检测只需要 wall_id。只 SELECT 该列就不碰 project_json 反序列化 / BlockFace 解析，
     * 从根上消除「单行坏数据毒化整表」这一整类风险——坏行的 id 照样在集合里，
     * 它的地图不会被误判为泄漏。
     */
    @Test
    void loadAllWallIds_unaffectedByCorruptRows() {
        String good = createGoodWall("world", 10);
        String badJson = createGoodWall("world", 20);
        String badFacing = createGoodWall("world", 30);
        corruptProjectJson(badJson);
        corruptFacing(badFacing);

        Optional<Set<String>> ids = repo.loadAllWallIds();

        assertTrue(ids.isPresent(), "能读到就必须返回 present");
        assertEquals(Set.of(good, badJson, badFacing), ids.get(),
                "坏行的 wall_id 也必须在 live 集合里——否则它的地图会被当泄漏释放");
    }

    @Test
    void loadAllWallIds_emptyTable_returnsPresentEmptySet() {
        Optional<Set<String>> ids = repo.loadAllWallIds();
        assertTrue(ids.isPresent(), "表是空的 ≠ 读失败；必须能区分");
        assertTrue(ids.get().isEmpty());
    }

    /**
     * 读失败必须返回 {@link Optional#empty()} 而不是空集合——调用方据此跳过本轮泄漏检测。
     * 用「关掉数据库后再读」模拟不可用。
     */
    @Test
    void loadAllWallIds_readFailure_returnsEmptyOptionalNotEmptySet() throws Exception {
        createGoodWall("world", 10);
        database.close();

        Optional<Set<String>> ids = repo.loadAllWallIds();

        assertTrue(ids.isEmpty(),
                "读失败必须是 Optional.empty()；返回空集合会让 detectLeaks 释放全服在用地图");
        database = null; // tearDown 不要重复 close
    }
}
