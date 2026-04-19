package moe.hikari.canvas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import io.javalin.websocket.WsHandlerType;
import io.javalin.websocket.WsMessageContext;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Javalin HTTP + WebSocket 服务。M1 阶段只实现 {@code /ws} 端点与
 * ping/pong；auth、session 预握手、state 协议族留给后续任务。
 */
public final class WebServer {

    private final Logger log;
    private final String host;
    private final int port;
    private Javalin app;

    public WebServer(Logger log, String host, int port) {
        this.log = log;
        this.host = host;
        this.port = port;
    }

    public void start() {
        app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJackson().updateMapper(mapper ->
                    mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL)));
            cfg.routes.addWsHandler(WsHandlerType.WEBSOCKET, "/ws", wsCfg -> {
                wsCfg.onConnect(ctx -> log.info("WS connected"));
                wsCfg.onClose(ctx -> log.info("WS closed"));
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
        switch (in.op()) {
            case "ping" -> ctx.send(Envelope.pong(in.id()));
            default -> ctx.send(Envelope.error(in.id(), "INVALID_OP", "unknown op: " + in.op()));
        }
    }
}
