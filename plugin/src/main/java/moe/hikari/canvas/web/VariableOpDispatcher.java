package moe.hikari.canvas.web;

import io.javalin.websocket.WsMessageContext;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.session.SessionRateLimiter;
import moe.hikari.canvas.state.EditSession;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.VariablePatch;
import moe.hikari.canvas.variable.VariableStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static moe.hikari.canvas.web.WebHelpers.asPayloadMap;
import static moe.hikari.canvas.web.WebHelpers.mapOrEmpty;
import static moe.hikari.canvas.web.WebHelpers.stringOrNull;

/**
 * 0.4.0-P1-B：{@code variable.*} 五个 WS op 的分发器。详见 {@code docs/protocol.md §5.11}
 * + {@code docs/dynamic-data.md §3.1 / §9}。
 *
 * <p>权限层（contract: {@code docs/dynamic-data.md §9.1}）：
 * <ul>
 *   <li>{@code variable.create / update / set / delete}：owner 走 {@code canvas.var.write.own}；
 *       非 owner 需 {@code canvas.var.write.any}。delete 单独细分 own/any 节点，本 v1 用 write 节点统管。</li>
 *   <li>{@code variable.bind}：敏感，统一查 {@code canvas.var.bind}。</li>
 * </ul>
 * 离线玩家无 {@code Player} 实例 → {@code hasPermission} 不可用；遵循项目惯例（参考
 * {@link WallOpDispatcher} alias / template 处理）按"无 bypass"处理：owner 仍可走 own 路径，
 * 非 owner 拒 {@code FORBIDDEN}。</p>
 *
 * <p>所有变量变更通过 {@link EditSession} 走完整 ack + state.patch 路径；变量值变更不影响
 * 画布像素（dirty=null），渲染期改 ${var:X} → currentValue 的工作由 C agent 在
 * CanvasCompositor 完成 + 经 VariableStore.wallDirtyCallback 触发投影重画。</p>
 */
final class VariableOpDispatcher {

    private final SessionManager sessionManager;
    private final SessionRateLimiter rateLimiter;
    private final VariableStore variableStore;
    private final OpPushCallback push;
    private final moe.hikari.canvas.storage.AuditLog auditLog;
    private final moe.hikari.canvas.storage.WallRepo wallRepo;

    VariableOpDispatcher(SessionManager sessionManager,
                         SessionRateLimiter rateLimiter,
                         VariableStore variableStore,
                         moe.hikari.canvas.storage.WallRepo wallRepo,
                         OpPushCallback push,
                         moe.hikari.canvas.storage.AuditLog auditLog) {
        this.sessionManager = sessionManager;
        this.rateLimiter = rateLimiter;
        this.variableStore = variableStore;
        this.wallRepo = wallRepo;
        this.push = push;
        this.auditLog = auditLog;
    }

    void dispatch(WsMessageContext ctx, Envelope in, String sessionId) {
        // 同 editOpDispatcher：输入限流先于鉴权 — 防恶意 token 后大流量 op 打爆 store。
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
        if (s.wallId() == null) {
            ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND",
                    "session has no bound wall"));
            return;
        }
        if (variableStore == null) {
            ctx.send(Envelope.error(in.id(), "INTERNAL_ERROR",
                    "variable store not configured"));
            return;
        }

        Map<String, Object> payload;
        try {
            payload = asPayloadMap(in.payload());
        } catch (IllegalArgumentException iae) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", iae.getMessage()));
            return;
        }

        // 权限检查：write 类 op vs bind op。
        String op = in.op();
        if (!checkPermission(ctx, in, sessionId, s, op)) return;

        EditSession es = s.editSession();
        String wallId = s.wallId();

        EditSession.OpResult result = switch (op) {
            case "variable.create" -> handleCreate(es, wallId, payload);
            case "variable.update" -> handleUpdate(es, wallId, payload);
            case "variable.set" -> handleSet(es, wallId, payload);
            case "variable.delete" -> handleDelete(es, wallId, payload);
            case "variable.bind" -> handleBind(es, wallId, payload);
            default -> new EditSession.OpResult.Error("INVALID_OP",
                    "unknown variable op: " + op);
        };

        switch (result) {
            case EditSession.OpResult.Ok ok -> {
                // ack + state.patch；变量 op 不动 dirty（像素层），所以不调 throttler
                Map<String, Object> ackPayload = new LinkedHashMap<>();
                ackPayload.put("version", ok.patch().version());
                // create 给前端回 fullName 方便后续 op 索引
                if ("variable.create".equals(op)) {
                    String createdFullName = extractCreatedFullName(ok);
                    if (createdFullName != null) ackPayload.put("fullName", createdFullName);
                }
                ctx.send(Envelope.of("ack", in.id(), ackPayload));
                if (!ok.patch().ops().isEmpty()) {
                    push.pushPatch(sessionId, ok.patch());
                }
                // audit（成功路径）
                recordAuditSuccess(op, sessionId, s, payload, ok);
            }
            case EditSession.OpResult.Error er -> {
                ctx.send(Envelope.error(in.id(), er.code(), er.message()));
            }
            case EditSession.OpResult.OkSnapshot oks ->
                    ctx.send(Envelope.error(in.id(), "UNEXPECTED",
                            "variable op returned snapshot, version=" + oks.version()));
            case EditSession.OpResult.OkBrushStart obs ->
                    ctx.send(Envelope.error(in.id(), "UNEXPECTED",
                            "variable op returned brush start: " + obs.strokeId()));
        }
    }

    // ──────────────────────────────────────────────────────────
    //  per-op payload 解析
    // ──────────────────────────────────────────────────────────

    private EditSession.OpResult handleCreate(EditSession es, String wallId,
                                              Map<String, Object> payload) {
        String name = stringOrNull(payload.get("name"));
        VarType type = parseVarTypeOrNull(payload.get("type"));
        if (type == null) {
            return new EditSession.OpResult.Error("INVALID_PAYLOAD",
                    "variable type required: STRING / NUMBER / BOOLEAN / COLOR");
        }
        String defaultValue = stringOrNull(payload.get("defaultValue"));
        return es.createVariable(variableStore, wallId, name, type, defaultValue);
    }

    private EditSession.OpResult handleUpdate(EditSession es, String wallId,
                                              Map<String, Object> payload) {
        String fullName = stringOrNull(payload.get("fullName"));
        Object patchRaw = payload.get("patch");
        Map<?, ?> patchMap;
        try {
            patchMap = mapOrEmpty(patchRaw);
        } catch (IllegalArgumentException iae) {
            return new EditSession.OpResult.Error("INVALID_PAYLOAD", iae.getMessage());
        }
        VarType newType = patchMap.containsKey("type")
                ? parseVarTypeOrNull(patchMap.get("type"))
                : null;
        // patch.type 出现但解析失败 → INVALID_PAYLOAD
        if (patchMap.containsKey("type") && newType == null) {
            return new EditSession.OpResult.Error("INVALID_PAYLOAD",
                    "patch.type invalid: " + patchMap.get("type"));
        }
        String newDefault = patchMap.containsKey("defaultValue")
                ? stringOrNull(patchMap.get("defaultValue"))
                : null;
        VariablePatch patch = new VariablePatch(newType, newDefault);
        return es.updateUserVariable(variableStore, wallId, fullName, patch);
    }

    private EditSession.OpResult handleSet(EditSession es, String wallId,
                                           Map<String, Object> payload) {
        String fullName = stringOrNull(payload.get("fullName"));
        // value 允许 null（明确清值）；非 null 时必须是 string
        Object raw = payload.get("value");
        String value;
        if (raw == null) {
            value = null;
        } else if (raw instanceof String s) {
            value = s;
        } else {
            // number / boolean 入参允许，自动 toString（变量是 string store）
            value = raw.toString();
        }
        return es.setUserVariableValue(variableStore, wallId, fullName, value);
    }

    private EditSession.OpResult handleDelete(EditSession es, String wallId,
                                              Map<String, Object> payload) {
        String fullName = stringOrNull(payload.get("fullName"));
        return es.deleteUserVariable(variableStore, wallId, fullName);
    }

    private EditSession.OpResult handleBind(EditSession es, String wallId,
                                            Map<String, Object> payload) {
        String fullName = stringOrNull(payload.get("fullName"));
        // boundTo: string | null（null = 解绑）
        Object raw = payload.get("boundTo");
        String boundTo = raw == null ? null : (raw instanceof String s ? s : raw.toString());
        return es.bindUserVariable(variableStore, wallId, fullName, boundTo);
    }

    // ──────────────────────────────────────────────────────────
    //  权限
    // ──────────────────────────────────────────────────────────

    /**
     * 检查权限。返 false 表示已发 error frame；调用方应直接 return。
     *
     * <p>own / any 判定：caller 是 wall owner → 走 own 节点；否则走 any 节点（更严）。
     * bind 单独 {@code canvas.var.bind}（不分 own/any，与 dynamic-data.md §9.1 一致）。</p>
     */
    private boolean checkPermission(WsMessageContext ctx, Envelope in, String sessionId,
                                    Session s, String op) {
        UUID callerUuid = s.playerUuid();
        Player player = callerUuid == null ? null : Bukkit.getPlayer(callerUuid);
        // 判定 owner = playerUuid 等于 wall.owner_uuid
        moe.hikari.canvas.storage.WallRepo.Wall wall = wallRepo.loadById(s.wallId()).orElse(null);
        if (wall == null) {
            ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "wall not found"));
            return false;
        }
        boolean isOwnerOnly = wall.ownerUuid().equals(callerUuid);

        String requiredNode;
        if ("variable.bind".equals(op)) {
            requiredNode = "canvas.var.bind";
        } else {
            requiredNode = isOwnerOnly ? "canvas.var.write.own" : "canvas.var.write.any";
        }
        boolean granted = player != null && player.hasPermission(requiredNode);
        // own 节点 default=true（dynamic-data.md §9.1）；offline 玩家也允许 own 路径，
        // 与 SessionManager.open 处理 paper-plugin.yml default 节点一致。
        if (!granted && "canvas.var.write.own".equals(requiredNode)) {
            granted = true;
        }
        if (granted) return true;

        // 拒：发 error + audit
        if (auditLog != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("operation", op);
            details.put("required", requiredNode);
            details.put("wall_id", s.wallId());
            auditLog.record("PERMISSION_DENIED",
                    callerUuid == null ? null : callerUuid.toString(),
                    s.playerName(), sessionId, null, details);
        }
        ctx.send(Envelope.error(in.id(), "PERMISSION_DENIED",
                "missing permission: " + requiredNode));
        return false;
    }

    // ──────────────────────────────────────────────────────────
    //  helpers
    // ──────────────────────────────────────────────────────────

    private static VarType parseVarTypeOrNull(Object raw) {
        if (!(raw instanceof String s)) return null;
        try {
            return VarType.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * variable.create 的 OpResult.Ok 内首条 patch op = add /variables/<encoded>，
     * value 是 Map；从 path 反 decode fullName 给 ack 用。简单 string 操作不再二次反向。
     */
    private static String extractCreatedFullName(EditSession.OpResult.Ok ok) {
        if (ok.patch() == null || ok.patch().ops().isEmpty()) return null;
        var first = ok.patch().ops().get(0);
        String path = first.path();
        if (path == null || !path.startsWith("/variables/")) return null;
        String encoded = path.substring("/variables/".length());
        // JSON Pointer 反解：~1 → /，~0 → ~（按 RFC 6901 顺序）
        return encoded.replace("~1", "/").replace("~0", "~");
    }

    private void recordAuditSuccess(String op, String sessionId, Session s,
                                    Map<String, Object> payload,
                                    EditSession.OpResult.Ok ok) {
        if (auditLog == null) return;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("wall_id", s.wallId());
        String event;
        switch (op) {
            case "variable.create" -> {
                event = "VARIABLE_CREATE";
                details.put("name", stringOrNull(payload.get("name")));
                details.put("type", stringOrNull(payload.get("type")));
                details.put("defaultValue", stringOrNull(payload.get("defaultValue")));
            }
            case "variable.update" -> {
                event = "VARIABLE_UPDATE";
                details.put("fullName", stringOrNull(payload.get("fullName")));
                details.put("patch", payload.get("patch"));
            }
            case "variable.set" -> {
                event = "VARIABLE_SET";
                details.put("fullName", stringOrNull(payload.get("fullName")));
                details.put("value", payload.get("value"));
            }
            case "variable.delete" -> {
                event = "VARIABLE_DELETE";
                details.put("fullName", stringOrNull(payload.get("fullName")));
            }
            case "variable.bind" -> {
                event = "VARIABLE_BIND";
                details.put("fullName", stringOrNull(payload.get("fullName")));
                details.put("boundTo", payload.get("boundTo"));
            }
            default -> { return; }
        }
        auditLog.record(event,
                s.playerUuid() == null ? null : s.playerUuid().toString(),
                s.playerName(), sessionId, null, details);
    }
}
