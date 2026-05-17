package moe.hikari.canvas.web;

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
import moe.hikari.canvas.render.ProjectionThrottler;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.session.SessionRateLimiter;
import moe.hikari.canvas.session.SessionState;
import moe.hikari.canvas.session.TokenService;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.StatePatch;
import moe.hikari.canvas.template.TemplateEntry;
import moe.hikari.canvas.template.TemplateRegistry;
import moe.hikari.canvas.template.TemplateSpec;
import moe.hikari.canvas.template.preview.TemplatePreviewService;
import moe.hikari.canvas.template.preview.WallPreviewService;
import moe.hikari.canvas.template.asset.TemplateAssetService;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *   <li>M1 demo {@code op=paint} 保留——待 T11 命令族与 WS 编辑协议族成熟后删</li>
 * </ul>
 *
 * <p>M3 已实装 token rotate（auth 成功后回发 {@code reconnectToken} 给前端，供 WS
 * 断线重连重新 auth 使用）。契约见 {@code docs/security.md §2.2}、{@code docs/protocol.md §11}。</p>
 *
 * <p>M15.x god-class 拆分：编辑 / brush / wall / template 四组 op 分发已搬到
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
    private final Runnable paintHandler;  // M1 demo
    /** M16 P1.2：WS auth 超时秒。0 < x ≤ 60。 */
    private final int wsAuthTimeoutSeconds;
    /** M16 P1.3：除回环 + 同源外的额外 Origin 白名单（公网反代用）。 */
    private final List<String> allowedOrigins;
    private final moe.hikari.canvas.storage.WallRepo wallRepo;
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final TemplateRegistry templateRegistry;
    private final TemplatePreviewService templatePreviewService;
    private final TemplateAssetService templateAssetService;
    private final WallPreviewService wallPreviewService;
    private final moe.hikari.canvas.image.UploadHandler uploadHandler;
    private final moe.hikari.canvas.storage.TemplateRepo templateRepo;
    private final moe.hikari.canvas.storage.AuditLog auditLog;

    // ---------- 拆分后的 dispatcher（M15.x god-class 拆分）----------
    private final EditOpDispatcher editOpDispatcher;
    private final BrushOpDispatcher brushOpDispatcher;
    private final WallOpDispatcher wallOpDispatcher;
    private final TemplateOpDispatcher templateOpDispatcher;

    /**
     * M7 wall 缩略图缓存：key = "wallId@updatedAt"，value = PNG bytes。
     * M15.1 P0-16：Caffeine 替代 ConcurrentHashMap（ConcurrentHashMap 不收缩）；
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
    /** 服务端主动推送 {@code s-<N>} 的自增计数。 */
    private final AtomicLong serverIdSeq = new AtomicLong(0);

    /**
     * M16 P1.2：未认证 WS 连接的 close timer。key = ctx 的 sessionId() 字符串
     * （Javalin 的 WsContext 唯一标识，不是登录态 sessionId）。
     * onConnect → 注册；auth 成功 / onClose → cancel & remove。
     */
    private final ConcurrentMap<String, ScheduledFuture<?>> pendingAuthTimers = new ConcurrentHashMap<>();
    /** P1.2 内部专用，单线程 daemon scheduler；够轻。 */
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
                     moe.hikari.canvas.storage.WallRepo wallRepo,
                     moe.hikari.canvas.deploy.FrameDeployer frameDeployer,
                     TemplateRegistry templateRegistry,
                     TemplatePreviewService templatePreviewService,
                     TemplateAssetService templateAssetService,
                     WallPreviewService wallPreviewService,
                     moe.hikari.canvas.image.UploadHandler uploadHandler,
                     moe.hikari.canvas.template.TemplatePublisher templatePublisher,
                     moe.hikari.canvas.storage.TemplateRepo templateRepo,
                     moe.hikari.canvas.storage.AuditLog auditLog,
                     org.bukkit.plugin.java.JavaPlugin plugin,
                     String serverVersion, Runnable paintHandler,
                     int wsAuthTimeoutSeconds,
                     List<String> allowedOrigins) {
        this.log = log;
        this.host = host;
        this.port = port;
        this.tokenService = tokenService;
        this.sessionManager = sessionManager;
        this.wallRepo = wallRepo;
        this.wsAuthTimeoutSeconds = Math.max(1, Math.min(60, wsAuthTimeoutSeconds));
        this.allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        this.templateRegistry = templateRegistry;
        this.templatePreviewService = templatePreviewService;
        this.templateAssetService = templateAssetService;
        this.wallPreviewService = wallPreviewService;
        this.uploadHandler = uploadHandler;
        this.templateRepo = templateRepo;
        this.auditLog = auditLog;
        this.plugin = plugin;
        this.serverVersion = serverVersion;
        this.paintHandler = paintHandler;

        // M15.x god-class 拆分：dispatcher 通过 OpPushCallback 触发服务端主动推送，
        // 避免直接耦合 wsBySession 映射。
        OpPushCallback push = new OpPushCallback() {
            @Override public boolean pushSnapshot(String sid, ProjectState s) {
                return WebServer.this.pushSnapshot(sid, s);
            }
            @Override public boolean pushPatch(String sid, StatePatch p) {
                return WebServer.this.pushPatch(sid, p);
            }
        };
        this.editOpDispatcher = new EditOpDispatcher(
                sessionManager, throttler, rateLimiter, templateRegistry, wallRepo, push, auditLog);
        this.brushOpDispatcher = new BrushOpDispatcher(sessionManager, throttler, push);
        this.wallOpDispatcher = new WallOpDispatcher(
                sessionManager, wallRepo, frameDeployer, throttler, plugin, auditLog);
        this.templateOpDispatcher = new TemplateOpDispatcher(sessionManager, templatePublisher);
    }

    public void start() {
        app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJackson().updateMapper(mapper -> {
                mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
                // M16 P6.1：接收方严格——客户端发未知字段一律拒，防协议漂移 / 字段嗅探。
                // 发送侧（server→client）依然宽松：client 用 TypeScript 解析，未声明字段读出 undefined 就行。
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
            }));

            // Jetty WS 默认 idleTimeout = 30s，太短；玩家停手 30s 就被踢。
            // 调到 60s + 前端每 20s 发应用层 ping 保活，两层兜底。
            // 真正的 session 超时由 SessionReaper 负责（wsGrace 5min / idle 30min）。
            cfg.jetty.modifyWebSocketServletFactory(factory -> {
                factory.setIdleTimeout(Duration.ofSeconds(60));
                factory.setMaxTextMessageSize(65536);  // M15.1 P0-9 + P1-Web-3：64KB 上限防 WS flood
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

            // M5-C2：前端加载 palette 的端点；与 Java PaletteLut 读同一份 classpath JSON
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.GET, "/api/palette", ctx -> servePalette(ctx)));

            // M5.5：网页首页"近期项目"列表。无玩家认证（127.0.0.1 trust）；返回所有 walls 的 summary
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.GET, "/api/walls", ctx -> ctx.json(wallRepo.listAll())));

            // M7：模板缩略图端点。Gallery 卡片 <img> 直接拉这条
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

            // M7：wall 缩略图。HomePage 卡片展示用；按 wall.updatedAt 做粗粒度缓存
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

            // M7：图标资源端点（whitelist 名 + builtin/classpath 优先 + 服主自定义后备）
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

            // M13：图片上传 + 下载 + 配额查询
            if (uploadHandler != null) {
                cfg.routes.addEndpoint(new Endpoint(
                        HandlerType.POST, "/api/upload", uploadHandler::handleUpload));
                cfg.routes.addEndpoint(new Endpoint(
                        HandlerType.GET, "/api/upload/quota", uploadHandler::handleQuota));
                cfg.routes.addEndpoint(new Endpoint(
                        HandlerType.GET, "/api/upload/{source}", uploadHandler::handleDownload));
            }

            // M20-P4：字体 advance 表查询端点（用户字体走这条路；内置字体仍由
            // /fonts/{id}.metrics.json 静态文件提供，前端先 fetch 静态后 fallback 此端点）
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.GET, "/api/font/metrics", ctx -> {
                        String id = ctx.queryParam("id");
                        if (id == null || id.isEmpty() || !id.matches("[a-zA-Z0-9_-]+")) {
                            ctx.status(400).result("{\"code\":\"BAD_REQUEST\"}");
                            return;
                        }
                        String json = moe.hikari.canvas.render.FontMetricsTable.serializeToJson(id);
                        if (json == null) {
                            ctx.status(404).result("{\"code\":\"NOT_FOUND\"}");
                            return;
                        }
                        ctx.contentType("application/json").result(json);
                        // 5 min；用户改字体后重启服务即可刷新
                        ctx.header("Cache-Control", "max-age=300, private");
                    }));

            // M14：创意工坊市场（DB 元数据列表）
            if (templateRepo != null) {
                cfg.routes.addEndpoint(new Endpoint(
                        HandlerType.GET, "/api/templates", this::handleTemplatesList));
            }

            // M16 P1.3：WS upgrade Origin 白名单。在 upgrade 前拒绝跨站 WS 攻击（CSWSH）。
            cfg.routes.addEndpoint(new Endpoint(
                    HandlerType.WEBSOCKET_BEFORE_UPGRADE, "/ws", this::checkWsOrigin));

            // WebSocket
            cfg.routes.addWsHandler(WsHandlerType.WEBSOCKET, "/ws", wsCfg -> {
                wsCfg.onConnect(ctx -> {
                    // M16 P1.2：起 auth 超时任务，N 秒后未 auth → close 4001
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
        // M16 P1.2：先停 scheduler，否则 JVM 退出时 daemon 线程虽不阻塞但 cancel 顺序不可控
        authTimeoutScheduler.shutdownNow();
        pendingAuthTimers.clear();
        if (app != null) {
            app.stop();
            log.info("WebServer stopped");
        }
    }

    // ---------- M16 P1.2：未认证 WS 超时 ----------

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

    private void cancelAuthTimeout(WsContext ctx) {
        ScheduledFuture<?> fut = pendingAuthTimers.remove(ctx.sessionId());
        if (fut != null) fut.cancel(false);
    }

    // ---------- M16 P1.3：WS upgrade Origin 白名单 ----------

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
        java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            ctx.status(404);
            return;
        }
        String mime = guessMime(resource);
        if (mime != null) ctx.contentType(mime);
        ctx.result(in);
    }

    /** M5-C2：直读 classpath 根的 palette.json。浏览器端 PaletteLut 用它构建 LUT。 */
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

    private static String guessMime(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js"))   return "application/javascript";
        if (path.endsWith(".mjs"))  return "application/javascript";
        if (path.endsWith(".css"))  return "text/css";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".woff2"))return "font/woff2";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        if (path.endsWith(".png"))  return "image/png";
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

        // M15.4 P0-Web-2：预握手只返 ok + wsUrl + playerName（低敏感），所有敏感元数据
        // （sessionId / wall / templates / mapIds 等）通过 WS ready 帧下发。
        // 修复 HTTP 响应可被嗅探后跨 session 访问的攻击面。
        ctx.json(Map.of(
                "ok", true,
                "playerName", session.playerName(),
                "wsUrl", "/ws"));
    }

    /**
     * M14 创意工坊：返所有模板的元数据（含 owner / featured / 下载数）+ 按 featured/created
     * 排序。前端 HomePage Marketplace 用这个数据 + selfUuid 决定"我的"判定。
     */
    private void handleTemplatesList(io.javalin.http.Context ctx) {
        java.util.List<moe.hikari.canvas.storage.TemplateRepo.Row> rows = templateRepo.listMarketplace(0);
        java.util.List<Map<String, Object>> json = new java.util.ArrayList<>(rows.size());
        for (moe.hikari.canvas.storage.TemplateRepo.Row r : rows) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("templateId", r.templateId());
            // M15.1 P0-30：v1 隐私——公开端点不暴露 ownerUuid；保留 ownerName 用于"我的"判定的友好展示
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

    /** M6-D 协议 §3.2：ready / pre-handshake 一并下发全量 TemplateSpec 列表。 */
    private List<TemplateSpec> listTemplates() {
        return templateRegistry.templates().values().stream()
                .map(TemplateEntry::spec)
                .toList();
    }

    // ---------- WS 消息 ----------

    private void handleMessage(WsMessageContext ctx) {
        Envelope in;
        try {
            in = ctx.messageAsClass(Envelope.class);
        } catch (Exception e) {
            // M16 P6.1：错误脱敏——不 echo Jackson 异常（含类路径 / 字段名 / unknown property 名）。
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
            case "paint" -> {
                paintHandler.run();  // M1 demo 通道；M3 保留作为回归测试通道，M7 polish 时删
                ctx.send(Envelope.of("ack", in.id(), Map.of("submitted", true)));
            }
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
                 "canvas.grid",
                 "canvas.guides.set",
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
            default -> ctx.send(Envelope.error(in.id(), "INVALID_OP", "unknown op: " + in.op()));
        }
    }

    // ---------- auth ----------

    private void handleAuth(WsMessageContext ctx, Envelope in) {
        // 从 payload 取 token
        if (!(in.payload() instanceof Map<?, ?> pl)) {
            ctx.send(Envelope.error(in.id(), "AUTH_FAILED", "missing payload"));
            closeAuthFailed(ctx, "missing payload");
            return;
        }

        // M16 P6.2：协议版本协商。client 在 auth payload 携 {@code client_v}（新字段）
        // 或旧 {@code clientProtocolVersion}（M8-C 兼容名）。server 校验范围
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

        // M16 P6.6：会话级 IP 绑定。首次 auth 时 sessionManager 写下 boundIp；reconnect 必须 IP 同源。
        // 不同 → 当作 AUTH_FAILED 拒（外部只看到 4001，不区分；服务器侧 log.warning 详细原因）。
        // 攻击者 XSS / 抓包拿到 reconnectToken 后从异机重连必撞 MISMATCH。
        String presentedIp = clientIp(ctx);
        SessionManager.IpBindResult ipRes = sessionManager.bindOrCheckIp(session.id(), presentedIp);
        if (ipRes == SessionManager.IpBindResult.MISMATCH) {
            log.warning("WS auth IP mismatch sid=" + session.id()
                    + " boundIp=" + session.boundIp() + " presentedIp=" + presentedIp
                    + "; token may be replayed from a different host");
            ctx.send(Envelope.error(in.id(), "AUTH_FAILED", null));
            closeAuthFailed(ctx, "ip_mismatch");
            return;
        }

        // M5.5：/canvas open 同 wall 路径下 session 已是 ACTIVE；只刷活跃时间，不再走 markActive 转移。
        // 只有首次 auth（ISSUED → ACTIVE）才调 markActive。
        if (session.state() == SessionState.ISSUED) {
            sessionManager.markActive(session.id());
        } else {
            sessionManager.touch(session.id());
        }
        // 旧的 WS ctx（同 sessionId）若还在，关掉再覆盖，避免双连
        WsContext oldCtx = wsBySession.put(session.id(), ctx);
        if (oldCtx != null && oldCtx != ctx) {
            try { oldCtx.closeSession(4003, "session-takeover"); } catch (Exception ignored) {}
        }
        ctx.attribute(ATTR_SESSION_ID, session.id());
        // M16 P1.2：auth 成功 → 取消未认证超时 close 任务
        cancelAuthTimeout(ctx);

        // T3 token rotate：auth 成功后立即 rotate 新 token 交回前端，供 WS 断线重连重新 auth。
        // 契约见 docs/security.md §2.2 / docs/protocol.md §11。
        String reconnectToken = tokenService.rotate(
                session.playerUuid(), session.playerName(), session.id());

        // T4：ready payload 中的 projectState 直接由 session 持有的权威状态序列化
        ProjectState state = session.projectState();

        // M5.5：附带 wall 元数据（wallId / alias / lockedAt + ownerUuid + selfUuid），前端 TopBar 显示。
        // 2026-05-14 lock-state 重设计：字段 publishedAt 改名 lockedAt；新增 ownerUuid + selfUuid
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
        payload.put("protocolVersion", 2);
        // M16 P6.2：accepted_v = server 实际同意的 business protocol 版本（来自 auth 中
        // 协商的 client_v，目前固定 2）。client 收到后应双向校验：accepted_v !== CLIENT_V → 断开。
        payload.put("accepted_v", negotiatedV);
        payload.put("reconnectToken", reconnectToken);
        payload.put("projectState", state);
        if (wallId != null) payload.put("wallId", wallId);
        if (alias != null) payload.put("alias", alias);
        if (lockedAt != null) payload.put("lockedAt", lockedAt);
        if (ownerUuid != null) payload.put("ownerUuid", ownerUuid);
        payload.put("selfUuid", session.playerUuid().toString());
        // M6-D 协议 §3.2：全量 TemplateSpec 下发，前端无需独立接口
        payload.put("templates", listTemplates());
        ctx.send(Envelope.of("ready", in.id(), payload));
    }

    // ---------- 服务端主动推送（M3-T5）----------

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

    /** 按 protocol.md §6.2: close 4001 = 认证失败。 */
    private void closeAuthFailed(WsContext ctx, String reason) {
        ctx.closeSession(4001, "AUTH_FAILED");
        log.info("WS closed 4001 AUTH_FAILED: " + reason);
    }

    /**
     * M16 P6.6：解析 WS 客户端 IP，用于 session-IP 绑定。
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

    /** 按 protocol.md §6.2: close 4002 = 协议版本不匹配（M8-C 起切断 v1；M16 P6.2 协商化）。 */
    private void closeVersionMismatch(WsContext ctx, String reason) {
        ctx.closeSession(Protocol.CLOSE_PROTOCOL_VERSION_UNSUPPORTED, "protocol_version_unsupported");
        log.info("WS closed " + Protocol.CLOSE_PROTOCOL_VERSION_UNSUPPORTED
                + " protocol_version_unsupported: " + reason);
    }
}
