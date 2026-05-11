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
 * <b>预览地图池</b>。契约见 {@code docs/architecture.md §4}、{@code docs/data-model.md §2.3}。
 *
 * <p>整个项目的"不让 idcounts.dat 膨胀"就靠这一层：编辑期间借出现有池中
 * {@link PoolState#FREE FREE} 地图刷像素，而不是每次都 {@code Bukkit.createMap}。
 *
 * <p><b>M5.5 起两态：</b> {@code FREE} / {@code RESERVED}。{@code RESERVED} 的 owner 统一
 * 格式 {@code wall:<wall_id>}，wall 占的 map 一直占着直到 {@code /canvas delete} 显式释放。
 * 原 PERMANENT 状态废止；{@code commit / promoteToPermanent} 整套移除。
 *
 * <p>线程模型：所有公共方法 {@code synchronized(this)}。涉及 Bukkit API（{@link Bukkit#createMap}、
 * {@link MapView}）的调用必须在 Bukkit 主线程：
 * <ul>
 *   <li>{@link #initialize(World)}</li>
 *   <li>{@link #reserveForWall(String, int)}（扩容时会 createMap）</li>
 * </ul>
 * {@link #detectLeaks} 可异步调用（只读状态不碰 Bukkit API）。
 */
public final class MapPool {

    /** RESERVED 时 owner 字段的标准前缀。 */
    public static final String WALL_OWNER_PREFIX = "wall:";

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
                        "SELECT map_id, state, reserved_by, world, created_at, last_used_at "
                                + "FROM pool_maps")
                .map((rs, ctx) -> new PooledMap(
                        rs.getInt("map_id"),
                        PoolState.valueOf(rs.getString("state")),
                        rs.getString("reserved_by"),
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
                missingMapView++;
                jdbi.useHandle(h -> h.execute("DELETE FROM pool_maps WHERE map_id = ?", rec.mapId()));
                auditLog.record("POOL_ORPHAN_ROW", null, null, null, null,
                        Map.of("map_id", rec.mapId(), "state", rec.state().name()));
                continue;
            }
            // 重启后 Paper 把默认 renderer 加回 MapView，会每 tick 写空白 canvas 覆盖
            // 我们直接 push 的 MapData packet。解法：清默认 + 装我们自己的 renderer。
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
                "MapPool recovered %d entries (free=%d reserved=%d; missing MapView=%d; normalized=%d)",
                recovered,
                countByState(PoolState.FREE),
                countByState(PoolState.RESERVED),
                missingMapView,
                normalized));

        // 补齐到 initial-size（只补 FREE）
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

    // ---------- M5.5 wall 模型主路径 ----------

    /**
     * 为新 wall 借出 {@code count} 张 FREE → RESERVED（owner = "wall:<wallId>"）。
     * 不够时按需 expand（到 max 为止）；超 max 抛 {@link PoolExhaustedException}。
     * <b>必须在主线程调用</b>（可能触发扩容 createMap）。
     */
    public synchronized List<Integer> reserveForWall(String wallId, int count) {
        Objects.requireNonNull(wallId);
        if (count <= 0) throw new IllegalArgumentException("count must be > 0");

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
        String owner = WALL_OWNER_PREFIX + wallId;
        List<Integer> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int mapId = freeQueue.poll();
            PooledMap cur = byId.get(mapId);
            PooledMap updated = cur.withReserved(owner, now);
            byId.put(mapId, updated);
            persist(updated);
            out.add(mapId);
        }
        auditLog.record("POOL_RESERVE", null, null, null, null,
                Map.of("wall_id", wallId, "count", count, "map_ids", out));
        return out;
    }

    /**
     * 把已有 {@code mapIds} 绑定到 {@code wallId}（启动恢复 / `/canvas open` 路径用）。
     * 接受的源状态：FREE 或已是该 wall 的 RESERVED；其它（被别 wall 持有）拒绝返回 false。
     */
    public synchronized boolean bindToWall(String wallId, List<Integer> mapIds) {
        Objects.requireNonNull(wallId);
        if (mapIds == null || mapIds.isEmpty()) return false;
        String owner = WALL_OWNER_PREFIX + wallId;
        for (int id : mapIds) {
            PooledMap m = byId.get(id);
            if (m == null) return false;
            if (m.state() == PoolState.RESERVED && !owner.equals(m.reservedBy())) return false;
        }
        long now = System.currentTimeMillis();
        for (int id : mapIds) {
            PooledMap m = byId.get(id);
            if (m.state() == PoolState.FREE) freeQueue.remove(Integer.valueOf(id));
            PooledMap upd = m.withReserved(owner, now);
            byId.put(id, upd);
            persist(upd);
        }
        auditLog.record("POOL_BIND_WALL", null, null, null, null,
                Map.of("wall_id", wallId, "map_ids", mapIds));
        return true;
    }

    /**
     * 释放某 wall 持有的所有 RESERVED 地图 → FREE。{@code /canvas delete} 走此路径。
     * Returns released map ids.
     */
    public synchronized List<Integer> releaseWall(String wallId) {
        Objects.requireNonNull(wallId);
        String owner = WALL_OWNER_PREFIX + wallId;
        long now = System.currentTimeMillis();
        List<Integer> released = new ArrayList<>();
        for (PooledMap m : new ArrayList<>(byId.values())) {
            if (m.state() == PoolState.RESERVED && owner.equals(m.reservedBy())) {
                PooledMap freed = m.withFree(now);
                byId.put(m.mapId(), freed);
                freeQueue.offer(m.mapId());
                persist(freed);
                released.add(m.mapId());
            }
        }
        if (!released.isEmpty()) {
            auditLog.record("POOL_RELEASE_WALL", null, null, null, null,
                    Map.of("wall_id", wallId, "count", released.size(), "map_ids", released));
        }
        return released;
    }

    /**
     * 泄漏检测：RESERVED 但 owner 非 "wall:*" 格式（旧版残留），或者 walls 表无对应行。
     * 当前简化策略：扫所有 RESERVED；非 "wall:" 前缀直接归还（视为旧 session: / draft: 残留）。
     * 后续可加"wall_id 不在 walls 表"的二次校验；调用方负责传 liveWallIds 集合。
     */
    public synchronized int detectLeaks(java.util.Set<String> liveWallIds) {
        long now = System.currentTimeMillis();
        List<Integer> leaked = new ArrayList<>();
        for (PooledMap m : new ArrayList<>(byId.values())) {
            if (m.state() != PoolState.RESERVED) continue;
            String owner = m.reservedBy();
            if (owner == null) {
                leaked.add(m.mapId());
                continue;
            }
            if (!owner.startsWith(WALL_OWNER_PREFIX)) {
                // 旧版 session: / draft: 残留，回收
                leaked.add(m.mapId());
                continue;
            }
            String wallId = owner.substring(WALL_OWNER_PREFIX.length());
            if (liveWallIds != null && !liveWallIds.contains(wallId)) {
                leaked.add(m.mapId());
            }
        }
        for (int id : leaked) {
            PooledMap m = byId.get(id);
            PooledMap freed = m.withFree(now);
            byId.put(id, freed);
            freeQueue.offer(id);
            persist(freed);
        }
        if (!leaked.isEmpty()) {
            log.warning("MapPool leak detected: " + leaked.size() + " RESERVED maps "
                    + "had no live wall; force-returned to FREE. map_ids=" + leaked);
            auditLog.record("POOL_LEAK", null, null, null, null,
                    Map.of("count", leaked.size(), "map_ids", leaked));
        }
        return leaked.size();
    }

    public synchronized Stats stats() {
        return new Stats(
                byId.size(),
                countByState(PoolState.FREE),
                countByState(PoolState.RESERVED));
    }

    public record Stats(int total, int free, int reserved) {}

    // ----- 内部实现 -----

    private void expand(World world, int count) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            MapView view = Bukkit.createMap(world);
            new java.util.ArrayList<>(view.getRenderers()).forEach(view::removeRenderer);
            view.addRenderer(sharedRenderer);
            int id = view.getId();
            PooledMap rec = new PooledMap(id, PoolState.FREE, null, world.getName(), now, now);
            byId.put(id, rec);
            freeQueue.offer(id);
            persist(rec);
        }
        auditLog.record("POOL_EXPAND", null, null, null, null,
                Map.of("count", count, "world", world.getName(), "new_total", byId.size()));
    }

    private PooledMap enforceInvariant(PooledMap rec, long now) {
        switch (rec.state()) {
            case FREE -> {
                if (rec.reservedBy() != null) {
                    log.warning("pool_maps row map_id=" + rec.mapId()
                            + " is FREE but has reserved_by; normalizing");
                    return rec.withFree(now);
                }
            }
            case RESERVED -> {
                if (rec.reservedBy() == null) {
                    log.warning("pool_maps row map_id=" + rec.mapId()
                            + " is RESERVED with null owner; downgrading to FREE");
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
                    "INSERT INTO pool_maps (map_id, state, reserved_by, world, created_at, last_used_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT(map_id) DO UPDATE SET "
                            + "state = excluded.state, "
                            + "reserved_by = excluded.reserved_by, "
                            + "world = excluded.world, "
                            + "last_used_at = excluded.last_used_at",
                    m.mapId(), m.state().name(), m.reservedBy(), m.world(),
                    m.createdAt(), m.lastUsedAt()));
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to persist pool_maps row for map_id=" + m.mapId(), e);
        }
    }
}
