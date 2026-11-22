package ac.haru.hikaricanvas;

import ac.haru.hikaricanvas.api.HikariCanvasAPI;
import ac.haru.hikaricanvas.canvasfile.AssetIngest;
import ac.haru.hikaricanvas.command.BenchmarkSubCommand;
import ac.haru.hikaricanvas.command.CanvasCommand;
import ac.haru.hikaricanvas.command.DiagnosticsSubCommand;
import ac.haru.hikaricanvas.command.VariableSubCommand;
import ac.haru.hikaricanvas.deploy.FrameDeployer;
import ac.haru.hikaricanvas.deploy.FrameProtectionListener;
import ac.haru.hikaricanvas.deploy.MapPacketSender;
import ac.haru.hikaricanvas.deploy.WallResolver;
import ac.haru.hikaricanvas.i18n.Messages;
import ac.haru.hikaricanvas.image.ImageQuotaService;
import ac.haru.hikaricanvas.image.ImageStorage;
import ac.haru.hikaricanvas.image.UploadHandler;
import ac.haru.hikaricanvas.pool.MapPool;
import ac.haru.hikaricanvas.render.AnimationTicker;
import ac.haru.hikaricanvas.render.BufferPool;
import ac.haru.hikaricanvas.render.CanvasCompositor;
import ac.haru.hikaricanvas.render.CanvasProjector;
import ac.haru.hikaricanvas.render.FontMetricsTable;
import ac.haru.hikaricanvas.render.FontRegistry;
import ac.haru.hikaricanvas.render.HikariCanvasRenderer;
import ac.haru.hikaricanvas.render.IconRegistry;
import ac.haru.hikaricanvas.render.PaletteLut;
import ac.haru.hikaricanvas.render.PlaceholderRenderer;
import ac.haru.hikaricanvas.render.ProjectionThrottler;
import ac.haru.hikaricanvas.render.TimelineTriggerRegistry;
import ac.haru.hikaricanvas.render.WallRestorer;
import ac.haru.hikaricanvas.script.ScriptRule;
import ac.haru.hikaricanvas.script.ScriptStore;
import ac.haru.hikaricanvas.script.engine.ActionExecutor;
import ac.haru.hikaricanvas.script.engine.ConditionEvaluator;
import ac.haru.hikaricanvas.script.engine.ElementPropertyApplier;
import ac.haru.hikaricanvas.script.engine.GameEventListenerHub;
import ac.haru.hikaricanvas.script.engine.PlayerNearSampler;
import ac.haru.hikaricanvas.script.engine.ScriptBudget;
import ac.haru.hikaricanvas.script.engine.ScriptRunner;
import ac.haru.hikaricanvas.script.engine.TickerControl;
import ac.haru.hikaricanvas.script.engine.TraceStep;
import ac.haru.hikaricanvas.script.engine.TriggerContext;
import ac.haru.hikaricanvas.script.engine.TriggerRouter;
import ac.haru.hikaricanvas.script.engine.TweenScheduler;
import ac.haru.hikaricanvas.session.SessionManager;
import ac.haru.hikaricanvas.session.SessionRateLimiter;
import ac.haru.hikaricanvas.session.SessionReaper;
import ac.haru.hikaricanvas.session.TokenService;
import ac.haru.hikaricanvas.session.WandListener;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.StatePatch;
import ac.haru.hikaricanvas.storage.AuditLog;
import ac.haru.hikaricanvas.storage.BackupReaper;
import ac.haru.hikaricanvas.storage.Database;
import ac.haru.hikaricanvas.storage.ImageUploadDao;
import ac.haru.hikaricanvas.storage.MigrationRunner;
import ac.haru.hikaricanvas.storage.RailDao;
import ac.haru.hikaricanvas.storage.ScheduleDao;
import ac.haru.hikaricanvas.storage.ScriptDao;
import ac.haru.hikaricanvas.storage.TemplateRepo;
import ac.haru.hikaricanvas.storage.UserGlobalVariableDao;
import ac.haru.hikaricanvas.storage.UserVariableDao;
import ac.haru.hikaricanvas.storage.VariableAliasDao;
import ac.haru.hikaricanvas.storage.WallRepo;
import ac.haru.hikaricanvas.template.TemplatePublisher;
import ac.haru.hikaricanvas.template.TemplateRegistry;
import ac.haru.hikaricanvas.template.preview.TemplatePreviewService;
import ac.haru.hikaricanvas.template.preview.WallPreviewService;
import ac.haru.hikaricanvas.variable.VariableInterpolator;
import ac.haru.hikaricanvas.variable.VariableStore;
import ac.haru.hikaricanvas.variable.plugin.HikariCanvasAPIImpl;
import ac.haru.hikaricanvas.variable.plugin.PluginCleanupListener;
import ac.haru.hikaricanvas.variable.plugin.PluginNamespaceRegistry;
import ac.haru.hikaricanvas.variable.plugin.PushRateLimiter;
import ac.haru.hikaricanvas.variable.provider.ManualScheduleProvider;
import ac.haru.hikaricanvas.variable.provider.ProviderBootstrap;
import ac.haru.hikaricanvas.variable.provider.RailScheduleProvider;
import ac.haru.hikaricanvas.variable.provider.VariableProviderDaemon;
import ac.haru.hikaricanvas.web.OpPushCallback;
import ac.haru.hikaricanvas.web.TokenRateLimiter;
import ac.haru.hikaricanvas.web.WebServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

@SuppressWarnings("UnstableApiUsage") // Paper Lifecycle API 标记为 experimental 但稳定可用
public final class HikariCanvas extends JavaPlugin {

    private Database database;
    private AuditLog auditLog;
    private TokenService tokenService;
    // onDisable 置 true。tokenPurgeTask / mapPoolLeakTask 是 Bukkit 异步周期任务，
    // cancel() 不等待 in-flight run 结束——若一次 run 正在跑而 onDisable 已 database.close()，
    // 任务体读 wallRepo/mapPool（走 DB）会 use-after-close。任务体入口检查此 flag 提前 short-circuit。
    private volatile boolean shuttingDown = false;
    private BukkitTask tokenPurgeTask;
    // MapPool 泄漏检测周期任务（5 分钟）。idcounts.dat 防膨胀的最后防线。
    private BukkitTask mapPoolLeakTask;
    private MapPool mapPool;
    private WallResolver wallResolver;
    // daemon 线程（VariableStore wallDirtyCallback / ChangeListener、
    // mapPoolLeakTask 异步任务）读这些单例字段；onEnable 在主线程写。无 volatile 时存在 JMM
    // 可见性缺口（daemon 可能读到 null 或半构造引用）。volatile 写建立 happens-before，
    // 与 config 字段（已 volatile）一致。
    private volatile SessionManager sessionManager;
    private SessionReaper sessionReaper;
    private WallRepo wallRepo;
    /** 启动期 wall 恢复器，wand listener 用它查"restore 失败"白名单。 */
    private WallRestorer wallRestorer;
    private volatile WebServer webServer;
    private MapPacketSender mapPacketSender;
    private FrameDeployer frameDeployer;
    private HikariCanvasRenderer canvasRenderer;
    private volatile CanvasProjector canvasProjector;
    private volatile ProjectionThrottler projectionThrottler;
    // 时间轴动画产帧引擎（单线程 daemon + BufferPool 池化）。装配在 webServer 之后
    // （setAnimationTicker seam 需 dispatcher 就位）；cleanupResources 与 variableProviderDaemon
    // 同段关停（两者都是引用 DB 的 daemon，须早于 database.close）。
    private volatile AnimationTicker animationTicker;
    private SessionRateLimiter rateLimiter;
    private FontRegistry fontRegistry;
    /** 矢量图标注册表（FA Free + 用户 SVG）。 */
    private IconRegistry iconRegistry;
    private PaletteLut paletteLut;
    private TemplateRegistry templateRegistry;
    private TemplatePreviewService templatePreviewService;
    private WallPreviewService wallPreviewService;
    private ImageStorage imageStorage;
    private UploadHandler uploadHandler;
    // .canvas 工程导入 assets 摄入栈；持 daemon 单线程解码器，onDisable 须 shutdown。
    private AssetIngest assetIngest;
    private TemplateRepo templateRepo;
    private TemplatePublisher templatePublisher;
    // 变量系统底座（VariableStore + user_variables 持久化）。
    private VariableStore variableStore;
    // 异步 Provider 调度框架（守护线程 + 定时 refresh）。Provider 由
    // ProviderBootstrap.initialize 注册（SystemVariableProvider / PapiVariableBridge 等）。
    private VariableProviderDaemon variableProviderDaemon;
    // 兜底列车时刻表 DAO。WebServer schedule.* op + ManualScheduleProvider 共享。
    private ScheduleDao scheduleDao;
    // 变量别名 DAO（per-wall fullName → alias 字符串映射）。WebServer 注入 dispatcher
    // + ready payload；不参与渲染。
    private VariableAliasDao variableAliasDao;
    // 全局用户变量 DAO（userglobal/* namespace；name 全服唯一）。装配后注入
    // VariableStore.configureUserGlobal —— EditSession.createGlobalVariable 路径走它持久化。
    private UserGlobalVariableDao userGlobalVariableDao;
    // 铁路网络 DAO（5 表统一 CRUD）。WebServer + RailScheduleProvider 共享。
    private RailDao railDao;
    // 墙脚本内存镜像（wall_scripts 持久化）。WebServer script.* op +
    // ready payload 注入；ScriptRunner 引擎也走它。
    private ScriptStore scriptStore;
    // 脚本执行引擎三件（装配在 AnimationTicker 之后——ActionExecutor 依赖 ticker）。
    // budget 留引用供 /canvas reload 热更（applyConfig）；runner / router 是 daemon 类资源，
    // onDisable 须 shutdown（router 先关停 timer 投递，runner 再关执行队列）。
    private ScriptBudget scriptBudget;
    private ScriptRunner scriptRunner;
    private TriggerRouter scriptTriggerRouter;
    // 留引用供 /canvas reload 热更 headless 克隆元素配额（applyConfig）。
    private ElementPropertyApplier scriptPropertyApplier;
    // 补间动画引擎：独立单线程 SES，渲临时帧（不落 DB）+ 末帧 applyMany 落盘。
    // 须在 scriptRunner.shutdown() 之后、animationTicker.shutdown() 之前关停。
    private TweenScheduler tweenScheduler;
    // playerNear 周期采样器（主线程 task，2 tick 底层周期 +
    // volatile 跳帧计数；applyConfig 热更采样间隔）。
    private PlayerNearSampler playerNearSampler;
    private BukkitTask playerNearTask;
    // 世界名 → UUID 快照表。playerNear 原点解析（originSource）可能在
    // 任意线程跑（WS 脚本 op → ScriptStore listener → rebuildWall 在 Jetty 线程），
    // 不能异步调 Bukkit.getWorld（读 CraftServer.worlds 普通 LinkedHashMap，官方不
    // 保证线程安全）。onEnable 用 Bukkit.getWorlds() 全量种子；之后由
    // GameEventListenerHub 的 WorldLoad/WorldUnloadEvent handler（主线程）增量维护。
    private final ConcurrentHashMap<String, UUID> scriptWorldUuidByName = new ConcurrentHashMap<>();
    // 外部插件 namespace 注册表 + Push API impl。
    // onEnable 实例化 + Bukkit.getServicesManager().register（让外部插件
    // 通过 ServicesManager.load(HikariCanvasAPI.class) 零编译耦合拿到 API）。
    // PluginCleanupListener 监听 PluginDisableEvent，外部插件 disable 时立即移除
    // namespace + 30s 后清 store 变量。
    private PluginNamespaceRegistry pluginNamespaceRegistry;
    private HikariCanvasAPIImpl apiImpl;
    /** /canvas bench 命令族；持守护线程 executor，onDisable 须 shutdown。 */
    private BenchmarkSubCommand benchmarkSubCommand;
    private volatile HikariCanvasConfig config;
    // 多语言消息注册表（jar 内置 lang/ + dataFolder/lang/ 外部覆盖）。
    private Messages messages;
    // onEnable 从 JVM 全局 IIORegistry 注销的 ImageReaderSpi 实例列表。
    // onDisable 逐个 registerServiceProvider 恢复——避免本插件（热）卸载后别的插件
    // ImageIO.read GIF/BMP/TIFF 永久失败（IIORegistry 是进程级共享单例）。
    private final List<ImageReaderSpi> deregisteredImageReaders = new ArrayList<>();

    @Override
    public void onEnable() {
        // 整段装配包进 try/catch(Throwable)。任一步失败（缺 palette.json /
        // migration 失败 / schema 损坏 / 磁盘问题）时，catch 逆序清理已就位资源
        // （HikariDataSource 连接池 + housekeeper 线程 / daemon 线程池 / WebServer Jetty /
        // executor 等非 Bukkit 托管资源），再 rethrow 让 Bukkit 标记插件 disabled。
        // 复用 cleanupResources()（与 onDisable 同一幂等清理），避免清理逻辑两处漂移。
        try {
        // 重置 shuttingDown——上一次 onEnable 失败走 cleanupResources 会置 true，
        // PlugMan 重新 enable 时若不复位会让 mapPoolLeakTask 入口永久 short-circuit。
        shuttingDown = false;

        // IIORegistry 防御 — 注销已知有 CVE 历史 / 不需要的 ImageIO reader。
        // 我们 ImageStorage 只用 PNG / JPEG（"webp" 保留在白名单里只是占位——标准 JDK 无
        // WebP reader，需第三方依赖才会出现；当前无该依赖故此项不匹配任何 SPI）；其他格式
        // （TIFF、BMP、GIF）attack surface 大且未用，启动期注销避免 ImageIO.read 触发它们的
        // 解码循环（Thread.interrupt 大多对 ImageIO 内部循环无效，唯一稳的防线是不让它们注册）。
        //
        // IIORegistry 是进程级 JVM 共享单例——注销会影响同 JVM 别的插件。
        // 把被注销的 SPI 实例存到字段，onDisable 逐个 registerServiceProvider 恢复，避免本插件
        // （热）卸载后别的依赖 JDK ImageIO 读 GIF/BMP/TIFF 的插件永久失败。
        try {
            IIORegistry registry = IIORegistry.getDefaultInstance();
            Iterator<ImageReaderSpi> readers = registry.getServiceProviders(
                    ImageReaderSpi.class, false);
            List<ImageReaderSpi> toRemove = new ArrayList<>();
            while (readers.hasNext()) {
                ImageReaderSpi spi = readers.next();
                String[] names = spi.getFormatNames();
                boolean keep = false;
                for (String n : names) {
                    String lc = n.toLowerCase(Locale.ROOT);
                    if (lc.equals("png") || lc.equals("jpeg") || lc.equals("jpg") || lc.equals("webp")) {
                        keep = true;
                        break;
                    }
                }
                if (!keep) toRemove.add(spi);
            }
            for (var spi : toRemove) registry.deregisterServiceProvider(spi);
            deregisteredImageReaders.clear();
            deregisteredImageReaders.addAll(toRemove);  // 留给 onDisable 恢复
            getLogger().info("IIORegistry: deregistered " + toRemove.size()
                    + " unused image readers (kept PNG/JPEG)");
        } catch (Exception e) {
            getLogger().log(Level.WARNING,
                    "IIORegistry deregistration failed (non-fatal)", e);
        }

        // config.yml。首次启动把 jar 内默认配置拷到 dataFolder
        saveDefaultConfig();
        config = HikariCanvasConfig.load(this);
        getLogger().info("Config loaded: " + config.summary());

        // 持久化：按 docs/data-model.md §2.1 在 plugins/HikariCanvas/data.db
        database = new Database(getLogger(), getDataFolder().toPath().resolve("data.db"));
        // migration 前 WAL 安全自动备份（默认开，见 data-model.md §6.6.2）。
        new MigrationRunner(database.jdbi(), getLogger(),
                config.databaseAutoBackup,
                getDataFolder().toPath().resolve("data.db")).run();
        // 备份保留策略：启动期清理超过保留天数的 migration 备份（BackupReaper）。
        new BackupReaper(getLogger()).reap(
                getDataFolder().toPath(), "data.db", config.backupRetentionDays,
                System.currentTimeMillis());
        // AuditLog 接 logger，让 DB 失败时能 fallback 到 server log。
        auditLog = new AuditLog(database.jdbi(), getLogger(), config.auditRetentionDays);

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
        // 多世界 per-world initial（可选 config）。defaultWorld 用于总数补齐 +
        // 旧池行 world 信息缺失时的 fallback。未配置 per-world 的 world 走 on-demand 扩容。
        mapPool.initialize(Bukkit.getWorlds().get(0), config.mapPoolPerWorldInitial);

        // 墙面识别 + 会话管理（单面招牌地图数上限 1~64，越界钳位并打 warning；plugin 提供 PDC namespace）
        int canvasMaxMaps = readCanvasMaxMaps();
        wallResolver = new WallResolver(canvasMaxMaps, this);
        wallRepo = new WallRepo(getLogger(), database.jdbi());

        // 把 protocol_version < 2 的老 wall 升到 v2 layered 形态。
        // 失败的行保留 v1，下次启动重试 + 运行期 /canvas open 时 lazy 兼容。
        WallRepo.MigrationStats wallV2Stats = wallRepo.migrateAllToV2();
        if (wallV2Stats.scanned() > 0) {
            getLogger().info("ProjectState v1→v2 migration: scanned="
                    + wallV2Stats.scanned() + " migrated=" + wallV2Stats.migrated()
                    + " failed=" + wallV2Stats.failed());
        }

        sessionManager = new SessionManager(getLogger(), mapPool, wallResolver, auditLog, wallRepo, canvasRenderer);
        sessionManager.setMaxImagesPerWall(config.images.maxPerWall());
        sessionManager.setMaxElementsPerWall(config.scriptsConfig.maxElementsPerWall());
        sessionManager.setTimelineFpsLimits(
                config.timelineConfig.defaultFps(), config.timelineConfig.maxFps());

        PlaceholderRenderer placeholderRenderer = new PlaceholderRenderer();
        frameDeployer = new FrameDeployer(this, placeholderRenderer, canvasRenderer);

        // 字体注册表。先加载内置（jar 里 /fonts/）再扫外部目录（允许玩家自定义）
        fontRegistry = new FontRegistry(getLogger());
        fontRegistry.loadBuiltIn();
        fontRegistry.loadExternal(getDataFolder().toPath().resolve("fonts"));
        getLogger().info("FontRegistry: " + fontRegistry.size() + " font(s) ready");

        // 多语言消息注册表。jar 内置 lang/ → 首次启动拷一份到 dataFolder/lang/ 供服主改；
        // 再 loadExternal 按键合并回来（改过的句子用服主的，没改的用内置，见 Messages.loadExternal）。
        messages = new Messages(getLogger());
        saveLangIfAbsent("lang/en_us.yml");
        saveLangIfAbsent("lang/zh_cn.yml");
        messages.loadBuiltIn();
        messages.loadExternal(getDataFolder().toPath().resolve("lang"));
        messages.setDefaultLocale(config.i18nConfig.defaultLocale());
        getLogger().info("Messages: " + messages.size() + " language(s) ready");

        // 矢量图标注册表。jar 内置 Font Awesome Free + 用户 plugins/HikariCanvas/icons/*.svg
        iconRegistry = new IconRegistry(getLogger());
        iconRegistry.loadBuiltIn();
        iconRegistry.loadExternal(getDataFolder().toPath().resolve("icons"));
        getLogger().info("IconRegistry: " + iconRegistry.size() + " icon(s) ready");

        // 调色板 LUT（32³ Lab）。启动期一次性构建 ~32 KiB，常驻
        try {
            paletteLut = PaletteLut.loadFromClasspath("/palette.json");
            getLogger().info("PaletteLut: " + paletteLut.size() + " entries loaded");
        } catch (IOException e) {
            throw new IllegalStateException("failed to load palette.json from classpath; "
                    + "did ./gradlew generatePalette run?", e);
        }

        // 图片上传 + 存储 + 配额。先于 compositor 装配，让 WallRestorer 重启
        // restore 时 ImageElement 也能正确加载（否则一次启动期会画占位）。
        ImageUploadDao imageDao = new ImageUploadDao(getLogger(), database.jdbi());
        imageStorage = new ImageStorage(getLogger(), getDataFolder().toPath(), imageDao);
        ImageQuotaService imageQuota = new ImageQuotaService(imageDao, config.images);

        // 编辑 op 成功后把受影响 mapIds 重绘。
        // Compositor = RGBA 大图 rasterize + palette 量化切片
        CanvasCompositor compositor = new CanvasCompositor(paletteLut, fontRegistry,
                iconRegistry, getLogger());
        compositor.setImageLoader(imageStorage::load);
        // 构造期注入 mapPacketSender + wallRepo，让 projector 在 pushPacketsEnabled 开启时主动向
        // chunk-loaded viewer 推送 ClientboundMapItemDataPacket。
        mapPacketSender = new MapPacketSender();
        canvasProjector = new CanvasProjector(canvasRenderer, compositor, placeholderRenderer,
                getLogger(), mapPacketSender, wallRepo);

        // 在此处实例化 WallRestorer 供后续 wand listener 与 ticker 接线；实际 restore() 调用推迟到
        // compositor.setVariableSupport 与 ProviderBootstrap.initialize 之后执行，确保启动期重绘时
        // ${var:...} 占位符已被解析为真实值而非字面字符串。
        WallRestorer wallRestorerInstance = new WallRestorer(getLogger(), wallRepo, mapPool,
                canvasRenderer, compositor, placeholderRenderer);
        this.wallRestorer = wallRestorerInstance;

        // 模板注册表：jar 内 /templates/*.canvas + plugins/HikariCanvas/templates/*.canvas +
        // user-templates/<uuid>/*.canvas（创意工坊）
        templateRegistry = new TemplateRegistry(
                getLogger(), HikariCanvas.class,
                getDataFolder().toPath().resolve("templates"),
                getDataFolder().toPath().resolve("user-templates"));
        if (config.autoReloadTemplatesOnStartup) {
            templateRegistry.reload();
        }
        // 变量系统底座。DAO + Store + 启动期 loadFromDb。
        UserVariableDao userVariableDao = new UserVariableDao(getLogger(), database.jdbi());
        variableStore = new VariableStore(userVariableDao,
                wallId -> {
                    if (sessionManager == null || projectionThrottler == null) return;
                    // wall 由 AnimationTicker 主动产帧时整条变量
                    // 重画路径退让——变量在 rasterize 期逐帧 resolve（cached 值），Ticker 的
                    // 下一帧自然带上新值 + per-map diff 检测推送；这里再走 projectByWall
                    // 会与 Ticker 双写同一 mapId（帧交错 + diff 基准失步）。
                    AnimationTicker t = animationTicker;
                    if (t != null && t.isWallAnimating(wallId)) return;
                    boolean handled = sessionManager.submitFullCanvasDirtyByWallAndReport(
                            wallId, projectionThrottler);
                    // 无活跃 editor session 时走 deploy-only refresh（hop 主线程避免与 MapView 写入竞态）。
                    if (!handled && canvasProjector != null) {
                        Bukkit.getScheduler().runTask(this,
                                () -> canvasProjector.projectByWall(wallId));
                    }
                });
        variableStore.loadFromDb();
        // 全局用户变量 DAO + 配额装配（config.yml `dynamic.variables.*`）。
        userGlobalVariableDao = new UserGlobalVariableDao(getLogger(), database.jdbi());
        variableStore.configureUserGlobal(userGlobalVariableDao,
                config.userGlobalMaxPerOwner, config.userGlobalMaxTotal);
        variableStore.loadGlobalsFromDb();
        getLogger().info("VariableStore: " + variableStore.size()
                + " variable(s) loaded (user + userglobal)");
        // Compositor 接入变量替换与倒排索引联动（早于下方 wallRestorerInstance.restore()，
        // 使启动期恢复与后续 CanvasProjector / 预览渲染均能解析占位符）。
        VariableInterpolator variableInterpolator = new VariableInterpolator(variableStore);
        compositor.setVariableSupport(variableInterpolator, variableStore);
        // wall 删除时（SessionManager.deleteWall 先删 walls 行、再释放地图后触发）清掉倒排索引。
        final VariableStore variableStoreForHook = variableStore;
        sessionManager.addWallDeleteHook(wid -> variableStoreForHook.clearWallReferences(wid));
        // 显式 deleteByWall 与 FK CASCADE 双重保障，防止连接级 foreign_keys pragma 未生效时遗留孤儿行。
        final UserVariableDao userVariableDaoForHook = userVariableDao;
        sessionManager.addWallDeleteHook(wid -> userVariableDaoForHook.deleteByWall(wid));
        // Provider daemon 框架（守护线程池 + 定时调度）。ScheduleDao 先于 ProviderBootstrap.initialize 构造。
        this.scheduleDao = new ScheduleDao(getLogger(), database.jdbi());
        this.variableAliasDao = new VariableAliasDao(getLogger(), database.jdbi());
        final VariableAliasDao variableAliasDaoForHook = this.variableAliasDao;
        sessionManager.addWallDeleteHook(wid -> variableAliasDaoForHook.deleteByWall(wid));
        this.railDao = new RailDao(getLogger(), database.jdbi());
        ScriptDao scriptDao = new ScriptDao(getLogger(), database.jdbi());
        scriptStore = new ScriptStore(
                getLogger(), scriptDao, config.scriptsConfig.maxRulesPerWall());
        scriptStore.loadFromDb();
        sessionManager.addWallDeleteHook(scriptStore::clearWall);
        this.variableProviderDaemon =
                ProviderBootstrap.initialize(this.variableStore, this, this.wallRepo,
                        this.scheduleDao, config.scheduleConfig, this.railDao);
        // 所有依赖就位（compositor.interpolator + Provider 已装入 store），执行启动期 wall 像素恢复。
        try {
            int restored = wallRestorerInstance.restore();
            getLogger().info("Wall restore: " + restored + " wall(s) repainted (post-provider init)");
            if (!wallRestorerInstance.failedRestoreWallIds().isEmpty()) {
                getLogger().warning("Wall restore: failed wall_ids = "
                        + wallRestorerInstance.failedRestoreWallIds()
                        + " (interactions will be blocked until next successful restart)");
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "WallRestorer failed (non-fatal)", e);
        }
        final ManualScheduleProvider manualScheduleProvider =
                (ManualScheduleProvider) this.variableProviderDaemon
                        .registeredProviders().stream()
                        .filter(p -> p instanceof ManualScheduleProvider)
                        .findFirst().orElse(null);
        final ScheduleDao scheduleDaoForHook = this.scheduleDao;
        sessionManager.addWallDeleteHook(wid -> {
            if (manualScheduleProvider != null) manualScheduleProvider.unregisterWall(wid);
            if (scheduleDaoForHook != null) scheduleDaoForHook.deleteByWall(wid);
        });

        final RailScheduleProvider railProviderRef =
                (RailScheduleProvider) this.variableProviderDaemon
                        .registeredProviders().stream()
                        .filter(p -> p instanceof RailScheduleProvider)
                        .findFirst().orElse(null);
        final RailDao railDaoForHook = this.railDao;
        sessionManager.addWallDeleteHook(wid -> {
            if (railProviderRef != null) railProviderRef.unregisterWall(wid);
            if (railDaoForHook != null) railDaoForHook.deleteBinding(wid);
        });

        // HikariCanvasAPI 装配。依赖 VariableStore + VariableProviderDaemon，
        // 必须晚于 ProviderBootstrap.initialize；早于 WebServer（未来想加 /api/plugin/* 端点
        // 可直接访问字段）。
        // 双层 push 限流（per-plugin 100/s + 全局 1000/s + 保护期 10s，
        // 默认值，可在 config.yml dynamic.push-rate-limit 调）。
        this.pluginNamespaceRegistry = new PluginNamespaceRegistry();
        PushRateLimiter pushRateLimiter = new PushRateLimiter(config.pushRateLimitConfig);
        // 注入 auditLog 让 checkAcl 拒绝 namespace spoof 时记 PLUGIN_NAMESPACE_DENIED。
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

        // 模板元数据 DAO + 创意工坊协调器
        templateRepo = new TemplateRepo(getLogger(), database.jdbi());
        templatePublisher = new TemplatePublisher(getLogger(),
                getDataFolder().toPath(),
                templateRegistry, templateRepo,
                compositor, config.templatesMaxPerPlayer);
        templatePublisher.syncBuiltinToDb();
        // 模板缩略图服务。Registry reload 时调 invalidate() 清缓存
        templatePreviewService = new TemplatePreviewService(getLogger(), templateRegistry, compositor);
        // 发布 / 删除模板后按 templateId 清缩略图，Gallery 立刻看到新图
        templatePublisher.setPreviewService(templatePreviewService);
        // 存为模板时把图片与积木脚本一起打进 pack。不接的话：原图被 LRU 驱逐后套用出来是空白，
        // 墙上的脚本更是直接丢光（docs/template-pack.md §3 明列 pack 含这两样）。
        templatePublisher.setAssetSource(imageStorage::readPngBytes);
        final ScriptStore storeForTemplates = scriptStore;
        templatePublisher.setScriptSource(wallId -> serializeWallScripts(storeForTemplates, wallId));
        wallPreviewService = new WallPreviewService(getLogger(), compositor);

        // 节流：投影 fps + 输入速率（per session）。
        // config.adaptiveFps.defaultMinIntervalMs() 在配置层已把 throttle.projection-fps 作为缺省推算来源
        // （未显式写 rendering.adaptive-fps.default-min-interval-ms 时按 1000/fps 推算，见 HikariCanvasConfig.resolveAdaptiveFps）。
        long projectionIntervalMs = Math.max(33L, config.adaptiveFps.defaultMinIntervalMs());
        projectionThrottler = new ProjectionThrottler(this, sessionManager, canvasProjector,
                projectionIntervalMs);
        rateLimiter = new SessionRateLimiter(config.inputBurst,
                Math.max(1000L, (long) config.inputBurst * 1000 / Math.max(1, config.inputRatePerSecond)));
        // 先 flushNow 把节流窗口内的 pending 尾帧落地，
        // 再 discardSession 取消任务清状态——保证 session cancel 时游戏内地图不停在倒数第二帧。
        sessionManager.addPreForgetHook(projectionThrottler::flushNow);
        sessionManager.addForgetHook(projectionThrottler::discardSession);
        sessionManager.addForgetHook(rateLimiter::discardSession);
        // session forget 时清 WebServer.wsBySession 映射，避免已 forget 的 session
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

        // /canvas var 子命令族。reload hook 重读 config.yml + 重建 PushRateLimiter
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
                },
                messages);
        // /canvas bench 命令族。持守护线程 executor，存字段供 onDisable shutdown。
        benchmarkSubCommand = new BenchmarkSubCommand(this, messages);
        // CanvasCommand 注册（含 DiagnosticsSubCommand 注入）下移到 animationTicker /
        // tweenScheduler 装配之后——DiagnosticsSubCommand.runStats 读这两者的活跃数，须非 null。
        final VariableSubCommand variableSubCommandRef = variableSubCommand;
        final String editorUrlTemplateRef = editorUrlTemplate;

        // UploadHandler 需要 sessionManager / wallRepo，所以晚于它们装配
        // 还需要 imageDao + jdbi 做事务化 quota+insert+evict
        uploadHandler = new UploadHandler(getLogger(), imageStorage, imageQuota,
                imageDao, database.jdbi(),
                config.images, sessionManager, wallRepo, auditLog);

        // .canvas 工程导入的 assets 图片摄入栈（与上传同款 magic+隔离解码+配额+落 hash）。
        // 复用上面已装配的 imageStorage / imageQuota / imageDao / wallRepo / jdbi，注入 WebServer
        // 由其内部 new ProjectImporter（复用 dispatcher 同款 push 广播）。
        this.assetIngest =
                new AssetIngest(getLogger(), imageStorage, imageQuota,
                        imageDao, wallRepo, database.jdbi());

        // 取出 ManualScheduleProvider 引用让 WebServer 在 entry 增删时通知 refreshWall
        ManualScheduleProvider manualScheduleProviderRef = (ManualScheduleProvider)
                variableProviderDaemon.registeredProviders().stream()
                        .filter(p -> p instanceof ManualScheduleProvider)
                        .findFirst().orElse(null);
        // 取出 RailScheduleProvider 引用让 WebServer 在 rail.wall.bind 后立即 register
        RailScheduleProvider railScheduleProviderRef = (RailScheduleProvider)
                variableProviderDaemon.registeredProviders().stream()
                        .filter(p -> p instanceof RailScheduleProvider)
                        .findFirst().orElse(null);
        // TokenRateLimiter：token 校验限流（与 SessionRateLimiter 区分——后者是 op-rate）。
        TokenRateLimiter tokenRateLimiter = new TokenRateLimiter(config.tokenRateLimitPerMinute);
        webServer = new WebServer(getLogger(), config.host, config.port,
                tokenService, sessionManager,
                projectionThrottler, rateLimiter,
                wallRepo, frameDeployer, templateRegistry, templatePreviewService,
                config.previewCacheSeconds, wallPreviewService, uploadHandler,
                templatePublisher, templateRepo, auditLog, fontRegistry, iconRegistry,
                variableStore, scheduleDao, manualScheduleProviderRef,
                railDao, railScheduleProviderRef,
                variableProviderDaemon, variableAliasDao, this,
                version,
                config.wsAuthTimeoutSeconds, config.allowedOrigins,
                tokenRateLimiter, scriptStore,
                // 命令模板表惰性读 volatile config → /canvas reload 即生效，
                // 与 ActionExecutor 读模板范式一致（绝不泄 command 原文由 handler 保证）。
                () -> config().scriptsConfig.commandTemplates(),
                // .canvas 工程导入——AssetIngest + 导入限额；WebServer 内 new ProjectImporter。
                assetIngest, config.importConfig,
                // i18n 文案中枢（WS auth 读编辑器 locale + 转交 ScriptOpDispatcher 渲染校验报错）
                messages);
        webServer.start();

        // 时间轴动画产帧引擎装配（docs/architecture.md §5.5 / docs/timeline.md §3）。
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
        // 数值轨 ${var:X} 求值（rendering.md §9.5）——Ticker 在插值前按 wall resolve。
        ticker.setNumberResolver(variableInterpolator::resolveAsNumber);
        // editOpDispatcher 持 ticker 引用，timeline.play/pause/seek 三 op 用。
        webServer.setAnimationTicker(ticker);
        // SessionManager 持 ticker 引用：编辑持久化后 invalidate（缓存重载）+ session 关闭后
        // refreshAutoPlay（编辑器关了 LOOP 墙自动播）。null-guard 在 SessionManager 侧（测试零侵入）。
        sessionManager.setAnimationTicker(ticker);
        // wall 删除钩子：/canvas delete 后立即注销播放——mapIds 已
        // releaseToFree，孤悬的播放条目会向可能被复用的 mapId 写像素（跨墙串台）。
        sessionManager.addWallDeleteHook(ticker::stopWall);
        // 启动期全库扫描：activeTimelineId 且 LOOP 的 wall 自动播（timeline.md §5.2）。
        // 排除启动期恢复失败的墙。
        int autoPlayed = ticker.autoRegisterAll(wallRestorer.failedRestoreWallIds());
        if (autoPlayed > 0) {
            getLogger().info("AnimationTicker: " + autoPlayed + " wall(s) auto-playing (LOOP)");
        }

        // 时间轴触发器路由（VARIABLE_CHANGE / SCHEDULE，timeline.md §5.2）。
        final AnimationTicker tickerForTrigger = ticker;
        TimelineTriggerRegistry triggerRegistry = new TimelineTriggerRegistry(
                AnimationTicker.fromRepo(wallRepo),
                VariableInterpolator::resolveFullName,
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

        // Provider 写值时主动推 state.patch 给前端 mirror（Provider 直接写 store，不经 EditSession op 路径）。
        final OpPushCallback varPushCallback =
                new OpPushCallback() {
                    @Override
                    public boolean pushSnapshot(String sid, ProjectState s) {
                        return webServer != null && webServer.pushSnapshot(sid, s);
                    }
                    @Override
                    public boolean pushPatch(String sid, StatePatch p) {
                        return webServer != null && webServer.pushPatch(sid, p);
                    }
                };
        final SessionManager sessionManagerRef = this.sessionManager;
        // 让 SessionManager.variableToMap 能查 userglobal owner 信息（注入到 state.patch 的
        // value），patch 收到端就能区分"我的全局 / 其他全局"。
        sessionManagerRef.setVariableStoreRef(variableStore);
        variableStore.registerChangeListener(event -> {
            // 路由按 fullName 前缀 — userglobal/* 全 session 广播；其他走 per-wall 路由
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

        // 自适应渲染：第二条 ChangeListener。任意变量 mutation 或 wall 引用集合变化都
        // 重新评估"该 wall 是否含高频变量"→ 给绑定该 wall 的所有 session 在 ProjectionThrottler
        // 上调 setIntervalForSession（高频 50ms / 默认 200ms）。
        final VariableStore variableStoreForAdaptive = this.variableStore;
        final ProjectionThrottler throttlerRef = this.projectionThrottler;
        final SessionManager sessionManagerForAdaptive = this.sessionManager;
        final long defaultIntervalMs = config.adaptiveFps.defaultMinIntervalMs();
        final long highFreqIntervalMs = config.adaptiveFps.highFreqMinIntervalMs();
        variableStore.registerChangeListener(event -> {
            Set<String> walls = event.referencingWalls();
            if (walls == null || walls.isEmpty()) return;
            for (String wallId : walls) {
                boolean highFreq = variableStoreForAdaptive.isWallHighFreq(wallId);
                long interval = highFreq ? highFreqIntervalMs : defaultIntervalMs;
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

        // ── 脚本执行引擎全链装配（docs/scripting.md §3） ─────────────
        // ActionExecutor 依赖 AnimationTicker（webServer.start() 之后才建），故整条链装配在
        // ticker / varPushCallback / projectionThrottler 全部就位之后（WebServer 不依赖本链）。
        this.scriptBudget = new ScriptBudget(config.scriptsConfig);
        ConditionEvaluator scriptConditions =
                new ConditionEvaluator(getLogger(), variableStore);
        // setElementProperty 路径 A（墙开着编辑器）：标准 element.update 链 + 前端 patch +
        // 投影节流；路径 B（headless）在 Applier 内部走 WallRepo + ticker。
        final SessionManager smForScript = this.sessionManager;
        final ProjectionThrottler throttlerForScript = this.projectionThrottler;
        TickerControl tickerControl = TickerControl.of(ticker);
        ElementPropertyApplier propertyApplier =
                new ElementPropertyApplier(
                        new ElementPropertyApplier.SessionPatchApplier() {
                            @Override
                            public ElementPropertyApplier.SessionOutcome apply(
                                    String wid, String eid, Map<String, Object> patch) {
                                return smForScript.applyScriptElementPatch(
                                        wid, eid, patch, varPushCallback, throttlerForScript);
                            }

                            @Override
                            public ElementPropertyApplier.SessionOutcome nudge(
                                    String wid, String eid, int dx, int dy) {
                                return smForScript.applyScriptElementNudge(
                                        wid, eid, dx, dy, varPushCallback, throttlerForScript);
                            }

                            @Override
                            public ElementPropertyApplier.SessionOutcome clone(
                                    String wid, String eid, int offsetX, int offsetY) {
                                return smForScript.applyScriptElementClone(
                                        wid, eid, offsetX, offsetY,
                                        varPushCallback, throttlerForScript);
                            }

                            @Override
                            public ElementPropertyApplier.SessionOutcome delete(
                                    String wid, String eid) {
                                return smForScript.applyScriptElementDelete(
                                        wid, eid, varPushCallback, throttlerForScript);
                            }

                            @Override
                            public ElementPropertyApplier.SessionOutcome reorderToEdge(
                                    String wid, String eid, String mode) {
                                return smForScript.applyScriptElementReorder(
                                        wid, eid, mode, varPushCallback, throttlerForScript);
                            }
                        },
                        wallRepo, tickerControl, getLogger());
        propertyApplier.setMaxElementsPerWall(config.scriptsConfig.maxElementsPerWall());
        this.scriptPropertyApplier = propertyApplier;
        ActionExecutor actionExecutor =
                new ActionExecutor(
                        variableStore, tickerControl, propertyApplier, wallRepo,
                        this,
                        () -> config().scriptsConfig.commandTemplates(),
                        () -> Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName)
                                .toList(),
                        auditLog, getLogger());
        this.scriptRunner = new ScriptRunner(
                scriptConditions, actionExecutor, scriptBudget, auditLog, getLogger());
        final int tweenMaxFps = config.tweenConfig.maxFps();
        final int tweenMaxConcurrent = config.tweenConfig.maxConcurrent();
        TweenScheduler ts =
                new TweenScheduler(
                        propertyApplier, tickerControl, wallRepo,
                        System::currentTimeMillis,
                        tweenMaxConcurrent, tweenMaxFps, getLogger());
        this.tweenScheduler = ts;
        this.scriptRunner.setTweenScheduler(ts);

        DiagnosticsSubCommand diagnosticsSubCommand = new DiagnosticsSubCommand(
                mapPool, sessionManager, wallRepo, variableStore,
                this.animationTicker, this.tweenScheduler,
                tokenService, database, messages);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        new CanvasCommand(this, sessionManager, frameDeployer,
                                tokenService, wallRepo,
                                templateRegistry, templatePreviewService, editorUrlTemplateRef,
                                variableSubCommandRef, benchmarkSubCommand,
                                diagnosticsSubCommand, messages).build()));

        // 墙原点源：用 scriptWorldUuidByName 快照表将世界名映射为世界 UUID，避免在 Jetty 线程异步调用 Bukkit.getWorld。
        for (World w : Bukkit.getWorlds()) {
            scriptWorldUuidByName.put(w.getName(), w.getUID());
        }
        final WallRepo wallRepoForNear = wallRepo;
        final Map<String, UUID> worldUuidSnapshot = scriptWorldUuidByName;
        TriggerRouter.WallOriginSource wallOriginSource =
                wallId -> {
                    var wall = wallRepoForNear.loadById(wallId).orElse(null);
                    if (wall == null) return null;
                    UUID worldUid = worldUuidSnapshot.get(wall.key().world());
                    if (worldUid == null) return null;
                    return new TriggerRouter.WallOrigin(
                            worldUid, wall.key().originX(),
                            wall.key().originY(), wall.key().originZ());
                };
        TriggerRouter routerForScript =
                new TriggerRouter(
                        scriptStore, scriptRunner,
                        VariableInterpolator::resolveFullName,
                        wallOriginSource, getLogger());
        this.scriptTriggerRouter = routerForScript;
        variableStore.registerChangeListener(routerForScript::onVariableChange);
        scriptStore.addListener(routerForScript::rebuildWall);
        sessionManager.addWallDeleteHook(routerForScript::removeWall);
        sessionManager.addWallReadyHook(routerForScript::fireWallReady);
        routerForScript.rebuildAll();
        final Set<String> failedRestoreForScript = wallRestorerInstance.failedRestoreWallIds();
        List<String> scriptReadyWalls = wallRepo.loadAll().stream()
                .map(WallRepo.Wall::wallId)
                .filter(id -> !failedRestoreForScript.contains(id))
                .toList();
        routerForScript.fireWallReadyAll(scriptReadyWalls);
        getServer().getPluginManager().registerEvents(
                new GameEventListenerHub(
                        routerForScript, scriptWorldUuidByName,
                        routerForScript::rebuildAll, frameDeployer::wallIdOf), this);
        PlayerNearSampler nearSampler =
                new PlayerNearSampler(
                        () -> {
                            var online = Bukkit.getOnlinePlayers();
                            if (online.isEmpty()) return List.of();
                            List<PlayerNearSampler.PlayerPos> list = new ArrayList<>(online.size());
                            for (Player p : online) {
                                Location loc = p.getLocation();
                                list.add(new PlayerNearSampler.PlayerPos(
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
        final ScriptStore storeForTest = scriptStore;
        final ScriptRunner runnerForTest = scriptRunner;
        webServer.setScriptTestLauncher((wallId, ruleId, traceCallback) -> {
            Optional<ScriptRule> r = storeForTest.find(wallId, ruleId);
            if (r.isEmpty()) {
                traceCallback.accept(List.of(
                        TraceStep.error("trigger", "rule not found: " + ruleId)));
                return;
            }
            runnerForTest.submit(wallId, r.get(),
                    new TriggerContext(TriggerContext.Source.TEST, 0, "test"),
                    traceCallback);
        });
        getLogger().info("Script engine: runner + trigger router registered ("
                + scriptReadyWalls.size() + " wall(s) wallReady fired)");

        // MapPool 泄漏检测周期任务（5 分钟）。
        mapPoolLeakTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                () -> {
                    if (shuttingDown || database == null) return;
                    // 只读 wall_id 列（不碰 project_json 反序列化），且读失败显式返回 empty 跳过本轮。
                    // 快照时间戳必须在读表前取，供 detectLeaks 豁免快照之后才借出的地图。
                    long liveSnapshotAt = System.currentTimeMillis();
                    Optional<Set<String>> liveWallIds = wallRepo.loadAllWallIds();
                    if (liveWallIds.isEmpty()) {
                        getLogger().severe("[mapPool] leak scan skipped: could not read walls table"
                                + " (refusing to treat an unreadable table as 'no live walls')");
                        return;
                    }
                    int leaked = mapPool.detectLeaks(liveWallIds.get(), liveSnapshotAt);
                    if (leaked > 0) {
                        getLogger().warning("[mapPool] detected " + leaked + " leaked map(s); released");
                    }
                },
                20L * 60 * 5,
                20L * 60 * 5);

        getLogger().info("HikariCanvas enabled");
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE,
                    "onEnable failed mid-assembly; cleaning up partially-initialized resources", t);
            try {
                cleanupResources();
            } catch (Throwable cleanupErr) {
                getLogger().log(Level.SEVERE,
                        "cleanup after onEnable failure also threw", cleanupErr);
            }
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error err) throw err;
            throw new IllegalStateException("HikariCanvas onEnable failed", t);
        }
    }

    /**
     * 读 {@code limits.canvas-max-maps}（单面招牌的最大地图数）。默认 16，允许范围 1~64
     * （与 {@code docs/data-model.md §5} 的取值表一致）；越界钳回边界并 warning。
     *
     * <p>这一项改了要重启：{@link WallResolver} 在装配期一次性拿到上限，且只作用于
     * 新建路径——已存在的 wall 即使把上限调小也照常能打开（见 {@code rendering.md §11}）。</p>
     */
    private int readCanvasMaxMaps() {
        int raw = getConfig().getInt("limits.canvas-max-maps", 16);
        int clamped = Math.max(1, Math.min(64, raw));
        if (clamped != raw) {
            getLogger().warning("limits.canvas-max-maps=" + raw
                    + " is out of the supported range 1-64; using " + clamped);
        }
        return clamped;
    }

    /**
     * 接收 {@code /canvas reload config} 的新配置。只更新引用 + log；多数字段（host/port/
     * 池容量/超时等）已传给具体服务，必须重启才能真正生效。提示由命令侧打。
     *
     * <p>未来若有需要做 hot-apply，可在此扩展：给 TokenService/SessionReaper/MapPool 各加 setter。</p>
     *
     * <p>不需要 {@code synchronized}——{@link #config} 字段已是 {@code volatile}，
     * 单引用赋值的可见性由 volatile write 充分保证，且无并发写者（仅命令线程触发），无需互斥。
     * 若未来扩展为多字段一致性更新可回头加锁。</p>
     */
    public void applyConfig(HikariCanvasConfig fresh) {
        this.config = fresh;
        // 脚本配额热更（只影响后续 create，不裁剪已有——ScriptStore javadoc）
        if (scriptStore != null) {
            scriptStore.setMaxRulesPerWall(fresh.scriptsConfig.maxRulesPerWall());
        }
        // 脚本执行预算三闸热更（已开的 1s 窗不重置，下个窗起生效——ScriptBudget javadoc）
        if (scriptBudget != null) {
            scriptBudget.applyConfig(fresh.scriptsConfig);
        }
        // playerNear 采样间隔热更（volatile 跳帧计数，无需重 schedule）
        if (playerNearSampler != null) {
            playerNearSampler.setSampleTicks(fresh.scriptsConfig.playerNearSampleTicks());
        }
        // 脚本克隆元素配额热更——路径 A（活跃 session 新建的 EditSession）经
        // SessionManager 透传、headless 临时 EditSession 经 ElementPropertyApplier。两处都 volatile，
        // /canvas reload 后下一次克隆即生效（已开的 session 内已注入的旧值不变，下次 open 起新值）。
        if (sessionManager != null) {
            sessionManager.setMaxElementsPerWall(fresh.scriptsConfig.maxElementsPerWall());
        }
        if (scriptPropertyApplier != null) {
            scriptPropertyApplier.setMaxElementsPerWall(fresh.scriptsConfig.maxElementsPerWall());
        }
        // 热重载时重扫外部 lang/ 并更新默认语言。
        if (messages != null) {
            messages.reload(getDataFolder().toPath().resolve("lang"));
            messages.setDefaultLocale(fresh.i18nConfig.defaultLocale());
        }
        getLogger().info("Config refreshed (most fields need restart): " + fresh.summary());
    }

    /**
     * 把某面墙的积木脚本序列化成 {@code scripts.json} 的字节（{@code ScriptRule[]} 数组），
     * 供"存为模板"把脚本一起打进 pack。该墙没有脚本、或序列化失败 → 返 null（模板照常存，
     * 只是不带脚本）。
     */
    private byte[] serializeWallScripts(ScriptStore store, String wallId) {
        if (store == null || wallId == null) return null;
        List<ScriptRule> rules = store.listByWall(wallId);
        if (rules == null || rules.isEmpty()) return null;
        try {
            return new ObjectMapper().writeValueAsBytes(rules);
        } catch (Exception e) {
            getLogger().log(Level.WARNING,
                    "serializing scripts of wall " + wallId + " failed; template saved without them", e);
            return null;
        }
    }

    /** 供命令侧用；返回 null 表示插件还没 onEnable。 */
    public HikariCanvasConfig config() { return config; }

    /** 多语言消息注册表；onEnable 完成前可能返 null。 */
    public Messages messages() { return messages; }

    /** 取 VariableStore 单例。 */
    public VariableStore getVariableStore() { return variableStore; }

    /**
     * 外部插件获取 HikariCanvasAPI 的入口 A（需 import HikariCanvas 主类）。
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
     * 幂等资源清理。被 {@link #onDisable} 与 {@link #onEnable} 装配失败的
     * catch 共用——避免清理逻辑两处漂移。每步走 {@link #closeQuietly}
     * 独立兜异常 + 全字段 null-guard，重复调用安全（已 null 的步骤跳过）。
     *
     * <p>顺序固化：先置 shuttingDown + cancel 异步周期任务 → daemon → reaper → webServer →
     * upload → imageStorage → database.close → 恢复 IIORegistry。
     * database.close 必须在引用 DB 的任务 / daemon 之后。</p>
     */
    private void cleanupResources() {
        // 第一步置 shuttingDown，让 in-flight 的 mapPoolLeakTask run 在下次入口检查
        // 时 short-circuit，避免它在 database.close() 之后才读 DB（use-after-close）。
        shuttingDown = true;
        // 先 cancel 异步周期任务，再关 DB（顺序保证 cancel 后不会再有新 run 排进
        // 队列触发 DB 访问）。Bukkit cancel() 不等 in-flight，shuttingDown flag 兜住已入队的 run。
        if (tokenPurgeTask != null) {
            closeQuietly("tokenPurgeTask.cancel", tokenPurgeTask::cancel);
            tokenPurgeTask = null;
        }
        if (mapPoolLeakTask != null) {
            closeQuietly("mapPoolLeakTask.cancel", mapPoolLeakTask::cancel);
            mapPoolLeakTask = null;
        }
        // playerNear 采样是主线程同步任务，onDisable 也在主线程——cancel 后
        // 不可能再有 in-flight tick；放 Router shutdown 之前停掉新触发来源。
        if (playerNearTask != null) {
            closeQuietly("playerNearTask.cancel", playerNearTask::cancel);
            playerNearTask = null;
            playerNearSampler = null;
        }
        // 每个资源 shutdown 步骤独立 try/catch（closeQuietly）——一个抛异常不
        // 跳过后续释放，HikariDataSource / executor 等非 Bukkit 托管资源必须保证关到。
        // 先停 provider daemon，让守护线程池 awaitTermination 完成 + provider
        // shutdown 钩子释放资源；放最前是因为 daemon 内部 refresh task 可能引用 store / DB。
        if (variableProviderDaemon != null) {
            closeQuietly("variableProviderDaemon.shutdown", variableProviderDaemon::shutdown);
            variableProviderDaemon = null;
        }
        // 停脚本引擎——先 Router（cancel 全部 timer + 关 hikari-script-trigger
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
        // 补间动画引擎：scriptRunner 停后、animationTicker 停前关停（补间依赖 ticker.renderStatic）。
        if (tweenScheduler != null) {
            closeQuietly("tweenScheduler.shutdown", tweenScheduler::shutdown);
            tweenScheduler = null;
        }
        // 停时间轴产帧引擎（单线程 daemon awaitTermination）。与 variableProviderDaemon
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
        // 停 .canvas 导入 assets 摄入栈的 daemon 解码线程池（shutdownNow 幂等）。
        if (assetIngest != null) {
            closeQuietly("assetIngest.shutdown", assetIngest::shutdown);
            assetIngest = null;
        }
        if (imageStorage != null) {
            closeQuietly("imageStorage.shutdown", imageStorage::shutdown);
            imageStorage = null;
        }
        // 关停 /canvas bench 守护线程 executor（shutdownNow 中断 in-flight 压测）。
        if (benchmarkSubCommand != null) {
            closeQuietly("benchmarkSubCommand.shutdown", benchmarkSubCommand::shutdown);
            benchmarkSubCommand = null;
        }
        // 停字体 metrics 后台 worker（中断 + join）+ 清 FontMetricsTable 内存表，
        // 防（热）卸载后 worker 线程泄漏 / 旧 metrics 残留致下次 onEnable 双端布局不一致。
        if (fontRegistry != null) {
            closeQuietly("fontRegistry.shutdownMetricsWorker", fontRegistry::shutdownMetricsWorker);
        }
        closeQuietly("FontMetricsTable.clear", FontMetricsTable::clear);
        // database.close() 必须在异步任务 cancel + daemon shutdown 之后（它们可能引用 DB）。
        if (database != null) {
            closeQuietly("database.close", database::close);
            database = null;
        }
        if (!deregisteredImageReaders.isEmpty()) {
            try {
                IIORegistry registry = IIORegistry.getDefaultInstance();
                int restored = 0;
                for (ImageReaderSpi spi : deregisteredImageReaders) {
                    try {
                        registry.registerServiceProvider(spi);
                        restored++;
                    } catch (Exception e) {
                        getLogger().log(Level.WARNING,
                                "IIORegistry: failed to restore reader " + spi.getClass().getName(), e);
                    }
                }
                getLogger().info("IIORegistry: restored " + restored + " image reader(s)");
            } catch (Exception e) {
                getLogger().log(Level.WARNING,
                        "IIORegistry restore failed (non-fatal)", e);
            }
            deregisteredImageReaders.clear();
        }
    }

    /**
     * onDisable / onEnable-fail 用——执行一个 shutdown 动作，捕获并 WARNING 记录任何
     * {@link Throwable}，保证后续资源释放步骤不被前面的异常跳过。
     *
     * @param name   动作名（仅用于日志定位）
     * @param action 关闭动作（如 {@code database::close}）
     */
    private void closeQuietly(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING,
                    "cleanup: " + name + " failed (continuing)", t);
        }
    }

    /**
     * 首次启动时把 jar 内的语言文件拷到 {@code plugins/HikariCanvas/lang/}；已存在则保留服主修改，
     * 缺失键由 {@link Messages#loadExternal} 按键合并回内置值。
     */
    private void saveLangIfAbsent(String resourcePath) {
        Path target = getDataFolder().toPath().resolve(resourcePath);
        if (Files.exists(target)) return;
        try {
            saveResource(resourcePath, false);
        } catch (Exception e) {
            getLogger().log(Level.WARNING,
                    "failed to write default lang file " + resourcePath, e);
        }
    }

}
