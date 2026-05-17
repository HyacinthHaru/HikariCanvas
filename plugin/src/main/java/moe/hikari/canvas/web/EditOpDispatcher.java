package moe.hikari.canvas.web;

import io.javalin.websocket.WsMessageContext;
import moe.hikari.canvas.render.ProjectionThrottler;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.session.SessionRateLimiter;
import moe.hikari.canvas.state.EditSession;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.template.TemplateEntry;
import moe.hikari.canvas.template.TemplateInstantiator;
import moe.hikari.canvas.template.TemplateRegistry;

import java.util.List;
import java.util.Map;

import static moe.hikari.canvas.web.WebHelpers.asPayloadMap;
import static moe.hikari.canvas.web.WebHelpers.intOrNull;
import static moe.hikari.canvas.web.WebHelpers.mapOrEmpty;
import static moe.hikari.canvas.web.WebHelpers.stringOrNull;

/**
 * M3-T6 编辑 op 分发：22 个 {@code element.* / layer.* / canvas.* / undo / redo / history.mark / template.apply}。
 * <p>从 {@link WebServer} 抽出。每个成功 op 走 ack + pushPatch/pushSnapshot + throttler 投影 + 持久化的标准路径。</p>
 */
final class EditOpDispatcher {

    private final SessionManager sessionManager;
    private final ProjectionThrottler throttler;
    private final SessionRateLimiter rateLimiter;
    private final TemplateRegistry templateRegistry;
    private final TemplateInstantiator templateInstantiator = new TemplateInstantiator();
    private final moe.hikari.canvas.storage.WallRepo wallRepo;
    private final OpPushCallback push;
    /** M16 P6.4：可空——给 template.apply 跨用户拒绝路径写 audit。 */
    private final moe.hikari.canvas.storage.AuditLog auditLog;

    EditOpDispatcher(SessionManager sessionManager,
                     ProjectionThrottler throttler,
                     SessionRateLimiter rateLimiter,
                     TemplateRegistry templateRegistry,
                     moe.hikari.canvas.storage.WallRepo wallRepo,
                     OpPushCallback push) {
        this(sessionManager, throttler, rateLimiter, templateRegistry, wallRepo, push, null);
    }

    EditOpDispatcher(SessionManager sessionManager,
                     ProjectionThrottler throttler,
                     SessionRateLimiter rateLimiter,
                     TemplateRegistry templateRegistry,
                     moe.hikari.canvas.storage.WallRepo wallRepo,
                     OpPushCallback push,
                     moe.hikari.canvas.storage.AuditLog auditLog) {
        this.sessionManager = sessionManager;
        this.throttler = throttler;
        this.rateLimiter = rateLimiter;
        this.templateRegistry = templateRegistry;
        this.wallRepo = wallRepo;
        this.push = push;
        this.auditLog = auditLog;
    }

    void dispatch(WsMessageContext ctx, Envelope in, String sessionId) {
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
            case "canvas.background" -> {
                // M17 F5：协议 v2 升级——优先看 fill（Fill 对象，solid/linear/radial），
                // 兼容老 color（hex 字符串）。两者皆缺 → INVALID_PAYLOAD。
                Object fillRaw = payload.get("fill");
                if (fillRaw != null) {
                    try {
                        moe.hikari.canvas.state.Fill fill =
                                moe.hikari.canvas.state.ElementValidator.parseFillNullable(fillRaw);
                        yield es.setBackground(fill);
                    } catch (moe.hikari.canvas.state.ValidationException ve) {
                        yield new EditSession.OpResult.Error(ve.code, ve.getMessage());
                    }
                }
                yield es.setBackground(stringOrNull(payload.get("color")));
            }
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
                // M16 P1.6：跨用户隔离——查 caller 的 use-others bypass 权限
                java.util.UUID callerUuid = s.playerUuid();
                org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(callerUuid);
                boolean hasBypass = p != null && p.hasPermission("canvas.template.use-others");
                yield applyTemplate(es, sessionId, tpl, tp, callerUuid, s.playerName(), hasBypass);
            }
            default -> new EditSession.OpResult.Error("INVALID_OP", "unreachable: " + in.op());
        };

        switch (result) {
            case EditSession.OpResult.Ok ok -> {
                // 1) ack 给 client id
                ctx.send(Envelope.of("ack", in.id(), Map.of("version", ok.patch().version())));
                // 2) pushPatch（s-N id）——空 ops 的 patch 跳过
                if (!ok.patch().ops().isEmpty()) {
                    push.pushPatch(sessionId, ok.patch());
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
                    push.pushSnapshot(sessionId, sn.projectState());
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
     * M6-D template.apply 中枢：resolve registry → instantiate → replaceContent → walls write-back。
     * 不在主线程跑（持有当前 WS thread），但只做内存/DB I/O，不碰 Bukkit world API。
     */
    private EditSession.OpResult applyTemplate(EditSession es, String sessionId,
                                               String templateId, Map<String, Object> params,
                                               java.util.UUID callerUuid, String callerName,
                                               boolean hasBypass) {
        if (templateId == null || templateId.isBlank()) {
            return new EditSession.OpResult.Error("INVALID_PAYLOAD", "templateId is required");
        }
        TemplateEntry entry;
        try {
            entry = templateRegistry.byIdForApply(templateId, callerUuid, hasBypass);
        } catch (moe.hikari.canvas.template.ForbiddenTemplateException fte) {
            // M16 P1.6：包装为 FORBIDDEN，不 echo 内部异常细节
            // M16 P6.4：跨用户 template.apply 拒绝留痕（监控异常尝试用他人 template）
            if (auditLog != null) {
                java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
                details.put("operation", "template.apply");
                details.put("template_id", fte.templateId());
                auditLog.record("PERMISSION_DENIED",
                        callerUuid == null ? null : callerUuid.toString(),
                        callerName, sessionId, null, details);
            }
            return new EditSession.OpResult.Error("FORBIDDEN",
                    "template '" + fte.templateId() + "' not accessible");
        }
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
}
