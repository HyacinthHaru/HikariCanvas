package moe.hikari.canvas;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import moe.hikari.canvas.command.BenchmarkSubCommand;
import moe.hikari.canvas.command.CanvasCommand;
import moe.hikari.canvas.command.VariableSubCommand;
import moe.hikari.canvas.deploy.FrameDeployer;
import moe.hikari.canvas.deploy.FrameProtectionListener;
import moe.hikari.canvas.deploy.MapPacketSender;
import moe.hikari.canvas.deploy.WallResolver;
import moe.hikari.canvas.pool.MapPool;
import moe.hikari.canvas.render.AnimationTicker;
import moe.hikari.canvas.render.TimelineTriggerRegistry;
import moe.hikari.canvas.render.BufferPool;
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
import moe.hikari.canvas.variable.plugin.HikariCanvasAPIImpl;
import moe.hikari.canvas.variable.plugin.PluginCleanupListener;
import moe.hikari.canvas.variable.plugin.PluginNamespaceRegistry;
import moe.hikari.canvas.variable.plugin.PushRateLimiter;
import moe.hikari.canvas.variable.provider.ManualScheduleProvider;
import moe.hikari.canvas.variable.provider.ProviderBootstrap;
import moe.hikari.canvas.variable.provider.VariableProviderDaemon;
import moe.hikari.canvas.api.HikariCanvasAPI;
import moe.hikari.canvas.storage.UserVariableDao;
import moe.hikari.canvas.web.WebServer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.time.Duration;

@SuppressWarnings("UnstableApiUsage") // Paper Lifecycle API 标记为 experimental 但稳定可用
public final class HikariCanvas extends JavaPlugin {

    private Database database;
    private AuditLog auditLog;
    private TokenService tokenService;
    // 0.4.10 P3-11：onDisable 置 true。tokenPurgeTask / mapPoolLeakTask 是 Bukkit 异步周期任务，
    // cancel() 不等待 in-flight run 结束——若一次 run 正在跑而 onDisable 已 database.close()，
    // 任务体读 wallRepo/mapPool（走 DB）会 use-after-close。任务体入口检查此 flag 提前 short-circuit。
    private volatile boolean shuttingDown = false;
    private BukkitTask tokenPurgeTask;
    // M15.3 P0-24：MapPool 泄漏检测周期任务（5 分钟）。idcounts.dat 防膨胀的最后防线。
    private BukkitTask mapPoolLeakTask;
    private MapPool mapPool;
    private WallResolver wallResolver;
    // 0.4.10 P2-16：daemon 线程（VariableStore wallDirtyCallback / ChangeListener、
    // mapPoolLeakTask 异步任务）读这些单例字段；onEnable 在主线程写。无 volatile 时存在 JMM
    // 可见性缺口（daemon 可能读到 null 或半构造引用）。volatile 写建立 happens-before，
    // 与 config 字段（已 volatile）一致。
    private volatile SessionManager sessionManager;
    private SessionReaper sessionReaper;
    private WallRepo wallRepo;
    /** M16 P2.5：启动期 wall 恢复器，wand listener 用它查"restore 失败"白名单。 */
    private WallRestorer wallRestorer;
    private volatile WebServer webServer;
    private MapPacketSender mapPacketSender;
    private FrameDeployer frameDeployer;
    private HikariCanvasRenderer canvasRenderer;
    private volatile CanvasProjector canvasProjector;
    private volatile ProjectionThrottler projectionThrottler;
    // 0.6 P2：时间轴动画产帧引擎（单线程 daemon + BufferPool 池化）。装配在 webServer 之后
    // （setAnimationTicker seam 需 dispatcher 就位）；cleanupResources 与 variableProviderDaemon
    // 同段关停（两者都是引用 DB 的 daemon，须早于 database.close）。
    private volatile AnimationTicker animationTicker;
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
    // 0.8-A2：.canvas 工程导入 assets 摄入栈；持 daemon 单线程 decoderPool，onDisable 须 shutdown
    // （否则热重载累积线程池泄漏）。
    private moe.hikari.canvas.canvasfile.AssetIngest assetIngest;
    private TemplateRepo templateRepo;
    private TemplatePublisher templatePublisher;
    // 0.4.0-P1-A：变量系统底座（VariableStore + user_variables 持久化）。
    // wallDirtyCallback 暂为 noop，待 0.4.0-P1-B 接入 ProjectionThrottler。
    private VariableStore variableStore;
    // 0.4.0-P1-E：异步 Provider 调度框架（守护线程 + 定时 refresh）。P1 阶段不注册任何
    // provider；P3 在 ProviderBootstrap.initialize 内加 SystemVariableProvider / PapiVariableBridge 等。
    private VariableProviderDaemon variableProviderDaemon;
    // 0.4.0-P3-L：兜底列车时刻表 DAO。WebServer schedule.* op + ManualScheduleProvider 共享。
    private moe.hikari.canvas.storage.ScheduleDao scheduleDao;
    // 0.4.2：变量别名 DAO（per-wall fullName → alias 字符串映射）。WebServer 注入 dispatcher
    // + ready payload；不参与渲染。
    private moe.hikari.canvas.storage.VariableAliasDao variableAliasDao;
    // 0.4.3：全局用户变量 DAO（userglobal/* namespace；name 全服唯一）。装配后注入
    // VariableStore.configureUserGlobal —— EditSession.createGlobalVariable 路径走它持久化。
    private moe.hikari.canvas.storage.UserGlobalVariableDao userGlobalVariableDao;
    // 0.4.4：铁路网络 DAO（V016；5 表统一 CRUD）。WebServer + RailScheduleProvider 共享。
    private moe.hikari.canvas.storage.RailDao railDao;
    // 0.7.0 P1：墙脚本内存镜像（V017 wall_scripts 持久化）。WebServer script.* op +
    // ready payload 注入；P2 ScriptRunner 引擎也走它。
    private moe.hikari.canvas.script.ScriptStore scriptStore;
    // 0.7.0 P2-5：脚本执行引擎三件（装配在 AnimationTicker 之后——ActionExecutor 依赖 ticker）。
    // budget 留引用供 /canvas reload 热更（applyConfig）；runner / router 是 daemon 类资源，
    // onDisable 须 shutdown（router 先关停 timer 投递，runner 再关执行队列）。
    private moe.hikari.canvas.script.engine.ScriptBudget scriptBudget;
    private moe.hikari.canvas.script.engine.ScriptRunner scriptRunner;
    private moe.hikari.canvas.script.engine.TriggerRouter scriptTriggerRouter;
    // 0.7.2-P2 F10：留引用供 /canvas reload 热更 headless 克隆元素配额（applyConfig）。
    private moe.hikari.canvas.script.engine.ElementPropertyApplier scriptPropertyApplier;
    // 补间动画引擎 P2：独立单线程 SES，渲临时帧（不落 DB）+ 末帧 applyMany 落盘。
    // 须在 scriptRunner.shutdown() 之后、animationTicker.shutdown() 之前关停。
    private moe.hikari.canvas.script.engine.TweenScheduler tweenScheduler;
    // 0.7.0-P3 B2（K14）：playerNear 周期采样器（主线程 task，2 tick 底层周期 +
    // volatile 跳帧计数；applyConfig 热更采样间隔）。
    private moe.hikari.canvas.script.engine.PlayerNearSampler playerNearSampler;
    private BukkitTask playerNearTask;
    // 0.7.0-P3-5：世界名 → UUID 快照表。playerNear 原点解析（originSource）可能在
    // 任意线程跑（WS 脚本 op → ScriptStore listener → rebuildWall 在 Jetty 线程），
    // 不能异步调 Bukkit.getWorld（读 CraftServer.worlds 普通 LinkedHashMap，官方不
    // 保证线程安全）。onEnable 用 Bukkit.getWorlds() 全量种子；之后由
    // GameEventListenerHub 的 WorldLoad/WorldUnloadEvent handler（主线程）增量维护。
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.UUID>
            scriptWorldUuidByName = new java.util.concurrent.ConcurrentHashMap<>();
    // 0.4.0-P4-O / P4-Q：外部插件 namespace 注册表 + Push API impl。
    // Q 任务装配：onEnable 实例化 + Bukkit.getServicesManager().register（让外部插件
    // 通过 ServicesManager.load(HikariCanvasAPI.class) 零编译耦合拿到 API）。
    // PluginCleanupListener 监听 PluginDisableEvent，外部插件 disable 时立即移除
    // namespace + 30s 后清 store 变量。
    private PluginNamespaceRegistry pluginNamespaceRegistry;
    private HikariCanvasAPIImpl apiImpl;
    /** 0.5.0-P1：/canvas bench 命令族；持守护线程 executor，onDisable 须 shutdown。 */
    private BenchmarkSubCommand benchmarkSubCommand;
    private volatile HikariCanvasConfig config;
    // 0.8.2 i18n：多语言消息注册表（jar 内置 lang/ + dataFolder/lang/ 外部覆盖）。
    private moe.hikari.canvas.i18n.Messages messages;
    // 0.4.10 P2-82：onEnable 从 JVM 全局 IIORegistry 注销的 ImageReaderSpi 实例列表。
    // onDisable 逐个 registerServiceProvider 恢复——避免本插件（热）卸载后别的插件
    // ImageIO.read GIF/BMP/TIFF 永久失败（IIORegistry 是进程级共享单例）。
    private final java.util.List<javax.imageio.spi.ImageReaderSpi> deregisteredImageReaders =
            new java.util.ArrayList<>();

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        // 0.4.10 P2-44：整段装配包进 try/catch(Throwable)。任一步失败（缺 palette.json /
        // migration 失败 / schema 损坏 / 磁盘问题）时，catch 逆序清理已就位资源
        // （HikariDataSource 连接池 + housekeeper 线程 / daemon 线程池 / WebServer Jetty /
        // executor 等非 Bukkit 托管资源），再 rethrow 让 Bukkit 标记插件 disabled。
        // 复用 cleanupResources()（与 onDisable 同一幂等清理），避免清理逻辑两处漂移。
        try {
        // 0.4.10 P2-44/P3-11：重置 shuttingDown——上一次 onEnable 失败走 cleanupResources 会置 true，
        // PlugMan 重新 enable 时若不复位会让 mapPoolLeakTask 入口永久 short-circuit。
        shuttingDown = false;
        PacketEvents.getAPI().init();

        // M15.4 P0-12：IIORegistry 防御 — 注销已知有 CVE 历史 / 不需要的 ImageIO reader。
        // 我们 ImageStorage 只用 PNG / JPEG（"webp" 保留在白名单里只是占位——标准 JDK 无
        // WebP reader，需第三方依赖才会出现；当前无该依赖故此项不匹配任何 SPI）；其他格式
        // （TIFF、BMP、GIF）attack surface 大且未用，启动期注销避免 ImageIO.read 触发它们的
        // 解码循环（Thread.interrupt 大多对 ImageIO 内部循环无效，唯一稳的防线是不让它们注册）。
        //
        // 0.4.10 P2-82：IIORegistry 是进程级 JVM 共享单例——注销会影响同 JVM 别的插件。
        // 把被注销的 SPI 实例存到字段，onDisable 逐个 registerServiceProvider 恢复，避免本插件
        // （热）卸载后别的依赖 JDK ImageIO 读 GIF/BMP/TIFF 的插件永久失败。
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
            deregisteredImageReaders.clear();
            deregisteredImageReaders.addAll(toRemove);  // P2-82：留给 onDisable 恢复
            getLogger().info("IIORegistry: deregistered " + toRemove.size()
                    + " unused image readers (kept PNG/JPEG)");
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
        sessionManager.setMaxImagesPerWall(config.images.maxPerWall());  // 0.4.10 P2-8：per-wall 图片配额注入
        sessionManager.setMaxElementsPerWall(config.scriptsConfig.maxElementsPerWall());  // 0.7.2-P2 F10：per-wall 元素配额注入（路径 A）
        sessionManager.setTimelineFpsLimits(  // 0.6 P1：时间轴 fps 配置注入（config 段 timeline）
                config.timelineConfig.defaultFps(), config.timelineConfig.maxFps());

        // 0.4.0 方案 B 自适应渲染：mapPacketSender 在 CanvasProjector 构造前才 new（见下方）。
        // PlaceholderRenderer 也注入 CanvasProjector，用于"state pristine 回 placeholder"语义
        PlaceholderRenderer placeholderRenderer = new PlaceholderRenderer();
        frameDeployer = new FrameDeployer(this, placeholderRenderer, canvasRenderer);

        // M4-T3：字体注册表。先加载内置（jar 里 /fonts/）再扫外部目录（允许玩家自定义）
        fontRegistry = new FontRegistry(getLogger());
        fontRegistry.loadBuiltIn();
        fontRegistry.loadExternal(getDataFolder().toPath().resolve("fonts"));
        getLogger().info("FontRegistry: " + fontRegistry.size() + " font(s) ready");

        // 0.8.2 i18n：多语言消息注册表。jar 内置 lang/ → saveResource 拷到 dataFolder/lang/ 供服主覆盖；
        // 再 loadExternal 扫 dataFolder/lang/ 让外部覆盖生效。
        messages = new moe.hikari.canvas.i18n.Messages(getLogger());
        saveResource("lang/en_us.yml", false);
        saveResource("lang/zh_cn.yml", false);
        messages.loadBuiltIn();
        messages.loadExternal(getDataFolder().toPath().resolve("lang"));
        messages.setDefaultLocale(config.i18nConfig.defaultLocale());
        getLogger().info("Messages: " + messages.size() + " language(s) ready");

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
        // 0.4.0 方案 B 自适应渲染：构造期传 mapPacketSender + wallRepo，让 projector 渲染完
        // 主动给 chunk-loaded viewer 推 ClientboundMapItemDataPacket（不再依赖 Paper 默认 MapView
        // sync 的 250ms-5s 抖动窗口）。mapPacketSender 已在上面 new，wallRepo 已 ready。
        mapPacketSender = new MapPacketSender();
        canvasProjector = new CanvasProjector(canvasRenderer, compositor, placeholderRenderer,
                getLogger(), mapPacketSender, wallRepo);

        // M5.5：启动末尾把所有 walls 的像素 compose 回对应 MapView
        // M16 P2.5：保留 restorer 引用，让 wand listener 能查"启动期 restore 失败的 wall"
        // 并给玩家 ActionBar 提示。
        // 0.4.2 bugfix（Bug A，2026-05-21）：restore() 调用延迟到 setVariableSupport +
        // ProviderBootstrap.initialize 之后（见下方）—— 之前 restore 时 compositor.interpolator
        // 还是 null + store 内 schedule:wallId/* 变量为空，wall 像素被画成字面 ${var:...}，
        // 后续没人触发重画一直显字面。修法：构造 restorer 在这里完成（依赖已具备），但 restore()
        // 调用挪到 ProviderDaemon.initialize() 之后，让 interpolator 注入 + Provider 装入 store。
        WallRestorer wallRestorerInstance = new WallRestorer(getLogger(), wallRepo, mapPool,
                canvasRenderer, compositor, placeholderRenderer);
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
        variableStore = new VariableStore(userVariableDao,
                wallId -> {
                    if (sessionManager == null || projectionThrottler == null) return;
                    // 0.6 P2 审查确认项 #5：wall 由 AnimationTicker 主动产帧时整条变量
                    // 重画路径退让——变量在 rasterize 期逐帧 resolve（cached 值），Ticker 的
                    // 下一帧自然带上新值 + per-map diff 检测推送；这里再走 projectByWall
                    // 会与 Ticker 双写同一 mapId（帧交错 + diff 基准失步）。
                    AnimationTicker t = animationTicker;
                    if (t != null && t.isWallAnimating(wallId)) return;
                    boolean handled = sessionManager.submitFullCanvasDirtyByWallAndReport(
                            wallId, projectionThrottler);
                    // Ultrareview 2026-05-25 #1：无活跃 editor session 时走 deploy-only refresh，
                    // 不让动态变量更新被"editor 关着"挡掉。CanvasProjector 内部线程安全（参见
                    // 该类 javadoc），但 callback 是 VariableStore 后台线程触发——保守 hop 主线程
                    // 避免与 ItemFrame / MapView 写入竞态。
                    if (!handled && canvasProjector != null) {
                        org.bukkit.Bukkit.getScheduler().runTask(this,
                                () -> canvasProjector.projectByWall(wallId));
                    }
                });
        variableStore.loadFromDb();
        // 0.4.3 P1：全局用户变量 DAO + 配额装配。配额段在 config.yml `dynamic.variables.*`
        // （P5 接入；这里先用默认值），加载持久化的 userglobal/* 变量到内存。
        userGlobalVariableDao = new moe.hikari.canvas.storage.UserGlobalVariableDao(
                getLogger(), database.jdbi());
        variableStore.configureUserGlobal(userGlobalVariableDao,
                config.userGlobalMaxPerOwner, config.userGlobalMaxTotal);
        variableStore.loadGlobalsFromDb();
        getLogger().info("VariableStore: " + variableStore.size()
                + " variable(s) loaded (user + userglobal)");
        // 0.4.0-P1-C：Compositor 接入变量替换 + 倒排索引联动。注入在 store 创建之后即生效；
        // 此时 WallRestorer 已用旧 compositor 完成启动期 restore（restore 时占位符还没机会被注册），
        // 后续 CanvasProjector / 预览路径走有 interpolator 的渲染。注入幂等，volatile 多线程可见。
        moe.hikari.canvas.variable.VariableInterpolator variableInterpolator =
                new moe.hikari.canvas.variable.VariableInterpolator(variableStore);
        compositor.setVariableSupport(variableInterpolator, variableStore);
        // 0.4.0-P1-C：wall 删除时清掉 VariableStore 倒排索引。SessionManager.deleteWall 完成
        // map 释放 + walls 表删除后触发；user_variables 表通过 FK CASCADE 自动清。
        final VariableStore variableStoreForHook = variableStore;
        sessionManager.addWallDeleteHook(wid -> variableStoreForHook.clearWallReferences(wid));
        // 0.4.10 P2-9：显式 deleteByWall hook 与三张兄弟表（variable_aliases / wall_schedules /
        // wall_rail_bindings）对齐——user_variables 原本仅靠 FK CASCADE（foreign_keys=true 是
        // per-connection pragma，单点依赖）。显式删 + CASCADE 双保险，规避 pragma 未兑现时孤儿行累积。
        final UserVariableDao userVariableDaoForHook = userVariableDao;
        sessionManager.addWallDeleteHook(wid -> userVariableDaoForHook.deleteByWall(wid));
        // 0.4.0-P1-E / P3-J/K/L：Provider daemon 框架（守护线程池 + 定时调度）。
        // P3-J 注册 system + scoreboard；P3-K 加 PAPI 桥接；P3-L 加 ManualScheduleProvider。
        // ScheduleDao 必须先于 ProviderBootstrap.initialize 构造（V012 migration 已跑）。
        this.scheduleDao = new moe.hikari.canvas.storage.ScheduleDao(getLogger(), database.jdbi());
        // 0.4.2：变量别名 DAO（V014 migration 已跑）；WebServer 与 dispatcher 共享。
        this.variableAliasDao = new moe.hikari.canvas.storage.VariableAliasDao(
                getLogger(), database.jdbi());
        // wall 删除时显式清掉别名（FK CASCADE 已配，显式调更稳，与 schedule 同款）
        final moe.hikari.canvas.storage.VariableAliasDao variableAliasDaoForHook = this.variableAliasDao;
        sessionManager.addWallDeleteHook(wid -> variableAliasDaoForHook.deleteByWall(wid));
        // 0.4.4：铁路网络 DAO（V016 5 表）。RailScheduleProvider + WebServer 共享。
        this.railDao = new moe.hikari.canvas.storage.RailDao(getLogger(), database.jdbi());
        // 0.7.0 P1：墙脚本 DAO + 内存镜像（V017 migration 已跑）。装配须早于 WebServer 构造
        // （script.* dispatcher + ready payload scripts 字段都依赖它）。
        moe.hikari.canvas.storage.ScriptDao scriptDao =
                new moe.hikari.canvas.storage.ScriptDao(getLogger(), database.jdbi());
        scriptStore = new moe.hikari.canvas.script.ScriptStore(
                getLogger(), scriptDao, config.scriptsConfig.maxRulesPerWall());
        scriptStore.loadFromDb();
        // wall 删除时清掉内存镜像（DB 行由 wall_scripts FK CASCADE 自动清）
        sessionManager.addWallDeleteHook(scriptStore::clearWall);
        this.variableProviderDaemon =
                ProviderBootstrap.initialize(this.variableStore, this, this.wallRepo,
                        this.scheduleDao, config.scheduleConfig, this.railDao);
        // 0.4.2 bugfix（Bug A）：现在所有依赖就位（compositor.interpolator 注入 + Provider 装入
        // schedule:wallId/* + system:wallId/wall.* 等值），可以安全 restore wall 像素让 wall 上的
        // ${var:...} placeholder 被正确替换为实际值（or fallback "???"）。之前在 line 261-274
        // 早期 restore 时这些都还空，wall 显字面字符串直到下一次手动 dirty 触发。
        try {
            int restored = wallRestorerInstance.restore();
            getLogger().info("Wall restore: " + restored + " wall(s) repainted (post-provider init)");
            if (!wallRestorerInstance.failedRestoreWallIds().isEmpty()) {
                getLogger().warning("Wall restore: failed wall_ids = "
                        + wallRestorerInstance.failedRestoreWallIds()
                        + " (interactions will be blocked until next successful restart)");
            }
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.WARNING, "WallRestorer failed (non-fatal)", e);
        }
        // P3-L：wall 删除时清掉 schedule_entries + wall_schedules（FK CASCADE 已配，显式调更稳；
        // 同时 unregister Provider 内存态 + store 内的 4 个 schedule:<wallId>/* 变量）。
        final ManualScheduleProvider manualScheduleProvider =
                (ManualScheduleProvider) this.variableProviderDaemon
                        .registeredProviders().stream()
                        .filter(p -> p instanceof ManualScheduleProvider)
                        .findFirst().orElse(null);
        final moe.hikari.canvas.storage.ScheduleDao scheduleDaoForHook = this.scheduleDao;
        sessionManager.addWallDeleteHook(wid -> {
            if (manualScheduleProvider != null) manualScheduleProvider.unregisterWall(wid);
            if (scheduleDaoForHook != null) scheduleDaoForHook.deleteByWall(wid);
        });

        // 0.4.4：wall 删除时清掉 rail binding + 反注册 RailScheduleProvider 内存态
        // （wall_rail_bindings FK CASCADE 已配，显式调更稳；store 的 schedule:<wallId>/* 变量
        // 由 RailScheduleProvider.unregisterWall 走 store.delete 清干净）
        final moe.hikari.canvas.variable.provider.RailScheduleProvider railProviderRef =
                (moe.hikari.canvas.variable.provider.RailScheduleProvider) this.variableProviderDaemon
                        .registeredProviders().stream()
                        .filter(p -> p instanceof moe.hikari.canvas.variable.provider.RailScheduleProvider)
                        .findFirst().orElse(null);
        final moe.hikari.canvas.storage.RailDao railDaoForHook = this.railDao;
        sessionManager.addWallDeleteHook(wid -> {
            if (railProviderRef != null) railProviderRef.unregisterWall(wid);
            if (railDaoForHook != null) railDaoForHook.deleteBinding(wid);
        });

        // 0.4.0-P4-Q：HikariCanvasAPI 装配。依赖 VariableStore + VariableProviderDaemon，
        // 必须晚于 ProviderBootstrap.initialize；早于 WebServer（虽然当前 P4 范围内 WebServer
        // 不直接用 apiImpl，未来 phase 想加 /api/plugin/* 端点可直接访问字段）。
        // 0.4.0-P4-P：双层 push 限流（per-plugin 100/s + 全局 1000/s + 保护期 10s，
        // 默认值，可在 config.yml dynamic.push-rate-limit 调）。
        this.pluginNamespaceRegistry = new PluginNamespaceRegistry();
        PushRateLimiter pushRateLimiter = new PushRateLimiter(config.pushRateLimitConfig);
        // 0.4.10 P3-4：注入 auditLog 让 checkAcl 拒绝 namespace spoof 时记 PLUGIN_NAMESPACE_DENIED。
        this.apiImpl = new HikariCanvasAPIImpl(
                pluginNamespaceRegistry, variableStore, variableProviderDaemon, pushRateLimiter, auditLog);
        // ServicesManager 注册：外部插件零编译耦合获取入口（推荐 docs/dynamic-data.md §4）。
        // plugin disable 时 Bukkit 自动反注册，无需手动 unregister。
        Bukkit.getServicesManager().register(
                HikariCanvasAPI.class, apiImpl, this, ServicePriority.Normal);
        getLogger().info("HikariCanvasAPI registered to Bukkit ServicesManager");
        // PluginDisableEvent listener：外部插件 disable 时清掉它的 namespace。
        // 立即 unregister registry + daemon；30s 后清 store 数据（保留 cached value 平滑过渡）。
        getServer().getPluginManager().registerEvents(
                new PluginCleanupListener(pluginNamespaceRegistry, apiImpl, this), this);

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
        // 0.4.0 方案 B：默认间隔走 adaptive-fps.default-min-interval-ms（覆盖 throttle.projection-fps
        // 推算结果），用户在 config 里调 adaptive-fps 即直接控制 throttler 默认底；旧 throttle.projection-fps
        // 仍保留作"上限提示"语义，但实际节流由 adaptive 段决定。
        long projectionIntervalMs = Math.max(33L, config.adaptiveFps.defaultMinIntervalMs());
        projectionThrottler = new ProjectionThrottler(this, sessionManager, canvasProjector,
                projectionIntervalMs);
        rateLimiter = new SessionRateLimiter(config.inputBurst,
                Math.max(1000L, (long) config.inputBurst * 1000 / Math.max(1, config.inputRatePerSecond)));
        // Ultrareview 2026-05-25 #5：先 flushNow 把节流窗口内的 pending 尾帧落地，
        // 再 discardSession 取消任务清状态——保证 session cancel 时游戏内地图不停在倒数第二帧。
        sessionManager.addPreForgetHook(projectionThrottler::flushNow);
        sessionManager.addForgetHook(projectionThrottler::discardSession);
        sessionManager.addForgetHook(rateLimiter::discardSession);
        // 0.4.10 P3-32：session forget 时清 WebServer.wsBySession 映射，避免已 forget 的 session
        // 在 map 里残留（webServer 装配在后，lambda 运行期惰性读 volatile 字段，null 时跳过）。
        sessionManager.addForgetHook(sid -> {
            WebServer ws = webServer;
            if (ws != null) ws.forgetSession(sid);
        });

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
                        editorUrlTemplate, wallRestorer, messages),
                this);
        getServer().getPluginManager().registerEvents(
                new FrameProtectionListener(frameDeployer, messages), this);

        // 0.4.0-P5：/canvas var 子命令族。reload hook 重读 config.yml + 重建 PushRateLimiter
        // + 通过 setRateLimiter 热替换，无需重启服务器。
        final HikariCanvas selfRef = this;
        final HikariCanvasAPIImpl apiImplRef = this.apiImpl;
        VariableSubCommand variableSubCommand = new VariableSubCommand(
                variableStore, variableProviderDaemon, wallRepo, auditLog,
                () -> {
                    selfRef.reloadConfig();
                    HikariCanvasConfig fresh = HikariCanvasConfig.load(selfRef);
                    selfRef.applyConfig(fresh);
                    PushRateLimiter newLimiter = new PushRateLimiter(fresh.pushRateLimitConfig);
                    apiImplRef.setRateLimiter(newLimiter);
                    return fresh.pushRateLimitConfig;
                });
        // 0.5.0-P1：/canvas bench 命令族。持守护线程 executor，存字段供 onDisable shutdown。
        benchmarkSubCommand = new BenchmarkSubCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        new CanvasCommand(this, sessionManager, frameDeployer,
                                tokenService, mapPool, database, wallRepo,
                                templateRegistry, templatePreviewService, editorUrlTemplate,
                                variableSubCommand, benchmarkSubCommand, messages).build()));

        // M13：UploadHandler 需要 sessionManager / wallRepo，所以晚于它们装配
        // M16 P2.1/P2.2：还需要 imageDao + jdbi 做事务化 quota+insert+evict
        uploadHandler = new UploadHandler(getLogger(), imageStorage, imageQuota,
                imageDao, database.jdbi(),
                config.images, tokenService, sessionManager, wallRepo, auditLog);

        // 0.8-A2：.canvas 工程导入的 assets 图片摄入栈（与上传同款 magic+隔离解码+配额+落 hash）。
        // 复用上面已装配的 imageStorage / imageQuota / imageDao / wallRepo / jdbi，注入 WebServer
        // 由其内部 new ProjectImporter（复用 dispatcher 同款 push 广播）。
        this.assetIngest =
                new moe.hikari.canvas.canvasfile.AssetIngest(getLogger(), imageStorage, imageQuota,
                        imageDao, wallRepo, database.jdbi());

        // 0.4.0-P3-L：取出 ManualScheduleProvider 引用让 WebServer 在 entry 增删时通知 refreshWall
        ManualScheduleProvider manualScheduleProviderRef = (ManualScheduleProvider)
                variableProviderDaemon.registeredProviders().stream()
                        .filter(p -> p instanceof ManualScheduleProvider)
                        .findFirst().orElse(null);
        // 0.4.4：取出 RailScheduleProvider 引用让 WebServer 在 rail.wall.bind 后立即 register
        moe.hikari.canvas.variable.provider.RailScheduleProvider railScheduleProviderRef =
                (moe.hikari.canvas.variable.provider.RailScheduleProvider)
                        variableProviderDaemon.registeredProviders().stream()
                                .filter(p -> p instanceof moe.hikari.canvas.variable.provider.RailScheduleProvider)
                                .findFirst().orElse(null);
        // 2026-05-25：token 暴力枚举防御（M16 留下的 SessionRateLimiter 待办，按本期 spec
        // 命名为 TokenRateLimiter 与 SessionRateLimiter 区分开 — 后者是 op-rate，
        // 前者是 token 校验限流）
        moe.hikari.canvas.web.TokenRateLimiter tokenRateLimiter =
                new moe.hikari.canvas.web.TokenRateLimiter(config.tokenRateLimitPerMinute);
        webServer = new WebServer(getLogger(), config.host, config.port,
                tokenService, sessionManager,
                projectionThrottler, rateLimiter,
                wallRepo, frameDeployer, templateRegistry, templatePreviewService,
                templateAssetService, wallPreviewService, uploadHandler,
                templatePublisher, templateRepo, auditLog, fontRegistry, iconRegistry,
                variableStore, scheduleDao, manualScheduleProviderRef,
                railDao, railScheduleProviderRef,
                variableProviderDaemon, variableAliasDao, this,
                version,
                config.wsAuthTimeoutSeconds, config.allowedOrigins,
                tokenRateLimiter, scriptStore,
                // 0.7.0-P5-E：命令模板表惰性读 volatile config → /canvas reload 即生效，
                // 与 ActionExecutor 读模板范式一致（绝不泄 command 原文由 handler 保证）。
                () -> config().scriptsConfig.commandTemplates(),
                // 0.8-A2：.canvas 工程导入——AssetIngest + 导入限额；WebServer 内 new ProjectImporter。
                assetIngest, config.importConfig);
        webServer.start();

        // 0.6 P2：时间轴动画产帧引擎装配（docs/architecture.md §5.5 / docs/timeline.md §3）。
        // 装配点选在 webServer.start() 之后：① setAnimationTicker seam 把 ticker 转交给
        // editOpDispatcher（timeline.play/pause/seek 用），dispatcher 在 WebServer 构造时才就位；
        // ② autoRegisterAll 须晚于上方 wallRestorerInstance.restore()（启动期像素已落地）；
        // ③ 依赖项 wallRepo / canvasProjector / config / compositor 此刻均已非 null。
        // CanvasProjector 已实现 AnimationTicker.FrameRenderer（hasViewers / renderFrame），直接传。
        BufferPool bufferPool = new BufferPool();
        AnimationTicker ticker = new AnimationTicker(
                AnimationTicker.fromRepo(wallRepo), canvasProjector,
                config.timelineConfig.maxFps(), getLogger());
        this.animationTicker = ticker;
        // BufferPool 绑定到 Ticker 单线程（owner）：ticker 出帧路径经 compositor 借/还，
        // 非 owner 线程（reactive flush / restore）退化为 new BufferedImage，零行为变化。
        ticker.bindBufferPool(bufferPool);
        compositor.setBufferPool(bufferPool);
        // ProjectionThrottler 分流 gate：wall 播放期间编辑 op 的 reactive flush 退让给 ticker。
        projectionThrottler.setAnimationGate(ticker);
        // 0.6 P3：数值轨 ${var:X} 求值（rendering.md §9.5）——Ticker 在插值前按 wall resolve。
        ticker.setNumberResolver(variableInterpolator::resolveAsNumber);
        // editOpDispatcher 持 ticker 引用，timeline.play/pause/seek 三 op 用。
        webServer.setAnimationTicker(ticker);
        // SessionManager 持 ticker 引用：编辑持久化后 invalidate（缓存重载）+ session 关闭后
        // refreshAutoPlay（编辑器关了 LOOP 墙自动播）。null-guard 在 SessionManager 侧（测试零侵入）。
        sessionManager.setAnimationTicker(ticker);
        // wall 删除钩子（P2 审查确认项 #10）：/canvas delete 后立即注销播放——mapIds 已
        // releaseToFree，孤悬的播放条目会向可能被复用的 mapId 写像素（跨墙串台）。
        sessionManager.addWallDeleteHook(ticker::stopWall);
        // 启动期全库扫描：activeTimelineId 且 LOOP 的 wall 自动播（timeline.md §5.2）。
        // 排除 restore 失败墙（P2 审查确认项 #7：其 mapIds 已归还，不得注册播放）。
        int autoPlayed = ticker.autoRegisterAll(wallRestorer.failedRestoreWallIds());
        if (autoPlayed > 0) {
            getLogger().info("AnimationTicker: " + autoPlayed + " wall(s) auto-playing (LOOP)");
        }

        // 0.6 P5：时间轴触发器路由（VARIABLE_CHANGE / SCHEDULE，timeline.md §5.2）。变量变化 →
        // 播放绑定该变量的 wall 时间轴。player = ticker::play（任意线程安全，自带 entry 锁）；
        // resolver = VariableInterpolator.resolveFullName（把 trigger 配的 user/X 注入 wallId →
        // 内部形式 user:<wallId>/X 才能与变化事件 fullName 匹配，§5.2 一致性坑 R）。
        final AnimationTicker tickerForTrigger = ticker;
        TimelineTriggerRegistry triggerRegistry = new TimelineTriggerRegistry(
                AnimationTicker.fromRepo(wallRepo),
                moe.hikari.canvas.variable.VariableInterpolator::resolveFullName,
                tickerForTrigger::play,
                getLogger());
        triggerRegistry.rebuildAll();
        sessionManager.setTimelineTriggerRegistry(triggerRegistry);
        // wall 删除：注销其触发绑定（与 ticker::stopWall 同源 hook）。
        sessionManager.addWallDeleteHook(triggerRegistry::removeWall);
        final TimelineTriggerRegistry triggerRegistryRef = triggerRegistry;
        variableStore.registerChangeListener(event -> {
            // 只在"值真的变了"时触发（VALUE_SET / UPDATED / CREATED）；WALL_REFS_UPDATED / DELETED 不播。
            VariableStore.ChangeType ct = event.type();
            if (ct == VariableStore.ChangeType.VALUE_SET || ct == VariableStore.ChangeType.UPDATED
                    || ct == VariableStore.ChangeType.CREATED) {
                triggerRegistryRef.onVariableChange(event.fullName());
            }
        });
        getLogger().info("TimelineTriggerRegistry: registered (variable/schedule change → timeline play)");

        // 0.4.0 bugfix3（Bug B）：Provider 写值时主动推 state.patch 给前端 mirror。
        // EditSession op 路径走 OpResult.dirty + dispatcher 调 push.pushPatch；
        // Provider（ManualScheduleProvider / SystemVariableProvider / PapiVariableBridge）
        // 直接调 store 不走该路径——必须 listener 钩子兜底，否则前端 mirror 永远拿不到
        // Provider 自动更新的值（用户报"Schedule Manager 7 项预览全 '—'"的根因）。
        //
        // listener 内构造 OpPushCallback wrap webServer 的 pushPatch（webServer 已 start）；
        // 异步线程安全：webServer.pushPatch / sessionManager.byId 都是线程安全只读。
        final moe.hikari.canvas.web.OpPushCallback varPushCallback =
                new moe.hikari.canvas.web.OpPushCallback() {
                    @Override
                    public boolean pushSnapshot(
                            String sid, moe.hikari.canvas.state.ProjectState s) {
                        return webServer != null && webServer.pushSnapshot(sid, s);
                    }
                    @Override
                    public boolean pushPatch(
                            String sid, moe.hikari.canvas.state.StatePatch p) {
                        return webServer != null && webServer.pushPatch(sid, p);
                    }
                };
        final SessionManager sessionManagerRef = this.sessionManager;
        // 0.4.3：让 SessionManager.variableToMap 能查 userglobal owner 信息（注入到 state.patch 的
        // value），patch 收到端就能区分"我的全局 / 其他全局"。
        sessionManagerRef.setVariableStoreRef(variableStore);
        variableStore.registerChangeListener(event -> {
            // 0.4.3：路由按 fullName 前缀 — userglobal/* 全 session 广播；其他走 per-wall 路由
            String fullName = event.fullName();
            boolean isGlobal = fullName != null
                    && fullName.startsWith(VariableStore.USER_GLOBAL_NAMESPACE + "/");
            if (isGlobal) {
                sessionManagerRef.broadcastVariableChangeToAll(event, varPushCallback);
            } else {
                sessionManagerRef.broadcastVariableChangeToWall(event, varPushCallback);
            }
        });
        getLogger().info("VariableStore.ChangeListener registered (Provider→frontend mirror, "
                + "userglobal broadcasts to all sessions)");

        // 0.4.0 方案 B 自适应渲染：第二条 ChangeListener。任意变量 mutation 或 wall 引用集合变化都
        // 重新评估"该 wall 是否含高频变量"→ 给绑定该 wall 的所有 session 在 ProjectionThrottler
        // 上调 setIntervalForSession（高频 50ms / 默认 200ms）。WALL_REFS_UPDATED 是 markWallReferences
        // 后专门 fire 的事件（Compositor 渲染期变化也能触发）；其他事件类型只在 referencingWalls
        // 已有的 wall 上重评，新引用 wall 由 WALL_REFS_UPDATED 兜底。
        final VariableStore variableStoreForAdaptive = this.variableStore;
        final ProjectionThrottler throttlerRef = this.projectionThrottler;
        final SessionManager sessionManagerForAdaptive = this.sessionManager;
        final long defaultIntervalMs = config.adaptiveFps.defaultMinIntervalMs();
        final long highFreqIntervalMs = config.adaptiveFps.highFreqMinIntervalMs();
        variableStore.registerChangeListener(event -> {
            // 路由 walls 集合：event.referencingWalls 已经是 listener 关心的全部 wall
            // （WALL_REFS_UPDATED 时仅包含触发 markWallReferences 的那一个 wall）。
            java.util.Set<String> walls = event.referencingWalls();
            if (walls == null || walls.isEmpty()) return;
            for (String wallId : walls) {
                boolean highFreq = variableStoreForAdaptive.isWallHighFreq(wallId);
                long interval = highFreq ? highFreqIntervalMs : defaultIntervalMs;
                // 找该 wall 的所有活跃 session，逐个调 setIntervalForSession
                for (String sid : sessionManagerForAdaptive.liveSessionIds()) {
                    var s = sessionManagerForAdaptive.byId(sid);
                    if (s == null) continue;
                    if (!wallId.equals(s.wallId())) continue;
                    throttlerRef.setIntervalForSession(sid, interval);
                }
            }
        });
        getLogger().info("VariableStore.ChangeListener registered (adaptive-fps high-freq detection,"
                + " default=" + defaultIntervalMs + "ms high-freq=" + highFreqIntervalMs + "ms)");

        // ── 0.7.0 P2-5：脚本执行引擎全链装配（docs/scripting.md §3） ─────────────
        // 装配点说明：计划原定"scriptStore 段之后、WebServer 之前"，但 ActionExecutor 依赖
        // AnimationTicker（webServer.start() 之后才建）——与其给 executor 加 setter 注入半装态，
        // 不如把整条链后移到 ticker / varPushCallback / projectionThrottler 全部就位之后
        // （WebServer 不依赖本链，后移无副作用；侵入最小）。
        this.scriptBudget = new moe.hikari.canvas.script.engine.ScriptBudget(config.scriptsConfig);
        moe.hikari.canvas.script.engine.ConditionEvaluator scriptConditions =
                new moe.hikari.canvas.script.engine.ConditionEvaluator(getLogger(), variableStore);
        // setElementProperty 路径 A（墙开着编辑器）：标准 element.update 链 + 前端 patch +
        // 投影节流；路径 B（headless）在 Applier 内部走 WallRepo + ticker。
        final SessionManager smForScript = this.sessionManager;
        final ProjectionThrottler throttlerForScript = this.projectionThrottler;
        moe.hikari.canvas.script.engine.TickerControl tickerControl =
                moe.hikari.canvas.script.engine.TickerControl.of(ticker);
        moe.hikari.canvas.script.engine.ElementPropertyApplier propertyApplier =
                new moe.hikari.canvas.script.engine.ElementPropertyApplier(
                        new moe.hikari.canvas.script.engine
                                .ElementPropertyApplier.SessionPatchApplier() {
                            @Override
                            public moe.hikari.canvas.script.engine
                                    .ElementPropertyApplier.SessionOutcome apply(
                                    String wid, String eid, java.util.Map<String, Object> patch) {
                                return smForScript.applyScriptElementPatch(
                                        wid, eid, patch, varPushCallback, throttlerForScript);
                            }

                            @Override
                            public moe.hikari.canvas.script.engine
                                    .ElementPropertyApplier.SessionOutcome nudge(
                                    String wid, String eid, int dx, int dy) {
                                return smForScript.applyScriptElementNudge(
                                        wid, eid, dx, dy, varPushCallback, throttlerForScript);
                            }

                            @Override
                            public moe.hikari.canvas.script.engine
                                    .ElementPropertyApplier.SessionOutcome clone(
                                    String wid, String eid, int offsetX, int offsetY) {
                                return smForScript.applyScriptElementClone(
                                        wid, eid, offsetX, offsetY,
                                        varPushCallback, throttlerForScript);
                            }

                            @Override
                            public moe.hikari.canvas.script.engine
                                    .ElementPropertyApplier.SessionOutcome delete(
                                    String wid, String eid) {
                                return smForScript.applyScriptElementDelete(
                                        wid, eid, varPushCallback, throttlerForScript);
                            }

                            @Override
                            public moe.hikari.canvas.script.engine
                                    .ElementPropertyApplier.SessionOutcome reorderToEdge(
                                    String wid, String eid, String mode) {
                                return smForScript.applyScriptElementReorder(
                                        wid, eid, mode, varPushCallback, throttlerForScript);
                            }
                        },
                        wallRepo, tickerControl, getLogger());
        // 0.7.2-P2（F10）：headless 克隆路径的元素配额（路径 A 配额由 SessionManager 注入到
        // 活跃 session 的 EditSession；此处只管 headless 临时 EditSession）。
        propertyApplier.setMaxElementsPerWall(config.scriptsConfig.maxElementsPerWall());
        this.scriptPropertyApplier = propertyApplier;  // 留引用供 reload 热更
        // 0.7.0-P3 A1：命令模板表惰性读 volatile config 引用——/canvas reload 后下一次
        // runCommand 即用新模板，无需重新接线。在线玩家名供 online-player 参数校验
        // （CraftBukkit playerView 是 CopyOnWriteArrayList 视图，runner 线程迭代安全）。
        moe.hikari.canvas.script.engine.ActionExecutor actionExecutor =
                new moe.hikari.canvas.script.engine.ActionExecutor(
                        variableStore, tickerControl, propertyApplier, wallRepo,
                        this,
                        () -> config().scriptsConfig.commandTemplates(),
                        () -> Bukkit.getOnlinePlayers().stream()
                                .map(org.bukkit.entity.Player::getName)
                                .toList(),
                        auditLog, getLogger());
        this.scriptRunner = new moe.hikari.canvas.script.engine.ScriptRunner(
                scriptConditions, actionExecutor, scriptBudget, auditLog, getLogger());
        // 补间动画引擎 0.7.1：独立 SES（hikari-canvas-tween），渲临时帧（不落 DB）+ 末帧落盘。
        // maxFps = config scripts.tween.max-fps（默 60）；per-wall fps 存 ProjectState.tweenFps。
        final int tweenMaxFps = config.tweenConfig.maxFps();
        final int tweenMaxConcurrent = config.tweenConfig.maxConcurrent();
        moe.hikari.canvas.script.engine.TweenScheduler ts =
                new moe.hikari.canvas.script.engine.TweenScheduler(
                        propertyApplier, tickerControl, wallRepo,
                        System::currentTimeMillis,
                        tweenMaxConcurrent, tweenMaxFps, getLogger());
        this.tweenScheduler = ts;
        this.scriptRunner.setTweenScheduler(ts);
        // 0.7.0-P3 B2（K14）+ P3-5 修正：墙原点源——WallRepo.loadById 拿 Wall.key
        // （world 是名字字符串）→ 查 scriptWorldUuidByName 快照表换世界 UUID。
        // rebuild 触发点并不全在主线程：onEnable 启动 / wall delete hook 是主线程，
        // 但 WS 脚本 op（script.create/update 等）经 ScriptStore listener →
        // rebuildWall 跑在 Jetty 线程——异步调 Bukkit.getWorld 不安全（读
        // CraftServer.worlds 普通 LinkedHashMap）。快照表零 Bukkit 调用，任意线程
        // 安全；表本身由主线程维护（onEnable 种子 + Hub 的世界事件 handler 增删）。
        // 墙不存在 / 世界未加载返 null（Router 跳过该 near 规则 + warning；世界
        // 随后加载时 Hub 的 WorldLoadEvent → rebuildAll 自动补登记）。
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            scriptWorldUuidByName.put(w.getName(), w.getUID());
        }
        final moe.hikari.canvas.storage.WallRepo wallRepoForNear = wallRepo;
        final java.util.Map<String, java.util.UUID> worldUuidSnapshot = scriptWorldUuidByName;
        moe.hikari.canvas.script.engine.TriggerRouter.WallOriginSource wallOriginSource =
                wallId -> {
                    var wall = wallRepoForNear.loadById(wallId).orElse(null);
                    if (wall == null) return null;
                    java.util.UUID worldUid = worldUuidSnapshot.get(wall.key().world());
                    if (worldUid == null) return null;
                    return new moe.hikari.canvas.script.engine.TriggerRouter.WallOrigin(
                            worldUid, wall.key().originX(),
                            wall.key().originY(), wall.key().originZ());
                };
        moe.hikari.canvas.script.engine.TriggerRouter routerForScript =
                new moe.hikari.canvas.script.engine.TriggerRouter(
                        scriptStore, scriptRunner,
                        moe.hikari.canvas.variable.VariableInterpolator::resolveFullName,
                        wallOriginSource, getLogger());
        this.scriptTriggerRouter = routerForScript;
        // 监听接线：变量变化（K1 链深在 Router 内读 runner ThreadLocal）/ 脚本增删改（K7
        // 墙级 rebuild）/ 墙删除（清索引 + cancel timer）/ 部署完成（K9② wallReady）。
        variableStore.registerChangeListener(routerForScript::onVariableChange);
        scriptStore.addListener(routerForScript::rebuildWall);
        sessionManager.addWallDeleteHook(routerForScript::removeWall);
        sessionManager.addWallReadyHook(routerForScript::fireWallReady);
        // K9①：启动期——restore + autoRegisterAll 已完成（上方），全量建索引后对全部
        // 恢复成功的墙（全墙 − failedRestoreWallIds）各发一次 wallReady。
        routerForScript.rebuildAll();
        final java.util.Set<String> failedRestoreForScript =
                wallRestorerInstance.failedRestoreWallIds();
        java.util.List<String> scriptReadyWalls = wallRepo.loadAll().stream()
                .map(w -> w.wallId())
                .filter(id -> !failedRestoreForScript.contains(id))
                .toList();
        routerForScript.fireWallReadyAll(scriptReadyWalls);
        // 0.7.0-P3 B1（K15）+ P3-5：进服 / 击杀 / 世界增删事件入口——Hub 只转发，
        // 索引遍历 + 最新规则判定全在 Router（可单测）。MONITOR 优先级观察语义，
        // 不改事件结果。世界事件维护 scriptWorldUuidByName 快照表；WorldLoadEvent
        // 额外 rebuildAll 让后加载世界的 playerNear 规则自动补登记。
        getServer().getPluginManager().registerEvents(
                new moe.hikari.canvas.script.engine.GameEventListenerHub(
                        routerForScript, scriptWorldUuidByName,
                        routerForScript::rebuildAll, frameDeployer::wallIdOf), this);
        // 0.7.0-P3 B2（K14）：playerNear 周期采样——主线程 task 固定 2 tick 周期（启动后
        // 1s 首跑），Sampler 内部按 scripts.player-near-sample-ticks（默 10）跳帧计数；
        // 热更走 applyConfig → setSampleTicks（volatile，无需重 schedule）。Sampler 本体
        // 零 Bukkit：玩家位置 / near 条目 / 触发投递全 lambda 注入。
        moe.hikari.canvas.script.engine.PlayerNearSampler nearSampler =
                new moe.hikari.canvas.script.engine.PlayerNearSampler(
                        () -> {
                            var online = Bukkit.getOnlinePlayers();
                            if (online.isEmpty()) return java.util.List.of();
                            java.util.List<moe.hikari.canvas.script.engine.PlayerNearSampler.PlayerPos>
                                    list = new java.util.ArrayList<>(online.size());
                            for (Player p : online) {
                                org.bukkit.Location loc = p.getLocation();
                                list.add(new moe.hikari.canvas.script.engine
                                        .PlayerNearSampler.PlayerPos(
                                        p.getName(), p.getWorld().getUID(),
                                        loc.getX(), loc.getY(), loc.getZ()));
                            }
                            return list;
                        },
                        routerForScript::nearRules,
                        routerForScript::firePlayerNear,
                        getLogger(),
                        config.scriptsConfig.playerNearSampleTicks());
        this.playerNearSampler = nearSampler;
        this.playerNearTask = Bukkit.getScheduler().runTaskTimer(
                this, nearSampler::tick, 20L, 2L);
        // 0.7.0-P3 A2（K11）：script.test 异步试跑入口——dispatcher ack 立即返，轨迹经
        // callback 推 script.trace。K12：TEST 不豁免 Budget（submit 投递侧统一过闸）。
        // find 与 launch 之间规则被并发删 → 回 error step（callback 契约恰一次）。
        final moe.hikari.canvas.script.ScriptStore storeForTest = scriptStore;
        final moe.hikari.canvas.script.engine.ScriptRunner runnerForTest = scriptRunner;
        webServer.setScriptTestLauncher((wallId, ruleId, traceCallback) -> {
            java.util.Optional<moe.hikari.canvas.script.ScriptRule> r =
                    storeForTest.find(wallId, ruleId);
            if (r.isEmpty()) {
                traceCallback.accept(java.util.List.of(
                        moe.hikari.canvas.script.engine.TraceStep.error(
                                "trigger", "规则不存在: " + ruleId)));
                return;
            }
            runnerForTest.submit(wallId, r.get(),
                    new moe.hikari.canvas.script.engine.TriggerContext(
                            moe.hikari.canvas.script.engine.TriggerContext.Source.TEST,
                            0, "test"),
                    traceCallback);
        });
        getLogger().info("Script engine: runner + trigger router registered ("
                + scriptReadyWalls.size() + " wall(s) wallReady fired)");

        // M15.3 P0-24：MapPool 泄漏检测周期任务（5 分钟）。idcounts.dat 防膨胀的最后防线。
        // 同 tokenPurgeTask 模式：异步周期跑；扫所有 RESERVED 找 owner 已不在 walls 表的强制 FREE。
        mapPoolLeakTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                () -> {
                    // 0.4.10 P3-11：onDisable 已置 shuttingDown 时 short-circuit——避免本 run 在
                    // database.close() 之后才读 wallRepo.loadAll() / mapPool.detectLeaks（走 DB）
                    // 触发 use-after-close。Bukkit cancel() 不等 in-flight run，这是唯一稳的兜底。
                    if (shuttingDown || database == null) return;
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
        } catch (Throwable t) {
            // 0.4.10 P2-44：装配中途失败——逆序清理已就位资源后 rethrow。Bukkit 在 onEnable 抛异常
            // 时只 catch+log 并把插件留在 disabled 态（不调 onDisable），故必须在此主动释放
            // HikariDataSource / daemon 线程池 / Jetty / executor 等非 Bukkit 托管资源。
            getLogger().log(java.util.logging.Level.SEVERE,
                    "onEnable failed mid-assembly; cleaning up partially-initialized resources", t);
            try {
                cleanupResources();
            } catch (Throwable cleanupErr) {
                getLogger().log(java.util.logging.Level.SEVERE,
                        "cleanup after onEnable failure also threw", cleanupErr);
            }
            // rethrow 让 Bukkit 标记插件 disabled（IllegalStateException 包装受检异常）。
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error err) throw err;
            throw new IllegalStateException("HikariCanvas onEnable failed", t);
        }
    }

    /**
     * 接收 {@code /canvas reload config} 的新配置。M7 v1 只更新引用 + log；多数字段（host/port/
     * 池容量/超时等）已传给具体服务，必须重启才能真正生效。提示由命令侧打。
     *
     * <p>未来若有需要做 hot-apply，可在此扩展：给 TokenService/SessionReaper/MapPool 各加 setter。</p>
     *
     * <p>P3-90：不需要 {@code synchronized}——{@link #config} 字段已是 {@code volatile}，
     * 单引用赋值的可见性由 volatile write 充分保证，且无并发写者（仅命令线程触发），无需互斥。
     * 若未来扩展为多字段一致性更新可回头加锁。</p>
     */
    public void applyConfig(HikariCanvasConfig fresh) {
        this.config = fresh;
        // 0.7.0-P1-7b #4：脚本配额热更（只影响后续 create，不裁剪已有——ScriptStore javadoc）
        if (scriptStore != null) {
            scriptStore.setMaxRulesPerWall(fresh.scriptsConfig.maxRulesPerWall());
        }
        // 0.7.0-P2-5：脚本执行预算三闸热更（已开的 1s 窗不重置，下个窗起生效——ScriptBudget javadoc）
        if (scriptBudget != null) {
            scriptBudget.applyConfig(fresh.scriptsConfig);
        }
        // 0.7.0-P3 B2：playerNear 采样间隔热更（volatile 跳帧计数，无需重 schedule）
        if (playerNearSampler != null) {
            playerNearSampler.setSampleTicks(fresh.scriptsConfig.playerNearSampleTicks());
        }
        // 0.7.2-P2 F10：脚本克隆元素配额热更——路径 A（活跃 session 新建的 EditSession）经
        // SessionManager 透传、headless 临时 EditSession 经 ElementPropertyApplier。两处都 volatile，
        // /canvas reload 后下一次克隆即生效（已开的 session 内已注入的旧值不变，下次 open 起新值）。
        if (sessionManager != null) {
            sessionManager.setMaxElementsPerWall(fresh.scriptsConfig.maxElementsPerWall());
        }
        if (scriptPropertyApplier != null) {
            scriptPropertyApplier.setMaxElementsPerWall(fresh.scriptsConfig.maxElementsPerWall());
        }
        // 0.8.2 i18n：热重载时重扫外部 lang/ 并更新默认语言。
        if (messages != null) {
            messages.reload(getDataFolder().toPath().resolve("lang"));
            messages.setDefaultLocale(fresh.i18nConfig.defaultLocale());
        }
        getLogger().info("Config refreshed (most fields need restart): " + fresh.summary());
    }

    /** 供命令侧用；返回 null 表示插件还没 onEnable。 */
    public HikariCanvasConfig config() { return config; }

    /** 0.8.2 i18n：多语言消息注册表；onEnable 完成前可能返 null。 */
    public moe.hikari.canvas.i18n.Messages messages() { return messages; }

    /** 0.4.0-P1-A：供 B / C / D / E 任务取 VariableStore 单例。 */
    public VariableStore getVariableStore() { return variableStore; }

    /**
     * 0.4.0-P4-Q：外部插件获取 HikariCanvasAPI 的入口 A（需 import HikariCanvas 主类）。
     *
     * <p>推荐入口 B：{@code Bukkit.getServicesManager().load(HikariCanvasAPI.class)}
     * （零编译耦合，详见 {@link HikariCanvasAPI} javadoc）。</p>
     *
     * @return API 实现；插件 onEnable 完成前可能返 null
     */
    public HikariCanvasAPI getAPI() { return apiImpl; }

    @Override
    public void onDisable() {
        cleanupResources();
        getLogger().info("HikariCanvas disabled");
    }

    /**
     * 0.4.10 P2-44 / P2-50：幂等资源清理。被 {@link #onDisable} 与 {@link #onEnable} 装配失败的
     * catch 共用——避免清理逻辑两处漂移（fixApproach 推荐路径）。每步走 {@link #closeQuietly}
     * 独立兜异常 + 全字段 null-guard，重复调用安全（已 null 的步骤跳过）。
     *
     * <p>顺序固化：先置 shuttingDown + cancel 异步周期任务 → daemon → reaper → webServer →
     * upload → imageStorage → database.close → 恢复 IIORegistry → PacketEvents.terminate。
     * database.close 必须在引用 DB 的任务 / daemon 之后。</p>
     */
    private void cleanupResources() {
        // 0.4.10 P3-11：第一步置 shuttingDown，让 in-flight 的 mapPoolLeakTask run 在下次入口检查
        // 时 short-circuit，避免它在 database.close() 之后才读 DB（use-after-close）。
        shuttingDown = true;
        // 0.4.10 P3-11：先 cancel 异步周期任务，再关 DB（顺序保证 cancel 后不会再有新 run 排进
        // 队列触发 DB 访问）。Bukkit cancel() 不等 in-flight，shuttingDown flag 兜住已入队的 run。
        if (tokenPurgeTask != null) {
            closeQuietly("tokenPurgeTask.cancel", tokenPurgeTask::cancel);
            tokenPurgeTask = null;
        }
        if (mapPoolLeakTask != null) {
            closeQuietly("mapPoolLeakTask.cancel", mapPoolLeakTask::cancel);
            mapPoolLeakTask = null;
        }
        // 0.7.0-P3 B2：playerNear 采样是主线程同步任务，onDisable 也在主线程——cancel 后
        // 不可能再有 in-flight tick；放 Router shutdown 之前停掉新触发来源。
        if (playerNearTask != null) {
            closeQuietly("playerNearTask.cancel", playerNearTask::cancel);
            playerNearTask = null;
            playerNearSampler = null;
        }
        // 0.4.10 P2-50：每个资源 shutdown 步骤独立 try/catch（closeQuietly）——一个抛异常不
        // 跳过后续释放，HikariDataSource / executor 等非 Bukkit 托管资源必须保证关到。
        // 0.4.0-P1-E：先停 provider daemon，让守护线程池 awaitTermination 完成 + provider
        // shutdown 钩子释放资源；放最前是因为 daemon 内部 refresh task 可能引用 store / DB。
        if (variableProviderDaemon != null) {
            closeQuietly("variableProviderDaemon.shutdown", variableProviderDaemon::shutdown);
            variableProviderDaemon = null;
        }
        // 0.7.0-P2-5：停脚本引擎——先 Router（cancel 全部 timer + 关 hikari-script-trigger
        // 调度线程，停掉新投递），再 Runner（关 hikari-script-runner 执行队列）。须早于
        // animationTicker / database 关停：脚本动作会触碰 ticker（playTimeline）与 DB
        // （setVariable 持久化 / persistWall）。
        if (scriptTriggerRouter != null) {
            closeQuietly("scriptTriggerRouter.shutdown", scriptTriggerRouter::shutdown);
            scriptTriggerRouter = null;
        }
        if (scriptRunner != null) {
            closeQuietly("scriptRunner.shutdown", scriptRunner::shutdown);
            scriptRunner = null;
        }
        // 补间动画引擎 P2：scriptRunner 停后、animationTicker 停前关停（补间依赖 ticker.renderStatic）。
        if (tweenScheduler != null) {
            closeQuietly("tweenScheduler.shutdown", tweenScheduler::shutdown);
            tweenScheduler = null;
        }
        // 0.6 P2：停时间轴产帧引擎（单线程 daemon awaitTermination）。与 variableProviderDaemon
        // 同段关停——两者都是引用 wallRepo / DB 的 daemon，须早于下方 database.close。
        if (animationTicker != null) {
            closeQuietly("animationTicker.shutdown", animationTicker::shutdown);
            animationTicker = null;
        }
        if (sessionReaper != null) {
            closeQuietly("sessionReaper.stop", sessionReaper::stop);
            sessionReaper = null;
        }
        if (webServer != null) {
            closeQuietly("webServer.stop", webServer::stop);
            webServer = null;
        }
        if (uploadHandler != null) {
            closeQuietly("uploadHandler.shutdown", uploadHandler::shutdown);
            uploadHandler = null;
        }
        // 0.8-A2：停 .canvas 导入 assets 摄入栈的 daemon 解码线程池（shutdownNow 幂等）。
        if (assetIngest != null) {
            closeQuietly("assetIngest.shutdown", assetIngest::shutdown);
            assetIngest = null;
        }
        if (imageStorage != null) {
            closeQuietly("imageStorage.shutdown", imageStorage::shutdown);
            imageStorage = null;
        }
        // 0.5.0-P1：关停 /canvas bench 守护线程 executor（shutdownNow 中断 in-flight 压测）。
        if (benchmarkSubCommand != null) {
            closeQuietly("benchmarkSubCommand.shutdown", benchmarkSubCommand::shutdown);
            benchmarkSubCommand = null;
        }
        // 0.4.10 P3-29：停字体 metrics 后台 worker（中断 + join）+ 清 FontMetricsTable 内存表，
        // 防（热）卸载后 worker 线程泄漏 / 旧 metrics 残留致下次 onEnable 双端布局不一致。
        if (fontRegistry != null) {
            closeQuietly("fontRegistry.shutdownMetricsWorker", fontRegistry::shutdownMetricsWorker);
        }
        closeQuietly("FontMetricsTable.clear", moe.hikari.canvas.render.FontMetricsTable::clear);
        // database.close() 必须在异步任务 cancel + daemon shutdown 之后（它们可能引用 DB）。
        if (database != null) {
            closeQuietly("database.close", database::close);
            database = null;
        }
        // 0.4.10 P2-82：恢复 onEnable 从进程级 IIORegistry 注销的 ImageReaderSpi——否则同 JVM
        // 别的插件读 GIF/BMP/TIFF 在本插件（热）卸载后永久失败。registerServiceProvider 即重新
        // 可用（ordering 不还原无妨）。逐个 try/catch 防一个失败拖垮后续 + PacketEvents.terminate。
        if (!deregisteredImageReaders.isEmpty()) {
            try {
                javax.imageio.spi.IIORegistry registry =
                        javax.imageio.spi.IIORegistry.getDefaultInstance();
                int restored = 0;
                for (javax.imageio.spi.ImageReaderSpi spi : deregisteredImageReaders) {
                    try {
                        registry.registerServiceProvider(spi);
                        restored++;
                    } catch (Exception e) {
                        getLogger().log(java.util.logging.Level.WARNING,
                                "IIORegistry: failed to restore reader " + spi.getClass().getName(), e);
                    }
                }
                getLogger().info("IIORegistry: restored " + restored + " image reader(s)");
            } catch (Exception e) {
                getLogger().log(java.util.logging.Level.WARNING,
                        "IIORegistry restore failed (non-fatal)", e);
            }
            deregisteredImageReaders.clear();
        }
        try {
            PacketEvents.getAPI().terminate();
        } catch (Exception ignored) {
            // terminate 在未 init 时可能抛；M1 不关心
        }
    }

    /**
     * 0.4.10 P2-50：onDisable / onEnable-fail 用——执行一个 shutdown 动作，捕获并 WARNING 记录任何
     * {@link Throwable}，保证后续资源释放步骤不被前面的异常跳过。
     *
     * @param name   动作名（仅用于日志定位）
     * @param action 关闭动作（如 {@code database::close}）
     */
    private void closeQuietly(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            getLogger().log(java.util.logging.Level.WARNING,
                    "cleanup: " + name + " failed (continuing)", t);
        }
    }

}
