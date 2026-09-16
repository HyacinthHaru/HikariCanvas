package ac.haru.hikaricanvas.session;

import ac.haru.hikaricanvas.storage.WallRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.19：会话主体类型是授权的唯一依据。
 *
 * <p>本类守的是一条纪律，而不是某个函数的输入输出：<b>授权判断只许读
 * {@link Principal}，不许从 {@link Session#playerUuid()} 反推主体。</b></p>
 *
 * <p>为什么值得单开一个测试类：控制台会话带的是 nil UUID（{@link Session#CONSOLE_UUID}），
 * 那是三索引结构里的键。一旦有人图省事把判断写成
 * {@code if (uuid.equals(CONSOLE_UUID))}，代码在<b>今天看起来完全正确</b>——
 * 因为目前只有控制台会用这个 UUID。等哪天出现第二种非玩家主体（计划任务 / 远程 API），
 * 那种写法要么漏掉新主体、要么得再加一个 UUID 常量，于是"UUID 即身份"的错误模型
 * 又被固化一层。{@code uuidIsNotAuthority} 那条用例就是钉死这一点的。</p>
 */
@DisplayName("0.9.19 会话主体类型 = 授权依据")
class PrincipalAuthorityTest {

    private static final UUID PLAYER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OWNER_UUID  = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private static Session player(UUID uuid) {
        return SessionTestFactory.withPrincipal("s-p", Principal.PLAYER, uuid, "tester", "w-1");
    }

    private static Session console() {
        return SessionTestFactory.console("s-c", "w-1");
    }

    // ---------- 主体本身 ----------

    @Test
    @DisplayName("控制台 session 的 principal 是 CONSOLE，玩家的是 PLAYER")
    void principalIsSetCorrectly() {
        assertTrue(console().isConsole());
        assertFalse(player(PLAYER_UUID).isConsole());
        assertTrue(Principal.CONSOLE.isConsole());
        assertFalse(Principal.PLAYER.isConsole());
    }

    @Test
    @DisplayName("既有构造器默认 PLAYER —— 现存调用点语义不变")
    void legacyConstructorDefaultsToPlayer() {
        Session s = SessionTestFactory.withWall("s-legacy", PLAYER_UUID, "tester", "w-1");
        assertFalse(s.isConsole());
    }

    @Test
    @DisplayName("控制台 UUID 是 nil，且不等于任何真实玩家 UUID")
    void consoleUuidIsNil() {
        org.junit.jupiter.api.Assertions.assertEquals(
                new UUID(0L, 0L), Session.CONSOLE_UUID);
        assertFalse(Session.CONSOLE_UUID.equals(PLAYER_UUID));
    }

    // ---------- 纪律守卫 ----------

    @Nested
    @DisplayName("UUID 不是权限依据")
    class UuidIsNotAuthority {

        @Test
        @DisplayName("principal=PLAYER 但 UUID 恰好是 nil → 仍按玩家判，绝不放行")
        void uuidIsNotAuthority() {
            // 这个组合在生产里不会自然出现，但它是"有人把判断写成 uuid.equals(CONSOLE_UUID)"
            // 之后唯一能红的信号。若哪天本条转绿失效，说明授权又退回到看 UUID 了。
            Session fake = SessionTestFactory.withPrincipal(
                    "s-fake", Principal.PLAYER, Session.CONSOLE_UUID, "impostor", "w-1");

            assertFalse(fake.isConsole(), "principal 才是权威，UUID 撞上 nil 不代表是控制台");

            WallRepo.Wall wall = wallOwnedBy(OWNER_UUID);
            assertFalse(SessionManager.canManageWall(fake, wall),
                    "UUID 是 nil 的玩家不是墙的管理者");

            assertFalse(SessionManager.mayOpenLockedWall(
                            Principal.PLAYER, Session.CONSOLE_UUID, OWNER_UUID, false),
                    "UUID 是 nil 的玩家开不了别人锁定的墙");
        }

        @Test
        @DisplayName("反过来：principal=CONSOLE 时，UUID 是什么都放行")
        void consolePrincipalWinsRegardlessOfUuid() {
            assertTrue(SessionManager.mayOpenLockedWall(
                    Principal.CONSOLE, PLAYER_UUID, OWNER_UUID, false));
            assertTrue(SessionManager.mayOpenLockedWall(
                    Principal.CONSOLE, Session.CONSOLE_UUID, OWNER_UUID, false));
        }
    }

    // ---------- 锁定墙的 open 准入 ----------

    @Nested
    @DisplayName("mayOpenLockedWall 真值表")
    class MayOpenLocked {

        @Test
        @DisplayName("控制台 → 放行（无需 bypass 权限）")
        void console() {
            assertTrue(SessionManager.mayOpenLockedWall(
                    Principal.CONSOLE, Session.CONSOLE_UUID, OWNER_UUID, false));
        }

        @Test
        @DisplayName("owner 本人 → 放行")
        void owner() {
            assertTrue(SessionManager.mayOpenLockedWall(
                    Principal.PLAYER, OWNER_UUID, OWNER_UUID, false));
        }

        @Test
        @DisplayName("非 owner + 持 bypass 权限 → 放行")
        void bypassPermission() {
            assertTrue(SessionManager.mayOpenLockedWall(
                    Principal.PLAYER, PLAYER_UUID, OWNER_UUID, true));
        }

        @Test
        @DisplayName("非 owner + 无 bypass → 拒绝")
        void strangerDenied() {
            assertFalse(SessionManager.mayOpenLockedWall(
                    Principal.PLAYER, PLAYER_UUID, OWNER_UUID, false));
        }
    }

    // ---------- 墙管理授权 ----------

    @Nested
    @DisplayName("canManageWall 真值表（lock / unlock / alias 共用此判据）")
    class CanManage {

        @Test
        @DisplayName("控制台 → 能管任何墙")
        void consoleManagesAnyWall() {
            assertTrue(SessionManager.canManageWall(console(), wallOwnedBy(OWNER_UUID)));
            assertTrue(SessionManager.canManageWall(console(), wallOwnedBy(PLAYER_UUID)));
        }

        @Test
        @DisplayName("owner → 能管自己的墙")
        void ownerManagesOwnWall() {
            assertTrue(SessionManager.canManageWall(player(OWNER_UUID), wallOwnedBy(OWNER_UUID)));
        }

        @Test
        @DisplayName("非 owner 玩家 → 不能管")
        void strangerCannotManage() {
            assertFalse(SessionManager.canManageWall(player(PLAYER_UUID), wallOwnedBy(OWNER_UUID)));
        }

        @Test
        @DisplayName("session 或 wall 为 null → 一律拒（fail-closed）")
        void nullsAreDenied() {
            assertFalse(SessionManager.canManageWall(null, wallOwnedBy(OWNER_UUID)));
            assertFalse(SessionManager.canManageWall(console(), null));
            assertFalse(SessionManager.canManageWall(null, null));
        }
    }

    /** 造一条只有 ownerUuid 有意义的 walls 行——本测试只看所有权。 */
    private static WallRepo.Wall wallOwnedBy(UUID owner) {
        return new WallRepo.Wall(
                "w-1",
                new WallKey("world", 0, 0, 0, org.bukkit.block.BlockFace.NORTH),
                new ac.haru.hikaricanvas.state.ProjectState(1, 1),
                java.util.List.of(1),
                1, 1,
                owner, "owner-name",
                null, null, 0L, 0L);
    }
}
