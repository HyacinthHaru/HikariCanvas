package moe.hikari.canvas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import io.javalin.websocket.WsHandlerType;
import io.javalin.websocket.WsMessageContext;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Javalin HTTP + WebSocket 服务。M1 阶段：
 * <ul>
 *   <li>GET {@code /} 及其下 — classpath 下 {@code /web} 目录的静态资源（Vite 产物）</li>
 *   <li>{@code /ws} — WebSocket 端点；{@code ping}/{@code pong}、{@code paint}/{@code ack}、未知 op 回 {@code error}</li>
 * </ul>
 * auth/session 预握手 / state 协议族留给后续任务。
 */
public final class WebServer {

    private final Logger log;
    private final String host;
    private final int port;
    private final Runnable paintHandler;
    private Javalin app;

    public WebServer(Logger log, String host, int port, Runnable paintHandler) {
        this.log = log;
        this.host = host;
        this.port = port;
        this.paintHandler = paintHandler;
    }

    public void start() {
        app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJackson().updateMapper(mapper ->
                    mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL)));

            // TODO(M7 单 jar 部署): Javalin 7 的 staticFiles.add(..., CLASSPATH)
            //   在 shadow/fat jar setup 下对 `/web` 的 directory discovery 失败
            //   （抛 JavalinException: "Static resource directory with path: '/web'
            //    does not exist. Depending on your setup, empty folders might not
            //    get copied to classpath."），Web 资源实际已进 jar（jar tf 可见
            //   web/index.html 与 web/assets/*）。M1-T7a 先绕开：开发期用 Vite dev
            //   server（127.0.0.1:5173）serve 前端、WS 跨源连 8877；M7 打磨阶段
            //   改用手写 GET handler 读 classpath 资源，或用 Location.EXTERNAL
            //   从已知文件系统路径 serve。

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
            case "paint" -> {
                paintHandler.run();
                ctx.send(Envelope.of("ack", in.id(), Map.of("submitted", true)));
            }
            default -> ctx.send(Envelope.error(in.id(), "INVALID_OP", "unknown op: " + in.op()));
        }
    }
}
