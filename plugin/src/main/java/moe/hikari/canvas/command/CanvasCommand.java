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
import moe.hikari.canvas.i18n.Messages;
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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
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
    /** 0.4.0-P5：{@code /canvas var} 子命令族（7 子命令）。null = 主插件未传，跳过注册。 */
    private final VariableSubCommand variableSubCommand;
    /** 0.5.0-P1：{@code /canvas bench} 命令族（list/run/report/clear）。null = 主插件未传，跳过注册。 */
    private final BenchmarkSubCommand benchmarkSubCommand;
    /** 0.8.2 i18n：多语言消息注册表。 */
    private final Messages messages;

    /**
     * M16-P2.6：玩家最近的 /canvas delete <wallId> 待确认条目。
     * 外层 key = playerUuid；内层 key = wallId，value = pending 元数据。
     *
     * <p>多 wall 并存：玩家可同时对多个不同 wall 各起一个 pending（30s 窗口内），
     * 互不覆盖。后续敲 {@code /canvas delete <wallId> confirm} 时按 (player, wallId)
     * 精确匹配。原来用 {@code Map<UUID, PendingDelete>} 会导致连续两个 {@code delete A}
     * 后 {@code delete B} 第二条覆盖第一条 → 玩家若想 confirm A 会得到 "mismatched" 报错。</p>
     *
     * <p>清理：{@link QuitListener} 在玩家退出时清整层 bucket（{@code pendingDeletes.remove(playerUuid)}）；
     * 另在 {@link #runDeleteFirstStep} 入口顺手 reap 该玩家已超 30s 的旧条目。无独立 reap task。</p>
     */
    private final ConcurrentMap<UUID, ConcurrentMap<String, PendingDelete>> pendingDeletes =
            new ConcurrentHashMap<>();

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
                         String editorUrlTemplate,
                         VariableSubCommand variableSubCommand,
                         BenchmarkSubCommand benchmarkSubCommand,
                         Messages messages) {
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
        this.variableSubCommand = variableSubCommand;
        this.benchmarkSubCommand = benchmarkSubCommand;
        this.messages = messages;
        // M16-P2.6：注册 PlayerQuit 监听清 pendingDeletes，避免玩家退出后 bucket 长期挂着
        plugin.getServer().getPluginManager().registerEvents(
                new QuitListener(), plugin);
    }

    /** Internal listener: PlayerQuit → 清整层 pendingDeletes entry。 */
    private final class QuitListener implements Listener {
        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            pendingDeletes.remove(event.getPlayer().getUniqueId());
        }
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        var root = Commands.literal("canvas");
        if (variableSubCommand != null) {
            // 0.4.0-P5：/canvas var <sub> 命令族
            root = root.then(variableSubCommand.build());
        }
        if (benchmarkSubCommand != null) {
            // 0.5.0-P1：/canvas bench <sub> 命令族
            root = root.then(benchmarkSubCommand.build());
        }
        return root
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
                // 2026-05-14 砍：runPublish / runUnpublish 命令处理器移除。
                // lock 状态由前端 TopBar Lock 按钮 → ws.send('wall.lock' | 'wall.unlock') → WebServer.handleWallOp 处理。
                .then(Commands.literal("alias")
                        .requires(src -> isPlayerWith(src, "canvas.edit"))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(this::runAlias)))
                .then(Commands.literal("delete")
                        // P3-3: 门禁与业务权限并集对齐——业务逻辑（runDeleteConfirm 等）允许
                        // canvas.delete.any 删他人 wall，故门禁不能只认 .own，否则纯 .any
                        // 管理员被挡在 Brigadier 节点外。
                        .requires(src -> isPlayerWith(src, "canvas.delete.own")
                                || isPlayerWith(src, "canvas.delete.any"))
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
            sendEditGuide(player, "command.edit.guide-headline-new");
        } else if (r instanceof SessionManager.BeginResult.AlreadyHasSession ex) {
            Session existing = ex.existing();
            switch (existing.state()) {
                case SELECTING -> {
                    // M5-D8：隐式 reselect，避免玩家被卡在"已选一半"
                    sessionManager.resetSelection(existing.id());
                    ensureWand(player);
                    sendEditGuide(player, "command.edit.guide-headline-reset");
                }
                case ISSUED, ACTIVE -> messages.send(player, "command.edit.already-open");
                case CLOSING -> messages.send(player, "command.edit.session-closing");
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private void ensureWand(Player player) {
        if (!CanvasWand.hasWand(player, plugin)) {
            player.getInventory().addItem(CanvasWand.forPlayer(plugin, player, messages));
        }
    }

    private void sendEditGuide(Player player, String headlineKey) {
        messages.send(player, headlineKey);
        messages.send(player, "command.edit.guide-step1");
        messages.send(player, "command.edit.guide-step2");
        messages.send(player, "command.edit.guide-step3");
    }

    private int runWand(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        player.getInventory().addItem(CanvasWand.forPlayer(plugin, player, messages));
        messages.send(player, "command.wand.received");
        return Command.SINGLE_SUCCESS;
    }

    private int runCancel(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        Session s = sessionManager.byPlayer(player.getUniqueId());
        if (s == null) {
            messages.send(player, "command.no-session");
            return 0;
        }
        // M5.5：cancel 仅释放 session/wand；wall 数据 + ItemFrames 保留
        String sid = s.id();
        SessionState prev = s.state();
        sessionManager.cancel(sid, "player-cancel");
        int wands = CanvasWand.removeAllFrom(player, plugin);

        // wand_note 为魔棒回收提示（有棒时）或空串（无棒时）
        String wandNoteKey = wands > 0 ? "command.cancel.cancelled-wand-returned"
                : "command.cancel.cancelled-no-wand";
        String wandNote = messages.rawOrNull(messages.localeId(player), wandNoteKey);
        if (wandNote == null) wandNote = "";
        messages.send(player, "command.cancel.cancelled",
                Placeholder.unparsed("prev_state", prev.toString()),
                Placeholder.unparsed("wand_note", wandNote));
        return Command.SINGLE_SUCCESS;
    }

    // ---------- open / list / alias / delete ----------

    private int runOpen(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        String idOrAlias = StringArgumentType.getString(ctx, "id_or_alias");
        SessionManager.OpenResult r = sessionManager.open(
                player.getUniqueId(), player.getName(), idOrAlias);
        if (r instanceof SessionManager.OpenResult.NotFound) {
            messages.send(player, "command.open.not-found",
                    Placeholder.unparsed("id_or_alias", idOrAlias));
            return Command.SINGLE_SUCCESS;
        }
        if (r instanceof SessionManager.OpenResult.AlreadyHasSession a) {
            messages.send(player, "command.open.already-has-session",
                    Placeholder.unparsed("state", a.current().toString()));
            return Command.SINGLE_SUCCESS;
        }
        if (r instanceof SessionManager.OpenResult.WallOccupied wo) {
            messages.send(player, "command.open.wall-occupied",
                    Placeholder.unparsed("other_player",
                            wo.otherPlayer() == null ? "?" : wo.otherPlayer().toString()));
            return Command.SINGLE_SUCCESS;
        }
        if (r instanceof SessionManager.OpenResult.BindFailed bf) {
            messages.send(player, "command.open.bind-failed",
                    Placeholder.unparsed("detail", bf.detail()));
            return Command.SINGLE_SUCCESS;
        }
        if (r instanceof SessionManager.OpenResult.Forbidden f) {
            messages.send(player, "command.open.forbidden",
                    Placeholder.unparsed("message", f.message()));
            return Command.SINGLE_SUCCESS;
        }
        SessionManager.OpenResult.Ok ok = (SessionManager.OpenResult.Ok) r;
        // 签发 token
        String token = tokenService.issue(player.getUniqueId(), player.getName(), ok.session().id());
        String url = editorUrlTemplate.replace("{token}", token);
        WallRepo.Wall w = ok.wall();

        // summary line with optional alias
        String aliasPart = "";
        if (w.alias() != null) {
            String aliasPartRaw = messages.rawOrNull(messages.localeId(player), "command.open.alias-part");
            if (aliasPartRaw == null) aliasPartRaw = " (alias: <alias>)";
            aliasPart = aliasPartRaw.replace("<alias>", w.alias());
        }
        messages.send(player, "command.open.summary",
                Placeholder.unparsed("wall_id", w.wallId()),
                Placeholder.unparsed("alias_part", aliasPart),
                Placeholder.unparsed("width", String.valueOf(w.widthMaps())),
                Placeholder.unparsed("height", String.valueOf(w.heightMaps())));
        sendEditorUrlComponent(player, url, "command.open.editor-url", "command.open.editor-url-hover");
        return Command.SINGLE_SUCCESS;
    }

    private int runList(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        List<WallRepo.Summary> walls = wallRepo.listForOwner(player.getUniqueId());
        if (walls.isEmpty()) {
            messages.send(player, "command.list.empty");
            return Command.SINGLE_SUCCESS;
        }
        // P3-99: 术语对齐 lock-state 重设计——publishedAt 非 null = 已锁定（locked）。
        // 数据语义不变，仅玩家可见文案从过时的 "published" 改为 "locked"。
        List<WallRepo.Summary> locked = walls.stream().filter(w -> w.publishedAt() != null).toList();
        List<WallRepo.Summary> drafts = walls.stream().filter(w -> w.publishedAt() == null).toList();
        messages.send(player, "command.list.header",
                Placeholder.unparsed("total", String.valueOf(walls.size())),
                Placeholder.unparsed("locked", String.valueOf(locked.size())),
                Placeholder.unparsed("drafts", String.valueOf(drafts.size())));
        for (WallRepo.Summary w : locked) sendWallLine(player, w, true);
        for (WallRepo.Summary w : drafts) sendWallLine(player, w, false);
        return Command.SINGLE_SUCCESS;
    }

    private void sendWallLine(Player p, WallRepo.Summary w, boolean locked) {
        String aliasPart = w.alias() != null ? " '" + w.alias() + "'" : "";
        String lineKey = locked ? "command.list.line-locked" : "command.list.line-draft";
        String lineRaw = messages.rawOrNull(messages.localeId(p), lineKey);
        if (lineRaw == null) {
            lineRaw = locked
                    ? "<aqua>  [L] <wall_id><alias_part> · <width>x<height> · <world> (<x>,<y>,<z>) <facing></aqua>"
                    : "<gray>  [D] <wall_id><alias_part> · <width>x<height> · <world> (<x>,<y>,<z>) <facing></gray>";
        }
        Component label = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                .deserialize(lineRaw,
                        Placeholder.unparsed("wall_id", w.wallId()),
                        Placeholder.unparsed("alias_part", aliasPart),
                        Placeholder.unparsed("width", String.valueOf(w.widthMaps())),
                        Placeholder.unparsed("height", String.valueOf(w.heightMaps())),
                        Placeholder.unparsed("world", w.world()),
                        Placeholder.unparsed("x", String.valueOf(w.originX())),
                        Placeholder.unparsed("y", String.valueOf(w.originY())),
                        Placeholder.unparsed("z", String.valueOf(w.originZ())),
                        Placeholder.unparsed("facing", w.facing()));
        // hover text from lang
        String hoverRaw = messages.rawOrNull(messages.localeId(p), "command.list.line-hover");
        if (hoverRaw == null) hoverRaw = "Click to suggest /canvas open <wall_id>";
        String hoverText = hoverRaw.replace("<wall_id>", w.wallId());
        p.sendMessage(label
                .clickEvent(ClickEvent.suggestCommand("/canvas open " + w.wallId()))
                .hoverEvent(HoverEvent.showText(Component.text(hoverText))));
    }

    // 2026-05-14 砍：runPublish / runUnpublish 命令处理器移除。
    // lock 状态由前端 TopBar Lock 按钮 → ws.send('wall.lock' | 'wall.unlock') → WebServer.handleWallOp 处理。

    private int runAlias(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        Session s = sessionManager.byPlayer(player.getUniqueId());
        if (s == null || s.wallId() == null) {
            messages.send(player, "command.no-wall-session");
            return Command.SINGLE_SUCCESS;
        }
        String alias = StringArgumentType.getString(ctx, "name");
        if (!ALIAS_PATTERN.matcher(alias).matches()) {
            messages.send(player, "command.alias.invalid-format");
            return Command.SINGLE_SUCCESS;
        }
        // M15.3 P0-19：alias 操作必须是 wall owner（或带 canvas.alias.any 权限）
        var wallOpt = wallRepo.loadById(s.wallId());
        if (wallOpt.isEmpty()) {
            messages.send(player, "command.alias.wall-not-found");
            return Command.SINGLE_SUCCESS;
        }
        var wall = wallOpt.get();
        boolean isOwner = wall.ownerUuid().equals(player.getUniqueId());
        boolean canAny = player.hasPermission("canvas.alias.any");
        if (!isOwner && !canAny) {
            messages.send(player, "command.alias.not-owner");
            return Command.SINGLE_SUCCESS;
        }
        boolean ok = wallRepo.setAlias(s.wallId(), alias);
        if (!ok) {
            messages.send(player, "command.alias.in-use",
                    Placeholder.unparsed("alias", alias));
            return Command.SINGLE_SUCCESS;
        }
        messages.send(player, "command.alias.set-ok",
                Placeholder.unparsed("wall_id", s.wallId()),
                Placeholder.unparsed("alias", alias));
        return Command.SINGLE_SUCCESS;
    }

    private int runDeleteFirstStep(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        String wallId = StringArgumentType.getString(ctx, "wall_id");
        var w = wallRepo.loadById(wallId).orElse(null);
        if (w == null) {
            messages.send(player, "command.delete.not-found",
                    Placeholder.unparsed("wall_id", wallId));
            return Command.SINGLE_SUCCESS;
        }
        if (!w.ownerUuid().equals(player.getUniqueId())
                && !player.hasPermission("canvas.delete.any")) {
            messages.send(player, "command.delete.not-owner");
            return Command.SINGLE_SUCCESS;
        }
        long now = System.currentTimeMillis();
        ConcurrentMap<String, PendingDelete> bucket = pendingDeletes.computeIfAbsent(
                player.getUniqueId(), k -> new ConcurrentHashMap<>());
        // M16-P2.6：先清这玩家自己已过期的条目（顺手 reap），再判同一 wallId 是否已 pending
        reapExpired(bucket, now);
        PendingDelete existing = bucket.get(wallId);
        if (existing != null && now - existing.ts() <= DELETE_CONFIRM_WINDOW_MS) {
            messages.send(player, "command.delete.already-pending",
                    Placeholder.unparsed("wall_id", wallId));
            return Command.SINGLE_SUCCESS;
        }
        bucket.put(wallId, new PendingDelete(wallId, now));

        // alias part for pending message
        String aliasPart = "";
        if (w.alias() != null) {
            String aliasPartRaw = messages.rawOrNull(messages.localeId(player), "command.delete.pending-alias-part");
            if (aliasPartRaw == null) aliasPartRaw = " '<alias>'";
            aliasPart = aliasPartRaw.replace("<alias>", w.alias());
        }
        messages.send(player, "command.delete.pending",
                Placeholder.unparsed("wall_id", wallId),
                Placeholder.unparsed("alias_part", aliasPart));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 清理一个玩家 bucket 中所有超过 {@link #DELETE_CONFIRM_WINDOW_MS} 的条目。
     * O(n) on bucket size；bucket 通常仅 0-3 项，不需要后台线程。
     */
    private static void reapExpired(ConcurrentMap<String, PendingDelete> bucket, long now) {
        bucket.entrySet().removeIf(e -> now - e.getValue().ts() > DELETE_CONFIRM_WINDOW_MS);
    }

    private int runDeleteConfirm(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        String wallId = StringArgumentType.getString(ctx, "wall_id");
        long now = System.currentTimeMillis();
        ConcurrentMap<String, PendingDelete> bucket = pendingDeletes.get(player.getUniqueId());
        PendingDelete pd = bucket == null ? null : bucket.remove(wallId);
        // 若 bucket 被清空，gc 外层 entry，避免长期挂着空 map
        if (bucket != null && bucket.isEmpty()) {
            pendingDeletes.remove(player.getUniqueId(), bucket);
        }
        if (pd == null || !pd.wallId().equals(wallId)
                || now - pd.ts() > DELETE_CONFIRM_WINDOW_MS) {
            messages.send(player, "command.delete.expired",
                    Placeholder.unparsed("wall_id", wallId));
            return Command.SINGLE_SUCCESS;
        }
        var w = wallRepo.loadById(wallId).orElse(null);
        if (w == null) {
            messages.send(player, "command.delete.already-gone",
                    Placeholder.unparsed("wall_id", wallId));
            return Command.SINGLE_SUCCESS;
        }
        if (!w.ownerUuid().equals(player.getUniqueId())
                && !player.hasPermission("canvas.delete.any")) {
            messages.send(player, "command.delete.not-owner-confirm");
            return Command.SINGLE_SUCCESS;
        }
        // 拆 ItemFrame
        World world = plugin.getServer().getWorld(w.key().world());
        int frames = world == null ? 0 : frameDeployer.removeForWall(wallId, world);
        // SessionManager.deleteWall 释放池 + 删 walls 行（含 cancel 任何活跃 session）
        sessionManager.deleteWall(wallId);
        messages.send(player, "command.delete.deleted",
                Placeholder.unparsed("wall_id", wallId),
                Placeholder.unparsed("frames", String.valueOf(frames)));
        return Command.SINGLE_SUCCESS;
    }

    // ---------- confirm ----------

    private int runConfirm(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        Session s = sessionManager.byPlayer(player.getUniqueId());
        if (s == null) {
            messages.send(player, "command.no-session-edit-first");
            return 0;
        }

        SessionManager.ConfirmResult result;
        try {
            result = sessionManager.confirm(s.id());
        } catch (SessionManager.SessionConfirmFailedException e) {
            // M16-P2.7-A：confirm 中跨子系统步骤已 rollback。给玩家提示，细节看服务端日志。
            plugin.getLogger().log(Level.SEVERE, "SessionManager.confirm rolled back", e);
            messages.send(player, "command.confirm-failed");
            return Command.SINGLE_SUCCESS;
        }
        if (result instanceof SessionManager.ConfirmResult.NotReady nr) {
            messages.send(player, "command.confirm.not-ready",
                    Placeholder.unparsed("detail", nr.detail()));
            return Command.SINGLE_SUCCESS;
        }
        if (result instanceof SessionManager.ConfirmResult.WallFailed wf) {
            messages.send(player, "command.confirm.wall-invalid",
                    Placeholder.unparsed("reason", String.valueOf(wf.reason().reason())),
                    Placeholder.unparsed("detail", wf.reason().detail()));
            return Command.SINGLE_SUCCESS;
        }
        if (result instanceof SessionManager.ConfirmResult.WallOccupied wo) {
            messages.send(player, "command.confirm.wall-occupied",
                    Placeholder.unparsed("session_id", wo.otherSessionId()));
            return Command.SINGLE_SUCCESS;
        }
        if (result instanceof SessionManager.ConfirmResult.PoolExhausted pe) {
            messages.send(player, "command.confirm.pool-exhausted",
                    Placeholder.unparsed("message", pe.message()));
            return Command.SINGLE_SUCCESS;
        }
        // Ultrareview 2026-05-25 #2：confirm 撞 locked + 非 owner + 无 bypass → Forbidden
        if (result instanceof SessionManager.ConfirmResult.Forbidden fb) {
            // fb.message() 是动态服务器错误信息，直接用 Component.text 转发
            player.sendMessage(Component.text(fb.message(),
                    net.kyori.adventure.text.format.NamedTextColor.RED));
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
                // 0.4.10 P2-47：部署失败必须原子回滚整个新建 wall，否则只 cancel 会留下
                // walls 行 + 预留的 RESERVED map 不归还（cancel 故意不释放池）→ 孤儿 wall +
                // idcounts.dat 漂移。removeForWall 清理可能已部署的部分 ItemFrame；deleteWall
                // 释放池→FREE + 删 walls 行 + cancel 活跃 session（与正常 /canvas delete 同款逆操作）。
                try {
                    frameDeployer.removeForWall(wallId, wall.world());
                    sessionManager.deleteWall(wallId);
                } catch (Exception rollbackEx) {
                    plugin.getLogger().log(Level.SEVERE,
                            "deploy-failure rollback also failed for wall " + wallId, rollbackEx);
                    sessionManager.cancel(sessionAfter.id(), "deploy-failed");  // 兜底至少结束 session
                }
                messages.send(player, "command.confirm.deploy-failed",
                        Placeholder.unparsed("message", e.getMessage() != null ? e.getMessage() : "unknown"));
                return Command.SINGLE_SUCCESS;
            }
        }

        // 签发 token（15min TTL）
        String token = tokenService.issue(
                player.getUniqueId(), player.getName(), sessionAfter.id());
        String url = editorUrlTemplate.replace("{token}", token);

        String confirmKey = newWall ? "command.confirm.created" : "command.confirm.opened";
        messages.send(player, confirmKey,
                Placeholder.unparsed("wall_id", wallId),
                Placeholder.unparsed("width", String.valueOf(wall.width())),
                Placeholder.unparsed("height", String.valueOf(wall.height())),
                Placeholder.unparsed("frames", String.valueOf(mounted)));
        sendEditorUrlComponent(player, url, "command.confirm.editor-url", "command.confirm.editor-url-hover");

        // 收回 wand（契约：confirm 后 wand 消失）
        CanvasWand.removeAllFrom(player, plugin);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 发送带 ClickEvent（open_url）和 HoverEvent 的编辑器链接。
     * lang 文件只提供 hover 文字，链接本体由 Java 构建（MiniMessage 不支持动态 url click tag）。
     */
    private void sendEditorUrlComponent(Player player, String url, String prefixKey, String hoverKey) {
        // hover 文字来自 lang
        String hoverRaw = messages.rawOrNull(messages.localeId(player), hoverKey);
        if (hoverRaw == null) hoverRaw = "Click to open in browser";
        // 前缀文字：lang 中 urlKey 的非 url 部分，直接用 MiniMessage 渲染 prefix key
        // 约定 lang 格式："<gray>Open editor: </gray><aqua><underlined><url></underlined></aqua>"
        // 这里把 <url> 替换为真实 url 并用 Placeholder 注入，Adventure 会渲染文字但无法附 ClickEvent。
        // 因此：用 Component 拼装，lang 仅贡献文字颜色语义。
        String prefixRaw = messages.rawOrNull(messages.localeId(player), prefixKey);
        Component prefix = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                .deserialize(prefixRaw != null ? prefixRaw.replace("<url>", "") : "Open editor: ");
        Component link = Component.text(url,
                net.kyori.adventure.text.format.NamedTextColor.AQUA)
                .decorate(net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text(hoverRaw)));
        player.sendMessage(prefix.append(link));
    }

    // ---------- admin ----------

    private int runStats(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MapPool.Stats ps = mapPool.stats();
        int wallsCount = database.jdbi().withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM walls").mapTo(Integer.class).one());
        // P3-99: published_at 非 null = 已锁定；统计标签术语对齐为 locked（DB 列名不变）。
        int locked = database.jdbi().withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM walls WHERE published_at IS NOT NULL")
                .mapTo(Integer.class).one());
        messages.send(sender, "command.stats.output",
                Placeholder.unparsed("pool_total", String.valueOf(ps.total())),
                Placeholder.unparsed("pool_free", String.valueOf(ps.free())),
                Placeholder.unparsed("pool_reserved", String.valueOf(ps.reserved())),
                Placeholder.unparsed("walls", String.valueOf(wallsCount)),
                Placeholder.unparsed("walls_locked", String.valueOf(locked)),
                Placeholder.unparsed("sessions", String.valueOf(sessionManager.size())),
                Placeholder.unparsed("tokens", String.valueOf(tokenService.activeCount())));
        return Command.SINGLE_SUCCESS;
    }

    private int runCleanup(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        // M5.5：stub —— 真正的孤立 ItemFrame / 错位 walls 行检测留 M7 fsck
        messages.send(sender, "command.cleanup.stubbed");
        return Command.SINGLE_SUCCESS;
    }

    private int runReloadConfig(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(plugin instanceof moe.hikari.canvas.HikariCanvas hc)) {
            messages.send(sender, "command.reload.config-type-mismatch");
            return Command.SINGLE_SUCCESS;
        }
        plugin.reloadConfig();
        moe.hikari.canvas.HikariCanvasConfig fresh = moe.hikari.canvas.HikariCanvasConfig.load(plugin);
        hc.applyConfig(fresh);
        messages.send(sender, "command.reload.config-ok",
                Placeholder.unparsed("summary", fresh.summary()));
        messages.send(sender, "command.reload.config-restart-note");
        return Command.SINGLE_SUCCESS;
    }

    private int runReloadTemplates(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        var stats = templateRegistry.reload();
        templatePreviewService.invalidate();  // 缩略图缓存随之失效
        messages.send(sender, "command.reload.templates-ok",
                Placeholder.unparsed("builtin", String.valueOf(stats.builtinLoaded())),
                Placeholder.unparsed("server", String.valueOf(stats.serverLoaded())),
                Placeholder.unparsed("overrides", String.valueOf(stats.overrides())),
                Placeholder.unparsed("failed", String.valueOf(stats.failed())),
                Placeholder.unparsed("total", String.valueOf(templateRegistry.size())));
        if (stats.failed() > 0) {
            for (String f : stats.failures()) {
                messages.send(sender, "command.reload.template-failure",
                        Placeholder.unparsed("file", f));
            }
        }
        return Command.SINGLE_SUCCESS;
    }
}
