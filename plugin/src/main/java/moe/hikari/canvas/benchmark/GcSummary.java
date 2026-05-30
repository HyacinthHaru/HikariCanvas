package moe.hikari.canvas.benchmark;

/**
 * 一次 benchmark run 期间的 GC 增量——由 {@link Instrumentation.GcStat} 在 run 前后各采一次相减。
 *
 * <p>衡量整段压测对 GC 的压力（{@code BufferedImage} 反复分配是大头）。这是「服务端可控成本」
 * 的一部分，与网络无关。</p>
 *
 * <p><b>范围注意</b>：覆盖整段 run（空白基线 + 全场景的 warmup + measure 全部迭代）。warmup
 * 迭代虽<b>不</b>计入 per-scene rasterize/palette percentile，其分配<b>仍</b>计入这里的 GC——
 * 故本字段不等于各场景 {@code meanAllocMbPerIter} 之和，勿据此反推。</p>
 *
 * @param collectionCount  run 期间 GC 次数增量
 * @param collectionTimeMs run 期间 GC 累计耗时增量（ms）
 */
public record GcSummary(long collectionCount, long collectionTimeMs) {
    /** 由 run 前后两次快照相减。 */
    public static GcSummary between(Instrumentation.GcStat before, Instrumentation.GcStat after) {
        Instrumentation.GcStat d = after.minus(before);
        return new GcSummary(d.collectionCount(), d.collectionTimeMs());
    }
}
