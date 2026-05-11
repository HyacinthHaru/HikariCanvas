package moe.hikari.canvas.deploy;

import moe.hikari.canvas.render.HikariCanvasRenderer;
import moe.hikari.canvas.render.PlaceholderRenderer;
import moe.hikari.canvas.session.Session;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * 按 {@link WallResolver.Result.Ok} 的墙面矩形批量挂物品框 + 填 Placeholder 像素；
 * 提供按 wall 移除、publish 标签写 PDC，以及保护 listener 用的"是否属于 HikariCanvas"接口。
 *
 * <p>契约见 {@code docs/architecture.md §7.2}、PDC key 规范见 {@code docs/data-model.md §3.2}。
 * <b>M5.5：</b> PDC key 简化为 {@code wall_id / slot / published_at}，删除原 {@code session / sign / role}。</p>
 *
 * <p><b>主线程约束：</b> {@link #deploy}、{@link #removeForWall}、{@link #markPublished}
 * 都使用 Bukkit 实体/世界 API，必须在主线程调用。</p>
 */
public final class FrameDeployer {

    private final JavaPlugin plugin;
    private final PlaceholderRenderer placeholderRenderer;
    private final HikariCanvasRenderer canvasRenderer;

    private final NamespacedKey wallIdKey;
    private final NamespacedKey slotKey;
    private final NamespacedKey publishedAtKey;

    public FrameDeployer(JavaPlugin plugin,
                         PlaceholderRenderer placeholderRenderer,
                         HikariCanvasRenderer canvasRenderer) {
        this.plugin = plugin;
        this.placeholderRenderer = placeholderRenderer;
        this.canvasRenderer = canvasRenderer;
        // 固定 namespace = hikari_canvas
        this.wallIdKey = new NamespacedKey(plugin, "wall_id");
        this.slotKey = new NamespacedKey(plugin, "slot");
        this.publishedAtKey = new NamespacedKey(plugin, "published_at");
    }

    /**
     * 对墙面 bbox 的每个方块前一格位置挂一个物品框，注入 MapView，写 wall_id / slot PDC，
     * push placeholder 像素。
     *
     * <p>{@link Session#wallId()} 必须已设置（confirm 新建路径）。</p>
     */
    public int deploy(Session session, WallResolver.Result.Ok wall, List<Integer> mapIds) {
        if (session.wallId() == null) {
            throw new IllegalStateException("FrameDeployer.deploy: session.wallId() is null");
        }
        return deployFor(session.wallId(), wall, mapIds, null);
    }

    /**
     * M5-D9 wall.refresh：补 spawn 用户在创造模式撸掉的画框。{@code wallId} 已有 ItemFrame
     * 的 slot 跳过；缺失 slot 重新 spawn 同样的 PDC + 同样的 MapView。返回新补的数量。
     */
    public int repairFor(String wallId, WallResolver.Result.Ok wall, List<Integer> mapIds) {
        // 收集现有 slot
        java.util.Set<Integer> present = new java.util.HashSet<>();
        for (ItemFrame f : wall.world().getEntitiesByClass(ItemFrame.class)) {
            PersistentDataContainer pdc = f.getPersistentDataContainer();
            if (!wallId.equals(pdc.get(wallIdKey, PersistentDataType.STRING))) continue;
            Integer slot = pdc.get(slotKey, PersistentDataType.INTEGER);
            if (slot != null) present.add(slot);
        }
        return deployFor(wallId, wall, mapIds, present);
    }

    /**
     * 共享部署逻辑。{@code skipSlots} 非 null 时跳过这些 slot（refresh 路径用，只补缺失）。
     */
    private int deployFor(String wallId, WallResolver.Result.Ok wall,
                          List<Integer> mapIds, java.util.Set<Integer> skipSlots) {
        World world = wall.world();
        int width = wall.width();
        int height = wall.height();
        int total = wall.mapCount();
        if (mapIds.size() != total) {
            throw new IllegalArgumentException(
                    "mapIds size " + mapIds.size() + " doesn't match wall area " + total);
        }
        BlockFace facing = wall.facing();
        int mounted = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int slotIndex = row * width + col;
                if (skipSlots != null && skipSlots.contains(slotIndex)) continue;
                if (spawnSlot(wallId, wall, mapIds, facing, row, col, slotIndex, world, total)) {
                    mounted++;
                }
            }
        }
        return mounted;
    }

    /** 单 slot spawn。{@code true} = 成功。 */
    private boolean spawnSlot(String wallId, WallResolver.Result.Ok wall, List<Integer> mapIds,
                              BlockFace facing, int row, int col, int slotIndex,
                              World world, int total) {
        int height = wall.height();
        int width = wall.width();
        int blockY = wall.minY() + (height - 1 - row);
        int blockX;
        int blockZ;
        // M5.5：按 facing 映射 (row,col) → 世界坐标，使 col=0 对应玩家视角最左。
        switch (facing) {
            case NORTH -> {
                blockX = wall.minX() + (width - 1 - col);
                blockZ = wall.minZ();
            }
            case SOUTH -> {
                blockX = wall.minX() + col;
                blockZ = wall.minZ();
            }
            case EAST -> {
                blockX = wall.minX();
                blockZ = wall.minZ() + (width - 1 - col);
            }
            case WEST -> {
                blockX = wall.minX();
                blockZ = wall.minZ() + col;
            }
            default -> throw new IllegalStateException("unsupported facing: " + facing);
        }

        Location frameLoc = world.getBlockAt(
                blockX + facing.getModX(),
                blockY + facing.getModY(),
                blockZ + facing.getModZ()
        ).getLocation().toCenterLocation();

        int mapId = mapIds.get(slotIndex);
        MapView view = Bukkit.getMap(mapId);
        if (view == null) {
            plugin.getLogger().warning(
                    "FrameDeployer: MapView missing for mapId=" + mapId + ", skipping slot " + slotIndex);
            return false;
        }

        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        meta.setMapView(view);
        mapItem.setItemMeta(meta);

        final int finalSlot = slotIndex;
        world.spawn(frameLoc, ItemFrame.class, f -> {
            f.setFacingDirection(facing, true);
            f.setItem(mapItem);
            f.setRotation(Rotation.NONE);
            PersistentDataContainer pdc = f.getPersistentDataContainer();
            pdc.set(wallIdKey, PersistentDataType.STRING, wallId);
            pdc.set(slotKey, PersistentDataType.INTEGER, finalSlot);
        });

        // 首次/补 spawn 都写一遍 placeholder；wall.refresh 之后由 throttler 全画布重画覆盖
        byte[] pixels = placeholderRenderer.render(slotIndex, total);
        canvasRenderer.update(mapId, pixels);
        return true;
    }

    /** 扫世界删除某 wall 的所有 ItemFrame。/canvas delete confirm 走此路径。 */
    public int removeForWall(String wallId, World world) {
        int removed = 0;
        for (ItemFrame f : world.getEntitiesByClass(ItemFrame.class)) {
            String pdc = f.getPersistentDataContainer().get(wallIdKey, PersistentDataType.STRING);
            if (wallId.equals(pdc)) {
                f.remove();
                removed++;
            }
        }
        return removed;
    }

    /** publish/unpublish 时给所有 wall 的 ItemFrame 写 published_at PDC。{@code timestamp == null} 表示 unpublish。 */
    public int markPublished(String wallId, World world, Long timestamp) {
        int touched = 0;
        for (ItemFrame f : world.getEntitiesByClass(ItemFrame.class)) {
            String pdc = f.getPersistentDataContainer().get(wallIdKey, PersistentDataType.STRING);
            if (!wallId.equals(pdc)) continue;
            PersistentDataContainer pc = f.getPersistentDataContainer();
            if (timestamp == null) pc.remove(publishedAtKey);
            else pc.set(publishedAtKey, PersistentDataType.LONG, timestamp);
            touched++;
        }
        return touched;
    }

    /** 反查：{@link ItemFrame} 上的 wall_id PDC（wand 瞄已有画框 P3 用）。 */
    public String wallIdOf(ItemFrame frame) {
        return frame.getPersistentDataContainer().get(wallIdKey, PersistentDataType.STRING);
    }

    /** 是否由 HikariCanvas 管理——保护 listener 判定用。 */
    public boolean isProtectedFrame(ItemFrame frame) {
        return frame.getPersistentDataContainer().has(wallIdKey, PersistentDataType.STRING);
    }

    public NamespacedKey wallIdKey() { return wallIdKey; }
    public NamespacedKey slotKey() { return slotKey; }
    public NamespacedKey publishedAtKey() { return publishedAtKey; }
}
