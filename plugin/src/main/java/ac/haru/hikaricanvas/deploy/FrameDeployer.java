package ac.haru.hikaricanvas.deploy;

import ac.haru.hikaricanvas.render.HikariCanvasRenderer;
import ac.haru.hikaricanvas.render.PlaceholderRenderer;
import ac.haru.hikaricanvas.session.Session;
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
 * PDC key = {@code wall_id / slot}（{@code published_at} 不再写入；现有 PDC 数据保留无害）。</p>
 *
 * <p><b>主线程约束：</b> {@link #deploy}、{@link #removeForWall} 都使用 Bukkit
 * 实体/世界 API，必须在主线程调用。</p>
 */
public final class FrameDeployer {

    private final JavaPlugin plugin;
    private final PlaceholderRenderer placeholderRenderer;
    private final HikariCanvasRenderer canvasRenderer;

    private final NamespacedKey wallIdKey;
    private final NamespacedKey slotKey;

    public FrameDeployer(JavaPlugin plugin,
                         PlaceholderRenderer placeholderRenderer,
                         HikariCanvasRenderer canvasRenderer) {
        this.plugin = plugin;
        this.placeholderRenderer = placeholderRenderer;
        this.canvasRenderer = canvasRenderer;
        // 固定 namespace = hikaricanvas（NamespacedKey(plugin,…) 取插件名小写，HikariCanvas → hikaricanvas）
        this.wallIdKey = new NamespacedKey(plugin, "wall_id");
        this.slotKey = new NamespacedKey(plugin, "slot");
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
     * {@link #repairFor} 的结果。
     *
     * @param framesRespawned   完全不存在的画框 → 新 spawn 的数量
     * @param framesReAttached  画框还挂着但 map item 被撸掉的 → 直接 setItem 塞回的数量
     * @param wallBlocksReplaced 顺手补回的支撑方块数
     */
    public record RepairResult(int framesRespawned, int framesReAttached, int wallBlocksReplaced) {
        /** 合并"重挂"计数：spawn 新 frame + 给空 frame 塞回 map，对玩家来说都算"修好了一格"。 */
        public int framesFixed() { return framesRespawned + framesReAttached; }
    }

    /**
     * wall.refresh：补 spawn 用户在创造模式撸掉的画框。
     *
     * <p><b>关键修复：</b> 玩家通常撸掉的是<b>支撑方块</b>，不是画框本身；
     * 画框因失去支撑掉到地上变成物品。仅 spawn 新画框时它会立即又掉下来 —— 这就是"按刷新按钮
     * 看似没反应"的真实原因。本方法现在先扫一遍墙面 bbox，把缺失的支撑方块用 {@link Material#STONE}
     * 自动补回（玩家可以事后手动换皮），再走原 spawn 流程。</p>
     *
     * <p>{@code wallId} 已有 ItemFrame 的 slot 跳过；缺失 slot 重新 spawn 同样的 PDC + MapView。</p>
     */
    public RepairResult repairFor(String wallId, WallResolver.Result.Ok wall, List<Integer> mapIds) {
        // 0) 先把墙面所在区块加载起来。找现存画框走的是 Bukkit 实体查询，而实体查询
        //    **只看得见已加载区块**——玩家在几百格外点刷新时墙那边多半没人、区块没加载，
        //    扫出来是 0 个画框，于是这里判定"全都不见了"，转头又 spawn 一整面新的，
        //    区块加载回来就是两层画框叠在一起。
        loadWallChunks(wall);

        // 1) 补回缺失的支撑方块（画框需要支撑才能挂住）
        int wallBlocksReplaced = replaceMissingWallBlocks(wall);

        // 2) 扫现存画框。三态：
        //    a) 完整（PDC + 有 FILLED_MAP item）→ present
        //    b) 空框（PDC 在但 item 被撸掉 / 不是 FILLED_MAP）→ emptyFrames，待 setItem 重新塞回
        //    c) 完全不存在 → 待 deployFor spawn
        //    MC 左键撸 ItemFrame 第一次去内容，第二次才打掉 entity；很多玩家只撸第一下就发现"画没了"
        java.util.Set<Integer> present = new java.util.HashSet<>();
        java.util.Map<Integer, ItemFrame> emptyFrames = new java.util.HashMap<>();
        int scanned = 0;
        int deadOrInvalid = 0;
        int wallMatched = 0;
        for (ItemFrame f : framesAroundWall(wall)) {
            scanned++;
            if (f.isDead() || !f.isValid()) { deadOrInvalid++; continue; }
            PersistentDataContainer pdc = f.getPersistentDataContainer();
            if (!wallId.equals(pdc.get(wallIdKey, PersistentDataType.STRING))) continue;
            wallMatched++;
            Integer slot = pdc.get(slotKey, PersistentDataType.INTEGER);
            if (slot == null) continue;
            ItemStack held = f.getItem();
            if (held != null && held.getType() == Material.FILLED_MAP) {
                present.add(slot);
            } else {
                emptyFrames.put(slot, f);
            }
        }
        int expected = wall.width() * wall.height();
        plugin.getLogger().fine(String.format(
                "[wall.refresh %s] scanned=%d deadOrInvalid=%d wallMatched=%d present=%s "
                        + "emptyFrames=%s expected=%d replacedBlocks=%d",
                wallId, scanned, deadOrInvalid, wallMatched, present,
                emptyFrames.keySet(), expected, wallBlocksReplaced));

        // 3) 给"画框还在但内容被撸掉"的 slot 直接 setItem，不需要 spawn 新 entity
        java.util.Set<Integer> reAttachedSlots = reAttachMapsToEmptyFrames(wallId, wall, mapIds, emptyFrames);
        int framesReAttached = reAttachedSlots.size();

        // 4) 完全缺失的 slot 走原 spawn 路径。
        //    只跳过"完整 present"和"成功 reattach"的 slot；reattach 失败的空框（坏 mapId /
        //    MapView 缺失）不进 skipSlots，交给 deployFor 重新 spawn，避免永久空框。
        java.util.Set<Integer> skipSlots = new java.util.HashSet<>(present);
        skipSlots.addAll(reAttachedSlots);
        int framesRespawned = deployFor(wallId, wall, mapIds, skipSlots);
        plugin.getLogger().fine("[wall.refresh " + wallId + "] framesRespawned=" + framesRespawned
                + " framesReAttached=" + framesReAttached);
        return new RepairResult(framesRespawned, framesReAttached, wallBlocksReplaced);
    }

    /**
     * 给已存在但 item 被撸掉的画框重新塞回对应 mapId 的 FILLED_MAP。
     *
     * @return 成功 reattach 的 slot 集合。失败的 slot（mapId 越界 / MapView 缺失）<b>不</b>纳入返回，
     *         由 {@link #repairFor} 交给 {@link #deployFor} 重新 spawn，避免永久空框。
     */
    private java.util.Set<Integer> reAttachMapsToEmptyFrames(String wallId, WallResolver.Result.Ok wall,
                                           List<Integer> mapIds,
                                           java.util.Map<Integer, ItemFrame> emptyFrames) {
        java.util.Set<Integer> reAttached = new java.util.HashSet<>();
        int total = wall.mapCount();
        for (var entry : emptyFrames.entrySet()) {
            int slot = entry.getKey();
            ItemFrame f = entry.getValue();
            if (slot < 0 || slot >= mapIds.size()) {
                plugin.getLogger().warning("[reAttach] slot " + slot + " out of mapIds range for wall " + wallId);
                continue;
            }
            int mapId = mapIds.get(slot);
            MapView view = Bukkit.getMap(mapId);
            if (view == null) {
                plugin.getLogger().warning("[reAttach] MapView missing for mapId=" + mapId + " slot=" + slot);
                continue;
            }
            ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) mapItem.getItemMeta();
            meta.setMapView(view);
            mapItem.setItemMeta(meta);
            f.setItem(mapItem);
            f.setRotation(Rotation.NONE);
            // 同时把当前 placeholder 重画一遍，throttler 之后会把真实 ProjectState 像素覆盖
            byte[] pixels = placeholderRenderer.render(slot, total);
            canvasRenderer.update(mapId, pixels);
            plugin.getLogger().fine("[reAttach] OK slot=" + slot + " mapId=" + mapId
                    + " frameUuid=" + f.getUniqueId());
            reAttached.add(slot);
        }
        return reAttached;
    }

    /**
     * 把墙面 bbox（含画框那一层 + 一格余量）覆盖到的区块同步加载起来。
     *
     * <p>Bukkit 的实体查询只看得见已加载区块，所以任何"扫现有画框"的逻辑都必须先做这一步，
     * 否则会把"区块没加载所以看不见"误判成"画框不存在"。与
     * {@code replaceMissingWallBlocks} 里 {@code world.getBlockAt} 的同步加载是同一性质。</p>
     */
    private void loadWallChunks(WallResolver.Result.Ok wall) {
        World world = wall.world();
        int minChunkX = (wall.minX() - 1) >> 4;
        int maxChunkX = (maxX(wall) + 1) >> 4;
        int minChunkZ = (wall.minZ() - 1) >> 4;
        int maxChunkZ = (maxZ(wall) + 1) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                world.getChunkAt(cx, cz);   // 同步加载
            }
        }
    }

    /**
     * 墙面附近的 ItemFrame。<b>只查墙自己的 bbox（各方向留 1 格余量）</b>，不是整个世界扫一遍：
     * 画框挂在墙面前一格，位置完全由几何决定，没有必要（也不该）为了找它们去遍历世界上
     * 所有实体。调用前须先 {@link #loadWallChunks}。
     */
    private java.util.List<ItemFrame> framesAroundWall(WallResolver.Result.Ok wall) {
        World world = wall.world();
        org.bukkit.util.BoundingBox box = new org.bukkit.util.BoundingBox(
                wall.minX() - 1, wall.minY() - 1, wall.minZ() - 1,
                maxX(wall) + 2, maxY(wall) + 2, maxZ(wall) + 2);
        java.util.List<ItemFrame> out = new java.util.ArrayList<>();
        for (org.bukkit.entity.Entity e : world.getNearbyEntities(box)) {
            if (e instanceof ItemFrame f) out.add(f);
        }
        return out;
    }

    /** 墙面 bbox 的最大 X 方块坐标（EAST/WEST 朝向时墙沿 Z 展开，X 只有一列）。 */
    private static int maxX(WallResolver.Result.Ok wall) {
        BlockFace f = wall.facing();
        boolean spansZ = (f == BlockFace.EAST || f == BlockFace.WEST);
        return spansZ ? wall.minX() : wall.minX() + wall.width() - 1;
    }

    /** 墙面 bbox 的最大 Z 方块坐标。 */
    private static int maxZ(WallResolver.Result.Ok wall) {
        BlockFace f = wall.facing();
        boolean spansZ = (f == BlockFace.EAST || f == BlockFace.WEST);
        return spansZ ? wall.minZ() + wall.width() - 1 : wall.minZ();
    }

    /** 墙面 bbox 的最大 Y 方块坐标。 */
    private static int maxY(WallResolver.Result.Ok wall) {
        return wall.minY() + wall.height() - 1;
    }

    /**
     * 扫 wall bbox 内的方块；任何 AIR 都视为玩家撸掉的支撑，补 {@link Material#STONE}。
     * 安全考虑：只动 wall 自己 8 个方块的 bbox，绝不外溢。
     */
    private int replaceMissingWallBlocks(WallResolver.Result.Ok wall) {
        World world = wall.world();
        int width = wall.width();
        int height = wall.height();
        BlockFace facing = wall.facing();
        int replaced = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int blockY = wall.minY() + (height - 1 - row);
                int blockX;
                int blockZ;
                switch (facing) {
                    case NORTH -> { blockX = wall.minX() + (width - 1 - col); blockZ = wall.minZ(); }
                    case SOUTH -> { blockX = wall.minX() + col;               blockZ = wall.minZ(); }
                    case EAST  -> { blockX = wall.minX();                     blockZ = wall.minZ() + (width - 1 - col); }
                    case WEST  -> { blockX = wall.minX();                     blockZ = wall.minZ() + col; }
                    default -> throw new IllegalStateException("unsupported facing: " + facing);
                }
                org.bukkit.block.Block b = world.getBlockAt(blockX, blockY, blockZ);
                if (b.getType().isAir()) {
                    b.setType(Material.STONE, false);
                    replaced++;
                }
            }
        }
        return replaced;
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
        // 按 facing 映射 (row,col) → 世界坐标，使 col=0 对应玩家视角最左。
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

        // 防御 1：支撑方块仍 AIR（极小概率：replaceMissingWallBlocks 之后又有 player 撸到）→ 再补
        org.bukkit.block.Block support = world.getBlockAt(blockX, blockY, blockZ);
        if (support.getType().isAir()) {
            plugin.getLogger().warning("[spawnSlot] support still AIR at (" + blockX + "," + blockY
                    + "," + blockZ + ") slot=" + slotIndex + "; placing STONE again");
            support.setType(Material.STONE, false);
        }

        // 防御 2：frameLoc 处的方块必须 air，否则 ItemFrame 无法 spawn
        org.bukkit.block.Block frontBlock = world.getBlockAt(
                blockX + facing.getModX(),
                blockY + facing.getModY(),
                blockZ + facing.getModZ());
        if (!frontBlock.getType().isAir()) {
            // 只清"能被放置动作直接覆盖"的方块：草、雪层、水、火之类自然长出来 / 流过来的东西
            // （WallResolver 在选区时就是拿它们当 FRAME_SPACE_BLOCKED 拒掉的）。
            // 玩家真放上去的方块（箱子、告示牌、任意建材）**不动**——这一格宁可不挂画框，
            // 也不能让插件替玩家把方块删了。缺的那一格清干净后再点一次刷新即可补上。
            if (!frontBlock.isReplaceable()) {
                plugin.getLogger().warning("[spawnSlot] front block at " + frontBlock.getLocation()
                        + " is " + frontBlock.getType() + "; someone placed a block in the frame"
                        + " space — skipping slot=" + slotIndex + " instead of breaking it");
                return false;
            }
            plugin.getLogger().warning("[spawnSlot] front block at " + frontBlock.getLocation()
                    + " is " + frontBlock.getType() + " (not air), clearing for slot=" + slotIndex);
            frontBlock.setType(Material.AIR, false);
        }

        // 防御 3：清掉 frameLoc 1 格内残留的 Item 掉落物（撸破的画框 / 地图 item 可能掉在这）
        //         + 清掉"自己 wall + 同 slot 上次失败 spawn 留下的"幽灵 ItemFrame。
        //
        // 掉落物只删画框和地图这两种：它们就是本插件自己弄掉的东西。原实现对范围内**所有**
        // 掉落物一律 remove()，玩家扔在墙根的钻石、死在这里掉的一整套装备都会在 confirm /
        // 重启恢复时被无声销毁。
        //
        // 关键约束 A（邻接 wall confirm 误删）：
        //   不动 PDC wall_id != current 的 ItemFrame —— ItemFrame bbox 半径 ~0.25 + query box
        //   半径 0.8 = 1.05 格 > 标准 1 格间距，相邻 wall 的 frame bbox 会被 getNearbyEntities
        //   抓到。
        // 关键约束 B（同 wall 邻接 slot 互删导致只剩 1 frame）：
        //   同 wall_id 但邻接 slot 也会被 query box 抓到（slot 间距 1 + bbox 0.5 + box 0.8 相交）。
        //   光看 wall_id 会把刚 spawn 的兄弟 slot 当残骸删。必须额外要求 PDC slot == current slot
        //   才算"真正同位置的失败 spawn 残留"。
        //   PDC 不带 wall_id 的 vanilla ItemFrame（玩家自己挂的画框 / 地图）也不动——
        //   位置占用问题由 WallResolver 在 confirm 之前的 OCCUPIED 检查拒绝。
        for (org.bukkit.entity.Entity e : world.getNearbyEntities(frameLoc, 0.8, 0.8, 0.8)) {
            if (e instanceof org.bukkit.entity.Item drop) {
                Material dropped = drop.getItemStack().getType();
                if (dropped != Material.ITEM_FRAME && dropped != Material.GLOW_ITEM_FRAME
                        && dropped != Material.FILLED_MAP && dropped != Material.MAP) {
                    continue;   // 玩家的东西，不碰
                }
                plugin.getLogger().fine("[spawnSlot] removing stray " + dropped + " at "
                        + e.getLocation() + " for slot=" + slotIndex);
                e.remove();
            } else if (e instanceof ItemFrame ifr) {
                PersistentDataContainer pdc = ifr.getPersistentDataContainer();
                String w = pdc.get(wallIdKey, PersistentDataType.STRING);
                Integer s = pdc.get(slotKey, PersistentDataType.INTEGER);
                if (wallId.equals(w) && s != null && s == slotIndex) {
                    plugin.getLogger().fine("[spawnSlot] removing stale ItemFrame (same wall + slot "
                            + slotIndex + ") at " + e.getLocation());
                    e.remove();
                }
                // 其他 wall_id / 同 wall 但不同 slot / PDC 缺失的 ItemFrame：跳过，不动
            }
        }

        final int finalSlot = slotIndex;
        ItemFrame frame;
        try {
            frame = world.spawn(frameLoc, ItemFrame.class, f -> {
                f.setFacingDirection(facing, true);
                f.setItem(mapItem);
                f.setRotation(Rotation.NONE);
                PersistentDataContainer pdc = f.getPersistentDataContainer();
                pdc.set(wallIdKey, PersistentDataType.STRING, wallId);
                pdc.set(slotKey, PersistentDataType.INTEGER, finalSlot);
            });
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "[spawnSlot] world.spawn threw for slot=" + slotIndex
                            + " loc=" + frameLoc + " facing=" + facing, e);
            return false;
        }
        if (frame == null || frame.isDead() || !frame.isValid()) {
            plugin.getLogger().warning("[spawnSlot] spawn returned invalid frame slot=" + slotIndex
                    + " loc=" + frameLoc + " frame=" + frame
                    + " isDead=" + (frame != null && frame.isDead())
                    + " isValid=" + (frame != null && frame.isValid()));
            return false;
        }
        plugin.getLogger().fine("[spawnSlot] OK slot=" + slotIndex + " mapId=" + mapId
                + " loc=" + frameLoc + " uuid=" + frame.getUniqueId());

        // 首次/补 spawn 都写一遍 placeholder；wall.refresh 之后由 throttler 全画布重画覆盖
        byte[] pixels = placeholderRenderer.render(slotIndex, total);
        canvasRenderer.update(mapId, pixels);
        return true;
    }

    /**
     * 扫世界删除某 wall 的所有 ItemFrame。{@code /canvas delete confirm} 走此路径。
     *
     * <p><b>调用方必须先确保墙面所在区块已加载</b>：这里用的实体查询只看得见已加载区块，
     * 区块没加载就一个也扫不到，而调用方通常紧接着就把 walls 行删了 —— 留在世界上的画框
     * 从此没有任何东西认得它，它挂着的地图 ID 却已回到池里被下一面墙借走，那面孤儿画框
     * 就会显示别人的画。{@code CanvasCommand.runDeleteConfirm} 为此在调用前按 wall 尺寸
     * 同步加载了一圈区块，并在世界未加载时直接拒绝删除。</p>
     */
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

    /** 反查：{@link ItemFrame} 上的 wall_id PDC（wand 瞄已有画框用）。 */
    public String wallIdOf(ItemFrame frame) {
        return frame.getPersistentDataContainer().get(wallIdKey, PersistentDataType.STRING);
    }

    /** 是否由 HikariCanvas 管理——保护 listener 判定用。 */
    public boolean isProtectedFrame(ItemFrame frame) {
        return frame.getPersistentDataContainer().has(wallIdKey, PersistentDataType.STRING);
    }

    public NamespacedKey wallIdKey() { return wallIdKey; }
    public NamespacedKey slotKey() { return slotKey; }
}
