package ac.haru.hikaricanvas.deploy;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@link WallResolver} 的尺寸上限判定。
 *
 * <p>守的是"面积用 long 算"这一条：两次点击的坐标差可以到几千万（/tp 过去点一下，
 * 再 /tp 回来点一下），{@code width * height} 在 int 里会溢出成 0 或负数，
 * 直接从 {@code TOO_LARGE} 底下钻过去，让后面那圈逐格 bbox 扫描拿着天文数字的范围开跑。</p>
 *
 * <p>测试用 JDK Proxy 假 {@link Block} / {@link World}：上限判定在碰任何方块之前就该返回，
 * 所以假 World 对 {@code getBlockAt} 之类一律抛异常 —— 一旦哪天判定又漏了，测试会以
 * "居然开始扫方块了"的形式炸出来，而不是悄悄变绿。</p>
 */
class WallResolverSizeLimitTest {

    private static final NamespacedKey WALL_ID_KEY = NamespacedKey.minecraft("wall_id");

    /** 只回答坐标和世界；其余方法一律抛，确保判定路径没有多余的世界访问。 */
    private static Block fakeBlock(World world, int x, int y, int z) {
        return (Block) Proxy.newProxyInstance(
                WallResolverSizeLimitTest.class.getClassLoader(),
                new Class[]{Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "getX" -> x;
                    case "getY" -> y;
                    case "getZ" -> z;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    case "toString" -> "FakeBlock[" + x + "," + y + "," + z + "]";
                    default -> throw new UnsupportedOperationException(
                            "size check must not touch Block." + method.getName());
                });
    }

    private static World fakeWorld() {
        return (World) Proxy.newProxyInstance(
                WallResolverSizeLimitTest.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "world_a";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    case "toString" -> "FakeWorld";
                    default -> throw new UnsupportedOperationException(
                            "size check must not touch World." + method.getName());
                });
    }

    /**
     * 65536 × 65536 在 int 里正好溢出成 0 —— 老实现的 {@code 0 > maxMaps} 判定为假，
     * 于是这片"面积 42.9 亿格"的选区被当成合法尺寸放行。
     */
    @Test
    void hugeSelectionWhoseAreaOverflowsInt_isStillRejected() {
        World world = fakeWorld();
        // EAST 朝向：X 必须相同，墙沿 Z 展开
        Block b1 = fakeBlock(world, 0, 0, 0);
        Block b2 = fakeBlock(world, 0, 65_535, 65_535);

        WallResolver resolver = new WallResolver(16, WALL_ID_KEY);
        WallResolver.Result r = resolver.resolve(b1, BlockFace.EAST, b2, BlockFace.EAST);

        WallResolver.Result.Failed failed =
                assertInstanceOf(WallResolver.Result.Failed.class, r,
                        "面积溢出 int 不能变成一个合法选区");
        assertEquals(WallResolver.FailReason.TOO_LARGE, failed.reason());
        assertEquals("65536x65536=4294967296 exceeds limit 16", failed.detail(),
                "面积要按 long 报，不能报溢出后的数");
    }

    /** 对照组：不涉及溢出的普通超限选区照常拒绝，判定本身没被改坏。 */
    @Test
    void ordinaryOversizedSelection_isRejected() {
        World world = fakeWorld();
        Block b1 = fakeBlock(world, 0, 0, 0);
        Block b2 = fakeBlock(world, 0, 4, 4);   // 5×5 = 25 > 16

        WallResolver resolver = new WallResolver(16, WALL_ID_KEY);
        WallResolver.Result r = resolver.resolve(b1, BlockFace.WEST, b2, BlockFace.WEST);

        WallResolver.Result.Failed failed =
                assertInstanceOf(WallResolver.Result.Failed.class, r);
        assertEquals(WallResolver.FailReason.TOO_LARGE, failed.reason());
        assertEquals("5x5=25 exceeds limit 16", failed.detail());
    }

    /** 两角朝向不一致必须在任何几何计算之前就被拒（NORMAL_MISMATCH 不是死代码）。 */
    @Test
    void mismatchedFaces_areRejected() {
        World world = fakeWorld();
        Block b1 = fakeBlock(world, 0, 0, 0);
        Block b2 = fakeBlock(world, 0, 1, 1);

        WallResolver resolver = new WallResolver(16, WALL_ID_KEY);
        WallResolver.Result r = resolver.resolve(b1, BlockFace.NORTH, b2, BlockFace.EAST);

        WallResolver.Result.Failed failed =
                assertInstanceOf(WallResolver.Result.Failed.class, r);
        assertEquals(WallResolver.FailReason.NORMAL_MISMATCH, failed.reason());
    }
}
