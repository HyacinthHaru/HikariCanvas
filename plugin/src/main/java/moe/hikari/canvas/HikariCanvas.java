package moe.hikari.canvas;

import moe.hikari.canvas.web.WebServer;
import org.bukkit.plugin.java.JavaPlugin;

public final class HikariCanvas extends JavaPlugin {

    private WebServer webServer;

    @Override
    public void onEnable() {
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
        getLogger().info("HikariCanvas disabled");
    }
}
