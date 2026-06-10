package moe.hikari.canvas.script.engine;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.Map;
import java.util.UUID;

/**
 * 脚本游戏事件入口（0.7.0-P3 B1 / K15；{@code docs/scripting.md §3}）：把 Bukkit
 * 事件转发给 {@link TriggerRouter} 的全局触发索引，全服脚本的进服 / 击杀事件
 * listener 集中在这一个类。
 *
 * <p><b>类本体只做转发，零逻辑</b>——索引查找 / store.find 最新规则 / enabled 判定 /
 * submit 全在 Router（可单测，见 {@code TriggerRouterTest}）。进服 / 击杀两个
 * handler 不强求单测：Bukkit event 构造重（PlayerDeathEvent 要真 Player + damage
 * source），而 handler 各自只有一行转发 + 一个 null 判，无分支逻辑可错。世界事件
 * 转发逻辑抽成包私有 {@code handleWorldLoad / handleWorldUnload}（单测直调，绕开
 * World 实例构造），见 {@code GameEventListenerHubTest}。</p>
 *
 * <p><b>MONITOR 优先级 + ignoreCancelled</b>：脚本触发是"观察"语义，不改事件结果；
 * 别的插件取消了进服 / 死亡（Paper 的 PlayerDeathEvent 可取消）就不该触发脚本。
 * PlayerJoinEvent / WorldLoadEvent 不可取消，ignoreCancelled 对它们是 no-op；
 * WorldUnloadEvent 可取消——取消的卸载不能把世界从快照表里删掉。</p>
 *
 * <p><b>击杀语义（scripting.md §2.2）</b>：playerKill = 玩家死亡且
 * {@code getKiller() != null}（被另一名玩家击杀）；环境死亡（摔落 / 岩浆等）
 * 不触发。两个 fire 都在主线程同步调，Router 内只做索引遍历 + submit（重活
 * 全在 runner 线程），主线程成本可忽略。</p>
 *
 * <p><b>世界名 → UUID 快照表维护（0.7.0-P3-5）</b>：playerNear 的墙原点解析
 * （originSource）可能在任意线程跑（WS 脚本 op 经 ScriptStore listener →
 * rebuildWall 在 Jetty 线程），不能调 {@code Bukkit.getWorld}（异步读
 * CraftServer.worlds 普通 LinkedHashMap，官方不保证线程安全）。改读装配层注入的
 * {@code ConcurrentHashMap<世界名, UUID>} 快照表：onEnable 用
 * {@code Bukkit.getWorlds()} 全量种子，之后由本类两个世界事件 handler（主线程）
 * 增量维护。WorldLoadEvent 额外回调 {@code onWorldChange}（生产 =
 * {@code TriggerRouter::rebuildAll}，主线程调便宜）——让"世界后加载的 near 规则"
 * 自动补登记，无需重保存规则或重启。</p>
 */
public final class GameEventListenerHub implements Listener {

    private final TriggerRouter router;
    /** 世界名 → UUID 快照表（装配层持有；originSource 异步读，本类主线程写）。 */
    private final Map<String, UUID> worldUuidByName;
    /** 世界加载后的补登记回调（生产 = {@code TriggerRouter::rebuildAll}；可 null）。 */
    private final Runnable onWorldChange;

    public GameEventListenerHub(TriggerRouter router,
                                Map<String, UUID> worldUuidByName,
                                Runnable onWorldChange) {
        this.router = router;
        this.worldUuidByName = worldUuidByName;
        this.onWorldChange = onWorldChange;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        router.firePlayerJoin(event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;   // 环境死亡不算击杀
        router.firePlayerKill(event.getEntity().getName(), killer.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldLoad(WorldLoadEvent event) {
        handleWorldLoad(event.getWorld().getName(), event.getWorld().getUID());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        handleWorldUnload(event.getWorld().getName());
    }

    /** 包私有转发体（单测直调，绕开 World 实例构造）：登记 + 补登记回调。 */
    void handleWorldLoad(String worldName, UUID worldUid) {
        worldUuidByName.put(worldName, worldUid);
        // 后加载世界的 playerNear 规则此前因"世界未加载"被跳过登记——这里全量
        // rebuild 一次自动补上（主线程调，规则量受 per-wall 配额约束，成本可忽略）。
        if (onWorldChange != null) onWorldChange.run();
    }

    /** 包私有转发体（单测直调）：世界卸载只摘表，不触发 rebuild。 */
    void handleWorldUnload(String worldName) {
        worldUuidByName.remove(worldName);
    }
}
