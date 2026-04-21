package moe.hikari.canvas.deploy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

/**
 * Canvas Wand 物品工厂 + 识别。
 *
 * <p>物品类型：命名金铲（{@link Material#GOLDEN_SHOVEL}），WorldEdit 风格肌肉记忆。
 * PDC key {@code canvas:wand_owner} 存玩家 UUID 字符串，防止别人捡到后误触。
 *
 * <p>PDC namespace 固定 {@code hikari_canvas}（见 {@code data-model.md §3.1}）。</p>
 */
public final class CanvasWand {

    private static final String PDC_KEY = "wand_owner";

    private CanvasWand() {}

    public static ItemStack forPlayer(JavaPlugin plugin, Player player) {
        ItemStack item = new ItemStack(Material.GOLDEN_SHOVEL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Canvas Wand")
                .color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Left-click: first corner",
                        NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Right-click: second corner",
                        NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Then /canvas confirm",
                        NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, PDC_KEY),
                PersistentDataType.STRING,
                player.getUniqueId().toString());
        item.setItemMeta(meta);
        return item;
    }

    /** 当前物品是否是 {@code playerUuid} 的 Canvas Wand。 */
    public static boolean isWandFor(ItemStack item, UUID playerUuid, JavaPlugin plugin) {
        if (item == null || item.getType() != Material.GOLDEN_SHOVEL) return false;
        if (!item.hasItemMeta()) return false;
        String owner = item.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey(plugin, PDC_KEY),
                PersistentDataType.STRING);
        return playerUuid.toString().equals(owner);
    }

    /** 从玩家背包移除所有 Canvas Wand（/canvas confirm / cancel 后调用）。 */
    public static int removeAllFrom(Player player, JavaPlugin plugin) {
        int removed = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (isWandFor(it, player.getUniqueId(), plugin)) {
                player.getInventory().setItem(i, null);
                removed++;
            }
        }
        return removed;
    }
}
