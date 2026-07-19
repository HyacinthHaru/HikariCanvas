package ac.haru.hikaricanvas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.json.JavalinJackson;
import io.javalin.router.Endpoint;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsHandlerType;
import io.javalin.websocket.WsMessageContext;
import ac.haru.hikaricanvas.render.FontRegistry;
import ac.haru.hikaricanvas.render.IconRegistry;
import ac.haru.hikaricanvas.render.ProjectionThrottler;
import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionManager;
import ac.haru.hikaricanvas.session.SessionRateLimiter;
import ac.haru.hikaricanvas.session.SessionState;
import ac.haru.hikaricanvas.session.TokenService;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.StatePatch;
import ac.haru.hikaricanvas.template.TemplateEntry;
import ac.haru.hikaricanvas.template.TemplateRegistry;
import ac.haru.hikaricanvas.template.TemplateSpec;
import ac.haru.hikaricanvas.template.preview.TemplatePreviewService;
import ac.haru.hikaricanvas.template.preview.WallPreviewService;
import ac.haru.hikaricanvas.template.asset.TemplateAssetService;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Javalin HTTP + WebSocket 服务。契约见 {@code docs/protocol.md §3}、{@code §5}。
 *
 * <ul>
 *   <li>{@code GET /api/session/:token} — HTTP 预握手，校验 token 并返回会话元信息</li>
 *   <li>{@code WS /ws} — auth-first 协议：首帧必须是 {@code op=auth}</li>
 * </ul>
 *
 * <p>token rotate（auth 成功后回发 {@code reconnectToken} 给前端，供 WS
 * 断线重连重新 auth 使用）。契约见 {@code docs/security.md §2.2}、{@code docs/protocol.md §11}。</p>
 *
 * <p>god-class 拆分：编辑 / brush / wall / template 四组 op 分发已搬到
 * {@link EditOpDispatcher} / {@link BrushOpDispatcher} / {@link WallOpDispatcher} /
 * {@link TemplateOpDispatcher}。WebServer 只负责路由 + 推送 + auth + 装配。</p>
 */
public final class WebServer {

    private static final String ATTR_SESSION_ID = "sessionId";

    private final Logger log;
    private final String host;
    private final int port;
    private final TokenService tokenService;
    private final SessionManager sessionManager;
    private final String serverVersion;
    /** WS auth 超时秒。0 < x ≤ 60。 */
    private final int wsAuthTimeoutSeconds;
    /** 除回环 + 同源外的额外 Origin 白名单（公网反代用）。 */
    private final List<String> allowedOrigins;
    /**
     * token 暴力枚举防御：每 IP 每分钟最多 N 次 token 校验尝试，
     * 超限 close 4429 + audit。详见 {@link TokenRateLimiter}。
     */
    private final TokenRateLimiter tokenRateLimiter;
    private final ac.haru.hikaricanvas.storage.WallRepo wallRepo;
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final TemplateRegistry templateRegistry;
    private final TemplatePreviewService templatePreviewService;
    private final TemplateAssetService templateAssetService;
    private final WallPreviewService wallPreviewService;
    private final ac.haru.hikaricanvas.image.UploadHandler uploadHandler;
    private final ac.haru.hikaricanvas.storage.TemplateRepo templateRepo;
    private final ac.haru.hikaricanvas.storage.AuditLog auditLog;
    /** HTTP 字体端点 {@code /api/font/file} 与 {@code /api/font/list} 用。 */
    private final FontRegistry fontRegistry;
    /** HTTP 矢量图标端点 {@code /api/icon/list} 与 {@code /api/icon/paths} 用。 */
    private final IconRegistry iconRegistry;
    /**
     * i18n 文案中枢。WS auth 读前端携带的 locale → {@code resolveLocaleId} 规范化后
     * 存入 {@link Session#setEditorLocale}，供脚本校验报错按编辑器语言渲染；同时转交
     * {@link ScriptOpDispatcher}（渲染 {@link ac.haru.hikaricanvas.script.ValidationError}）。
     * 可为 null（旧测试装配容忍——auth 缺 messages 时跳过 setEditorLocale）。
     */
    private final ac.haru.hikaricanvas.i18n.Messages messages;

    // ---------- 拆分后的 dispatcher（god-class 拆分）----------
    private final EditOpDispatcher editOpDispatcher;

    /**
     * 把 AnimationTicker 转交给 editOpDispatcher（timeline.play/pause/seek 三 op 用）。
     * Ticker 在 WebServer 之后构造（依赖 CanvasProjector），故走 setter 而非构造器参数。
     */
    public void setAnimationTicker(ac.haru.hikaricanvas.render.AnimationTicker ticker) {
        editOpDispatcher.setAnimationTicker(ticker);
    }
    private final BrushOpDispatcher brushOpDispatcher;
    private final WallOpDispatcher wallOpDispatcher;
    private final TemplateOpDispatcher templateOpDispatcher;
    /** variable.* 五个 op 的分发；可为 null（VariableStore 未配置） */
    private final VariableOpDispatcher variableOpDispatcher;
    /** schedule.* 五个 op 的分发；可为 null（ScheduleDao 未配置） */
    private final ScheduleOpDispatcher scheduleOpDispatcher;
    /** rail.* 12 个 op 的分发；可为 null（RailDao 未配置） */
    private final RailOpDispatcher railOpDispatcher;
    /** variable.alias.* 三个 op 的分发；可为 null（VariableAliasDao 未配置） */
    private final VariableAliasDispatcher variableAliasDispatcher;
    /** ready payload 注入 aliases 快照；与 dispatcher 同生命周期。 */
    private final ac.haru.hikaricanvas.storage.VariableAliasDao variableAliasDao;
    /** script.* 五个 op 的分发；可为 null（ScriptStore 未配置）。 */
    private final ScriptOpDispatcher scriptOpDispatcher;
    /** ready payload 注入 scripts 快照；与 dispatcher 同生命周期。 */
    private final ac.haru.hikaricanvas.script.ScriptStore scriptStore;
    /**
     * ready payload 注入 variables 快照需要直读 VariableStore。
     * 与 {@link #variableOpDispatcher} 同生命周期；可为 null（VariableStore 未配置时跳过注入）。
     */
    private final ac.haru.hikaricanvas.variable.VariableStore variableStore;
    /** /api/variable/list-all-namespaces 端点 handler，聚合 Provider declaredKeys。可为 null。 */
    private final VariableMetadataHandler variableMetadataHandler;
    /**
     * /api/script/command-templates 端点 handler。
     * 只下发 id + params（绝不泄 command 原文）。可为 null（测试装配未传模板供给时禁用端点）。
     */
    private final CommandTemplateHandler commandTemplateHandler;

    /**
     * {@code POST /api/project/import} 端点 handler（.canvas 工程导入）。
     * 可为 null（测试装配 / AssetIngest 未传时禁用端点）。
     */
    private final ProjectImportHandler projectImportHandler;

    /**
     * wall 缩略图缓存：key = "wallId@updatedAt"，value = PNG bytes。
     * Caffeine 替代 ConcurrentHashMap（ConcurrentHashMap 不收缩）；
     * 5 分钟 access TTL + 上限 100 项。
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, byte[]> wallPreviewCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(100)
                    .expireAfterAccess(java.time.Duration.ofMinutes(5))
                    .build();
    private Javalin app;

    /** 活跃 session → 绑定的 WS 连接；用于服务端主动推送（state.snapshot / state.patch）。 */
    private final ConcurrentMap<String, WsContext> wsBySession = new ConcurrentHashMap<>();
    /** 会话输入限流器；持引用供"反复超限 → 单点断连"回调（见 {@link #closeForRepeatedViolation}）。 */
    private final SessionRateLimiter rateLimiter;
    /** 服务端主动推送 {@code s-<N>} 的自增计数。 */
    private final AtomicLong serverIdSeq = new AtomicLong(0);

    /**
     * 未认证 WS 连接的 close timer。key = ctx 的 sessionId() 字符串
     * （Javalin 的 WsContext 唯一标识，不是登录态 sessionId）。
     * onConnect → 注册；auth 成功 / onClose → cancel & remove。
     */
    private final ConcurrentMap<String, ScheduledFuture<?>> pendingAuthTimers = new ConcurrentHashMap<>();
    /** auth 超时内部专用，单线程 daemon scheduler；够轻。 */
    private final ScheduledExecutorService authTimeoutScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "hikari-ws-auth-timeout");
                t.setDaemon(true);
                return t;
            });

    public WebServer(Logger log, String host, int port,
                     TokenService tokenService, SessionManager sessionManager,
                     ProjectionThrottler throttler,
                     SessionRateLimiter rateLimiter,
                     ac.haru.hikaricanvas.storage.WallRepo wallRepo,
                     ac.haru.hikaricanvas.deploy.FrameDeployer frameDeployer,
                     TemplateRegistry templateRegistry,
                     TemplatePreviewService templatePreviewService,
                     TemplateAssetService templateAssetService,
                     WallPreviewService wallPreviewService,
                     ac.haru.hikaricanvas.image.UploadHandler uploadHandler,
                     ac.haru.hikaricanvas.template.TemplatePublisher templatePublisher,
                     ac.haru.hikaricanvas.storage.TemplateRepo templateRepo,
                     ac.haru.hikaricanvas.storage.AuditLog auditLog,
                     FontRegistry fontRegistry,
                     IconRegistry iconRegistry,
                     ac.haru.hikaricanvas.variable.VariableStore variableStore,
                     ac.haru.hikaricanvas.storage.ScheduleDao scheduleDao,
                     ac.haru.hikaricanvas.variable.provider.ManualScheduleProvider manualScheduleProvider,
                     ac.haru.hikaricanvas.storage.RailDao railDao,
                     ac.haru.hikaricanvas.variable.provider.RailScheduleProvider railScheduleProvider,
                     ac.haru.hikaricanvas.variable.provider.VariableProviderDaemon variableProviderDaemon,
                     ac.haru.hikaricanvas.storage.VariableAliasDao variableAliasDao,
                     org.bukkit.plugin.java.JavaPlugin plugin,
                     String serverVersion,
                     int wsAuthTimeoutSeconds,
                     List<String> allowedOrigins,
                     TokenRateLimiter tokenRateLimiter,
                     ac.haru.hikaricanvas.script.ScriptStore scriptStore,
                     java.util.function.Supplier<Map<String,
                             ac.haru.hikaricanvas.HikariCanvasConfig.CommandTemplate>>
                             commandTemplatesSupplier,
                     ac.haru.hikaricanvas.canvasfile.AssetIngest assetIngest,
                     ac.haru.hikaricanvas.HikariCanvasConfig.ImportConfig importConfig,
                     ac.haru.hikaricanvas.i18n.Messages messages) {
        this.log = log;
        this.host = host;
        this.port = port;
        this.messages = messages;
        this.tokenService = tokenService;
        this.sessionManager = sessionManager;
        this.wallRepo = wallRepo;
        this.wsAuthTimeoutSeconds = Math.max(1, Math.min(60, wsAuthTimeoutSeconds));
        this.allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        // token rate limiter；null = 不限流（测试 / 老代码）
        this.tokenRateLimiter = tokenRateLimiter;
        this.templateRegistry = templateRegistry;
        this.templatePreviewService = templatePreviewService;
        this.templateAssetService = templateAssetService;
        this.wallPreviewService = wallPreviewService;
        this.uploadHandler = uploadHandler;
        this.templateRepo = templateRepo;
        this.auditLog = auditLog;
        this.fontRegistry = fontRegistry;
        this.iconRegistry = iconRegistry;
        this.plugin = plugin;
        this.serverVersion = serverVersion;
        this.rateLimiter = rateLimiter;
        // 会话反复超限 → 单点断连回调（6 个 dispatcher 的 allow() 调用不变）
        rateLimiter.setOnRepeatedViolation(this::closeForRepeatedViolation);

        // dispatcher 通过 OpPushCallback 触发服务端主动推送，
        // 避免直接耦合 wsBySession 映射。
        OpPushCallback push = new OpPushCallback() {
            @Override public boolean pushSnapshot(String sid, ProjectState s) {
                return WebServer.this.pushSnapshot(sid, s);
            }
            @Override public boolean pushPatch(String sid, StatePatch p) {
                return WebServer.this.pushPatch(sid, p);
            }
            @Override public boolean pushOp(String sid, String op, Object payload) {
                return WebServer.this.pushOp(sid, op, payload);
            }
        };
        this.editOpDispatcher = new EditOpDispatcher(
                sessionManager, throttler, rateLimiter, templateRegistry, wallRepo, push, auditLog, plugin);
        this.brushOpDispatcher = new BrushOpDispatcher(sessionManager, throttler, push);
        this.wallOpDispatcher = new WallOpDispatcher(
                sessionManager, wallRepo, frameDeployer, throttler, plugin, auditLog);
        this.templateOpDispatcher = new TemplateOpDispatcher(sessionManager, templatePublisher, plugin);
        // HikariCanvas 总是先于 WebServer 构造完 VariableStore，生产恒非 null。
        this.variableOpDispatcher = variableStore == null ? null
                : new VariableOpDispatcher(sessionManager, rateLimiter, variableStore,
                        wallRepo, push, auditLog, plugin);
        // schedule.* dispatcher（ScheduleDao 必传；manualScheduleProvider 可空）
        this.scheduleOpDispatcher = scheduleDao == null ? null
                : new ScheduleOpDispatcher(sessionManager, rateLimiter, scheduleDao,
                        manualScheduleProvider, wallRepo, auditLog, plugin);
        // rail.* dispatcher（RailDao 必传；railScheduleProvider 可空）
        this.railOpDispatcher = railDao == null ? null
                : new RailOpDispatcher(sessionManager, rateLimiter, railDao,
                        railScheduleProvider, wallRepo, auditLog, plugin);
        // variable.alias.* dispatcher（VariableAliasDao 必传，否则禁用）
        this.variableAliasDao = variableAliasDao;
        this.variableAliasDispatcher = variableAliasDao == null ? null
                : new VariableAliasDispatcher(sessionManager, rateLimiter, variableAliasDao,
                        wallRepo, push, auditLog, plugin);
        // script.* dispatcher（ScriptStore 必传，否则禁用；测试装配传 null 容忍）
        this.scriptStore = scriptStore;
        this.scriptOpDispatcher = scriptStore == null ? null
                : new ScriptOpDispatcher(sessionManager, rateLimiter, scriptStore,
                        wallRepo, push, auditLog, plugin, log, messages);
        // 保留引用供 ready payload 注入 variables 快照
        this.variableStore = variableStore;
        // variable metadata 端点 handler；store/daemon/sessionManager 任一缺则禁用
        this.variableMetadataHandler = (variableStore == null || variableProviderDaemon == null)
                ? null
                : new VariableMetadataHandler(
                        variableStore, variableProviderDaemon, sessionManager,
                        new com.fasterxml.jackson.databind.ObjectMapper());
        // 命令模板端点 handler；模板供给缺（旧测试装配）则禁用端点。
        // 供给惰性读 volatile config → /canvas reload 热更友好。
        this.commandTemplateHandler = commandTemplatesSupplier == null
                ? null
                : new CommandTemplateHandler(
                        sessionManager, commandTemplatesSupplier,
                        new com.fasterxml.jackson.databind.ObjectMapper());
        // .canvas 工程导入端点 handler。AssetIngest 由 bootstrap 注入（图片摄入栈所在处）；
        // ProjectImporter 在此 new——复用 dispatcher 同款内部 push（snapshot 广播）+ wallRepo 持久化
        // （照 EditOpDispatcher 的 OkSnapshot 收尾范式）。AssetIngest / importConfig 任一缺则禁用端点。
        if (assetIngest == null || importConfig == null) {
            this.projectImportHandler = null;
        } else {
            // scripts.json 导入器。scriptStore 缺（旧装配）→ null，工程导入仍可用、
            //         仅静默忽略包内脚本。命令模板供给复用 commandTemplatesSupplier（缺则空表 →
            //         所有 runCommand 判为 blocked，但规则照常落库）。
            ac.haru.hikaricanvas.canvasfile.ScriptImporter scriptImporter = scriptStore == null
                    ? null
                    : new ac.haru.hikaricanvas.canvasfile.ScriptImporter(
                            scriptStore,
                            commandTemplatesSupplier == null
                                    ? java.util.Map::of : commandTemplatesSupplier);
            // 导入时扫缺字体 / 缺 user 图标 / 缺 userglobal 变量并提示
            // （docs/import-export.md §3.2 step 8）。三件 registry 已是 WebServer 字段；各自可空降级
            // （variableStore 可能为 null）。scanner 整体注入 ProjectImporter（best-effort 可空范式）。
            ac.haru.hikaricanvas.canvasfile.MissingResourceScanner missingResourceScanner =
                    new ac.haru.hikaricanvas.canvasfile.MissingResourceScanner(
                            fontRegistry, iconRegistry, variableStore);
            ac.haru.hikaricanvas.canvasfile.ProjectImporter projectImporter =
                    new ac.haru.hikaricanvas.canvasfile.ProjectImporter(
                            importConfig, assetIngest, push, wallRepo,
                            scriptImporter, auditLog, throttler, missingResourceScanner);
            this.projectImportHandler = new ProjectImportHandler(sessionManager, projectImporter);
        }
    }

    public void start() {
        app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJackson().updateMapper(mapper -> {
                mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
                // 接收方严格——客户端发未知字段一律拒，防协议漂移 / 字段嗅探。
                // 发送侧（server→client）依然宽松：client 用 TypeScript 解析，未声明字段读出 undefined 就行。
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
            }));

            // Jetty WS 默认 idleTimeout = 30s，太短；玩家停手 30s 就被踢。
            // 调到 60s + 前端每 20s 发应用层 ping 保活，两层兜底。
            // 真正的 session 超时由 SessionReaper 负责（wsGrace 5min / idle 30min）。
            cfg.jetty.modifyWebSocketServletFactory(factory -> {
                factory.setIdleTimeout(Duration.ofSeconds(60));
                factory.setMaxTextMessageSize(65536);  // 64KB 上限防 WS flood
            });

            // 静态资源手写 GET（因 Javalin 7 staticFiles.add + fat jar 的 directory
            // discovery 有 bug，改为显式读 classpath 资源）。覆盖 Vite 产物：
            // index.html + assets/*（hash 化文件名）
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.GET, "/", ctx -> serveClasspath(ctx, "web/index.html")));
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.GET, "/assets/{file}", ctx -> {
                        String file = ctx.pathParam("file");
                        // 防路径穿越
                        if (file.contains("/") || file.contains("..")) {
                            ctx.status(400);
                            return;
                        }
                        serveClasspath(ctx, "web/assets/" + file);
                    }));

            // HTTP 预握手
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.GET, "/api/session/{token}", this::handlePreHandshake));

            // 前端加载 palette 的端点；与 Java PaletteLut 读同一份 classpath JSON
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.GET, "/api/palette", ctx -> servePalette(ctx)));

            // 网页首页"近期项目"列表。无玩家认证（127.0.0.1 trust）；返回所有 walls 的 summary
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.GET, "/api/walls", ctx -> ctx.json(wallRepo.listAll())));

            // 模板缩略图端点。Gallery 卡片 <img> 直接拉这条
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.GET, "/api/template/{id}/preview.png", ctx -> {
                        String id = ctx.pathParam("id");
                        byte[] png = templatePreviewService.pngFor(id);
                        if (png == null) {
                            ctx.status(404);
                            return;
                        }
                        ctx.contentType("image/png");
                        // 模板内容不变 → 长缓存；reload 后服务端 invalidate，浏览器靠 ETag/304 路径
                        ctx.header("Cache-Control", "public, max-age=300");
                        ctx.result(png);
                    }));

            // wall 缩略图。HomePage 卡片展示用；按 wall.updatedAt 做粗粒度缓存
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.GET, "/api/wall/{id}/preview.png", ctx -> {
                        String wid = ctx.pathParam("id");
                        var w = wallRepo.loadById(wid).orElse(null);
                        if (w == null) { ctx.status(404); return; }
                        byte[] png = wallPreviewCache.get(
                                wid + "@" + w.updatedAt(),
                                key -> wallPreviewService.renderPng(w.state()));
                        if (png == null) { ctx.status(500); return; }
                        ctx.contentType("image/png");
                        ctx.header("Cache-Control", "public, max-age=60");
                        ctx.result(png);
                    }));

            // 图标资源端点（whitelist 名 + builtin/classpath 优先 + 服主自定义后备）
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.GET, "/api/template-asset/icons/{name}", ctx -> {
                        String name = ctx.pathParam("name");
                        // 移除 .png 后缀（允许 /icons/foo 也允许 /icons/foo.png）
                        if (name.endsWith(".png")) name = name.substring(0, name.length() - 4);
                        if (!TemplateAssetService.isValidName(name)) {
                            ctx.status(400);
                            return;
                        }
                        byte[] png = templateAssetService.iconPng(name);
                        if (png == null) {
                            ctx.status(404);
                            return;
                        }
                        ctx.contentType("image/png");
                        ctx.header("Cache-Control", "public, max-age=3600");
                        ctx.result(png);
                    }));

            // 图片上传 + 下载 + 配额查询
            if (uploadHandler != null) {
                cfg.routes.addEndpoint(new Endpoint(
                        HandlerType.POST, "/api/upload", uploadHandler::handleUpload));
                // URL 粘贴上传（与 file upload 同款校验栈 + SSRF 防御）
                cfg.routes.addEndpoint(new Endpoint(
                        HandlerType.POST, "/api/upload/url", uploadHandler::handleUrlUpload));
                cfg.routes.addEndpoint(new Endpoint(
                        HandlerType.GET, "/api/upload/quota", uploadHandler::handleQuota));
                cfg.routes.addEndpoint(new Endpoint(
                        HandlerType.GET, "/api/upload/{source}", uploadHandler::handleDownload));
            }

            // .canvas 工程导入（multipart 收 zip → 安全解包 → 校验 → 灌会话 → snapshot）
            if (projectImportHandler != null) {
                cfg.routes.addEndpoint(new Endpoint(
                        HandlerType.POST, "/api/project/import", projectImportHandler::handleImport));
            }

            // 字体 advance 表查询端点（用户字体走这条路；内置字体仍由
            // /fonts/{id}.metrics.json 静态文件提供，前端先 fetch 静态后 fallback 此端点）
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.GET, "/api/font/metrics", ctx -> {
                        String id = ctx.queryParam("id");
                        if (id == null || id.isEmpty() || !id.matches("[a-zA-Z0-9_-]+")) {
                            ctx.status(400).result("{\"code\":\"BAD_REQUEST\"}");
                            return;
                        }
                        String json = ac.haru.hikaricanvas.render.FontMetricsTable.serializeToJson(id);
                        if (json == null) {
                            ctx.status(404).result("{\"code\":\"NOT_FOUND\"}");
                            return;
                        }
                        ctx.contentType("application/json").result(json);
                        // 5 min；用户改字体后重启服务即可刷新
                        ctx.header("Cache-Control", "max-age=300, private");
                    }));

            // 字体二进制端点。前端 FontFace API 动态加载（替代 @font-face 静态注册）。
            // 严格 id 白名单 [a-zA-Z0-9_-]+；间接寻址（FontRegistry.loadFontBytes 查表）防 path traversal。
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.GET, "/api/font/file", ctx -> {
                        String id = ctx.queryParam("id");
                        if (id == null || id.isEmpty() || !id.matches("[a-zA-Z0-9_-]+")) {
                            ctx.status(400).result("{\"code\":\"BAD_REQUEST\"}");
                            return;
                        }
                        byte[] bytes = fontRegistry.loadFontBytes(id);
                        if (bytes == null) {
                            ctx.status(404).result("{\"code\":\"NOT_FOUND\"}");
                            return;
                        }
                        // 文件扩展名决定 Content-Type；同 id 文件 immutable，长缓存
                        String mime = "font/ttf";
                        for (FontRegistry.FontInfo info : fontRegistry.listAll()) {
                            if (info.id().equals(id)) {
                                mime = "otf".equals(info.format()) ? "font/otf" : "font/ttf";
                                break;
                            }
                        }
                        ctx.contentType(mime);
                        ctx.header("Cache-Control", "max-age=86400, immutable");
                        ctx.result(bytes);
                    }));

            // 字体清单端点。返所有已注册字体（内置 + 用户）的 metadata。
            // 无鉴权（与 /api/font/metrics 同级 trust）；短缓存（用户字体可能重启后增减）。
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.GET, "/api/font/list", ctx -> {
                        ctx.header("Cache-Control", "max-age=60");
                        ctx.json(Map.of("fonts", fontRegistry.listAll()));
                    }));

            // 矢量图标清单 + 搜索 + 分页。前端 IconPicker 走这条路。
            // query：q（substring，可空）/ category（pack id；支持 "fa-*" 前缀）/ limit（1..200）/ offset（≥0）
            // 响应：{ icons:[{id,displayName,pack,viewBox,source}], total:int, hasMore:bool }
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.GET, "/api/icon/list", ctx -> {
                        String q = ctx.queryParam("q");
                        String category = ctx.queryParam("category");
                        int limit = parseIntOrDefault(ctx.queryParam("limit"), 60);
                        int offset = parseIntOrDefault(ctx.queryParam("offset"), 0);
                        if (limit > 200) limit = 200;
                        if (limit < 1) limit = 1;
                        if (offset < 0) offset = 0;
                        IconRegistry.SearchResult result =
                                iconRegistry.search(q, category, limit, offset);
                        ctx.header("Cache-Control", "max-age=300");
                        ctx.json(Map.of(
                                "icons", result.icons(),
                                "total", result.total(),
                                "hasMore", result.hasMore()));
                    }));

            // 单个图标 path d 拉取。id 形如 fa-solid/heart；查 IconRegistry 表，未注册 404。
            // 严格 fullId 校验（防 path traversal / 嗅探）：用 IconElement.isValidSource 同一正则。
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.GET, "/api/icon/paths", ctx -> {
                        String id = ctx.queryParam("id");
                        if (id == null || id.isEmpty() || id.length() > 64
                                || !ac.haru.hikaricanvas.state.IconElement.isValidSource(id)
                                || ac.haru.hikaricanvas.state.IconElement.isLegacySource(id)) {
                            // legacy PNG 形态不走本端点（前端应拉 /api/template-asset/icons/）
                            ctx.status(400).result("{\"code\":\"BAD_REQUEST\"}");
                            return;
                        }
                        String d = iconRegistry.getPathD(id);
                        String viewBox = iconRegistry.getViewBox(id);
                        if (d == null || viewBox == null) {
                            ctx.status(404).result("{\"code\":\"NOT_FOUND\"}");
                            return;
                        }
                        // path d + viewBox 按 id immutable（重启 / reload 才改）→ 长缓存
                        ctx.header("Cache-Control", "max-age=86400, immutable");
                        ctx.json(Map.of(
                                "id", id,
                                "viewBox", viewBox,
                                // 数组形态预留 v2：单 svg 多 path / 各自 fill 颜色（v1 单元素）
                                "paths", List.of(Map.of("d", d))));
                    }));

            // 创意工坊市场（DB 元数据列表）
            if (templateRepo != null) {
                cfg.routes.addEndpoint(new Endpoint(
                        HandlerType.GET, "/api/templates", this::handleTemplatesList));
            }

            // variable metadata 聚合端点（Picker 自动补全用，5s server-side cache）
            if (variableMetadataHandler != null) {
                cfg.routes.addEndpoint(new Endpoint(
                        HandlerType.GET, "/api/variable/list-all-namespaces",
                        variableMetadataHandler::handle));
            }

            // 命令模板列表端点（积木 runCommand 下拉用）。
            // sessionId 鉴权 + 只返 id/params，绝不泄 command 原文。
            if (commandTemplateHandler != null) {
                cfg.routes.addEndpoint(new Endpoint(
                        HandlerType.GET, "/api/script/command-templates",
                        commandTemplateHandler::handle));
            }

            // WS upgrade Origin 白名单。在 upgrade 前拒绝跨站 WS 攻击（CSWSH）。
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.WEBSOCKET_BEFORE_UPGRADE, "/ws", this::checkWsOrigin));

            // WebSocket
            cfg.routes.addWsHandler(WsHandlerType.WEBSOCKET, "/ws", wsCfg -> {
                wsCfg.onConnect(ctx -> {
                    // 起 auth 超时任务，N 秒后未 auth → close 4001
                    scheduleAuthTimeout(ctx);
                    log.info("WS connected (sid=" + ctx.sessionId() + ", auth-timeout=" + wsAuthTimeoutSeconds + "s)");
                });
                wsCfg.onClose(ctx -> {
                    cancelAuthTimeout(ctx);
                    String sid = ctx.attribute(ATTR_SESSION_ID);
                    if (sid != null) {
                        // 原子 CAS：只清空自己绑的那个 ctx，避免 race 把新连接的 mapping 抹掉
                        wsBySession.remove(sid, ctx);
                        sessionManager.markDisconnected(sid);
                        log.info("WS closed, sessionId=" + sid);
                    } else {
                        log.info("WS closed (pre-auth)");
                    }
                });
                wsCfg.onMessage(this::handleMessage);
                wsCfg.onError(ctx -> log.log(Level.WARNING, "WS error", ctx.error()));
            });
        });
        app.start(host, port);
        log.info("WebServer listening on " + host + ":" + port);
    }

    public void stop() {
        // 先停 scheduler，否则 JVM 退出时 daemon 线程虽不阻塞但 cancel 顺序不可控
        authTimeoutScheduler.shutdownNow();
        pendingAuthTimers.clear();
        if (app != null) {
            app.stop();
            log.info("WebServer stopped");
        }
    }

    // ---------- 未认证 WS 超时 ----------

    /**
     * onConnect 注册一个 N 秒后触发的 close 任务；如果到时间还未通过 auth（即没有
     * 设置 {@link #ATTR_SESSION_ID}）就主动 close 4001，避免空连接累积。
     */
    private void scheduleAuthTimeout(WsContext ctx) {
        String key = ctx.sessionId();
        ScheduledFuture<?> fut = authTimeoutScheduler.schedule(() -> {
            try {
                if (ctx.attribute(ATTR_SESSION_ID) == null) {
                    log.info("WS auth timeout (" + wsAuthTimeoutSeconds + "s) → close 4001 sid=" + key);
                    try {
                        ctx.closeSession(4001, "auth_timeout");
                    } catch (Exception ignored) {}
                }
            } finally {
                pendingAuthTimers.remove(key);
            }
        }, wsAuthTimeoutSeconds, TimeUnit.SECONDS);
        // 并发新连接复用 sid 极不可能；put 即可
        pendingAuthTimers.put(key, fut);
    }

    /**
     * 取消未认证超时任务。返回 {@code true}=成功阻止其运行（auth 可安全注册 ctx）；
     * {@code false}=该任务已运行 / 正在运行（连接将 / 已被 close 4001——auth 应放弃注册，
     * 避免把已 close 的 ctx 写进 {@link #wsBySession} 导致后续 push 静默失败）。onClose 调用忽略返回值。
     */
    private boolean cancelAuthTimeout(WsContext ctx) {
        ScheduledFuture<?> fut = pendingAuthTimers.remove(ctx.sessionId());
        if (fut == null) return false;   // 已被超时任务在 finally 自移除（即它已运行）→ 不安全
        return fut.cancel(false);        // true=未启动、已阻止；false=已 / 正在运行
    }

    // ---------- WS upgrade Origin 白名单 ----------

    /**
     * Origin 检查。放行：1) 无 Origin（同源 fetch / 非浏览器 client）；
     * 2) 127.0.0.1:<port> / localhost:<port>（任何端口都接受，因为本机用户友好）；
     * 3) 等于 network.host:port（被代理时的同源）；4) 配置白名单。
     * 拒绝 → 403 + 不 upgrade。
     */
    private void checkWsOrigin(Context ctx) {
        String origin = ctx.header("Origin");
        if (isOriginAllowed(origin)) return;
        log.warning("WS upgrade rejected: Origin=" + origin + " not in allowlist; "
                + "add to network.allowed-origins if intentional");
        ctx.status(403).result("forbidden");
        ctx.skipRemainingHandlers();
    }

    boolean isOriginAllowed(String origin) {
        if (origin == null || origin.isEmpty() || "null".equalsIgnoreCase(origin)) {
            return true;  // 同源 fetch / 非浏览器
        }
        String low = origin.toLowerCase(Locale.ROOT);
        // 1) 127.0.0.1:* / localhost:*（任何方案 + 端口，开发环境 vite proxy 等）
        if (low.startsWith("http://127.0.0.1:") || low.startsWith("https://127.0.0.1:")
                || low.startsWith("http://localhost:") || low.startsWith("https://localhost:")
                || low.equals("http://127.0.0.1") || low.equals("http://localhost")) {
            return true;
        }
        // 2) 同源（与 host:port 完全匹配）
        String selfHttp = "http://" + host + ":" + port;
        String selfHttps = "https://" + host + ":" + port;
        if (low.equals(selfHttp) || low.equals(selfHttps)) return true;
        // 3) 白名单（严格大小写敏感匹配 scheme + host + port）
        for (String allowed : allowedOrigins) {
            if (allowed != null && origin.equals(allowed)) return true;
        }
        return false;
    }

    // ---------- 静态资源 ----------

    private void serveClasspath(Context ctx, String resource) {
        // 静态资源一律禁 MIME 嗅探：即便 Content-Type 配错也不让浏览器宽容猜测掩盖问题。
        ctx.header("X-Content-Type-Options", "nosniff");
        java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            ctx.status(404);
            return;
        }
        // guessMime 猜不到时兜底 application/octet-stream（安全下载），别落到 Javalin 默认 text/plain。
        String mime = guessMime(resource);
        ctx.contentType(mime != null ? mime : "application/octet-stream");
        ctx.result(in);
    }

    /** 直读 classpath 根的 palette.json。浏览器端 PaletteLut 用它构建 LUT。 */
    private void servePalette(Context ctx) {
        java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("palette.json");
        if (in == null) {
            ctx.status(500).json(Map.of(
                    "error", "palette.json missing from classpath",
                    "hint", "run ./gradlew generatePalette"));
            return;
        }
        ctx.contentType("application/json; charset=utf-8");
        // 长期缓存 —— palette 只跟 Paper 版本绑定，极少变
        ctx.header("Cache-Control", "public, max-age=86400, immutable");
        ctx.result(in);
    }

    /**
     * 按扩展名映射 MIME。猜不到返 null —— 调用方（{@link #serveClasspath}）负责兜底到
     * {@code application/octet-stream}（安全下载语义），别让响应落到 Javalin 默认的
     * {@code text/plain}。仅供本包 + 单测使用（package-private）。
     */
    static String guessMime(String path) {
        // 文档 / 脚本 / 样式
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js"))   return "application/javascript";
        if (path.endsWith(".mjs"))  return "application/javascript";
        if (path.endsWith(".css"))  return "text/css";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".map"))  return "application/json";        // source map 是 JSON
        if (path.endsWith(".wasm")) return "application/wasm";
        if (path.endsWith(".txt"))  return "text/plain; charset=utf-8";
        // 字体
        if (path.endsWith(".woff2"))return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".ttf"))  return "font/ttf";
        if (path.endsWith(".otf"))  return "font/otf";
        // 图片
        if (path.endsWith(".svg"))  return "image/svg+xml";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".ico"))  return "image/x-icon";
        return null;
    }

    // ---------- HTTP 预握手 ----------

    private void handlePreHandshake(Context ctx) {
        String token = ctx.pathParam("token");
        TokenService.ValidateResult result = tokenService.peek(token);
        if (result instanceof TokenService.ValidateResult.Rejected rej) {
            log.fine("pre-handshake rejected: " + rej.reason());
            ctx.status(401).json(Map.of("error", "AUTH_FAILED"));
            return;
        }
        TokenService.ValidateResult.Ok ok = (TokenService.ValidateResult.Ok) result;
        Session session = sessionManager.byId(ok.sessionId());
        if (session == null || session.state() == SessionState.CLOSING) {
            ctx.status(409).json(Map.of("error", "SESSION_CLOSED"));
            return;
        }

        // 预握手只返 ok + wsUrl + playerName（低敏感），所有敏感元数据
        // （sessionId / wall / templates / mapIds 等）通过 WS ready 帧下发。
        // 修复 HTTP 响应可被嗅探后跨 session 访问的攻击面。
        ctx.json(Map.of(
                "ok", true,
                "playerName", session.playerName(),
                "wsUrl", "/ws"));
    }

    /**
     * 返所有模板的元数据（含 owner / featured / 下载数）+ 按 featured/created
     * 排序。前端 HomePage Marketplace 用这个数据 + selfUuid 决定"我的"判定。
     */
    private void handleTemplatesList(io.javalin.http.Context ctx) {
        java.util.List<ac.haru.hikaricanvas.storage.TemplateRepo.Row> rows = templateRepo.listMarketplace(0);
        java.util.List<Map<String, Object>> json = new java.util.ArrayList<>(rows.size());
        for (ac.haru.hikaricanvas.storage.TemplateRepo.Row r : rows) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("templateId", r.templateId());
            // 隐私——公开端点不暴露 ownerUuid；保留 ownerName 用于"我的"判定的友好展示
            m.put("ownerName", r.ownerName());
            m.put("displayName", r.displayName());
            m.put("description", r.description());
            m.put("builtin", r.builtin());
            m.put("featured", r.featured());
            m.put("downloadCount", r.downloadCount());
            m.put("createdAt", r.createdAt());
            m.put("updatedAt", r.updatedAt());
            json.add(m);
        }
        ctx.json(json);
    }

    /**
     * ready 帧下发 TemplateSpec 列表（协议 §3.2）。
     *
     * <p>与 {@link TemplateRegistry#byIdForApply} 共用同一可见性判定——对 source==USER
     * 且 ownerUuid 非 caller 且无 {@code canvas.template.use-others} bypass 的条目整条剔除，
     * 防其他玩家私有模板的完整 {@code raw_state} 画布内容经 ready 帧外泄（apply 端隔离已有，
     * 此前 list 端零过滤使其形同虚设）。builtin / server 模板始终可见。</p>
     *
     * @param callerUuid 请求方玩家 UUID；{@code null} 等同非 owner
     * @param hasBypass  调用方是否持 {@code canvas.template.use-others}
     */
    private List<TemplateSpec> listTemplates(UUID callerUuid, boolean hasBypass) {
        return templateRegistry.listVisibleTo(callerUuid, hasBypass).stream()
                .map(TemplateEntry::spec)
                .toList();
    }

    // ---------- WS 消息 ----------

    private void handleMessage(WsMessageContext ctx) {
        Envelope in;
        try {
            in = ctx.messageAsClass(Envelope.class);
        } catch (Exception e) {
            // 错误脱敏——不 echo Jackson 异常（含类路径 / 字段名 / unknown property 名）。
            // 细节走 server 日志，client 拿固定 code。
            log.log(Level.FINE, "WS malformed envelope", e);
            ctx.send(Envelope.error(null, "INVALID_PAYLOAD", null));
            return;
        }
        if (in == null || in.op() == null || in.op().isBlank()) {
            String id = in == null ? null : in.id();
            ctx.send(Envelope.error(id, "INVALID_PAYLOAD", "missing op"));
            return;
        }

        String bound = ctx.attribute(ATTR_SESSION_ID);
        if (bound == null) {
            // 必须先 auth
            if (!"auth".equals(in.op())) {
                ctx.send(Envelope.error(in.id(), "AUTH_FAILED", "expected auth first"));
                closeAuthFailed(ctx, "pre-auth op=" + in.op());
                return;
            }
            handleAuth(ctx, in);
            return;
        }

        // 已认证
        sessionManager.touch(bound);
        switch (in.op()) {
            case "ping" -> ctx.send(Envelope.pong(in.id()));
            case "element.add",
                 "element.update",
                 "element.delete",
                 "element.reorder",
                 "element.transform",
                 "element.move-to-layer",
                 "layer.create",
                 "layer.delete",
                 "layer.update",
                 "layer.reorder",
                 "layer.duplicate",
                 "layer.set-active",
                 "canvas.resize",
                 "canvas.background",
                 "canvas.tweenFps",
                 "canvas.grid",
                 "canvas.guides.set",
                 "timeline.create",
                 "timeline.update",
                 "timeline.delete",
                 "timeline.play",
                 "timeline.pause",
                 "timeline.seek",
                 "keyframe.add",
                 "keyframe.update",
                 "keyframe.delete",
                 "keyframe.move",
                 "undo",
                 "redo",
                 "history.mark",
                 "template.apply" -> editOpDispatcher.dispatch(ctx, in, bound);
            case "brush.start", "brush.point", "brush.end", "brush.cancel"
                    -> brushOpDispatcher.dispatch(ctx, in, bound);
            case "wall.lock", "wall.unlock", "wall.alias", "wall.refresh"
                    -> wallOpDispatcher.dispatch(ctx, in, bound);
            case "template.save", "template.delete", "template.feature", "template.unfeature"
                    -> templateOpDispatcher.dispatch(ctx, in, bound);
            case "variable.create", "variable.update", "variable.set",
                 "variable.delete", "variable.bind" -> {
                if (variableOpDispatcher == null) {
                    ctx.send(Envelope.error(in.id(), "INTERNAL_ERROR",
                            "variable system not initialized"));
                } else {
                    variableOpDispatcher.dispatch(ctx, in, bound);
                }
            }
            case "schedule.upsert", "schedule.entry.add", "schedule.entry.update",
                 "schedule.entry.delete", "schedule.list" -> {
                if (scheduleOpDispatcher == null) {
                    ctx.send(Envelope.error(in.id(), "INTERNAL_ERROR",
                            "schedule system not initialized"));
                } else {
                    scheduleOpDispatcher.dispatch(ctx, in, bound);
                }
            }
            // rail.* 12 op + rail.line.detail（共 13 op）
            case "rail.line.list", "rail.line.detail",
                 "rail.line.create", "rail.line.update", "rail.line.delete",
                 "rail.station.add", "rail.station.update", "rail.station.delete",
                 "rail.run.create", "rail.run.update", "rail.run.delete",
                 "rail.run.timetable.set", "rail.wall.bind" -> {
                if (railOpDispatcher == null) {
                    ctx.send(Envelope.error(in.id(), "INTERNAL_ERROR",
                            "rail system not initialized"));
                } else {
                    railOpDispatcher.dispatch(ctx, in, bound);
                }
            }
            case "variable.alias.set", "variable.alias.clear", "variable.alias.list" -> {
                if (variableAliasDispatcher == null) {
                    ctx.send(Envelope.error(in.id(), "INTERNAL_ERROR",
                            "variable alias system not initialized"));
                } else {
                    variableAliasDispatcher.dispatch(ctx, in, bound);
                }
            }
            // script.* 5 op
            case "script.create", "script.update", "script.delete",
                 "script.enable", "script.test" -> {
                if (scriptOpDispatcher == null) {
                    ctx.send(Envelope.error(in.id(), "INTERNAL_ERROR",
                            "script system not initialized"));
                } else {
                    scriptOpDispatcher.dispatch(ctx, in, bound);
                }
            }
            default -> ctx.send(Envelope.error(in.id(), "INVALID_OP", "unknown op: " + in.op()));
        }
    }

    // ---------- auth ----------

    private void handleAuth(WsMessageContext ctx, Envelope in) {
        // token 暴力枚举防御：进入校验前先做 per-IP 速率限制。
        // 注意：tokenRateLimiter 可能为 null（旧测试构造路径）— 那种情况下不限流。
        // 在 protocol version / token 校验前限流的好处：哪怕攻击者发垃圾 payload 也消耗配额，
        // 防止"先做 version 检查再试 token"绕过；缺点是合法 client 发错 client_v 也被算配额，
        // 但合法 client 不会 retry 10 次 client_v，权衡上限流先行更安全。
        String authIp = clientIp(ctx);
        if (tokenRateLimiter != null && !tokenRateLimiter.tryConsume(authIp)) {
            log.warning("WS auth rate limited: ip=" + authIp
                    + " (≥" + tokenRateLimiter.perMinute() + "/min); close 4429");
            if (auditLog != null) {
                java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
                details.put("perMinute", tokenRateLimiter.perMinute());
                details.put("windowMs", tokenRateLimiter.windowMs());
                // 注意：IP 是敏感数据 — 仅记 SHA-256 hash 防泄露；与 sessionManager.bindOrCheckIp
                // 思路一致（详见 docs/security.md §11 audit 字段规范）
                details.put("ipHash", sha256HexShort(authIp));
                auditLog.record("TOKEN_RATE_LIMIT_EXCEEDED",
                        null, null, null, sha256HexShort(authIp), details);
            }
            ctx.send(Envelope.error(in.id(), "RATE_LIMITED", null));
            closeTokenRateLimited(ctx, "ip=" + authIp);
            return;
        }

        // 从 payload 取 token
        if (!(in.payload() instanceof Map<?, ?> pl)) {
            ctx.send(Envelope.error(in.id(), "AUTH_FAILED", "missing payload"));
            closeAuthFailed(ctx, "missing payload");
            return;
        }

        // 协议版本协商。client 在 auth payload 携 {@code client_v}（新字段）
        // 或旧 {@code clientProtocolVersion}（兼容名）。server 校验范围
        // [SUPPORTED_MIN, SUPPORTED_MAX]；不在范围 → close 4002 + VERSION_MISMATCH。
        // 注意检查在 token consume 之前，避免为不兼容客户端浪费一次性 token。
        Object cpv = pl.get("client_v");
        if (cpv == null) cpv = pl.get("clientProtocolVersion");  // 旧字段名兼容
        if (!(cpv instanceof Number cpvN) || !Protocol.isSupported(cpvN.intValue())) {
            // 不 echo 客户端发的版本号（防嗅探 + 信息漏）；客户端用 i18n 自己提示
            log.fine("WS auth: client_v=" + cpv + " not in ["
                    + Protocol.SUPPORTED_MIN + ", " + Protocol.SUPPORTED_MAX + "]");
            ctx.send(Envelope.error(in.id(), "VERSION_MISMATCH", null));
            closeVersionMismatch(ctx, "client_v=" + cpv);
            return;
        }
        int negotiatedV = cpvN.intValue();

        Object tokenObj = pl.get("token");
        if (!(tokenObj instanceof String token)) {
            ctx.send(Envelope.error(in.id(), "AUTH_FAILED", "token missing"));
            closeAuthFailed(ctx, "token field not a string");
            return;
        }

        TokenService.ValidateResult vr = tokenService.consume(token);
        if (vr instanceof TokenService.ValidateResult.Rejected rej) {
            log.fine("WS auth rejected: " + rej.reason());
            ctx.send(Envelope.error(in.id(), "AUTH_FAILED", null));
            closeAuthFailed(ctx, rej.reason().name());
            return;
        }
        TokenService.ValidateResult.Ok ok = (TokenService.ValidateResult.Ok) vr;

        Session session = sessionManager.byId(ok.sessionId());
        if (session == null || session.state() == SessionState.CLOSING) {
            ctx.send(Envelope.error(in.id(), "AUTH_FAILED", "session not available"));
            closeAuthFailed(ctx, "session missing/closing");
            return;
        }

        // auth 时重新校验 canvas.edit。token 签发后玩家被撤权
        // （lp / pex / 配置 reload）→ 拒绝继续认证。Bukkit.hasPermission 必须主线程，
        // 用 callSyncMethod 同步等待结果（auth 路径是 Jetty 线程，可阻塞少量时间）。
        // 离线玩家放行（player == null）—— 玩家已不在线，token 又一次性，重连受 IP 绑定保护。
        // 同一主线程 hop 顺带解析 canvas.template.use-others（ready 帧模板可见性过滤用），
        // 避免再起一次 callSyncMethod；离线玩家无 bypass（fail-closed，只看到自己的模板）。
        boolean templateBypass = false;
        try {
            boolean[] perms = org.bukkit.Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                org.bukkit.entity.Player live = org.bukkit.Bukkit.getPlayer(session.playerUuid());
                if (live == null) return new boolean[]{true, false};  // 玩家离线：放行 + 无 bypass
                return new boolean[]{
                        live.hasPermission("canvas.edit"),
                        live.hasPermission("canvas.template.use-others")};
            }).get(2, java.util.concurrent.TimeUnit.SECONDS);
            boolean allowed = perms != null && perms[0];
            templateBypass = perms != null && perms[1];
            if (!allowed) {
                log.warning("WS auth permission revoked sid=" + session.id()
                        + " player=" + session.playerName() + " canvas.edit=false");
                if (auditLog != null) {
                    java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
                    details.put("operation", "ws.auth");
                    details.put("required", "canvas.edit");
                    auditLog.record("PERMISSION_DENIED",
                            session.playerUuid().toString(), session.playerName(),
                            session.id(), null, details);
                }
                ctx.send(Envelope.error(in.id(), "PERMISSION_DENIED",
                        "canvas.edit permission revoked"));
                closePermissionRevoked(ctx, "canvas.edit_revoked");
                return;
            }
        } catch (java.util.concurrent.TimeoutException te) {
            // 主线程繁忙：保守放行，避免拒绝合法玩家
            log.warning("WS auth permission check timeout sid=" + session.id()
                    + "; allowing through (main thread busy)");
        } catch (InterruptedException ie) {
            // 复位中断标志，再 fail-closed 拒绝（不能在中断态下静默放行）
            Thread.currentThread().interrupt();
            log.log(Level.WARNING, "WS auth permission check interrupted sid=" + session.id(), ie);
            ctx.send(Envelope.error(in.id(), "PERMISSION_DENIED",
                    "permission check interrupted"));
            closePermissionRevoked(ctx, "perm_check_interrupted");
            return;
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.RejectedExecutionException
                | java.util.concurrent.CancellationException pe) {
            // 非超时异常（scheduler 关停/拒绝、callable 内部抛错）改 fail-closed 拒绝，
            // 而非原先一律保守放行——避免被撤权玩家恰逢 scheduler 异常窗口绕过 canvas.edit 复查。
            log.log(Level.WARNING, "WS auth permission check failed sid=" + session.id()
                    + "; denying (fail-closed)", pe);
            ctx.send(Envelope.error(in.id(), "PERMISSION_DENIED",
                    "permission check failed"));
            closePermissionRevoked(ctx, "perm_check_failed");
            return;
        }

        // 会话级 IP 绑定。首次 auth 时 sessionManager 写下 boundIp；reconnect 必须 IP 同源。
        // 不同 → 当作 AUTH_FAILED 拒（外部只看到 4001，不区分；服务器侧 log.warning 详细原因）。
        // 攻击者 XSS / 抓包拿到 reconnectToken 后从异机重连必撞 MISMATCH。
        // 复用上方限流路径 capture 的 authIp，避免重复调用 clientIp(ctx)
        String presentedIp = authIp;
        SessionManager.IpBindResult ipRes = sessionManager.bindOrCheckIp(session.id(), presentedIp);
        if (ipRes == SessionManager.IpBindResult.MISMATCH) {
            log.warning("WS auth IP mismatch sid=" + session.id()
                    + " boundIp=" + session.boundIp() + " presentedIp=" + presentedIp
                    + "; token may be replayed from a different host");
            ctx.send(Envelope.error(in.id(), "AUTH_FAILED", null));
            closeAuthFailed(ctx, "ip_mismatch");
            return;
        }

        // 读前端携带的编辑器 UI 语言（game locale id 形态，如 zh_cn / en_us）→
        // Messages.resolveLocaleId 规范化 + 兜底后存入 session，供脚本校验报错按编辑器语言渲染。
        // 缺失 / 非字符串时不设（editorLocale 保持 null，渲染回退默认 locale）；messages 为 null
        // （旧测试装配）也跳过。放在 IP 绑定通过后、markActive 前：session 已确认可用。
        if (messages != null) {
            Object rawLocale = pl.get("locale");
            if (rawLocale instanceof String localeStr && !localeStr.isBlank()) {
                session.setEditorLocale(messages.resolveLocaleId(localeStr));
            }
        }

        // /canvas open 同 wall 路径下 session 已是 ACTIVE；只刷活跃时间，不再走 markActive 转移。
        // 只有首次 auth（ISSUED → ACTIVE）才调 markActive。
        // 极窄 TOCTOU——读 state 与 markActive 内部重读之间，主线程 reaper 可能恰好
        // 以 issued-timeout cancel 本 session（state→CLOSING / byId 移除），markActive/touch 会抛
        // IllegalState/IllegalArgumentException。原先无 try/catch 时异常逃逸到 Javalin onError 只 log，
        // client 收不到 AUTH_FAILED 而是静默卡死。改为捕获后发 AUTH_FAILED + close，与 841 的
        // null/CLOSING 检查同语义优雅降级。
        try {
            if (session.state() == SessionState.ISSUED) {
                sessionManager.markActive(session.id());
            } else {
                sessionManager.touch(session.id());
            }
        } catch (IllegalStateException | IllegalArgumentException se) {
            log.log(Level.WARNING, "WS auth markActive/touch failed sid=" + session.id()
                    + " (session likely reaped concurrently)", se);
            ctx.send(Envelope.error(in.id(), "AUTH_FAILED", "session not available"));
            closeAuthFailed(ctx, "session reaped during auth");
            return;
        }
        // R1：先取消未认证超时任务并 gate——只有成功阻止其运行才注册 ctx。若超时已触发 / 正在
        // close 此连接，放弃注册，避免把已被 close 4001 的 ctx 写进 wsBySession 致后续 push 静默失败、
        // 客户端假死。竞态窗口（put↔attr 之间超时触发）由此关闭。
        if (!cancelAuthTimeout(ctx)) {
            log.info("WS auth raced auth-timeout close (sid=" + session.id() + "), abort registration");
            return;  // 连接将被超时任务 close 4001；客户端按 close 重新发起 auth
        }
        // 先设 attr 再 put：超时任务此刻已被取消，onClose 总能据 attr 找到 sid 做 CAS 清理。
        ctx.attribute(ATTR_SESSION_ID, session.id());
        // 旧的 WS ctx（同 sessionId）若还在，关掉再覆盖，避免双连
        WsContext oldCtx = wsBySession.put(session.id(), ctx);
        if (oldCtx != null && oldCtx != ctx) {
            try { oldCtx.closeSession(4003, "session-takeover"); } catch (Exception ignored) {}
        }

        // token rotate：auth 成功后立即 rotate 新 token 交回前端，供 WS 断线重连重新 auth。
        // 契约见 docs/security.md §2.2 / docs/protocol.md §11。
        String reconnectToken = tokenService.rotate(
                session.playerUuid(), session.playerName(), session.id());

        // T4：ready payload 中的 projectState 直接由 session 持有的权威状态序列化
        ProjectState state = session.projectState();

        // 附带 wall 元数据（wallId / alias / lockedAt + ownerUuid + selfUuid），前端 TopBar 显示。
        // 字段 publishedAt 改名 lockedAt；新增 ownerUuid + selfUuid
        // 供前端判 isOwner = (selfUuid === ownerUuid)。
        String wallId = session.wallId();
        String alias = null;
        Long lockedAt = null;
        String ownerUuid = null;
        if (wallId != null) {
            var w = wallRepo.loadById(wallId).orElse(null);
            if (w != null) {
                alias = w.alias();
                lockedAt = w.publishedAt();  // DB 列名保留 published_at，语义为 lock 时间戳
                ownerUuid = w.ownerUuid().toString();  // Wall.ownerUuid() 返回 UUID，前端 selfUuid 也是 String
            }
        }

        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("sessionId", session.id());
        payload.put("serverVersion", serverVersion);
        payload.put("protocolVersion", ProjectState.PROTOCOL_VERSION);
        // accepted_v = server 实际同意的 business protocol 版本（来自 auth 中
        // 协商的 client_v，目前固定 2）。client 收到后应双向校验：accepted_v !== CLIENT_V → 断开。
        payload.put("accepted_v", negotiatedV);
        payload.put("reconnectToken", reconnectToken);
        payload.put("projectState", state);
        if (wallId != null) payload.put("wallId", wallId);
        if (alias != null) payload.put("alias", alias);
        if (lockedAt != null) payload.put("lockedAt", lockedAt);
        if (ownerUuid != null) payload.put("ownerUuid", ownerUuid);
        payload.put("selfUuid", session.playerUuid().toString());
        // TemplateSpec 下发（协议 §3.2），前端无需独立接口
        // 按 caller owner + canvas.template.use-others 过滤——只下发 builtin/server +
        // 自己的 user 模板（或持 bypass 时全部），堵住其他玩家私有模板 rawState 经 ready 帧外泄。
        payload.put("templates", listTemplates(session.playerUuid(), templateBypass));
        // 携带 wall 可见的变量快照，前端无需额外 HTTP round-trip
        // 初始化 VariableStore mirror。改用 listVisibleToWall（不依赖 byWall 倒排索引），
        // 解决 ready payload 鸡生蛋问题：wall 刚 open 时 Compositor 尚未渲染 → listByWall 返空 →
        // 前端漏掉 system / schedule / scoreboard / papi 等内置变量 → live preview 出现误报
        // "变量已删除" 红色 banner。listVisibleToWall 按 namespace 形态判定可见性。
        // VariableDto 主动剔除 referencedByWalls 字段防泄露。
        if (variableStore != null && wallId != null) {
            java.util.List<ac.haru.hikaricanvas.variable.Variable> vars =
                    variableStore.listVisibleToWall(wallId);
            java.util.List<ac.haru.hikaricanvas.variable.VariableDto> dtos =
                    new java.util.ArrayList<>(vars.size());
            for (ac.haru.hikaricanvas.variable.Variable v : vars) {
                // 注入 userglobal owner 信息供前端 Picker / Panel 区分"我的全局 / 其他全局"
                dtos.add(ac.haru.hikaricanvas.variable.VariableDto.from(v, variableStore));
            }
            payload.put("variables", dtos);
        } else {
            payload.put("variables", java.util.List.of());
        }
        // 携带当前 wall 的全部变量别名（fullName → alias 字符串映射），让前端
        // VariableAliasStore 一次性初始化。null wall 或 dao 未配时回空 map。
        if (variableAliasDao != null && wallId != null) {
            payload.put("aliases", variableAliasDao.loadByWall(wallId));
        } else {
            payload.put("aliases", java.util.Map.of());
        }
        // 携带当前 wall 的脚本规则快照（ScriptRule 列表，wire 形态由注解 serializer
        // 决定），让前端 ScriptStore mirror 一次性初始化。null wall 或 store 未配时回空 list。
        payload.put("scripts", (scriptStore != null && wallId != null)
                ? scriptStore.listByWall(wallId) : java.util.List.of());
        // 携带当前 wall 的铁路绑定（line + station + direction），让 ScheduleManagerModal
        // 一打开就知道是否走 RailScheduleProvider 路径。未绑定时返 null。
        if (railOpDispatcher != null && wallId != null) {
            try {
                // 直接 query RailDao（dispatcher 内部已持有）；这里复用 plugin 装配链
                ac.haru.hikaricanvas.rail.WallRailBinding b =
                        ac.haru.hikaricanvas.web.RailOpDispatcher.lookupBinding(railOpDispatcher, wallId);
                if (b != null && b.lineId() != null) {
                    java.util.Map<String, Object> bMap = new java.util.LinkedHashMap<>();
                    bMap.put("wallId", b.wallId());
                    bMap.put("lineId", b.lineId());
                    if (b.stationId() != null) bMap.put("stationId", b.stationId());
                    if (b.direction() != null) bMap.put("direction", b.direction());
                    payload.put("railBinding", bMap);
                } else {
                    payload.put("railBinding", null);
                }
            } catch (Exception e) {
                payload.put("railBinding", null);
            }
        } else {
            payload.put("railBinding", null);
        }
        ctx.send(Envelope.of("ready", in.id(), payload));
    }

    // ---------- 服务端主动推送 ----------

    /**
     * 推送 {@code state.snapshot}（全量状态）。用于 undo 之后、template.apply 之后，
     * 或前端请求全量刷新时使用。
     *
     * @return 是否成功发送（false = 该 session 没有活跃 WS 连接）
     */
    public boolean pushSnapshot(String sessionId, ProjectState state) {
        WsContext ctx = wsBySession.get(sessionId);
        if (ctx == null) return false;
        String id = "s-" + serverIdSeq.incrementAndGet();
        ctx.send(Envelope.of("state.snapshot", id, Map.of("projectState", state)));
        return true;
    }

    /**
     * 推送 {@code state.patch}（RFC 6902 子集增量）。每个 element/canvas op 成功后调用。
     *
     * @return 是否成功发送
     */
    public boolean pushPatch(String sessionId, StatePatch patch) {
        WsContext ctx = wsBySession.get(sessionId);
        if (ctx == null) return false;
        String id = "s-" + serverIdSeq.incrementAndGet();
        ctx.send(Envelope.of("state.patch", id, patch));
        return true;
    }

    /**
     * 推任意服务端主动 op（首个消费者 {@code script.trace}——
     * ScriptRunner 线程在试跑结束时经 {@link OpPushCallback#pushOp} 调到这里；
     * {@code WsContext.send} 线程安全，与 pushPatch 的跨线程使用同纪律）。
     *
     * @return 是否成功发送（false = 该 session 没有活跃 WS 连接，调用方静默丢）
     */
    public boolean pushOp(String sessionId, String op, Object payload) {
        WsContext ctx = wsBySession.get(sessionId);
        if (ctx == null) return false;
        String id = "s-" + serverIdSeq.incrementAndGet();
        ctx.send(Envelope.of(op, id, payload));
        return true;
    }

    /**
     * 转交 {@code script.test} 试跑入口（HikariCanvas onEnable 在
     * ScriptRunner 装配完成后注入；dispatcher 侧 volatile 可见）。
     * scriptStore 未配置（dispatcher 为 null）时 no-op。
     */
    public void setScriptTestLauncher(ac.haru.hikaricanvas.script.engine.ScriptTestLauncher launcher) {
        if (scriptOpDispatcher != null) {
            scriptOpDispatcher.setTestLauncher(launcher);
        }
    }

    /**
     * 服务端主动 forget（/canvas delete / cancel / idle reaper）某 session 时，
     * 立即清掉 {@link #wsBySession} 映射并断开陈旧 WS，不依赖 WS onClose 自然关闭。
     *
     * <p>设计为 {@code SessionManager.addForgetHook} 的回调目标（wiring 在 HikariCanvas
     * onEnable 完成）。幂等：sessionId 不在映射里时是 no-op。广播路径迭代 byId（被 forget
     * 的 session 已从 byId 移除），故无需改广播逻辑——本方法只补关闭陈旧连接这一步。</p>
     *
     * @param sessionId 被 forget 的登录态 sessionId；null 时直接返回
     */
    public void forgetSession(String sessionId) {
        if (sessionId == null) return;
        WsContext ctx = wsBySession.remove(sessionId);
        if (ctx != null) {
            // 服务端主动 forget 后该连接已无对应 session，关闭让 client 重新走 auth。
            try { ctx.closeSession(4001, "session_forgotten"); } catch (Exception ignored) {}
        }
    }

    /** 按 protocol.md §6.2: close 4001 = 认证失败。 */
    private void closeAuthFailed(WsContext ctx, String reason) {
        ctx.closeSession(4001, "AUTH_FAILED");
        log.info("WS closed 4001 AUTH_FAILED: " + reason);
    }

    /**
     * close 4003 = 认证后权限被撤销。沿用 session-takeover 同款
     * 4003 close code（client 看到 4003 即放弃重连）。
     */
    private void closePermissionRevoked(WsContext ctx, String reason) {
        ctx.closeSession(4003, "PERMISSION_REVOKED");
        log.info("WS closed 4003 PERMISSION_REVOKED: " + reason);
    }

    /**
     * 解析 WS 客户端 IP，用于 session-IP 绑定。
     *
     * <p>从 Jetty Session.getRemoteAddress() 取 client socket peer 地址（不是 Host header，
     * 那是服务器端 hostname）。返回形如 {@code "/127.0.0.1:54321"} 的 InetSocketAddress
     * toString —— 我们用 {@link java.net.InetSocketAddress#getAddress()} 拿 IP 字符串。
     *
     * <p>反代场景下 client IP 是反代机的，不是真实玩家。需要反代写 {@code X-Forwarded-For}
     * 并在 nginx/Caddy 配置好。V1 不解析 XFF（避免伪造头攻击）；如果未来要支持，应当
     * 配 {@code trust-proxy} 白名单。</p>
     *
     * <p>取不到 → 返回 {@code "unknown"}。所有 session 的首次 auth 都会绑到 "unknown"，
     * 后续重连只要也是 "unknown" 仍 OK——这是 fail-open；接受这个 trade-off 因为安全
     * 主要靠 token + Origin 白名单，IP 绑定是第三层防御。</p>
     */
    private static String clientIp(WsContext ctx) {
        try {
            java.net.SocketAddress addr = ctx.session.getRemoteSocketAddress();
            if (addr instanceof java.net.InetSocketAddress isa && isa.getAddress() != null) {
                return isa.getAddress().getHostAddress();
            }
            if (addr != null) return addr.toString();
        } catch (Throwable ignored) {}
        return "unknown";
    }

    /** query param 安全 int 解析。null / 非数字 / 越界返默认值。 */
    private static int parseIntOrDefault(String raw, int def) {
        if (raw == null || raw.isEmpty()) return def;
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException e) { return def; }
    }

    /** 按 protocol.md §6.2: close 4002 = 协议版本不匹配（协商化）。 */
    private void closeVersionMismatch(WsContext ctx, String reason) {
        ctx.closeSession(Protocol.CLOSE_PROTOCOL_VERSION_UNSUPPORTED, "protocol_version_unsupported");
        log.info("WS closed " + Protocol.CLOSE_PROTOCOL_VERSION_UNSUPPORTED
                + " protocol_version_unsupported: " + reason);
    }

    /**
     * close 4429 = token 暴力枚举超限。client 应显示"请稍后再试"
     * 而不是自动重连（沿用 HTTP 429 语义）。
     */
    private void closeTokenRateLimited(WsContext ctx, String reason) {
        try {
            ctx.closeSession(Protocol.CLOSE_TOKEN_RATE_LIMITED, "token_rate_limited");
        } catch (Exception ignored) {}
        log.info("WS closed " + Protocol.CLOSE_TOKEN_RATE_LIMITED
                + " token_rate_limited: " + reason);
    }

    /**
     * 某会话在 1 分钟内反复触发输入限流（≥ {@code violationThreshold} 次 RATE_LIMITED）
     * → 主动断连 + 审计。由 {@link SessionRateLimiter#setOnRepeatedViolation} 回调，运行在
     * WS onMessage 线程。close 1008（policy violation）= 终止态，前端不重连。
     *
     * <p>关连接逻辑收口于此单点；6 个 dispatcher 的 {@code rateLimiter.allow()} 调用一律不变。</p>
     */
    private void closeForRepeatedViolation(String sessionId) {
        WsContext ctx = wsBySession.remove(sessionId);
        if (auditLog != null) {
            java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("reason", "repeated input rate-limit violations");
            Session s = sessionManager.byId(sessionId);
            String playerUuid = (s == null || s.playerUuid() == null) ? null : s.playerUuid().toString();
            String playerName = (s == null) ? null : s.playerName();
            auditLog.record("SESSION_RATE_LIMIT_DISCONNECT",
                    playerUuid, playerName, sessionId, null, details);
        }
        if (ctx != null) {
            try {
                ctx.closeSession(Protocol.CLOSE_RATE_LIMIT_VIOLATION, "rate_limit_violation");
            } catch (Exception ignored) {}
            log.info("WS closed " + Protocol.CLOSE_RATE_LIMIT_VIOLATION
                    + " rate_limit_violation: sessionId=" + sessionId);
        }
        rateLimiter.discardSession(sessionId);
    }

    /**
     * token rate limit audit 助手：对 IP 算 sha-256 hex 取前 16 字符。
     * 与 ImageStorage.sha256Hex16 同款短 hex；用 hash 防 IP 原文进 audit_log 表。
     */
    private static String sha256HexShort(String s) {
        if (s == null) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }
}
