package ac.haru.hikaricanvas.pool;

import ac.haru.hikaricanvas.render.HikariCanvasRenderer;
import ac.haru.hikaricanvas.storage.AuditLog;
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
 * <p>本层复用池中 {@link PoolState#FREE FREE} 地图刷像素，避免每次都 {@code Bukkit.createMap}
 * 致 idcounts.dat 膨胀。
 *
 * <p><b>两态：</b> {@code FREE} / {@code RESERVED}。{@code RESERVED} 的 owner 统一
 * 格式 {@code wall:<wall_id>}，wall 占的 map 一直占着直到 {@code /canvas delete} 显式释放。
 *
 * <p><b>多世界化：</b>池按 world 分桶。每个 world 一条 FREE 队列；
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
 * {@link #detectLeaks} 可异步调用：它会 mutate 池状态并写 DB（{@code persist}），但归还
 * 路径以 {@code allowBukkitFallback=false} 调用 {@link #worldNameToUid} 缓存，缓存 miss 时
 * 落 unknown-world 桶而<b>绝不</b>调 Bukkit API，
 * 且整段 {@code synchronized(this)} 与主线程 reserve/bind 互斥，DB 写在 HikariCP 连接上线程安全。
 */
public final class MapPool {

    /** RESERVED 时 owner 字段的标准前缀。 */
    public static final String WALL_OWNER_PREFIX = "wall:";

    /**
     * confirm() 在 reserve→bind 之间用的临时 wallId 前缀（owner 形如
     * {@code wall:pending-<uuid>}）。{@link #detectLeaks} 据此豁免正在创建中的预留，
     * 避免异步泄漏检测在窗口期误删。SessionManager.confirm 必须用此前缀生成临时 wallId。
     */
    public static final String PENDING_WALL_PREFIX = "pending-";

    /**
     * pending 豁免的有效期。超过这个时长还挂在 {@code wall:pending-*} 名下的预留，
     * 一律当垃圾回收。
     *
     * <p>真实的 reserve→bind 窗口是毫秒级（同一次 confirm 里的连续两步）。窗口内进程被
     * {@code kill -9} / 断电 / 别的插件崩服，这批行会以 RESERVED 状态留在库里，重启后
     * 既没有对应 walls 行、又因为 pending 前缀被泄漏检测无条件豁免 —— 没有任何路径能
     * 回收它们，等于地图 ID 永久泄漏（{@code idcounts.dat} 膨胀，本项目的核心风险）。
     * 60 秒对正常窗口是天文数字，对残留则足够快地收敛。</p>
     */
    public static final long PENDING_RESERVE_TTL_MS = 60_000L;

    private final Logger log;
    private final Jdbi jdbi;
    private final AuditLog auditLog;
    private final HikariCanvasRenderer sharedRenderer;
    private final int initialSize;
    private final int maxSize;
    private final MapBackend backend;

    private final Map<Integer, PooledMap> byId = new HashMap<>();
    /** FREE 队列按 world UUID 分桶。同一 world 的 FREE map 集中在一条 deque 里。 */
    private final Map<UUID, Deque<Integer>> freeByWorld = new HashMap<>();
    /**
     * world name → UUID 缓存。每次 {@link #offerFree(int, World)} 拿到 live
     * {@link World} 时填充。让 {@link #detectLeaks} 这类<b>异步</b>路径能用名字反查 UUID 桶，
     * 而<b>不</b>调 {@link Bukkit#getWorld(String)}（Bukkit API 主线程专用）。
     * 缓存 miss（极罕见：world 已卸载且从未在本进程 offerFree 过）才退化到 unknown 桶。
     */
    private final Map<String, UUID> worldNameToUid = new HashMap<>();

    public MapPool(Logger log, Jdbi jdbi, AuditLog auditLog,
                   HikariCanvasRenderer sharedRenderer,
                   int initialSize, int maxSize) {
        this(log, jdbi, auditLog, sharedRenderer, initialSize, maxSize, new BukkitMapBackend());
    }

    public MapPool(Logger log, Jdbi jdbi, AuditLog auditLog,
                   HikariCanvasRenderer sharedRenderer,
                   int initialSize, int maxSize, MapBackend backend) {
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
        this.backend = backend;
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

        // 收集 DB 写工作，循环结束后用单个事务批量执行，消除每行独立 useHandle
        // 各自 WAL fsync 的 O(N) 启动开销。Bukkit 调用（getMap / renderer / offerFree）
        // 仍逐行做（主线程，不能进 DB 事务），但纯内存。
        List<Integer> orphanDeletes = new ArrayList<>();
        List<PooledMap> normalizedPersists = new ArrayList<>();

        int worldNotLoaded = 0;
        for (PooledMap rec : persisted) {
            World mapWorld = backend.installRenderer(rec.mapId(), sharedRenderer);
            if (mapWorld == null) {
                // installRenderer 返 null 有两种含义，必须区分：
                //   ① MapView 不存在        → 真孤儿，DELETE pool 行
                //   ② MapView 在，但其 world 未加载（MapView.getWorld() 返 null）
                //
                // ② 在真实服上很常见：initialize 在 onEnable 同步执行，而 Multiverse 等插件
                // 管理的世界此时往往还没加载。原实现把 ② 也 DELETE，后果是 mapId 从池簿记
                // 永久消失（不再复用 → 重新 createMap → idcounts.dat 膨胀，项目核心风险），
                // 且该世界的 wall 因 byId 无此 mapId 使 bindToWall 返 false，永久打不开、
                // 每次重启复现。审计名 POOL_ORPHAN_ROW 还让误删看起来像正常回收。
                if (backend.hasMapView(rec.mapId())) {
                    worldNotLoaded++;
                    // 保留 pool 行 + 登记进 byId（簿记完整，detectLeaks 也能看到）。
                    // FREE 的按名字进桶：world 名解析不到就落 unknown-world 桶，
                    // 世界加载后由 reserveForWall → reclaimUnknownBucketForWorld 自动回迁。
                    PooledMap kept = enforceInvariant(rec, now);
                    if (!kept.equals(rec)) {
                        normalized++;
                        normalizedPersists.add(kept);
                    }
                    byId.put(kept.mapId(), kept);
                    if (kept.state() == PoolState.FREE) {
                        offerFreeByName(kept.mapId(), kept.world(), false);
                    }
                    recovered++;
                    continue;
                }
                missingMapView++;
                orphanDeletes.add(rec.mapId());
                auditLog.record("POOL_ORPHAN_ROW", null, null, null, null,
                        Map.of("map_id", rec.mapId(), "state", rec.state().name()));
                continue;
            }
            PooledMap normalizedRec = enforceInvariant(rec, now);
            if (!normalizedRec.equals(rec)) {
                normalized++;
                normalizedPersists.add(normalizedRec);
            }
            byId.put(normalizedRec.mapId(), normalizedRec);
            if (normalizedRec.state() == PoolState.FREE) {
                offerFree(normalizedRec.mapId(), mapWorld);
            }
            recovered++;
        }

        // 单事务批量 flush orphan DELETE + normalized INSERT/UPDATE。失败仅 SEVERE
        // 日志（保持原 persist 吞异常的纪律），不阻断启动——in-memory 状态已就绪，下次
        // 启动 / detectLeaks 可再收敛。
        flushInitDbWork(orphanDeletes, normalizedPersists);

        log.info(String.format(
                "MapPool recovered %d entries (free=%d reserved=%d; missing MapView=%d; "
                        + "world-not-loaded=%d; normalized=%d)",
                recovered,
                countByState(PoolState.FREE),
                countByState(PoolState.RESERVED),
                missingMapView,
                worldNotLoaded,
                normalized));
        if (worldNotLoaded > 0) {
            log.info("MapPool: " + worldNotLoaded + " map(s) kept although their world is not"
                    + " loaded yet (Multiverse-style late world load); rows preserved,"
                    + " they rejoin the correct bucket once the world loads");
        }

        // 补齐到 initial-size（只补 FREE，按 perWorldInitial 分配；剩余去 defaultWorld）
        int totalFree = totalFreeCount();
        int totalInitialBudget = initialSize;

        // 阶段 1：per-world 显式补齐
        int totalNewlyCreated = 0;
        for (Map.Entry<String, Integer> e : perWorldInitial.entrySet()) {
            World w = backend.worldByName(e.getKey());
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

    // ---------- wall 模型主路径 ----------

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

        // 先把 unknown-world 桶里实际属于本 world 的 map 迁回。这些是 world 卸载期间
        // offerFreeByName 暂存进 zero-UUID 桶的"僵尸 FREE"——world 重新加载后名字可重新匹配，
        // 不该白白吃掉预算 / 触发不必要的 expand。
        reclaimUnknownBucketForWorld(world);

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

        // 借出与 bindToWall 走同一条持久化纪律：先在单事务里把全部 map 落盘
        // （all-or-nothing，失败抛出），DB 成功后才改内存。
        // 原实现在循环里逐张 persist（吞异常只打 SEVERE），中段失败会留下"内存已借出、
        // DB 还写着 FREE"的分叉：重启后这批 map 回到 FREE 队列被别的 wall 借走，
        // 而先前那面 wall 的 map_ids 仍指向它们 → 两面墙共用同一张地图、像素互相覆盖。
        List<Integer> picked = new ArrayList<>(count);
        List<PooledMap> updates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Integer mapId = queue == null ? null : queue.poll();
            if (mapId == null) {
                // 不该发生（上面已按 count 校验 / 扩容过）。把已摘下的放回队列后爆出来，
                // 绝不半途借出。
                for (Integer back : picked) queue.offer(back);
                throw new IllegalStateException(
                        "reserveForWall: FREE queue for world '" + world.getName()
                                + "' ran dry after capacity check (wanted " + count + ")");
            }
            picked.add(mapId);
            updates.add(byId.get(mapId).withReserved(owner, now));
        }
        try {
            persistAllStrict(updates);
        } catch (RuntimeException e) {
            // DB 没写成 → 内存一并回滚（map 放回 FREE 队列，byId 一个字节没动）
            for (Integer back : picked) queue.offer(back);
            throw e;
        }
        for (PooledMap upd : updates) {
            byId.put(upd.mapId(), upd);
            out.add(upd.mapId());
        }
        auditLog.record("POOL_RESERVE", null, null, null, null,
                Map.of("wall_id", wallId, "count", count, "world", world.getName(), "map_ids", out));
        return out;
    }

    /**
     * 把已有 {@code mapIds} 绑定到 {@code wallId}（启动恢复 / `/canvas open` 路径用）。
     * 接受的源状态：FREE 或已是该 wall 的 RESERVED；其它（被别 wall 持有）拒绝返回 false。
     *
     * <p><b>world 校验：</b> 每个 mapId 对应的 MapView 必须属于传入的 {@code expectedWorld}；
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
            World actual = backend.mapWorld(id);
            if (actual == null) {
                throw new IllegalStateException(
                        "bindToWall: MapView missing for map_id=" + id + " (wall=" + wallId + ")");
            }
            if (!actual.getUID().equals(expectedWorld.getUID())) {
                throw new IllegalStateException(
                        "bindToWall: world mismatch — map_id=" + id
                                + " in world='" + actual.getName()
                                + "' but wall='" + wallId
                                + "' expects world='" + expectedWorld.getName() + "'");
            }
        }
        long now = System.currentTimeMillis();
        // 两阶段，杜绝内存/DB 分叉。① 先在单事务里把全部 map upsert 落盘（all-or-nothing，
        // 失败抛异常 → byId 完全未动，调用方回滚链处理）；② DB 成功后才改内存（纯内存操作不会失败）。
        List<PooledMap> upds = new ArrayList<>(mapIds.size());
        for (int id : mapIds) {
            upds.add(byId.get(id).withReserved(owner, now));
        }
        persistAllStrict(upds);
        for (PooledMap upd : upds) {
            int id = upd.mapId();
            if (byId.get(id).state() == PoolState.FREE) removeFromFree(id, expectedWorld);
            byId.put(id, upd);
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
                offerFreeByName(m.mapId(), m.world(), true);
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
     * 把单个 mapId 强制归还为 FREE（不论当前 owner）。
     *
     * <p>{@link ac.haru.hikaricanvas.render.WallRestorer} 失败回滚专用：启动期 restore 某 wall
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
        offerFreeByName(mapId, m.world(), true);
        persist(freed);
        auditLog.record("POOL_RELEASE_TO_FREE", null, null, null, null,
                Map.of("map_id", mapId, "prev_owner", String.valueOf(m.reservedBy())));
        return true;
    }

    /**
     * 泄漏检测：RESERVED 但 owner 非 "wall:*" 格式（旧版残留），或者 walls 表无对应行。
     * 当前简化策略：扫所有 RESERVED；非 "wall:" 前缀直接归还（视为旧 session: / draft: 残留）。
     * 后续可加"wall_id 不在 walls 表"的二次校验；调用方负责传 liveWallIds 集合。
     *
     * <p><b>异步安全：</b> 本方法可异步调用——内部 {@link #offerFreeByName}
     * 以 {@code allowBukkitFallback=false} 调用，保证缓存 miss 时直接落 unknown-world 桶，
     * <b>绝不调用</b> {@link Bukkit#getWorld(String)}（Bukkit API 主线程专用）。
     * 缓存命中（{@link #worldNameToUid} 有该 world）时走 UUID 桶直接归还，零 Bukkit 调用。
     * unknown-world 桶里的 map 在下次主线程 {@link #reserveForWall} →
     * {@link #reclaimUnknownBucketForWorld} 时自动回迁正确桶。
     * {@code persist} 的 DB 写在 HikariCP 连接上线程安全。
     * 整段 {@code synchronized(this)} 与主线程 reserve/bind 互斥。</p>
     *
     * <p><b>pending 豁免：</b> 跳过 {@link #PENDING_WALL_PREFIX pending-} owner —— confirm() 在
     * reserve（owner=wall:pending-&lt;uuid&gt;）与最终 bind 到真 wallId 之间有一个短暂窗口，
     * 此间 maps 是 RESERVED 但 wallId 是临时的 pending-*，不在 liveWallIds 里。若不豁免，异步
     * detectLeaks 恰好在该窗口运行会把正在创建的 wall 的 maps 强制 FREE，造成 confirm 内部
     * bind race / 像素错乱。pending-* 是 transient 中间态，<b>不参与泄漏判定</b>——但豁免
     * 有 {@link #PENDING_RESERVE_TTL_MS} 的有效期，超期的残留照常回收（见该常量说明）。</p>
     *
     * <p>本重载不带 live 集的读取时刻，等价于"假定快照绝对新鲜"（无 reserve 竞态豁免），
     * 供测试与单线程调用方使用。<b>周期泄漏任务必须用
     * {@link #detectLeaks(java.util.Set, long)}</b>。</p>
     */
    public synchronized int detectLeaks(java.util.Set<String> liveWallIds) {
        return detectLeaks(liveWallIds, Long.MAX_VALUE);
    }

    /**
     * {@link #detectLeaks(java.util.Set)} 带快照时刻的版本。
     *
     * @param liveWallIds     walls 表快照
     * @param liveSnapshotAt  <b>读 walls 表之前</b>取的 {@code System.currentTimeMillis()}。
     *                        比这个时刻更晚被借出的 RESERVED 地图一律跳过本轮。
     *
     * <p><b>为什么需要这个参数（快照 TOCTOU）：</b>快照是在池锁外拍的，而
     * {@code /canvas confirm} 在主线程按 reserve(pending) → INSERT walls → bind(wall:新id)
     * 的顺序推进。若快照恰好拍在 INSERT 之前、而 detectLeaks 进锁又在 bind 之后，新墙的
     * wall_id 既不在快照里、owner 也已不是 pending-* → 这批刚借出的地图被判泄漏、强制 FREE
     * 并落盘；下一次 confirm 再把同一 mapId 借给别的墙 → 两面墙共用一张地图、像素互相覆盖，
     * 而原墙的 map_ids 指向已被抢占的地图，状态永久错乱。</p>
     *
     * <p>用"借出时刻 vs 快照时刻"判定即可关掉这个窗口：{@code lastUsedAt} 在 reserve / bind
     * 时被刷新，晚于快照就说明"这张地图是在我们看 walls 表之后才借出去的"，此时快照对它
     * 没有发言权。跳过一轮的代价是 5 分钟后再判，代价可忽略；反过来误删则是数据损坏。</p>
     */
    public synchronized int detectLeaks(java.util.Set<String> liveWallIds, long liveSnapshotAt) {
        return detectLeaks(liveWallIds, liveSnapshotAt, System.currentTimeMillis());
    }

    /**
     * 注入"当前时刻"的版本，package-private 只为测试用 —— pending 豁免的 TTL 判定要跨
     * 几十秒，测试不可能真等。生产路径一律走 {@link #detectLeaks(java.util.Set, long)}。
     */
    synchronized int detectLeaks(java.util.Set<String> liveWallIds, long liveSnapshotAt, long now) {
        // 防呆：live 集合为空但池里确有 wall:* 拥有的 RESERVED map —— 几乎必然是「读 walls
        // 表失败」而不是「真的一面墙都没有」。此时若照常执行，这些在用地图会被判泄漏、强制
        // FREE 并落盘，随后被新墙复用 → 旧墙 ItemFrame 显示新墙像素（跨墙串台，全服级损坏）。
        // 宁可漏检一轮（5 分钟后再来）也不能误删全服在用地图。
        //
        // **只抑制「wall 不在 live 集」这一条判定**：owner 为 null / 旧版 session: / draft:
        // 前缀的记录是无歧义的垃圾，与 walls 表读没读到无关，照常回收。
        //
        // 调用方另有一道闸：WallRepo.loadAllWallIds() 读失败返回 Optional.empty()，
        // HikariCanvas 的周期任务据此直接跳过本轮。两道闸互不依赖。
        //
        // 不做「较上轮骤降则拒绝」的启发式：loadAllWallIds 只读 wall_id 列，要么完整读到
        // 要么整个失败，不存在「部分行读丢」的中间态，故骤降只可能来自服主真的删了墙。
        boolean liveSetUntrustworthy = liveWallIds != null && liveWallIds.isEmpty()
                && hasWallOwnedReserved();
        int suppressed = 0;
        int reservedAfterSnapshot = 0;
        int stalePending = 0;
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
            // pending-* 是 confirm() reserve→bind 之间的临时 owner，窗口内豁免泄漏检测；
            // 超过 TTL 说明那次 confirm 没能走到 bind（进程被杀 / 崩服），残留必须回收，
            // 否则没有任何路径能拿回这些地图。
            if (wallId.startsWith(PENDING_WALL_PREFIX)) {
                if (now - m.lastUsedAt() <= PENDING_RESERVE_TTL_MS) continue;
                stalePending++;
                leaked.add(m.mapId());
                continue;
            }
            if (liveWallIds != null && !liveWallIds.contains(wallId)) {
                if (liveSetUntrustworthy) {
                    // live 集空 = 大概率读表失败，不是"这面墙真没了"。跳过，下轮再判。
                    suppressed++;
                    continue;
                }
                if (m.lastUsedAt() >= liveSnapshotAt) {
                    // 这张地图是在快照之后才借出去的 —— 快照里没有它的 wall 是理所当然，
                    // 不能据此判泄漏（见方法 javadoc 的 TOCTOU 说明）。下轮再判。
                    reservedAfterSnapshot++;
                    continue;
                }
                leaked.add(m.mapId());
            }
        }
        if (reservedAfterSnapshot > 0) {
            log.info("MapPool.detectLeaks: skipped " + reservedAfterSnapshot
                    + " map(s) reserved after the walls-table snapshot was taken"
                    + " (they will be judged next round)");
        }
        if (stalePending > 0) {
            log.warning("MapPool.detectLeaks: " + stalePending
                    + " map(s) stuck on a 'pending-*' reservation older than "
                    + PENDING_RESERVE_TTL_MS + "ms — reclaiming (a wall creation was"
                    + " interrupted, most likely by a hard shutdown)");
            auditLog.record("POOL_PENDING_EXPIRED", null, null, null, null,
                    Map.of("count", stalePending, "ttl_ms", PENDING_RESERVE_TTL_MS));
        }
        if (suppressed > 0) {
            log.severe("MapPool.detectLeaks: live wall set is EMPTY but " + suppressed
                    + " map(s) are still owned by wall:* — refusing to reclaim them"
                    + " (almost certainly a failed walls-table read, not an actually empty"
                    + " server). Skipping those this round.");
            auditLog.record("POOL_LEAK_SCAN_SKIPPED", null, null, null, null,
                    Map.of("reason", "empty_live_set", "suppressed", suppressed));
        }
        for (int id : leaked) {
            PooledMap m = byId.get(id);
            PooledMap freed = m.withFree(now);
            byId.put(id, freed);
            // detectLeaks 在异步线程；缓存 miss 时禁止 Bukkit fallback——
            // Bukkit.getWorld 是主线程专用 API。map 暂存 unknown-world 桶，
            // 下次主线程 reserveForWall → reclaimUnknownBucketForWorld 可回收。
            offerFreeByName(id, m.world(), false);
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

    /** 池里是否有 {@code wall:<真 wallId>} 拥有的 RESERVED map（pending-* 不算）。 */
    private boolean hasWallOwnedReserved() {
        for (PooledMap m : byId.values()) {
            if (m.state() != PoolState.RESERVED) continue;
            String owner = m.reservedBy();
            if (owner == null || !owner.startsWith(WALL_OWNER_PREFIX)) continue;
            if (owner.substring(WALL_OWNER_PREFIX.length()).startsWith(PENDING_WALL_PREFIX)) continue;
            return true;
        }
        return false;
    }

    public synchronized Stats stats() {
        return new Stats(
                byId.size(),
                countByState(PoolState.FREE),
                countByState(PoolState.RESERVED));
    }

    public record Stats(int total, int free, int reserved) {}

    /**
     * 池容量上限。直接返构造期注入的 {@code maxSize} final 字段（{@code map-pool.max-size} config）。
     */
    public synchronized int maxSize() {
        return maxSize;
    }

    /**
     * 按 world 统计当前 FREE map 数。
     *
     * <p>遍历 {@link #freeByWorld}（按 world UUID 分桶的 FREE 队列）→ 用桶内任一 mapId 的
     * {@link PooledMap#world()} 名字作 key（同一 world 桶内 PooledMap.world() 一致）。
     * unknown-world 桶（zero UUID，world 卸载窗口期暂存）固定标 {@code "<unknown>"}。
     * 空桶（曾有 map 后全被借出）跳过不计。</p>
     *
     * @return world 名 → 该 world FREE map 数（{@link java.util.LinkedHashMap}，无序枚举）。
     */
    public synchronized Map<String, Integer> byWorldStats() {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        UUID unknownKey = new UUID(0L, 0L);
        for (Map.Entry<UUID, Deque<Integer>> e : freeByWorld.entrySet()) {
            Deque<Integer> q = e.getValue();
            if (q == null || q.isEmpty()) continue;
            String name;
            if (unknownKey.equals(e.getKey())) {
                name = "<unknown>";
            } else {
                // 桶内任一 mapId 的 PooledMap.world() 名字即该 world 名；同桶名字一致。
                name = "<unknown>";
                for (Integer id : q) {
                    PooledMap m = byId.get(id);
                    if (m != null && m.world() != null) {
                        name = m.world();
                        break;
                    }
                }
            }
            out.merge(name, q.size(), Integer::sum);
        }
        return out;
    }

    // ----- 内部实现 -----

    /**
     * 主线程断言。{@link #initialize} / {@link #reserveForWall} / {@link #bindToWall}
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
        // 钳到 maxSize 上限。initialize 的 per-world 预热（perWorldInitial 求和可能 >
        // maxSize）若不钳，会让池超过 max 不变式。reserveForWall 路径已提前校验 maxSize，
        // 这里的钳位主要保护 initialize 预热路径，并作为 expand 的单点不变式守卫。
        int room = Math.max(0, maxSize - byId.size());
        if (count > room) {
            log.warning("MapPool.expand: requested " + count + " new maps in world '"
                    + world.getName() + "' but only " + room + " slots left under max="
                    + maxSize + " (current total=" + byId.size() + "); clamping to " + room
                    + ". Check map-pool.per-world config sums <= max-size.");
            count = room;
        }
        int created = 0;
        for (int i = 0; i < count; i++) {
            int id = backend.createMap(world, sharedRenderer);
            PooledMap rec = new PooledMap(id, PoolState.FREE, null, world.getName(), now, now);
            // 先持久化再注册到 in-memory 结构。persist 失败时不 byId.put/offerFree——
            // 让该 map 成为一个"DB 不知道、内存也不知道"的真孤儿（下次 initialize/detectLeaks
            // 通过 Bukkit.getMap 扫描可发现并规整），而不是"内存有、DB 没有"的幽灵（重启即丢，
            // 借出去后 last_used 永不落盘，LRU/恢复逻辑全乱）。persist 在此路径必须抛而非吞。
            persistStrict(rec);
            byId.put(id, rec);
            offerFree(id, world);
            created++;
        }
        auditLog.record("POOL_EXPAND", null, null, null, null,
                Map.of("count", created, "world", world.getName(), "new_total", byId.size()));
    }

    /** 把 mapId 加入对应 world 的 FREE 队列。 */
    private void offerFree(int mapId, World world) {
        // 记下 name→UUID，让异步 detectLeaks 路径可名字反查不调 Bukkit。
        worldNameToUid.put(world.getName(), world.getUID());
        freeByWorld.computeIfAbsent(world.getUID(), k -> new ArrayDeque<>()).offer(mapId);
    }

    /**
     * 由 world 名字版本（用于 release 路径——PooledMap 只持 world name）。
     * 名字解析失败时打 warning 但不抛——map 仍然加进 byId，detectLeaks 后续可见。
     *
     * <p>先查 {@link #worldNameToUid} 内存缓存（已 offerFree 过的 world 都有），
     * 命中即直接入对应 UUID 桶，<b>零 Bukkit 调用</b>——这是让 {@link #detectLeaks} 能在异步
     * 线程安全归还的关键。</p>
     *
     * <p>{@code allowBukkitFallback} 控制缓存 miss 时的行为：</p>
     * <ul>
     *   <li>{@code true}（主线程路径：{@link #releaseWall} / {@link #releaseToFree}）：
     *       缓存 miss 退化到 {@link Bukkit#getWorld}，合法（主线程调用）；并顺便填充缓存。</li>
     *   <li>{@code false}（异步路径：{@link #detectLeaks}）：缓存 miss 时<b>禁止</b>调用
     *       {@link Bukkit#getWorld}（Bukkit API 主线程专用）——直接暂存 unknown-world 桶，
     *       等下次主线程 {@link #reserveForWall} → {@link #reclaimUnknownBucketForWorld} 回收。</li>
     * </ul>
     */
    private void offerFreeByName(int mapId, String worldName, boolean allowBukkitFallback) {
        if (worldName != null) {
            UUID cached = worldNameToUid.get(worldName);
            if (cached != null) {
                freeByWorld.computeIfAbsent(cached, k -> new ArrayDeque<>()).offer(mapId);
                return;
            }
        }
        // 缓存 miss：异步路径（detectLeaks）禁止 Bukkit 调用，直接落 unknown-world 桶。
        if (!allowBukkitFallback) {
            freeByWorld.computeIfAbsent(new UUID(0L, 0L), k -> new ArrayDeque<>()).offer(mapId);
            log.warning("MapPool.offerFreeByName: world '" + worldName
                    + "' not in cache (async path, Bukkit.getWorld skipped); map_id=" + mapId
                    + " parked in unknown-world bucket");
            return;
        }
        World w = worldName == null ? null : backend.worldByName(worldName);
        if (w == null) {
            // World 已卸载（玩家删 multiverse world？）。fallback：放在一个"未知"桶里，
            // 用 zero UUID 作 key；下次 initialize / reserveForWall 会被规整。
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

    /**
     * 把 unknown-world 桶（zero UUID）里 {@link PooledMap#world()} 名字 == 目标 world
     * 名的 FREE map 迁回该 world 的 UUID 桶。world 卸载窗口期 offerFreeByName 把归还的 map
     * 暂存到 zero 桶；world 重新加载后调本方法即可回收，避免僵尸 FREE 永久占预算。
     * 调用方须持 {@code synchronized(this)}。
     */
    private void reclaimUnknownBucketForWorld(World world) {
        Deque<Integer> unknown = freeByWorld.get(new UUID(0L, 0L));
        if (unknown == null || unknown.isEmpty()) return;
        worldNameToUid.put(world.getName(), world.getUID());
        String targetName = world.getName();
        List<Integer> moved = new ArrayList<>();
        for (Integer id : new ArrayList<>(unknown)) {
            PooledMap m = byId.get(id);
            if (m != null && targetName.equals(m.world())) {
                unknown.remove(id);
                freeByWorld.computeIfAbsent(world.getUID(), k -> new ArrayDeque<>()).offer(id);
                moved.add(id);
            }
        }
        if (!moved.isEmpty()) {
            log.info("MapPool: reclaimed " + moved.size() + " map(s) from unknown-world bucket "
                    + "back to world '" + targetName + "'. map_ids=" + moved);
        }
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
                // wall:pending-* 是 confirm 内部 reserve→bind 的中间态，活不过一次重启：
                // 能读到它只说明上次 confirm 被硬中断（kill -9 / 断电 / 崩服）。留着的话
                // detectLeaks 的 pending 豁免会让它永远回不来（地图 ID 永久泄漏）。
                if (rec.reservedBy().startsWith(WALL_OWNER_PREFIX + PENDING_WALL_PREFIX)) {
                    log.warning("pool_maps row map_id=" + rec.mapId()
                            + " is still held by an unfinished wall creation ('"
                            + rec.reservedBy() + "'); returning it to the FREE pool");
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

    private static final String PERSIST_SQL =
            "INSERT INTO pool_maps (map_id, state, reserved_by, world, created_at, last_used_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT(map_id) DO UPDATE SET "
                    + "state = excluded.state, "
                    + "reserved_by = excluded.reserved_by, "
                    + "world = excluded.world, "
                    + "last_used_at = excluded.last_used_at";

    /** named-param 版本，给 {@link #flushInitDbWork} 的 {@code createUpdate} 批量路径用。 */
    private static final String PERSIST_NAMED_SQL =
            "INSERT INTO pool_maps (map_id, state, reserved_by, world, created_at, last_used_at) "
                    + "VALUES (:map_id, :state, :reserved_by, :world, :created_at, :last_used_at) "
                    + "ON CONFLICT(map_id) DO UPDATE SET "
                    + "state = excluded.state, "
                    + "reserved_by = excluded.reserved_by, "
                    + "world = excluded.world, "
                    + "last_used_at = excluded.last_used_at";

    private void persist(PooledMap m) {
        try {
            persistStrict(m);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to persist pool_maps row for map_id=" + m.mapId(), e);
        }
    }

    /**
     * 把 {@link #initialize} 恢复阶段收集的孤儿行删除 + 规整行 upsert 收进<b>单个</b>
     * 事务执行，取代每行独立 {@code useHandle}（各自 WAL fsync）的 O(N) 启动写压力——单事务
     * 只在 commit 时 fsync 一次。失败吞为 SEVERE 日志（与 {@link #persist} 同纪律），不阻断启动。
     */
    private void flushInitDbWork(List<Integer> orphanDeletes, List<PooledMap> upserts) {
        if (orphanDeletes.isEmpty() && upserts.isEmpty()) return;
        try {
            jdbi.useTransaction(h -> {
                for (int id : orphanDeletes) {
                    h.createUpdate("DELETE FROM pool_maps WHERE map_id = :id")
                            .bind("id", id)
                            .execute();
                }
                for (PooledMap m : upserts) {
                    h.createUpdate(PERSIST_NAMED_SQL)
                            .bind("map_id", m.mapId())
                            .bind("state", m.state().name())
                            .bind("reserved_by", m.reservedBy())
                            .bind("world", m.world())
                            .bind("created_at", m.createdAt())
                            .bind("last_used_at", m.lastUsedAt())
                            .execute();
                }
            });
        } catch (Exception e) {
            log.log(Level.SEVERE, "MapPool.initialize: batch DB flush failed (orphanDeletes="
                    + orphanDeletes.size() + ", upserts=" + upserts.size() + ")", e);
        }
    }

    /**
     * 持久化并向调用方<b>抛出</b> DB 异常（不吞）。用于 {@link #expand} 的"先落盘再注册"
     * 路径——新建 map 的 DB 写若失败，必须让调用方知道并避免把 map 注册进内存结构。
     */
    private void persistStrict(PooledMap m) {
        jdbi.useHandle(h -> h.execute(PERSIST_SQL,
                m.mapId(), m.state().name(), m.reservedBy(), m.world(),
                m.createdAt(), m.lastUsedAt()));
    }

    /**
     * <b>地图借出路径统一走这里</b>（{@link #reserveForWall} + {@link #bindToWall}）——把多张 map 的
     * upsert 收进<b>单个事务</b>，失败<b>抛出</b>（不吞）。保证 all-or-nothing：要么全部落盘、
     * 要么全不落盘并抛异常让调用方回滚，杜绝 {@link #persist} 那种"中段失败、内存全改而
     * DB 部分改"的分叉。
     *
     * <p>归还路径（{@link #releaseWall} / {@link #releaseToFree} / {@link #detectLeaks}）仍用
     * 吞异常的 {@link #persist}：那边内存/DB 分叉的方向是"内存 FREE、DB 还写着 RESERVED"，
     * 重启后地图仍归原 wall，不会串给别人，风险等级完全不同。</p>
     */
    private void persistAllStrict(List<PooledMap> maps) {
        jdbi.useTransaction(h -> {
            for (PooledMap m : maps) {
                h.execute(PERSIST_SQL, m.mapId(), m.state().name(), m.reservedBy(),
                        m.world(), m.createdAt(), m.lastUsedAt());
            }
        });
    }
}
