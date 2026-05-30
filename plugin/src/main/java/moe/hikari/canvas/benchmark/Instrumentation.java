package moe.hikari.canvas.benchmark;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;

/**
 * 底层 JVM 采样辅助：测量线程分配字节 + GC 快照。
 *
 * <p>纯 headless 工具类——只读 {@code java.lang.management} / {@code com.sun.management} 计数器，
 * 零 Bukkit / PacketEvents 依赖。{@code SceneTimer} 用 {@link #threadAllocatedBytes()} 算每轮
 * rasterize+palette 的分配增量；{@code GcStat} 给 P2 报告做 GC 压力归因。</p>
 *
 * <h2>为什么测分配 / GC</h2>
 * benchmark 的核心成本不只是 CPU 时间，还有 <b>分配率</b>：{@code CanvasCompositor.rasterize}
 * 每次 new 一个 {@code widthMaps*128 × heightMaps*128} 的 ARGB {@code BufferedImage}（一面 2×2
 * 墙 = 256×256×4 = 256 KB/帧），高 fps 下持续分配会触发频繁 minor GC。光看 wall-clock 时间
 * 看不出这层成本（GC 暂停可能落在别的迭代里），所以单独采分配字节 + GC 计数，让报告能区分
 * 「算得慢」与「GC 压力大」（见 {@code docs/dynamic-data.md §13.3} 决策 ③：数据透明，不替服主决策）。
 *
 * <h2>平台兼容</h2>
 * {@code getCurrentThreadAllocatedBytes()} 是 HotSpot/OpenJ9 私有 API（{@code com.sun.management}），
 * 绝大多数 JVM 支持但非 JLS 强制。任何不支持 / 抛异常的路径统一降级返回 {@code -1L}，调用方
 * 据此判定「该平台无分配数据」（{@code RasterizeSample.hasAllocation()}），不崩溃。
 */
public final class Instrumentation {

    /**
     * 缓存的 {@code com.sun.management.ThreadMXBean}（仅当平台支持线程分配计数器时非 null）。
     * 缓存避免每轮迭代都做 {@code ManagementFactory.getThreadMXBean()} + instanceof 判定。
     */
    private static final com.sun.management.ThreadMXBean SUN_THREAD_BEAN;

    /** 平台是否支持当前线程分配字节计数；false 时 {@link #threadAllocatedBytes()} 恒返 -1。 */
    private static final boolean ALLOCATION_SUPPORTED;

    static {
        com.sun.management.ThreadMXBean bean = null;
        boolean supported = false;
        try {
            ThreadMXBean base = ManagementFactory.getThreadMXBean();
            if (base instanceof com.sun.management.ThreadMXBean sun) {
                // 计数器默认开启，但显式判定 + 尝试开启，规避某些 JVM 默认关闭的情形。
                if (sun.isThreadAllocatedMemorySupported()) {
                    if (!sun.isThreadAllocatedMemoryEnabled()) {
                        sun.setThreadAllocatedMemoryEnabled(true);
                    }
                    bean = sun;
                    supported = sun.isThreadAllocatedMemoryEnabled();
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // ClassCastException / UnsupportedOperationException / NoClassDefFoundError 等一律降级。
            bean = null;
            supported = false;
        }
        SUN_THREAD_BEAN = bean;
        ALLOCATION_SUPPORTED = supported;
    }

    private Instrumentation() {
        // 工具类，不实例化。
    }

    /**
     * 读取<b>当前线程</b>累计分配字节（单调递增计数器）。
     *
     * <p>两次调用相减得到区间内的分配增量。仅当 {@code SceneTimer} 在测量线程上同步调用
     * rasterize+palette 时这个差值才有意义（CanvasCompositor 无后台线程，分配全发生在调用线程）。</p>
     *
     * @return 当前线程累计分配字节；平台不支持或采样失败时返回 {@code -1L}
     */
    public static long threadAllocatedBytes() {
        if (!ALLOCATION_SUPPORTED || SUN_THREAD_BEAN == null) {
            return -1L;
        }
        try {
            return SUN_THREAD_BEAN.getCurrentThreadAllocatedBytes();
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    /**
     * 跨所有 GC 收集器汇总一次快照（次数 + 累计暂停毫秒）。
     *
     * <p>在测量段前后各取一次、{@code minus} 求差，得到该段内触发的 GC 次数与暂停时间。
     * 返回 {@code -1} 的 bean（某些 JVM 对部分收集器不统计）跳过。</p>
     *
     * @return 全收集器汇总后的 {@link GcStat}
     */
    public static GcStat gcSnapshot() {
        long count = 0L;
        long timeMs = 0L;
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean bean : beans) {
            long c = bean.getCollectionCount();
            long t = bean.getCollectionTime();
            if (c >= 0) {
                count += c;
            }
            if (t >= 0) {
                timeMs += t;
            }
        }
        return new GcStat(count, timeMs);
    }

    /**
     * GC 快照值对象：累计收集次数 + 累计暂停毫秒。
     *
     * @param collectionCount  累计 GC 收集次数
     * @param collectionTimeMs 累计 GC 暂停毫秒
     */
    public record GcStat(long collectionCount, long collectionTimeMs) {
        /**
         * 求本快照相对 {@code earlier} 的增量（本段内发生的 GC 次数 / 暂停时间）。
         *
         * @param earlier 较早的快照
         * @return 增量 {@link GcStat}
         */
        public GcStat minus(GcStat earlier) {
            return new GcStat(
                    collectionCount - earlier.collectionCount(),
                    collectionTimeMs - earlier.collectionTimeMs());
        }
    }
}
