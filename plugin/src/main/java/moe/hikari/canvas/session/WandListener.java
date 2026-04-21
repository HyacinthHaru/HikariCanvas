package moe.hikari.canvas.session;

import moe.hikari.canvas.deploy.CanvasWand;
import moe.hikari.canvas.deploy.WallResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 把玩家左/右键点击方块翻译为 {@link SessionManager#recordPos} 调用，
 * 并在聊天栏即时回显坐标 / 墙面预览。两条入口：
 * <ul>
 *   <li>玩家持 Canvas Wand 点击 → 隐式 {@link SessionManager#beginSelecting} + recordPos</li>
 *   <li>玩家空手 / 任何非 Wand 物品点击，但已在 SELECTING 状态中 → 仅 recordPos</li>
 * </ul>
 *
 * <p>契约见 {@code docs/architecture.md §7.1}。</p>
 */
public final class WandListener implements Listener {

    private final JavaPlugin plugin;
    private final SessionManager sessionManager;

    public WandListener(JavaPlugin plugin, SessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        // 只处理方块点击（忽略空气点击、entity 交互）
        Action a = event.getAction();
        if (a != Action.LEFT_CLICK_BLOCK && a != Action.RIGHT_CLICK_BLOCK) return;
        // 双手触发时只处理主手一次，避免一次点击触发两次
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack hand = event.getItem();
        boolean hasWand = CanvasWand.isWandFor(hand, player.getUniqueId(), plugin);

        Session existing = sessionManager.byPlayer(player.getUniqueId());
        boolean inSelecting = existing != null && existing.state() == SessionState.SELECTING;

        // 既没 wand 又不在 SELECTING 状态 → 正常建筑行为，不干预
        if (!hasWand && !inSelecting) return;

        // 拦截：防止左键破坏方块 / 右键放置方块
        event.setCancelled(true);

        Block block = event.getClickedBlock();
        BlockFace face = event.getBlockFace();
        if (block == null) return;

        // 隐式开启 SELECTING（wand 入口 + 无活跃会话）
        Session session = existing;
        if (session == null) {
            SessionManager.BeginResult br = sessionManager.beginSelecting(
                    player.getUniqueId(), player.getName());
            if (br instanceof SessionManager.BeginResult.Ok ok) {
                session = ok.session();
                player.sendMessage(Component.text("Canvas Wand: selection mode started.",
                        NamedTextColor.GOLD));
            } else {
                // 逻辑上不应到此（existing 已是 null）
                return;
            }
        } else if (session.state() != SessionState.SELECTING) {
            // 已在 ISSUED / ACTIVE / CLOSING 阶段——不允许点击选新墙
            player.sendMessage(Component.text(
                    "You have an active canvas session; finish it or /canvas cancel first.",
                    NamedTextColor.RED));
            return;
        }

        boolean isFirstCorner = (a == Action.LEFT_CLICK_BLOCK);
        sessionManager.recordPos(session.id(), isFirstCorner, block, face);
        echoCorner(player, isFirstCorner, block, face);

        // 两角都设了 → 跑 preview，聊天栏展示墙面结果 / 错误
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
        // preview == null 表示只选了一个角，不必额外提示
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessionManager.byPlayer(event.getPlayer().getUniqueId());
        if (s == null) return;
        // SELECTING 态玩家掉线 → 立即释放（契约：arch.md §3.3 + §7.1）
        // ISSUED / ACTIVE 保留——WS 5min 宽限 / token 过期会自行清理
        if (s.state() == SessionState.SELECTING) {
            sessionManager.cancel(s.id(), "player-quit-while-selecting");
        }
    }

    private void echoCorner(Player player, boolean first, Block block, BlockFace face) {
        Component label = Component.text(first ? "First corner " : "Second corner ", NamedTextColor.GRAY);
        Component coord = Component.text(String.format("(%d, %d, %d)",
                block.getX(), block.getY(), block.getZ()), NamedTextColor.WHITE);
        Component facing = Component.text(" facing " + face.name(), NamedTextColor.DARK_GRAY);
        player.sendMessage(label.append(coord).append(facing));
    }

    // WallResolver 只给 minX/minY/minZ + width/height，根据 facing 轴推算 max 角
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
