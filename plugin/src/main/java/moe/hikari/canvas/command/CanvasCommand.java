package moe.hikari.canvas.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import moe.hikari.canvas.storage.WallRepo;
import moe.hikari.canvas.template.TemplateRegistry;
import moe.hikari.canvas.template.preview.TemplatePreviewService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

/**
 * {@code /canvas} 根命令。M5.5 wall 模型起命令族（详见 docs/architecture.md §3、PROPOSAL.md §5.2.x）：
 * <ul>
 *   <li>{@code edit / wand / cancel} — 选区入口</li>
 *   <li>{@code confirm} — 锁定墙面，新建 wall（部署 ItemFrame + 借池）或 bind 现有 wall（不部署）+ 签发 token URL</li>
 *   <li>{@code open <wall_id\|alias>} — 直接打开已有 wall（绕过 SELECTING）</li>
 *   <li>{@code list} — 玩家自己的画清单</li>
 *   <li>{@code alias} — wall 元数据修改（标签层）</li>
 *   <li>~~{@code publish / unpublish}~~ 2026-05-14 砍：lock 状态由前端 TopBar 按钮触发 ws 的 wall.lock/unlock</li>
 *   <li>{@code delete <wall_id> [confirm]} — 删除 wall（30s 二次确认）</li>
 *   <li>{@code stats / cleanup} — 管理员</li>
 * </ul>
 *
 * <p>M5.5 起 {@code commit} 子命令彻底废止——保存通过 op auto-save 实现。</p>
 */
public final class CanvasCommand {

    /** delete 二次确认的窗口（毫秒）。在此期间内 player 再敲带 confirm 才真删。 */
    private static final long DELETE_CONFIRM_WINDOW_MS = 30_000;
    /** wall alias 字符集：字母数字 _ -，长度 2-32。与 WebServer / 前端三路一致。 */
    private static final java.util.regex.Pattern ALIAS_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{2,32}$");

    private final JavaPlugin plugin;
    private final SessionManager sessionManager;
    private final FrameDeployer frameDeployer;
    private final TokenService tokenService;
    private final MapPool mapPool;
    private final Database database;
    private final WallRepo wallRepo;
    private final TemplateRegistry templateRegistry;
    private final TemplatePreviewService templatePreviewService;
    /** 形如 {@code http://host:port/?token={token}}；{token} 占位符会被替换。 */
    private final String editorUrlTemplate;

    /** 玩家最近一次 /canvas delete <id> 的待确认信息：playerUuid → (wallId, ts)。 */
    private final ConcurrentMap<UUID, PendingDelete> pendingDeletes = new ConcurrentHashMap<>();

    private record PendingDelete(String wallId, long ts) {}

    public CanvasCommand(JavaPlugin plugin,
                         SessionManager sessionManager,
                         FrameDeployer frameDeployer,
                         TokenService tokenService,
                         MapPool mapPool,
                         Database database,
                         WallRepo wallRepo,
                         TemplateRegistry templateRegistry,
                         TemplatePreviewService templatePreviewService,
                         String editorUrlTemplate) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
        this.frameDeployer = frameDeployer;
        this.tokenService = tokenService;
        this.mapPool = mapPool;
        this.database = database;
        this.wallRepo = wallRepo;
        this.templateRegistry = templateRegistry;
        this.templatePreviewService = templatePreviewService;
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
                .then(Commands.literal("open")
                        .requires(src -> isPlayerWith(src, "canvas.edit"))
                        .then(Commands.argument("id_or_alias", StringArgumentType.word())
                                .executes(this::runOpen)))
                .then(Commands.literal("list")
                        .requires(src -> isPlayerWith(src, "canvas.edit"))
                        .executes(this::runList))
                // 2026-05-14 lock-state 重设计：/canvas publish 与 /canvas unpublish 砍。
                // 锁定 / 解锁由前端浏览器 TopBar 的 Lock 按钮触发 WS op wall.lock/wall.unlock。
                .then(Commands.literal("alias")
                        .requires(src -> isPlayerWith(src, "canvas.edit"))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(this::runAlias)))
                .then(Commands.literal("delete")
                        .requires(src -> isPlayerWith(src, "canvas.delete.own"))
                        .then(Commands.argument("wall_id", StringArgumentType.word())
                                .executes(this::runDeleteFirstStep)
                                .then(Commands.literal("confirm")
                                        .executes(this::runDeleteConfirm))))
                .then(Commands.literal("stats")
                        .requires(src -> src.getSender().hasPermission("canvas.admin"))
                        .executes(this::runStats))
                .then(Commands.literal("cleanup")
                        .requires(src -> src.getSender().hasPermission("canvas.admin"))
                        .executes(this::runCleanup))
                .then(Commands.literal("reload")
                        .requires(src -> src.getSender().hasPermission("canvas.admin"))
                        .then(Commands.literal("templates")
                                .executes(this::runReloadTemplates))
                        .then(Commands.literal("config")
                                .executes(this::runReloadConfig)))
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
            ensureWand(player);
            sendEditGuide(player, "Selection mode on. Pick the wall corners:");
        } else if (r instanceof SessionManager.BeginResult.AlreadyHasSession ex) {
            Session existing = ex.existing();
            switch (existing.state()) {
                case SELECTING -> {
                    // M5-D8：隐式 reselect，避免玩家被卡在"已选一半"
                    sessionManager.resetSelection(existing.id());
                    ensureWand(player);
                    sendEditGuide(player, "Selection reset. Pick the wall corners again:");
                }
                case ISSUED, ACTIVE -> player.sendMessage(Component.text(
                        "Editor is already open. Use /canvas cancel to close it, "
                                + "or open the URL from the previous /canvas confirm.",
                        NamedTextColor.YELLOW));
                case CLOSING -> player.sendMessage(Component.text(
                        "Session is closing; try again in a moment.",
                        NamedTextColor.GRAY));
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private void ensureWand(Player player) {
        if (!CanvasWand.hasWand(player, plugin)) {
            player.getInventory().addItem(CanvasWand.forPlayer(plugin, player));
        }
    }

    private void sendEditGuide(Player player, String headline) {
        player.sendMessage(Component.text(headline, NamedTextColor.GOLD));
        player.sendMessage(Component.text("  ① Left-click the first wall block (or item frame on it)",
                NamedTextColor.GRAY));
        player.sendMessage(Component.text("  ② Right-click the opposite corner block",
                NamedTextColor.GRAY));
        player.sendMessage(Component.text("  ③ Run /canvas confirm to open the editor",
                NamedTextColor.GRAY));
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
        // M5.5：cancel 仅释放 session/wand；wall 数据 + ItemFrames 保留
        String sid = s.id();
        SessionState prev = s.state();
        sessionManager.cancel(sid, "player-cancel");
        int wands = CanvasWand.removeAllFrom(player, plugin);

        StringBuilder msg = new StringBuilder("Session cancelled (was " + prev + ").");
        if (wands > 0) msg.append(" Wand returned.");
        msg.append(" Wall data preserved—use /canvas open or right-click ItemFrame to resume.");
        player.sendMessage(Component.text(msg.toString(), NamedTextColor.YELLOW));
        return Command.SINGLE_SUCCESS;
    }

    // ---------- open / list / alias / delete ----------

    private int runOpen(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        String idOrAlias = StringArgumentType.getString(ctx, "id_or_alias");
        SessionManager.OpenResult r = sessionManager.open(
                player.getUniqueId(), player.getName(), idOrAlias);
        if (r instanceof SessionManager.OpenResult.NotFound) {
            player.sendMessage(Component.text("No such wall: " + idOrAlias, NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        if (r instanceof SessionManager.OpenResult.AlreadyHasSession a) {
            player.sendMessage(Component.text(
                    "You already have an active session (state=" + a.current() + "). Use /canvas cancel first.",
                    NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        if (r instanceof SessionManager.OpenResult.WallOccupied wo) {
            player.sendMessage(Component.text(
                    "Wall is being edited by " + (wo.otherPlayer() == null ? "?" : wo.otherPlayer()) + ".",
                    NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        if (r instanceof SessionManager.OpenResult.BindFailed bf) {
            player.sendMessage(Component.text("Cannot bind wall: " + bf.detail(), NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        SessionManager.OpenResult.Ok ok = (SessionManager.OpenResult.Ok) r;
        // 签发 token
        String token = tokenService.issue(player.getUniqueId(), player.getName(), ok.session().id());
        String url = editorUrlTemplate.replace("{token}", token);
        WallRepo.Wall w = ok.wall();
        String summary = "Opened wall " + w.wallId()
                + (w.alias() != null ? " (alias: " + w.alias() + ")" : "")
                + " · " + w.widthMaps() + "×" + w.heightMaps();
        player.sendMessage(Component.text(summary, NamedTextColor.GREEN));
        player.sendMessage(Component.text("Open editor: ", NamedTextColor.GRAY)
                .append(Component.text(url, NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to open in browser")))));
        return Command.SINGLE_SUCCESS;
    }

    private int runList(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        List<WallRepo.Summary> walls = wallRepo.listForOwner(player.getUniqueId());
        if (walls.isEmpty()) {
            player.sendMessage(Component.text(
                    "You have no canvas walls yet. Use /canvas edit to create one.",
                    NamedTextColor.GRAY));
            return Command.SINGLE_SUCCESS;
        }
        // 简单分组：published 在前
        List<WallRepo.Summary> pub = walls.stream().filter(w -> w.publishedAt() != null).toList();
        List<WallRepo.Summary> drafts = walls.stream().filter(w -> w.publishedAt() == null).toList();
        player.sendMessage(Component.text(
                "Your canvas walls: " + walls.size() + " total ("
                        + pub.size() + " published, " + drafts.size() + " editing)",
                NamedTextColor.GOLD));
        for (WallRepo.Summary w : pub) sendWallLine(player, w, true);
        for (WallRepo.Summary w : drafts) sendWallLine(player, w, false);
        return Command.SINGLE_SUCCESS;
    }

    private void sendWallLine(Player p, WallRepo.Summary w, boolean published) {
        String tag = published ? "[P]" : "[D]";
        String aliasPart = w.alias() != null ? " '" + w.alias() + "'" : "";
        String label = String.format("  %s %s%s · %dx%d · %s (%d,%d,%d) %s",
                tag, w.wallId(), aliasPart, w.widthMaps(), w.heightMaps(),
                w.world(), w.originX(), w.originY(), w.originZ(), w.facing());
        Component line = Component.text(label,
                published ? NamedTextColor.AQUA : NamedTextColor.GRAY)
                .clickEvent(ClickEvent.suggestCommand("/canvas open " + w.wallId()))
                .hoverEvent(HoverEvent.showText(Component.text(
                        "Click to suggest /canvas open " + w.wallId())));
        p.sendMessage(line);
    }

    // 2026-05-14 砍：runPublish / runUnpublish 命令处理器移除。
    // lock 状态由前端 TopBar Lock 按钮 → ws.send('wall.lock' | 'wall.unlock') → WebServer.handleWallOp 处理。

    private int runAlias(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        Session s = sessionManager.byPlayer(player.getUniqueId());
        if (s == null || s.wallId() == null) {
            player.sendMessage(Component.text("No active wall session.", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        String alias = StringArgumentType.getString(ctx, "name");
        if (!ALIAS_PATTERN.matcher(alias).matches()) {
            player.sendMessage(Component.text(
                    "Alias must match [A-Za-z0-9_-]{2,32}.", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        boolean ok = wallRepo.setAlias(s.wallId(), alias);
        if (!ok) {
            player.sendMessage(Component.text(
                    "Alias '" + alias + "' is already in use.", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        player.sendMessage(Component.text(
                "Wall " + s.wallId() + " alias = " + alias, NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private int runDeleteFirstStep(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        String wallId = StringArgumentType.getString(ctx, "wall_id");
        var w = wallRepo.loadById(wallId).orElse(null);
        if (w == null) {
            player.sendMessage(Component.text("No such wall: " + wallId, NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        if (!w.ownerUuid().equals(player.getUniqueId())
                && !player.hasPermission("canvas.delete.any")) {
            player.sendMessage(Component.text(
                    "You can only delete your own walls (need canvas.delete.any otherwise).",
                    NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        pendingDeletes.put(player.getUniqueId(),
                new PendingDelete(wallId, System.currentTimeMillis()));
        player.sendMessage(Component.text(
                "About to delete wall " + wallId
                        + (w.alias() != null ? " '" + w.alias() + "'" : "")
                        + ". Run /canvas delete " + wallId + " confirm within 30s.",
                NamedTextColor.YELLOW));
        return Command.SINGLE_SUCCESS;
    }

    private int runDeleteConfirm(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        String wallId = StringArgumentType.getString(ctx, "wall_id");
        PendingDelete pd = pendingDeletes.remove(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (pd == null || !pd.wallId().equals(wallId)
                || now - pd.ts() > DELETE_CONFIRM_WINDOW_MS) {
            player.sendMessage(Component.text(
                    "Confirm window expired or mismatched. Re-run /canvas delete " + wallId,
                    NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        var w = wallRepo.loadById(wallId).orElse(null);
        if (w == null) {
            player.sendMessage(Component.text("Wall already gone: " + wallId, NamedTextColor.GRAY));
            return Command.SINGLE_SUCCESS;
        }
        if (!w.ownerUuid().equals(player.getUniqueId())
                && !player.hasPermission("canvas.delete.any")) {
            player.sendMessage(Component.text(
                    "You can only delete your own walls.", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        // 拆 ItemFrame
        World world = plugin.getServer().getWorld(w.key().world());
        int frames = world == null ? 0 : frameDeployer.removeForWall(wallId, world);
        // SessionManager.deleteWall 释放池 + 删 walls 行（含 cancel 任何活跃 session）
        sessionManager.deleteWall(wallId);
        player.sendMessage(Component.text(
                "Wall " + wallId + " deleted. " + frames + " frames removed.",
                NamedTextColor.GREEN));
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
            return Command.SINGLE_SUCCESS;
        }
        if (result instanceof SessionManager.ConfirmResult.WallFailed wf) {
            player.sendMessage(Component.text(
                    "Wall invalid: " + wf.reason().reason() + " — " + wf.reason().detail(),
                    NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        if (result instanceof SessionManager.ConfirmResult.WallOccupied wo) {
            player.sendMessage(Component.text(
                    "This wall is already being edited (session " + wo.otherSessionId() + ").",
                    NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        if (result instanceof SessionManager.ConfirmResult.PoolExhausted pe) {
            player.sendMessage(Component.text(
                    "Pool exhausted: " + pe.message() + ". Try smaller wall or wait.",
                    NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }

        Session sessionAfter;
        WallResolver.Result.Ok wall;
        List<Integer> mapIds;
        String wallId;
        boolean newWall;
        if (result instanceof SessionManager.ConfirmResult.OkNewWall ok) {
            sessionAfter = ok.session(); wall = ok.wall(); mapIds = ok.mapIds(); wallId = ok.wallId();
            newWall = true;
        } else if (result instanceof SessionManager.ConfirmResult.OkExistingWall ok) {
            sessionAfter = ok.session(); wall = ok.wall(); mapIds = ok.mapIds(); wallId = ok.wallId();
            newWall = false;
        } else {
            return Command.SINGLE_SUCCESS;
        }

        // 仅"新建"路径部署 ItemFrames；"打开现有"已有物品框，跳过
        int mounted = mapIds.size();
        if (newWall) {
            try {
                mounted = frameDeployer.deploy(sessionAfter, wall, mapIds);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "FrameDeployer.deploy failed", e);
                sessionManager.cancel(sessionAfter.id(), "deploy-failed");
                player.sendMessage(Component.text("Frame deployment failed: " + e.getMessage(),
                        NamedTextColor.RED));
                return Command.SINGLE_SUCCESS;
            }
        }

        // 签发 token（15min TTL）
        String token = tokenService.issue(
                player.getUniqueId(), player.getName(), sessionAfter.id());
        String url = editorUrlTemplate.replace("{token}", token);

        String summary = newWall
                ? "Wall created: " + wallId + " · " + wall.width() + "×" + wall.height()
                        + " (" + mounted + " frames)."
                : "Wall opened: " + wallId + " · " + wall.width() + "×" + wall.height()
                        + " (existing).";
        player.sendMessage(Component.text(summary + " Editor token valid 15 min.",
                NamedTextColor.GREEN));
        player.sendMessage(Component.text("Open editor: ", NamedTextColor.GRAY)
                .append(Component.text(url, NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Click to open in browser")))));

        // 收回 wand（契约：confirm 后 wand 消失）
        CanvasWand.removeAllFrom(player, plugin);
        return Command.SINGLE_SUCCESS;
    }

    // ---------- admin ----------

    private int runStats(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MapPool.Stats ps = mapPool.stats();
        int wallsCount = database.jdbi().withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM walls").mapTo(Integer.class).one());
        int published = database.jdbi().withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM walls WHERE published_at IS NOT NULL")
                .mapTo(Integer.class).one());
        sender.sendMessage(Component.text(String.format(
                        "MapPool: total=%d  free=%d  reserved=%d   "
                                + "|   Walls: %d (published=%d)   "
                                + "|   Sessions: %d   |   Tokens: %d",
                        ps.total(), ps.free(), ps.reserved(),
                        wallsCount, published,
                        sessionManager.size(), tokenService.activeCount()),
                NamedTextColor.GOLD));
        return Command.SINGLE_SUCCESS;
    }

    private int runCleanup(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        // M5.5：stub —— 真正的孤立 ItemFrame / 错位 walls 行检测留 M7 fsck
        sender.sendMessage(Component.text(
                "cleanup is stubbed. Full fsck (orphan ItemFrame / pool-walls drift) implemented in M7.",
                NamedTextColor.YELLOW));
        return Command.SINGLE_SUCCESS;
    }

    private int runReloadConfig(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(plugin instanceof moe.hikari.canvas.HikariCanvas hc)) {
            sender.sendMessage(Component.text(
                    "reload config: plugin type mismatch (internal bug)", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        plugin.reloadConfig();
        moe.hikari.canvas.HikariCanvasConfig fresh = moe.hikari.canvas.HikariCanvasConfig.load(plugin);
        hc.applyConfig(fresh);
        sender.sendMessage(Component.text("Config reloaded: " + fresh.summary(),
                NamedTextColor.GOLD));
        sender.sendMessage(Component.text(
                "Note: host/port changes require server restart to take effect.",
                NamedTextColor.GRAY));
        return Command.SINGLE_SUCCESS;
    }

    private int runReloadTemplates(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        var stats = templateRegistry.reload();
        templatePreviewService.invalidate();  // 缩略图缓存随之失效
        sender.sendMessage(Component.text(String.format(
                        "Templates reloaded: %d builtin + %d server (overrides=%d, failed=%d) — total=%d",
                        stats.builtinLoaded(), stats.serverLoaded(),
                        stats.overrides(), stats.failed(), templateRegistry.size()),
                NamedTextColor.GOLD));
        if (stats.failed() > 0) {
            for (String f : stats.failures()) {
                sender.sendMessage(Component.text("  ✗ " + f, NamedTextColor.RED));
            }
        }
        return Command.SINGLE_SUCCESS;
    }
}
