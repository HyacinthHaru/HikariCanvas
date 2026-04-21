package moe.hikari.canvas;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import moe.hikari.canvas.command.CanvasCommand;
import moe.hikari.canvas.deploy.FrameDeployer;
import moe.hikari.canvas.deploy.FrameProtectionListener;
import moe.hikari.canvas.deploy.MapPacketSender;
import moe.hikari.canvas.deploy.WallResolver;
import moe.hikari.canvas.pool.MapPool;
import moe.hikari.canvas.render.PlaceholderRenderer;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.session.TokenService;
import moe.hikari.canvas.session.WandListener;
import moe.hikari.canvas.storage.AuditLog;
import moe.hikari.canvas.storage.Database;
import moe.hikari.canvas.storage.MigrationRunner;
import moe.hikari.canvas.web.WebServer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Arrays;

@SuppressWarnings("UnstableApiUsage") // Paper Lifecycle API 标记为 experimental 但稳定可用
public final class HikariCanvas extends JavaPlugin {

    private static final byte RED_PALETTE = 18;

    private Database database;
    private AuditLog auditLog;
    private TokenService tokenService;
    private BukkitTask tokenPurgeTask;
    private MapPool mapPool;
    private WallResolver wallResolver;
    private SessionManager sessionManager;
    private WebServer webServer;
    private MapPacketSender mapPacketSender;
    private FrameDeployer frameDeployer;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        PacketEvents.getAPI().init();

        // 持久化：按 docs/data-model.md §2.1 在 plugins/HikariCanvas/data.db
        database = new Database(getLogger(), getDataFolder().toPath().resolve("data.db"));
        new MigrationRunner(database.jdbi(), getLogger()).run();
        auditLog = new AuditLog(database.jdbi());

        // 一次性 token 服务（contract: docs/security.md §2）。TTL 暂硬编码 15m，待 config.yml 接入
        tokenService = new TokenService(
                auditLog, getLogger(), Duration.ofMinutes(15).toMillis());
        // 每 5 分钟异步清一次过期/已用 token
        long ticks5min = 20L * 60 * 5;
        tokenPurgeTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this, () -> tokenService.purgeExpired(), ticks5min, ticks5min);

        // 预览地图池（M2 核心机制）。initial=64, max=256，待 config.yml 接入
        mapPool = new MapPool(getLogger(), database.jdbi(), auditLog, 64, 256);
        mapPool.initialize(Bukkit.getWorlds().get(0));

        // 墙面识别 + 会话管理（T6 Wand / T11 命令族会注入这两个）
        wallResolver = new WallResolver(16);  // canvas-max-maps 默认
        sessionManager = new SessionManager(getLogger(), mapPool, wallResolver, auditLog);

        mapPacketSender = new MapPacketSender();
        frameDeployer = new FrameDeployer(this, new PlaceholderRenderer(), mapPacketSender);

        getServer().getPluginManager().registerEvents(
                new WandListener(this, sessionManager), this);
        getServer().getPluginManager().registerEvents(
                new FrameProtectionListener(frameDeployer), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        new CanvasCommand(this, mapPacketSender, sessionManager).build()));

        // M1 骨架：host/port 硬编码，后续任务从 config.yml 读取
        String host = "127.0.0.1";
        int port = 8877;
        String version = getPluginMeta().getVersion();
        webServer = new WebServer(getLogger(), host, port,
                tokenService, sessionManager, version, this::paintAllHeldMapsRed);
        webServer.start();

        getLogger().info("HikariCanvas enabled (skeleton)");
    }

    @Override
    public void onDisable() {
        if (tokenPurgeTask != null) {
            tokenPurgeTask.cancel();
            tokenPurgeTask = null;
        }
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        if (database != null) {
            database.close();
            database = null;
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
