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
import moe.hikari.canvas.render.CanvasCompositor;
import moe.hikari.canvas.render.CanvasProjector;
import moe.hikari.canvas.render.FontRegistry;
import moe.hikari.canvas.render.HikariCanvasRenderer;
import moe.hikari.canvas.render.PaletteLut;
import moe.hikari.canvas.render.PlaceholderRenderer;
import moe.hikari.canvas.render.ProjectionThrottler;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.session.SessionRateLimiter;
import moe.hikari.canvas.session.SessionReaper;
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

import java.io.IOException;
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
    private SessionReaper sessionReaper;
    private WebServer webServer;
    private MapPacketSender mapPacketSender;
    private FrameDeployer frameDeployer;
    private HikariCanvasRenderer canvasRenderer;
    private CanvasProjector canvasProjector;
    private ProjectionThrottler projectionThrottler;
    private SessionRateLimiter rateLimiter;
    private FontRegistry fontRegistry;
    private PaletteLut paletteLut;

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

        // 共享 MapRenderer：所有受管 MapView 都挂它，让 Paper tick 持续把我们
        // 的 Placeholder / 编辑像素同步给 viewer，避免默认 canvas 每 tick 覆盖回空白
        canvasRenderer = new HikariCanvasRenderer();

        // 预览地图池（M2 核心机制）。initial=64, max=256，待 config.yml 接入
        mapPool = new MapPool(getLogger(), database.jdbi(), auditLog,
                canvasRenderer, 64, 256);
        mapPool.initialize(Bukkit.getWorlds().get(0));

        // 墙面识别 + 会话管理（T6 Wand / T11 命令族会注入这两个）
        wallResolver = new WallResolver(16);  // canvas-max-maps 默认
        sessionManager = new SessionManager(getLogger(), mapPool, wallResolver, auditLog);

        mapPacketSender = new MapPacketSender();
        frameDeployer = new FrameDeployer(this, new PlaceholderRenderer(), canvasRenderer);

        // M4-T3：字体注册表。先加载内置（jar 里 /fonts/）再扫外部目录（允许玩家自定义）
        fontRegistry = new FontRegistry(getLogger());
        fontRegistry.loadBuiltIn();
        fontRegistry.loadExternal(getDataFolder().toPath().resolve("fonts"));
        getLogger().info("FontRegistry: " + fontRegistry.size() + " font(s) ready");

        // M4-T2：调色板 LUT（32³ Lab）。启动期一次性构建 ~32 KiB，常驻
        try {
            paletteLut = PaletteLut.loadFromClasspath("/palette.json");
            getLogger().info("PaletteLut: " + paletteLut.size() + " entries loaded");
        } catch (IOException e) {
            throw new IllegalStateException("failed to load palette.json from classpath; "
                    + "did ./gradlew generatePalette run?", e);
        }

        // M3-T7 / M4-T4：编辑 op 成功后把受影响 mapIds 重绘。
        // Compositor = RGBA 大图 rasterize + palette 量化切片
        CanvasCompositor compositor = new CanvasCompositor(paletteLut, fontRegistry, getLogger());
        canvasProjector = new CanvasProjector(canvasRenderer, compositor, getLogger());

        // M3-T10 节流：5fps 投影 + 40msg/2s 输入限流（per session）
        projectionThrottler = new ProjectionThrottler(this, sessionManager, canvasProjector);
        rateLimiter = new SessionRateLimiter();
        // session forget 时清两个 bucket map 避免内存膨胀
        sessionManager.addForgetHook(projectionThrottler::discardSession);
        sessionManager.addForgetHook(rateLimiter::discardSession);

        // 超时回收：ISSUED 15min（与 token TTL 一致）/ WS 断连 5min / ACTIVE idle 30min。
        // 扫描周期 30s，待 config.yml 接入后可调。
        sessionReaper = new SessionReaper(
                this, sessionManager, frameDeployer, getLogger(),
                Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(30));
        sessionReaper.start(20L * 30);

        getServer().getPluginManager().registerEvents(
                new WandListener(this, sessionManager), this);
        getServer().getPluginManager().registerEvents(
                new FrameProtectionListener(frameDeployer), this);

        // M1 骨架：host/port 硬编码，后续任务从 config.yml 读取
        String host = "127.0.0.1";
        int port = 8877;
        String version = getPluginMeta().getVersion();
        String editorUrlTemplate = "http://" + host + ":" + port + "/?token={token}";

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        new CanvasCommand(this, sessionManager, frameDeployer,
                                tokenService, mapPool, database, editorUrlTemplate).build()));

        webServer = new WebServer(getLogger(), host, port,
                tokenService, sessionManager,
                projectionThrottler, rateLimiter,
                version, this::paintAllSessionMaps);
        webServer.start();

        getLogger().info("HikariCanvas enabled (skeleton)");
    }

    @Override
    public void onDisable() {
        if (sessionReaper != null) {
            sessionReaper.stop();
            sessionReaper = null;
        }
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
     * 被 WebServer 的 {@code paint} op 触发：把所有活跃会话的全部 mapIds 涂红。
     * 走 {@link HikariCanvasRenderer#update} 存像素，Paper tick 自动同步给 viewer。
     * M2 demo 一般只有 1 个会话，效果等同于"把该会话墙面全涂红"。
     */
    private void paintAllSessionMaps() {
        Bukkit.getScheduler().runTask(this, () -> {
            byte[] pixels = new byte[128 * 128];
            Arrays.fill(pixels, RED_PALETTE);
            int painted = 0;
            for (String sid : sessionManager.liveSessionIds()) {
                var s = sessionManager.byId(sid);
                if (s == null || s.mapIds() == null) continue;
                for (Integer mapId : s.mapIds()) {
                    canvasRenderer.update(mapId, pixels);
                    painted++;
                }
            }
            getLogger().info("WS paint op: painted " + painted + " session maps");
        });
    }
}
