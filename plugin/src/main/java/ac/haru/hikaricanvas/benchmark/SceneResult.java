package ac.haru.hikaricanvas.benchmark;

/**
 * 单个场景的聚合结果：rasterize / palette 的 percentile + 内存 + 场景元数据。
 *
 * <p>由 {@code ResultAggregator.aggregate} 从一组 {@link RasterizeSample} 算出。rasterize 与
 * palette 分开统计（{@code §13.3} 要求两条成本不混用一个单位）。{@code pixelCount} /
 * {@code tileCount} 留作 RENDER（按像素）/ SERIALIZE（按 tile×fps）换算的横轴。</p>
 *
 * @param sceneId             场景 id
 * @param name                场景名
 * @param category            分组
 * @param dominantElementType 主元素类型
 * @param tilesWide           canvas 宽（tile）
 * @param tilesHigh           canvas 高（tile）
 * @param pixelCount          总像素数（RENDER 横轴）
 * @param tileCount           总 tile 数（SERIALIZE 横轴）
 * @param elementCount        元素实例数
 * @param sampleCount         测量样本数
 * @param rasterizeMs         rasterize 耗时分位数
 * @param paletteMs           全 tile palette 量化耗时分位数
 * @param meanAllocMbPerIter  每次迭代平均分配（MB）；{@code allocSupported=false} 时为 0
 * @param allocSupported      当前 JVM 是否支持线程分配计数（{@code ThreadMXBean}）
 */
public record SceneResult(
        String sceneId, String name, String category, String dominantElementType,
        int tilesWide, int tilesHigh, long pixelCount, int tileCount, int elementCount,
        int sampleCount,
        Percentiles rasterizeMs, Percentiles paletteMs,
        double meanAllocMbPerIter, boolean allocSupported
) {}
