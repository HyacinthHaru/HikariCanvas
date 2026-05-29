package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 图层（M8 v2 引入）。契约见 {@code docs/architecture.md §10.5}。
 *
 * <p>每个 Layer 持有一组共享 visible / locked / opacity / blendMode 的元素；
 * 层间 z-order = {@code ProjectState.layers} 的索引（越大越上层），
 * 层内 z-order = {@link #elements} 的索引。</p>
 *
 * <p><b>可变性：</b> {@link #elements} 是 {@link ArrayList}，供 {@link ProjectState} mutator
 * 直接增删（M8-A 阶段 EditSession 继续按"扁平 elements"操作 activeLayer 的列表）。
 * 紧凑构造器会把 null / 其他子类的列表归一化为 {@code ArrayList}，避免反序列化拿到不可变视图。</p>
 *
 * @param id        {@code "l-<8hex>"}，由服务端生成
 * @param name      用户可见名（默认 {@code "Layer N"} / {@code "Default Layer"}）
 * @param visible   层可见性；false 时整层跳过渲染
 * @param locked    锁层；M8-C 起 element.* op 命中 locked 层时拒 {@code LAYER_LOCKED}
 * @param opacity   层级不透明度 0.0–1.0
 * @param blendMode 层级混合模式
 * @param colorTag  PS 风格颜色标签（仅 UI 显示用，不影响渲染）。
 *                  允许 null / Catppuccin 色名（red / peach / yellow / green / blue / mauve / overlay0）。
 *                  其他字符串校验由 {@code LayerOperations.updateLayer} 拦截。
 * @param elements  层内元素列表（z-order = index）；mutable
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Layer(
        String id,
        String name,
        boolean visible,
        boolean locked,
        float opacity,
        BlendMode blendMode,
        String colorTag,
        List<Element> elements
) {
    public Layer {
        if (elements == null) {
            elements = new ArrayList<>();
        } else if (!(elements instanceof ArrayList)) {
            elements = new ArrayList<>(elements);
        }
        if (blendMode == null) blendMode = BlendMode.NORMAL;
    }

    /**
     * 6-arg 兼容构造器：colorTag 字段引入之前的代码不传 {@code colorTag}，全代 null。
     * 让现有 {@code new Layer(...)} 调用点（LayerOperations / ProjectState / ProjectSnapshot）
     * 不必逐一改签名也能编译通过。
     */
    public Layer(String id, String name, boolean visible, boolean locked,
                 float opacity, BlendMode blendMode, List<Element> elements) {
        this(id, name, visible, locked, opacity, blendMode, null, elements);
    }

    /** Jackson 反序列化用：兼容缺字段（visible/locked/opacity/blendMode/colorTag 缺省时给默认）。 */
    @JsonCreator
    public static Layer fromJson(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("visible") Boolean visible,
            @JsonProperty("locked") Boolean locked,
            @JsonProperty("opacity") Float opacity,
            @JsonProperty("blendMode") BlendMode blendMode,
            @JsonProperty("colorTag") String colorTag,
            @JsonProperty("elements") List<Element> elements) {
        return new Layer(
                id,
                name == null ? "Layer" : name,
                visible == null || visible,
                locked != null && locked,
                opacity == null ? 1.0f : opacity,
                blendMode == null ? BlendMode.NORMAL : blendMode,
                colorTag,
                elements
        );
    }
}
