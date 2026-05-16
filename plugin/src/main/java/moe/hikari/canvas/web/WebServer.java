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
    private final moe.hikari.canvas.storage.WallRepo wallRepo;
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final TemplateRegistry templateRegistry;
    private final TemplatePreviewService templatePreviewService;
    private final TemplateAssetService templateAssetService;
    private final WallPreviewService wallPreviewService;
    private final moe.hikari.canvas.image.UploadHandler uploadHandler;
    private final moe.hikari.canvas.storage.TemplateRepo templateRepo;

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
        this.wallRepo = wallRepo;
        this.templateRegistry = templateRegistry;
        this.templatePreviewService = templatePreviewService;
        this.templateAssetService = templateAssetService;
        this.wallPreviewService = wallPreviewService;
        this.uploadHandler = uploadHandler;
        this.templateRepo = templateRepo;
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
                sessionManager, throttler, rateLimiter, templateRegistry, wallRepo, push);
        this.brushOpDispatcher = new BrushOpDispatcher(sessionManager, throttler, push);
        this.wallOpDispatcher = new WallOpDispatcher(
                sessionManager, wallRepo, frameDeployer, throttler, plugin);
        this.templateOpDispatcher = new TemplateOpDispatcher(sessionManager, templatePublisher);
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
