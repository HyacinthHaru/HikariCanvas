package moe.hikari.canvas.script.engine;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 0.7.0-P3-5：{@link GameEventListenerHub} 世界事件转发体覆盖。
 *
 * <p>只测包私有 {@code handleWorldLoad / handleWorldUnload}（@EventHandler 壳是
 * 一行 getter 转发，Bukkit World 实例构造重——全仓零 ServerMock 实跑先例，不为
 * 两行转发引入）。进服 / 击杀 handler 同理不在本测范围（见 Hub javadoc）。
 * router 传 null：世界转发体不碰 router，进服 / 击杀路径本测不触发。</p>
 */
class GameEventListenerHubTest {

    @Test
    void handleWorldLoad_putsSnapshotAndFiresRebuild() {
        ConcurrentHashMap<String, UUID> map = new ConcurrentHashMap<>();
        AtomicInteger rebuilds = new AtomicInteger();
        GameEventListenerHub hub = new GameEventListenerHub(null, map, rebuilds::incrementAndGet);

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
        GameEventListenerHub hub = new GameEventListenerHub(null, map, rebuilds::incrementAndGet);

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
        GameEventListenerHub hub = new GameEventListenerHub(null, map, null);
        UUID uid = UUID.randomUUID();
        hub.handleWorldLoad("w", uid);   // 回调可 null（测试装配）——不抛
        assertEquals(uid, map.get("w"));
    }
}
