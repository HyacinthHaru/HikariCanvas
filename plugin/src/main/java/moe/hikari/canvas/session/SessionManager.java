package moe.hikari.canvas.session;

import moe.hikari.canvas.deploy.WallResolver;
import moe.hikari.canvas.pool.MapPool;
import moe.hikari.canvas.pool.PoolExhaustedException;
import moe.hikari.canvas.render.HikariCanvasRenderer;
import moe.hikari.canvas.state.EditSession;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.storage.AuditLog;
import moe.hikari.canvas.storage.WallRepo;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 会话生命周期核心。契约见 {@code docs/architecture.md §3}。
 *
 * <p><b>并发模型（M16-P6.7 重构）：</b></p>
 * <ul>
 *   <li>三索引 {@code byId} / {@code byPlayer} / {@code byWall} 全部 {@link ConcurrentHashMap}：
 *       单 key 读写 lock-free；任意线程可并发查询无阻塞。</li>
 *   <li>跨 map 的复合写（confirm / cancel / open / deleteWall / forget）用 {@link #writeLock}
 *       串行化，保证多 map mutate 原子可见——避免出现 {@code byPlayer} 已 put 但
 *       {@code byId} 还没 put 的中间态被另一线程读到。</li>
 *   <li><b>持锁中严禁调 Bukkit API</b>（{@code Bukkit.getPlayer} / {@code getWorld} /
 *       {@link MapPool} 的 {@code createMap} 等）：Bukkit 主线程若同时在排其它任务，再来
 *       等 SessionManager 锁就死锁。本类的所有 MapPool / Bukkit 调用都在
 *       {@link #writeLock} <b>外</b>执行；持锁阶段只做纯 map mutate。</li>
 *   <li>{@link #forgetHooks} 回调也在锁外触发，防止 hook 反向锁定造成死锁。</li>
 * </ul>
 *
 * <p><b>主线程约束：</b> 涉及 {@link MapPool#reserveForWall} / {@link MapPool#bindToWall}
 * （可能 {@code Bukkit.createMap}）的方法必须在 Bukkit 主线程调用——即 {@link #confirm} /
 * {@link #open} / {@link #cancel} / {@link #deleteWall}。这些方法用 {@link #assertMainThread}
 * 自检。只读查询（{@link #byId(String)} / {@link #liveSessionIds} 等）可从异步调用。</p>
 *
 * <p>排他约束：</p>
 * <ul>
 *   <li>每玩家最多 1 个活跃会话（含 {@link SessionState#SELECTING}）</li>
 *   <li>每墙面排他锁 {@link WallKey}——{@code SELECTING → ISSUED} 时获取、
 *       cancel/commit 时释放</li>
 * </ul>
 */
public final class SessionManager {

    private final Logger log;
    private final MapPool mapPool;
    private final WallResolver wallResolver;
    private final AuditLog auditLog;
    private final WallRepo wallRepo;
    /** M15.3 P0-25：deleteWall 释放 map 后清除像素缓存，防 mapId 复用导致跨 wall 像素泄漏。可空（不强制依赖）。 */
    private final HikariCanvasRenderer canvasRenderer;

    /** M16-P6.7：lock-free 单 key read/write，跨 map 复合写通过 {@link #writeLock} 串行化。 */
    private final Map<String, Session> byId = new ConcurrentHashMap<>();
    private final Map<UUID, String> byPlayer = new ConcurrentHashMap<>();
    private final Map<WallKey, String> byWall = new ConcurrentHashMap<>();

    /**
     * M16-P6.7：守护多 map 的复合 mutate（confirm / cancel / open / deleteWall / forget）。
     * 不守护 Bukkit / MapPool 调用——那些必须在锁外执行（详见 class javadoc）。
     */
    private final ReentrantLock writeLock = new ReentrantLock();

    /** session forget 时调用，用于让 throttler / rate-limiter / WS ctx map 等清状态。 */
    private final List<Consumer<String>> forgetHooks = new CopyOnWriteArrayList<>();

    /**
     * 0.4.0-P1-C：wall 删除时调用，让 VariableStore 清掉 wall 的倒排索引（避免被删 wall 仍
     * 出现在 referencedByWalls 列表里、变量值变更触发已不存在 wall 的 dirty）。
     * callback 接收被删的 wallId；线程安全（CopyOnWriteArrayList）。
     */
    private final List<Consumer<String>> wallDeleteHooks = new CopyOnWriteArrayList<>();

    /** 注册 session forget 监听。callback 接收被 forget 的 sessionId。线程安全。 */
    public void addForgetHook(Consumer<String> hook) {
        forgetHooks.add(hook);
    }

    /**
     * 0.4.0-P1-C：注册 wall 删除监听。callback 在 {@link #deleteWall} 完成 map 释放 + DB 删行后
     * 触发，按 hook 注册顺序异常隔离逐个调用。
     */
    public void addWallDeleteHook(Consumer<String> hook) {
        wallDeleteHooks.add(hook);
    }

    public SessionManager(Logger log, MapPool mapPool, WallResolver wallResolver,
                          AuditLog auditLog, WallRepo wallRepo) {
        this(log, mapPool, wallResolver, auditLog, wallRepo, null);
    }

    /**
     * M15.3 P0-25：带 renderer 引用的构造器。生产路径走此构造，测试可走 5-arg 版本传 null。
     */
    public SessionManager(Logger log, MapPool mapPool, WallResolver wallResolver,
                          AuditLog auditLog, WallRepo wallRepo,
                          HikariCanvasRenderer canvasRenderer) {
        this.log = log;
        this.mapPool = mapPool;
        this.wallResolver = wallResolver;
        this.auditLog = auditLog;
        this.wallRepo = wallRepo;
        this.canvasRenderer = canvasRenderer;
    }

    // ---------- SELECTING 阶段 ----------

    public sealed interface BeginResult {
        record Ok(Session session) implements BeginResult {}
        record AlreadyHasSession(Session existing) implements BeginResult {}
    }

    /** 开启新会话（{@code /canvas edit} 或持 Wand 首次点击）。 */
    public BeginResult beginSelecting(UUID playerUuid, String playerName) {
        Objects.requireNonNull(playerUuid);
        long now = System.currentTimeMillis();
        String sessionId = UUID.randomUUID().toString();
        Session s = new Session(sessionId, playerUuid, playerName, now);
        // M16-P6.7：跨 byPlayer + byId 双 put，用 writeLock 串行化（race：两玩家同时 edit）
        writeLock.lock();
        try {
            String existingId = byPlayer.get(playerUuid);
            if (existingId != null) {
                return new BeginResult.AlreadyHasSession(byId.get(existingId));
            }
            byId.put(sessionId, s);
            byPlayer.put(playerUuid, sessionId);
        } finally {
            writeLock.unlock();
        }
        auditLog.record("SESSION_BEGIN", playerUuid.toString(), playerName, sessionId, null,
                Map.of("state", s.state().name()));
        return new BeginResult.Ok(s);
    }

    /** 左键点击记为 pos1；右键点击记为 pos2。 */
    public void recordPos(String sessionId, boolean isFirstCorner, Block block, BlockFace face) {
        Session s = requireState(sessionId, SessionState.SELECTING);
        if (isFirstCorner) s.pos1(block, face);
        else s.pos2(block, face);
    }

    /**
     * M5-D8：清空已选 pos1/pos2，玩家在 SELECTING 状态下隐式 reselect。
     * {@code /canvas edit} 在已 SELECTING 时调用此方法替代抛 AlreadyHasSession。
     */
    public boolean resetSelection(String sessionId) {
        Session s = byId.get(sessionId);
        if (s == null || s.state() != SessionState.SELECTING) return false;
        s.clearPos();
        return true;
    }

    /**
     * 对当前已记的 pos1 / pos2 调用 {@link WallResolver} 做预览（不改状态）。
     * 用于聊天栏实时回显。
     */
    public WallResolver.Result preview(String sessionId) {
        Session s = requireSession(sessionId);
        if (s.state() != SessionState.SELECTING) {
            throw new IllegalStateException("preview only valid in SELECTING; got " + s.state());
        }
        if (s.pos1() == null || s.pos2() == null) {
            return null;
        }
        return wallResolver.resolve(s.pos1(), s.face(), s.pos2(), s.face());
    }

    // ---------- /canvas confirm ----------

    public sealed interface ConfirmResult {
        /** 新建路径：池借了新 map，调用方应 deploy ItemFrames。 */
        record OkNewWall(Session session, WallResolver.Result.Ok wall, List<Integer> mapIds, String wallId) implements ConfirmResult {}
        /** 复用路径：bind 现有 wall 的 map，ItemFrames 已存在不重新部署。 */
        record OkExistingWall(Session session, WallResolver.Result.Ok wall, List<Integer> mapIds, String wallId) implements ConfirmResult {}
        record NotReady(String detail) implements ConfirmResult {}
        record WallFailed(WallResolver.Result.Failed reason) implements ConfirmResult {}
        record WallOccupied(String otherSessionId, UUID otherPlayer) implements ConfirmResult {}
        record PoolExhausted(String message) implements ConfirmResult {}
    }

    /**
     * M16-P2.7-A：confirm 失败时统一异常，含已执行的 rollback 链摘要。
     * 调用方据此向玩家显示"failed to confirm session, server log for details"。
     */
    public static final class SessionConfirmFailedException extends RuntimeException {
        public SessionConfirmFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * {@code SELECTING → ISSUED}：解析墙面 → 排他锁 → 两分支：
     * <ul>
     *   <li><b>新建</b>（无 walls 行）：reserveForWall + create walls 行 → {@link ConfirmResult.OkNewWall}</li>
     *   <li><b>现有</b>（walls 行存在）：bindToWall + load ProjectState → {@link ConfirmResult.OkExistingWall}</li>
     * </ul>
     * <b>必须主线程</b>（MapPool.reserveForWall 可能 createMap）。
     *
     * <p>M16-P2.7-A：multi-step 链路用 rollback stack 包裹——任一中间步抛异常时
     * 倒序执行已 push 的 rollback action，避免留下 mapPool reserved 但 walls 无对应行
     * （maps 永久泄漏）或反过来的状态。</p>
     *
     * <p>M16-P6.7：不再 {@code synchronized(this)}。confirm 已 {@link #assertMainThread}
     * 保证主线程串行；跨 map 的最终 commit（{@code byWall.putIfAbsent}）由 {@link #writeLock}
     * 短临界区守护，确保异步线程的 read 观察到完整状态。Bukkit / MapPool 调用全部在
     * writeLock 之外。</p>
     */
    public ConfirmResult confirm(String sessionId) {
        assertMainThread();
        Session s = byId.get(sessionId);
        if (s == null) return new ConfirmResult.NotReady("session not found");
        if (s.state() != SessionState.SELECTING) {
            return new ConfirmResult.NotReady(
                    "session already in state " + s.state() + " — use /canvas cancel to reset");
        }
        if (s.pos1() == null || s.pos2() == null) {
            return new ConfirmResult.NotReady("please click both corners first");
        }

        // 走 WallResolver；M5.5 阶段 OCCUPIED 由 WallResolver 兼容处理（P3 完善）
        WallResolver.Result r = wallResolver.resolve(s.pos1(), s.face(), s.pos2(), s.face());
        if (r instanceof WallResolver.Result.Failed f) {
            return new ConfirmResult.WallFailed(f);
        }
        WallResolver.Result.Ok wall = (WallResolver.Result.Ok) r;

        WallKey key = new WallKey(
                wall.world().getName(), wall.minX(), wall.minY(), wall.minZ(), wall.facing());
        // M16-P6.7：early probe；最终 commit 用 putIfAbsent 在临界区里 race-free
        String holderId = byWall.get(key);
        if (holderId != null) {
            Session holder = byId.get(holderId);
            return new ConfirmResult.WallOccupied(holderId,
                    holder == null ? null : holder.playerUuid());
        }

        var existing = wallRepo.loadByKey(key).orElse(null);
        boolean hasFrames = wall.hasExistingFrames();
        ProjectState ps;
        List<Integer> mapIds;
        String wallId;
        boolean newWall;

        // M16-P2.7-A：rollback stack。每步成功后 push 一个 undo action；任何后续步骤
        // 抛异常时倒序运行（LIFO）。所有 Runnable 都用 try/catch 包裹，单条 rollback
        // 失败仅 log.warning，不影响其它 rollback 和原始异常传递。
        final java.util.Deque<Runnable> rollbacks = new java.util.ArrayDeque<>();

        try {
            if (hasFrames && existing != null && !existing.mapIds().isEmpty()
                    && mapPool.bindToWall(existing.wallId(), existing.mapIds(), wall.world())) {
                // 自家画框 + walls 行齐全 → 二次编辑路径。bindToWall 接受 FREE 或同 wallId
                // 的 RESERVED；本路径下原本就是 RESERVED，所以 rollback 无需 release（释放
                // 会把别人的 owner 也清掉）——只注册 byWall 一项即可。
                mapIds = existing.mapIds();
                ps = existing.state();
                wallId = existing.wallId();
                newWall = false;
            } else if (hasFrames || existing != null) {
                // 不一致：要么画框存在但 walls 行缺失；要么 walls 行存在但 bbox 没画框。
                // 拒绝建议玩家先 /canvas delete 重置或换地点。
                return new ConfirmResult.NotReady(
                        "this location has stale wall data; /canvas delete <id> to reset, or pick empty space");
            } else {
                // 全空 → 新建。链路：reserve → INSERT walls → release(pending) + bind(wallId)
                ps = new ProjectState(wall.width(), wall.height());
                final String reserveOwnerWallId = "pending-" + UUID.randomUUID();
                try {
                    mapIds = mapPool.reserveForWall(reserveOwnerWallId, wall.mapCount(), wall.world());
                } catch (PoolExhaustedException e) {
                    return new ConfirmResult.PoolExhausted(e.getMessage());
                }
                final List<Integer> reservedMapIds = mapIds;
                // step1 rollback：释放 pending owner 持有的 reserve
                rollbacks.push(() -> mapPool.releaseWall(reserveOwnerWallId));

                wallId = wallRepo.createWithMapIds(key, ps, mapIds,
                        wall.width(), wall.height(),
                        s.playerUuid(), s.playerName());
                final String createdWallId = wallId;
                // step2 rollback：删 walls 行（注意倒序：先 delete walls 行，再 release maps）
                rollbacks.push(() -> wallRepo.delete(createdWallId));

                // owner 从 pending-* 改写为真正的 wallId：先 release pending，再 bind 到 wallId。
                // 这两步是 step3 的 atomic 处理——失败一并视作 step3 失败，前两步 rollback 起作用。
                mapPool.releaseWall(reserveOwnerWallId);
                // 注意：上面 rollback push(() -> releaseWall(pending)) 已经执行了同样的效果，
                // 此处 release 后 pending owner 已无 maps，rollback 再调用是 no-op，幂等安全。
                if (!mapPool.bindToWall(wallId, mapIds, wall.world())) {
                    // 极罕见：maps 在两步之间被别处抢占。让 outer catch 触发 rollback 链。
                    throw new SessionConfirmFailedException(
                            "map bind race after reserve for wall=" + wallId, null);
                }
                // step3 rollback：释放最终绑定的 maps（按 wallId owner）
                final String boundWallId = wallId;
                rollbacks.push(() -> mapPool.releaseWall(boundWallId));
                // 用 reservedMapIds 防止后续 step 把变量 shadow（仅文档作用，不参与逻辑）
                if (reservedMapIds != mapIds) {
                    log.warning("mapIds mutated mid-flow (unexpected); using last value");
                }
                newWall = true;
            }

            // ---- 从此处起进入 in-memory 状态变更 ----
            // Session 字段赋值不需要 writeLock：confirm 已 assertMainThread 保证主线程串行，
            // 而异步线程拿到的 Session 对象引用是从 byId.get 来的——byWall.putIfAbsent
            // 失败回滚时，session 也未发布到 byWall，外部读不到。
            s.wall(wall);
            s.mapIds(mapIds);
            s.wallKey(key);
            s.wallId(wallId);
            s.projectState(ps);
            s.editSession(new EditSession(ps));
            s.state(SessionState.ISSUED);

            // M16-P6.7：byWall race-free put。失败说明 confirm-vs-confirm race（实际不会
            // 发生，因 confirm 主线程串行），但作为防御保留 + WallOccupied 路径。
            writeLock.lock();
            try {
                String raceHolder = byWall.putIfAbsent(key, sessionId);
                if (raceHolder != null) {
                    Session holder = byId.get(raceHolder);
                    throw new WallRaceException(raceHolder, holder == null ? null : holder.playerUuid());
                }
            } finally {
                writeLock.unlock();
            }

            auditLog.record("SESSION_CONFIRM", s.playerUuid().toString(), s.playerName(),
                    sessionId, null,
                    Map.of("wall_id", wallId, "new_wall", newWall, "map_count", mapIds.size(),
                            "world", wall.world().getName()));

            return newWall
                    ? new ConfirmResult.OkNewWall(s, wall, mapIds, wallId)
                    : new ConfirmResult.OkExistingWall(s, wall, mapIds, wallId);
        } catch (WallRaceException race) {
            runRollbacks(rollbacks);
            return new ConfirmResult.WallOccupied(race.holderSessionId, race.holderPlayer);
        } catch (SessionConfirmFailedException e) {
            // map bind race 内部抛出 → 跑 rollback 后向上层报 PoolExhausted（保持 API 兼容）
            runRollbacks(rollbacks);
            log.log(Level.WARNING, "SessionManager.confirm rolled back: " + e.getMessage(), e);
            return new ConfirmResult.PoolExhausted("map bind race after reserve");
        } catch (RuntimeException e) {
            runRollbacks(rollbacks);
            log.log(Level.SEVERE, "SessionManager.confirm failed, rolled back; see chain above", e);
            throw new SessionConfirmFailedException(
                    "failed to confirm session; server log for details", e);
        }
    }

    /** 内部 sentinel：byWall.putIfAbsent 发现竞争时使用，触发 confirm rollback 链。 */
    private static final class WallRaceException extends RuntimeException {
        final String holderSessionId;
        final UUID holderPlayer;
        WallRaceException(String holderSessionId, UUID holderPlayer) {
            super("wall race lost to session " + holderSessionId);
            this.holderSessionId = holderSessionId;
            this.holderPlayer = holderPlayer;
        }
    }

    /** M16-P2.7-A：倒序执行 rollback stack，单条失败仅 log.warning，不抛。 */
    private void runRollbacks(java.util.Deque<Runnable> rollbacks) {
        while (!rollbacks.isEmpty()) {
            Runnable r = rollbacks.pop();
            try {
                r.run();
            } catch (RuntimeException rollbackEx) {
                log.log(Level.WARNING,
                        "SessionManager.confirm rollback step failed (continuing): "
                                + rollbackEx.getMessage(),
                        rollbackEx);
            }
        }
    }

    /**
     * `/canvas open <wall_id>` / wand 瞄已有 ItemFrame：直接从 CLOSED 进 ISSUED，绕过 SELECTING。
     * 主线程调用。
     */
    public sealed interface OpenResult {
        record Ok(Session session, WallRepo.Wall wall) implements OpenResult {}
        record NotFound() implements OpenResult {}
        record AlreadyHasSession(SessionState current) implements OpenResult {}
        record WallOccupied(String otherSessionId, UUID otherPlayer) implements OpenResult {}
        record BindFailed(String detail) implements OpenResult {}
        /** M15.3 Phase 2 方案 C：wall 已锁定 + caller 非 owner + 无 bypass 权限 → 拒绝 open。 */
        record Forbidden(String message) implements OpenResult {}
    }

    public OpenResult open(UUID playerUuid, String playerName, String wallIdOrAlias) {
        assertMainThread();
        var w = wallRepo.loadById(wallIdOrAlias).orElse(
                wallRepo.loadByAlias(wallIdOrAlias).orElse(null));
        if (w == null) return new OpenResult.NotFound();

        // M15.3 Phase 2 方案 C：lock-aware open。后端编辑 op 仍透明放行（CLAUDE.md §lock-state 第 2 条），
        // 仅在 open 入口拦截：locked wall + 非 owner + 无 canvas.admin.bypass-lock 权限 → Forbidden。
        // 注意：Bukkit.getPlayer 调用在锁外（持锁中调 Bukkit API 易死锁）。
        if (w.publishedAt() != null && !playerUuid.equals(w.ownerUuid())) {
            org.bukkit.entity.Player live = Bukkit.getPlayer(playerUuid);
            boolean bypass = live != null && live.hasPermission("canvas.admin.bypass-lock");
            if (!bypass) {
                // M16 P6.4：lock-aware open 拒绝留痕——监控异常尝试访问他人 locked wall
                LinkedHashMap<String, Object> details = new LinkedHashMap<>();
                details.put("operation", "open");
                details.put("wall_id", w.wallId());
                details.put("caller", playerUuid.toString());
                details.put("reason", "lock_owner_only");
                auditLog.record("PERMISSION_DENIED", playerUuid.toString(), playerName,
                        null, null, details);
                return new OpenResult.Forbidden(
                        "wall '" + w.wallId() + "' is locked by its owner");
            }
        }

        // M5.5 修：玩家若已绑同一 wall（浏览器关了但 session 还 ACTIVE）→ 幂等重用 + 重发 URL；
        // 绑别的 wall 才报 AlreadyHasSession，避免"5min idle 等不及"的卡死。
        String existingId = byPlayer.get(playerUuid);
        if (existingId != null) {
            Session existing = byId.get(existingId);
            if (existing != null && w.wallId().equals(existing.wallId())) {
                existing.touchActivity(System.currentTimeMillis());
                // 防御性：上次 open 是修复前版本进入的 → wall geometry 可能 null，补上
                if (existing.wall() == null) existing.wall(rebuildWallGeometry(w));
                return new OpenResult.Ok(existing, w);
            }
            return new OpenResult.AlreadyHasSession(existing == null ? null : existing.state());
        }

        WallKey key = w.key();
        // early probe（race-free 由后续 writeLock + putIfAbsent 段保证）
        String holderId = byWall.get(key);
        if (holderId != null) {
            Session holder = byId.get(holderId);
            return new OpenResult.WallOccupied(holderId,
                    holder == null ? null : holder.playerUuid());
        }
        // Bukkit.getWorld + MapPool.bindToWall 必须在锁外（持锁中调 Bukkit API 易死锁）
        World world = Bukkit.getWorld(w.key().world());
        if (world == null) {
            return new OpenResult.BindFailed(
                    "world '" + w.key().world() + "' not loaded for wall " + w.wallId());
        }
        if (!mapPool.bindToWall(w.wallId(), w.mapIds(), world)) {
            return new OpenResult.BindFailed("map pool refused bind for " + w.wallId());
        }

        long now = System.currentTimeMillis();
        String sessionId = UUID.randomUUID().toString();
        Session s = new Session(sessionId, playerUuid, playerName, now);
        s.wallKey(key);
        s.wallId(w.wallId());
        s.mapIds(w.mapIds());
        s.projectState(w.state());
        s.editSession(new EditSession(w.state()));
        // 2026-05-12 修：补回 wall geometry，否则 wall.refresh / frame ops 在 /canvas open 后空跑
        s.wall(rebuildWallGeometry(w));
        s.state(SessionState.ISSUED);

        // M16-P6.7：跨 byId / byPlayer / byWall 三 map commit 在 writeLock 临界区内串行化，
        // 确保异步 read 看到完整状态；race-check 防主线程窗口（不应该发生但防御）。
        writeLock.lock();
        try {
            String racePlayer = byPlayer.get(playerUuid);
            if (racePlayer != null) {
                Session existing = byId.get(racePlayer);
                return new OpenResult.AlreadyHasSession(existing == null ? null : existing.state());
            }
            String raceHolder = byWall.putIfAbsent(key, sessionId);
            if (raceHolder != null) {
                Session holder = byId.get(raceHolder);
                return new OpenResult.WallOccupied(raceHolder,
                        holder == null ? null : holder.playerUuid());
            }
            byId.put(sessionId, s);
            byPlayer.put(playerUuid, sessionId);
        } finally {
            writeLock.unlock();
        }

        auditLog.record("SESSION_OPEN", playerUuid.toString(), playerName, sessionId, null,
                Map.of("wall_id", w.wallId()));
        return new OpenResult.Ok(s, w);
    }

    /**
     * 从持久化的 {@link WallRepo.Wall} 反推 {@link WallResolver.Result.Ok}（运行期几何）。
     * confirm 路径下 geometry 由 WallResolver 直接产出；open 路径下没有玩家点击，只能从
     * DB 行重建。{@code hasExistingFrames} 固定 true，因为打开已存在的 wall 必然有自家画框。
     */
    private WallResolver.Result.Ok rebuildWallGeometry(WallRepo.Wall w) {
        // 注意：Bukkit.getWorld 调用在锁外（rebuildWallGeometry 仅由 open 主线程路径调用）
        World world = Bukkit.getWorld(w.key().world());
        if (world == null) {
            throw new IllegalStateException(
                    "world '" + w.key().world() + "' not loaded for wall " + w.wallId());
        }
        return new WallResolver.Result.Ok(
                world,
                w.key().originX(),
                w.key().originY(),
                w.key().originZ(),
                w.widthMaps(),
                w.heightMaps(),
                w.key().facing(),
                /* hasExistingFrames */ true);
    }

    // ---------- WS auth / ACTIVE ----------

    /** WS {@code auth} 成功后标 ACTIVE；token consume 由 {@link TokenService#consume} 提前做。 */
    public void markActive(String sessionId) {
        Session s = requireSession(sessionId);
        if (s.state() != SessionState.ISSUED) {
            throw new IllegalStateException("markActive expects ISSUED; got " + s.state());
        }
        s.state(SessionState.ACTIVE);
        s.touchActivity(System.currentTimeMillis());
        auditLog.record("AUTH_OK", s.playerUuid().toString(), s.playerName(),
                sessionId, null, Map.of());
    }

    public void touch(String sessionId) {
        Session s = byId.get(sessionId);
        if (s != null) s.touchActivity(System.currentTimeMillis());
    }

    public void markDisconnected(String sessionId) {
        Session s = byId.get(sessionId);
        if (s != null) s.markWsDisconnected(System.currentTimeMillis());
    }

    /**
     * M5.5：WS op 成功后由 WebServer 调用，把当前 {@link ProjectState} 存 walls 表
     * （仅 UPDATE project_json，不动 mapIds / wall_id）。非主线程调用即可。
     */
    public void persistWall(String sessionId) {
        // M16-P6.7：CHM read 无锁
        Session s = byId.get(sessionId);
        if (s == null || s.wallId() == null || s.projectState() == null) return;
        wallRepo.updateState(s.wallId(), s.projectState());
    }

    /**
     * M16 P6.6：会话级 IP 绑定判定。
     * <ul>
     *   <li>session 不存在 → {@link IpBindResult#NO_SESSION}（调用方应已 closeAuthFailed，
     *       此返回仅为安全网）。</li>
     *   <li>session.boundIp == null（首次 auth）→ 写入 presentedIp 并返回 {@link IpBindResult#BOUND}。</li>
     *   <li>session.boundIp.equals(presentedIp) → {@link IpBindResult#OK}。</li>
     *   <li>不等 → {@link IpBindResult#MISMATCH}。调用方 closeAuthFailed。</li>
     * </ul>
     * 用单 session 字段的可见性保证（boundIp 仅 SessionManager 写）+ writeLock 串行化首次绑定，
     * 防同一 sessionId 并发两 WS 都看到 boundIp==null 同时写入两不同 IP。
     */
    public enum IpBindResult { OK, BOUND, MISMATCH, NO_SESSION }

    public IpBindResult bindOrCheckIp(String sessionId, String presentedIp) {
        Session s = byId.get(sessionId);
        if (s == null) return IpBindResult.NO_SESSION;
        String current = s.boundIp();
        if (current != null) {
            return current.equals(presentedIp) ? IpBindResult.OK : IpBindResult.MISMATCH;
        }
        // 首次绑定：加锁防 race（两 WS 同 sessionId 同时 auth）
        writeLock.lock();
        try {
            current = s.boundIp();
            if (current == null) {
                s.boundIp(presentedIp);
                return IpBindResult.BOUND;
            }
            return current.equals(presentedIp) ? IpBindResult.OK : IpBindResult.MISMATCH;
        } finally {
            writeLock.unlock();
        }
    }

    // ---------- /canvas cancel ----------

    /**
     * 释放 session（不删 wall）。任何非 CLOSING 状态都可 cancel；不归还池（wall 一直占）；
     * 仅清 byPlayer / byWall / forgetHooks。M5.5 起 wall 数据生命周期与 session 解耦。
     *
     * <p>M16-P6.7：跨 map mutate 在 {@link #writeLock} 内；{@link #forgetHooks} 在锁外触发，
     * 防止 hook 回调持外部锁造成 lock-order 死锁。</p>
     */
    public void cancel(String sessionId, String reason) {
        assertMainThread();
        Session s = byId.get(sessionId);
        if (s == null) return;
        if (s.state() == SessionState.CLOSING) return;

        writeLock.lock();
        try {
            // 锁内再校验（防 race：另一线程已并发 cancel/forget）
            s = byId.get(sessionId);
            if (s == null || s.state() == SessionState.CLOSING) return;
            s.state(SessionState.CLOSING);
            // 不归还池！wall 一直占着 maps，等 /canvas delete 才释放
            releaseLocks(s);
            forget(s);
        } finally {
            writeLock.unlock();
        }

        auditLog.record("SESSION_CANCEL", s.playerUuid().toString(), s.playerName(),
                sessionId, null, Map.of("reason", reason == null ? "" : reason));
        runForgetHooks(sessionId);
    }

    /**
     * `/canvas delete <wall_id> confirm`：彻底删除 wall（释放 map → FREE，删 walls 行）。
     * 调用方负责拆 ItemFrames 和 cancel 任何活跃 session。
     *
     * <p>M16-P6.7：map mutate 在 writeLock 内；MapPool / WallRepo / renderer / forgetHooks
     * 全部在锁外执行。</p>
     */
    public boolean deleteWall(String wallId) {
        assertMainThread();
        if (wallId == null) return false;
        // 收集要 forget 的 session id（在锁外发 hook），map mutate 在锁内
        List<String> forgottenIds = new ArrayList<>();
        writeLock.lock();
        try {
            for (Session s : new ArrayList<>(byId.values())) {
                if (wallId.equals(s.wallId()) && s.state() != SessionState.CLOSING) {
                    s.state(SessionState.CLOSING);
                    releaseLocks(s);
                    forget(s);
                    forgottenIds.add(s.id());
                }
            }
        } finally {
            writeLock.unlock();
        }
        // Bukkit / MapPool / WallRepo / renderer 全部在锁外
        List<Integer> released = mapPool.releaseWall(wallId);
        // M15.3 P0-25：清像素缓存，防 mapId 复用导致旧像素显示在新 wall。
        if (canvasRenderer != null && !released.isEmpty()) {
            canvasRenderer.invalidate(released);
        }
        wallRepo.delete(wallId);
        auditLog.record("WALL_DELETE", null, null, null, null,
                Map.of("wall_id", wallId, "released_maps", released.size()));
        for (String id : forgottenIds) runForgetHooks(id);
        // 0.4.0-P1-C：wall 删除完成 → 触发监听（VariableStore.clearWallReferences 等）。
        // 异常隔离避免单个 hook 抛拖垮主路径。
        for (Consumer<String> hook : wallDeleteHooks) {
            try {
                hook.accept(wallId);
            } catch (Exception e) {
                log.log(java.util.logging.Level.WARNING,
                        "SessionManager.deleteWall: wallDeleteHook threw for " + wallId, e);
            }
        }
        return true;
    }

    // ---------- 查询 ----------

    public Session byPlayer(UUID playerUuid) {
        String id = byPlayer.get(playerUuid);
        return id == null ? null : byId.get(id);
    }

    public Session byId(String sessionId) {
        return byId.get(sessionId);
    }

    /** 给 {@link MapPool#detectLeaks} 用：当前所有活跃（非 CLOSING）会话 id。 */
    public Set<String> liveSessionIds() {
        Set<String> out = new HashSet<>();
        for (Session s : byId.values()) {
            if (s.state() != SessionState.CLOSING) out.add(s.id());
        }
        return Collections.unmodifiableSet(out);
    }

    public int size() {
        return byId.size();
    }

    /**
     * 2026-05-14：对所有活跃 session 的 {@link moe.hikari.canvas.state.EditSession#purgeStaleStrokes}
     * 一并调用。由 {@link SessionReaper} 周期触发——确保用户永久离开后服务端
     * 不会积压 stroke buffer 内存（M12 brush 引入的潜在泄漏）。
     */
    public void purgeAllStaleStrokes() {
        for (Session s : byId.values()) {
            moe.hikari.canvas.state.EditSession es = s.editSession();
            if (es != null) es.purgeStaleStrokes();
        }
    }

    /**
     * 0.4.0-P1-B：找到所有绑定到 {@code wallId} 的活跃 session，对每个 submit 全画布 dirty
     * 到 {@link moe.hikari.canvas.render.ProjectionThrottler}。
     *
     * <p>用例：变量值变化（{@link moe.hikari.canvas.variable.VariableStore} wallDirtyCallback）
     * 触发引用该变量的所有 wall 重画。throttler 自动合并到下个 200ms 窗口（5fps 上限）。</p>
     *
     * <p>线程安全：只读 {@code byId} + per-session immutable getters；ProjectionThrottler.submit
     * 自身线程安全。可以从任意线程调（VariableStore 的 setValue 多半在 async daemon 或 WS 线程）。</p>
     */
    public void submitFullCanvasDirtyByWall(String wallId,
                                            moe.hikari.canvas.render.ProjectionThrottler throttler) {
        if (wallId == null || throttler == null) return;
        for (Session s : byId.values()) {
            if (s.state() == SessionState.CLOSING) continue;
            if (!wallId.equals(s.wallId())) continue;
            moe.hikari.canvas.state.ProjectState ps = s.projectState();
            if (ps == null) continue;
            throttler.submit(s.id(),
                    moe.hikari.canvas.render.DirtyRegion.fullCanvas(ps));
        }
    }

    // ---------- 超时扫描（M3-T2 Reaper 用） ----------

    /**
     * 由 {@link SessionReaper} 批量查询要 cancel 的会话。仅做决策，不做副作用；
     * 返回后调用方逐个调 {@link #cancel}。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>{@link SessionState#ISSUED}：{@code now - createdAt > issuedTimeoutMs}
     *       → {@code "issued-timeout"}（玩家 confirm 后迟迟不打开编辑器）</li>
     *   <li>{@link SessionState#ACTIVE} + {@code wsDisconnectedAt > 0}：
     *       {@code now - wsDisconnectedAt > wsGraceMs} → {@code "ws-reconnect-timeout"}
     *       （断连超过宽限没回来）</li>
     *   <li>{@link SessionState#ACTIVE} + {@code wsDisconnectedAt < 0}：
     *       {@code now - lastActivityAt > activeIdleMs} → {@code "idle-timeout"}
     *       （在线但长期无消息，疑似挂着不管）</li>
     *   <li>{@link SessionState#SELECTING} / {@link SessionState#CLOSING}：不超时</li>
     * </ul>
     */
    public record ExpiredSession(String id, String reason) {}

    public List<ExpiredSession> collectExpired(
            long now, long issuedTimeoutMs, long wsGraceMs, long activeIdleMs) {
        List<ExpiredSession> out = new ArrayList<>();
        for (Session s : byId.values()) {
            switch (s.state()) {
                case ISSUED -> {
                    if (now - s.createdAt() > issuedTimeoutMs) {
                        out.add(new ExpiredSession(s.id(), "issued-timeout"));
                    }
                }
                case ACTIVE -> {
                    if (s.wsDisconnectedAt() > 0
                            && now - s.wsDisconnectedAt() > wsGraceMs) {
                        out.add(new ExpiredSession(s.id(), "ws-reconnect-timeout"));
                    } else if (s.wsDisconnectedAt() < 0
                            && now - s.lastActivityAt() > activeIdleMs) {
                        out.add(new ExpiredSession(s.id(), "idle-timeout"));
                    }
                }
                case SELECTING, CLOSING -> {}
            }
        }
        return out;
    }

    // ---------- 内部 ----------

    /**
     * M16-P2.7-B：主线程断言。{@link #confirm} / {@link #cancel} / {@link #deleteWall}
     * 直接或间接调用 {@link MapPool} 的 Bukkit API 路径（{@code createMap} / MapView 操作），必须主线程。
     *
     * <p>纯单测环境（Bukkit server 未注入）下 {@link Bukkit#getServer()} 抛 NPE 或返回 null，
     * 此时跳过断言，防止已有单测因新增断言变红。MockBukkit / 生产路径下 server 非 null，
     * 严格校验主线程。</p>
     */
    private static void assertMainThread() {
        try {
            if (Bukkit.getServer() == null) return;
        } catch (Throwable t) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "SessionManager: must be called on Bukkit main thread (was: "
                            + Thread.currentThread().getName() + ")");
        }
    }

    private Session requireSession(String sessionId) {
        Session s = byId.get(sessionId);
        if (s == null) throw new IllegalArgumentException("unknown session: " + sessionId);
        return s;
    }

    private Session requireState(String sessionId, SessionState expected) {
        Session s = requireSession(sessionId);
        if (s.state() != expected) {
            throw new IllegalStateException(
                    "session " + sessionId + " is " + s.state() + ", expected " + expected);
        }
        return s;
    }

    /** 调用方必须持 {@link #writeLock}。 */
    private void releaseLocks(Session s) {
        if (s.wallKey() != null) byWall.remove(s.wallKey(), s.id());
    }

    /** 调用方必须持 {@link #writeLock}。仅做 map mutate；hook 由调用方在锁外触发。 */
    private void forget(Session s) {
        byId.remove(s.id());
        byPlayer.remove(s.playerUuid(), s.id());
        s.state(SessionState.CLOSING);
    }

    /** 在锁外执行 forgetHooks，避免持锁中回调三方组件造成反向 lock-order 死锁。 */
    private void runForgetHooks(String sessionId) {
        // hooks 是 CopyOnWriteArrayList，遍历期间可并发 add。异常不影响其他 hook 执行。
        for (Consumer<String> hook : forgetHooks) {
            try {
                hook.accept(sessionId);
            } catch (RuntimeException e) {
                log.warning("SessionManager: forget hook threw for " + sessionId + ": " + e.getMessage());
            }
        }
    }
}
