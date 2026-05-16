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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 会话生命周期核心。契约见 {@code docs/architecture.md §3}。
 *
 * <p><b>并发与主线程约束：</b> 所有公共方法均 {@code synchronized(this)}。涉及
 * {@link MapPool#reserve} / {@link MapPool#promoteToPermanent}（会 {@code Bukkit.createMap}）
 * 的方法必须在 Bukkit 主线程调用——即 {@link #confirm} 与 {@link #commit}。
 * {@link #cancel} 若当前状态 ≥ {@link SessionState#ISSUED} 也会触发 MapPool 操作，
 * 因此同样限主线程。只读查询（{@link #liveSessionIds} 等）可从异步调用。</p>
 *
 * <p>并发约束：</p>
 * <ul>
 *   <li>每玩家最多 1 个活跃会话（含 {@link SessionState#SELECTING}）</li>
 *   <li>每墙面排他锁 {@link WallKey}——{@code SELECTING → ISSUED} 时获取、
 *       cancel/commit 时释放</li>
 * </ul>
 *
 * <p><b>M2 范围：</b> WS 相关 timing（auth 超时、idle disconnect、5 分钟重连宽限的
 * 后台扫描）留给 M2-T10 / T11 接入时补。本类已提供 {@link Session#markWsDisconnected}
 * 等 hook，但没有主动 schedule 的回收 task。</p>
 */
public final class SessionManager {

    private final Logger log;
    private final MapPool mapPool;
    private final WallResolver wallResolver;
    private final AuditLog auditLog;
    private final WallRepo wallRepo;
    /** M15.3 P0-25：deleteWall 释放 map 后清除像素缓存，防 mapId 复用导致跨 wall 像素泄漏。可空（不强制依赖）。 */
    private final HikariCanvasRenderer canvasRenderer;

    private final Map<String, Session> byId = new HashMap<>();
    private final Map<UUID, String> byPlayer = new HashMap<>();
    private final Map<WallKey, String> byWall = new HashMap<>();

    /** session forget 时调用，用于让 throttler / rate-limiter / WS ctx map 等清状态。 */
    private final List<Consumer<String>> forgetHooks = new CopyOnWriteArrayList<>();

    /** 注册 session forget 监听。callback 接收被 forget 的 sessionId。线程安全。 */
    public void addForgetHook(Consumer<String> hook) {
        forgetHooks.add(hook);
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
    public synchronized BeginResult beginSelecting(UUID playerUuid, String playerName) {
        Objects.requireNonNull(playerUuid);
        String existingId = byPlayer.get(playerUuid);
        if (existingId != null) {
            return new BeginResult.AlreadyHasSession(byId.get(existingId));
        }
        long now = System.currentTimeMillis();
        String sessionId = UUID.randomUUID().toString();
        Session s = new Session(sessionId, playerUuid, playerName, now);
        byId.put(sessionId, s);
        byPlayer.put(playerUuid, sessionId);
        auditLog.record("SESSION_BEGIN", playerUuid.toString(), playerName, sessionId, null,
                Map.of("state", s.state().name()));
        return new BeginResult.Ok(s);
    }

    /** 左键点击记为 pos1；右键点击记为 pos2。 */
    public synchronized void recordPos(String sessionId, boolean isFirstCorner, Block block, BlockFace face) {
        Session s = requireState(sessionId, SessionState.SELECTING);
        if (isFirstCorner) s.pos1(block, face);
        else s.pos2(block, face);
    }

    /**
     * M5-D8：清空已选 pos1/pos2，玩家在 SELECTING 状态下隐式 reselect。
     * {@code /canvas edit} 在已 SELECTING 时调用此方法替代抛 AlreadyHasSession。
     */
    public synchronized boolean resetSelection(String sessionId) {
        Session s = byId.get(sessionId);
        if (s == null || s.state() != SessionState.SELECTING) return false;
        s.clearPos();
        return true;
    }

    /**
     * 对当前已记的 pos1 / pos2 调用 {@link WallResolver} 做预览（不改状态）。
     * 用于聊天栏实时回显。
     */
    public synchronized WallResolver.Result preview(String sessionId) {
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
     * {@code SELECTING → ISSUED}：解析墙面 → 排他锁 → 两分支：
     * <ul>
     *   <li><b>新建</b>（无 walls 行）：reserveForWall + create walls 行 → {@link ConfirmResult.OkNewWall}</li>
     *   <li><b>现有</b>（walls 行存在）：bindToWall + load ProjectState → {@link ConfirmResult.OkExistingWall}</li>
     * </ul>
     * <b>必须主线程</b>（MapPool.reserveForWall 可能 createMap）。
     */
    public synchronized ConfirmResult confirm(String sessionId) {
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

        if (hasFrames && existing != null && !existing.mapIds().isEmpty()
                && mapPool.bindToWall(existing.wallId(), existing.mapIds())) {
            // 自家画框 + walls 行齐全 → 二次编辑路径
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
            // 全空 → 新建。
            // M15.3 P0-32 v1：先 reserve（mapPool 自带 byId.put 内存状态难协调事务，留在事务外），
            // 再 createWithMapIds 单事务 INSERT walls 行（含 mapIds）。避免旧路径 create + updateMapIds
            // 两步无事务（中间 mapPool 故障会留下 mapIds 为空的 walls 行）。
            // 完整 mapPool + walls 跨子系统事务一致性 → M15.4 phase。
            ps = new ProjectState(wall.width(), wall.height());
            // 用占位 wall_id 先 reserve（reserveForWall 内部仅靠 owner 字符串区分，事后无需回填）
            String reserveOwnerWallId = "pending-" + UUID.randomUUID();
            try {
                mapIds = mapPool.reserveForWall(reserveOwnerWallId, wall.mapCount());
            } catch (PoolExhaustedException e) {
                return new ConfirmResult.PoolExhausted(e.getMessage());
            }
            try {
                wallId = wallRepo.createWithMapIds(key, ps, mapIds,
                        wall.width(), wall.height(),
                        s.playerUuid(), s.playerName());
            } catch (RuntimeException e) {
                // 回滚 map reserve（释放刚刚分配的 maps）
                mapPool.releaseWall(reserveOwnerWallId);
                throw e;
            }
            // 把 reserve 时的占位 owner 改写为真正的 wallId（bindToWall 接受 FREE 或同 wallId 的 RESERVED，
            // 但我们这里的 maps owner 是 pending-*，需要先 release 再 bind 到正确 wallId）。
            mapPool.releaseWall(reserveOwnerWallId);
            if (!mapPool.bindToWall(wallId, mapIds)) {
                // 极罕见：maps 在两步之间被别处抢占。回滚 walls 行。
                wallRepo.delete(wallId);
                return new ConfirmResult.PoolExhausted("map bind race after reserve");
            }
            newWall = true;
        }

        s.wall(wall);
        s.mapIds(mapIds);
        s.wallKey(key);
        s.wallId(wallId);
        s.projectState(ps);
        s.editSession(new EditSession(ps));
        s.state(SessionState.ISSUED);
        byWall.put(key, sessionId);

        auditLog.record("SESSION_CONFIRM", s.playerUuid().toString(), s.playerName(),
                sessionId, null,
                Map.of("wall_id", wallId, "new_wall", newWall, "map_count", mapIds.size(),
                        "world", wall.world().getName()));

        return newWall
                ? new ConfirmResult.OkNewWall(s, wall, mapIds, wallId)
                : new ConfirmResult.OkExistingWall(s, wall, mapIds, wallId);
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

    public synchronized OpenResult open(UUID playerUuid, String playerName, String wallIdOrAlias) {
        var w = wallRepo.loadById(wallIdOrAlias).orElse(
                wallRepo.loadByAlias(wallIdOrAlias).orElse(null));
        if (w == null) return new OpenResult.NotFound();

        // M15.3 Phase 2 方案 C：lock-aware open。后端编辑 op 仍透明放行（CLAUDE.md §lock-state 第 2 条），
        // 仅在 open 入口拦截：locked wall + 非 owner + 无 canvas.admin.bypass-lock 权限 → Forbidden。
        if (w.publishedAt() != null && !playerUuid.equals(w.ownerUuid())) {
            org.bukkit.entity.Player live = org.bukkit.Bukkit.getPlayer(playerUuid);
            boolean bypass = live != null && live.hasPermission("canvas.admin.bypass-lock");
            if (!bypass) {
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
        String holderId = byWall.get(key);
        if (holderId != null) {
            Session holder = byId.get(holderId);
            return new OpenResult.WallOccupied(holderId,
                    holder == null ? null : holder.playerUuid());
        }
        if (!mapPool.bindToWall(w.wallId(), w.mapIds())) {
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

        byId.put(sessionId, s);
        byPlayer.put(playerUuid, sessionId);
        byWall.put(key, sessionId);

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
    public synchronized void markActive(String sessionId) {
        Session s = requireSession(sessionId);
        if (s.state() != SessionState.ISSUED) {
            throw new IllegalStateException("markActive expects ISSUED; got " + s.state());
        }
        s.state(SessionState.ACTIVE);
        s.touchActivity(System.currentTimeMillis());
        auditLog.record("AUTH_OK", s.playerUuid().toString(), s.playerName(),
                sessionId, null, Map.of());
    }

    public synchronized void touch(String sessionId) {
        Session s = byId.get(sessionId);
        if (s != null) s.touchActivity(System.currentTimeMillis());
    }

    public synchronized void markDisconnected(String sessionId) {
        Session s = byId.get(sessionId);
        if (s != null) s.markWsDisconnected(System.currentTimeMillis());
    }

    /**
     * M5.5：WS op 成功后由 WebServer 调用，把当前 {@link ProjectState} 存 walls 表
     * （仅 UPDATE project_json，不动 mapIds / wall_id）。非主线程调用即可。
     */
    public void persistWall(String sessionId) {
        Session s;
        synchronized (this) {
            s = byId.get(sessionId);
        }
        if (s == null || s.wallId() == null || s.projectState() == null) return;
        wallRepo.updateState(s.wallId(), s.projectState());
    }

    // ---------- /canvas cancel ----------

    /**
     * 释放 session（不删 wall）。任何非 CLOSING 状态都可 cancel；不归还池（wall 一直占）；
     * 仅清 byPlayer / byWall / forgetHooks。M5.5 起 wall 数据生命周期与 session 解耦。
     */
    public synchronized void cancel(String sessionId, String reason) {
        Session s = byId.get(sessionId);
        if (s == null) return;
        if (s.state() == SessionState.CLOSING) return;
        s.state(SessionState.CLOSING);

        // 不归还池！wall 一直占着 maps，等 /canvas delete 才释放
        releaseLocks(s);
        auditLog.record("SESSION_CANCEL", s.playerUuid().toString(), s.playerName(),
                sessionId, null, Map.of("reason", reason == null ? "" : reason));
        forget(s);
    }

    /**
     * `/canvas delete <wall_id> confirm`：彻底删除 wall（释放 map → FREE，删 walls 行）。
     * 调用方负责拆 ItemFrames 和 cancel 任何活跃 session。
     */
    public synchronized boolean deleteWall(String wallId) {
        if (wallId == null) return false;
        // 若有活跃 session 持此 wall，先 cancel
        for (Session s : new ArrayList<>(byId.values())) {
            if (wallId.equals(s.wallId()) && s.state() != SessionState.CLOSING) {
                s.state(SessionState.CLOSING);
                releaseLocks(s);
                forget(s);
            }
        }
        List<Integer> released = mapPool.releaseWall(wallId);
        // M15.3 P0-25：清像素缓存，防 mapId 复用导致旧像素显示在新 wall。
        if (canvasRenderer != null && !released.isEmpty()) {
            canvasRenderer.invalidate(released);
        }
        wallRepo.delete(wallId);
        auditLog.record("WALL_DELETE", null, null, null, null,
                Map.of("wall_id", wallId, "released_maps", released.size()));
        return true;
    }

    // ---------- 查询 ----------

    public synchronized Session byPlayer(UUID playerUuid) {
        String id = byPlayer.get(playerUuid);
        return id == null ? null : byId.get(id);
    }

    public synchronized Session byId(String sessionId) {
        return byId.get(sessionId);
    }

    /** 给 {@link MapPool#detectLeaks} 用：当前所有活跃（非 CLOSING）会话 id。 */
    public synchronized Set<String> liveSessionIds() {
        Set<String> out = new HashSet<>();
        for (Session s : byId.values()) {
            if (s.state() != SessionState.CLOSING) out.add(s.id());
        }
        return Collections.unmodifiableSet(out);
    }

    public synchronized int size() {
        return byId.size();
    }

    /**
     * 2026-05-14：对所有活跃 session 的 {@link moe.hikari.canvas.state.EditSession#purgeStaleStrokes}
     * 一并调用。由 {@link SessionReaper} 周期触发——确保用户永久离开后服务端
     * 不会积压 stroke buffer 内存（M12 brush 引入的潜在泄漏）。
     */
    public synchronized void purgeAllStaleStrokes() {
        for (Session s : byId.values()) {
            moe.hikari.canvas.state.EditSession es = s.editSession();
            if (es != null) es.purgeStaleStrokes();
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

    public synchronized List<ExpiredSession> collectExpired(
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

    private void releaseLocks(Session s) {
        if (s.wallKey() != null) byWall.remove(s.wallKey(), s.id());
    }

    private void forget(Session s) {
        byId.remove(s.id());
        byPlayer.remove(s.playerUuid(), s.id());
        s.state(SessionState.CLOSING);
        // 让 throttler / rate-limiter / WS ctx map 跟着清，避免长期运行后内存膨胀。
        // hooks 是 CopyOnWriteArrayList，遍历期间可并发 add。异常不影响其他 hook 执行。
        for (Consumer<String> hook : forgetHooks) {
            try {
                hook.accept(s.id());
            } catch (RuntimeException e) {
                log.warning("SessionManager: forget hook threw for " + s.id() + ": " + e.getMessage());
            }
        }
    }
}
