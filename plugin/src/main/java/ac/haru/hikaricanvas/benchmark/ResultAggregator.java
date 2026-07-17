package ac.haru.hikaricanvas.benchmark;

import ac.haru.hikaricanvas.state.ProjectState;

import java.util.ArrayList;
import java.util.List;

/**
 * 聚合器：把逐次产出的原始样本（{@link RasterizeSample}）收敛成
 * {@link SceneResult} percentile 统计 + {@link PerElementCost} 边际成本。
 *
 * <p><b>纯函数 / headless</b>：不碰 Bukkit / Player / PacketEvents / 时钟，
 * 只读样本与场景元数据，无任何副作用——同一组入参多次调用结果完全相同。</p>
 *
 * <p>核心设计：rasterize 成本<b>只</b>取决于场景本身（canvas 尺寸已烘进
 * 每个场景），与 fps / 观看人数无关，故每个场景只测一次。per-element 成本 = 该元素
 * 隔离场景的 rasterize 均值，减去<b>同尺寸空白画布</b>基线后再除以元素数——把 buffer
 * 分配 / clear / palette 等固定开销扣掉，得到「多画一个该元素」的净边际成本
 * （见 {@code docs/dynamic-data.md §13.3}）。</p>
 */
public final class ResultAggregator {

    private static final double BYTES_PER_MB = 1024.0 * 1024.0;

    /**
     * 把单个场景的一组原始样本聚合为 {@link SceneResult}。
     *
     * <p>rasterize 与 palette 各自走线性插值 percentile（两条成本不混用一个单位）；
     * 内存只在<b>支持线程分配计数</b>的样本上统计——若全部样本 {@code allocatedBytes < 0}
     * （平台不支持 {@code ThreadMXBean}），则 {@code allocSupported=false} 且均值记 0。
     * 入参为空时优雅降级（{@link Percentiles#EMPTY} + {@code sampleCount=0}）。</p>
     *
     * @param scene   场景（提供元数据 + 元素数）
     * @param samples 该场景的测量样本（不含 warmup）；可空
     * @return 聚合结果，绝不为 null
     */
    public SceneResult aggregate(BenchmarkScene scene, List<RasterizeSample> samples) {
        List<RasterizeSample> safe = samples == null ? List.of() : samples;
        int sampleCount = safe.size();

        // rasterize / palette 毫秒数组（空入参 → 长度 0 → Percentiles.EMPTY）。
        double[] rasterMs = new double[sampleCount];
        double[] palMs = new double[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            RasterizeSample s = safe.get(i);
            rasterMs[i] = s.rasterizeMillis();
            palMs[i] = s.paletteMillis();
        }
        Percentiles rasterizeMs = Percentiles.of(rasterMs);
        Percentiles paletteMs = Percentiles.of(palMs);

        // 内存：仅在支持分配计数的样本上算均值（平台不支持时 allocatedBytes 为 -1）。
        long allocSum = 0;
        int allocN = 0;
        for (RasterizeSample s : safe) {
            if (s.hasAllocation()) {
                allocSum += s.allocatedBytes();
                allocN++;
            }
        }
        boolean allocSupported = allocN > 0;
        double meanAllocMbPerIter = allocSupported
                ? (allocSum / (double) allocN) / BYTES_PER_MB
                : 0.0;

        int elementCount = totalElements(scene.state());
        long pixelCount = (long) scene.pixelCount();
        int tileCount = scene.tileCount();

        return new SceneResult(
                scene.id(), scene.name(), scene.category(), scene.dominantElementType(),
                scene.tilesWide(), scene.tilesHigh(), pixelCount, tileCount, elementCount,
                sampleCount,
                rasterizeMs, paletteMs,
                meanAllocMbPerIter, allocSupported);
    }

    /**
     * 从一组<b>单元素隔离场景</b>结果推导 per-element 边际成本。
     *
     * <p>调用方只应传入 {@code element-isolation} 类的 {@link SceneResult}。每条：
     * {@code marginal = (rasterizeMs.mean() − blankBaselineMeanMs) / max(1, elementCount)}。
     * 不对负值钳零——测量噪声可能让结果轻微为负，按 {@link PerElementCost} 契约原样保留。</p>
     *
     * @param isolationResults    隔离场景聚合结果（仅 element-isolation）；可空 → 空列表
     * @param blankBaselineMeanMs 同尺寸空白画布 rasterize 均值（ms），用于扣固定开销
     * @return per-element 成本列表（顺序与入参一致），绝不为 null
     */
    public List<PerElementCost> derivePerElement(
            List<SceneResult> isolationResults, double blankBaselineMeanMs) {
        if (isolationResults == null || isolationResults.isEmpty()) {
            return List.of();
        }
        List<PerElementCost> out = new ArrayList<>(isolationResults.size());
        for (SceneResult r : isolationResults) {
            double isolationMean = r.rasterizeMs().mean();
            double marginal =
                    (isolationMean - blankBaselineMeanMs) / Math.max(1, r.elementCount());
            out.add(new PerElementCost(
                    r.sceneId(), r.dominantElementType(), r.elementCount(),
                    marginal, isolationMean));
        }
        return out;
    }

    /**
     * 跨<b>全部图层</b>统计元素数。{@link ProjectState#elements()} 只返回活动层的兼容视图——
     * 当前隔离场景都建在默认单层，二者等价；但若将来出现多层场景，必须按层求和才不漏算，
     * 否则 per-element 净成本会被高估（除数偏小）。
     */
    private static int totalElements(ProjectState state) {
        int n = 0;
        for (var layer : state.layers()) {
            n += layer.elements().size();
        }
        return n;
    }
}
