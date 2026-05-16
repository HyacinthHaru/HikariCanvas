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
import java.util.UUID;
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
 * <p><b>M16 Phase 2 多世界化</b>（P2.3 / P2.4）：池按 world 分桶。每个 world 一条 FREE 队列；
 * {@link #reserveForWall(String, int, World)} / {@link #bindToWall(String, List, World)}
 * 都要求显式 world 入参，确保给 wall 借出的 map 与 wall 所在 world 一致——多世界服务器上
 * nether/end 的 wall 不会绑到 overworld map 上。{@code bindToWall} 内部断言 MapView world
 * 与传入 world 一致，遇到错配立即抛 {@link IllegalStateException}（不可恢复的内部 bug）。
 *
 * <p>线程模型：所有公共方法 {@code synchronized(this)}。涉及 Bukkit API（{@link Bukkit#createMap}、
 * {@link MapView}）的调用必须在 Bukkit 主线程：
 * <ul>
 *   <li>{@link #initialize(World, Map)}</li>
 *   <li>{@link #reserveForWall(String, int, World)}（扩容时会 createMap）</li>
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
    /** FREE 队列按 world UUID 分桶。同一 world 的 FREE map 集中在一条 deque 里。 */
    private final Map<UUID, Deque<Integer>> freeByWorld = new HashMap<>();

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
     *
     * @param defaultWorld 默认 world（用于 initial 总数补齐 + 旧池行 world 缺失时的 fallback）
     * @param perWorldInitial 可选 per-world initial 配置（world name → 该 world 至少 FREE 数）；
     *                        未配置的 world 走 on-demand 扩容；可传 {@link Map#of()} 表示"全走 defaultWorld"。
     */
    public synchronized void initialize(World defaultWorld, Map<String, Integer> perWorldInitial) {
        assertMainThread();
        Objects.requireNonNull(defaultWorld, "defaultWorld required for creating new maps");
        if (perWorldInitial == null) perWorldInitial = Map.of();

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
                offerFree(normalizedRec.mapId(), view.getWorld());
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

        // 补齐到 initial-size（只补 FREE，按 perWorldInitial 分配；剩余去 defaultWorld）
        int totalFree = totalFreeCount();
        int totalInitialBudget = initialSize;

        // 阶段 1：per-world 显式补齐
        int totalNewlyCreated = 0;
        for (Map.Entry<String, Integer> e : perWorldInitial.entrySet()) {
            World w = Bukkit.getWorld(e.getKey());
            if (w == null) {
                log.warning("map-pool.per-world: world '" + e.getKey()
                        + "' is not loaded; skipping initial allocation");
                continue;
            }
            int want = Math.max(0, e.getValue());
            int haveInWorld = freeCountFor(w);
            int need = want - haveInWorld;
            if (need > 0) {
                log.info("MapPool growing FREE in world '" + w.getName() + "' by " + need
                        + " (target=" + want + ")");
                expand(w, need);
                totalNewlyCreated += need;
            }
        }

        // 阶段 2：若 (recovered FREE + per-world new) 仍不满 initialSize，剩余去 defaultWorld
        int freeAfterPerWorld = totalFree + totalNewlyCreated;
        if (freeAfterPerWorld < totalInitialBudget) {
            int need = totalInitialBudget - freeAfterPerWorld;
            log.info("MapPool growing FREE in default world '" + defaultWorld.getName()
                    + "' by " + need + " to reach initial-size=" + totalInitialBudget);
            expand(defaultWorld, need);
        }
        auditLog.record("POOL_INITIALIZED", null, null, null, null,
                Map.of("total", byId.size(), "free", totalFreeCount(),
                        "initial_size", initialSize, "max_size", maxSize,
                        "per_world_configured", perWorldInitial.keySet()));
    }

    /** 兼容旧调用方：等价于 {@code initialize(defaultWorld, Map.of())}。 */
    public synchronized void initialize(World defaultWorld) {
        initialize(defaultWorld, Map.of());
    }

    // ---------- M5.5 wall 模型主路径 ----------

    /**
     * 为新 wall 借出 {@code count} 张 FREE → RESERVED（owner = "wall:&lt;wallId&gt;"）。
     * 借出的 map <b>必须与 wall 所在 world 一致</b>——同 world FREE 不够时按需 {@link #expand}
     * 在该 world 内扩容（到 max 为止）；超 max 抛 {@link PoolExhaustedException}。
     * <b>必须在主线程调用</b>（可能触发扩容 createMap）。
     *
     * @param wallId 目标 wall_id
     * @param count  借出数量
     * @param world  wall 所在 world（不可 null）；扩容也在该 world 内做
     */
    public synchronized List<Integer> reserveForWall(String wallId, int count, World world) {
        assertMainThread();
        Objects.requireNonNull(wallId);
        Objects.requireNonNull(world, "world required (multi-world map pool)");
        if (count <= 0) throw new IllegalArgumentException("count must be > 0");

        int haveInWorld = freeCountFor(world);
        int shortfall = count - haveInWorld;
        if (shortfall > 0) {
            int totalAfter = byId.size() + shortfall;
            if (totalAfter > maxSize) {
                throw new PoolExhaustedException(
                        "cannot reserve " + count + " maps for world '" + world.getName()
                                + "': pool at " + byId.size() + "/" + maxSize
                                + " (free-in-world=" + haveInWorld
                                + ", total-free=" + totalFreeCount() + ")");
            }
            expand(world, shortfall);
        }

        long now = System.currentTimeMillis();
        String owner = WALL_OWNER_PREFIX + wallId;
        Deque<Integer> queue = freeByWorld.get(world.getUID());
        List<Integer> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Integer mapId = queue.poll();
            PooledMap cur = byId.get(mapId);
            PooledMap updated = cur.withReserved(owner, now);
            byId.put(mapId, updated);
            persist(updated);
            out.add(mapId);
        }
        auditLog.record("POOL_RESERVE", null, null, null, null,
                Map.of("wall_id", wallId, "count", count, "world", world.getName(), "map_ids", out));
        return out;
    }

    /**
     * 把已有 {@code mapIds} 绑定到 {@code wallId}（启动恢复 / `/canvas open` 路径用）。
     * 接受的源状态：FREE 或已是该 wall 的 RESERVED；其它（被别 wall 持有）拒绝返回 false。
     *
     * <p><b>M16 P2.4 world 校验：</b> 每个 mapId 对应的 MapView 必须属于传入的 {@code expectedWorld}；
     * 不一致抛 {@link IllegalStateException}——这是不可恢复的内部 bug（wall 所在 world 与
     * 池中 map 所在 world 错配），应该尽早爆，绝不静默把像素画到错误 world 的 map。</p>
     */
    public synchronized boolean bindToWall(String wallId, List<Integer> mapIds, World expectedWorld) {
        assertMainThread();
        Objects.requireNonNull(wallId);
        Objects.requireNonNull(expectedWorld, "expectedWorld required (multi-world map pool)");
        if (mapIds == null || mapIds.isEmpty()) return false;
        String owner = WALL_OWNER_PREFIX + wallId;
        // 先扫一遍：world 校验 + state 校验
        for (int id : mapIds) {
            PooledMap m = byId.get(id);
            if (m == null) return false;
            if (m.state() == PoolState.RESERVED && !owner.equals(m.reservedBy())) return false;
            MapView view = Bukkit.getMap(id);
            if (view == null) {
                throw new IllegalStateException(
                        "bindToWall: MapView missing for map_id=" + id + " (wall=" + wallId + ")");
            }
            World actual = view.getWorld();
            if (actual == null || !actual.getUID().equals(expectedWorld.getUID())) {
                throw new IllegalStateException(
                        "bindToWall: world mismatch — map_id=" + id
                                + " in world='" + (actual == null ? "null" : actual.getName())
                                + "' but wall='" + wallId
                                + "' expects world='" + expectedWorld.getName() + "'");
            }
        }
        long now = System.currentTimeMillis();
        for (int id : mapIds) {
            PooledMap m = byId.get(id);
            if (m.state() == PoolState.FREE) removeFromFree(id, expectedWorld);
            PooledMap upd = m.withReserved(owner, now);
            byId.put(id, upd);
            persist(upd);
        }
        auditLog.record("POOL_BIND_WALL", null, null, null, null,
                Map.of("wall_id", wallId, "world", expectedWorld.getName(), "map_ids", mapIds));
        return true;
    }

    /**
     * 释放某 wall 持有的所有 RESERVED 地图 → FREE。{@code /canvas delete} 走此路径。
     * Returns released map ids.
     */
    public synchronized List<Integer> releaseWall(String wallId) {
        assertMainThread();
        Objects.requireNonNull(wallId);
        String owner = WALL_OWNER_PREFIX + wallId;
        long now = System.currentTimeMillis();
        List<Integer> released = new ArrayList<>();
        for (PooledMap m : new ArrayList<>(byId.values())) {
            if (m.state() == PoolState.RESERVED && owner.equals(m.reservedBy())) {
                PooledMap freed = m.withFree(now);
                byId.put(m.mapId(), freed);
                offerFreeByName(m.mapId(), m.world());
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
     * M16 P2.5：把单个 mapId 强制归还为 FREE（不论当前 owner）。
     *
     * <p>{@link moe.hikari.canvas.render.WallRestorer} 失败回滚专用：启动期 restore 某 wall
     * 中途任何一步炸（bind / compose），调用方必须把这一轮已经 bind 过的 mapId 全 release，
     * 否则它们留在 RESERVED 状态但 wall 也没真正恢复 → 下一轮 detectLeaks 才能扫到，
     * 中间窗口期视为"软泄漏"。直接走这条 API 立刻归还，干净。</p>
     *
     * @return true 若 mapId 存在并被归还；false 若 mapId 不在池里（或已 FREE）
     */
    public synchronized boolean releaseToFree(int mapId) {
        assertMainThread();
        PooledMap m = byId.get(mapId);
        if (m == null) return false;
        if (m.state() == PoolState.FREE) return false;
        long now = System.currentTimeMillis();
        PooledMap freed = m.withFree(now);
        byId.put(mapId, freed);
        offerFreeByName(mapId, m.world());
        persist(freed);
        auditLog.record("POOL_RELEASE_TO_FREE", null, null, null, null,
                Map.of("map_id", mapId, "prev_owner", String.valueOf(m.reservedBy())));
        return true;
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
            offerFreeByName(id, m.world());
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

    /**
     * M16-P2.7-B：主线程断言。{@link #initialize} / {@link #reserveForWall} / {@link #bindToWall}
     * / {@link #releaseWall} 都可能触发 {@link Bukkit#createMap} 或 {@link MapView} 操作，必须主线程。
     *
     * <p>纯单测环境（{@link Bukkit#getServer()} 为 null 或抛异常）下跳过断言，
     * 防止已有单测因新增断言变红。</p>
     */
    private static void assertMainThread() {
        try {
            if (Bukkit.getServer() == null) return;
        } catch (Throwable t) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "MapPool: must be called on Bukkit main thread (was: "
                            + Thread.currentThread().getName() + ")");
        }
    }

    private void expand(World world, int count) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            MapView view = Bukkit.createMap(world);
            new java.util.ArrayList<>(view.getRenderers()).forEach(view::removeRenderer);
            view.addRenderer(sharedRenderer);
            int id = view.getId();
            PooledMap rec = new PooledMap(id, PoolState.FREE, null, world.getName(), now, now);
            byId.put(id, rec);
            offerFree(id, world);
            persist(rec);
        }
        auditLog.record("POOL_EXPAND", null, null, null, null,
                Map.of("count", count, "world", world.getName(), "new_total", byId.size()));
    }

    /** 把 mapId 加入对应 world 的 FREE 队列。 */
    private void offerFree(int mapId, World world) {
        freeByWorld.computeIfAbsent(world.getUID(), k -> new ArrayDeque<>()).offer(mapId);
    }

    /**
     * 由 world 名字版本（用于 release 路径——PooledMap 只持 world name）。
     * 名字解析失败时打 warning 但不抛——map 仍然加进 byId，detectLeaks 后续可见。
     */
    private void offerFreeByName(int mapId, String worldName) {
        World w = worldName == null ? null : Bukkit.getWorld(worldName);
        if (w == null) {
            // World 已卸载（玩家删 multiverse world？）。fallback：放在一个"未知"桶里，
            // 用 zero UUID 作 key；下次 initialize 会被规整。
            freeByWorld.computeIfAbsent(new UUID(0L, 0L), k -> new ArrayDeque<>()).offer(mapId);
            log.warning("MapPool.offerFreeByName: world '" + worldName
                    + "' not loaded; map_id=" + mapId + " parked in unknown-world bucket");
            return;
        }
        offerFree(mapId, w);
    }

    private void removeFromFree(int mapId, World world) {
        Deque<Integer> q = freeByWorld.get(world.getUID());
        if (q != null) q.remove(Integer.valueOf(mapId));
        // 防御：mapId 实际可能在 unknown-world 桶里（重启后 world 顺序变化）；扫所有桶
        for (Deque<Integer> other : freeByWorld.values()) {
            if (other == q) continue;
            other.remove(Integer.valueOf(mapId));
        }
    }

    private int freeCountFor(World world) {
        Deque<Integer> q = freeByWorld.get(world.getUID());
        return q == null ? 0 : q.size();
    }

    private int totalFreeCount() {
        int total = 0;
        for (Deque<Integer> q : freeByWorld.values()) total += q.size();
        return total;
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
