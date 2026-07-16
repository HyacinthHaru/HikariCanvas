package moe.hikari.canvas.web;

import io.javalin.websocket.WsMessageContext;
import moe.hikari.canvas.render.ProjectionThrottler;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.state.BrushPoint;
import moe.hikari.canvas.state.EditSession;

import java.util.List;
import java.util.Map;

import static moe.hikari.canvas.web.WebHelpers.asPayloadMap;
import static moe.hikari.canvas.web.WebHelpers.mapOrEmpty;
import static moe.hikari.canvas.web.WebHelpers.parseBrushPoints;
import static moe.hikari.canvas.web.WebHelpers.stringOrNull;

/**
 * brush op 入口。{@code brush.start / point / end / cancel} 走独立路径，**不走** edit
 * 路径的 rateLimiter（brush.point 高频低消息，限流会卡笔触流畅性）；内存安全靠
 * EditSession 的 {@code MAX_BRUSH_POINTS_PER_STROKE} + {@code MAX_ACTIVE_STROKES} 保护。
 */
final class BrushOpDispatcher {

    private final SessionManager sessionManager;
    private final ProjectionThrottler throttler;
    private final OpPushCallback push;

    BrushOpDispatcher(SessionManager sessionManager,
                      ProjectionThrottler throttler,
                      OpPushCallback push) {
        this.sessionManager = sessionManager;
        this.throttler = throttler;
        this.push = push;
    }

    void dispatch(WsMessageContext ctx, Envelope in, String sessionId) {
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
                    push.pushPatch(sessionId, ok.patch());
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
}
