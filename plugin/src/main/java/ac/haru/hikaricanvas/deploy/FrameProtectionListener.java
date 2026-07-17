package ac.haru.hikaricanvas.deploy;

import ac.haru.hikaricanvas.i18n.Messages;
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
 *   <li>{@link BlockBreakEvent} — 支撑方块被破坏；扫 4 个水平相邻格（只支持垂直墙面）</li>
 * </ul>
 */
public final class FrameProtectionListener implements Listener {

    private static final String FORCE_BREAK_PERMISSION = "canvas.admin.force-break";
    private static final BlockFace[] HORIZONTAL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final FrameDeployer frameDeployer;
    private final Messages messages;

    public FrameProtectionListener(FrameDeployer frameDeployer, Messages messages) {
        this.frameDeployer = frameDeployer;
        this.messages = messages;
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
        // 所有 wall ItemFrame 同等保护（不区分 lock 状态）。
        // canvas.admin.force-break 权限是唯一例外（管理员强行破坏）。
        Player p = event.getRemover() instanceof Player pp ? pp : null;
        if (p != null && p.hasPermission(FORCE_BREAK_PERMISSION)) {
            return;  // force-break → 允许
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
                if (f.getAttachedFace() != face.getOppositeFace()) continue;
                // 所有 wall 同等保护（不区分 lock 状态）；仅 force-break 权限可绕过。
                if (canForce) return;
                event.setCancelled(true);
                messages.sendActionBar(player, "frame-protect.blocked");
                return;
            }
        }
    }
}
