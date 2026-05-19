package moe.hikari.canvas.demos.score;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 玩家进服把当前玩家设为 MVP（demo 用，真实插件会基于击杀 / 助攻统计算）。
 */
public final class DemoScoreListener implements Listener {
    private final DemoScorePlugin plugin;

    public DemoScoreListener(DemoScorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent ev) {
        plugin.setMvp(ev.getPlayer().getName());
    }
}
