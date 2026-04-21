package moe.hikari.canvas.pool;

import moe.hikari.canvas.render.HikariCanvasRenderer;
import moe.hikari.canvas.storage.AuditLog;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.map.MapView;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <b>M2 核心机制</b>——预览地图池。契约见 {@code docs/architecture.md §4}、
 * {@code docs/data-model.md §2.3}。
 *
 * <p>整个项目的"不让 idcounts.dat 膨胀"就靠这一层：编辑会话借出现有池中
 * {@link PoolState#FREE FREE} 地图并改像素，而不是每次都 {@code Bukkit.createMap}；
 * commit 时将 RESERVED 转 PERMANENT 从池抽走，同时 refill 补新 FREE 到 initial-size。</p>
 *
 * <p>线程模型：所有公共方法 {@code synchronized(this)}，调用方不要在持锁状态下做
 * 主线程阻塞操作。涉及 Bukkit API（{@link Bukkit#createMap}、{@link MapView}）
 * 的调用**必须在 Bukkit 主线程**，因此以下方法不能从异步线程调用：
 * <ul>
 *   <li>{@link #initialize(World)}</li>
 *   <li>{@link #reserve(String, int)}（扩容时会 createMap）</li>
 *   <li>{@link #promoteToPermanent(String, String, String)}（触发 refill）</li>
 * </ul>
 * {@link #detectLeaks} 可以从异步调度器调用，它只读状态不碰 Bukkit API。</p>
 */
public final class MapPool {

    private final Logger log;
    private final Jdbi jdbi;
    private final AuditLog auditLog;
    private final HikariCanvasRenderer sharedRenderer;
    private final int initialSize;
    private final int maxSize;

    private final Map<Integer, PooledMap> byId = new HashMap<>();
    private final Deque<Integer> freeQueue = new ArrayDeque<>();

    public MapPool(Logger log, Jdbi jdbi, AuditLog auditLog,
                   HikariCanvasRenderer sharedRenderer,
                   int initialSize, int maxSize) {
        if (initialSize <= 0 || maxSize < initialSize) {
            throw new IllegalArgumentException(
                    "invalid pool sizing: initial=" + initialSize + " max=" + maxSize);
        }
        this.log = log;
        this.jdbi = jdbi;
        this.auditLog = auditLog;
        this.sharedRenderer = sharedRenderer;
        this.initialSize = initialSize;
        this.maxSize = maxSize;
    }

    /**
     * 启动初始化：从 SQLite 加载既有池记录 → 校验不变式 → FREE 数量不足时 createMap 补齐。
     * <b>必须在主线程调用</b>（createMap 需要主线程）。
     */
    public synchronized void initialize(World defaultWorld) {
        Objects.requireNonNull(defaultWorld, "defaultWorld required for creating new maps");

        List<PooledMap> persisted = jdbi.withHandle(h -> h.createQuery(
                        "SELECT map_id, state, reserved_by, sign_id, world, created_at, last_used_at "
                                + "FROM pool_maps")
                .map((rs, ctx) -> new PooledMap(
                        rs.getInt("map_id"),
                        PoolState.valueOf(rs.getString("state")),
                        rs.getString("reserved_by"),
                        rs.getString("sign_id"),
                        rs.getString("world"),
                        rs.getLong("created_at"),
                        rs.getLong("last_used_at")))
                .list());

        long now = System.currentTimeMillis();
        int recovered = 0;
        int missingMapView = 0;
        int normalized = 0;

        for (PooledMap rec : persisted) {
            MapView view = Bukkit.getMap(rec.mapId());
            if (view == null) {
                // DB 里有记录但游戏里 map 消失（世界数据丢失等）→ 删 DB 行 + 告警，跳过
                missingMapView++;
                jdbi.useHandle(h -> h.execute("DELETE FROM pool_maps WHERE map_id = ?", rec.mapId()));
                auditLog.record("POOL_ORPHAN_ROW", null, null, null, null,
                        Map.of("map_id", rec.mapId(), "state", rec.state().name()));
                continue;
            }
            // 重启后 Paper 把默认 renderer 加回 MapView，会每 tick 写空白 canvas 覆盖
            // 我们直接 push 的 MapData packet。解法：清默认 + 装我们自己的 renderer，
            // 让 Paper tick 去 HikariCanvasRenderer 拿像素。
            new java.util.ArrayList<>(view.getRenderers()).forEach(view::removeRenderer);
            view.addRenderer(sharedRenderer);

            PooledMap normalizedRec = enforceInvariant(rec, now);
            if (!normalizedRec.equals(rec)) {
                normalized++;
                persist(normalizedRec);
            }

            byId.put(normalizedRec.mapId(), normalizedRec);
            if (normalizedRec.state() == PoolState.FREE) {
                freeQueue.offer(normalizedRec.mapId());
            }
            recovered++;
        }

        log.info(String.format(
                "MapPool recovered %d entries (free=%d reserved=%d permanent=%d; missing MapView=%d; normalized=%d)",
                recovered,
                countByState(PoolState.FREE),
                countByState(PoolState.RESERVED),
                countByState(PoolState.PERMANENT),
                missingMapView,
                normalized));

        // 补齐到 initial-size（只补 FREE；不动 RESERVED/PERMANENT 数量）
        int freeNow = freeQueue.size();
        if (freeNow < initialSize) {
            int need = initialSize - freeNow;
            log.info("MapPool growing FREE by " + need + " to reach initial-size=" + initialSize);
            expand(defaultWorld, need);
        }
        auditLog.record("POOL_INITIALIZED", null, null, null, null,
                Map.of("total", byId.size(), "free", freeQueue.size(),
                        "initial_size", initialSize, "max_size", maxSize));
    }

    /**
     * 借出 {@code count} 张 FREE → RESERVED，返回对应的 mapId 列表。
     * 不够时按需 expand（到 max 为止）；超 max 抛 {@link PoolExhaustedException}。
     * <b>必须在主线程调用</b>（可能触发扩容 createMap）。
     */
    public synchronized List<Integer> reserve(String sessionId, int count) {
        Objects.requireNonNull(sessionId);
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0");
        }

        int shortfall = count - freeQueue.size();
        if (shortfall > 0) {
            int totalAfter = byId.size() + shortfall;
            if (totalAfter > maxSize) {
                throw new PoolExhaustedException(
                        "cannot reserve " + count + " maps: pool at "
                                + byId.size() + "/" + maxSize + " (free=" + freeQueue.size() + ")");
            }
            World world = Bukkit.getWorlds().get(0);
            expand(world, shortfall);
        }

        long now = System.currentTimeMillis();
        List<Integer> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int mapId = freeQueue.poll();
            PooledMap cur = byId.get(mapId);
            PooledMap updated = cur.withReserved(sessionId, now);
            byId.put(mapId, updated);
            persist(updated);
            out.add(mapId);
        }
        auditLog.record("POOL_RESERVE", null, null, sessionId, null,
                Map.of("count", count, "map_ids", out));
        return out;
    }

    /**
     * 归还某 session 所持全部 RESERVED 地图 → FREE。
     * {@code cancel} 语义；{@code commit} 走 {@link #promoteToPermanent}。
     */
    public synchronized int returnToPool(String sessionId) {
        long now = System.currentTimeMillis();
        List<Integer> affected = new ArrayList<>();
        for (PooledMap m : new ArrayList<>(byId.values())) {
            if (m.state() == PoolState.RESERVED && sessionId.equals(m.reservedBy())) {
                PooledMap freed = m.withFree(now);
                byId.put(m.mapId(), freed);
                freeQueue.offer(m.mapId());
                persist(freed);
                affected.add(m.mapId());
            }
        }
        if (!affected.isEmpty()) {
            auditLog.record("POOL_RETURN", null, null, sessionId, null,
                    Map.of("count", affected.size(), "map_ids", affected));
        }
        return affected.size();
    }

    /**
     * 提交：把某 session 的所有 RESERVED 转为 PERMANENT（绑到 signId + world），
     * 从池中 <em>移出</em>（FREE 计数不含它），随后 refill 到 initialSize。
     * <b>必须在主线程调用</b>（refill 会 createMap）。
     */
    public synchronized List<Integer> promoteToPermanent(String sessionId, String signId, String world) {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(signId);
        Objects.requireNonNull(world);
        long now = System.currentTimeMillis();
        List<Integer> affected = new ArrayList<>();
        for (PooledMap m : new ArrayList<>(byId.values())) {
            if (m.state() == PoolState.RESERVED && sessionId.equals(m.reservedBy())) {
                PooledMap perm = m.withPermanent(signId, world, now);
                byId.put(m.mapId(), perm);
                persist(perm);
                affected.add(m.mapId());
            }
        }
        auditLog.record("POOL_PROMOTE", null, null, sessionId, null,
                Map.of("count", affected.size(), "sign_id", signId, "map_ids", affected));

        // refill：把 FREE 数量补回 initialSize（PERMANENT 不算池内"可用"）
        int freeNow = freeQueue.size();
        if (freeNow < initialSize) {
            int need = Math.min(initialSize - freeNow, maxSize - byId.size());
            if (need > 0) {
                expand(Bukkit.getWorlds().get(0), need);
            }
        }
        return affected;
    }

    /**
     * 泄漏检测：DB 显示 RESERVED 但其 sessionId 已不在 {@code liveSessions} 中
     * → 强制归还 + 记 WARN。可从异步线程调用。
     */
    public synchronized int detectLeaks(java.util.Set<String> liveSessions) {
        long now = System.currentTimeMillis();
        List<Integer> leaked = new ArrayList<>();
        for (PooledMap m : new ArrayList<>(byId.values())) {
            if (m.state() == PoolState.RESERVED && !liveSessions.contains(m.reservedBy())) {
                PooledMap freed = m.withFree(now);
                byId.put(m.mapId(), freed);
                freeQueue.offer(m.mapId());
                persist(freed);
                leaked.add(m.mapId());
            }
        }
        if (!leaked.isEmpty()) {
            log.warning("MapPool leak detected: " + leaked.size() + " RESERVED maps "
                    + "had no live session; force-returned to FREE. map_ids=" + leaked);
            auditLog.record("POOL_LEAK", null, null, null, null,
                    Map.of("count", leaked.size(), "map_ids", leaked));
        }
        return leaked.size();
    }

    public synchronized Stats stats() {
        return new Stats(
                byId.size(),
                countByState(PoolState.FREE),
                countByState(PoolState.RESERVED),
                countByState(PoolState.PERMANENT));
    }

    public record Stats(int total, int free, int reserved, int permanent) {}

    // ----- 内部实现 -----

    private void expand(World world, int count) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            MapView view = Bukkit.createMap(world);
            // 清默认 renderer，装我们的
            new java.util.ArrayList<>(view.getRenderers()).forEach(view::removeRenderer);
            view.addRenderer(sharedRenderer);
            int id = view.getId();
            PooledMap rec = new PooledMap(id, PoolState.FREE, null, null, null, now, now);
            byId.put(id, rec);
            freeQueue.offer(id);
            persist(rec);
        }
        auditLog.record("POOL_EXPAND", null, null, null, null,
                Map.of("count", count, "world", world.getName(), "new_total", byId.size()));
    }

    /**
     * 修复启动时读到的记录中违反不变式的字段（例如 FREE 但 reserved_by 非空）。
     * 不变式异常时降级为 FREE 并记告警。
     */
    private PooledMap enforceInvariant(PooledMap rec, long now) {
        switch (rec.state()) {
            case FREE -> {
                if (rec.reservedBy() != null || rec.signId() != null) {
                    log.warning("pool_maps row for map_id=" + rec.mapId()
                            + " is FREE but has reserved_by/sign_id; normalizing");
                    return rec.withFree(now);
                }
            }
            case RESERVED -> {
                if (rec.reservedBy() == null || rec.signId() != null) {
                    log.warning("pool_maps row for map_id=" + rec.mapId()
                            + " is RESERVED but invariants broken; downgrading to FREE");
                    return rec.withFree(now);
                }
            }
            case PERMANENT -> {
                if (rec.signId() == null || rec.reservedBy() != null) {
                    log.warning("pool_maps row for map_id=" + rec.mapId()
                            + " is PERMANENT but invariants broken; downgrading to FREE");
                    return rec.withFree(now);
                }
            }
        }
        return rec;
    }

    private int countByState(PoolState s) {
        int c = 0;
        for (PooledMap m : byId.values()) if (m.state() == s) c++;
        return c;
    }

    private void persist(PooledMap m) {
        try {
            jdbi.useHandle(h -> h.execute(
                    "INSERT INTO pool_maps (map_id, state, reserved_by, sign_id, world, created_at, last_used_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT(map_id) DO UPDATE SET "
                            + "state = excluded.state, "
                            + "reserved_by = excluded.reserved_by, "
                            + "sign_id = excluded.sign_id, "
                            + "world = excluded.world, "
                            + "last_used_at = excluded.last_used_at",
                    m.mapId(), m.state().name(), m.reservedBy(), m.signId(), m.world(),
                    m.createdAt(), m.lastUsedAt()));
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to persist pool_maps row for map_id=" + m.mapId(), e);
        }
    }
}
