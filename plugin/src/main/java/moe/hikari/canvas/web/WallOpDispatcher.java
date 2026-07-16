package moe.hikari.canvas.web;

import io.javalin.websocket.WsMessageContext;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;

import java.util.Map;
import java.util.regex.Pattern;

import static moe.hikari.canvas.web.WebHelpers.asPayloadMap;
import static moe.hikari.canvas.web.WebHelpers.stringOrNull;

/**
 * wall 元数据 op 分发：{@code wall.lock / unlock / alias / refresh}。
 * <p>lock/unlock 为 owner-only；refresh 切回主线程做
 * 方块 + entity 修复。</p>
 */
final class WallOpDispatcher {

    /** wall alias 字符集：字母数字 _ -，长度 2-32。前端 / WS / 命令三路统一校验。 */
    private static final Pattern ALIAS_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]{2,32}$");

    private final SessionManager sessionManager;
    private final moe.hikari.canvas.storage.WallRepo wallRepo;
    private final moe.hikari.canvas.deploy.FrameDeployer frameDeployer;
    private final moe.hikari.canvas.render.ProjectionThrottler throttler;
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final moe.hikari.canvas.storage.AuditLog auditLog;

    WallOpDispatcher(SessionManager sessionManager,
                     moe.hikari.canvas.storage.WallRepo wallRepo,
                     moe.hikari.canvas.deploy.FrameDeployer frameDeployer,
                     moe.hikari.canvas.render.ProjectionThrottler throttler,
                     org.bukkit.plugin.java.JavaPlugin plugin) {
        this(sessionManager, wallRepo, frameDeployer, throttler, plugin, null);
    }

    WallOpDispatcher(SessionManager sessionManager,
                     moe.hikari.canvas.storage.WallRepo wallRepo,
                     moe.hikari.canvas.deploy.FrameDeployer frameDeployer,
                     moe.hikari.canvas.render.ProjectionThrottler throttler,
                     org.bukkit.plugin.java.JavaPlugin plugin,
                     moe.hikari.canvas.storage.AuditLog auditLog) {
        this.sessionManager = sessionManager;
        this.wallRepo = wallRepo;
        this.frameDeployer = frameDeployer;
        this.throttler = throttler;
        this.plugin = plugin;
        this.auditLog = auditLog;
    }

    /** 入口：payload 解析后转 {@link #handleWallOp}。 */
    void dispatch(WsMessageContext ctx, Envelope in, String sessionId) {
        Map<String, Object> payload;
        try {
            payload = asPayloadMap(in.payload());
        } catch (IllegalArgumentException iae) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", iae.getMessage()));
            return;
        }
        handleWallOp(ctx, sessionId, in, payload);
    }

    /** wall.alias / wall.refresh / wall.lock / wall.unlock（owner-only）。
     *  不影响 ProjectState，绕开 EditSession。 */
    private void handleWallOp(WsMessageContext ctx, String sessionId,
                              Envelope in, Map<String, Object> payload) {
        Session s = sessionManager.byId(sessionId);
        if (s == null || s.wallId() == null) {
            ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "session has no bound wall"));
            return;
        }
        String wallId = s.wallId();
        switch (in.op()) {
            case "wall.lock" -> {
                // owner-only：只有 wall 创建者（owner_uuid）能锁
                var wall = wallRepo.loadById(wallId).orElse(null);
                if (wall == null) {
                    ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "wall not found"));
                    return;
                }
                if (!wall.ownerUuid().equals(s.playerUuid())) {
                    ctx.send(Envelope.error(in.id(), "FORBIDDEN", "only wall owner can lock"));
                    return;
                }
                Long ts = wallRepo.markPublished(wallId);
                if (ts == null) {
                    ctx.send(Envelope.error(in.id(), "INTERNAL_ERROR", "lock failed"));
                    return;
                }
                // owner 锁定画板留痕（防误锁纠纷 / 后续 audit 查谁锁的）
                if (auditLog != null) {
                    java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
                    details.put("wall_id", wallId);
                    details.put("locked_at", ts);
                    auditLog.record("WALL_LOCK", s.playerUuid().toString(), s.playerName(),
                            sessionId, null, details);
                }
                ctx.send(Envelope.of("ack", in.id(), Map.of("lockedAt", ts)));
            }
            case "wall.unlock" -> {
                // owner-only：只有 wall 创建者能解锁
                var wall = wallRepo.loadById(wallId).orElse(null);
                if (wall == null) {
                    ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "wall not found"));
                    return;
                }
                if (!wall.ownerUuid().equals(s.playerUuid())) {
                    ctx.send(Envelope.error(in.id(), "FORBIDDEN", "only wall owner can unlock"));
                    return;
                }
                wallRepo.markUnpublished(wallId);
                // owner 解锁画板留痕（对应 WALL_LOCK 的对偶事件）
                if (auditLog != null) {
                    java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
                    details.put("wall_id", wallId);
                    auditLog.record("WALL_UNLOCK", s.playerUuid().toString(), s.playerName(),
                            sessionId, null, details);
                }
                // 全局 JsonInclude.NON_NULL 会把 lockedAt: null 字段吞掉，前端收空对象；
                // 改用显式布尔 locked: false（协议变更，前端 wsClient.ts 同步调整）
                ctx.send(Envelope.of("ack", in.id(), Map.of("locked", false)));
            }
            case "wall.alias" -> {
                String alias = stringOrNull(payload.get("alias"));
                if (alias == null || !ALIAS_PATTERN.matcher(alias).matches()) {
                    ctx.send(Envelope.error(in.id(), "INVALID_ALIAS_FORMAT",
                            "alias must match [A-Za-z0-9_-]{2,32}"));
                    return;
                }
                // owner-only（或带 canvas.alias.any 权限）。与命令侧 CanvasCommand.runAlias 同款检查。
                // 离线玩家无法走 hasPermission，bypass 视为 false（与 SessionManager.open 处理一致）。
                var wallOpt = wallRepo.loadById(wallId);
                if (wallOpt.isEmpty()) {
                    ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "wall not found"));
                    return;
                }
                var wall = wallOpt.get();
                boolean isOwner = wall.ownerUuid().equals(s.playerUuid());
                if (!isOwner) {
                    // 主线程解析权限（Bukkit.getPlayer + hasPermission 主线程专用）；离线 / 超时返 false。
                    boolean canAny = MainThreadPerms.hasPermission(plugin, s.playerUuid(), "canvas.alias.any");
                    if (!canAny) {
                        // 非 owner 尝试改 alias 留痕——可观测异常尝试
                        if (auditLog != null) {
                            java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
                            details.put("operation", "alias");
                            details.put("wall_id", wallId);
                            details.put("caller", s.playerUuid().toString());
                            auditLog.record("PERMISSION_DENIED",
                                    s.playerUuid().toString(), s.playerName(),
                                    sessionId, null, details);
                        }
                        ctx.send(Envelope.error(in.id(), "FORBIDDEN",
                                "only wall owner can change alias"));
                        return;
                    }
                }
                String oldAlias = wall.alias();
                boolean ok = wallRepo.setAlias(wallId, alias);
                if (!ok) {
                    ctx.send(Envelope.error(in.id(), "ALIAS_TAKEN",
                            "alias '" + alias + "' is already in use"));
                    return;
                }
                if (auditLog != null) {
                    java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
                    details.put("wall_id", wallId);
                    details.put("old_alias", oldAlias);
                    details.put("new_alias", alias);
                    auditLog.record("WALL_ALIAS", s.playerUuid().toString(), s.playerName(),
                            sessionId, null, details);
                }
                ctx.send(Envelope.of("ack", in.id(), Map.of("alias", alias)));
            }
            case "wall.refresh" -> {
                // 玩家撸掉了支撑方块或画框 → 补回方块 + 补 spawn 缺失画框 + 全画布重画
                if (s.wall() == null || s.mapIds() == null) {
                    ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND",
                            "session lacks wall geometry"));
                    return;
                }
                final moe.hikari.canvas.deploy.WallResolver.Result.Ok geom = s.wall();
                final java.util.List<Integer> mapIds = s.mapIds();
                final String ackId = in.id();
                // 主线程跑（动方块 + spawn entity），完成后回 ack 到当前 WS ctx（Jetty 线程安全）
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    // 整个 task 体包 try/catch。repairFor 动方块/spawn entity 任一步抛
                    // RuntimeException 时，原代码异常逃出 task 体 → 仅 Bukkit 默认日志，前端永不收 ack
                    // → 8s ack_timeout 误报"无响应"。改为捕获后记 SEVERE + 回 INTERNAL_ERROR 让前端拿真错码。
                    try {
                        moe.hikari.canvas.deploy.FrameDeployer.RepairResult result =
                                frameDeployer.repairFor(wallId, geom, mapIds);
                        if (s.projectState() != null) {
                            throttler.submit(sessionId,
                                    moe.hikari.canvas.render.DirtyRegion.fullCanvas(s.projectState()));
                        }
                        plugin.getLogger().info("wall.refresh: wall=" + wallId
                                + " framesRespawned=" + result.framesRespawned()
                                + " framesReAttached=" + result.framesReAttached()
                                + " wallBlocksReplaced=" + result.wallBlocksReplaced());
                        java.util.LinkedHashMap<String, Object> ackPayload = new java.util.LinkedHashMap<>();
                        // 给前端的"重挂"是 spawn 新 frame + 给空 frame 塞回 map 的总和
                        ackPayload.put("framesRespawned", result.framesFixed());
                        ackPayload.put("framesReAttached", result.framesReAttached());
                        ackPayload.put("wallBlocksReplaced", result.wallBlocksReplaced());
                        ctx.send(Envelope.of("ack", ackId, ackPayload));
                    } catch (Exception ex) {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                "wall.refresh repair failed for wall=" + wallId, ex);
                        try {
                            ctx.send(Envelope.error(ackId, "INTERNAL_ERROR",
                                    "wall refresh failed"));
                        } catch (Exception ignored) {
                            // WS 可能已断开；忽略
                        }
                    }
                });
            }
            default -> ctx.send(Envelope.error(in.id(), "INVALID_OP",
                    "unknown wall op: " + in.op()));
        }
    }
}
