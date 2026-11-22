package ac.haru.hikaricanvas.deploy;

import ac.haru.hikaricanvas.i18n.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
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
        this(maxMaps, new NamespacedKey(plugin, "wall_id"));
    }

    /**
     * 直接给 PDC key 的构造。{@code new NamespacedKey(plugin, ...)} 需要一个真的插件实例，
     * 单测里造不出来；本构造让"选区几何 / 上限判定"这部分纯算法可以脱离 Bukkit server 测试。
     * 生产一律走 {@link #WallResolver(int, JavaPlugin)}，两者产生同一个 key。
     */
    public WallResolver(int maxMaps, NamespacedKey wallIdKey) {
        if (maxMaps <= 0) {
            throw new IllegalArgumentException("maxMaps must be positive: " + maxMaps);
        }
        this.maxMaps = maxMaps;
        this.wallIdKey = wallIdKey;
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
        record Failed(FailReason reason, String detail, Map<String, String> params) implements Result {
            public Failed(FailReason reason, String detail) {
                this(reason, detail, Map.of());
            }

            public Failed {
                params = params == null ? Map.of() : Map.copyOf(params);
            }
        }
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
                    "first click face=" + face1 + " but second face=" + face2,
                    Map.of("face1", face1 == null ? "?" : face1.name(),
                            "face2", face2 == null ? "?" : face2.name()));
        }
        if (!HORIZONTAL.contains(face1)) {
            return fail(FailReason.VERTICAL_ONLY,
                    "floor/ceiling placement is not supported (N/S/E/W walls only)");
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
                        "facing " + face1 + " requires same X; got " + block1.getX() + " vs " + block2.getX(),
                        Map.of("facing", face1.name(),
                                "axis", "X",
                                "coord1", String.valueOf(block1.getX()),
                                "coord2", String.valueOf(block2.getX())));
            }
        } else { // NORTH / SOUTH
            if (block1.getZ() != block2.getZ()) {
                return fail(FailReason.NOT_COPLANAR,
                        "facing " + face1 + " requires same Z; got " + block1.getZ() + " vs " + block2.getZ(),
                        Map.of("facing", face1.name(),
                                "axis", "Z",
                                "coord1", String.valueOf(block1.getZ()),
                                "coord2", String.valueOf(block2.getZ())));
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

        // 用 long 算面积。两次点击的坐标差可以到几千万（/tp 之后隔着半张地图点两下），
        // width * height 在 int 里会溢出成负数或一个很小的正数，直接绕过下面的上限判定。
        long totalMaps = (long) width * (long) height;
        if (totalMaps > maxMaps) {
            return fail(FailReason.TOO_LARGE,
                    width + "x" + height + "=" + totalMaps + " exceeds limit " + maxMaps,
                    Map.of("width", String.valueOf(width),
                            "height", String.valueOf(height),
                            "maps", String.valueOf(totalMaps),
                            "max", String.valueOf(maxMaps)));
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
                                        + wall.getType() + " not a solid full cube",
                                Map.of("x", String.valueOf(x),
                                        "y", String.valueOf(y),
                                        "z", String.valueOf(z)));
                    }
                    Block adjacent = wall.getRelative(face1);
                    FrameKind kind = kindOfFrame(wall, face1);
                    if (kind == FrameKind.NONE) {
                        // 没画框 → 检查 air（草/水会让 frame despawn）
                        if (!adjacent.getType().isAir()) {
                            return fail(FailReason.FRAME_SPACE_BLOCKED,
                                    "frame space at (" + adjacent.getX() + "," + adjacent.getY() + "," + adjacent.getZ()
                                            + ") = " + adjacent.getType() + " must be air",
                                    Map.of("x", String.valueOf(adjacent.getX()),
                                            "y", String.valueOf(adjacent.getY()),
                                            "z", String.valueOf(adjacent.getZ())));
                        }
                    } else if (kind == FrameKind.FOREIGN) {
                        return fail(FailReason.OCCUPIED,
                                "non-HikariCanvas item frame at face " + face1
                                        + " of (" + x + "," + y + "," + z + ")",
                                Map.of("x", String.valueOf(adjacent.getX()),
                                        "y", String.valueOf(adjacent.getY()),
                                        "z", String.valueOf(adjacent.getZ())));
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

    private Result.Failed fail(FailReason r, String detail, Map<String, String> params) {
        return new Result.Failed(r, detail, params);
    }

    /** 将 {@link BlockFace} 映射为 {@code direction.*} 本地化文字（如 北 / north）。 */
    public static String localizeDirection(@Nullable Messages messages, @Nullable String locale,
                                           @Nullable BlockFace face) {
        return localizeDirection(messages, locale, face == null ? null : face.name());
    }

    /** 将朝向名称字符串映射为 {@code direction.*} 本地化文字。 */
    public static String localizeDirection(@Nullable Messages messages, @Nullable String locale,
                                           @Nullable String faceName) {
        if (faceName == null || faceName.isBlank()) {
            return "?";
        }
        if (messages == null) {
            return faceName;
        }
        String key = "direction." + faceName.trim().toLowerCase(Locale.ROOT);
        String val = messages.rawOrNull(locale, key);
        if (val == null) {
            val = messages.rawOrNull(messages.defaultLocale(), key);
        }
        return val != null ? val : faceName.toLowerCase(Locale.ROOT);
    }

    /** 将选区失败 {@link Result.Failed} 渲染为 {@code wall.invalid.*} 自然语言提示。 */
    public static String localizeFailure(@Nullable Messages messages, @Nullable String locale,
                                         @Nullable Result.Failed failed) {
        if (failed == null) {
            return "";
        }
        if (messages == null) {
            return failed.detail();
        }
        String key = switch (failed.reason()) {
            case NORMAL_MISMATCH -> "wall.invalid.normal-mismatch";
            case DIFFERENT_WORLDS -> "wall.invalid.different-worlds";
            case VERTICAL_ONLY -> "wall.invalid.vertical-only";
            case NOT_COPLANAR -> "wall.invalid.not-coplanar";
            case TOO_LARGE -> "wall.invalid.too-large";
            case BLOCK_NOT_SOLID -> "wall.invalid.block-not-solid";
            case FRAME_SPACE_BLOCKED -> "wall.invalid.frame-space-blocked";
            case OCCUPIED -> "wall.invalid.occupied";
        };
        TagResolver[] resolvers = failed.params().entrySet().stream()
                .map(e -> {
                    String k = e.getKey();
                    String v = e.getValue();
                    if ("face1".equals(k) || "face2".equals(k) || "facing".equals(k)) {
                        v = localizeDirection(messages, locale, v);
                    }
                    return Placeholder.unparsed(k, v);
                })
                .toArray(TagResolver[]::new);
        return messages.plain(locale, key, resolvers);
    }
}
