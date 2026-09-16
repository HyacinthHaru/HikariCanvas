package ac.haru.hikaricanvas.web;

import ac.haru.hikaricanvas.session.Principal;
import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionTestFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.19：{@link MainThreadPerms} 的主体分流。
 *
 * <p>控制台主体必须在<b>进入 Bukkit 解析之前</b>就短路掉。这不只是"结果对不对"的问题：
 * 走进去意味着一次 {@code callSyncMethod} 主线程 hop（带 2 秒超时），
 * 而 {@code Bukkit.getPlayer(nil)} 对控制台恒返 null，hop 完还是全拒 ——
 * 既慢又错。所以这里除了断言结果，还断言<b>解析 seam 根本没被调用</b>。</p>
 */
@DisplayName("0.9.19 MainThreadPerms 主体分流")
class MainThreadPermsPrincipalTest {

    private static final UUID PLAYER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** 记录 seam 被调用时收到的 UUID；控制台路径下应保持为空。 */
    private final List<UUID> seamCalls = new CopyOnWriteArrayList<>();

    private void installSeam(boolean grant) {
        MainThreadPerms.testResolver = (uuid, nodes) -> {
            seamCalls.add(uuid);
            boolean[] g = new boolean[nodes.length];
            java.util.Arrays.fill(g, grant);
            return new MainThreadPerms.Resolved(grant, g);
        };
    }

    @AfterEach
    void tearDown() {
        MainThreadPerms.testResolver = null;
        seamCalls.clear();
    }

    // ---------- 控制台 ----------

    @Test
    @DisplayName("控制台 resolve：全授予，且完全不碰解析 seam")
    void consoleShortCircuitsResolve() {
        installSeam(false);   // seam 若被调用会全拒 —— 一旦短路失效，断言立刻红
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
    @DisplayName("default=true 兜底：控制台在首行就命中，不会落到「离线兜底」那条分支")
    void consoleHitsFirstBranchOfDefaultTrueFallback() {
        Session console = SessionTestFactory.console("s-c", "w-1");
        MainThreadPerms.Resolved r = MainThreadPerms.resolve(null, console, "canvas.var.write.own");
        // 两种 nodeIsDefaultTrue 都必须放行：控制台的放行理由是"已授予"，与兜底无关。
        assertTrue(MainThreadPerms.grantedWithDefaultTrueFallback(r, true));
        assertTrue(MainThreadPerms.grantedWithDefaultTrueFallback(r, false));
    }

    // ---------- 玩家 ----------

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
        // 守的是"授权看 principal 不看 UUID"这条纪律：如果有人把短路条件写成
        // uuid.equals(CONSOLE_UUID)，本条会转绿失效（seam 不再被调用 + 结果变成全授予）。
        installSeam(false);
        Session impostor = SessionTestFactory.withPrincipal(
                "s-x", Principal.PLAYER, Session.CONSOLE_UUID, "impostor", "w-1");

        MainThreadPerms.Resolved r = MainThreadPerms.resolve(null, impostor, "canvas.admin");

        assertFalse(r.granted(0), "UUID 撞上 nil 不该带来任何特权");
        assertEquals(List.of(Session.CONSOLE_UUID), seamCalls,
                "必须真的走解析路径，而不是被当成控制台短路掉");
    }

    // ---------- 边界 ----------

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
