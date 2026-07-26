package ac.haru.hikaricanvas.script.engine;

import ac.haru.hikaricanvas.script.Action;
import ac.haru.hikaricanvas.session.WallKey;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.storage.Database;
import ac.haru.hikaricanvas.storage.MigrationRunner;
import ac.haru.hikaricanvas.storage.UserGlobalVariableDao;
import ac.haru.hikaricanvas.storage.UserVariableDao;
import ac.haru.hikaricanvas.storage.WallRepo;
import ac.haru.hikaricanvas.variable.VarType;
import ac.haru.hikaricanvas.variable.VariableStore;
import org.bukkit.block.BlockFace;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脚本变量族积木的越权守卫（{@code security.md §13.8}）。
 *
 * <p>变量名是玩家在编辑器里自由填的字符串，而脚本的执行身份是"墙"——不校验的话，任何能
 * 编排这面墙脚本的玩家（{@code canvas.script.edit} 默认开）都可以：写字面
 * {@code userglobal/别人的变量} 覆写他人全服变量<b>并落库</b>、写
 * {@code user:<别人的墙>/key} 跨墙改数据、写 {@code system:*} 伪造系统值——
 * 把 {@code variable.*} WS op 那一整套 owner ACL 整个绕过去。</p>
 *
 * <p>装配：真 SQLite + 真 {@link WallRepo}（墙主判定要真读库）+ 真 {@link VariableStore}。</p>
 */
class ActionExecutorVariableAclTest {

    private static final Logger LOG =
            Logger.getLogger(ActionExecutorVariableAclTest.class.getName());
    private static final UUID WALL_OWNER = UUID.randomUUID();
    private static final UUID SOMEONE_ELSE = UUID.randomUUID();

    private Path tmpDir;
    private Database database;
    private WallRepo wallRepo;
    private VariableStore store;
    private ActionExecutor executor;
    private String wallId;
    private String otherWallId;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-script-acl-");
        database = new Database(LOG, tmpDir.resolve("data.db"));
        new MigrationRunner(database.jdbi(), LOG).run();
        wallRepo = new WallRepo(LOG, database.jdbi());
        wallId = wallRepo.createWithMapIds(new WallKey("world", 0, 64, 0, BlockFace.NORTH),
                new ProjectState(1, 1), List.of(0), 1, 1, WALL_OWNER, "Owner");
        otherWallId = wallRepo.createWithMapIds(new WallKey("world", 10, 64, 0, BlockFace.NORTH),
                new ProjectState(1, 1), List.of(1), 1, 1, SOMEONE_ELSE, "Other");

        store = new VariableStore(new NoopDao(), w -> { });
        store.configureUserGlobal(new NoopGlobalDao(), 100, 1000);
        executor = new ActionExecutor(store, null, null, wallRepo, null, LOG);
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

    // ──────────────────────────────────────────────────────────
    //  跨墙
    // ──────────────────────────────────────────────────────────

    @Test
    void setVariable_crossWallUserVariable_denied() {
        store.create("user:" + otherWallId, "score", VarType.NUMBER, "0", "manual");
        store.setValue("user:" + otherWallId + "/score", "1", null);

        TraceStep step = executor.execute(wallId, "b",
                new Action.SetVariable("user:" + otherWallId + "/score", "9999"));

        assertEquals("error", step.result(), step::detail);
        assertEquals("1", value("user:" + otherWallId + "/score"), "别的墙的变量不许被改");
    }

    @Test
    void setVariable_ownWallUserVariable_allowed() {
        store.create("user:" + wallId, "score", VarType.NUMBER, "0", "manual");

        TraceStep step = executor.execute(wallId, "b",
                new Action.SetVariable("user/score", "42"));

        assertEquals("ok", step.result(), step::detail);
        assertEquals("42", value("user:" + wallId + "/score"));
    }

    /** 读侧同款：copyVariable 的来源不许指向别的墙。 */
    @Test
    void copyVariable_crossWallSource_denied() {
        store.create("user:" + otherWallId, "secret", VarType.STRING, null, "manual");
        store.setValue("user:" + otherWallId + "/secret", "机密", null);
        store.create("user:" + wallId, "leak", VarType.STRING, null, "manual");

        TraceStep step = executor.execute(wallId, "b",
                new Action.CopyVariable("user:" + otherWallId + "/secret", "user/leak"));

        assertEquals("error", step.result(), step::detail);
        assertEquals(null, value("user:" + wallId + "/leak"), "跨墙内容不许被抄过来");
    }

    // ──────────────────────────────────────────────────────────
    //  全服变量（userglobal）
    // ──────────────────────────────────────────────────────────

    @Test
    void setVariable_userGlobalOwnedByOtherPlayer_denied() {
        store.createGlobal(SOMEONE_ELSE, "Other", "server_notice", VarType.STRING, null);
        store.setValue("userglobal/server_notice", "原文案", null);

        TraceStep step = executor.execute(wallId, "b",
                new Action.SetVariable("userglobal/server_notice", "被篡改"));

        assertEquals("error", step.result(), step::detail);
        assertEquals("原文案", value("userglobal/server_notice"));
    }

    @Test
    void setVariable_userGlobalOwnedByWallOwner_allowed() {
        store.createGlobal(WALL_OWNER, "Owner", "my_counter", VarType.NUMBER, "0");

        TraceStep step = executor.execute(wallId, "b",
                new Action.SetVariable("userglobal/my_counter", "7"));

        assertEquals("ok", step.result(), step::detail);
        assertEquals("7", value("userglobal/my_counter"));
    }

    /** 墙记录查不到（墙已删 / repo 未接线）→ fail-closed。 */
    @Test
    void setVariable_userGlobal_unknownWall_denied() {
        store.createGlobal(WALL_OWNER, "Owner", "my_counter", VarType.NUMBER, "0");

        TraceStep step = executor.execute("w-deadbeef", "b",
                new Action.SetVariable("userglobal/my_counter", "7"));

        assertEquals("error", step.result(), step::detail);
        assertEquals(null, value("userglobal/my_counter"));
    }

    // ──────────────────────────────────────────────────────────
    //  Provider 自有 namespace
    // ──────────────────────────────────────────────────────────

    @Test
    void setVariable_providerOwnedNamespaces_denied() {
        store.create("system:" + wallId, "wall.alias", VarType.STRING, null, "system");
        store.setValue("system:" + wallId + "/wall.alias", "真别名", null);
        store.create("scoreboard", "kills.Steve", VarType.NUMBER, null, "scoreboard-provider");
        store.setValue("scoreboard/kills.Steve", "3", null);
        store.create("schedule:" + wallId, "next_departure", VarType.STRING, null, "schedule");
        store.setValue("schedule:" + wallId + "/next_departure", "08:00", null);

        assertEquals("error", executor.execute(wallId, "b",
                new Action.SetVariable("system:" + wallId + "/wall.alias", "假别名")).result());
        assertEquals("error", executor.execute(wallId, "b",
                new Action.SetVariable("scoreboard/kills.Steve", "9999")).result());
        assertEquals("error", executor.execute(wallId, "b",
                new Action.SetVariable("schedule/next_departure", "23:59")).result());

        assertEquals("真别名", value("system:" + wallId + "/wall.alias"));
        assertEquals("3", value("scoreboard/kills.Steve"));
        assertEquals("08:00", value("schedule:" + wallId + "/next_departure"));
    }

    /** 插件自己的 namespace 仍放行（插件集成用法，且 setValue 建不出新变量）。 */
    @Test
    void setVariable_pluginNamespace_stillAllowed() {
        store.create("bedwars", "score", VarType.NUMBER, "0", "bedwars");

        TraceStep step = executor.execute(wallId, "b",
                new Action.SetVariable("bedwars/score", "5"));

        assertEquals("ok", step.result(), step::detail);
        assertEquals("5", value("bedwars/score"));
    }

    // ──────────────────────────────────────────────────────────
    //  7 个变量族积木都要拦（不是只拦 setVariable）
    // ──────────────────────────────────────────────────────────

    @Test
    void everyVariableActionIsGuarded() {
        String foreign = "user:" + otherWallId + "/score";
        store.create("user:" + otherWallId, "score", VarType.NUMBER, "0", "manual");
        store.setValue(foreign, "1", null);
        store.create("user:" + wallId, "mine", VarType.STRING, null, "manual");

        List<Action> actions = List.of(
                new Action.SetVariable(foreign, "9"),
                new Action.IncrementVariable(foreign, 1.0),
                new Action.CopyVariable("user/mine", foreign),
                new Action.AppendVariable(foreign, "x"),
                new Action.SetRandomVariable(foreign, 1.0, 6.0),
                new Action.ScaleVariable(foreign, "multiply", 2.0),
                new Action.RoundVariable(foreign, "round"));

        for (Action a : actions) {
            TraceStep step = executor.execute(wallId, "b", a);
            assertEquals("error", step.result(),
                    () -> a.wireType() + " 也必须拦跨墙写: " + step.detail());
            assertTrue(step.detail().contains(otherWallId), step.detail());
        }
        assertEquals("1", value(foreign), "7 个积木一个都没写进去");
    }

    private String value(String fullName) {
        return store.get(fullName).map(v -> v.currentValue()).orElse(null);
    }

    private static final class NoopDao extends UserVariableDao {
        NoopDao() {
            super(LOG, null);
        }

        @Override
        public void upsert(String wallId, String name, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {
        }

        @Override
        public void delete(String wallId, String name) {
        }
    }

    private static final class NoopGlobalDao extends UserGlobalVariableDao {
        NoopGlobalDao() {
            super(LOG, null);
        }

        @Override
        public void upsert(String name, UUID ownerUuid, String ownerName, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {
        }

        @Override
        public void delete(String name) {
        }

        @Override
        public int countByOwner(UUID ownerUuid) {
            return 0;
        }

        @Override
        public int countTotal() {
            return 0;
        }
    }
}
