package moe.hikari.canvas.web;

import io.javalin.websocket.WsMessageContext;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.session.SessionRateLimiter;
import moe.hikari.canvas.state.EditSession;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.VariablePatch;
import moe.hikari.canvas.variable.VariableStore;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static moe.hikari.canvas.web.WebHelpers.asPayloadMap;
import static moe.hikari.canvas.web.WebHelpers.mapOrEmpty;
import static moe.hikari.canvas.web.WebHelpers.stringOrNull;

/**
 * {@code variable.*} 五个 WS op 的分发器。详见 {@code docs/protocol.md §5.11}
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
 * 画布像素（dirty=null），渲染期改 ${var:X} → currentValue 的工作在
 * CanvasCompositor 完成 + 经 VariableStore.wallDirtyCallback 触发投影重画。</p>
 */
final class VariableOpDispatcher {

    private final SessionManager sessionManager;
    private final SessionRateLimiter rateLimiter;
    private final VariableStore variableStore;
    private final OpPushCallback push;
    private final moe.hikari.canvas.storage.AuditLog auditLog;
    private final moe.hikari.canvas.storage.WallRepo wallRepo;
    /** 主线程权限解析用宿主插件；可为 null（测试装配走直接调用）。 */
    private final org.bukkit.plugin.Plugin plugin;

    VariableOpDispatcher(SessionManager sessionManager,
                         SessionRateLimiter rateLimiter,
                         VariableStore variableStore,
                         moe.hikari.canvas.storage.WallRepo wallRepo,
                         OpPushCallback push,
                         moe.hikari.canvas.storage.AuditLog auditLog,
                         org.bukkit.plugin.Plugin plugin) {
        this.sessionManager = sessionManager;
        this.rateLimiter = rateLimiter;
        this.variableStore = variableStore;
        this.wallRepo = wallRepo;
        this.push = push;
        this.auditLog = auditLog;
        this.plugin = plugin;
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

        // 权限检查：write 类 op vs bind op；全局变量走独立权限节点。
        String op = in.op();
        Map<String, Object> payloadForPerm = payload;
        if (!checkPermission(ctx, in, sessionId, s, op, payloadForPerm)) return;

        EditSession es = s.editSession();
        String wallId = s.wallId();

        // scope='global' 走 createGlobalVariable；update/set/delete/bind 按 fullName
        // 前缀（userglobal/）自动路由——dispatcher 不要求用户额外传 scope 字段，复用 fullName 形态
        boolean isGlobalCreate = "global".equalsIgnoreCase(
                String.valueOf(payload.getOrDefault("scope", "wall")));
        boolean isGlobalMutate = !isGlobalCreate && isGlobalOpByFullName(op, payload);

        EditSession.OpResult result = switch (op) {
            case "variable.create" -> isGlobalCreate
                    ? handleCreateGlobal(es, s, payload)
                    : handleCreate(es, wallId, payload);
            case "variable.update" -> isGlobalMutate
                    ? handleUpdateGlobal(es, payload)
                    : handleUpdate(es, wallId, payload);
            case "variable.set" -> isGlobalMutate
                    ? handleSetGlobal(es, payload)
                    : handleSet(es, wallId, payload);
            case "variable.delete" -> isGlobalMutate
                    ? handleDeleteGlobal(es, payload)
                    : handleDelete(es, wallId, payload);
            case "variable.bind" -> isGlobalMutate
                    ? handleBindGlobal(es, payload)
                    : handleBind(es, wallId, payload);
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
    //  全局用户变量 handlers
    // ──────────────────────────────────────────────────────────

    private EditSession.OpResult handleCreateGlobal(EditSession es, Session s,
                                                    Map<String, Object> payload) {
        String name = stringOrNull(payload.get("name"));
        VarType type = parseVarTypeOrNull(payload.get("type"));
        if (type == null) {
            return new EditSession.OpResult.Error("INVALID_PAYLOAD",
                    "variable type required: STRING / NUMBER / BOOLEAN / COLOR");
        }
        String defaultValue = stringOrNull(payload.get("defaultValue"));
        UUID owner = s.playerUuid();
        String ownerName = s.playerName();
        if (owner == null) {
            return new EditSession.OpResult.Error("FORBIDDEN",
                    "caller uuid required for userglobal variable");
        }
        return es.createGlobalVariable(variableStore, owner, ownerName,
                name, type, defaultValue);
    }

    private EditSession.OpResult handleUpdateGlobal(EditSession es, Map<String, Object> payload) {
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
        if (patchMap.containsKey("type") && newType == null) {
            return new EditSession.OpResult.Error("INVALID_PAYLOAD",
                    "patch.type invalid: " + patchMap.get("type"));
        }
        String newDefault = patchMap.containsKey("defaultValue")
                ? stringOrNull(patchMap.get("defaultValue"))
                : null;
        VariablePatch patch = new VariablePatch(newType, newDefault);
        return es.updateGlobalVariable(variableStore, fullName, patch);
    }

    private EditSession.OpResult handleSetGlobal(EditSession es, Map<String, Object> payload) {
        String fullName = stringOrNull(payload.get("fullName"));
        Object raw = payload.get("value");
        String value;
        if (raw == null) {
            value = null;
        } else if (raw instanceof String s) {
            value = s;
        } else {
            value = raw.toString();
        }
        return es.setGlobalVariableValue(variableStore, fullName, value);
    }

    private EditSession.OpResult handleDeleteGlobal(EditSession es, Map<String, Object> payload) {
        String fullName = stringOrNull(payload.get("fullName"));
        return es.deleteGlobalVariable(variableStore, fullName);
    }

    private EditSession.OpResult handleBindGlobal(EditSession es, Map<String, Object> payload) {
        String fullName = stringOrNull(payload.get("fullName"));
        Object raw = payload.get("boundTo");
        String boundTo = raw == null ? null : (raw instanceof String s ? s : raw.toString());
        return es.bindGlobalVariable(variableStore, fullName, boundTo);
    }

    /**
     * op + payload 形态判断当前操作是否针对 userglobal 变量。
     * update/set/delete/bind 走 fullName 前缀；create 由 caller 处理（scope 字段）。
     */
    private static boolean isGlobalOpByFullName(String op, Map<String, Object> payload) {
        if (op == null || !op.startsWith("variable.")) return false;
        if ("variable.create".equals(op)) return false;
        String fullName = stringOrNull(payload.get("fullName"));
        return fullName != null && fullName.startsWith(EditSession.USERGLOBAL_PREFIX);
    }

    // ──────────────────────────────────────────────────────────
    //  权限
    // ──────────────────────────────────────────────────────────

    /**
     * 检查权限。返 false 表示已发 error frame；调用方应直接 return。
     *
     * <p><b>per-wall user 变量</b>（默认路径）：own / any 判定 = caller 是否 wall owner；
     * bind 单独 {@code canvas.var.bind}（不分 own/any）。</p>
     *
     * <p><b>0.4.3 全局用户变量</b>（{@code scope='global'} create 或 fullName 以
     * {@code userglobal/} 开头的 mutation）：own / any 判定 = caller 是否 variable owner
     * （{@code VariableStore.getGlobalOwner}）；create 单独 {@code canvas.var.global.create}；
     * delete 单独 {@code .delete.own/any}；其他改 type/value/bind 走 {@code .write.own/any}。</p>
     */
    private boolean checkPermission(WsMessageContext ctx, Envelope in, String sessionId,
                                    Session s, String op, Map<String, Object> payload) {
        UUID callerUuid = s.playerUuid();

        // 路径区分：全局用户变量 vs per-wall user 变量
        boolean isGlobalCreate = "variable.create".equals(op)
                && "global".equalsIgnoreCase(String.valueOf(
                        payload.getOrDefault("scope", "wall")));
        boolean isGlobalMutate = !isGlobalCreate && isGlobalOpByFullName(op, payload);
        boolean isGlobal = isGlobalCreate || isGlobalMutate;

        String requiredNode;
        if (isGlobal) {
            requiredNode = pickGlobalPermissionNode(op, callerUuid, payload);
        } else {
            // 原有 per-wall user 变量逻辑（不变）
            moe.hikari.canvas.storage.WallRepo.Wall wall =
                    wallRepo.loadById(s.wallId()).orElse(null);
            if (wall == null) {
                ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "wall not found"));
                return false;
            }
            boolean isOwnerOnly = wall.ownerUuid().equals(callerUuid);
            if ("variable.bind".equals(op)) {
                requiredNode = "canvas.var.bind";
            } else {
                requiredNode = isOwnerOnly ? "canvas.var.write.own" : "canvas.var.write.any";
            }
        }

        // 主线程解析在线玩家权限（Bukkit.getPlayer + hasPermission 主线程专用），
        // 复用 auth 路径同款 callSyncMethod；离线 / 超时 / 异常返 false（own 节点下方兜底放行）。
        boolean granted = MainThreadPerms.hasPermission(plugin, callerUuid, requiredNode);
        // own / create 节点 default=true（paper-plugin.yml）；offline 玩家 / 测试期 mock
        // 也允许 own / create 路径，与 SessionManager.open 处理 default 节点惯例一致。
        if (!granted && isOwnNodeDefaultTrue(requiredNode)) {
            granted = true;
        }
        if (granted) return true;

        // 拒：发 error + audit
        if (auditLog != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("operation", op);
            details.put("required", requiredNode);
            details.put("wall_id", s.wallId());
            if (isGlobal) details.put("scope", "global");
            auditLog.record("PERMISSION_DENIED",
                    callerUuid == null ? null : callerUuid.toString(),
                    s.playerName(), sessionId, null, details);
        }
        ctx.send(Envelope.error(in.id(), "PERMISSION_DENIED",
                "missing permission: " + requiredNode));
        return false;
    }

    /**
     * 选 global 路径需要的权限节点。
     *
     * <ul>
     *   <li>{@code variable.create} (scope=global) → {@code canvas.var.global.create}</li>
     *   <li>{@code variable.delete} (userglobal/) → owner? {@code .delete.own} : {@code .delete.any}</li>
     *   <li>{@code variable.update / set / bind} (userglobal/) → owner? {@code .write.own} : {@code .write.any}</li>
     * </ul>
     */
    private String pickGlobalPermissionNode(String op, UUID callerUuid,
                                            Map<String, Object> payload) {
        if ("variable.create".equals(op)) {
            return "canvas.var.global.create";
        }
        String fullName = stringOrNull(payload.get("fullName"));
        boolean ownerOnly = isCallerGlobalOwner(callerUuid, fullName);
        if ("variable.delete".equals(op)) {
            return ownerOnly ? "canvas.var.global.delete.own" : "canvas.var.global.delete.any";
        }
        // update / set / bind 共用 write.own/.any（dynamic-data.md §17.3）
        return ownerOnly ? "canvas.var.global.write.own" : "canvas.var.global.write.any";
    }

    /**
     * caller 是否本全局变量的 owner？变量不存在时返 false（让 .any 路径处理，
     * 走 store 时再抛 VARIABLE_NOT_FOUND）。
     */
    private boolean isCallerGlobalOwner(UUID callerUuid, String fullName) {
        if (callerUuid == null || fullName == null
                || !fullName.startsWith(EditSession.USERGLOBAL_PREFIX)) {
            return false;
        }
        String key = fullName.substring(EditSession.USERGLOBAL_PREFIX.length());
        return variableStore.getGlobalOwner(key)
                .map(info -> callerUuid.equals(info.ownerUuid()))
                .orElse(false);
    }

    /**
     * 哪些节点是 default=true（offline 玩家也通行）。
     * 与 paper-plugin.yml 一致：{@code .own / .create} 节点默认通；{@code .any / .bind} 默认 op。
     */
    private static boolean isOwnNodeDefaultTrue(String node) {
        return node.equals("canvas.var.write.own")
                || node.equals("canvas.var.global.create")
                || node.equals("canvas.var.global.write.own")
                || node.equals("canvas.var.global.delete.own");
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
        // global 事件单独命名前缀 VARIABLE_GLOBAL_*，让运维过滤 + 审计搜索更精准
        boolean isGlobalCreate = "variable.create".equals(op)
                && "global".equalsIgnoreCase(String.valueOf(
                        payload.getOrDefault("scope", "wall")));
        boolean isGlobalMutate = !isGlobalCreate && isGlobalOpByFullName(op, payload);
        boolean isGlobal = isGlobalCreate || isGlobalMutate;

        Map<String, Object> details = new LinkedHashMap<>();
        if (isGlobal) {
            details.put("scope", "global");
            // global 路径也带上发起端 wall_id（让审计能定位调用上下文）
            if (s.wallId() != null) details.put("via_wall_id", s.wallId());
        } else {
            details.put("wall_id", s.wallId());
        }
        String prefix = isGlobal ? "VARIABLE_GLOBAL_" : "VARIABLE_";
        String event;
        switch (op) {
            case "variable.create" -> {
                event = prefix + "CREATE";
                details.put("name", stringOrNull(payload.get("name")));
                details.put("type", stringOrNull(payload.get("type")));
                details.put("defaultValue", stringOrNull(payload.get("defaultValue")));
            }
            case "variable.update" -> {
                event = prefix + "UPDATE";
                details.put("fullName", stringOrNull(payload.get("fullName")));
                details.put("patch", payload.get("patch"));
            }
            case "variable.set" -> {
                event = prefix + "SET";
                details.put("fullName", stringOrNull(payload.get("fullName")));
                details.put("value", payload.get("value"));
            }
            case "variable.delete" -> {
                event = prefix + "DELETE";
                details.put("fullName", stringOrNull(payload.get("fullName")));
            }
            case "variable.bind" -> {
                event = prefix + "BIND";
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
