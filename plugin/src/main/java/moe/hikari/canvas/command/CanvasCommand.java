package moe.hikari.canvas.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import moe.hikari.canvas.deploy.MapPacketSender;
import moe.hikari.canvas.render.PlaceholderRenderer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.util.Arrays;

/**
 * {@code /canvas} 根命令的 Brigadier 注册点。正式子命令
 * （edit / wand / confirm / cancel / commit / cleanup / stats）
 * 将在 M2-T11 逐步接入，此处只是入口骨架。
 *
 * <p>以下两个子命令是 <b>M1 demo 遗留，DEPRECATED</b>，仅在 M2 实施
 * 中间阶段临时保留（缺少正式命令族时还能用它手动验证发包链路）；
 * M2-T11 命令族完整实装时删除。</p>
 * <ul>
 *   <li>{@code /canvas give} — 给玩家一张绑定新 MapView 的空白 filled_map</li>
 *   <li>{@code /canvas paint} — 把玩家主手的 filled_map 整张涂红</li>
 *   <li>{@code /canvas placeholder <slot> <total>} — T9 的 placeholder 视觉验证</li>
 * </ul>
 */
public final class CanvasCommand {

    /** MC map palette 中一个明显的红色索引（M1 demo paint 用）。 */
    private static final byte RED_PALETTE = 18;

    private final MapPacketSender mapPacketSender;
    private final PlaceholderRenderer placeholderRenderer = new PlaceholderRenderer();

    public CanvasCommand(MapPacketSender mapPacketSender) {
        this.mapPacketSender = mapPacketSender;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("canvas")
                .requires(src -> src.getSender() instanceof Player)
                .then(Commands.literal("give").executes(this::runGive))   // DEPRECATED, remove at M2-T11
                .then(Commands.literal("paint").executes(this::runPaint)) // DEPRECATED, remove at M2-T11
                .then(Commands.literal("placeholder")                     // DEPRECATED, remove at M2-T11
                        .then(Commands.argument("slot", IntegerArgumentType.integer(0))
                                .then(Commands.argument("total", IntegerArgumentType.integer(1))
                                        .executes(this::runPlaceholder))))
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
        player.sendMessage("[DEPRECATED demo] Gave you a blank canvas map (id=" + view.getId()
                + "). Hold it and run /canvas paint.");
        return Command.SINGLE_SUCCESS;
    }

    private int runPaint(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.FILLED_MAP) {
            player.sendMessage("Hold a filled_map in your main hand (try /canvas give first)");
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
        player.sendMessage("[DEPRECATED demo] Painted map #" + mapId + " red (palette=" + RED_PALETTE + ")");
        return Command.SINGLE_SUCCESS;
    }

    private int runPlaceholder(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        int slot = IntegerArgumentType.getInteger(ctx, "slot");
        int total = IntegerArgumentType.getInteger(ctx, "total");
        if (slot >= total) {
            player.sendMessage("slot must be < total");
            return 0;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.FILLED_MAP
                || !(item.getItemMeta() instanceof MapMeta meta) || !meta.hasMapView()) {
            player.sendMessage("Hold a filled_map in your main hand (try /canvas give first)");
            return 0;
        }
        MapView view = meta.getMapView();
        if (view == null) {
            player.sendMessage("MapMeta returned null map view");
            return 0;
        }

        byte[] pixels = placeholderRenderer.render(slot, total);
        mapPacketSender.sendFullMap(player, view.getId(), pixels);
        player.sendMessage("[DEPRECATED demo] Rendered placeholder on map #"
                + view.getId() + " slot=" + (slot + 1) + "/" + total);
        return Command.SINGLE_SUCCESS;
    }
}
