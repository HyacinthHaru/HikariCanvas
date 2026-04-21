package moe.hikari.canvas.session;

import moe.hikari.canvas.deploy.WallResolver;
import moe.hikari.canvas.pool.MapPool;
import moe.hikari.canvas.pool.PoolExhaustedException;
import moe.hikari.canvas.storage.AuditLog;
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

    private final Map<String, Session> byId = new HashMap<>();
    private final Map<UUID, String> byPlayer = new HashMap<>();
    private final Map<WallKey, String> byWall = new HashMap<>();

    public SessionManager(Logger log, MapPool mapPool, WallResolver wallResolver, AuditLog auditLog) {
        this.log = log;
        this.mapPool = mapPool;
        this.wallResolver = wallResolver;
        this.auditLog = auditLog;
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
        record Ok(Session session, WallResolver.Result.Ok wall, List<Integer> mapIds) implements ConfirmResult {}
        record NotReady(String detail) implements ConfirmResult {}
        record WallFailed(WallResolver.Result.Failed reason) implements ConfirmResult {}
        record WallOccupied(String otherSessionId, UUID otherPlayer) implements ConfirmResult {}
        record PoolExhausted(String message) implements ConfirmResult {}
    }

    /**
     * {@code SELECTING → ISSUED}：解析墙面 → 校验排他锁 → 借池 → 挂锁。
     * <b>必须主线程</b>（MapPool.reserve 可能 createMap）。
     *
     * <p>幂等容错：非 SELECTING 状态（已 ISSUED/ACTIVE/CLOSING）会返回
     * {@link ConfirmResult.NotReady} 而不是抛异常——调用方 pattern-match 出"请先 cancel"。</p>
     */
    public synchronized ConfirmResult confirm(String sessionId) {
        Session s = byId.get(sessionId);
        if (s == null) {
            return new ConfirmResult.NotReady("session not found");
        }
        if (s.state() != SessionState.SELECTING) {
            return new ConfirmResult.NotReady(
                    "session already in state " + s.state() + " — use /canvas cancel to reset");
        }
        if (s.pos1() == null || s.pos2() == null) {
            return new ConfirmResult.NotReady("please click both corners first");
        }

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

        List<Integer> mapIds;
        try {
            mapIds = mapPool.reserve(sessionId, wall.mapCount());
        } catch (PoolExhaustedException e) {
            return new ConfirmResult.PoolExhausted(e.getMessage());
        }

        s.wall(wall);
        s.mapIds(mapIds);
        s.wallKey(key);
        s.state(SessionState.ISSUED);
        byWall.put(key, sessionId);

        auditLog.record("SESSION_CONFIRM", s.playerUuid().toString(), s.playerName(),
                sessionId, null,
                Map.of("wall", Map.of(
                        "world", wall.world().getName(),
                        "origin", List.of(wall.minX(), wall.minY(), wall.minZ()),
                        "w", wall.width(), "h", wall.height(),
                        "facing", wall.facing().name()),
                        "map_count", mapIds.size()));
        return new ConfirmResult.Ok(s, wall, mapIds);
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

    // ---------- /canvas cancel ----------

    /** 任何非 CLOSING 状态都可 cancel；归还池、释放锁。<b>可能触发 MapPool 操作，限主线程</b>。 */
    public synchronized void cancel(String sessionId, String reason) {
        Session s = byId.get(sessionId);
        if (s == null) return;
        if (s.state() == SessionState.CLOSING) return;
        s.state(SessionState.CLOSING);

        if (s.mapIds() != null && !s.mapIds().isEmpty()) {
            mapPool.returnToPool(sessionId);
        }
        releaseLocks(s);
        auditLog.record("SESSION_CANCEL", s.playerUuid().toString(), s.playerName(),
                sessionId, null, Map.of("reason", reason == null ? "" : reason));
        forget(s);
    }

    // ---------- /canvas commit ----------

    public sealed interface CommitResult {
        record Ok(List<Integer> mapIds, String world) implements CommitResult {}
        record NotActive(SessionState current) implements CommitResult {}
    }

    /**
     * {@code ACTIVE → CLOSING → CLOSED}：MapPool 转 PERMANENT + 释放锁。
     * SignRecord 的 DB 写入由调用方（M2-T11 commit 命令 / T10 WS op=commit）负责。
     * <b>必须主线程</b>。
     */
    public synchronized CommitResult commit(String sessionId, String signId) {
        Session s = requireSession(sessionId);
        if (s.state() != SessionState.ACTIVE && s.state() != SessionState.ISSUED) {
            return new CommitResult.NotActive(s.state());
        }
        s.state(SessionState.CLOSING);

        String worldName = s.wall().world().getName();
        mapPool.promoteToPermanent(sessionId, signId, worldName);
        releaseLocks(s);

        auditLog.record("SESSION_COMMIT", s.playerUuid().toString(), s.playerName(),
                sessionId, null,
                Map.of("sign_id", signId, "map_count", s.mapIds().size()));
        List<Integer> mapIds = s.mapIds();
        forget(s);
        return new CommitResult.Ok(mapIds, worldName);
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
    }
}
