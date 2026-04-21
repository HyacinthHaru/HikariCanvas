package moe.hikari.canvas.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import moe.hikari.canvas.deploy.CanvasWand;
import moe.hikari.canvas.deploy.MapPacketSender;
import moe.hikari.canvas.render.PlaceholderRenderer;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.session.SessionState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;

/**
 * {@code /canvas} 根命令的 Brigadier 注册点。正式子命令
 * （edit / wand / confirm / cancel / commit / cleanup / stats）
 * 将在 M2-T11 逐步接入。
 *
 * <p>M2-T6 已接入的正式子命令：
 * <ul>
 *   <li>{@code /canvas edit}   — 开启 SELECTING 状态</li>
 *   <li>{@code /canvas wand}   — 领取 Canvas Wand 物品</li>
 *   <li>{@code /canvas cancel} — 撤销当前会话（任何阶段）</li>
 * </ul>
 *
 * <p>M1 demo 遗留 DEPRECATED 子命令（M2-T11 命令族完整实装时删）：
 * <ul>
 *   <li>{@code /canvas give} — 给玩家一张空白 filled_map</li>
 *   <li>{@code /canvas paint} — 把主手 filled_map 涂红</li>
 *   <li>{@code /canvas placeholder <slot> <total>} — placeholder 视觉验证</li>
 * </ul>
 */
public final class CanvasCommand {

    private static final byte RED_PALETTE = 18;  // M1 demo paint 用

    private final JavaPlugin plugin;
    private final MapPacketSender mapPacketSender;
    private final SessionManager sessionManager;
    private final PlaceholderRenderer placeholderRenderer = new PlaceholderRenderer();

    public CanvasCommand(JavaPlugin plugin, MapPacketSender mapPacketSender, SessionManager sessionManager) {
        this.plugin = plugin;
        this.mapPacketSender = mapPacketSender;
        this.sessionManager = sessionManager;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("canvas")
                .requires(src -> src.getSender() instanceof Player)
                // 正式（T6）
                .then(Commands.literal("edit").executes(this::runEdit))
                .then(Commands.literal("wand").executes(this::runWand))
                .then(Commands.literal("cancel").executes(this::runCancel))
                // DEPRECATED demo（T11 删）
                .then(Commands.literal("give").executes(this::runGive))
                .then(Commands.literal("paint").executes(this::runPaint))
                .then(Commands.literal("placeholder")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(0))
                                .then(Commands.argument("total", IntegerArgumentType.integer(1))
                                        .executes(this::runPlaceholder))))
                .build();
    }

    // ---------- 正式子命令 ----------

    private int runEdit(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        SessionManager.BeginResult r = sessionManager.beginSelecting(
                player.getUniqueId(), player.getName());
        if (r instanceof SessionManager.BeginResult.Ok) {
            player.sendMessage(Component.text(
                    "Selection mode on. Left-click first corner, right-click second corner, then /canvas confirm.",
                    NamedTextColor.GOLD));
        } else if (r instanceof SessionManager.BeginResult.AlreadyHasSession ex) {
            Session existing = ex.existing();
            player.sendMessage(Component.text(
                    "You already have an active canvas session (state=" + existing.state()
                            + "). Use /canvas cancel first.",
                    NamedTextColor.RED));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int runWand(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        ItemStack wand = CanvasWand.forPlayer(plugin, player);
        player.getInventory().addItem(wand);
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
        SessionState prev = s.state();
        sessionManager.cancel(s.id(), "player-cancel");
        // 如果持有 wand，一并收回（契约：confirm/cancel 后 wand 消失）
        int removed = CanvasWand.removeAllFrom(player, plugin);
        StringBuilder msg = new StringBuilder("Session cancelled (was " + prev + ").");
        if (removed > 0) msg.append(" Wand returned.");
        player.sendMessage(Component.text(msg.toString(), NamedTextColor.YELLOW));
        return Command.SINGLE_SUCCESS;
    }

    // ---------- DEPRECATED demo ----------

    private int runGive(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();

        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapView view = Bukkit.createMap(player.getWorld());
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
