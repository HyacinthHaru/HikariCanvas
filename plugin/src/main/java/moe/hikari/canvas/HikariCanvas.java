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
import moe.hikari.canvas.render.WallRestorer;
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
import moe.hikari.canvas.storage.ImageUploadDao;
import moe.hikari.canvas.storage.TemplateRepo;
import moe.hikari.canvas.storage.WallRepo;
import moe.hikari.canvas.storage.MigrationRunner;
import moe.hikari.canvas.image.ImageQuotaService;
import moe.hikari.canvas.image.ImageStorage;
import moe.hikari.canvas.image.UploadHandler;
import moe.hikari.canvas.template.TemplateLoader;
import moe.hikari.canvas.template.TemplatePublisher;
import moe.hikari.canvas.template.TemplateRegistry;
import moe.hikari.canvas.template.asset.TemplateAssetService;
import moe.hikari.canvas.template.preview.TemplatePreviewService;
import moe.hikari.canvas.template.preview.WallPreviewService;
import moe.hikari.canvas.variable.VariableStore;
import moe.hikari.canvas.storage.UserVariableDao;
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
    // M15.3 P0-24：MapPool 泄漏检测周期任务（5 分钟）。idcounts.dat 防膨胀的最后防线。
    private BukkitTask mapPoolLeakTask;
    private MapPool mapPool;
    private WallResolver wallResolver;
    private SessionManager sessionManager;
    private SessionReaper sessionReaper;
    private WallRepo wallRepo;
    /** M16 P2.5：启动期 wall 恢复器，wand listener 用它查"restore 失败"白名单。 */
    private WallRestorer wallRestorer;
    private WebServer webServer;
    private MapPacketSender mapPacketSender;
    private FrameDeployer frameDeployer;
    private HikariCanvasRenderer canvasRenderer;
    private CanvasProjector canvasProjector;
    private ProjectionThrottler projectionThrottler;
    private SessionRateLimiter rateLimiter;
    private FontRegistry fontRegistry;
    /** M26：矢量图标注册表（FA Free + 用户 SVG）。 */
    private moe.hikari.canvas.render.IconRegistry iconRegistry;
    private PaletteLut paletteLut;
    private TemplateRegistry templateRegistry;
    private TemplatePreviewService templatePreviewService;
    private TemplateAssetService templateAssetService;
    private WallPreviewService wallPreviewService;
    private ImageStorage imageStorage;
    private UploadHandler uploadHandler;
    private TemplateRepo templateRepo;
    private TemplatePublisher templatePublisher;
    // 0.4.0-P1-A：变量系统底座（VariableStore + user_variables 持久化）。
    // wallDirtyCallback 暂为 noop，待 0.4.0-P1-B 接入 ProjectionThrottler。
    private VariableStore variableStore;
    private volatile HikariCanvasConfig config;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        PacketEvents.getAPI().init();

        // M15.4 P0-12：IIORegistry 防御 — 注销已知有 CVE 历史 / 不需要的 ImageIO reader。
        // 我们 ImageStorage 只用 PNG / JPEG / WebP；其他格式（TIFF、BMP、GIF）attack surface
        // 大且未用，启动期注销避免 ImageIO.read 触发它们的解码循环（Thread.interrupt
        // 大多对 ImageIO 内部循环无效，唯一稳的防线是不让它们注册）。
        try {
            javax.imageio.spi.IIORegistry registry = javax.imageio.spi.IIORegistry.getDefaultInstance();
            java.util.Iterator<javax.imageio.spi.ImageReaderSpi> readers = registry.getServiceProviders(
                    javax.imageio.spi.ImageReaderSpi.class, false);
            java.util.List<javax.imageio.spi.ImageReaderSpi> toRemove = new java.util.ArrayList<>();
            while (readers.hasNext()) {
                javax.imageio.spi.ImageReaderSpi spi = readers.next();
                String[] names = spi.getFormatNames();
                boolean keep = false;
                for (String n : names) {
                    String lc = n.toLowerCase(java.util.Locale.ROOT);
                    if (lc.equals("png") || lc.equals("jpeg") || lc.equals("jpg") || lc.equals("webp")) {
                        keep = true;
                        break;
                    }
                }
                if (!keep) toRemove.add(spi);
            }
            for (var spi : toRemove) registry.deregisterServiceProvider(spi);
            getLogger().info("IIORegistry: deregistered " + toRemove.size()
                    + " unused image readers (kept PNG/JPEG/WebP)");
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.WARNING,
                    "IIORegistry deregistration failed (non-fatal)", e);
        }

        // M7 polish：config.yml。首次启动把 jar 内默认配置拷到 dataFolder
        saveDefaultConfig();
        config = HikariCanvasConfig.load(this);
        getLogger().info("Config loaded: " + config.summary());

        // 持久化：按 docs/data-model.md §2.1 在 plugins/HikariCanvas/data.db
        database = new Database(getLogger(), getDataFolder().toPath().resolve("data.db"));
        // M15.4 P0-29：可选 migration 前自动备份；pre-release 默认关。
        new MigrationRunner(database.jdbi(), getLogger(),
                config.databaseAutoBackup,
                getDataFolder().toPath().resolve("data.db")).run();
        // M15.4 P0-33：AuditLog 接 logger，让 DB 失败时能 fallback 到 server log。
        auditLog = new AuditLog(database.jdbi(), getLogger());

        // 一次性 token 服务（contract: docs/security.md §2）。
        tokenService = new TokenService(
                auditLog, getLogger(), config.tokenTtl.toMillis());
        tokenPurgeTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this, () -> tokenService.purgeExpired(),
                config.tokenPurgeTicks, config.tokenPurgeTicks);

        // 共享 MapRenderer：所有受管 MapView 都挂它，让 Paper tick 持续把我们
        // 的 Placeholder / 编辑像素同步给 viewer，避免默认 canvas 每 tick 覆盖回空白
        canvasRenderer = new HikariCanvasRenderer();

        // 预览地图池
        mapPool = new MapPool(getLogger(), database.jdbi(), auditLog,
                canvasRenderer, config.mapPoolInitial, config.mapPoolMax);
        // M16 P2.3：多世界 per-world initial（可选 config）。defaultWorld 用于总数补齐 +
        // 旧池行 world 信息缺失时的 fallback。未配置 per-world 的 world 走 on-demand 扩容。
        mapPool.initialize(Bukkit.getWorlds().get(0), config.mapPoolPerWorldInitial);

        // 墙面识别 + 会话管理（T6 Wand / T11 命令族会注入这两个）
        wallResolver = new WallResolver(16, this);  // canvas-max-maps 默认；plugin 提供 PDC namespace
        wallRepo = new WallRepo(getLogger(), database.jdbi());

        // M8-B：把 protocol_version < 2 的老 wall 升到 v2 layered 形态。
        // 失败的行保留 v1，下次启动重试 + 运行期 /canvas open 时 lazy 兼容。
        WallRepo.MigrationStats wallV2Stats = wallRepo.migrateAllToV2();
        if (wallV2Stats.scanned() > 0) {
            getLogger().info("ProjectState v1→v2 migration: scanned="
                    + wallV2Stats.scanned() + " migrated=" + wallV2Stats.migrated()
                    + " failed=" + wallV2Stats.failed());
        }

        sessionManager = new SessionManager(getLogger(), mapPool, wallResolver, auditLog, wallRepo, canvasRenderer);

        mapPacketSender = new MapPacketSender();
        // PlaceholderRenderer 也注入 CanvasProjector，用于"state pristine 回 placeholder"语义
        PlaceholderRenderer placeholderRenderer = new PlaceholderRenderer();
        frameDeployer = new FrameDeployer(this, placeholderRenderer, canvasRenderer);

        // M4-T3：字体注册表。先加载内置（jar 里 /fonts/）再扫外部目录（允许玩家自定义）
        fontRegistry = new FontRegistry(getLogger());
        fontRegistry.loadBuiltIn();
        fontRegistry.loadExternal(getDataFolder().toPath().resolve("fonts"));
        getLogger().info("FontRegistry: " + fontRegistry.size() + " font(s) ready");

        // M26：矢量图标注册表。jar 内置 Font Awesome Free + 用户 plugins/HikariCanvas/icons/*.svg
        iconRegistry = new moe.hikari.canvas.render.IconRegistry(getLogger());
        iconRegistry.loadBuiltIn();
        iconRegistry.loadExternal(getDataFolder().toPath().resolve("icons"));
        getLogger().info("IconRegistry: " + iconRegistry.size() + " icon(s) ready");

        // M4-T2：调色板 LUT（32³ Lab）。启动期一次性构建 ~32 KiB，常驻
        try {
            paletteLut = PaletteLut.loadFromClasspath("/palette.json");
            getLogger().info("PaletteLut: " + paletteLut.size() + " entries loaded");
        } catch (IOException e) {
            throw new IllegalStateException("failed to load palette.json from classpath; "
                    + "did ./gradlew generatePalette run?", e);
        }

        // M7：模板图标资源服务（classpath /template-assets/icons + dataFolder/assets/icons）
        templateAssetService = new TemplateAssetService(getLogger(),
                getDataFolder().toPath());

        // M13：图片上传 + 存储 + 配额。先于 compositor 装配，让 WallRestorer 重启
        // restore 时 ImageElement 也能正确加载（否则一次启动期会画占位）。
        ImageUploadDao imageDao = new ImageUploadDao(getLogger(), database.jdbi());
        imageStorage = new ImageStorage(getLogger(), getDataFolder().toPath(), imageDao);
        ImageQuotaService imageQuota = new ImageQuotaService(imageDao, config.images);

        // M3-T7 / M4-T4：编辑 op 成功后把受影响 mapIds 重绘。
        // Compositor = RGBA 大图 rasterize + palette 量化切片
        CanvasCompositor compositor = new CanvasCompositor(paletteLut, fontRegistry,
                templateAssetService, iconRegistry, getLogger());
        compositor.setImageLoader(imageStorage::load);
        canvasProjector = new CanvasProjector(canvasRenderer, compositor, placeholderRenderer, getLogger());

        // M5.5：启动末尾把所有 walls 的像素 compose 回对应 MapView
        // M16 P2.5：保留 restorer 引用，让 wand listener 能查"启动期 restore 失败的 wall"
        // 并给玩家 ActionBar 提示。
        WallRestorer wallRestorerInstance = new WallRestorer(getLogger(), wallRepo, mapPool,
                canvasRenderer, compositor, placeholderRenderer);
        try {
            int restored = wallRestorerInstance.restore();
            getLogger().info("Wall restore: " + restored + " wall(s) repainted");
            if (!wallRestorerInstance.failedRestoreWallIds().isEmpty()) {
                getLogger().warning("Wall restore: failed wall_ids = "
                        + wallRestorerInstance.failedRestoreWallIds()
                        + " (interactions will be blocked until next successful restart)");
            }
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.WARNING, "WallRestorer failed (non-fatal)", e);
        }
        this.wallRestorer = wallRestorerInstance;

        // M6-A：模板注册表。jar 内 /templates/*.yml + plugins/HikariCanvas/templates/ +
        // M14 user-templates/<uuid>/*.yml（创意工坊）
        templateRegistry = new TemplateRegistry(
                getLogger(), HikariCanvas.class,
                getDataFolder().toPath().resolve("templates"),
                getDataFolder().toPath().resolve("user-templates"));
        if (config.autoReloadTemplatesOnStartup) {
            templateRegistry.reload();
        }
        // 0.4.0-P1-A：变量系统底座。DAO + Store + 启动期 loadFromDb。
        // wallDirtyCallback 暂占位 noop —— B 任务接入 ProjectionThrottler 后注入真 hook。
        UserVariableDao userVariableDao = new UserVariableDao(getLogger(), database.jdbi());
        variableStore = new VariableStore(userVariableDao, wallId -> { /* B 任务接 ProjectionThrottler#dirty */ });
        variableStore.loadFromDb();
        getLogger().info("VariableStore: " + variableStore.size() + " user variable(s) loaded");

        // M14：模板元数据 DAO + 创意工坊协调器
        templateRepo = new TemplateRepo(getLogger(), database.jdbi());
        TemplateLoader publisherYamlLoader = new TemplateLoader();
        templatePublisher = new TemplatePublisher(getLogger(),
                getDataFolder().toPath(),
                publisherYamlLoader, templateRegistry, templateRepo,
                compositor, config.templatesMaxPerPlayer);
        templatePublisher.syncBuiltinToDb();
        // M7：模板缩略图服务。Registry reload 时调 invalidate() 清缓存
        templatePreviewService = new TemplatePreviewService(getLogger(), templateRegistry, compositor);
        wallPreviewService = new WallPreviewService(getLogger(), compositor);

        // 节流：投影 fps + 输入速率（per session）
        long projectionIntervalMs = Math.max(1000L / config.projectionFps, 33L);
        projectionThrottler = new ProjectionThrottler(this, sessionManager, canvasProjector,
                projectionIntervalMs);
        rateLimiter = new SessionRateLimiter(config.inputBurst,
                Math.max(1000L, (long) config.inputBurst * 1000 / Math.max(1, config.inputRatePerSecond)));
        sessionManager.addForgetHook(projectionThrottler::discardSession);
        sessionManager.addForgetHook(rateLimiter::discardSession);

        // 超时回收：ISSUED ttl（与 token TTL 一致）/ WS 断连 grace / ACTIVE idle
        sessionReaper = new SessionReaper(
                this, sessionManager, getLogger(),
                config.tokenTtl, config.wsGrace, config.idleTimeout);
        sessionReaper.start(config.reaperScanTicks);

        String version = getPluginMeta().getVersion();
        // 用 config 里的 url 模板（保留 {token} 占位符给运行期 token 替换）
        String editorUrlTemplate = config.editorUrlTemplate;

        // WandListener 注册：需要 frameDeployer / tokenService / editorUrlTemplate 来支持
        // "瞄已有 ItemFrame 二次确认 → open" 路径
        getServer().getPluginManager().registerEvents(
                new WandListener(this, sessionManager, frameDeployer, tokenService, wallRepo,
                        editorUrlTemplate, wallRestorer),
                this);
        getServer().getPluginManager().registerEvents(
                new FrameProtectionListener(frameDeployer), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        new CanvasCommand(this, sessionManager, frameDeployer,
                                tokenService, mapPool, database, wallRepo,
                                templateRegistry, templatePreviewService, editorUrlTemplate).build()));

        // M13：UploadHandler 需要 sessionManager / wallRepo，所以晚于它们装配
        // M16 P2.1/P2.2：还需要 imageDao + jdbi 做事务化 quota+insert+evict
        uploadHandler = new UploadHandler(getLogger(), imageStorage, imageQuota,
                imageDao, database.jdbi(),
                config.images, tokenService, sessionManager, wallRepo, auditLog);

        webServer = new WebServer(getLogger(), config.host, config.port,
                tokenService, sessionManager,
                projectionThrottler, rateLimiter,
                wallRepo, frameDeployer, templateRegistry, templatePreviewService,
                templateAssetService, wallPreviewService, uploadHandler,
                templatePublisher, templateRepo, auditLog, fontRegistry, iconRegistry, this,
                version, this::paintAllSessionMaps,
                config.wsAuthTimeoutSeconds, config.allowedOrigins);
        webServer.start();

        // M15.3 P0-24：MapPool 泄漏检测周期任务（5 分钟）。idcounts.dat 防膨胀的最后防线。
        // 同 tokenPurgeTask 模式：异步周期跑；扫所有 RESERVED 找 owner 已不在 walls 表的强制 FREE。
        mapPoolLeakTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                () -> {
                    java.util.Set<String> liveWallIds = wallRepo.loadAll().stream()
                            .map(w -> w.wallId())
                            .collect(java.util.stream.Collectors.toSet());
                    int leaked = mapPool.detectLeaks(liveWallIds);
                    if (leaked > 0) {
                        getLogger().warning("[mapPool] detected " + leaked + " leaked map(s); released");
                    }
                },
                20L * 60 * 5,    // 启动后 5 分钟首次跑
                20L * 60 * 5);   // 每 5 分钟一次

        getLogger().info("HikariCanvas enabled (skeleton)");
    }

    /**
     * 接收 {@code /canvas reload config} 的新配置。M7 v1 只更新引用 + log；多数字段（host/port/
     * 池容量/超时等）已传给具体服务，必须重启才能真正生效。提示由命令侧打。
     *
     * <p>未来若有需要做 hot-apply，可在此扩展：给 TokenService/SessionReaper/MapPool 各加 setter。</p>
     */
    public synchronized void applyConfig(HikariCanvasConfig fresh) {
        this.config = fresh;
        getLogger().info("Config refreshed (most fields need restart): " + fresh.summary());
    }

    /** 供命令侧用；返回 null 表示插件还没 onEnable。 */
    public HikariCanvasConfig config() { return config; }

    /** 0.4.0-P1-A：供 B / C / D / E 任务取 VariableStore 单例。 */
    public VariableStore getVariableStore() { return variableStore; }

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
        if (mapPoolLeakTask != null) {
            mapPoolLeakTask.cancel();
            mapPoolLeakTask = null;
        }
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        if (uploadHandler != null) {
            uploadHandler.shutdown();
            uploadHandler = null;
        }
        if (imageStorage != null) {
            imageStorage.shutdown();
            imageStorage = null;
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
