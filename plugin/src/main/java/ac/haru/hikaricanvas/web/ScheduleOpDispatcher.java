package ac.haru.hikaricanvas.web;

import io.javalin.websocket.WsMessageContext;
import ac.haru.hikaricanvas.schedule.ScheduleEntry;
import ac.haru.hikaricanvas.schedule.WallSchedule;
import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionManager;
import ac.haru.hikaricanvas.session.SessionRateLimiter;
import ac.haru.hikaricanvas.storage.ScheduleDao;
import ac.haru.hikaricanvas.variable.provider.ManualScheduleProvider;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static ac.haru.hikaricanvas.web.WebHelpers.asPayloadMap;
import static ac.haru.hikaricanvas.web.WebHelpers.intOrNull;
import static ac.haru.hikaricanvas.web.WebHelpers.stringOrNull;

/**
 * {@code schedule.*} WS op 分发器。详见 {@code docs/protocol.md §5.12}
 * + {@code docs/dynamic-data.md §7.3}。
 *
 * <h2>5 个 op</h2>
 *
 * <ul>
 *   <li>{@code schedule.upsert} — 创建 / 更新 wall_schedules 元数据（{@code stationName}）</li>
 *   <li>{@code schedule.entry.add} — 添加时刻表 entry；返 {@code id}</li>
 *   <li>{@code schedule.entry.update} — 改单条 entry（按 id）</li>
 *   <li>{@code schedule.entry.delete} — 删单条 entry（按 id）</li>
 *   <li>{@code schedule.list} — 查当前 wall 的完整 schedule（首次打开 modal 用）</li>
 * </ul>
 *
 * <h2>权限</h2>
 *
 * <p>所有 op 走 {@code canvas.schedule.own}（default=true，与 var write own 同款）；
 * 非 owner 需 {@code canvas.schedule.any}（default=op）。entry.* op 同时触发
 * {@link ManualScheduleProvider#refreshWall(String)}：让 store 内 4 个 schedule:* 变量
 * 立即重算，避免等 30s refresh tick。</p>
 *
 * <p>同 {@link VariableOpDispatcher} 风格，不走 EditSession（schedule 不影响 ProjectState
 * / 像素 dirty），只发 ack；前端 ScheduleStore 用 ack payload 自己更新本地 mirror。</p>
 */
final class ScheduleOpDispatcher {

    /**
     * "HH:mm" 或 "HH:mm:ss" 24h 格式校验。00:00 .. 23:59[:59]；不接受单位数 hour 如 "8:00"。
     * 可选秒精度。Server 端不强制 wall.precision——前端 UI 控制展示，server 两种格式都接受。
     */
    private static final Pattern HHMM_PATTERN =
            Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$");

    /** stationName / destination 字符上限。 */
    private static final int STR_MAX_LEN = 64;

    private final SessionManager sessionManager;
    private final SessionRateLimiter rateLimiter;
    private final ScheduleDao dao;
    private final @Nullable ManualScheduleProvider provider;
    private final ac.haru.hikaricanvas.storage.WallRepo wallRepo;
    private final ac.haru.hikaricanvas.storage.AuditLog auditLog;
    /** 主线程权限解析用宿主插件；可为 null（测试装配走直接调用）。 */
    private final org.bukkit.plugin.Plugin plugin;

    ScheduleOpDispatcher(SessionManager sessionManager,
                         SessionRateLimiter rateLimiter,
                         ScheduleDao dao,
                         @Nullable ManualScheduleProvider provider,
                         ac.haru.hikaricanvas.storage.WallRepo wallRepo,
                         ac.haru.hikaricanvas.storage.AuditLog auditLog,
                         org.bukkit.plugin.Plugin plugin) {
        this.sessionManager = sessionManager;
        this.rateLimiter = rateLimiter;
        this.dao = dao;
        this.provider = provider;
        this.wallRepo = wallRepo;
        this.auditLog = auditLog;
        this.plugin = plugin;
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
                    "schedule dao not configured"));
            return;
        }

        Map<String, Object> payload;
        try {
            payload = asPayloadMap(in.payload());
        } catch (IllegalArgumentException iae) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", iae.getMessage()));
            return;
        }

        String op = in.op();
        if (!checkPermission(ctx, in, sessionId, s, op)) return;

        String wallId = s.wallId();
        switch (op) {
            case "schedule.list" -> handleList(ctx, in, wallId);
            case "schedule.upsert" -> handleUpsert(ctx, in, sessionId, s, wallId, payload);
            case "schedule.entry.add" -> handleEntryAdd(ctx, in, sessionId, s, wallId, payload);
            case "schedule.entry.update" -> handleEntryUpdate(ctx, in, sessionId, s, wallId, payload);
            case "schedule.entry.delete" -> handleEntryDelete(ctx, in, sessionId, s, wallId, payload);
            default -> ctx.send(Envelope.error(in.id(), "INVALID_OP",
                    "unknown schedule op: " + op));
        }
    }

    // ──────────────────────────────────────────────────────────
    //  per-op handlers
    // ──────────────────────────────────────────────────────────

    private void handleList(WsMessageContext ctx, Envelope in, String wallId) {
        WallSchedule ws = dao.loadByWall(wallId).orElse(null);
        Map<String, Object> ackPayload = new LinkedHashMap<>();
        if (ws == null) {
            ackPayload.put("schedule", null);
        } else {
            ackPayload.put("schedule", scheduleToMap(ws));
        }
        ctx.send(Envelope.of("ack", in.id(), ackPayload));
    }

    private void handleUpsert(WsMessageContext ctx, Envelope in, String sessionId,
                              Session s, String wallId, Map<String, Object> payload) {
        Object rawName = payload.get("stationName");
        String stationName;
        if (rawName == null) {
            stationName = null;
        } else if (rawName instanceof String str) {
            String t = str.trim();
            if (t.isEmpty()) {
                stationName = null;
            } else if (t.length() > STR_MAX_LEN) {
                ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                        "stationName too long (max " + STR_MAX_LEN + ")"));
                return;
            } else {
                stationName = t;
            }
        } else {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                    "stationName must be string or null"));
            return;
        }
        // 可选 precision 字段（"minute" / "second"，默认 minute）
        Object rawPrecision = payload.get("precision");
        String precision = ac.haru.hikaricanvas.schedule.WallSchedule.PRECISION_MINUTE;
        if (rawPrecision instanceof String pstr) {
            precision = ac.haru.hikaricanvas.schedule.WallSchedule.normalizePrecision(pstr);
        } else if (rawPrecision != null) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                    "precision must be 'minute' or 'second' (string)"));
            return;
        } else {
            // 没传 precision → 保留现有值（如果有）；首次 upsert 走 minute 默认
            var existing = dao.loadByWall(wallId).orElse(null);
            if (existing != null) {
                precision = ac.haru.hikaricanvas.schedule.WallSchedule
                        .normalizePrecision(existing.precision());
            }
        }
        dao.upsertSchedule(wallId, stationName, precision);
        // upsert 不影响 schedule:* 变量值，但首次创建可能让 Provider 注册变量；
        // ensureWallRegistered 幂等。
        if (provider != null) provider.refreshWall(wallId);
        recordAudit("SCHEDULE_UPSERT", sessionId, s, wallId,
                Map.of("station_name", stationName == null ? "" : stationName,
                        "precision", precision));
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("stationName", stationName);
        ack.put("precision", precision);
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    private void handleEntryAdd(WsMessageContext ctx, Envelope in, String sessionId,
                                Session s, String wallId, Map<String, Object> payload) {
        String departureTime = stringOrNull(payload.get("departureTime"));
        if (departureTime == null || !HHMM_PATTERN.matcher(departureTime).matches()) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                    "departureTime must be HH:mm (24h)"));
            return;
        }
        DestResult destResult = sanitizeDestination(ctx, in, payload.get("destination"));
        if (destResult instanceof DestResult.Error) {
            // sanitize 已发 error
            return;
        }
        // Empty → 合法 null（纯空白也正常插入 destination=null + 回 ack，不再静默丢弃）
        String destination = destResult instanceof DestResult.Ok ok ? ok.value() : null;
        int sortOrder = intOrNullDefault(payload.get("sortOrder"), 0);

        // 业务侧确保 wall_schedules 元数据行存在（首次 add 时自动，默认 minute precision）
        WallSchedule existing = dao.loadByWall(wallId).orElse(null);
        if (existing == null) {
            dao.upsertSchedule(wallId, null,
                    ac.haru.hikaricanvas.schedule.WallSchedule.PRECISION_MINUTE);
        }

        long id = dao.insertEntry(wallId, departureTime, destination, sortOrder);
        if (id < 0) {
            ctx.send(Envelope.error(in.id(), "INTERNAL_ERROR",
                    "schedule.entry.add failed"));
            return;
        }
        // Provider 首次 register 该 wall + refresh 立即重算
        if (provider != null) provider.refreshWall(wallId);

        recordAudit("SCHEDULE_ENTRY_ADD", sessionId, s, wallId, Map.of(
                "id", id,
                "departure_time", departureTime,
                "destination", destination == null ? "" : destination,
                "sort_order", sortOrder));

        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("id", id);
        ack.put("wallId", wallId);
        ack.put("departureTime", departureTime);
        ack.put("destination", destination);
        ack.put("sortOrder", sortOrder);
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    private void handleEntryUpdate(WsMessageContext ctx, Envelope in, String sessionId,
                                   Session s, String wallId, Map<String, Object> payload) {
        Long id = longOrNull(payload.get("id"));
        if (id == null || id < 0) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "entry id missing or invalid"));
            return;
        }
        String departureTime = stringOrNull(payload.get("departureTime"));
        if (departureTime == null || !HHMM_PATTERN.matcher(departureTime).matches()) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                    "departureTime must be HH:mm (24h)"));
            return;
        }
        DestResult destResult = sanitizeDestination(ctx, in, payload.get("destination"));
        if (destResult instanceof DestResult.Error) {
            return;
        }
        // Empty → 合法 null（纯空白也正常更新 destination=null + 回 ack）
        String destination = destResult instanceof DestResult.Ok ok ? ok.value() : null;
        int sortOrder = intOrNullDefault(payload.get("sortOrder"), 0);

        // 跨 wall 拒：先按 id 拿 entry，校验 wallId 一致
        WallSchedule ws = dao.loadByWall(wallId).orElse(null);
        if (ws == null || !containsEntry(ws.entries(), id)) {
            ctx.send(Envelope.error(in.id(), "NOT_FOUND",
                    "entry " + id + " does not belong to this wall"));
            return;
        }

        int rows = dao.updateEntry(id, departureTime, destination, sortOrder);
        if (rows == 0) {
            ctx.send(Envelope.error(in.id(), "NOT_FOUND", "entry " + id + " not found"));
            return;
        }
        if (provider != null) provider.refreshWall(wallId);
        recordAudit("SCHEDULE_ENTRY_UPDATE", sessionId, s, wallId, Map.of(
                "id", id,
                "departure_time", departureTime,
                "destination", destination == null ? "" : destination,
                "sort_order", sortOrder));
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("id", id);
        ack.put("wallId", wallId);
        ack.put("departureTime", departureTime);
        ack.put("destination", destination);
        ack.put("sortOrder", sortOrder);
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    private void handleEntryDelete(WsMessageContext ctx, Envelope in, String sessionId,
                                   Session s, String wallId, Map<String, Object> payload) {
        Long id = longOrNull(payload.get("id"));
        if (id == null || id < 0) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "entry id missing or invalid"));
            return;
        }
        WallSchedule ws = dao.loadByWall(wallId).orElse(null);
        if (ws == null || !containsEntry(ws.entries(), id)) {
            ctx.send(Envelope.error(in.id(), "NOT_FOUND",
                    "entry " + id + " does not belong to this wall"));
            return;
        }
        int rows = dao.deleteEntry(id);
        if (rows == 0) {
            ctx.send(Envelope.error(in.id(), "NOT_FOUND", "entry " + id + " not found"));
            return;
        }
        if (provider != null) provider.refreshWall(wallId);
        recordAudit("SCHEDULE_ENTRY_DELETE", sessionId, s, wallId, Map.of("id", id));
        ctx.send(Envelope.of("ack", in.id(), Map.of("id", id)));
    }

    // ──────────────────────────────────────────────────────────
    //  权限
    // ──────────────────────────────────────────────────────────

    private boolean checkPermission(WsMessageContext ctx, Envelope in, String sessionId,
                                    Session s, String op) {
        UUID callerUuid = s.playerUuid();
        ac.haru.hikaricanvas.storage.WallRepo.Wall wall = wallRepo.loadById(s.wallId()).orElse(null);
        if (wall == null) {
            ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "wall not found"));
            return false;
        }
        boolean isOwnerOnly = wall.ownerUuid().equals(callerUuid);
        String requiredNode = isOwnerOnly ? "canvas.schedule.own" : "canvas.schedule.any";
        // 主线程解析权限（Bukkit.getPlayer + hasPermission 主线程专用）；离线 / 超时返 false。
        boolean granted = MainThreadPerms.hasPermission(plugin, callerUuid, requiredNode);
        // own 节点 default=true（与 var write own 同款）；offline 玩家 own 路径放行
        if (!granted && "canvas.schedule.own".equals(requiredNode)) {
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

    /** 转 WallSchedule → Map（Jackson 序列化 friendly）。 */
    private static Map<String, Object> scheduleToMap(WallSchedule ws) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("wallId", ws.wallId());
        if (ws.stationName() != null) m.put("stationName", ws.stationName());
        m.put("updatedAt", ws.updatedAt());
        // 携带 precision 字段（旧客户端忽略未知字段；新客户端用此切 UI）
        m.put("precision", WallSchedule.normalizePrecision(ws.precision()));
        List<Map<String, Object>> entries = new ArrayList<>(ws.entries().size());
        for (ScheduleEntry e : ws.entries()) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("id", e.id());
            em.put("departureTime", e.departureTime());
            if (e.destination() != null) em.put("destination", e.destination());
            em.put("sortOrder", e.sortOrder());
            entries.add(em);
        }
        m.put("entries", entries);
        return m;
    }

    private static boolean containsEntry(List<ScheduleEntry> entries, long id) {
        for (ScheduleEntry e : entries) {
            if (e.id() == id) return true;
        }
        return false;
    }

    /**
     * destination 校验三态结果。原先返单一 {@code null} 让调用方无法区分
     * 「合法空 destination（应正常插入 null）」与「类型/长度非法（已发 error 应 return）」，
     * 导致纯空白 destination 被守卫误判为已发 error → 既不写库也不回 ack/error 静默丢弃。
     */
    private sealed interface DestResult {
        /** 合法非空 destination。 */
        record Ok(String value) implements DestResult {}
        /** 合法空 destination（null / 纯空白）——应正常插入 destination=null 并回 ack。 */
        record Empty() implements DestResult {}
        /** 非法（类型错 / 超长）——error 已发给客户端，调用方直接 return。 */
        record Error() implements DestResult {}
    }

    /** 校验 destination：null / 空 → Empty；非空合法 → Ok(trim)；类型错 / 超长 → 发 error + 返 Error。 */
    private DestResult sanitizeDestination(WsMessageContext ctx, Envelope in, Object raw) {
        if (raw == null) return new DestResult.Empty();
        if (!(raw instanceof String str)) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                    "destination must be string or null"));
            return new DestResult.Error();
        }
        String t = str.trim();
        if (t.isEmpty()) return new DestResult.Empty();
        if (t.length() > STR_MAX_LEN) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                    "destination too long (max " + STR_MAX_LEN + ")"));
            return new DestResult.Error();
        }
        return new DestResult.Ok(t);
    }

    private static int intOrNullDefault(Object raw, int def) {
        Integer v = intOrNull(raw);
        return v == null ? def : v;
    }

    private static @Nullable Long longOrNull(Object raw) {
        if (raw instanceof Number n) return n.longValue();
        return null;
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
