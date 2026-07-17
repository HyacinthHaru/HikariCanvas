package ac.haru.hikaricanvas.benchmark;

/**
 * 单个「单元素饱和场景」推导出的某种元素类型的<b>边际</b>渲染成本。
 *
 * <p>{@code marginalMsPerElement = (isolationSceneMeanMs − blankBaselineMeanMs) / elementCount}：
 * 用同尺寸空白画布的渲染均值扣掉 buffer 分配 / clear / palette 等固定开销，再除以元素数，得到
 * 「多画一个该元素」的净成本。这是 per-element 分解的核心产物——服主据此判断哪类元素贵
 * （如一面贴满蒙版图片的墙 vs 纯文字墙成本天差地别）。</p>
 *
 * <p>注意 text 类型有两个隔离场景（{@code text-saturated} 普通文字、{@code variable-text} 含
 * {@code ${var:...}} 占位符），故按 sceneId 区分而非按 type 去重——让占位符解析的额外成本可见。</p>
 *
 * @param sceneId             来源隔离场景 id
 * @param elementType         元素类型（dominantElementType）
 * @param elementCount        该场景元素实例数
 * @param marginalMsPerElement 净边际成本（ms/元素）；可能因测量噪声为轻微负值，原样保留不钳零
 * @param isolationSceneMeanMs 该隔离场景 rasterize 均值（ms，未扣基线）
 */
public record PerElementCost(
        String sceneId, String elementType, int elementCount,
        double marginalMsPerElement, double isolationSceneMeanMs
) {}
