package moe.hikari.canvas;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import moe.hikari.canvas.command.HcCommand;
import moe.hikari.canvas.deploy.MapPacketSender;
import moe.hikari.canvas.web.WebServer;
import org.bukkit.plugin.java.JavaPlugin;

@SuppressWarnings("UnstableApiUsage") // Paper Lifecycle API 标记为 experimental 但稳定可用
public final class HikariCanvas extends JavaPlugin {

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
        webServer = new WebServer(getLogger(), host, port);
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
            // terminate 在 PacketEvents 未 init 时可能抛异常；M1 不关心
        }
        getLogger().info("HikariCanvas disabled");
    }
}
