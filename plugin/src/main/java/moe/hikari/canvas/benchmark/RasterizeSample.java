package moe.hikari.canvas.benchmark;

/**
 * 单次测量迭代的原始样本。
 *
 * <p>rasterize 与 palette 量化分开计时（{@code docs/dynamic-data.md §13.3} 要求两条成本
 * 不能混用一个单位）；{@code allocatedBytes} 是本次 rasterize+palette 在测量线程上分配的
 * 字节增量（{@code com.sun.management.ThreadMXBean.getThreadAllocatedBytes}），衡量 GC
 * 压力——{@code BufferedImage} 分配是大头。</p>
 *
 * <p>这里只承载原始样本；p50/p95/p99 percentile + per-element 归因由
 * {@code ResultAggregator} 计算。</p>
 *
 * @param sceneId        场景标识
 * @param iteration      测量迭代序号（0-based，不含 warmup）
 * @param rasterizeNanos {@code rasterize()} 耗时（ns）
 * @param paletteNanos   全 tile {@code toPaletteSlice()} 总耗时（ns）
 * @param allocatedBytes 本次在测量线程分配的字节（{@code -1} = 平台不支持该计数器）
 */
public record RasterizeSample(
        String sceneId,
        int iteration,
        long rasterizeNanos,
        long paletteNanos,
        long allocatedBytes
) {
    public double rasterizeMillis() {
        return rasterizeNanos / 1_000_000.0;
    }

    public double paletteMillis() {
        return paletteNanos / 1_000_000.0;
    }

    /** rasterize + palette 合计毫秒。 */
    public double totalMillis() {
        return (rasterizeNanos + paletteNanos) / 1_000_000.0;
    }

    public boolean hasAllocation() {
        return allocatedBytes >= 0;
    }
}
