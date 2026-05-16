package moe.hikari.canvas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import moe.hikari.canvas.state.BrushPoint;
import moe.hikari.canvas.state.EditSession;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.StatePatch;
import moe.hikari.canvas.template.TemplateEntry;
import moe.hikari.canvas.template.TemplateInstantiator;
import moe.hikari.canvas.template.TemplateRegistry;
import moe.hikari.canvas.template.TemplateSpec;
import moe.hikari.canvas.template.preview.TemplatePreviewService;
import moe.hikari.canvas.template.preview.WallPreviewService;
import moe.hikari.canvas.template.asset.TemplateAssetService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
 */
public final class WebServer {

    /** wall alias 字符集：字母数字 _ -，长度 2-32。前端 / WS / 命令三路统一校验。 */
    private static final java.util.regex.Pattern ALIAS_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{2,32}$");

    private static final String ATTR_SESSION_ID = "sessionId";

    private final Logger log;
    private final String host;
    private final int port;
    private final TokenService tokenService;
    private final SessionManager sessionManager;
    private final ProjectionThrottler throttler;
    private final SessionRateLimiter rateLimiter;
    private final String serverVersion;
    private final Runnable paintHandler;  // M1 demo
    private final moe.hikari.canvas.storage.WallRepo wallRepo;
    private final moe.hikari.canvas.deploy.FrameDeployer frameDeployer;
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final TemplateRegistry templateRegistry;
    private final TemplateInstantiator templateInstantiator = new TemplateInstantiator();
    private final TemplatePreviewService templatePreviewService;
    private final TemplateAssetService templateAssetService;
    private final WallPreviewService wallPreviewService;
    private final moe.hikari.canvas.image.UploadHandler uploadHandler;
    private final moe.hikari.canvas.template.TemplatePublisher templatePublisher;
    private final moe.hikari.canvas.storage.TemplateRepo templateRepo;
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
                     org.bukkit.plugin.java.JavaPlugin plugin,
                     String serverVersion, Runnable paintHandler) {
        this.log = log;
        this.host = host;
        this.port = port;
        this.tokenService = tokenService;
        this.sessionManager = sessionManager;
        this.throttler = throttler;
        this.rateLimiter = rateLimiter;
        this.wallRepo = wallRepo;
        this.frameDeployer = frameDeployer;
        this.templateRegistry = templateRegistry;
        this.templatePreviewService = templatePreviewService;
        this.templateAssetService = templateAssetService;
        this.wallPreviewService = wallPreviewService;
        this.uploadHandler = uploadHandler;
        this.templatePublisher = templatePublisher;
        this.templateRepo = templateRepo;
        this.plugin = plugin;
        this.serverVersion = serverVersion;
        this.paintHandler = paintHandler;
    }

    public void start() {
        app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJackson().updateMapper(mapper ->
                    mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL)));

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

            // M14：创意工坊市场（DB 元数据列表）
            if (templateRepo != null) {
                cfg.routes.addEndpoint(new Endpoint(
                        HandlerType.GET, "/api/templates", this::handleTemplatesList));
            }

            // WebSocket
            cfg.routes.addWsHandler(WsHandlerType.WEBSOCKET, "/ws", wsCfg -> {
                wsCfg.onConnect(ctx -> log.info("WS connected"));
                wsCfg.onClose(ctx -> {
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
        if (app != null) {
            app.stop();
            log.info("WebServer stopped");
        }
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

        ctx.json(Map.of(
                "sessionId", session.id(),
                "playerName", session.playerName(),
                "wall", Map.of(
                        "width", session.wall().width(),
                        "height", session.wall().height()),
                "mapIds", session.mapIds(),
                "templates", listTemplates(),
                "palette", Map.of(),
                "fonts", List.of(),
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
            ctx.send(Envelope.error(null, "INVALID_PAYLOAD", "malformed envelope: " + e.getMessage()));
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
                 "template.apply" -> dispatchEditOp(ctx, in, bound);
            case "brush.start", "brush.point", "brush.end", "brush.cancel" -> dispatchBrushOp(ctx, in, bound);
            case "wall.lock", "wall.unlock", "wall.alias", "wall.refresh" -> dispatchWallOp(ctx, in, bound);
            case "template.save", "template.delete", "template.feature", "template.unfeature"
                    -> dispatchTemplateOp(ctx, in, bound);
            default -> ctx.send(Envelope.error(in.id(), "INVALID_OP", "unknown op: " + in.op()));
        }
    }

    /**
     * M14 创意工坊：template.save / delete / feature / unfeature 入口。
     * 鉴权用当前 session 的 player UUID 查 Bukkit live Player 拿 hasPermission。
     */
    private void dispatchTemplateOp(WsMessageContext ctx, Envelope in, String sessionId) {
        Session s = sessionManager.byId(sessionId);
        if (s == null) {
            ctx.send(Envelope.error(in.id(), "SESSION_CLOSED", "no active session"));
            return;
        }
        Map<String, Object> payload;
        try {
            payload = asPayloadMap(in.payload());
        } catch (IllegalArgumentException iae) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", iae.getMessage()));
            return;
        }
        org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(s.playerUuid());
        if (player == null) {
            ctx.send(Envelope.error(in.id(), "FORBIDDEN", "player offline"));
            return;
        }

        switch (in.op()) {
            case "template.save" -> handleTemplateSave(ctx, in, s, payload, player);
            case "template.delete" -> handleTemplateDelete(ctx, in, s, payload, player);
            case "template.feature" -> handleTemplateFeature(ctx, in, payload, player, true);
            case "template.unfeature" -> handleTemplateFeature(ctx, in, payload, player, false);
            default -> ctx.send(Envelope.error(in.id(), "INVALID_OP", "unreachable: " + in.op()));
        }
    }

    private void handleTemplateSave(WsMessageContext ctx, Envelope in, Session s,
                                    Map<String, Object> payload, org.bukkit.entity.Player player) {
        if (!player.hasPermission("canvas.template.save")) {
            ctx.send(Envelope.error(in.id(), "FORBIDDEN", "missing canvas.template.save"));
            return;
        }
        moe.hikari.canvas.state.ProjectState state = s.projectState();
        if (state == null) {
            ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "no project state in session"));
            return;
        }
        String slug = stringOrNull(payload.get("slug"));
        String displayName = stringOrNull(payload.get("displayName"));
        String description = stringOrNull(payload.get("description"));
        moe.hikari.canvas.template.TemplateExporter.ParamConfig paramConfig =
                parseParamConfig(payload.get("paramConfig"));
        boolean bypass = player.hasPermission("canvas.template.bypass-limit");

        moe.hikari.canvas.template.TemplatePublisher.Result result = templatePublisher.publish(
                s.playerUuid(), s.playerName(),
                slug, displayName, description, paramConfig, state, bypass);
        if (result instanceof moe.hikari.canvas.template.TemplatePublisher.Result.Ok ok) {
            ctx.send(Envelope.of("ack", in.id(), Map.of("templateId", ok.templateId())));
        } else if (result instanceof moe.hikari.canvas.template.TemplatePublisher.Result.Failed f) {
            ctx.send(Envelope.error(in.id(), f.code(), f.message()));
        }
    }

    private void handleTemplateDelete(WsMessageContext ctx, Envelope in, Session s,
                                      Map<String, Object> payload, org.bukkit.entity.Player player) {
        String templateId = stringOrNull(payload.get("templateId"));
        if (templateId == null) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "templateId is required"));
            return;
        }
        boolean isAdmin = player.hasPermission("canvas.template.delete.any");
        boolean canDeleteOwn = player.hasPermission("canvas.template.delete.own");
        if (!isAdmin && !canDeleteOwn) {
            ctx.send(Envelope.error(in.id(), "FORBIDDEN", "missing delete permission"));
            return;
        }
        moe.hikari.canvas.template.TemplatePublisher.Result result =
                templatePublisher.delete(templateId, s.playerUuid(), isAdmin);
        if (result instanceof moe.hikari.canvas.template.TemplatePublisher.Result.Ok ok) {
            ctx.send(Envelope.of("ack", in.id(), Map.of("templateId", ok.templateId())));
        } else if (result instanceof moe.hikari.canvas.template.TemplatePublisher.Result.Failed f) {
            ctx.send(Envelope.error(in.id(), f.code(), f.message()));
        }
    }

    private void handleTemplateFeature(WsMessageContext ctx, Envelope in,
                                       Map<String, Object> payload,
                                       org.bukkit.entity.Player player, boolean featured) {
        if (!player.hasPermission("canvas.template.feature")) {
            ctx.send(Envelope.error(in.id(), "FORBIDDEN", "missing canvas.template.feature"));
            return;
        }
        String templateId = stringOrNull(payload.get("templateId"));
        if (templateId == null) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "templateId is required"));
            return;
        }
        moe.hikari.canvas.template.TemplatePublisher.Result result =
                templatePublisher.setFeatured(templateId, featured);
        if (result instanceof moe.hikari.canvas.template.TemplatePublisher.Result.Ok ok) {
            ctx.send(Envelope.of("ack", in.id(), Map.of("templateId", ok.templateId(), "featured", featured)));
        } else if (result instanceof moe.hikari.canvas.template.TemplatePublisher.Result.Failed f) {
            ctx.send(Envelope.error(in.id(), f.code(), f.message()));
        }
    }

    @SuppressWarnings("unchecked")
    private static moe.hikari.canvas.template.TemplateExporter.ParamConfig parseParamConfig(Object raw) {
        if (raw == null) return moe.hikari.canvas.template.TemplateExporter.ParamConfig.empty();
        if (!(raw instanceof Map<?, ?> m)) return moe.hikari.canvas.template.TemplateExporter.ParamConfig.empty();
        Object textActionsObj = m.get("textActions");
        if (!(textActionsObj instanceof Map<?, ?> txMap)) {
            return moe.hikari.canvas.template.TemplateExporter.ParamConfig.empty();
        }
        Map<String, moe.hikari.canvas.template.TemplateExporter.AutoTextAction> textActions = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : txMap.entrySet()) {
            if (!(e.getKey() instanceof String autoId)) continue;
            if (!(e.getValue() instanceof Map<?, ?> v)) continue;
            String action = v.get("action") instanceof String sa ? sa : "keep";
            String name = v.get("name") instanceof String sn ? sn : null;
            String label = v.get("label") instanceof String sl ? sl : null;
            String desc = v.get("description") instanceof String sd ? sd : null;
            textActions.put(autoId, new moe.hikari.canvas.template.TemplateExporter.AutoTextAction(
                    action, name, label, desc));
        }
        return new moe.hikari.canvas.template.TemplateExporter.ParamConfig(textActions);
    }

    /** M5.5 wall 元数据 op 入口；payload 解析后转 {@link #handleWallOp}。 */
    private void dispatchWallOp(WsMessageContext ctx, Envelope in, String sessionId) {
        Map<String, Object> payload;
        try {
            payload = asPayloadMap(in.payload());
        } catch (IllegalArgumentException iae) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", iae.getMessage()));
            return;
        }
        handleWallOp(ctx, sessionId, in, payload);
    }

    // ---------- M3-T6 编辑 op 分发 ----------

    private void dispatchEditOp(WsMessageContext ctx, Envelope in, String sessionId) {
        // T10 输入限流：超 40 msg/2s（≈ 20 msg/s）返 RATE_LIMITED，不进 EditSession
        if (!rateLimiter.allow(sessionId)) {
            ctx.send(Envelope.error(in.id(), "RATE_LIMITED",
                    "input rate exceeded; slow down"));
            return;
        }
        Session s = sessionManager.byId(sessionId);
        if (s == null || s.editSession() == null) {
            ctx.send(Envelope.error(in.id(), "SESSION_CLOSED", "no active edit session"));
            return;
        }
        EditSession es = s.editSession();

        Map<String, Object> payload;
        try {
            payload = asPayloadMap(in.payload());
        } catch (IllegalArgumentException iae) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", iae.getMessage()));
            return;
        }

        EditSession.OpResult result = switch (in.op()) {
            case "element.add" -> {
                String type = stringOrNull(payload.get("type"));
                @SuppressWarnings("unchecked")
                Map<String, Object> props = (Map<String, Object>) mapOrEmpty(payload.get("props"));
                String after = stringOrNull(payload.get("after"));
                String layerId = stringOrNull(payload.get("layerId"));
                yield es.addElement(type, props, after, layerId);
            }
            case "element.update" -> {
                String eid = stringOrNull(payload.get("elementId"));
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) mapOrEmpty(payload.get("patch"));
                yield es.updateElement(eid, p);
            }
            case "element.delete" -> es.deleteElement(stringOrNull(payload.get("elementId")));
            case "element.reorder" -> {
                String eid = stringOrNull(payload.get("elementId"));
                Object idxObj = payload.get("index");
                if (!(idxObj instanceof Number n)) {
                    yield new EditSession.OpResult.Error("INVALID_PAYLOAD", "index must be number");
                }
                yield es.reorderElement(eid, n.intValue());
            }
            case "element.transform" -> {
                String eid = stringOrNull(payload.get("elementId"));
                yield es.transformElement(eid,
                        intOrNull(payload.get("x")),
                        intOrNull(payload.get("y")),
                        intOrNull(payload.get("w")),
                        intOrNull(payload.get("h")),
                        intOrNull(payload.get("rotation")));
            }
            case "element.move-to-layer" -> {
                String eid = stringOrNull(payload.get("elementId"));
                String target = stringOrNull(payload.get("targetLayerId"));
                Integer idx = intOrNull(payload.get("index"));
                yield es.moveElementToLayer(eid, target, idx);
            }
            // ---- layer.* op 族（M8-C 新增）----
            case "layer.create" -> {
                String name = stringOrNull(payload.get("name"));
                String afterId = stringOrNull(payload.get("afterLayerId"));
                yield es.createLayer(name, afterId);
            }
            case "layer.delete" -> es.deleteLayer(stringOrNull(payload.get("layerId")));
            case "layer.update" -> {
                String lid = stringOrNull(payload.get("layerId"));
                @SuppressWarnings("unchecked")
                Map<String, Object> lp = (Map<String, Object>) mapOrEmpty(payload.get("patch"));
                yield es.updateLayer(lid, lp);
            }
            case "layer.reorder" -> {
                String lid = stringOrNull(payload.get("layerId"));
                Object idxObj = payload.get("index");
                if (!(idxObj instanceof Number n)) {
                    yield new EditSession.OpResult.Error("INVALID_PAYLOAD", "index must be number");
                }
                yield es.reorderLayer(lid, n.intValue());
            }
            case "layer.duplicate" -> es.duplicateLayer(stringOrNull(payload.get("layerId")));
            case "layer.set-active" -> es.setActiveLayer(stringOrNull(payload.get("layerId")));
            // ---- canvas.* ----
            case "canvas.resize" -> {
                Object wObj = payload.get("widthMaps");
                Object hObj = payload.get("heightMaps");
                if (!(wObj instanceof Number wn) || !(hObj instanceof Number hn)) {
                    yield new EditSession.OpResult.Error(
                            "INVALID_PAYLOAD", "widthMaps/heightMaps must be numbers");
                }
                yield es.resizeCanvas(wn.intValue(), hn.intValue());
            }
            case "canvas.background" -> es.setBackground(stringOrNull(payload.get("color")));
            case "canvas.grid" -> {
                Object sz = payload.get("size");
                if (sz != null && !(sz instanceof Number)) {
                    yield new EditSession.OpResult.Error("INVALID_PAYLOAD",
                            "size must be number or null");
                }
                yield es.setGridSize(sz == null ? null : ((Number) sz).intValue());
            }
            case "canvas.guides.set" -> {
                Object gs = payload.get("guides");
                if (gs != null && !(gs instanceof List<?>)) {
                    yield new EditSession.OpResult.Error("INVALID_PAYLOAD",
                            "guides must be array");
                }
                yield es.setGuides((List<?>) gs);
            }
            // ---- history / template ----
            case "undo" -> es.undo();
            case "redo" -> es.redo();
            case "history.mark" -> es.historyMark(stringOrNull(payload.get("label")));
            case "template.apply" -> {
                String tpl = stringOrNull(payload.get("templateId"));
                @SuppressWarnings("unchecked")
                Map<String, Object> tp = (Map<String, Object>) mapOrEmpty(payload.get("params"));
                yield applyTemplate(es, sessionId, tpl, tp);
            }
            default -> new EditSession.OpResult.Error("INVALID_OP", "unreachable: " + in.op());
        };

        switch (result) {
            case EditSession.OpResult.Ok ok -> {
                // 1) ack 给 client id
                ctx.send(Envelope.of("ack", in.id(), Map.of("version", ok.patch().version())));
                // 2) pushPatch（s-N id）——空 ops 的 patch 跳过
                if (!ok.patch().ops().isEmpty()) {
                    pushPatch(sessionId, ok.patch());
                }
                // 3) 脏矩形投影经 T10 节流器（Bukkit async task 里调 projector.project）
                if (ok.dirty() != null) {
                    throttler.submit(sessionId, ok.dirty());
                }
                // 4) 草稿持久化（M5-D6）
                sessionManager.persistWall(sessionId);
            }
            case EditSession.OpResult.OkSnapshot oks -> {
                // undo/redo/template.apply：结构跳变，下行 state.snapshot 全量；
                // 像素全画布重绘经 throttler 排队
                ctx.send(Envelope.of("ack", in.id(), Map.of("version", oks.version())));
                Session sn = sessionManager.byId(sessionId);
                if (sn != null && sn.projectState() != null) {
                    pushSnapshot(sessionId, sn.projectState());
                }
                if (oks.dirty() != null) {
                    throttler.submit(sessionId, oks.dirty());
                }
                sessionManager.persistWall(sessionId);
            }
            case EditSession.OpResult.OkBrushStart obs ->
                    // 非 brush op 不应返回 OkBrushStart；进到这里说明 EditSession 实现 bug
                    ctx.send(Envelope.error(in.id(), "UNEXPECTED",
                            "non-brush op returned brush start: strokeId=" + obs.strokeId()));
            case EditSession.OpResult.Error er ->
                    ctx.send(Envelope.error(in.id(), er.code(), er.message()));
        }
    }

    /**
     * M12 brush op 入口。{@code brush.start / point / end / cancel} 走独立路径，**不走** edit
     * 路径的 rateLimiter（brush.point 高频低消息，限流会卡笔触流畅性）；内存安全靠
     * EditSession 的 {@code MAX_BRUSH_POINTS_PER_STROKE} + {@code MAX_ACTIVE_STROKES} 保护。
     */
    private void dispatchBrushOp(WsMessageContext ctx, Envelope in, String sessionId) {
        Session s = sessionManager.byId(sessionId);
        if (s == null || s.editSession() == null) {
            ctx.send(Envelope.error(in.id(), "SESSION_CLOSED", "no active edit session"));
            return;
        }
        EditSession es = s.editSession();
        Map<String, Object> payload;
        try {
            payload = asPayloadMap(in.payload());
        } catch (IllegalArgumentException iae) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", iae.getMessage()));
            return;
        }

        EditSession.OpResult result;
        try {
            result = switch (in.op()) {
                case "brush.start" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> props = (Map<String, Object>) mapOrEmpty(payload.get("props"));
                    String layerId = stringOrNull(payload.get("layerId"));
                    yield es.startBrush(props, layerId);
                }
                case "brush.point" -> {
                    String sid = stringOrNull(payload.get("strokeId"));
                    List<BrushPoint> points = parseBrushPoints(payload.get("points"));
                    yield es.appendBrushPoints(sid, points);
                }
                case "brush.end" -> es.endBrush(stringOrNull(payload.get("strokeId")));
                case "brush.cancel" -> es.cancelBrush(stringOrNull(payload.get("strokeId")));
                default -> new EditSession.OpResult.Error("INVALID_OP", "unreachable brush: " + in.op());
            };
        } catch (IllegalArgumentException iae) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", iae.getMessage()));
            return;
        }

        switch (result) {
            case EditSession.OpResult.OkBrushStart obs ->
                    ctx.send(Envelope.of("ack", in.id(), Map.of("strokeId", obs.strokeId())));
            case EditSession.OpResult.Ok ok -> {
                // brush.end 走这里：state.patch 含 element.add；brush.point/cancel 走这里但 patch 为空
                if (!ok.patch().ops().isEmpty()) {
                    ctx.send(Envelope.of("ack", in.id(), Map.of("version", ok.patch().version())));
                    pushPatch(sessionId, ok.patch());
                }
                // brush.point 高频不 ack（避免来回）；brush.cancel ack 空
                else if ("brush.cancel".equals(in.op()) || "brush.end".equals(in.op())) {
                    ctx.send(Envelope.of("ack", in.id(), Map.of()));
                }
                if (ok.dirty() != null) {
                    throttler.submit(sessionId, ok.dirty());
                }
                if ("brush.end".equals(in.op())) {
                    sessionManager.persistWall(sessionId);
                }
            }
            case EditSession.OpResult.OkSnapshot oks ->
                    ctx.send(Envelope.error(in.id(), "UNEXPECTED",
                            "brush op should not return OkSnapshot v=" + oks.version()));
            case EditSession.OpResult.Error er ->
                    ctx.send(Envelope.error(in.id(), er.code(), er.message()));
        }
    }

    /** 解析 brush.point 的 payload {@code points: [[x, y, pressure], ...]} 为 {@link BrushPoint} 列表。 */
    private static List<BrushPoint> parseBrushPoints(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("points must be array");
        }
        List<BrushPoint> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof List<?> inner) || inner.size() < 3) {
                throw new IllegalArgumentException("each point must be [x, y, pressure]");
            }
            Object xo = inner.get(0), yo = inner.get(1), po = inner.get(2);
            if (!(xo instanceof Number) || !(yo instanceof Number) || !(po instanceof Number)) {
                throw new IllegalArgumentException("point values must be numbers");
            }
            double x = ((Number) xo).doubleValue();
            double y = ((Number) yo).doubleValue();
            double p = ((Number) po).doubleValue();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(p)) {
                throw new IllegalArgumentException("non-finite point value");
            }
            out.add(new BrushPoint(x, y, p));
        }
        return out;
    }

    /**
     * M6-D template.apply 中枢：resolve registry → instantiate → replaceContent → walls write-back。
     * 不在主线程跑（持有当前 WS thread），但只做内存/DB I/O，不碰 Bukkit world API。
     */
    private EditSession.OpResult applyTemplate(EditSession es, String sessionId,
                                               String templateId, Map<String, Object> params) {
        if (templateId == null || templateId.isBlank()) {
            return new EditSession.OpResult.Error("INVALID_PAYLOAD", "templateId is required");
        }
        TemplateEntry entry = templateRegistry.byId(templateId);
        if (entry == null) {
            return new EditSession.OpResult.Error("INVALID_PAYLOAD",
                    "unknown template: " + templateId);
        }
        ProjectState.Canvas cv = es.state().canvas();
        TemplateInstantiator.Result r = templateInstantiator.instantiate(
                entry.spec(), params, cv.widthMaps(), cv.heightMaps());
        if (r instanceof TemplateInstantiator.Result.Failed f) {
            return new EditSession.OpResult.Error(f.code(),
                    String.join("; ", f.errors()));
        }
        TemplateInstantiator.Result.Ok ok = (TemplateInstantiator.Result.Ok) r;
        EditSession.OpResult applied = es.replaceContent(ok.backgroundColor(), ok.elements());
        if (applied instanceof EditSession.OpResult.OkSnapshot) {
            // walls.template_id / template_version write-back（best-effort）
            Session sess = sessionManager.byId(sessionId);
            if (sess != null && sess.wallId() != null) {
                wallRepo.setTemplate(sess.wallId(), templateId, entry.spec().version());
            }
        }
        return applied;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asPayloadMap(Object payload) {
        if (payload == null) return Map.of();
        if (payload instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("payload must be object");
    }

    private static Map<?, ?> mapOrEmpty(Object v) {
        if (v == null) return Map.of();
        if (v instanceof Map<?, ?> m) return m;
        throw new IllegalArgumentException("expected object, got " + v.getClass().getSimpleName());
    }

    private static String stringOrNull(Object v) {
        return (v instanceof String s) ? s : null;
    }

    private static Integer intOrNull(Object v) {
        return (v instanceof Number n) ? n.intValue() : null;
    }

    /** M5.5：wall.alias / wall.refresh；M11+ lock-state 重设计：wall.lock / wall.unlock（owner-only）。
     *  不影响 ProjectState，绕开 EditSession。 */
    private void handleWallOp(WsMessageContext ctx, String sessionId,
                              Envelope in, Map<String, Object> payload) {
        Session s = sessionManager.byId(sessionId);
        if (s == null || s.wallId() == null) {
            ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "session has no bound wall"));
            return;
        }
        String wallId = s.wallId();
        switch (in.op()) {
            case "wall.lock" -> {
                // owner-only：只有 wall 创建者（owner_uuid）能锁
                var wall = wallRepo.loadById(wallId).orElse(null);
                if (wall == null) {
                    ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "wall not found"));
                    return;
                }
                if (!wall.ownerUuid().equals(s.playerUuid())) {
                    ctx.send(Envelope.error(in.id(), "FORBIDDEN", "only wall owner can lock"));
                    return;
                }
                Long ts = wallRepo.markPublished(wallId);
                if (ts == null) {
                    ctx.send(Envelope.error(in.id(), "INTERNAL_ERROR", "lock failed"));
                    return;
                }
                // 2026-05-14 lock-state 重设计：ItemFrame PDC 不再写 published_at（FrameDeployer.markPublished 砍）
                ctx.send(Envelope.of("ack", in.id(), Map.of("lockedAt", ts)));
            }
            case "wall.unlock" -> {
                // owner-only：只有 wall 创建者能解锁
                var wall = wallRepo.loadById(wallId).orElse(null);
                if (wall == null) {
                    ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "wall not found"));
                    return;
                }
                if (!wall.ownerUuid().equals(s.playerUuid())) {
                    ctx.send(Envelope.error(in.id(), "FORBIDDEN", "only wall owner can unlock"));
                    return;
                }
                wallRepo.markUnpublished(wallId);
                // M15.1 P0-2：全局 JsonInclude.NON_NULL 会把 lockedAt: null 字段吞掉，前端收空对象；
                // 改用显式布尔 locked: false（协议变更，前端 wsClient.ts 同步调整）
                ctx.send(Envelope.of("ack", in.id(), Map.of("locked", false)));
            }
            case "wall.alias" -> {
                String alias = stringOrNull(payload.get("alias"));
                if (alias == null || !ALIAS_PATTERN.matcher(alias).matches()) {
                    ctx.send(Envelope.error(in.id(), "INVALID_ALIAS_FORMAT",
                            "alias must match [A-Za-z0-9_-]{2,32}"));
                    return;
                }
                boolean ok = wallRepo.setAlias(wallId, alias);
                if (!ok) {
                    ctx.send(Envelope.error(in.id(), "ALIAS_TAKEN",
                            "alias '" + alias + "' is already in use"));
                    return;
                }
                ctx.send(Envelope.of("ack", in.id(), Map.of("alias", alias)));
            }
            case "wall.refresh" -> {
                // 玩家撸掉了支撑方块或画框 → 补回方块 + 补 spawn 缺失画框 + 全画布重画
                if (s.wall() == null || s.mapIds() == null) {
                    ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND",
                            "session lacks wall geometry"));
                    return;
                }
                final moe.hikari.canvas.deploy.WallResolver.Result.Ok geom = s.wall();
                final java.util.List<Integer> mapIds = s.mapIds();
                final String ackId = in.id();
                // 主线程跑（动方块 + spawn entity），完成后回 ack 到当前 WS ctx（Jetty 线程安全）
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    moe.hikari.canvas.deploy.FrameDeployer.RepairResult result =
                            frameDeployer.repairFor(wallId, geom, mapIds);
                    if (s.projectState() != null) {
                        throttler.submit(sessionId,
                                moe.hikari.canvas.render.DirtyRegion.fullCanvas(s.projectState()));
                    }
                    plugin.getLogger().info("wall.refresh: wall=" + wallId
                            + " framesRespawned=" + result.framesRespawned()
                            + " framesReAttached=" + result.framesReAttached()
                            + " wallBlocksReplaced=" + result.wallBlocksReplaced());
                    java.util.LinkedHashMap<String, Object> ackPayload = new java.util.LinkedHashMap<>();
                    // 给前端的"重挂"是 spawn 新 frame + 给空 frame 塞回 map 的总和
                    ackPayload.put("framesRespawned", result.framesFixed());
                    ackPayload.put("framesReAttached", result.framesReAttached());
                    ackPayload.put("wallBlocksReplaced", result.wallBlocksReplaced());
                    try {
                        ctx.send(Envelope.of("ack", ackId, ackPayload));
                    } catch (Exception ignored) {
                        // WS 可能已断开；忽略
                    }
                });
            }
            default -> ctx.send(Envelope.error(in.id(), "INVALID_OP",
                    "unknown wall op: " + in.op()));
        }
    }

    private void handleAuth(WsMessageContext ctx, Envelope in) {
        // 从 payload 取 token
        if (!(in.payload() instanceof Map<?, ?> pl)) {
            ctx.send(Envelope.error(in.id(), "AUTH_FAILED", "missing payload"));
            closeAuthFailed(ctx, "missing payload");
            return;
        }

        // M8-C：协议 v2 强制——前端必须声明 clientProtocolVersion >= 2，否则切断 v1 客户端。
        // 注意检查在 token consume 之前，避免为不兼容客户端浪费一次性 token。
        Object cpv = pl.get("clientProtocolVersion");
        if (!(cpv instanceof Number cpvN) || cpvN.intValue() < 2) {
            ctx.send(Envelope.error(in.id(), "VERSION_MISMATCH",
                    "client must speak protocol v2; received " + cpv));
            closeVersionMismatch(ctx, "clientProtocolVersion=" + cpv);
            return;
        }

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

    /** 按 protocol.md §6.2: close 4002 = 协议版本不匹配（M8-C 起切断 v1）。 */
    private void closeVersionMismatch(WsContext ctx, String reason) {
        ctx.closeSession(4002, "VERSION_MISMATCH");
        log.info("WS closed 4002 VERSION_MISMATCH: " + reason);
    }
}
