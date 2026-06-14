package moe.hikari.canvas.web;

import io.javalin.websocket.WsMessageContext;
import moe.hikari.canvas.rail.AutoTimetableGenerator;
import moe.hikari.canvas.rail.RailLine;
import moe.hikari.canvas.rail.RailRun;
import moe.hikari.canvas.rail.RailStation;
import moe.hikari.canvas.rail.RailTimetableEntry;
import moe.hikari.canvas.rail.WallRailBinding;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.session.SessionRateLimiter;
import moe.hikari.canvas.storage.RailDao;
import moe.hikari.canvas.variable.provider.RailScheduleProvider;
import org.jetbrains.annotations.Nullable;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static moe.hikari.canvas.web.WebHelpers.asPayloadMap;
import static moe.hikari.canvas.web.WebHelpers.intOrNull;
import static moe.hikari.canvas.web.WebHelpers.mapOrEmpty;
import static moe.hikari.canvas.web.WebHelpers.stringOrNull;

/**
 * 0.4.4 P3：{@code rail.*} WS op 分发器（11 个 op）。详见 {@code docs/dynamic-data.md §18.7}。
 *
 * <h2>11 op</h2>
 * <ul>
 *   <li>{@code rail.line.create / .update / .delete}（3）</li>
 *   <li>{@code rail.station.add / .update / .delete}（3）</li>
 *   <li>{@code rail.run.create / .update / .delete}（3）</li>
 *   <li>{@code rail.run.timetable.set}（1）批量替换车次时刻表</li>
 *   <li>{@code rail.wall.bind}（1）wall → 线路 + 本站 + 方向</li>
 * </ul>
 *
 * <h2>权限</h2>
 * <ul>
 *   <li>{@code rail.line.create} → {@code canvas.rail.line.create}（default true）</li>
 *   <li>{@code rail.line.update / station.* / run.* / timetable.set} →
 *       caller 是 line owner 时 {@code canvas.rail.line.edit.own}（default true）；
 *       非 owner 走 {@code .edit.any}（default op）</li>
 *   <li>{@code rail.line.delete} →
 *       caller 是 line owner 时 {@code canvas.rail.line.delete.own}；
 *       非 owner 走 {@code .delete.any}</li>
 *   <li>{@code rail.wall.bind} → 同 schedule own/any（wall owner 判定）</li>
 * </ul>
 *
 * <p>同 {@link ScheduleOpDispatcher} 风格，不走 EditSession（rail 不影响 ProjectState
 * 像素）；ack payload 返新建 id / count；前端 mirror 自己更新本地 store。</p>
 */
final class RailOpDispatcher {

    /** HH:mm:ss 格式校验（rail timetable 精度到秒）；允许 HH:mm 兼容（自动补 :00）。 */
    private static final Pattern HHMMSS_PATTERN =
            Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$");
    private static final int STR_MAX_LEN = 64;
    private static final int NOTES_MAX_LEN = 256;

    private final SessionManager sessionManager;
    private final SessionRateLimiter rateLimiter;
    private final RailDao dao;
    private final @Nullable RailScheduleProvider provider;
    private final moe.hikari.canvas.storage.WallRepo wallRepo;
    private final moe.hikari.canvas.storage.AuditLog auditLog;
    /** P2-26：主线程权限解析用宿主插件；可为 null（测试装配走直接调用）。 */
    private final org.bukkit.plugin.Plugin plugin;

    RailOpDispatcher(SessionManager sessionManager,
                     SessionRateLimiter rateLimiter,
                     RailDao dao,
                     @Nullable RailScheduleProvider provider,
                     moe.hikari.canvas.storage.WallRepo wallRepo,
                     moe.hikari.canvas.storage.AuditLog auditLog,
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
        if (dao == null) {
            ctx.send(Envelope.error(in.id(), "INTERNAL_ERROR",
                    "rail dao not configured"));
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
        if (!checkPermission(ctx, in, sessionId, s, op, payload)) return;

        switch (op) {
            case "rail.line.list" -> handleLineList(ctx, in);
            case "rail.line.detail" -> handleLineDetail(ctx, in, payload);
            case "rail.line.create" -> handleLineCreate(ctx, in, sessionId, s, payload);
            case "rail.line.update" -> handleLineUpdate(ctx, in, sessionId, s, payload);
            case "rail.line.delete" -> handleLineDelete(ctx, in, sessionId, s, payload);
            case "rail.station.add" -> handleStationAdd(ctx, in, sessionId, s, payload);
            case "rail.station.update" -> handleStationUpdate(ctx, in, sessionId, s, payload);
            case "rail.station.delete" -> handleStationDelete(ctx, in, sessionId, s, payload);
            case "rail.run.create" -> handleRunCreate(ctx, in, sessionId, s, payload);
            case "rail.run.update" -> handleRunUpdate(ctx, in, sessionId, s, payload);
            case "rail.run.delete" -> handleRunDelete(ctx, in, sessionId, s, payload);
            case "rail.run.timetable.set" -> handleTimetableSet(ctx, in, sessionId, s, payload);
            case "rail.wall.bind" -> handleWallBind(ctx, in, sessionId, s, payload);
            default -> ctx.send(Envelope.error(in.id(), "INVALID_OP", "unknown rail op: " + op));
        }
    }

    // ────────────────────────────────────────────────────────────
    //  rail.line.* handlers
    // ────────────────────────────────────────────────────────────

    private void handleLineList(WsMessageContext ctx, Envelope in) {
        List<RailLine> lines = dao.listAllLines();
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("lines", lines.stream().map(RailOpDispatcher::lineToMap).toList());
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    /**
     * 0.4.5 P1：聚合视图 — 一次返该线路的 stations + runs + 各 run 的 timetable。
     * 前端 RailNetworkModal.selectLine 调用避免 N+1 请求。
     */
    private void handleLineDetail(WsMessageContext ctx, Envelope in,
                                  Map<String, Object> payload) {
        String lineId = stringOrNull(payload.get("lineId"));
        if (lineId == null) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "lineId required"));
            return;
        }
        RailLine line = dao.findLine(lineId).orElse(null);
        if (line == null) {
            ctx.send(Envelope.error(in.id(), "NOT_FOUND", "line not found"));
            return;
        }
        var detail = dao.loadLineDetail(lineId);
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("line", lineToMap(line));
        ack.put("stations", detail.stations().stream()
                .map(RailOpDispatcher::stationToMap).toList());
        ack.put("runs", detail.runs().stream()
                .map(RailOpDispatcher::runToMap).toList());
        // timetableByRun: { runId: [{ stationId, arrival, departure, stopsHere }] }
        Map<String, Object> ttMap = new LinkedHashMap<>();
        for (var entry : detail.timetableByRun().entrySet()) {
            List<Map<String, Object>> rows = new java.util.ArrayList<>(entry.getValue().size());
            for (RailTimetableEntry te : entry.getValue()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("stationId", te.stationId());
                if (te.arrivalTime() != null) row.put("arrival", te.arrivalTime());
                if (te.departureTime() != null) row.put("departure", te.departureTime());
                row.put("stopsHere", te.stopsHere());
                rows.add(row);
            }
            ttMap.put(entry.getKey(), rows);
        }
        ack.put("timetableByRun", ttMap);
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    private void handleLineCreate(WsMessageContext ctx, Envelope in, String sessionId,
                                  Session s, Map<String, Object> payload) {
        String name = trimOrNull(stringOrNull(payload.get("name")));
        if (name == null || name.isEmpty() || name.length() > STR_MAX_LEN) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "name required (≤64)"));
            return;
        }
        String code = trimOrNull(stringOrNull(payload.get("code")));
        String color = trimOrNull(stringOrNull(payload.get("color")));
        long now = System.currentTimeMillis();
        // P3-6：do-while 唯一 id（冲突时追加 random 后缀并再次校验，根除同毫秒碰撞）
        String id = generateUniqueId("line-", cand -> dao.findLine(cand).isPresent());
        UUID owner = s.playerUuid();
        if (owner == null) {
            ctx.send(Envelope.error(in.id(), "FORBIDDEN", "caller uuid required"));
            return;
        }
        String ownerName = s.playerName() == null ? owner.toString() : s.playerName();
        RailLine line = new RailLine(id, name, code, color, owner, ownerName, now, now);
        dao.upsertLine(line);
        audit(sessionId, s, "RAIL_LINE_CREATE",
                Map.of("line_id", id, "name", name));
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("lineId", id);
        ack.put("line", lineToMap(line));
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    private void handleLineUpdate(WsMessageContext ctx, Envelope in, String sessionId,
                                  Session s, Map<String, Object> payload) {
        String id = stringOrNull(payload.get("lineId"));
        RailLine existing = dao.findLine(id).orElse(null);
        if (existing == null) {
            ctx.send(Envelope.error(in.id(), "NOT_FOUND", "line not found"));
            return;
        }
        String name = trimOrNull(stringOrNull(payload.get("name")));
        String code = trimOrNull(stringOrNull(payload.get("code")));
        String color = trimOrNull(stringOrNull(payload.get("color")));
        RailLine updated = new RailLine(
                existing.id(),
                name == null ? existing.name() : name,
                code == null ? existing.code() : code,
                color == null ? existing.color() : color,
                existing.ownerUuid(), existing.ownerName(),
                existing.createdAt(), System.currentTimeMillis());
        dao.upsertLine(updated);
        audit(sessionId, s, "RAIL_LINE_UPDATE", Map.of("line_id", id));
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("line", lineToMap(updated));
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    private void handleLineDelete(WsMessageContext ctx, Envelope in, String sessionId,
                                  Session s, Map<String, Object> payload) {
        String id = stringOrNull(payload.get("lineId"));
        // 0.4.10 P2-5：删线路前先收集绑定到该线路的 wall（FK CASCADE 会把 line_id SET NULL，
        // 删后无法再反查），删后立即 unregister 让 RailScheduleProvider 释放接管、ManualSchedule
        // 接手——否则 provider.refresh() 永不重读 binding，wall 永久卡在 rail 接管态显示旧值。
        List<String> boundWalls = new ArrayList<>();
        if (id != null) {
            for (WallRailBinding b : dao.listAllBindings()) {
                if (id.equals(b.lineId())) boundWalls.add(b.wallId());
            }
        }
        int n = dao.deleteLine(id);
        if (provider != null) {
            for (String wid : boundWalls) provider.unregisterWall(wid);
        }
        audit(sessionId, s, "RAIL_LINE_DELETE",
                Map.of("line_id", id == null ? "" : id, "rows", n));
        // FK CASCADE 会自动清 stations / runs / timetable；wall_rail_bindings.line_id SET NULL
        // 这些 wall 走 fallback（已 unregister，下次 ManualSchedule 接管）
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("deleted", n);
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    // ────────────────────────────────────────────────────────────
    //  rail.station.* handlers
    // ────────────────────────────────────────────────────────────

    private void handleStationAdd(WsMessageContext ctx, Envelope in, String sessionId,
                                  Session s, Map<String, Object> payload) {
        String lineId = stringOrNull(payload.get("lineId"));
        if (dao.findLine(lineId).isEmpty()) {
            ctx.send(Envelope.error(in.id(), "NOT_FOUND", "line not found"));
            return;
        }
        String name = trimOrNull(stringOrNull(payload.get("name")));
        if (name == null || name.isEmpty() || name.length() > STR_MAX_LEN) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "name required (≤64)"));
            return;
        }
        String code = trimOrNull(stringOrNull(payload.get("code")));
        Integer sortOrder = intOrNull(payload.get("sortOrder"));
        if (sortOrder == null) {
            // 自动取末尾
            sortOrder = dao.listStationsByLine(lineId).size();
        }
        boolean terminus = Boolean.TRUE.equals(payload.get("isTerminus"));
        long now = System.currentTimeMillis();
        // P3-6：do-while 唯一 id（冲突时追加 random 后缀并再次校验）
        String id = generateUniqueId("stn-", cand -> dao.findStation(cand).isPresent());
        RailStation st = new RailStation(id, lineId, name, code, sortOrder, terminus, now);
        dao.upsertStation(st);
        audit(sessionId, s, "RAIL_STATION_ADD",
                Map.of("line_id", lineId, "station_id", id, "name", name));
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("stationId", id);
        ack.put("station", stationToMap(st));
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    private void handleStationUpdate(WsMessageContext ctx, Envelope in, String sessionId,
                                     Session s, Map<String, Object> payload) {
        String id = stringOrNull(payload.get("stationId"));
        RailStation existing = dao.findStation(id).orElse(null);
        if (existing == null) {
            ctx.send(Envelope.error(in.id(), "NOT_FOUND", "station not found"));
            return;
        }
        String name = trimOrNull(stringOrNull(payload.get("name")));
        String code = trimOrNull(stringOrNull(payload.get("code")));
        Integer sortOrder = intOrNull(payload.get("sortOrder"));
        Object termObj = payload.get("isTerminus");
        RailStation updated = new RailStation(
                existing.id(), existing.lineId(),
                name == null ? existing.name() : name,
                code == null ? existing.code() : code,
                sortOrder == null ? existing.sortOrder() : sortOrder,
                termObj == null ? existing.isTerminus() : Boolean.TRUE.equals(termObj),
                existing.createdAt());
        dao.upsertStation(updated);
        audit(sessionId, s, "RAIL_STATION_UPDATE", Map.of("station_id", id));
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("station", stationToMap(updated));
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    private void handleStationDelete(WsMessageContext ctx, Envelope in, String sessionId,
                                     Session s, Map<String, Object> payload) {
        String id = stringOrNull(payload.get("stationId"));
        // 0.7.3 P2-14：删站前先收集"本站"绑定到该 station 的 wall。FK 是
        // ON DELETE SET NULL —— 删后 wall_rail_bindings.station_id 变 null，无法再反查；
        // 删后对每个绑定 wall 调 provider.unregisterWall 让 RailScheduleProvider 释放接管，
        // 否则它持旧 stationId 快照 + refresh() 永查空 → push 空串覆盖 schedule:* +
        // hasWallBinding 仍 true 让 ManualSchedule 持续跳过 → 该 wall 屏整个运行期空白到重启。
        // 与 handleLineDelete 同款。
        List<String> boundWalls = new ArrayList<>();
        if (id != null) {
            for (WallRailBinding b : dao.listAllBindings()) {
                if (id.equals(b.stationId())) boundWalls.add(b.wallId());
            }
        }
        int n = dao.deleteStation(id);
        if (provider != null) {
            for (String wid : boundWalls) provider.unregisterWall(wid);
        }
        audit(sessionId, s, "RAIL_STATION_DELETE",
                Map.of("station_id", id == null ? "" : id, "rows", n));
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("deleted", n);
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    // ────────────────────────────────────────────────────────────
    //  rail.run.* handlers
    // ────────────────────────────────────────────────────────────

    private void handleRunCreate(WsMessageContext ctx, Envelope in, String sessionId,
                                 Session s, Map<String, Object> payload) {
        String lineId = stringOrNull(payload.get("lineId"));
        if (dao.findLine(lineId).isEmpty()) {
            ctx.send(Envelope.error(in.id(), "NOT_FOUND", "line not found"));
            return;
        }
        String runNumber = trimOrNull(stringOrNull(payload.get("runNumber")));
        String direction = stringOrNull(payload.get("direction"));
        String serviceType = trimOrNull(stringOrNull(payload.get("serviceType")));
        Integer cars = intOrNull(payload.get("cars"));
        String startId = stringOrNull(payload.get("startStationId"));
        String endId = stringOrNull(payload.get("endStationId"));
        String notes = trimOrNull(stringOrNull(payload.get("notes")));

        if (runNumber == null || runNumber.isEmpty() || runNumber.length() > STR_MAX_LEN) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "runNumber required (≤64)"));
            return;
        }
        if (!RailRun.DIRECTION_UP.equals(direction) && !RailRun.DIRECTION_DOWN.equals(direction)) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                    "direction must be up / down"));
            return;
        }
        if (serviceType == null || serviceType.isEmpty()) serviceType = "local";
        if (notes != null && notes.length() > NOTES_MAX_LEN) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                    "notes too long (≤256)"));
            return;
        }
        // 0.4.10 P3-47：编组数范围校验（可选字段；提供时须 1..32，防退化/超长编组）
        if (cars != null && (cars < 1 || cars > 32)) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "cars must be 1..32"));
            return;
        }
        long now = System.currentTimeMillis();
        // P3-6：do-while 唯一 id（冲突时追加 random 后缀并再次校验）
        String id = generateUniqueId("run-", cand -> dao.findRun(cand).isPresent());

        // 0.4.10 P2-7/P2-64：先生成+校验 timetable（generateAutoTimetable 可能抛
        // IllegalArgumentException），再写库——避免校验失败时 run 行已 upsert 留下孤儿。
        List<RailTimetableEntry> autoEntries = null;
        Object genOpts = payload.get("generateOptions");
        if (genOpts instanceof Map<?, ?> opts) {
            try {
                autoEntries = generateAutoTimetable(id, lineId, direction, startId, endId, opts);
            } catch (IllegalArgumentException iae) {
                ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                        "generateOptions invalid: " + iae.getMessage()));
                return;
            }
        }
        RailRun run = new RailRun(id, lineId, runNumber, direction, serviceType,
                cars, startId, endId, notes, now, now);
        dao.upsertRun(run);
        if (autoEntries != null && !autoEntries.isEmpty()) {
            dao.replaceTimetable(id, autoEntries);
        }
        audit(sessionId, s, "RAIL_RUN_CREATE",
                Map.of("line_id", lineId, "run_id", id, "run_number", runNumber));
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("runId", id);
        ack.put("run", runToMap(run));
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    private List<RailTimetableEntry> generateAutoTimetable(String runId, String lineId,
                                                           String direction,
                                                           @Nullable String startId,
                                                           @Nullable String endId,
                                                           Map<?, ?> opts) {
        String first = stringOrNull(opts.get("firstDeparture"));
        Integer travel = intOrNull(opts.get("travelSeconds"));
        Integer dwell = intOrNull(opts.get("dwellSeconds"));
        if (first == null || !HHMMSS_PATTERN.matcher(first).matches()) {
            throw new IllegalArgumentException("firstDeparture required HH:mm[:ss]");
        }
        if (travel == null || travel < 0) {
            throw new IllegalArgumentException("travelSeconds required");
        }
        if (dwell == null) dwell = 30;
        if (dwell < 0) dwell = 0;
        LocalTime firstT;
        try {
            // 兼容 HH:mm → 补 :00
            firstT = LocalTime.parse(first.length() == 5 ? first + ":00" : first);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("firstDeparture parse failed");
        }
        Set<String> skips = new HashSet<>();
        Object skipsObj = opts.get("skipStationIds");
        if (skipsObj instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) skips.add(s);
            }
        }
        List<RailStation> stations = dao.listStationsByLine(lineId);
        if (RailRun.DIRECTION_DOWN.equals(direction)) {
            // down 方向反转
            List<RailStation> reversed = new ArrayList<>(stations);
            java.util.Collections.reverse(reversed);
            stations = reversed;
        }
        return AutoTimetableGenerator.generate(runId, stations,
                new AutoTimetableGenerator.GenerateOptions(
                        firstT, travel, dwell, skips, startId, endId));
    }

    private void handleRunUpdate(WsMessageContext ctx, Envelope in, String sessionId,
                                 Session s, Map<String, Object> payload) {
        String id = stringOrNull(payload.get("runId"));
        RailRun existing = dao.findRun(id).orElse(null);
        if (existing == null) {
            ctx.send(Envelope.error(in.id(), "NOT_FOUND", "run not found"));
            return;
        }
        String runNumber = trimOrNull(stringOrNull(payload.get("runNumber")));
        String direction = stringOrNull(payload.get("direction"));
        String serviceType = trimOrNull(stringOrNull(payload.get("serviceType")));
        Integer cars = intOrNull(payload.get("cars"));
        Object startObj = payload.get("startStationId");  // 允许 null = 清空区间起点
        Object endObj = payload.get("endStationId");
        String notes = trimOrNull(stringOrNull(payload.get("notes")));
        if (notes != null && notes.length() > NOTES_MAX_LEN) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                    "notes too long (≤256)"));
            return;
        }
        // 0.4.10 P3-47：编组数范围校验（提供时须 1..32）
        if (cars != null && (cars < 1 || cars > 32)) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "cars must be 1..32"));
            return;
        }
        RailRun updated = new RailRun(
                existing.id(), existing.lineId(),
                runNumber == null ? existing.runNumber() : runNumber,
                direction == null ? existing.direction() : direction,
                serviceType == null ? existing.serviceType() : serviceType,
                cars == null ? existing.cars() : cars,
                payload.containsKey("startStationId")
                        ? (startObj instanceof String stStr ? stStr : null) : existing.startStationId(),
                payload.containsKey("endStationId")
                        ? (endObj instanceof String enStr ? enStr : null) : existing.endStationId(),
                notes == null ? existing.notes() : notes,
                existing.createdAt(), System.currentTimeMillis());
        dao.upsertRun(updated);
        audit(sessionId, s, "RAIL_RUN_UPDATE", Map.of("run_id", id));
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("run", runToMap(updated));
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    private void handleRunDelete(WsMessageContext ctx, Envelope in, String sessionId,
                                 Session s, Map<String, Object> payload) {
        String id = stringOrNull(payload.get("runId"));
        int n = dao.deleteRun(id);
        audit(sessionId, s, "RAIL_RUN_DELETE",
                Map.of("run_id", id == null ? "" : id, "rows", n));
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("deleted", n);
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    private void handleTimetableSet(WsMessageContext ctx, Envelope in, String sessionId,
                                    Session s, Map<String, Object> payload) {
        String runId = stringOrNull(payload.get("runId"));
        if (dao.findRun(runId).isEmpty()) {
            ctx.send(Envelope.error(in.id(), "NOT_FOUND", "run not found"));
            return;
        }
        Object entriesObj = payload.get("entries");
        if (!(entriesObj instanceof List<?> list)) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "entries must be list"));
            return;
        }
        List<RailTimetableEntry> parsed = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> row)) continue;
            String stationId = stringOrNull(row.get("stationId"));
            if (stationId == null) continue;
            String arrival = trimOrNull(stringOrNull(row.get("arrival")));
            String departure = trimOrNull(stringOrNull(row.get("departure")));
            if (arrival != null && !HHMMSS_PATTERN.matcher(arrival).matches()) {
                ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                        "arrival invalid HH:mm[:ss]: " + arrival));
                return;
            }
            if (departure != null && !HHMMSS_PATTERN.matcher(departure).matches()) {
                ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD",
                        "departure invalid HH:mm[:ss]: " + departure));
                return;
            }
            boolean stops = !Boolean.FALSE.equals(row.get("stopsHere"));
            parsed.add(new RailTimetableEntry(runId, stationId,
                    arrival == null ? null : padSec(arrival),
                    departure == null ? null : padSec(departure),
                    stops));
        }
        dao.replaceTimetable(runId, parsed);
        audit(sessionId, s, "RAIL_TIMETABLE_SET",
                Map.of("run_id", runId, "rows", parsed.size()));
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("rows", parsed.size());
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    /** HH:mm → HH:mm:00（schema 字段保持秒精度规范）。 */
    private static String padSec(String hhmm) {
        return hhmm.length() == 5 ? hhmm + ":00" : hhmm;
    }

    // ────────────────────────────────────────────────────────────
    //  rail.wall.bind
    // ────────────────────────────────────────────────────────────

    private void handleWallBind(WsMessageContext ctx, Envelope in, String sessionId,
                                Session s, Map<String, Object> payload) {
        String wallId = stringOrNull(payload.get("wallId"));
        if (wallId == null) wallId = s.wallId();  // 默认绑定当前 wall
        if (wallId == null) {
            ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "wallId required"));
            return;
        }
        String lineId = stringOrNull(payload.get("lineId"));
        String stationId = stringOrNull(payload.get("stationId"));
        String direction = WallRailBinding.normalizeDirection(
                stringOrNull(payload.get("direction")));
        long now = System.currentTimeMillis();
        WallRailBinding b = new WallRailBinding(wallId, lineId, stationId, direction, now);
        dao.upsertBinding(b);
        // 即时生效：注册到 RailScheduleProvider
        if (provider != null) provider.registerWallByBinding(wallId, b);
        audit(sessionId, s, "RAIL_WALL_BIND",
                Map.of("wall_id", wallId,
                        "line_id", lineId == null ? "" : lineId,
                        "station_id", stationId == null ? "" : stationId,
                        "direction", direction));
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("binding", bindingToMap(b));
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    // ────────────────────────────────────────────────────────────
    //  权限
    // ────────────────────────────────────────────────────────────

    private boolean checkPermission(WsMessageContext ctx, Envelope in, String sessionId,
                                    Session s, String op, Map<String, Object> payload) {
        UUID callerUuid = s.playerUuid();

        // 1. rail.line.list / detail 完全开放（只读）
        if ("rail.line.list".equals(op) || "rail.line.detail".equals(op)) return true;
        // 2. rail.line.create → .create
        if ("rail.line.create".equals(op)) {
            return ensurePerm(ctx, in, sessionId, s, callerUuid, "canvas.rail.line.create", true);
        }
        // 3. rail.wall.bind → 复用 schedule.own/any（wall owner 判定）
        if ("rail.wall.bind".equals(op)) {
            String wallId = stringOrNull(payload.get("wallId"));
            if (wallId == null) wallId = s.wallId();
            moe.hikari.canvas.storage.WallRepo.Wall wall = wallId == null
                    ? null : wallRepo.loadById(wallId).orElse(null);
            boolean isOwner = wall != null && wall.ownerUuid().equals(callerUuid);
            String node = isOwner ? "canvas.rail.wall.bind" : "canvas.schedule.any";
            return ensurePerm(ctx, in, sessionId, s, callerUuid, node, isOwner);
        }
        // 4. 其他改类 op → 走 line owner 判定
        String lineId = resolveLineId(op, payload);
        RailLine line = lineId == null ? null : dao.findLine(lineId).orElse(null);
        boolean isOwner = line != null && line.ownerUuid().equals(callerUuid);
        boolean isDelete = "rail.line.delete".equals(op);
        String node;
        if (isDelete) {
            node = isOwner ? "canvas.rail.line.delete.own" : "canvas.rail.line.delete.any";
        } else {
            node = isOwner ? "canvas.rail.line.edit.own" : "canvas.rail.line.edit.any";
        }
        return ensurePerm(ctx, in, sessionId, s, callerUuid, node, isOwner);
    }

    private boolean ensurePerm(WsMessageContext ctx, Envelope in, String sessionId,
                               Session s, UUID callerUuid, String node,
                               boolean defaultTrue) {
        // P2-26：主线程解析权限（Bukkit.getPlayer + hasPermission 主线程专用）；离线 / 超时返 false。
        boolean granted = MainThreadPerms.hasPermission(plugin, callerUuid, node);
        if (!granted && defaultTrue) {
            // own / create 节点 default=true：offline 玩家也通行
            granted = true;
        }
        if (granted) return true;
        // 拒
        if (auditLog != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("operation", in.op());
            details.put("required", node);
            details.put("scope", "rail");
            auditLog.record("PERMISSION_DENIED",
                    s.playerUuid() == null ? null : s.playerUuid().toString(),
                    s.playerName(), sessionId, null, details);
        }
        ctx.send(Envelope.error(in.id(), "PERMISSION_DENIED",
                "missing permission: " + node));
        return false;
    }

    /**
     * 从 op + payload 提取作用线路 id（用于 ACL line owner 判定）。
     * 大多数 op 含 lineId 字段；rail.station.* 走 stationId → 反查；run.* 走 runId → 反查；
     * timetable.set 走 runId → 反查。
     */
    @Nullable
    private String resolveLineId(String op, Map<String, Object> payload) {
        return switch (op) {
            case "rail.line.update", "rail.line.delete" ->
                    stringOrNull(payload.get("lineId"));
            case "rail.station.add", "rail.run.create" ->
                    stringOrNull(payload.get("lineId"));
            case "rail.station.update", "rail.station.delete" -> {
                String sid = stringOrNull(payload.get("stationId"));
                yield sid == null ? null
                        : dao.findStation(sid).map(RailStation::lineId).orElse(null);
            }
            case "rail.run.update", "rail.run.delete", "rail.run.timetable.set" -> {
                String rid = stringOrNull(payload.get("runId"));
                yield rid == null ? null
                        : dao.findRun(rid).map(RailRun::lineId).orElse(null);
            }
            default -> null;
        };
    }

    // ────────────────────────────────────────────────────────────
    //  helpers
    // ────────────────────────────────────────────────────────────

    private void audit(String sessionId, Session s, String event, Map<String, Object> details) {
        if (auditLog == null) return;
        auditLog.record(event,
                s.playerUuid() == null ? null : s.playerUuid().toString(),
                s.playerName(), sessionId, null, details);
    }

    @Nullable
    private static String trimOrNull(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * P3-6：生成唯一 id（line/station/run 共用）。基础形态 = {@code prefix + 时间戳低 8 位 hex}；
     * 若 {@code exists} 命中冲突则追加 random 后缀并 <b>再次校验</b>，循环到无冲突。
     *
     * <p>修复点：原代码冲突时只做单次 random 后缀且不二次 findX 校验——同毫秒并发 create 或
     * 第二次仍撞 random 值时 id 可能与既有行相同，触发 upsert {@code ON CONFLICT(id) DO UPDATE}
     * 静默覆盖既有行（数据丢失）。do-while 每次重生成后都 re-check，从根消除残留碰撞窗口。</p>
     *
     * <p>非安全敏感（仅做唯一性，非凭证），用 {@link Math#random()} 足够；64 次硬上限兜底防
     * exists 实现异常导致死循环（实际首次或一次 random 即命中）。</p>
     */
    private static String generateUniqueId(String prefix, java.util.function.Predicate<String> exists) {
        String hex = Long.toHexString(System.currentTimeMillis());
        String base = prefix + hex.substring(Math.max(0, hex.length() - 8));
        String id = base;
        int attempts = 0;
        while (exists.test(id)) {
            id = base + "-" + Integer.toHexString((int) (Math.random() * 0xFFFF));
            if (++attempts >= 64) {
                // 极端兜底：拼一个长随机串几乎不可能再撞（亦防 exists 误判死循环）
                id = base + "-" + Long.toHexString(Double.doubleToLongBits(Math.random()));
                break;
            }
        }
        return id;
    }

    private static Map<String, Object> lineToMap(RailLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.id());
        m.put("name", l.name());
        if (l.code() != null) m.put("code", l.code());
        if (l.color() != null) m.put("color", l.color());
        m.put("ownerUuid", l.ownerUuid().toString());
        m.put("ownerName", l.ownerName());
        m.put("createdAt", l.createdAt());
        m.put("updatedAt", l.updatedAt());
        return m;
    }

    private static Map<String, Object> stationToMap(RailStation s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("lineId", s.lineId());
        m.put("name", s.name());
        if (s.code() != null) m.put("code", s.code());
        m.put("sortOrder", s.sortOrder());
        m.put("isTerminus", s.isTerminus());
        m.put("createdAt", s.createdAt());
        return m;
    }

    private static Map<String, Object> runToMap(RailRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("lineId", r.lineId());
        m.put("runNumber", r.runNumber());
        m.put("direction", r.direction());
        m.put("serviceType", r.serviceType());
        if (r.cars() != null) m.put("cars", r.cars());
        if (r.startStationId() != null) m.put("startStationId", r.startStationId());
        if (r.endStationId() != null) m.put("endStationId", r.endStationId());
        if (r.notes() != null) m.put("notes", r.notes());
        m.put("createdAt", r.createdAt());
        m.put("updatedAt", r.updatedAt());
        return m;
    }

    /**
     * 0.4.5 P3：包外（WebServer）查 wall 当前 binding。dispatcher 内部持有 dao，
     * 包外不直接拿；走静态 helper 转发 dao.findBinding。
     */
    static @Nullable WallRailBinding lookupBinding(RailOpDispatcher self, String wallId) {
        if (self == null || self.dao == null || wallId == null) return null;
        return self.dao.findBinding(wallId).orElse(null);
    }

    private static Map<String, Object> bindingToMap(WallRailBinding b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("wallId", b.wallId());
        if (b.lineId() != null) m.put("lineId", b.lineId());
        if (b.stationId() != null) m.put("stationId", b.stationId());
        if (b.direction() != null) m.put("direction", b.direction());
        m.put("updatedAt", b.updatedAt());
        return m;
    }
}
