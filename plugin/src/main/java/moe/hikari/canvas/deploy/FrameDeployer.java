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
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * 按 {@link WallResolver.Result.Ok} 的墙面矩形批量挂物品框 + 填 Placeholder 像素；
 * 提供会话终止时的物品框移除、commit 时的 permanent 升级；以及保护 listener
 * 用来判定"是否属于 HikariCanvas 管理"的接口。
 *
 * <p>契约见 {@code docs/architecture.md §7.2}、PDC key 规范见 {@code docs/data-model.md §3.2}。</p>
 *
 * <p><b>主线程约束：</b> {@link #deploy}、{@link #removeForSession}、{@link #promote} 都
 * 使用 Bukkit 实体/世界 API，必须在主线程调用。</p>
 *
 * <p><b>per-viewer 同步局限：</b> M2 阶段 {@link #deploy} 只对会话玩家 push 一次
 * Placeholder 像素；其他在线玩家看到的是 MC 客户端本地缓存（新地图可能是空白）。
 * 完整的 per-viewer 差分同步留给 M3。</p>
 */
public final class FrameDeployer {

    /** 物品框的 PDC role 可选值。 */
    public static final String ROLE_PREVIEW = "preview";
    public static final String ROLE_PERMANENT = "permanent";

    private final JavaPlugin plugin;
    private final PlaceholderRenderer placeholderRenderer;
    private final HikariCanvasRenderer canvasRenderer;

    private final NamespacedKey sessionKey;
    private final NamespacedKey signKey;
    private final NamespacedKey slotKey;
    private final NamespacedKey roleKey;

    public FrameDeployer(JavaPlugin plugin,
                         PlaceholderRenderer placeholderRenderer,
                         HikariCanvasRenderer canvasRenderer) {
        this.plugin = plugin;
        this.placeholderRenderer = placeholderRenderer;
        this.canvasRenderer = canvasRenderer;
        // 固定 namespace = hikari_canvas（和 data-model.md §3.1 对齐）
        this.sessionKey = new NamespacedKey(plugin, "session");
        this.signKey = new NamespacedKey(plugin, "sign");
        this.slotKey = new NamespacedKey(plugin, "slot");
        this.roleKey = new NamespacedKey(plugin, "role");
    }

    /**
     * 对墙面 bbox 的每个方块前一格位置挂一个物品框，
     * 注入对应的 MapView，并立即 push Placeholder 像素给会话玩家。
     *
     * <p>slot 编号规则：{@code slotIndex = row * width + col}；
     * {@code row=0} 对应 bbox 的最上排（Y=maxY），
     * {@code col=0} 对应 bbox 的最小水平坐标（EAST/WEST 朝向下为 minZ；
     * NORTH/SOUTH 朝向下为 minX）。玩家实际视觉方向在 M3 完整 per-viewer
     * 同步时再对齐。</p>
     *
     * @return 实际挂上的物品框数（= {@code mapIds.size()} 若全部成功）
     */
    public int deploy(Session session, WallResolver.Result.Ok wall, List<Integer> mapIds) {
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

                int blockY = wall.minY() + (height - 1 - row);
                int blockX;
                int blockZ;
                if (facing == BlockFace.EAST || facing == BlockFace.WEST) {
                    blockX = wall.minX();
                    blockZ = wall.minZ() + col;
                } else {
                    blockX = wall.minX() + col;
                    blockZ = wall.minZ();
                }

                // frame 实体位置 = 墙块朝 facing 方向 1 格**的方块中心**。
                // 用整数 block 坐标 spawn 时 MC 客户端会把 entity 判为不合法并立即
                // despawn（"闪一下恢复原样"）——改用 block.toCenterLocation() 放到
                // 格子中心（x+0.5, y+0.5, z+0.5）才稳妥。
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
                    continue;
                }
                // MapPool.initialize / expand 已为每张 MapView 装好 HikariCanvasRenderer；
                // 这里**不要**再清 renderer（否则把刚装的那个也清掉了）

                ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
                MapMeta meta = (MapMeta) mapItem.getItemMeta();
                meta.setMapView(view);
                mapItem.setItemMeta(meta);

                final int finalSlot = slotIndex;
                // M2 调试：暂时不 setInvisible / setFixed——Paper 1.21 spawn consumer
                // 里同时设这两项会触发客户端 entity desync（spawn 瞬间 despawn "闪一下"）。
                // 保护交给 FrameProtectionListener。polished 版本 M7 再评估。
                ItemFrame frame = world.spawn(frameLoc, ItemFrame.class, f -> {
                    f.setFacingDirection(facing, true);
                    f.setItem(mapItem);
                    f.setRotation(Rotation.NONE);
                    PersistentDataContainer pdc = f.getPersistentDataContainer();
                    pdc.set(sessionKey, PersistentDataType.STRING, session.id());
                    pdc.set(slotKey, PersistentDataType.INTEGER, finalSlot);
                    pdc.set(roleKey, PersistentDataType.STRING, ROLE_PREVIEW);
                });

                // 写 Placeholder 像素到 shared renderer；Paper 每 tick 自动同步给所有 viewer
                byte[] pixels = placeholderRenderer.render(slotIndex, total);
                canvasRenderer.update(mapId, pixels);
                mounted++;
            }
        }
        return mounted;
    }

    /** 扫整个世界，删除属于指定 session 的所有 preview 物品框。 */
    public int removeForSession(String sessionId, World world) {
        int removed = 0;
        for (ItemFrame f : world.getEntitiesByClass(ItemFrame.class)) {
            String pdc = f.getPersistentDataContainer().get(sessionKey, PersistentDataType.STRING);
            if (sessionId.equals(pdc)) {
                f.remove();
                removed++;
            }
        }
        return removed;
    }

    /** commit 时：把 preview 物品框升级为 permanent（改 PDC，不重建实体）。 */
    public int promote(String sessionId, String signId, World world) {
        int promoted = 0;
        for (ItemFrame f : world.getEntitiesByClass(ItemFrame.class)) {
            PersistentDataContainer pdc = f.getPersistentDataContainer();
            String s = pdc.get(sessionKey, PersistentDataType.STRING);
            if (!sessionId.equals(s)) continue;
            pdc.remove(sessionKey);
            pdc.set(signKey, PersistentDataType.STRING, signId);
            pdc.set(roleKey, PersistentDataType.STRING, ROLE_PERMANENT);
            promoted++;
        }
        return promoted;
    }

    /** 是否由 HikariCanvas 管理（preview 或 permanent）——保护 listener 判定用。 */
    public boolean isProtectedFrame(ItemFrame frame) {
        PersistentDataContainer pdc = frame.getPersistentDataContainer();
        return pdc.has(sessionKey, PersistentDataType.STRING)
                || pdc.has(signKey, PersistentDataType.STRING);
    }

    public NamespacedKey sessionKey() { return sessionKey; }
    public NamespacedKey signKey()    { return signKey; }
    public NamespacedKey slotKey()    { return slotKey; }
    public NamespacedKey roleKey()    { return roleKey; }
}
