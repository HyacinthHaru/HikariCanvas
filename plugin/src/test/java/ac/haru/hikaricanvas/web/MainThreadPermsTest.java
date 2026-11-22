package ac.haru.hikaricanvas.web;

import ac.haru.hikaricanvas.session.Principal;
import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionTestFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MainThreadPerms} 主体分流与 {@code default=true} 权限节点兜底规则单测。
 */
@DisplayName("MainThreadPerms 主体分流与权限兜底")
class MainThreadPermsTest {

    @Nested
    class PrincipalRouting {

        private static final UUID PLAYER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

        private final List<UUID> seamCalls = new CopyOnWriteArrayList<>();

        private void installSeam(boolean grant) {
            MainThreadPerms.testResolver = (uuid, nodes) -> {
                seamCalls.add(uuid);
                boolean[] g = new boolean[nodes.length];
                Arrays.fill(g, grant);
                return new MainThreadPerms.Resolved(grant, g);
            };
        }

        @AfterEach
        void tearDown() {
            MainThreadPerms.testResolver = null;
            seamCalls.clear();
        }

        @Test
        @DisplayName("控制台 resolve：全授予，且完全不碰解析 seam")
        void consoleShortCircuitsResolve() {
            installSeam(false);
            Session console = SessionTestFactory.console("s-c", "w-1");

            MainThreadPerms.Resolved r = MainThreadPerms.resolve(
                    null, console, "canvas.admin", "canvas.delete.any", "canvas.script.broadcast");

            assertTrue(r.online(), "控制台永远在场");
            for (int i = 0; i < 3; i++) assertTrue(r.granted(i), "控制台不查节点，一律授予");
            assertTrue(seamCalls.isEmpty(), "控制台不该走进 Bukkit 解析路径");
        }

        @Test
        @DisplayName("控制台 hasPermission：任意节点都为 true，且不碰 seam")
        void consoleShortCircuitsHasPermission() {
            installSeam(false);
            Session console = SessionTestFactory.console("s-c", "w-1");

            assertTrue(MainThreadPerms.hasPermission(null, console, "canvas.admin"));
            assertTrue(MainThreadPerms.hasPermission(null, console, "随便一个不存在的节点"));
            assertTrue(seamCalls.isEmpty());
        }

        @Test
        @DisplayName("default=true 兜底：控制台在首行就命中，不会落到离线兜底分支")
        void consoleHitsFirstBranchOfDefaultTrueFallback() {
            Session console = SessionTestFactory.console("s-c", "w-1");
            MainThreadPerms.Resolved r = MainThreadPerms.resolve(null, console, "canvas.var.write.own");
            assertTrue(MainThreadPerms.grantedWithDefaultTrueFallback(r, true));
            assertTrue(MainThreadPerms.grantedWithDefaultTrueFallback(r, false));
        }

        @Test
        @DisplayName("玩家 resolve：照常走解析 seam，并带上自己的 UUID")
        void playerGoesThroughSeam() {
            installSeam(true);
            Session player = SessionTestFactory.withPrincipal(
                    "s-p", Principal.PLAYER, PLAYER_UUID, "tester", "w-1");

            MainThreadPerms.Resolved r = MainThreadPerms.resolve(null, player, "canvas.admin");

            assertTrue(r.granted(0));
            assertEquals(List.of(PLAYER_UUID), seamCalls, "玩家必须走解析路径");
        }

        @Test
        @DisplayName("principal=PLAYER 但 UUID 是 nil → 仍走解析路径，被拒就是被拒")
        void nilUuidPlayerIsStillAPlayer() {
            installSeam(false);
            Session impostor = SessionTestFactory.withPrincipal(
                    "s-x", Principal.PLAYER, Session.CONSOLE_UUID, "impostor", "w-1");

            MainThreadPerms.Resolved r = MainThreadPerms.resolve(null, impostor, "canvas.admin");

            assertFalse(r.granted(0), "UUID 撞上 nil 不该带来任何特权");
            assertEquals(List.of(Session.CONSOLE_UUID), seamCalls,
                    "必须真的走解析路径，而不是被当成控制台短路掉");
        }

        @Test
        @DisplayName("session 为 null → fail-closed 全拒")
        void nullSessionDenied() {
            installSeam(true);
            MainThreadPerms.Resolved r = MainThreadPerms.resolve(null, (Session) null, "a", "b");
            assertFalse(r.online());
            assertFalse(r.granted(0));
            assertFalse(r.granted(1));
            assertFalse(MainThreadPerms.hasPermission(null, (Session) null, "a"));
            assertTrue(seamCalls.isEmpty());
        }

        @Test
        @DisplayName("Resolved.allGranted：长度对齐且 online=true")
        void allGrantedShape() {
            MainThreadPerms.Resolved r = MainThreadPerms.Resolved.allGranted(4);
            assertTrue(r.online());
            for (int i = 0; i < 4; i++) assertTrue(r.granted(i));
            assertEquals(0, MainThreadPerms.Resolved.allGranted(0).granted().length);
        }
    }

    @Nested
    class DefaultTrueFallback {

        private static MainThreadPerms.Resolved resolved(boolean online, boolean granted) {
            return new MainThreadPerms.Resolved(online, new boolean[]{granted});
        }

        @Test
        void onlineWithNode_granted() {
            assertTrue(MainThreadPerms.grantedWithDefaultTrueFallback(resolved(true, true), true));
            assertTrue(MainThreadPerms.grantedWithDefaultTrueFallback(resolved(true, true), false));
        }

        @Test
        void onlineButRevoked_deniedEvenForDefaultTrueNode() {
            assertFalse(MainThreadPerms.grantedWithDefaultTrueFallback(resolved(true, false), true),
                    "在线且被显式收回 default=true 节点必须拒");
        }

        @Test
        void onlineButRevoked_deniedForNonDefaultTrueNode() {
            assertFalse(MainThreadPerms.grantedWithDefaultTrueFallback(resolved(true, false), false));
        }

        @Test
        void offline_defaultTrueNodeStillAllowed() {
            assertTrue(MainThreadPerms.grantedWithDefaultTrueFallback(resolved(false, false), true),
                    "离线 / 解析超时 → default=true 节点放行");
        }

        @Test
        void offline_nonDefaultTrueNodeDenied() {
            assertFalse(MainThreadPerms.grantedWithDefaultTrueFallback(resolved(false, false), false),
                    "提权节点宁可误拒不可误放");
        }
    }
}
