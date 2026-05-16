package moe.hikari.canvas.session;

import moe.hikari.canvas.deploy.CanvasWand;
import moe.hikari.canvas.deploy.FrameDeployer;
import moe.hikari.canvas.deploy.WallResolver;
import moe.hikari.canvas.storage.WallRepo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
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
 * 当点中 HikariCanvas 自家 ItemFrame 时进入"二次确认 → /canvas open"路径（M5.5）。
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

    /** 第一次点 HikariCanvas ItemFrame 后记录待确认。playerUuid → (wallId, ts)。 */
    private final ConcurrentMap<UUID, PendingOpen> pendingOpens = new ConcurrentHashMap<>();

    private record PendingOpen(String wallId, long ts) {}

    public WandListener(JavaPlugin plugin, SessionManager sessionManager,
                        FrameDeployer frameDeployer, TokenService tokenService,
                        WallRepo wallRepo, String editorUrlTemplate) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
        this.frameDeployer = frameDeployer;
        this.tokenService = tokenService;
        this.wallRepo = wallRepo;
        this.editorUrlTemplate = editorUrlTemplate;
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
        // M15.3 Phase 2 方案 C：wand 路径同步 lock check。SessionManager.open 已硬拦截,
        // 这里多一道 ActionBar 提示让玩家立刻明白不必再点。
        WallRepo.Wall w = wallRepo.loadById(wallId).orElse(null);
        if (w != null && w.publishedAt() != null
                && !player.getUniqueId().equals(w.ownerUuid())
                && !player.hasPermission("canvas.admin.bypass-lock")) {
            player.sendActionBar(Component.text(
                    "Wall '" + wallId + "' is locked by " + w.ownerName(),
                    NamedTextColor.RED));
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
        player.sendActionBar(Component.text(
                "Wall " + wallId + " — click again within 30s to open editor",
                NamedTextColor.AQUA));
    }

    private void doOpen(Player player, String wallId) {
        SessionManager.OpenResult r = sessionManager.open(
                player.getUniqueId(), player.getName(), wallId);
        if (r instanceof SessionManager.OpenResult.NotFound) {
            player.sendMessage(Component.text("Wall no longer exists: " + wallId, NamedTextColor.RED));
            return;
        }
        if (r instanceof SessionManager.OpenResult.AlreadyHasSession a) {
            player.sendMessage(Component.text(
                    "You already have an active session (state=" + a.current() + "). /canvas cancel first.",
                    NamedTextColor.RED));
            return;
        }
        if (r instanceof SessionManager.OpenResult.WallOccupied wo) {
            player.sendMessage(Component.text(
                    "Wall is being edited by " + (wo.otherPlayer() == null ? "?" : wo.otherPlayer()) + ".",
                    NamedTextColor.RED));
            return;
        }
        if (r instanceof SessionManager.OpenResult.BindFailed bf) {
            player.sendMessage(Component.text("Cannot open: " + bf.detail(), NamedTextColor.RED));
            return;
        }
        if (r instanceof SessionManager.OpenResult.Forbidden f) {
            player.sendMessage(Component.text(
                    "Wall is locked: " + f.message(), NamedTextColor.RED));
            return;
        }
        SessionManager.OpenResult.Ok ok = (SessionManager.OpenResult.Ok) r;
        String token = tokenService.issue(player.getUniqueId(), player.getName(), ok.session().id());
        String url = editorUrlTemplate.replace("{token}", token);
        // 收回 wand（与 confirm 行为一致）
        CanvasWand.removeAllFrom(player, plugin);
        player.sendMessage(Component.text(
                "Opened wall " + wallId
                        + (ok.wall().alias() != null ? " '" + ok.wall().alias() + "'" : "")
                        + " · " + ok.wall().widthMaps() + "×" + ok.wall().heightMaps(),
                NamedTextColor.GREEN));
        player.sendMessage(Component.text("Open editor: ", NamedTextColor.GRAY)
                .append(Component.text(url, NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to open in browser")))));
    }

    // ---------- 选区共享逻辑 ----------

    private boolean shouldHandle(Player player, ItemStack hand) {
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
                player.sendMessage(Component.text("Canvas Wand: selection mode started.",
                        NamedTextColor.GOLD));
            } else {
                return;
            }
        } else if (session.state() != SessionState.SELECTING) {
            player.sendMessage(Component.text(
                    "You have an active canvas session; finish it or /canvas cancel first.",
                    NamedTextColor.RED));
            return;
        }

        sessionManager.recordPos(session.id(), firstCorner, block, face);
        echoCorner(player, firstCorner, block, face);

        WallResolver.Result preview = sessionManager.preview(session.id());
        if (preview instanceof WallResolver.Result.Ok ok) {
            player.sendMessage(Component.text(String.format(
                            "Wall: %d×%d (%d maps), facing %s. From (%d,%d,%d) to (%d,%d,%d). Run /canvas confirm.",
                            ok.width(), ok.height(), ok.mapCount(), ok.facing().name(),
                            ok.minX(), ok.minY(), ok.minZ(),
                            computeMaxX(ok), computeMaxY(ok), computeMaxZ(ok)),
                    NamedTextColor.AQUA));
        } else if (preview instanceof WallResolver.Result.Failed f) {
            player.sendMessage(Component.text(
                    "Selection invalid: " + f.reason() + " — " + f.detail(),
                    NamedTextColor.RED));
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
        Component label = Component.text(first ? "First corner " : "Second corner ", NamedTextColor.GRAY);
        Component coord = Component.text(String.format("(%d, %d, %d)",
                block.getX(), block.getY(), block.getZ()), NamedTextColor.WHITE);
        Component facing = Component.text(" facing " + face.name(), NamedTextColor.DARK_GRAY);
        player.sendMessage(label.append(coord).append(facing));
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
