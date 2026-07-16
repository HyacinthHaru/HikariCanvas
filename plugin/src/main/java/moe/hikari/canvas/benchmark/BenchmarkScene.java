package moe.hikari.canvas.benchmark;

import moe.hikari.canvas.state.ProjectState;

/**
 * 一个程序生成的 benchmark 场景：一个完整的 {@link ProjectState} + 元数据。
 *
 * <p>不可变值对象，由 {@code SceneLibrary} 用固定 seed 确定性生成（同一构建在同一进程内
 * 多次生成的同一 id 场景，其 ProjectState 结构完全相同）。benchmark 直接把
 * {@code state} 喂给 {@code CanvasCompositor.rasterize}，不经地图池 / ItemFrame /
 * WS 协议——纯函数压测（见 {@code docs/dynamic-data.md §13.3} 与
 * {@code PROPOSAL.md §5.2.7}）。</p>
 *
 * @param id                  稳定标识，如 {@code "text-saturated-2x2"}
 * @param name                人类可读名
 * @param category            分组：见 {@link #CATEGORY_ELEMENT_ISOLATION} 等常量
 * @param dominantElementType 主元素类型（{@code "text"/"rect"/.../"mixed"}），供 per-element 归因
 * @param tilesWide           canvas 宽（128px map tile 数）
 * @param tilesHigh           canvas 高（128px map tile 数）
 * @param state               待 rasterize 的 ProjectState
 */
public record BenchmarkScene(
        String id,
        String name,
        String category,
        String dominantElementType,
        int tilesWide,
        int tilesHigh,
        ProjectState state
) {
    /** 单元素饱和场景：一面墙铺满同一种元素，供干净的 per-element 成本分解。 */
    public static final String CATEGORY_ELEMENT_ISOLATION = "element-isolation";

    /** 特效场景：渐变 / dither / blend / opacity / 文字描边阴影发光。 */
    public static final String CATEGORY_EFFECT = "effect";

    /** 真实混合场景：地铁屏 / 欢迎墙 / 密集仪表盘等贴近实战的组合。 */
    public static final String CATEGORY_MIXED = "mixed";

    public BenchmarkScene {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("scene id must not be blank");
        }
        if (state == null) {
            throw new IllegalArgumentException("scene state must not be null");
        }
        if (tilesWide < 1 || tilesHigh < 1) {
            throw new IllegalArgumentException("scene must be at least 1x1 tile");
        }
    }

    /** 该场景总像素数（= rasterize 工作量主因，RENDER 换算按此线性）。 */
    public int pixelCount() {
        return tilesWide * 128 * tilesHigh * 128;
    }

    /** map tile 总数（= 序列化工作量主因，SERIALIZE 换算按 tile × fps 线性）。 */
    public int tileCount() {
        return tilesWide * tilesHigh;
    }
}
