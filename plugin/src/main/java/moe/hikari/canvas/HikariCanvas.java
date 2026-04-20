package moe.hikari.canvas;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import moe.hikari.canvas.command.HcCommand;
import moe.hikari.canvas.deploy.MapPacketSender;
import moe.hikari.canvas.web.WebServer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;

@SuppressWarnings("UnstableApiUsage") // Paper Lifecycle API 标记为 experimental 但稳定可用
public final class HikariCanvas extends JavaPlugin {

    private static final byte RED_PALETTE = 18;

    private WebServer webServer;
    private MapPacketSender mapPacketSender;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        PacketEvents.getAPI().init();

        mapPacketSender = new MapPacketSender();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(new HcCommand(mapPacketSender).build()));

        // M1 骨架：host/port 硬编码，后续任务从 config.yml 读取
        String host = "127.0.0.1";
        int port = 8877;
        webServer = new WebServer(getLogger(), host, port, this::paintAllHeldMapsRed);
        webServer.start();

        getLogger().info("HikariCanvas enabled (skeleton)");
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        try {
            PacketEvents.getAPI().terminate();
        } catch (Exception ignored) {
            // terminate 在未 init 时可能抛；M1 不关心
        }
        getLogger().info("HikariCanvas disabled");
    }

    /**
     * 被 WebServer 的 {@code paint} op 触发；切到主线程遍历在线玩家，
     * 对主手的 {@code filled_map} 整张涂红。M1 demo：不做身份绑定，
     * M2 引入 session/token 后按会话对应的玩家来精确发包。
     */
    private void paintAllHeldMapsRed() {
        Bukkit.getScheduler().runTask(this, () -> {
            int painted = 0;
            byte[] pixels = new byte[MapPacketSender.MAP_PIXELS];
            Arrays.fill(pixels, RED_PALETTE);
            for (Player p : Bukkit.getOnlinePlayers()) {
                ItemStack item = p.getInventory().getItemInMainHand();
                if (item.getType() != Material.FILLED_MAP) continue;
                if (!(item.getItemMeta() instanceof MapMeta meta)) continue;
                MapView view = meta.getMapView();
                if (view == null) continue;
                mapPacketSender.sendFullMap(p, view.getId(), pixels);
                painted++;
            }
            getLogger().info("WS paint op: painted " + painted + " held maps");
        });
    }
}
