package moe.hikari.canvas.script.engine;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 脚本游戏事件入口（0.7.0-P3 B1 / K15；{@code docs/scripting.md §3}）：把 Bukkit
 * 事件转发给 {@link TriggerRouter} 的全局触发索引，全服脚本的进服 / 击杀事件
 * listener 集中在这一个类。
 *
 * <p><b>类本体只做转发，零逻辑</b>——索引查找 / store.find 最新规则 / enabled 判定 /
 * submit 全在 Router（可单测，见 {@code TriggerRouterTest}）。本类不强求单测：
 * Bukkit event 构造重（PlayerDeathEvent 要真 Player + damage source），而两个
 * handler 各自只有一行转发 + 一个 null 判，无分支逻辑可错。</p>
 *
 * <p><b>MONITOR 优先级 + ignoreCancelled</b>：脚本触发是"观察"语义，不改事件结果；
 * 别的插件取消了进服 / 死亡（Paper 的 PlayerDeathEvent 可取消）就不该触发脚本。
 * PlayerJoinEvent 不可取消，ignoreCancelled 对它是 no-op。</p>
 *
 * <p><b>击杀语义（scripting.md §2.2）</b>：playerKill = 玩家死亡且
 * {@code getKiller() != null}（被另一名玩家击杀）；环境死亡（摔落 / 岩浆等）
 * 不触发。两个 fire 都在主线程同步调，Router 内只做索引遍历 + submit（重活
 * 全在 runner 线程），主线程成本可忽略。</p>
 */
public final class GameEventListenerHub implements Listener {

    private final TriggerRouter router;

    public GameEventListenerHub(TriggerRouter router) {
        this.router = router;
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
}
