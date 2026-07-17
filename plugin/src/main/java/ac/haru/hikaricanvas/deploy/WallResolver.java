package ac.haru.hikaricanvas.deploy;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.Set;

/**
 * 从玩家两次点击（pos1/pos2 + BlockFace）解析出墙面矩形并做合法性校验。
 * 契约见 {@code docs/architecture.md §7.1}。纯算法类：输入 Bukkit 对象，
 * 输出不可变 Result。无副作用、无状态。
 *
 * <p><b>仅支持垂直墙面</b>（normal 必须是水平方向 N/S/E/W）。天花板/地板
 * （UP/DOWN）暂不放宽，避免处理跨轴像素方向的复杂度。</p>
 */
public final class WallResolver {

    /** 墙面的最大面积（地图数上限，对应 config 里 {@code limits.canvas-max-maps}）。 */
    private final int maxMaps;
    /** PDC key 用于识别 ItemFrame 是否属于 HikariCanvas（与 FrameDeployer 同 NamespacedKey）。 */
    private final NamespacedKey wallIdKey;

    public WallResolver(int maxMaps, JavaPlugin plugin) {
        if (maxMaps <= 0) {
            throw new IllegalArgumentException("maxMaps must be positive: " + maxMaps);
        }
        this.maxMaps = maxMaps;
        this.wallIdKey = new NamespacedKey(plugin, "wall_id");
    }

    public sealed interface Result {
        record Ok(
                World world,
                int minX, int minY, int minZ,
                int width, int height,     // 水平方向地图数 × 垂直方向地图数
                BlockFace facing,          // 墙面朝向（= 点击时的 normal）
                boolean hasExistingFrames  // bbox 内全是 HikariCanvas 自家画框 → 走"打开现有"路径
        ) implements Result {
            public int mapCount() {
                return width * height;
            }
        }
        record Failed(FailReason reason, String detail) implements Result {}
    }

    /** bbox 一格上的 ItemFrame 类型。 */
    private enum FrameKind { NONE, HIKARI, FOREIGN }

    public enum FailReason {
        NORMAL_MISMATCH,   // 两次点击的朝向不同
        DIFFERENT_WORLDS,  // 两个 block 在不同世界
        VERTICAL_ONLY,     // normal 是 UP/DOWN（不支持）
        NOT_COPLANAR,      // 两点不在同一垂直平面
        TOO_LARGE,         // 超过 maxMaps
        BLOCK_NOT_SOLID,     // bbox 内某方块非实心 full cube
        FRAME_SPACE_BLOCKED, // bbox 前方 + 1 格被非 air 方块（草/花/水等）占住，ItemFrame 无法稳定存在
        OCCUPIED             // bbox 前方 + 1 格已有 ItemFrame
    }

    private static final Set<BlockFace> HORIZONTAL = EnumSet.of(
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);

    public Result resolve(Block block1, BlockFace face1, Block block2, BlockFace face2) {
        if (face1 != face2) {
            return fail(FailReason.NORMAL_MISMATCH,
                    "first click face=" + face1 + " but second face=" + face2);
        }
        if (!HORIZONTAL.contains(face1)) {
            return fail(FailReason.VERTICAL_ONLY,
                    "normal=" + face1 + " not supported in M2 (only N/S/E/W)");
        }
        if (block1.getWorld() != block2.getWorld()) {
            return fail(FailReason.DIFFERENT_WORLDS,
                    "blocks belong to different worlds");
        }
        World world = block1.getWorld();

        // 共面校验：两点必须在同一垂直平面上（X 或 Z 相同，取决于 normal 轴）
        boolean xAxisNormal = (face1 == BlockFace.EAST || face1 == BlockFace.WEST);
        if (xAxisNormal) {
            if (block1.getX() != block2.getX()) {
                return fail(FailReason.NOT_COPLANAR,
                        "facing " + face1 + " requires same X; got " + block1.getX() + " vs " + block2.getX());
            }
        } else { // NORTH / SOUTH
            if (block1.getZ() != block2.getZ()) {
                return fail(FailReason.NOT_COPLANAR,
                        "facing " + face1 + " requires same Z; got " + block1.getZ() + " vs " + block2.getZ());
            }
        }

        int minX = Math.min(block1.getX(), block2.getX());
        int maxX = Math.max(block1.getX(), block2.getX());
        int minY = Math.min(block1.getY(), block2.getY());
        int maxY = Math.max(block1.getY(), block2.getY());
        int minZ = Math.min(block1.getZ(), block2.getZ());
        int maxZ = Math.max(block1.getZ(), block2.getZ());

        int width = xAxisNormal ? (maxZ - minZ + 1) : (maxX - minX + 1);
        int height = maxY - minY + 1;

        int totalMaps = width * height;
        if (totalMaps > maxMaps) {
            return fail(FailReason.TOO_LARGE,
                    width + "x" + height + "=" + totalMaps + " exceeds limit " + maxMaps);
        }

        // 校验：bbox 内每个方块是实心 full cube + 法线方向前一格状态：
        // (a) 空 air → 没框，正常；(b) HikariCanvas 自家画框 → 视为"打开现有 wall"信号；
        // (c) 第三方画框 → OCCUPIED 拒绝；(d) 非 air 方块（草/水）→ FRAME_SPACE_BLOCKED。
        boolean hasHikariFrame = false;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block wall = world.getBlockAt(x, y, z);
                    if (!isWallBlock(wall)) {
                        return fail(FailReason.BLOCK_NOT_SOLID,
                                "block at (" + x + "," + y + "," + z + ") = "
                                        + wall.getType() + " not a solid full cube");
                    }
                    Block adjacent = wall.getRelative(face1);
                    FrameKind kind = kindOfFrame(wall, face1);
                    if (kind == FrameKind.NONE) {
                        // 没画框 → 检查 air（草/水会让 frame despawn）
                        if (!adjacent.getType().isAir()) {
                            return fail(FailReason.FRAME_SPACE_BLOCKED,
                                    "frame space at (" + adjacent.getX() + "," + adjacent.getY() + "," + adjacent.getZ()
                                            + ") = " + adjacent.getType() + " must be air");
                        }
                    } else if (kind == FrameKind.FOREIGN) {
                        return fail(FailReason.OCCUPIED,
                                "non-HikariCanvas item frame at face " + face1
                                        + " of (" + x + "," + y + "," + z + ")");
                    } else {
                        // HIKARI：自家画框 → 标记 + 继续
                        hasHikariFrame = true;
                    }
                }
            }
        }

        return new Result.Ok(world, minX, minY, minZ, width, height, face1, hasHikariFrame);
    }

    private boolean isWallBlock(Block b) {
        Material t = b.getType();
        // 实心完整立方体。排除台阶 / 栅栏 / 玻璃板之类非 full cube。
        return t.isSolid() && t.isOccluding();
    }

    /**
     * 检查指定方块的指定 face 面是否挂着 ItemFrame，区分 HikariCanvas 自家 vs 第三方。
     * Bukkit 的 ItemFrame 实体坐标 = 挂它的方块的相邻一格中心。
     */
    private FrameKind kindOfFrame(Block wall, BlockFace face) {
        Block adjacent = wall.getRelative(face);
        for (ItemFrame frame : wall.getWorld().getNearbyEntitiesByType(
                ItemFrame.class, adjacent.getLocation().toCenterLocation(), 0.5)) {
            boolean hits = frame.getAttachedFace().getOppositeFace() == face
                    || frame.getLocation().getBlock().equals(adjacent);
            if (!hits) continue;
            // 是 HikariCanvas 自家？看 PDC wall_id
            if (frame.getPersistentDataContainer().has(wallIdKey, PersistentDataType.STRING)) {
                return FrameKind.HIKARI;
            }
            return FrameKind.FOREIGN;
        }
        return FrameKind.NONE;
    }

    private Result.Failed fail(FailReason r, String detail) {
        return new Result.Failed(r, detail);
    }
}
