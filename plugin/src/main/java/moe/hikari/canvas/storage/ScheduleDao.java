package moe.hikari.canvas.storage;

import moe.hikari.canvas.schedule.ScheduleEntry;
import moe.hikari.canvas.schedule.WallSchedule;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code wall_schedules} + {@code schedule_entries} 表 DAO（0.4.0 P3-L）。
 *
 * <p>schema 见 {@code db-migrations/V012__wall_schedules.sql} + V013 增加 precision 列
 * + {@code docs/data-model.md §2.9}。
 *
 * <p><b>per-wall</b>：一个 wall 一行 wall_schedules + N 行 schedule_entries。FK CASCADE 让
 * wall 删除时 SQLite 自动级联清理；{@link #deleteByWall} 提供显式入口避免业务依赖 PRAGMA
 * 时序。
 *
 * <p><b>排序</b>：{@link #loadByWall} 返回的 entries 已按 {@code sort_order ASC, departure_time ASC}
 * 预排序——Provider / 编辑器 modal 可直接迭代不再排。
 *
 * <p><b>无 upsertEntry</b>：entry 主键 = 自增 id，插入用 {@link #insertEntry} 返回新 id，
 * 更新用 {@link #updateEntry}（按 id）。upsert 语义对 entry 不合适（玩家可能创建多条相同
 * departureTime 的 entry，e.g. 同时刻两班车去不同终点）。
 */
public class ScheduleDao {

    private final Logger log;
    private final Jdbi jdbi;

    public ScheduleDao(Logger log, Jdbi jdbi) {
        this.log = log;
        this.jdbi = jdbi;
    }

    /**
     * 加载一个 wall 的完整时刻表（元数据 + entries）。wall_schedules 行不存在时返
     * {@link Optional#empty()}（首次添加 entry 前的 wall 没有元数据行）。
     */
    public Optional<WallSchedule> loadByWall(String wallId) {
        try {
            return jdbi.withHandle(h -> {
                Optional<MetaRow> metaOpt = h.createQuery(
                        "SELECT wall_id, station_name, updated_at, precision FROM wall_schedules "
                                + "WHERE wall_id = :w")
                        .bind("w", wallId)
                        .map((rs, ctx) -> new MetaRow(
                                rs.getString("wall_id"),
                                rs.getString("station_name"),
                                rs.getLong("updated_at"),
                                rs.getString("precision")))
                        .findOne();
                if (metaOpt.isEmpty()) return Optional.empty();
                MetaRow meta = metaOpt.get();
                List<ScheduleEntry> entries = h.createQuery(
                        "SELECT id, wall_id, departure_time, destination, sort_order "
                                + "FROM schedule_entries WHERE wall_id = :w "
                                + "ORDER BY sort_order ASC, departure_time ASC")
                        .bind("w", wallId)
                        .map((rs, ctx) -> mapEntry(rs))
                        .list();
                return Optional.of(new WallSchedule(
                        meta.wallId, meta.stationName, meta.updatedAt,
                        WallSchedule.normalizePrecision(meta.precision), entries));
            });
        } catch (Exception e) {
            log.log(Level.WARNING, "ScheduleDao.loadByWall failed: " + wallId, e);
            return Optional.empty();
        }
    }

    /**
     * Upsert wall_schedules 元数据（站名 + updated_at）。首次调用同时创建元数据行；后续调用
     * 仅刷新字段。entries 走 {@link #insertEntry} 等独立 API。
     *
     * <p>3 参数 overload 保持向下兼容：默认 precision = "minute"。新代码请用
     * {@link #upsertSchedule(String, String, String)}。</p>
     */
    public void upsertSchedule(String wallId, @Nullable String stationName) {
        upsertSchedule(wallId, stationName, WallSchedule.PRECISION_MINUTE);
    }

    /**
     * 0.4.0 bugfix（Bug 4）：4 参数 overload，可指定 precision。
     *
     * <p>首次 upsert 时插入 (wall_id, station_name, updated_at, precision)；后续 upsert 同时
     * 更新 station_name + precision + updated_at（让玩家在 modal 切精度时 UI 即可触发持久化）。</p>
     *
     * @param precision {@code "minute"} 或 {@code "second"}；非法值自动规范化为 "minute"
     */
    public void upsertSchedule(String wallId, @Nullable String stationName, String precision) {
        long now = System.currentTimeMillis();
        String normalized = WallSchedule.normalizePrecision(precision);
        try {
            jdbi.useHandle(h -> h.createUpdate(
                    "INSERT INTO wall_schedules(wall_id, station_name, updated_at, precision) "
                            + "VALUES(:w, :s, :now, :p) "
                            + "ON CONFLICT(wall_id) DO UPDATE SET "
                            + "station_name = excluded.station_name, "
                            + "precision = excluded.precision, "
                            + "updated_at = excluded.updated_at")
                    .bind("w", wallId)
                    .bind("s", stationName)
                    .bind("now", now)
                    .bind("p", normalized)
                    .execute());
        } catch (Exception e) {
            log.log(Level.WARNING, "ScheduleDao.upsertSchedule failed: " + wallId, e);
        }
    }

    /**
     * 插入一条 entry，返回新生成的 id；失败返 -1。注意调用前确保 wall_schedules 元数据行
     * 已存在（业务侧通常先 {@link #upsertSchedule} 后才 insertEntry）；不过 schema 上 entries
     * FK 直挂 walls 表，没有元数据行也能插。
     */
    public long insertEntry(String wallId, String departureTime,
                            @Nullable String destination, int sortOrder) {
        try {
            return jdbi.withHandle(h -> h.createUpdate(
                    "INSERT INTO schedule_entries(wall_id, departure_time, destination, sort_order) "
                            + "VALUES(:w, :dt, :dst, :so)")
                    .bind("w", wallId)
                    .bind("dt", departureTime)
                    .bind("dst", destination)
                    .bind("so", sortOrder)
                    .executeAndReturnGeneratedKeys("id")
                    .mapTo(Long.class)
                    .one());
        } catch (Exception e) {
            log.log(Level.WARNING, "ScheduleDao.insertEntry failed: " + wallId, e);
            return -1L;
        }
    }

    /** 更新 entry 的时间 / 终点 / 排序。返回受影响行数（0 = id 不存在）。 */
    public int updateEntry(long id, String departureTime,
                           @Nullable String destination, int sortOrder) {
        try {
            return jdbi.withHandle(h -> h.createUpdate(
                    "UPDATE schedule_entries SET "
                            + "departure_time = :dt, destination = :dst, sort_order = :so "
                            + "WHERE id = :id")
                    .bind("id", id)
                    .bind("dt", departureTime)
                    .bind("dst", destination)
                    .bind("so", sortOrder)
                    .execute());
        } catch (Exception e) {
            log.log(Level.WARNING, "ScheduleDao.updateEntry failed: id=" + id, e);
            return 0;
        }
    }

    /** 删除单条 entry，按 id。返回受影响行数（0 = id 不存在）。 */
    public int deleteEntry(long id) {
        try {
            return jdbi.withHandle(h -> h.createUpdate(
                    "DELETE FROM schedule_entries WHERE id = :id")
                    .bind("id", id)
                    .execute());
        } catch (Exception e) {
            log.log(Level.WARNING, "ScheduleDao.deleteEntry failed: id=" + id, e);
            return 0;
        }
    }

    /**
     * Wall 删除时显式级联清理。
     *
     * <p>schema 已 FK CASCADE 自动触发；显式调用让业务侧不依赖 PRAGMA 时序 + 直接测试覆盖。
     * 同时清 wall_schedules 元数据 + entries 两表；分两步以兼容 SQLite 单语句 limits。</p>
     */
    public int deleteByWall(String wallId) {
        try {
            return jdbi.withHandle(h -> {
                int entries = h.createUpdate(
                        "DELETE FROM schedule_entries WHERE wall_id = :w")
                        .bind("w", wallId)
                        .execute();
                int meta = h.createUpdate(
                        "DELETE FROM wall_schedules WHERE wall_id = :w")
                        .bind("w", wallId)
                        .execute();
                return entries + meta;
            });
        } catch (Exception e) {
            log.log(Level.WARNING, "ScheduleDao.deleteByWall failed: " + wallId, e);
            return 0;
        }
    }

    /**
     * 启动期一次性加载所有 wall_schedules（含 entries）。Provider 用此在 initialize 阶段
     * 注册所有已配置时刻表的 wall。
     */
    public List<WallSchedule> loadAll() {
        try {
            return jdbi.withHandle(h -> {
                // 先取元数据列表
                List<MetaRow> metas = h.createQuery(
                        "SELECT wall_id, station_name, updated_at, precision FROM wall_schedules "
                                + "ORDER BY wall_id")
                        .map((rs, ctx) -> new MetaRow(
                                rs.getString("wall_id"),
                                rs.getString("station_name"),
                                rs.getLong("updated_at"),
                                rs.getString("precision")))
                        .list();
                if (metas.isEmpty()) return List.of();
                // 再一次性拉所有 entries（按 wall 桶分发）
                List<ScheduleEntry> all = h.createQuery(
                        "SELECT id, wall_id, departure_time, destination, sort_order "
                                + "FROM schedule_entries "
                                + "ORDER BY wall_id, sort_order ASC, departure_time ASC")
                        .map((rs, ctx) -> mapEntry(rs))
                        .list();
                List<WallSchedule> out = new ArrayList<>(metas.size());
                for (MetaRow meta : metas) {
                    List<ScheduleEntry> bucket = new ArrayList<>();
                    for (ScheduleEntry e : all) {
                        if (meta.wallId.equals(e.wallId())) bucket.add(e);
                    }
                    out.add(new WallSchedule(meta.wallId, meta.stationName,
                            meta.updatedAt,
                            WallSchedule.normalizePrecision(meta.precision),
                            bucket));
                }
                return out;
            });
        } catch (Exception e) {
            log.log(Level.WARNING, "ScheduleDao.loadAll failed", e);
            return List.of();
        }
    }

    /** 事务感知重载：插入 entry on existing handle（业务侧批量操作用）。 */
    public long insertEntryOn(Handle h, String wallId, String departureTime,
                              @Nullable String destination, int sortOrder) {
        return h.createUpdate(
                "INSERT INTO schedule_entries(wall_id, departure_time, destination, sort_order) "
                        + "VALUES(:w, :dt, :dst, :so)")
                .bind("w", wallId)
                .bind("dt", departureTime)
                .bind("dst", destination)
                .bind("so", sortOrder)
                .executeAndReturnGeneratedKeys("id")
                .mapTo(Long.class)
                .one();
    }

    private static ScheduleEntry mapEntry(ResultSet rs) throws SQLException {
        return new ScheduleEntry(
                rs.getLong("id"),
                rs.getString("wall_id"),
                rs.getString("departure_time"),
                rs.getString("destination"),
                rs.getInt("sort_order"));
    }

    /** 内部行视图。 */
    private record MetaRow(String wallId, @Nullable String stationName,
                           long updatedAt, @Nullable String precision) {}
}
