package moe.hikari.canvas.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import moe.hikari.canvas.deploy.CanvasWand;
import moe.hikari.canvas.deploy.FrameDeployer;
import moe.hikari.canvas.deploy.WallResolver;
import moe.hikari.canvas.pool.MapPool;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.session.SessionState;
import moe.hikari.canvas.session.TokenService;
import moe.hikari.canvas.storage.Database;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * {@code /canvas} 根命令的 Brigadier 注册点，M2-T11 完整实装。
 *
 * <p>正式子命令：
 * <ul>
 *   <li>{@code edit / wand / cancel} — 玩家侧交互入口（M2-T6）</li>
 *   <li>{@code confirm} — 锁定墙面、借池、挂物品框、签发 token、发可点击 URL</li>
 *   <li>{@code commit}  — 转 PERMANENT、写 sign_records、清 wand</li>
 *   <li>{@code stats}   — 管理员：MapPool + 活跃会话统计</li>
 *   <li>{@code cleanup} — 管理员：M2 阶段 stub，实际数据回收留给 M7 fsck</li>
 * </ul>
 *
 * <p>权限（见 {@code docs/security.md §5}）：
 * <ul>
 *   <li>edit / wand / confirm / cancel → {@code canvas.edit}</li>
 *   <li>commit → {@code canvas.commit}</li>
 *   <li>stats / cleanup → {@code canvas.admin}</li>
 * </ul>
 */
public final class CanvasCommand {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JavaPlugin plugin;
    private final SessionManager sessionManager;
    private final FrameDeployer frameDeployer;
    private final TokenService tokenService;
    private final MapPool mapPool;
    private final Database database;
    /** 形如 {@code http://host:port/?token={token}}；{token} 占位符会被替换。 */
    private final String editorUrlTemplate;

    public CanvasCommand(JavaPlugin plugin,
                         SessionManager sessionManager,
                         FrameDeployer frameDeployer,
                         TokenService tokenService,
                         MapPool mapPool,
                         Database database,
                         String editorUrlTemplate) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
        this.frameDeployer = frameDeployer;
        this.tokenService = tokenService;
        this.mapPool = mapPool;
        this.database = database;
        this.editorUrlTemplate = editorUrlTemplate;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("canvas")
                .then(Commands.literal("edit")
                        .requires(src -> isPlayerWith(src, "canvas.edit"))
                        .executes(this::runEdit))
                .then(Commands.literal("wand")
                        .requires(src -> isPlayerWith(src, "canvas.edit"))
                        .executes(this::runWand))
                .then(Commands.literal("confirm")
                        .requires(src -> isPlayerWith(src, "canvas.edit"))
                        .executes(this::runConfirm))
                .then(Commands.literal("cancel")
                        .requires(src -> isPlayerWith(src, "canvas.edit"))
                        .executes(this::runCancel))
                .then(Commands.literal("commit")
                        .requires(src -> isPlayerWith(src, "canvas.commit"))
                        .executes(this::runCommit))
                .then(Commands.literal("stats")
                        .requires(src -> src.getSender().hasPermission("canvas.admin"))
                        .executes(this::runStats))
                .then(Commands.literal("cleanup")
                        .requires(src -> src.getSender().hasPermission("canvas.admin"))
                        .executes(this::runCleanup))
                .build();
    }

    private static boolean isPlayerWith(CommandSourceStack src, String permission) {
        return src.getSender() instanceof Player p && p.hasPermission(permission);
    }

    // ---------- edit / wand / cancel ----------

    private int runEdit(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        SessionManager.BeginResult r = sessionManager.beginSelecting(
                player.getUniqueId(), player.getName());
        if (r instanceof SessionManager.BeginResult.Ok) {
            player.sendMessage(Component.text(
                    "Selection mode on. Left-click first corner, right-click second corner, then /canvas confirm.",
                    NamedTextColor.GOLD));
        } else if (r instanceof SessionManager.BeginResult.AlreadyHasSession ex) {
            player.sendMessage(Component.text(
                    "You already have an active canvas session (state=" + ex.existing().state()
                            + "). Use /canvas cancel first.",
                    NamedTextColor.RED));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int runWand(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        player.getInventory().addItem(CanvasWand.forPlayer(plugin, player));
        player.sendMessage(Component.text(
                "Received Canvas Wand. Left-click / right-click blocks to select corners.",
                NamedTextColor.GOLD));
        return Command.SINGLE_SUCCESS;
    }

    private int runCancel(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        Session s = sessionManager.byPlayer(player.getUniqueId());
        if (s == null) {
            player.sendMessage(Component.text("No active session.", NamedTextColor.GRAY));
            return 0;
        }
        // 先快照 world 引用（cancel 后 session 被 forget）
        String sid = s.id();
        SessionState prev = s.state();
        World wallWorld = s.wall() != null ? s.wall().world() : null;

        sessionManager.cancel(sid, "player-cancel");

        int frames = 0;
        if (wallWorld != null) {
            frames = frameDeployer.removeForSession(sid, wallWorld);
        }
        int wands = CanvasWand.removeAllFrom(player, plugin);

        StringBuilder msg = new StringBuilder("Session cancelled (was " + prev + ").");
        if (frames > 0) msg.append(" ").append(frames).append(" frames removed.");
        if (wands > 0) msg.append(" Wand returned.");
        player.sendMessage(Component.text(msg.toString(), NamedTextColor.YELLOW));
        return Command.SINGLE_SUCCESS;
    }

    // ---------- confirm ----------

    private int runConfirm(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        Session s = sessionManager.byPlayer(player.getUniqueId());
        if (s == null) {
            player.sendMessage(Component.text("No active session. Run /canvas edit first.",
                    NamedTextColor.RED));
            return 0;
        }

        SessionManager.ConfirmResult result = sessionManager.confirm(s.id());
        if (result instanceof SessionManager.ConfirmResult.NotReady nr) {
            player.sendMessage(Component.text("Not ready: " + nr.detail(), NamedTextColor.RED));
        } else if (result instanceof SessionManager.ConfirmResult.WallFailed wf) {
            player.sendMessage(Component.text(
                    "Wall invalid: " + wf.reason().reason() + " — " + wf.reason().detail(),
                    NamedTextColor.RED));
        } else if (result instanceof SessionManager.ConfirmResult.WallOccupied wo) {
            player.sendMessage(Component.text(
                    "This wall is already being edited (session " + wo.otherSessionId() + ").",
                    NamedTextColor.RED));
        } else if (result instanceof SessionManager.ConfirmResult.PoolExhausted pe) {
            player.sendMessage(Component.text(
                    "Pool exhausted: " + pe.message() + ". Try smaller wall or wait.",
                    NamedTextColor.RED));
        } else if (result instanceof SessionManager.ConfirmResult.Ok ok) {
            // 挂物品框
            int mounted;
            try {
                mounted = frameDeployer.deploy(ok.session(), ok.wall(), ok.mapIds());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "FrameDeployer.deploy failed", e);
                // 回滚：SessionManager.cancel 会归还池
                sessionManager.cancel(ok.session().id(), "deploy-failed");
                player.sendMessage(Component.text("Frame deployment failed: " + e.getMessage(),
                        NamedTextColor.RED));
                return 0;
            }

            // 签发 token（15min TTL）
            String token = tokenService.issue(
                    player.getUniqueId(), player.getName(), ok.session().id());
            String url = editorUrlTemplate.replace("{token}", token);

            player.sendMessage(Component.text(
                    "Wall confirmed: " + ok.wall().width() + "×" + ok.wall().height()
                            + " (" + mounted + " frames). Editor token valid 15 min.",
                    NamedTextColor.GREEN));
            player.sendMessage(Component.text("Open editor: ", NamedTextColor.GRAY)
                    .append(Component.text(url, NamedTextColor.AQUA)
                            .decorate(TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.openUrl(url))
                            .hoverEvent(HoverEvent.showText(Component.text(
                                    "Click to open in browser")))));

            // 移除玩家 inventory 里的 wand（契约：confirm 后 wand 消失）
            CanvasWand.removeAllFrom(player, plugin);
        }
        return Command.SINGLE_SUCCESS;
    }

    // ---------- commit ----------

    private int runCommit(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        Session s = sessionManager.byPlayer(player.getUniqueId());
        if (s == null) {
            player.sendMessage(Component.text("No active session.", NamedTextColor.GRAY));
            return 0;
        }
        // 快照 session 信息；commit 后 s 会被 forget
        WallResolver.Result.Ok wall = s.wall();
        List<Integer> mapIds = s.mapIds();
        UUID ownerUuid = s.playerUuid();
        String ownerName = s.playerName();
        if (wall == null || mapIds == null) {
            player.sendMessage(Component.text(
                    "Nothing to commit—run /canvas confirm first.", NamedTextColor.RED));
            return 0;
        }

        String signId = UUID.randomUUID().toString();
        SessionManager.CommitResult r = sessionManager.commit(s.id(), signId);
        if (r instanceof SessionManager.CommitResult.NotActive na) {
            player.sendMessage(Component.text(
                    "Cannot commit in state " + na.current() + ".", NamedTextColor.RED));
            return 0;
        }
        // Ok
        int promoted = frameDeployer.promote(s.id(), signId, wall.world());
        insertSignRecord(signId, ownerUuid, ownerName, wall, mapIds);
        CanvasWand.removeAllFrom(player, plugin);

        player.sendMessage(Component.text(
                "Committed sign " + signId.substring(0, 8) + "… (" + promoted + " frames).",
                NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private void insertSignRecord(String signId, UUID ownerUuid, String ownerName,
                                  WallResolver.Result.Ok wall, List<Integer> mapIds) {
        long now = System.currentTimeMillis();
        String mapIdsJson;
        try {
            mapIdsJson = JSON.writeValueAsString(mapIds);
        } catch (Exception e) {
            mapIdsJson = "[]";
            plugin.getLogger().log(Level.WARNING, "serialize mapIds failed", e);
        }
        final String finalMapIdsJson = mapIdsJson;
        try {
            database.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO sign_records (id, owner_uuid, owner_name, world, "
                            + "origin_x, origin_y, origin_z, facing, width_maps, height_maps, "
                            + "map_ids, project_json, template_id, template_version, "
                            + "created_at, updated_at, deleted_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    signId, ownerUuid.toString(), ownerName, wall.world().getName(),
                    wall.minX(), wall.minY(), wall.minZ(), wall.facing().name(),
                    wall.width(), wall.height(),
                    finalMapIdsJson, "{}", null, null,
                    now, now, null));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "INSERT sign_records failed for " + signId, e);
        }
    }

    // ---------- admin ----------

    private int runStats(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MapPool.Stats ps = mapPool.stats();
        sender.sendMessage(Component.text(String.format(
                        "MapPool: total=%d  free=%d  reserved=%d  permanent=%d   "
                                + "|   Sessions: %d   |   Tokens: %d",
                        ps.total(), ps.free(), ps.reserved(), ps.permanent(),
                        sessionManager.size(), tokenService.activeCount()),
                NamedTextColor.GOLD));
        return Command.SINGLE_SUCCESS;
    }

    private int runCleanup(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        // M2 阶段 stub：扫 sign_records 里 deleted_at 非空的行并报数
        // 真正的 map 回收 + 物品框清除 + DB 删除留给 M7 的 /canvas fsck 实装
        int softDeleted = database.jdbi().withHandle(h -> h.createQuery(
                        "SELECT COUNT(*) FROM sign_records WHERE deleted_at IS NOT NULL")
                .mapTo(Integer.class).one());
        sender.sendMessage(Component.text(
                "cleanup is stubbed in M2. Soft-deleted sign_records: " + softDeleted
                        + " (actual reclamation implemented in M7 fsck).",
                NamedTextColor.YELLOW));
        return Command.SINGLE_SUCCESS;
    }
}
