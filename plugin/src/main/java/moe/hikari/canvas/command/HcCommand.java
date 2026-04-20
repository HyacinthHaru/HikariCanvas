package moe.hikari.canvas.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import moe.hikari.canvas.deploy.MapPacketSender;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.util.Arrays;

/**
 * M1 阶段只注册最小子命令：
 * <ul>
 *   <li>{@code /hc give}  —— 给玩家一张绑定新 MapView 的空白 filled_map（便于测试，不需要 OP）</li>
 *   <li>{@code /hc paint} —— 把玩家主手的 filled_map 整张涂红</li>
 * </ul>
 * 完整命令树在后续任务按 {@code edit/cancel/cleanup/undo} 扩展（见 PROPOSAL §4.1）。
 */
public final class HcCommand {

    /** MC map palette 中一个明显的红色索引。 */
    private static final byte RED_PALETTE = 18;

    private final MapPacketSender mapPacketSender;

    public HcCommand(MapPacketSender mapPacketSender) {
        this.mapPacketSender = mapPacketSender;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("hc")
                .requires(src -> src.getSender() instanceof Player)
                .then(Commands.literal("give").executes(this::runGive))
                .then(Commands.literal("paint").executes(this::runPaint))
                .build();
    }

    private int runGive(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();

        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapView view = Bukkit.createMap(player.getWorld());
        // 移除默认 renderer，这样地图初始是空白的（不画世界地形），方便看涂色效果
        view.getRenderers().forEach(view::removeRenderer);

        MapMeta meta = (MapMeta) map.getItemMeta();
        meta.setMapView(view);
        map.setItemMeta(meta);

        player.getInventory().addItem(map);
        player.sendMessage("Gave you a blank canvas map (id=" + view.getId() + "). Hold it and run /hc paint.");
        return Command.SINGLE_SUCCESS;
    }

    private int runPaint(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.FILLED_MAP) {
            player.sendMessage("Hold a filled_map in your main hand (try /hc give first)");
            return 0;
        }
        if (!(item.getItemMeta() instanceof MapMeta meta) || !meta.hasMapView()) {
            player.sendMessage("This filled_map has no map view");
            return 0;
        }
        MapView view = meta.getMapView();
        if (view == null) {
            player.sendMessage("MapMeta returned null map view");
            return 0;
        }

        int mapId = view.getId();
        byte[] pixels = new byte[MapPacketSender.MAP_PIXELS];
        Arrays.fill(pixels, RED_PALETTE);
        mapPacketSender.sendFullMap(player, mapId, pixels);
        player.sendMessage("Painted map #" + mapId + " red (palette=" + RED_PALETTE + ")");
        return Command.SINGLE_SUCCESS;
    }
}
