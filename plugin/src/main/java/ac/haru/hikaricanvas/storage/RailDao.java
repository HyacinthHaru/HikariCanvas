package ac.haru.hikaricanvas.storage;

import ac.haru.hikaricanvas.rail.RailLine;
import ac.haru.hikaricanvas.rail.RailRun;
import ac.haru.hikaricanvas.rail.RailStation;
import ac.haru.hikaricanvas.rail.RailTimetableEntry;
import ac.haru.hikaricanvas.rail.WallRailBinding;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 铁路网络 5 表统一 DAO（与 {@link ScheduleDao} 单类风格一致）。
 *
 * <p>承担：{@code rail_lines} / {@code rail_stations} / {@code rail_runs} /
 * {@code rail_timetable} / {@code wall_rail_bindings} 五表的 CRUD。FK 级联由 schema 自带
 * （V016 migration），DAO 不重复触发显式 DELETE。</p>
 *
 * <p>所有方法异常被 catch + log，<b>不上抛</b>——保持与 ScheduleDao 风格一致。但吞掉的异常
 * 必须让业务层看得见结果：读方法返 Optional / List，删方法返受影响行数，<b>写方法
 * （upsert* / replaceTimetable）返 boolean</b>，{@code false} = 这次没写进去（外键约束、
 * UNIQUE 冲突、DB 故障……）。业务层（{@code RailOpDispatcher}）据此回 error 而不是照样 ack
 * 成功——写方法曾经一律 void，绑定失败也回 ack，站牌空白到重启且查不出原因。</p>
 */
public class RailDao {

    private final Logger log;
    private final Jdbi jdbi;

    public RailDao(Logger log, Jdbi jdbi) {
        this.log = log;
        this.jdbi = jdbi;
    }

    // ────────────────────────────────────────────────────────────
    //  rail_lines
    // ────────────────────────────────────────────────────────────

    /** @return 是否写入成功（false = 约束冲突 / DB 故障，已记 WARNING） */
    public boolean upsertLine(RailLine line) {
        try {
            jdbi.useHandle(h -> h.createUpdate(
                    "INSERT INTO rail_lines("
                            + "id, name, code, color, owner_uuid, owner_name, "
                            + "created_at, updated_at) "
                            + "VALUES(:id, :name, :code, :color, :owner, :ownerName, :created, :updated) "
                            + "ON CONFLICT(id) DO UPDATE SET "
                            + "name = excluded.name, "
                            + "code = excluded.code, "
                            + "color = excluded.color, "
                            + "updated_at = excluded.updated_at")
                    .bind("id", line.id())
                    .bind("name", line.name())
                    .bind("code", line.code())
                    .bind("color", line.color())
                    .bind("owner", line.ownerUuid().toString())
                    .bind("ownerName", line.ownerName())
                    .bind("created", line.createdAt())
                    .bind("updated", line.updatedAt())
                    .execute());
            return true;
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.upsertLine failed: " + line.id(), e);
            return false;
        }
    }

    public Optional<RailLine> findLine(String id) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM rail_lines WHERE id = :id")
                    .bind("id", id)
                    .map((rs, ctx) -> mapLine(rs))
                    .findOne());
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.findLine failed: " + id, e);
            return Optional.empty();
        }
    }

    public List<RailLine> listAllLines() {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM rail_lines ORDER BY name")
                    .map((rs, ctx) -> mapLine(rs))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.listAllLines failed", e);
            return List.of();
        }
    }

    public int deleteLine(String id) {
        try {
            return jdbi.withHandle(h -> h.createUpdate(
                    "DELETE FROM rail_lines WHERE id = :id")
                    .bind("id", id)
                    .execute());
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.deleteLine failed: " + id, e);
            return 0;
        }
    }

    // ────────────────────────────────────────────────────────────
    //  rail_stations
    // ────────────────────────────────────────────────────────────

    /** @return 是否写入成功（false = 线路不存在等外键冲突 / DB 故障，已记 WARNING） */
    public boolean upsertStation(RailStation s) {
        try {
            jdbi.useHandle(h -> h.createUpdate(
                    "INSERT INTO rail_stations("
                            + "id, line_id, name, code, sort_order, is_terminus, created_at) "
                            + "VALUES(:id, :line, :name, :code, :sort, :term, :created) "
                            + "ON CONFLICT(id) DO UPDATE SET "
                            + "name = excluded.name, "
                            + "code = excluded.code, "
                            + "sort_order = excluded.sort_order, "
                            + "is_terminus = excluded.is_terminus")
                    .bind("id", s.id())
                    .bind("line", s.lineId())
                    .bind("name", s.name())
                    .bind("code", s.code())
                    .bind("sort", s.sortOrder())
                    .bind("term", s.isTerminus() ? 1 : 0)
                    .bind("created", s.createdAt())
                    .execute());
            return true;
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.upsertStation failed: " + s.id(), e);
            return false;
        }
    }

    public Optional<RailStation> findStation(String id) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM rail_stations WHERE id = :id")
                    .bind("id", id)
                    .map((rs, ctx) -> mapStation(rs))
                    .findOne());
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.findStation failed: " + id, e);
            return Optional.empty();
        }
    }

    public List<RailStation> listStationsByLine(String lineId) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM rail_stations WHERE line_id = :line "
                            + "ORDER BY sort_order ASC, name ASC")
                    .bind("line", lineId)
                    .map((rs, ctx) -> mapStation(rs))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.listStationsByLine failed: " + lineId, e);
            return List.of();
        }
    }

    public int deleteStation(String id) {
        try {
            return jdbi.withHandle(h -> h.createUpdate(
                    "DELETE FROM rail_stations WHERE id = :id")
                    .bind("id", id)
                    .execute());
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.deleteStation failed: " + id, e);
            return 0;
        }
    }

    // ────────────────────────────────────────────────────────────
    //  rail_runs
    // ────────────────────────────────────────────────────────────

    /**
     * @return 是否写入成功。{@code false} 常见于 {@code UNIQUE(line_id, run_number)} 撞车次号
     *         或线路 / 起终点站不存在（外键），也可能是 DB 故障；均已记 WARNING
     */
    public boolean upsertRun(RailRun r) {
        try {
            jdbi.useHandle(h -> h.createUpdate(
                    "INSERT INTO rail_runs("
                            + "id, line_id, run_number, direction, service_type, cars, "
                            + "start_station_id, end_station_id, notes, created_at, updated_at) "
                            + "VALUES(:id, :line, :run, :dir, :type, :cars, :start, :end, :notes, "
                            + ":created, :updated) "
                            + "ON CONFLICT(id) DO UPDATE SET "
                            + "run_number = excluded.run_number, "
                            + "direction = excluded.direction, "
                            + "service_type = excluded.service_type, "
                            + "cars = excluded.cars, "
                            + "start_station_id = excluded.start_station_id, "
                            + "end_station_id = excluded.end_station_id, "
                            + "notes = excluded.notes, "
                            + "updated_at = excluded.updated_at")
                    .bind("id", r.id())
                    .bind("line", r.lineId())
                    .bind("run", r.runNumber())
                    .bind("dir", r.direction())
                    .bind("type", r.serviceType())
                    .bind("cars", r.cars())
                    .bind("start", r.startStationId())
                    .bind("end", r.endStationId())
                    .bind("notes", r.notes())
                    .bind("created", r.createdAt())
                    .bind("updated", r.updatedAt())
                    .execute());
            return true;
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.upsertRun failed: " + r.id(), e);
            return false;
        }
    }

    public Optional<RailRun> findRun(String id) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM rail_runs WHERE id = :id")
                    .bind("id", id)
                    .map((rs, ctx) -> mapRun(rs))
                    .findOne());
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.findRun failed: " + id, e);
            return Optional.empty();
        }
    }

    public List<RailRun> listRunsByLine(String lineId) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM rail_runs WHERE line_id = :line "
                            + "ORDER BY direction ASC, run_number ASC")
                    .bind("line", lineId)
                    .map((rs, ctx) -> mapRun(rs))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.listRunsByLine failed: " + lineId, e);
            return List.of();
        }
    }

    public int deleteRun(String id) {
        try {
            return jdbi.withHandle(h -> h.createUpdate(
                    "DELETE FROM rail_runs WHERE id = :id")
                    .bind("id", id)
                    .execute());
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.deleteRun failed: " + id, e);
            return 0;
        }
    }

    // ────────────────────────────────────────────────────────────
    //  rail_timetable
    // ────────────────────────────────────────────────────────────

    /**
     * 批量替换车次的 timetable：先删该 runId 的所有行，再批量 insert。事务内执行。
     *
     * @return 是否整体成功。{@code false} 时事务已回滚（旧时刻表原样保留），常见原因是
     *         某条目的 station_id 不存在（外键）
     */
    public boolean replaceTimetable(String runId, List<RailTimetableEntry> entries) {
        try {
            jdbi.useTransaction(h -> {
                h.createUpdate("DELETE FROM rail_timetable WHERE run_id = :r")
                        .bind("r", runId)
                        .execute();
                for (RailTimetableEntry e : entries) {
                    h.createUpdate(
                            "INSERT INTO rail_timetable("
                                    + "run_id, station_id, arrival_time, departure_time, stops_here) "
                                    + "VALUES(:r, :s, :arr, :dep, :stops)")
                            .bind("r", e.runId())
                            .bind("s", e.stationId())
                            .bind("arr", e.arrivalTime())
                            .bind("dep", e.departureTime())
                            .bind("stops", e.stopsHere() ? 1 : 0)
                            .execute();
                }
            });
            return true;
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.replaceTimetable failed: run=" + runId, e);
            return false;
        }
    }

    /**
     * 按线路一次性聚合 stations + runs + 各 run 的 timetable，给前端
     * RailNetworkModal `selectLine` 调用避免 N+1 个独立请求。
     *
     * @return 聚合视图；线路不存在时返 empty
     */
    public LineDetail loadLineDetail(String lineId) {
        if (lineId == null) return LineDetail.empty(null);
        List<RailStation> sts = listStationsByLine(lineId);
        List<RailRun> rs = listRunsByLine(lineId);
        // 批量拉 timetable：一次查 WHERE run_id IN (...)。runs 通常 ≤ 50，单查无压力。
        java.util.Map<String, List<RailTimetableEntry>> ttByRun = new java.util.LinkedHashMap<>();
        if (rs.isEmpty()) {
            return new LineDetail(lineId, sts, rs, ttByRun);
        }
        try {
            jdbi.useHandle(h -> {
                // 用 IN 子句拉所有 run 的 timetable（jdbi bindList）
                List<String> runIds = rs.stream().map(RailRun::id).toList();
                h.createQuery(
                        "SELECT t.* FROM rail_timetable t "
                                + "JOIN rail_stations s ON s.id = t.station_id "
                                + "WHERE t.run_id IN (<rids>) "
                                + "ORDER BY t.run_id, s.sort_order ASC")
                        .bindList("rids", runIds)
                        .map((rs2, ctx) -> mapTimetable(rs2))
                        .forEach(te -> ttByRun
                                .computeIfAbsent(te.runId(), k -> new ArrayList<>())
                                .add(te));
            });
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.loadLineDetail timetable fetch failed: " + lineId, e);
        }
        return new LineDetail(lineId, sts, rs, ttByRun);
    }

    /** {@link #loadLineDetail} 的聚合返回。 */
    public record LineDetail(
            String lineId,
            List<RailStation> stations,
            List<RailRun> runs,
            java.util.Map<String, List<RailTimetableEntry>> timetableByRun
    ) {
        public static LineDetail empty(String lineId) {
            return new LineDetail(lineId, List.of(), List.of(), java.util.Map.of());
        }
    }

    public List<RailTimetableEntry> listTimetableByRun(String runId) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT t.* FROM rail_timetable t "
                            + "JOIN rail_stations s ON s.id = t.station_id "
                            + "WHERE t.run_id = :r ORDER BY s.sort_order ASC")
                    .bind("r", runId)
                    .map((rs, ctx) -> mapTimetable(rs))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.listTimetableByRun failed: " + runId, e);
            return List.of();
        }
    }

    /**
     * 关键查询：某条线上、某方向、某站停靠的车次的下一班 / 下下班。
     *
     * <p>SQL：JOIN runs + timetable WHERE 站 = ? + 方向 = ? + stops_here = 1 + (direction
     * 过滤) ORDER BY arrival_time（或 departure_time fallback） LIMIT N。</p>
     *
     * <p>过零点逻辑：不在 SQL 内处理（业务复杂）；Provider 拿"全天"班次 list 自己按 LocalTime
     * 比较 + 过零点回首班。</p>
     *
     * @param stationId   本站
     * @param direction   {@code "up"} / {@code "down"} / {@code "both"}（{@code "both"} 不过滤）
     */
    public List<TimetableJoinRow> listStationStops(String stationId, String direction) {
        String dirClause = WallRailBinding.DIRECTION_BOTH.equals(direction)
                ? ""
                : " AND r.direction = :dir";
        try {
            return jdbi.withHandle(h -> {
                var q = h.createQuery(
                        "SELECT t.run_id, t.station_id, t.arrival_time, t.departure_time, "
                                + "t.stops_here, r.id AS r_id, r.line_id AS r_line, "
                                + "r.run_number, r.direction, r.service_type, r.cars, "
                                + "r.start_station_id, r.end_station_id, r.notes, "
                                + "r.created_at AS r_created, r.updated_at AS r_updated "
                                + "FROM rail_timetable t "
                                + "JOIN rail_runs r ON r.id = t.run_id "
                                + "WHERE t.station_id = :s AND t.stops_here = 1"
                                + dirClause + " "
                                + "ORDER BY COALESCE(t.arrival_time, t.departure_time) ASC")
                        .bind("s", stationId);
                if (!WallRailBinding.DIRECTION_BOTH.equals(direction)) {
                    q = q.bind("dir", direction);
                }
                return q.map((rs, ctx) -> {
                    RailTimetableEntry te = new RailTimetableEntry(
                            rs.getString("run_id"),
                            rs.getString("station_id"),
                            rs.getString("arrival_time"),
                            rs.getString("departure_time"),
                            rs.getInt("stops_here") != 0);
                    Integer cars = rs.getObject("cars") == null
                            ? null : rs.getInt("cars");
                    RailRun run = new RailRun(
                            rs.getString("r_id"),
                            rs.getString("r_line"),
                            rs.getString("run_number"),
                            rs.getString("direction"),
                            rs.getString("service_type"),
                            cars,
                            rs.getString("start_station_id"),
                            rs.getString("end_station_id"),
                            rs.getString("notes"),
                            rs.getLong("r_created"),
                            rs.getLong("r_updated"));
                    return new TimetableJoinRow(te, run);
                }).list();
            });
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.listStationStops failed: station="
                    + stationId + " dir=" + direction, e);
            return List.of();
        }
    }

    /** {@link #listStationStops} 的 JOIN 结果，timetable + run 一行一打包。 */
    public record TimetableJoinRow(RailTimetableEntry entry, RailRun run) {}

    // ────────────────────────────────────────────────────────────
    //  wall_rail_bindings
    // ────────────────────────────────────────────────────────────

    /**
     * @return 是否写入成功。{@code false} 常见于 line_id / station_id 指向不存在的行（外键），
     *         此时绝不能回 ack——否则 wall 被当成"已绑铁路"，ManualSchedule 永久跳过它，
     *         而 RailScheduleProvider 又查不到停靠信息，站牌只剩空白
     */
    public boolean upsertBinding(WallRailBinding b) {
        try {
            jdbi.useHandle(h -> h.createUpdate(
                    "INSERT INTO wall_rail_bindings("
                            + "wall_id, line_id, station_id, direction, updated_at) "
                            + "VALUES(:w, :l, :s, :d, :u) "
                            + "ON CONFLICT(wall_id) DO UPDATE SET "
                            + "line_id = excluded.line_id, "
                            + "station_id = excluded.station_id, "
                            + "direction = excluded.direction, "
                            + "updated_at = excluded.updated_at")
                    .bind("w", b.wallId())
                    .bind("l", b.lineId())
                    .bind("s", b.stationId())
                    .bind("d", b.direction())
                    .bind("u", b.updatedAt())
                    .execute());
            return true;
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.upsertBinding failed: " + b.wallId(), e);
            return false;
        }
    }

    public Optional<WallRailBinding> findBinding(String wallId) {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM wall_rail_bindings WHERE wall_id = :w")
                    .bind("w", wallId)
                    .map((rs, ctx) -> new WallRailBinding(
                            rs.getString("wall_id"),
                            rs.getString("line_id"),
                            rs.getString("station_id"),
                            rs.getString("direction"),
                            rs.getLong("updated_at")))
                    .findOne());
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.findBinding failed: " + wallId, e);
            return Optional.empty();
        }
    }

    public List<WallRailBinding> listAllBindings() {
        try {
            return jdbi.withHandle(h -> h.createQuery(
                    "SELECT * FROM wall_rail_bindings")
                    .map((rs, ctx) -> new WallRailBinding(
                            rs.getString("wall_id"),
                            rs.getString("line_id"),
                            rs.getString("station_id"),
                            rs.getString("direction"),
                            rs.getLong("updated_at")))
                    .list());
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.listAllBindings failed", e);
            return List.of();
        }
    }

    public int deleteBinding(String wallId) {
        try {
            return jdbi.withHandle(h -> h.createUpdate(
                    "DELETE FROM wall_rail_bindings WHERE wall_id = :w")
                    .bind("w", wallId)
                    .execute());
        } catch (Exception e) {
            log.log(Level.WARNING, "RailDao.deleteBinding failed: " + wallId, e);
            return 0;
        }
    }

    // ────────────────────────────────────────────────────────────
    //  事务感知重载（供 RailOpDispatcher 在外层事务调用）
    // ────────────────────────────────────────────────────────────

    public int deleteBindingOn(Handle h, String wallId) {
        return h.createUpdate("DELETE FROM wall_rail_bindings WHERE wall_id = :w")
                .bind("w", wallId)
                .execute();
    }

    // ────────────────────────────────────────────────────────────
    //  helpers
    // ────────────────────────────────────────────────────────────

    // 非 static（用实例 log 记录损坏数据告警）。仅在实例方法的 RowMapper lambda 内调用。
    private RailLine mapLine(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID owner;
        try {
            owner = UUID.fromString(rs.getString("owner_uuid"));
        } catch (IllegalArgumentException ex) {
            // 外部 DB 数据损坏的防御性降级——nil UUID 匹配 owner 失败更受限（安全）。
            // 补 WARNING 日志便于运维排查 owner_uuid 被损坏的根因。
            log.log(Level.WARNING, "RailDao.mapLine owner_uuid parse failed, id="
                    + rs.getString("id") + ", owner=" + rs.getString("owner_uuid"), ex);
            owner = new UUID(0L, 0L);
        }
        return new RailLine(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("code"),
                rs.getString("color"),
                owner,
                rs.getString("owner_name"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    private static RailStation mapStation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RailStation(
                rs.getString("id"),
                rs.getString("line_id"),
                rs.getString("name"),
                rs.getString("code"),
                rs.getInt("sort_order"),
                rs.getInt("is_terminus") != 0,
                rs.getLong("created_at"));
    }

    private static RailRun mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        Object carsObj = rs.getObject("cars");
        Integer cars = carsObj == null ? null : ((Number) carsObj).intValue();
        return new RailRun(
                rs.getString("id"),
                rs.getString("line_id"),
                rs.getString("run_number"),
                rs.getString("direction"),
                rs.getString("service_type"),
                cars,
                rs.getString("start_station_id"),
                rs.getString("end_station_id"),
                rs.getString("notes"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    private static RailTimetableEntry mapTimetable(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RailTimetableEntry(
                rs.getString("run_id"),
                rs.getString("station_id"),
                rs.getString("arrival_time"),
                rs.getString("departure_time"),
                rs.getInt("stops_here") != 0);
    }

    @Nullable
    @SuppressWarnings("unused")  // 供未来按 owner 列表用
    private static String nullableString(java.sql.ResultSet rs, String col)
            throws java.sql.SQLException {
        String v = rs.getString(col);
        return rs.wasNull() ? null : v;
    }
}
