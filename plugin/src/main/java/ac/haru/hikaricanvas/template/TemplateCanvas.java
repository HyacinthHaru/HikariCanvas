package ac.haru.hikaricanvas.template;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 模板顶层 {@code canvas:} 块。契约见 {@code docs/template-spec.md §3}。
 *
 * <p><b>字段惯例：</b></p>
 * <ul>
 *   <li>{@code size} = {@code "auto"} | {@code "fixed"}（默认 {@code "auto"}）</li>
 *   <li>{@code maps / minMaps / maxMaps} = {@code [width, height]}</li>
 *   <li>{@code background} 支持 {@code ${param}}（实例化时插值）</li>
 *   <li>{@code padding} 可为单一 int 或 [上, 右, 下, 左]；本 record 持有原始
 *       {@link Object}，由 {@link TemplateLoader} 归一化到 {@code int[4]} 后用</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TemplateCanvas(
        String size,
        List<Integer> maps,
        @JsonProperty("min_maps") List<Integer> minMaps,
        @JsonProperty("max_maps") List<Integer> maxMaps,
        String background,
        Object padding
) {
    @JsonCreator
    public TemplateCanvas {
        // record 反序列化入口；默认值在 Loader 阶段补
    }
}
