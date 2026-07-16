package moe.hikari.canvas.session;

import moe.hikari.canvas.deploy.CanvasWand;
import moe.hikari.canvas.deploy.FrameDeployer;
import moe.hikari.canvas.deploy.WallResolver;
import moe.hikari.canvas.i18n.Messages;
import moe.hikari.canvas.render.WallRestorer;
import moe.hikari.canvas.storage.WallRepo;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 把玩家左/右键点击翻译为 {@link SessionManager#recordPos}（选区流程），或者
 * 当点中 HikariCanvas 自家 ItemFrame 时进入"二次确认 → /canvas open"路径。
 *
 * <p>触发条件（任一即可）：</p>
 * <ul>
 *   <li>主手持 Canvas Wand</li>
 *   <li>玩家已在 {@link SessionState#SELECTING} 状态（隐式接管）</li>
 * </ul>
 *
 * <p>路由策略：</p>
 * <ul>
 *   <li>点 HikariCanvas ItemFrame（PDC wall_id 存在）→ {@link #handleOpenExistingWall}：
 *       第一次提示 ActionBar；30s 内对同一 wall 再次操作 → 自动 {@code open} + 发 token URL</li>
 *   <li>点空墙方块 / 第三方 ItemFrame → {@link #handleSelection} 走选区流程</li>
 * </ul>
 */
public final class WandListener implements Listener {

    private static final long OPEN_CONFIRM_WINDOW_MS = 30_000;

    private final JavaPlugin plugin;
    private final SessionManager sessionManager;
    private final FrameDeployer frameDeployer;
    private final TokenService tokenService;
    private final WallRepo wallRepo;
    private final String editorUrlTemplate;
    /** 启动期 restore 失败的 wall 白名单查询；玩家与失败 wall 交互时给提示。可空（test 路径）。 */
    private final WallRestorer wallRestorer;
    private final Messages messages;

    /** 第一次点 HikariCanvas ItemFrame 后记录待确认。playerUuid → (wallId, ts)。 */
    private final ConcurrentMap<UUID, PendingOpen> pendingOpens = new ConcurrentHashMap<>();

    private record PendingOpen(String wallId, long ts) {}

    /**
     * 完整构造：注入 {@link WallRestorer}（启动期 restore 失败白名单）与 {@link Messages}（i18n）。
     */
    public WandListener(JavaPlugin plugin, SessionManager sessionManager,
                        FrameDeployer frameDeployer, TokenService tokenService,
                        WallRepo wallRepo, String editorUrlTemplate,
                        WallRestorer wallRestorer, Messages messages) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
        this.frameDeployer = frameDeployer;
        this.tokenService = tokenService;
        this.wallRepo = wallRepo;
        this.editorUrlTemplate = editorUrlTemplate;
        this.wallRestorer = wallRestorer;
        this.messages = messages;
    }

    // ---------- 方块层 ----------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Action a = event.getAction();
        if (a != Action.LEFT_CLICK_BLOCK && a != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack hand = event.getItem();
        if (!shouldHandle(player, hand)) return;

        event.setCancelled(true);
        Block block = event.getClickedBlock();
        if (block == null) return;
        boolean firstCorner = (a == Action.LEFT_CLICK_BLOCK);
        handleSelection(player, firstCorner, block, event.getBlockFace());
    }

    // ---------- entity 层 ----------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onRightClickEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof ItemFrame frame)) return;
        Player player = event.getPlayer();
        if (!shouldHandle(player, player.getInventory().getItemInMainHand())) return;
        event.setCancelled(true);
        routeFrameClick(player, frame, false);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onLeftClickEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof ItemFrame frame)) return;
        if (!shouldHandle(player, player.getInventory().getItemInMainHand())) return;
        event.setCancelled(true);
        routeFrameClick(player, frame, true);
    }

    private void routeFrameClick(Player player, ItemFrame frame, boolean firstCorner) {
        String wallId = frameDeployer.wallIdOf(frame);
        Session ex = sessionManager.byPlayer(player.getUniqueId());
        boolean inSelecting = ex != null && ex.state() == SessionState.SELECTING;

        // SELECTING 中：所有点击都视作选角（即使点的是自家画框）；
        // 否则（无 session，纯持 wand）：HikariCanvas 自家画框 → 二次确认 + open；第三方画框 → 选角。
        if (wallId != null && !inSelecting) {
            handleOpenExistingWall(player, wallId);
        } else {
            WallTarget t = wallBehindFrame(frame);
            handleSelection(player, firstCorner, t.block(), t.face());
        }
    }

    // ---------- "瞄已有 wall → 二次确认 → open" ----------

    private void handleOpenExistingWall(Player player, String wallId) {
        // 启动期 restore 失败的 wall → ActionBar 提示，不进入 open 路径。
        // 让玩家立刻明白不必再点（避免一直点不开还以为是 lock 问题）。
        if (wallRestorer != null && wallRestorer.isRestorationFailed(wallId)) {
            sendActionBar(player, "wand.restore-failed",
                    Placeholder.unparsed("wall_id", wallId));
            return;
        }
        // wand 路径同步 lock check。SessionManager.open 已硬拦截,
        // 这里多一道 ActionBar 提示让玩家立刻明白不必再点。
        WallRepo.Wall w = wallRepo.loadById(wallId).orElse(null);
        if (w != null && w.publishedAt() != null
                && !player.getUniqueId().equals(w.ownerUuid())
                && !player.hasPermission("canvas.admin.bypass-lock")) {
            sendActionBar(player, "wand.locked-by",
                    Placeholder.unparsed("wall_id", wallId),
                    Placeholder.unparsed("owner_name", w.ownerName()));
            return;
        }
        long now = System.currentTimeMillis();
        PendingOpen pending = pendingOpens.get(player.getUniqueId());
        if (pending != null && pending.wallId().equals(wallId)
                && now - pending.ts() <= OPEN_CONFIRM_WINDOW_MS) {
            // 二次操作 → 真打开
            pendingOpens.remove(player.getUniqueId());
            doOpen(player, wallId);
            return;
        }
        pendingOpens.put(player.getUniqueId(), new PendingOpen(wallId, now));
        sendActionBar(player, "wand.click-again",
                Placeholder.unparsed("wall_id", wallId));
    }

    private void doOpen(Player player, String wallId) {
        SessionManager.OpenResult r = sessionManager.open(
                player.getUniqueId(), player.getName(), wallId);
        if (r instanceof SessionManager.OpenResult.NotFound) {
            sendMsg(player, "command.open.not-found",
                    Placeholder.unparsed("id_or_alias", wallId));
            return;
        }
        if (r instanceof SessionManager.OpenResult.AlreadyHasSession a) {
            sendMsg(player, "command.open.already-has-session",
                    Placeholder.unparsed("state", a.current().toString()));
            return;
        }
        if (r instanceof SessionManager.OpenResult.WallOccupied wo) {
            sendMsg(player, "command.open.wall-occupied",
                    Placeholder.unparsed("other_player",
                            wo.otherPlayer() == null ? "?" : wo.otherPlayer().toString()));
            return;
        }
        if (r instanceof SessionManager.OpenResult.BindFailed bf) {
            sendMsg(player, "command.open.bind-failed",
                    Placeholder.unparsed("detail", bf.detail()));
            return;
        }
        if (r instanceof SessionManager.OpenResult.Forbidden f) {
            sendMsg(player, "command.open.forbidden",
                    Placeholder.unparsed("message", f.message()));
            return;
        }
        SessionManager.OpenResult.Ok ok = (SessionManager.OpenResult.Ok) r;
        String token = tokenService.issue(player.getUniqueId(), player.getName(), ok.session().id());
        String url = editorUrlTemplate.replace("{token}", token);
        // 收回 wand（与 confirm 行为一致）
        CanvasWand.removeAllFrom(player, plugin);

        // summary line with optional alias
        WallRepo.Wall wall = ok.wall();
        String aliasPart = "";
        if (wall.alias() != null && messages != null) {
            String aliasPartRaw = messages.rawOrNull(messages.localeId(player), "command.open.alias-part");
            if (aliasPartRaw == null) aliasPartRaw = " (alias: <alias>)";
            aliasPart = aliasPartRaw.replace("<alias>", wall.alias());
        } else if (wall.alias() != null) {
            aliasPart = " '" + wall.alias() + "'";
        }
        sendMsg(player, "command.open.summary",
                Placeholder.unparsed("wall_id", wall.wallId()),
                Placeholder.unparsed("alias_part", aliasPart),
                Placeholder.unparsed("width", String.valueOf(wall.widthMaps())),
                Placeholder.unparsed("height", String.valueOf(wall.heightMaps())));
        sendEditorUrl(player, url);
    }

    // ---------- 选区共享逻辑 ----------

    private boolean shouldHandle(Player player, ItemStack hand) {
        // 交互时重新校验 canvas.edit。曾经合法持有 wand 的玩家
        // 被撤权（lp user xx permission unset canvas.edit）后仍可触发选区 / open 流程。
        // 静默忽略 —— UX 上 wand 不响应。
        if (!player.hasPermission("canvas.edit")) return false;
        boolean hasWand = CanvasWand.isWandFor(hand, player.getUniqueId(), plugin);
        Session existing = sessionManager.byPlayer(player.getUniqueId());
        boolean inSelecting = existing != null && existing.state() == SessionState.SELECTING;
        return hasWand || inSelecting;
    }

    private void handleSelection(Player player, boolean firstCorner, Block block, BlockFace face) {
        Session existing = sessionManager.byPlayer(player.getUniqueId());
        Session session = existing;
        if (session == null) {
            SessionManager.BeginResult br = sessionManager.beginSelecting(
                    player.getUniqueId(), player.getName());
            if (br instanceof SessionManager.BeginResult.Ok ok) {
                session = ok.session();
                sendMsg(player, "wand.selection-started");
            } else {
                return;
            }
        } else if (session.state() != SessionState.SELECTING) {
            sendMsg(player, "wand.busy-session");
            return;
        }

        sessionManager.recordPos(session.id(), firstCorner, block, face);
        echoCorner(player, firstCorner, block, face);

        WallResolver.Result preview = sessionManager.preview(session.id());
        if (preview instanceof WallResolver.Result.Ok ok) {
            sendMsg(player, "wand.preview-ok",
                    Placeholder.unparsed("width", String.valueOf(ok.width())),
                    Placeholder.unparsed("height", String.valueOf(ok.height())),
                    Placeholder.unparsed("maps", String.valueOf(ok.mapCount())),
                    Placeholder.unparsed("facing", ok.facing().name()),
                    Placeholder.unparsed("min_x", String.valueOf(ok.minX())),
                    Placeholder.unparsed("min_y", String.valueOf(ok.minY())),
                    Placeholder.unparsed("min_z", String.valueOf(ok.minZ())),
                    Placeholder.unparsed("max_x", String.valueOf(computeMaxX(ok))),
                    Placeholder.unparsed("max_y", String.valueOf(computeMaxY(ok))),
                    Placeholder.unparsed("max_z", String.valueOf(computeMaxZ(ok))));
        } else if (preview instanceof WallResolver.Result.Failed f) {
            sendMsg(player, "wand.preview-invalid",
                    Placeholder.unparsed("reason", f.reason().name()),
                    Placeholder.unparsed("detail", f.detail()));
        }
    }

    private static WallTarget wallBehindFrame(ItemFrame frame) {
        BlockFace face = frame.getFacing();
        Block wallBlock = frame.getLocation().getBlock().getRelative(face.getOppositeFace());
        return new WallTarget(wallBlock, face);
    }

    private record WallTarget(Block block, BlockFace face) {}

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessionManager.byPlayer(event.getPlayer().getUniqueId());
        if (s == null) return;
        if (s.state() == SessionState.SELECTING) {
            sessionManager.cancel(s.id(), "player-quit-while-selecting");
        }
        pendingOpens.remove(event.getPlayer().getUniqueId());
    }

    private void echoCorner(Player player, boolean first, Block block, BlockFace face) {
        String labelKey = first ? "wand.label-first" : "wand.label-second";
        String labelText = (messages != null)
                ? messages.rawOrNull(messages.localeId(player), labelKey)
                : null;
        if (labelText == null) labelText = first ? "First corner" : "Second corner";
        sendMsg(player, "wand.corner",
                Placeholder.unparsed("label", labelText),
                Placeholder.unparsed("x", String.valueOf(block.getX())),
                Placeholder.unparsed("y", String.valueOf(block.getY())),
                Placeholder.unparsed("z", String.valueOf(block.getZ())),
                Placeholder.unparsed("face", face.name()));
    }

    // ---------- i18n helpers (null-safe: fall back to legacy Component when messages == null) ----------

    private void sendMsg(Player player, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        if (messages != null) {
            messages.send(player, key, resolvers);
        }
        // If messages is null (test paths without i18n), silently skip — test assertions
        // target SessionManager / WallRepo behaviour, not player-visible text.
    }

    private void sendActionBar(Player player, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        if (messages != null) {
            messages.sendActionBar(player, key, resolvers);
        }
    }

    private void sendEditorUrl(Player player, String url) {
        if (messages != null) {
            String hoverRaw = messages.rawOrNull(messages.localeId(player), "command.open.editor-url-hover");
            if (hoverRaw == null) hoverRaw = "Click to open in browser";
            String prefixRaw = messages.rawOrNull(messages.localeId(player), "command.open.editor-url");
            Component prefix = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(prefixRaw != null ? prefixRaw.replace("<url>", "") : "Open editor: ");
            Component link = Component.text(url, NamedTextColor.AQUA)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(HoverEvent.showText(Component.text(hoverRaw)));
            player.sendMessage(prefix.append(link));
        } else {
            // fallback for test paths
            player.sendMessage(Component.text("Open editor: ", NamedTextColor.GRAY)
                    .append(Component.text(url, NamedTextColor.AQUA)
                            .decorate(TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.openUrl(url))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to open in browser")))));
        }
    }

    private int computeMaxX(WallResolver.Result.Ok ok) {
        return (ok.facing() == BlockFace.EAST || ok.facing() == BlockFace.WEST)
                ? ok.minX()
                : ok.minX() + ok.width() - 1;
    }
    private int computeMaxY(WallResolver.Result.Ok ok) {
        return ok.minY() + ok.height() - 1;
    }
    private int computeMaxZ(WallResolver.Result.Ok ok) {
        return (ok.facing() == BlockFace.EAST || ok.facing() == BlockFace.WEST)
                ? ok.minZ() + ok.width() - 1
                : ok.minZ();
    }
}
