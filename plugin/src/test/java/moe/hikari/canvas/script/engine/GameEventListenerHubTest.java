package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.script.Action;
import moe.hikari.canvas.script.ScriptRule;
import moe.hikari.canvas.script.ScriptStore;
import moe.hikari.canvas.script.Trigger;
import moe.hikari.canvas.variable.VariableInterpolator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.0-P3-5 + 0.7.1：{@link GameEventListenerHub} 转发体覆盖。
 *
 * <p>测包私有转发体（@EventHandler 壳是一行 getter 转发，Bukkit 事件实例构造重——全仓零
 * ServerMock 实跑先例，不为转发引入）：worldLoad/worldUnload（P3-5）+ 0.7.1 的
 * {@code handlePlayerQuit} / {@code handleRightClickByWallId}。退服 / 右键用真
 * {@link TriggerRouter}（RecordingSubmitter 侧证投递），右键反查走 {@link
 * GameEventListenerHub.WallIdLookup} fake（不构造 ItemFrame）。</p>
 */
class GameEventListenerHubTest {

    private static final Logger LOG = Logger.getLogger("test");

    @Test
    void handleWorldLoad_putsSnapshotAndFiresRebuild() {
        ConcurrentHashMap<String, UUID> map = new ConcurrentHashMap<>();
        AtomicInteger rebuilds = new AtomicInteger();
        GameEventListenerHub hub =
                new GameEventListenerHub(null, map, rebuilds::incrementAndGet, null);

        UUID uid = UUID.randomUUID();
        hub.handleWorldLoad("script_world", uid);

        assertEquals(uid, map.get("script_world"), "世界加载后快照表登记 名字 → UUID");
        assertEquals(1, rebuilds.get(), "WorldLoad 触发一次 onWorldChange（生产 = rebuildAll，"
                + "让后加载世界的 near 规则自动补登记）");

        // 同名世界重载（卸载后再加载换 UUID）→ 覆盖旧值 + 再次补登记
        UUID uid2 = UUID.randomUUID();
        hub.handleWorldLoad("script_world", uid2);
        assertEquals(uid2, map.get("script_world"), "重载世界覆盖为新 UUID");
        assertEquals(2, rebuilds.get());
    }

    @Test
    void handleWorldUnload_removesSnapshotOnly() {
        ConcurrentHashMap<String, UUID> map = new ConcurrentHashMap<>();
        map.put("doomed", UUID.randomUUID());
        AtomicInteger rebuilds = new AtomicInteger();
        GameEventListenerHub hub =
                new GameEventListenerHub(null, map, rebuilds::incrementAndGet, null);

        hub.handleWorldUnload("doomed");
        assertFalse(map.containsKey("doomed"), "卸载后世界从快照表摘除");
        assertEquals(0, rebuilds.get(), "卸载不触发 rebuild（无新原点可补登记）");

        // 未登记过的世界卸载 → no-op 不抛
        hub.handleWorldUnload("never_seen");
        assertNull(map.get("never_seen"));
    }

    @Test
    void handleWorldLoad_nullOnWorldChange_noThrow() {
        ConcurrentHashMap<String, UUID> map = new ConcurrentHashMap<>();
        GameEventListenerHub hub = new GameEventListenerHub(null, map, null, null);
        UUID uid = UUID.randomUUID();
        hub.handleWorldLoad("w", uid);   // 回调可 null（测试装配）——不抛
        assertEquals(uid, map.get("w"));
    }

    // ---------- 0.7.1：退服 / 右键转发体 ----------

    /** 记录投递的 RunSubmitter（侧证 router fire 转发）。 */
    private static final class RecordingSubmitter implements TriggerRouter.RunSubmitter {
        final List<String> wallIds = new ArrayList<>();
        final List<TriggerContext> contexts = new ArrayList<>();

        @Override
        public void submit(String wallId, ScriptRule rule, TriggerContext ctx) {
            wallIds.add(wallId);
            contexts.add(ctx);
        }
    }

    private record Fixture(GameEventListenerHub hub, RecordingSubmitter rec,
                           ScriptStore store, TriggerRouter router) {}

    /** 装真 TriggerRouter（RecordingSubmitter）+ 给定 wallIdLookup 的 Hub。 */
    private static Fixture fixture(GameEventListenerHub.WallIdLookup lookup) {
        ScriptStore store = new ScriptStore(LOG, null, 16);
        RecordingSubmitter rec = new RecordingSubmitter();
        TriggerRouter router = new TriggerRouter(store, rec,
                VariableInterpolator::resolveFullName,
                wallId -> new TriggerRouter.WallOrigin(UUID.randomUUID(), 0, 0, 0),
                LOG, new TriggerRouterTest.FakeTimerScheduler());
        GameEventListenerHub hub = new GameEventListenerHub(
                router, new ConcurrentHashMap<>(), null, lookup);
        return new Fixture(hub, rec, store, router);
    }

    private static ScriptRule add(ScriptStore store, String wallId, Trigger t) {
        return store.create(wallId, new ScriptRule(null, null, true, "r", t,
                List.of(new Action.Log("x")), "{}"));
    }

    @Test
    void handlePlayerQuit_firesQuitRule() {
        Fixture f = fixture(frame -> null);
        add(f.store(), "w-1", new Trigger.PlayerQuit());
        f.router().rebuildAll();
        f.hub().handlePlayerQuit("Steve");
        assertEquals(1, f.rec().contexts.size(), "退服 → quit 规则投递");
        assertEquals("w-1", f.rec().wallIds.get(0));
        assertEquals(TriggerContext.Source.PLAYER_QUIT, f.rec().contexts.get(0).source());
        assertEquals("Steve", f.rec().contexts.get(0).detail());
    }

    @Test
    void handlePlayerQuit_nullRouter_noThrow() {
        GameEventListenerHub hub =
                new GameEventListenerHub(null, new ConcurrentHashMap<>(), null, null);
        hub.handlePlayerQuit("Steve");   // router null → no-op 不抛
        assertTrue(true);
    }

    @Test
    void handleRightClickByWallId_firesMatchingWall() {
        Fixture f = fixture(frame -> "w-1");
        add(f.store(), "w-1", new Trigger.RightClickWall());
        f.router().rebuildAll();
        f.hub().handleRightClickByWallId("w-1", "Steve");
        assertEquals(1, f.rec().contexts.size(), "右键命中墙 → 投递");
        assertEquals(TriggerContext.Source.RIGHT_CLICK_WALL, f.rec().contexts.get(0).source());
        assertEquals("Steve", f.rec().contexts.get(0).detail());
    }

    @Test
    void handleRightClickByWallId_nullWallId_skips() {
        // 非 HikariCanvas 画框反查得 null → 不触发
        Fixture f = fixture(frame -> null);
        add(f.store(), "w-1", new Trigger.RightClickWall());
        f.router().rebuildAll();
        f.hub().handleRightClickByWallId(null, "Steve");
        assertEquals(0, f.rec().contexts.size(), "wallId=null（非本插件画框）→ 不触发");
    }

    // ---------- 回归：FrameProtectionListener cancel × hub ignoreCancelled 交互 ----------
    //
    // 0.7.1 实测 bug「右键画板触发器完全不触发」的根因：
    //   1. FrameProtectionListener.onPlayerInteractEntity（@EventHandler priority=HIGH）对
    //      每个 wall frame 右键 setCancelled(true)，防玩家旋转 / 改画框内容。
    //   2. GameEventListenerHub.onPlayerInteractEntity 原本 @EventHandler(MONITOR,
    //      ignoreCancelled=true) → 已取消事件被 Bukkit 派发层跳过 → rightClickWall 永不触发。
    //   两个 listener 靠同一组 wall_id PDC 的 frame 互锁。
    //
    // 上面那批 handleRightClickByWallId_* / handleRightClick_* 测试直调转发体，绕过真实
    // @EventHandler 派发，抓不到这个 priority/ignoreCancelled 交互——bug 正是从那个缝隙
    // 溜过去的。
    //
    // 本想用 MockBukkit 注册两个真 listener + 派发真实 PlayerInteractEntityEvent 做端到端
    // 回归（MockBukkit.PluginManagerMock.callEvent 委托真 Bukkit RegisteredListener，会按
    // priority 排序 + 尊重每个 handler 的 ignoreCancelled，能如实复现此交互）。但本仓 classpath
    // 的 MockBukkit-v1.21:3.123.0 与 Paper API 版本错配：ServerMock.<init> 里 InternalTag
    // .loadInternalTags 解析自带 tag JSON 时抛 "Invalid namespace key minecraft:chain"，mock()
    // 直接构造失败（全仓零 ServerMock 实跑先例 + ScriptOpPermissionDispatchTest 早已记录此风险）。
    // 故退而求其次用反射钉死 @EventHandler 注解契约——直接守住「不能把 ignoreCancelled 改回
    // true」，改回即重现 bug。

    /**
     * 契约守卫（反射，零 Bukkit 构造成本）：直接钉死
     * {@code GameEventListenerHub.onPlayerInteractEntity} 的
     * {@code @EventHandler.ignoreCancelled() == false}。
     *
     * <p>HikariCanvas 自己的 {@code FrameProtectionListener}（HIGH）必然 cancel 所有 wall
     * frame 右键，脚本 hub 不观察已取消事件就永远收不到 {@code rightClickWall}。这是 0.7.1
     * bug 根因——本测试守住修复不被回退。同时校验它仍是 MONITOR（"观察不修改"语义，读取
     * 已取消事件正当用途）。</p>
     */
    @Test
    void onPlayerInteractEntity_mustObserveCancelledEvents() throws Exception {
        Method m = GameEventListenerHub.class.getMethod(
                "onPlayerInteractEntity", PlayerInteractEntityEvent.class);
        EventHandler ann = m.getAnnotation(EventHandler.class);
        assertNotNull(ann, "onPlayerInteractEntity 必须带 @EventHandler");
        assertFalse(ann.ignoreCancelled(),
                "ignoreCancelled 必须为 false：HikariCanvas 自己的 FrameProtectionListener "
                        + "必然 cancel 所有 wall frame 右键，脚本 hub 不观察已取消事件就永远"
                        + "收不到 rightClickWall（0.7.1 bug 根因）");
        assertEquals(EventPriority.MONITOR, ann.priority(),
                "右键墙是观察语义（不改事件结果），应保持 MONITOR——读取已取消事件正是 MONITOR 的用途");
    }
}
