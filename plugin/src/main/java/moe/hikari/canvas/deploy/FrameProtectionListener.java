package moe.hikari.canvas.deploy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/**
 * 保护由 {@link FrameDeployer} 管理的物品框不被玩家 / 环境破坏、不被改动。
 * 契约见 {@code docs/security.md §5}（权限节点 {@code canvas.admin.force-break}）。
 *
 * <p>覆盖范围：</p>
 * <ul>
 *   <li>{@link HangingBreakEvent} — 所有破坏原因（含爆炸、物理失联）</li>
 *   <li>{@link HangingBreakByEntityEvent} — 实体攻击；玩家持 {@code canvas.admin.force-break} 权限时允许</li>
 *   <li>{@link PlayerInteractEntityEvent} — 玩家右键改内容</li>
 *   <li>{@link BlockBreakEvent} — 支撑方块被破坏；扫 4 个水平相邻格（M2 只支持垂直墙面）</li>
 * </ul>
 */
public final class FrameProtectionListener implements Listener {

    private static final String FORCE_BREAK_PERMISSION = "canvas.admin.force-break";
    private static final BlockFace[] HORIZONTAL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final FrameDeployer frameDeployer;

    public FrameProtectionListener(FrameDeployer frameDeployer) {
        this.frameDeployer = frameDeployer;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHangingBreak(HangingBreakEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) return;
        if (!frameDeployer.isProtectedFrame(frame)) return;
        // 实体原因（爆炸、物理）一律拒绝；玩家攻击走下一个 handler
        if (event instanceof HangingBreakByEntityEvent) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) return;
        if (!frameDeployer.isProtectedFrame(frame)) return;
        // 只有持 force-break 权限的玩家允许
        if (event.getRemover() instanceof Player p && p.hasPermission(FORCE_BREAK_PERMISSION)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame)) return;
        if (!frameDeployer.isProtectedFrame(frame)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        boolean canForce = player.hasPermission(FORCE_BREAK_PERMISSION);

        // 扫 4 个水平相邻格：某格若存在 attached 到本方块的 protected ItemFrame，取消
        for (BlockFace face : HORIZONTAL_FACES) {
            Block adjacent = block.getRelative(face);
            for (ItemFrame f : block.getWorld().getNearbyEntitiesByType(
                    ItemFrame.class, adjacent.getLocation().toCenterLocation(), 0.5)) {
                if (!frameDeployer.isProtectedFrame(f)) continue;
                // frame.getAttachedFace() = 它贴的墙面方向（指向墙方块）；
                // 即 face.getOppositeFace()（从 adjacent 指回 block）
                if (f.getAttachedFace() != face.getOppositeFace()) continue;
                if (canForce) return;
                event.setCancelled(true);
                player.sendMessage(Component.text(
                        "This block is supporting a HikariCanvas sign; cannot break.",
                        NamedTextColor.RED));
                return;
            }
        }
    }
}
