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
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.session.SessionState;
import moe.hikari.canvas.session.TokenService;

import java.util.List;
import java.util.Map;
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
 * <p>M2 阶段暂不实装 token rotate（auth 成功后重发新 token 给前端用于断线重连）——
 * WS 断开后玩家需要重新 {@code /canvas edit} + confirm 签发新 token。rotate 留给 M3/M7。</p>
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
    private Javalin app;

    public WebServer(Logger log, String host, int port,
                     TokenService tokenService, SessionManager sessionManager,
                     String serverVersion, Runnable paintHandler) {
        this.log = log;
        this.host = host;
        this.port = port;
        this.tokenService = tokenService;
        this.sessionManager = sessionManager;
        this.serverVersion = serverVersion;
        this.paintHandler = paintHandler;
    }

    public void start() {
        app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJackson().updateMapper(mapper ->
                    mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL)));

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

            // WebSocket
            cfg.routes.addWsHandler(WsHandlerType.WEBSOCKET, "/ws", wsCfg -> {
                wsCfg.onConnect(ctx -> log.info("WS connected"));
                wsCfg.onClose(ctx -> {
                    String sid = ctx.attribute(ATTR_SESSION_ID);
                    if (sid != null) {
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
                "templates", List.of(),
                "palette", Map.of(),
                "fonts", List.of(),
                "wsUrl", "/ws"));
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
                paintHandler.run();  // M1 demo 通道，T11 后会被正规 op 替换
                ctx.send(Envelope.of("ack", in.id(), Map.of("submitted", true)));
            }
            default -> ctx.send(Envelope.error(in.id(), "INVALID_OP", "unknown op: " + in.op()));
        }
    }

    private void handleAuth(WsMessageContext ctx, Envelope in) {
        // 从 payload 取 token
        if (!(in.payload() instanceof Map<?, ?> pl)) {
            ctx.send(Envelope.error(in.id(), "AUTH_FAILED", "missing payload"));
            closeAuthFailed(ctx, "missing payload");
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

        sessionManager.markActive(session.id());
        ctx.attribute(ATTR_SESSION_ID, session.id());

        Map<String, Object> projectState = Map.of(
                "version", 0,
                "canvas", Map.of(
                        "widthMaps", session.wall().width(),
                        "heightMaps", session.wall().height(),
                        "background", "#CCCCCC"),
                "elements", List.of(),
                "history", Map.of("undoDepth", 0, "redoDepth", 0));

        ctx.send(Envelope.of("ready", in.id(), Map.of(
                "sessionId", session.id(),
                "serverVersion", serverVersion,
                "protocolVersion", 1,
                "projectState", projectState)));
    }

    /** 按 protocol.md §6.2: close 4001 = 认证失败。 */
    private void closeAuthFailed(WsContext ctx, String reason) {
        ctx.closeSession(4001, "AUTH_FAILED");
        log.info("WS closed 4001 AUTH_FAILED: " + reason);
    }
}
