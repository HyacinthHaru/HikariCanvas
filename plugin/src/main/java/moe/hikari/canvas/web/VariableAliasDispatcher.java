package moe.hikari.canvas.web;

import io.javalin.websocket.WsMessageContext;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.session.SessionRateLimiter;
import moe.hikari.canvas.state.PatchOp;
import moe.hikari.canvas.state.StatePatch;
import moe.hikari.canvas.storage.VariableAliasDao;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static moe.hikari.canvas.web.WebHelpers.asPayloadMap;
import static moe.hikari.canvas.web.WebHelpers.stringOrNull;

/**
 * 0.4.2：{@code variable.alias.*} WS op 分发器。详见 {@code docs/variables.md §1.12} +
 * {@code docs/protocol.md §5.13}。
 *
 * <h2>3 个 op</h2>
 *
 * <ul>
 *   <li>{@code variable.alias.set {fullName, alias}} — upsert 别名；alias 非空且 ≤ 64 字符</li>
 *   <li>{@code variable.alias.clear {fullName}} — 删除别名</li>
 *   <li>{@code variable.alias.list} — 查当前 wall 的所有别名</li>
 * </ul>
 *
 * <h2>权限</h2>
 *
 * <p>沿用 variable write 节点：owner 走 {@code canvas.var.write.own}（default=true）；
 * 非 owner 需 {@code canvas.var.write.any}。理由：别名是 wall-scoped 元数据，作者负责
 * 命名清晰度即可，没必要单独再起一组节点。</p>
 *
 * <h2>state.patch 路径</h2>
 *
 * <p>{@code /aliases/<encoded fullName>}（JSON Pointer 编码 ~ → ~0, / → ~1）。
 * add/replace 携 alias 字符串；remove 不携 value。前端 wsClient 按 {@code /aliases/} 前缀
 * 分拣后落 {@link moe.hikari.canvas.variable} mirror（VariableAliasStore）。</p>
 *
 * <p>同 {@link ScheduleOpDispatcher} 风格，不走 EditSession（别名不影响 ProjectState
 * / 像素 dirty / version），dispatcher 自己产生 state.patch（version=0 占位，前端不读
 * 此通道的 version）发推。</p>
 */
final class VariableAliasDispatcher {

    /** alias 字符上限（与 DAO 常量对齐）。 */
    private static final int ALIAS_MAX_LEN = VariableAliasDao.ALIAS_MAX_LEN;
    /** fullName 字符上限。 */
    private static final int FULL_NAME_MAX_LEN = VariableAliasDao.FULL_NAME_MAX_LEN;

    private final SessionManager sessionManager;
    private final SessionRateLimiter rateLimiter;
    private final VariableAliasDao dao;
    private final moe.hikari.canvas.storage.WallRepo wallRepo;
    private final OpPushCallback push;
    private final moe.hikari.canvas.storage.AuditLog auditLog;

    VariableAliasDispatcher(SessionManager sessionManager,
                            SessionRateLimiter rateLimiter,
                            VariableAliasDao dao,
                            moe.hikari.canvas.storage.WallRepo wallRepo,
                            OpPushCallback push,
                            moe.hikari.canvas.storage.AuditLog auditLog) {
        this.sessionManager = sessionManager;
        this.rateLimiter = rateLimiter;
        this.dao = dao;
        this.wallRepo = wallRepo;
        this.push = push;
        this.auditLog = auditLog;
    }

    void dispatch(WsMessageContext ctx, Envelope in, String sessionId) {
        if (!rateLimiter.allow(sessionId)) {
            ctx.send(Envelope.error(in.id(), "RATE_LIMITED",
                    "input rate exceeded; slow down"));
            return;
        }
        Session s = sessionManager.byId(sessionId);
        if (s == null) {
            ctx.send(Envelope.error(in.id(), "SESSION_CLOSED", "no active session"));
            return;
        }
        if (s.wallId() == null) {
            ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND",
                    "session has no bound wall"));
            return;
        }
        if (dao == null) {
            ctx.send(Envelope.error(in.id(), "INTERNAL_ERROR",
                    "variable alias dao not configured"));
            return;
        }

        String op = in.op();

        // list op 无 payload 必读字段；其他 op 走标准解析
        Map<String, Object> payload;
        if ("variable.alias.list".equals(op)) {
            payload = Map.of();
        } else {
            try {
                payload = asPayloadMap(in.payload());
            } catch (IllegalArgumentException iae) {
                ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", iae.getMessage()));
                return;
            }
        }

        if (!checkPermission(ctx, in, sessionId, s, op)) return;

        String wallId = s.wallId();
        switch (op) {
            case "variable.alias.set" -> handleSet(ctx, in, sessionId, s, wallId, payload);
            case "variable.alias.clear" -> handleClear(ctx, in, sessionId, s, wallId, payload);
            case "variable.alias.list" -> handleList(ctx, in, wallId);
            default -> ctx.send(Envelope.error(in.id(), "INVALID_OP",
                    "unknown variable alias op: " + op));
        }
    }

    // ──────────────────────────────────────────────────────────
    //  per-op handlers
    // ──────────────────────────────────────────────────────────

    private void handleSet(WsMessageContext ctx, Envelope in, String sessionId,
                           Session s, String wallId, Map<String, Object> payload) {
        String fullName = stringOrNull(payload.get("fullName"));
        if (fullName == null || fullName.isEmpty()) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "fullName required"));
            return;
        }
        if (fullName.length() > FULL_NAME_MAX_LEN) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                    "fullName too long (max " + FULL_NAME_MAX_LEN + ")"));
            return;
        }
        String alias = stringOrNull(payload.get("alias"));
        if (alias == null) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                    "alias required (use variable.alias.clear to remove)"));
            return;
        }
        String trimmed = alias.trim();
        if (trimmed.isEmpty()) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                    "alias cannot be empty"));
            return;
        }
        if (trimmed.length() > ALIAS_MAX_LEN) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                    "alias too long (max " + ALIAS_MAX_LEN + ")"));
            return;
        }

        long now = System.currentTimeMillis();
        dao.upsert(wallId, fullName, trimmed, now);

        // 推 state.patch：path=/aliases/<encoded>，value=alias 字符串
        // 已存在的别名 → replace 语义；前端 mirror set(key, value) 幂等，统一用 add 即可。
        // 与 /variables/ 通道同款 RFC 6901 JSON Pointer 编码。
        // Ultrareview 2026-05-25 #17：用当前 session ProjectState.version 不要写 0
        // —— 前端 applyPatch 会把空 projectOps 的 patch.version 当作 state.version 覆盖，
        // 写 0 会把 ProjectState.version 倒退到 0 影响后续 op 冲突判定。
        String path = "/aliases/" + encodeJsonPointer(fullName);
        StatePatch patch = new StatePatch(currentVersion(s), List.of(PatchOp.add(path, trimmed)));
        push.pushPatch(sessionId, patch);

        recordAudit("VARIABLE_ALIAS_SET", sessionId, s, wallId, Map.of(
                "full_name", fullName,
                "alias", trimmed));

        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("fullName", fullName);
        ack.put("alias", trimmed);
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    private void handleClear(WsMessageContext ctx, Envelope in, String sessionId,
                             Session s, String wallId, Map<String, Object> payload) {
        String fullName = stringOrNull(payload.get("fullName"));
        if (fullName == null || fullName.isEmpty()) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "fullName required"));
            return;
        }
        if (fullName.length() > FULL_NAME_MAX_LEN) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                    "fullName too long (max " + FULL_NAME_MAX_LEN + ")"));
            return;
        }
        int rows = dao.delete(wallId, fullName);

        // 即使 rows=0（别名不存在）也推 remove 让前端 mirror 保持一致——idempotent
        // Ultrareview 2026-05-25 #17：用当前 session ProjectState.version 不要写 0
        String path = "/aliases/" + encodeJsonPointer(fullName);
        StatePatch patch = new StatePatch(currentVersion(s), List.of(PatchOp.remove(path)));
        push.pushPatch(sessionId, patch);

        recordAudit("VARIABLE_ALIAS_CLEAR", sessionId, s, wallId, Map.of(
                "full_name", fullName,
                "removed_rows", rows));

        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("fullName", fullName);
        ack.put("removed", rows > 0);
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    private void handleList(WsMessageContext ctx, Envelope in, String wallId) {
        Map<String, String> aliases = dao.loadByWall(wallId);
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("aliases", aliases);
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    // ──────────────────────────────────────────────────────────
    //  权限
    // ──────────────────────────────────────────────────────────

    private boolean checkPermission(WsMessageContext ctx, Envelope in, String sessionId,
                                    Session s, String op) {
        UUID callerUuid = s.playerUuid();
        Player player = callerUuid == null ? null : Bukkit.getPlayer(callerUuid);
        moe.hikari.canvas.storage.WallRepo.Wall wall = wallRepo.loadById(s.wallId()).orElse(null);
        if (wall == null) {
            ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "wall not found"));
            return false;
        }
        // list 是只读，复用 var write own 节点（default=true）即可；任何能 open 该 wall 的玩家可读
        if ("variable.alias.list".equals(op)) {
            return true;
        }
        boolean isOwnerOnly = wall.ownerUuid().equals(callerUuid);
        String requiredNode = isOwnerOnly ? "canvas.var.write.own" : "canvas.var.write.any";
        boolean granted = player != null && player.hasPermission(requiredNode);
        // own 节点 default=true（与 var write 一致）；offline 玩家 own 路径放行
        if (!granted && "canvas.var.write.own".equals(requiredNode)) {
            granted = true;
        }
        if (granted) return true;

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

    /**
     * JSON Pointer 段编码（RFC 6901 §3）：{@code ~} → {@code ~0}，{@code /} → {@code ~1}。
     * 与 {@code EditSession.encodeJsonPointer} 同款。
     */
    private static String encodeJsonPointer(String s) {
        return s.replace("~", "~0").replace("/", "~1");
    }

    /**
     * Ultrareview 2026-05-25 #17：从 session 拿当前 ProjectState.version。
     * 别名通道不改 ProjectState 像素 → 不 bump，但要保持前端 mirror 看到的版本号一致。
     * session 无 ProjectState（极早期）回 0。
     */
    private static long currentVersion(Session s) {
        if (s == null) return 0L;
        moe.hikari.canvas.state.ProjectState ps = s.projectState();
        return ps == null ? 0L : ps.version();
    }

    private void recordAudit(String event, String sessionId, Session s, String wallId,
                             Map<String, Object> details) {
        if (auditLog == null) return;
        Map<String, Object> d = new LinkedHashMap<>(details);
        d.put("wall_id", wallId);
        auditLog.record(event,
                s.playerUuid() == null ? null : s.playerUuid().toString(),
                s.playerName(), sessionId, null, d);
    }
}
