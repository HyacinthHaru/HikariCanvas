package ac.haru.hikaricanvas.web;

import io.javalin.websocket.WsMessageContext;
import ac.haru.hikaricanvas.render.ProjectionThrottler;
import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionManager;
import ac.haru.hikaricanvas.session.SessionRateLimiter;
import ac.haru.hikaricanvas.state.BrushPoint;
import ac.haru.hikaricanvas.state.EditSession;

import java.util.List;
import java.util.Map;

import static ac.haru.hikaricanvas.web.WebHelpers.asPayloadMap;
import static ac.haru.hikaricanvas.web.WebHelpers.mapOrEmpty;
import static ac.haru.hikaricanvas.web.WebHelpers.parseBrushPoints;
import static ac.haru.hikaricanvas.web.WebHelpers.stringOrNull;

/**
 * brush op 入口。{@code brush.start / point / cancel} 走独立路径，**不走** edit 路径的
 * rateLimiter（brush.point 高频低消息，限流会卡笔触流畅性）；内存安全靠 EditSession 的
 * {@code MAX_BRUSH_POINTS_PER_STROKE} + {@code MAX_ACTIVE_STROKES} 保护。
 *
 * <p><b>{@code brush.end} 例外，它走限流</b>（{@code docs/security.md §3.3}）：这一个 op
 * 一次要跑最多 5000 点的 RDP 简化（最坏 O(n²)）+ 一次全量 {@code ProjectSnapshot} 深拷贝
 * 入 undo 栈 + 往图层永久追加一个元素，根本不属于「高频低消息」。
 * {@code MAX_ACTIVE_STROKES} 只挡「start 了不 end」，挡不住 start→点满→end 的循环，
 * 于是任何持 {@code canvas.edit}（默认全员）的玩家都能无节流地刷。正常落笔一秒撑死几笔，
 * 40msg/2s 的通用窗口对手感零影响。</p>
 */
final class BrushOpDispatcher {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(BrushOpDispatcher.class.getName());

    /**
     * 走 {@link SessionRateLimiter} 的 brush op。只有 {@code brush.end} —— 其余三个是
     * 「高频低消息」，限流会卡笔触流畅性（见类注释与 {@code docs/security.md §3.3}）。
     */
    static final java.util.Set<String> RATE_LIMITED_OPS = java.util.Set.of("brush.end");

    /** 该 op 是否计入会话输入限流窗口。 */
    static boolean isRateLimited(String op) {
        return RATE_LIMITED_OPS.contains(op);
    }

    private final SessionManager sessionManager;
    private final ProjectionThrottler throttler;
    private final SessionRateLimiter rateLimiter;
    private final OpPushCallback push;

    BrushOpDispatcher(SessionManager sessionManager,
                      ProjectionThrottler throttler,
                      SessionRateLimiter rateLimiter,
                      OpPushCallback push) {
        this.sessionManager = sessionManager;
        this.throttler = throttler;
        this.rateLimiter = rateLimiter;
        this.push = push;
    }

    void dispatch(WsMessageContext ctx, Envelope in, String sessionId) {
        // brush.end 单独接限流（其余三个 op 保持豁免，见类注释）
        if (isRateLimited(in.op()) && !rateLimiter.allow(sessionId)) {
            ctx.send(Envelope.error(in.id(), "RATE_LIMITED", "input rate exceeded; slow down"));
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
        } catch (RuntimeException re) {
            // 兜底：op 解析期任意运行期异常不应静默逃逸（逃到 Javalin 只会被 onError 记一行，
            // 客户端永远等不到 ack，界面看着像"点了没反应"）。与 EditOpDispatcher 同款分支。
            // 错误脱敏：细节只进服务器日志，客户端拿固定 code。
            LOG.log(java.util.logging.Level.WARNING, "brush op failed: op=" + in.op(), re);
            ctx.send(Envelope.error(in.id(), "INTERNAL_ERROR", "op processing failed"));
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
