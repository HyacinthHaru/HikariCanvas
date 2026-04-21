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
import moe.hikari.canvas.state.EditSession;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.StatePatch;

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

    private static final String ATTR_SESSION_ID = "sessionId";

    private final Logger log;
    private final String host;
    private final int port;
    private final TokenService tokenService;
    private final SessionManager sessionManager;
    private final String serverVersion;
    private final Runnable paintHandler;  // M1 demo
    private Javalin app;

    /** 活跃 session → 绑定的 WS 连接；用于服务端主动推送（state.snapshot / state.patch）。 */
    private final ConcurrentMap<String, WsContext> wsBySession = new ConcurrentHashMap<>();
    /** 服务端主动推送 {@code s-<N>} 的自增计数。 */
    private final AtomicLong serverIdSeq = new AtomicLong(0);

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
                paintHandler.run();  // M1 demo 通道；M3 保留作为回归测试通道，M7 polish 时删
                ctx.send(Envelope.of("ack", in.id(), Map.of("submitted", true)));
            }
            case "element.add",
                 "element.update",
                 "element.delete",
                 "element.reorder",
                 "element.transform",
                 "canvas.resize",
                 "canvas.background" -> dispatchEditOp(ctx, in, bound);
            default -> ctx.send(Envelope.error(in.id(), "INVALID_OP", "unknown op: " + in.op()));
        }
    }

    // ---------- M3-T6 编辑 op 分发 ----------

    private void dispatchEditOp(WsMessageContext ctx, Envelope in, String sessionId) {
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
                yield es.addElement(type, props, after);
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
            default -> new EditSession.OpResult.Error("INVALID_OP", "unreachable: " + in.op());
        };

        if (result instanceof EditSession.OpResult.Ok ok) {
            // ack 给 client id；然后立即 pushPatch（s-N id）
            ctx.send(Envelope.of("ack", in.id(), Map.of("version", ok.patch().version())));
            if (!ok.patch().ops().isEmpty()) {
                pushPatch(sessionId, ok.patch());
            }
        } else if (result instanceof EditSession.OpResult.Error er) {
            ctx.send(Envelope.error(in.id(), er.code(), er.message()));
        }
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
        wsBySession.put(session.id(), ctx);

        // T3 token rotate：auth 成功后立即 rotate 新 token 交回前端，供 WS 断线重连重新 auth。
        // 契约见 docs/security.md §2.2 / docs/protocol.md §11。
        String reconnectToken = tokenService.rotate(
                session.playerUuid(), session.playerName(), session.id());

        // T4：ready payload 中的 projectState 直接由 session 持有的权威状态序列化
        ProjectState state = session.projectState();

        ctx.send(Envelope.of("ready", in.id(), Map.of(
                "sessionId", session.id(),
                "serverVersion", serverVersion,
                "protocolVersion", 1,
                "reconnectToken", reconnectToken,
                "projectState", state)));
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
}
