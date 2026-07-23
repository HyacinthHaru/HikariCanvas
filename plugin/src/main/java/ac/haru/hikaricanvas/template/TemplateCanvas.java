package ac.haru.hikaricanvas.template;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 模板 spec 的 {@code canvas} 块。pack 合成 spec 只用 {@code size="fixed"} +
 * {@code maps=[width, height]} 表达 pack 的墙尺寸（供 Gallery 显示 + 兼容判定）；
 * {@code minMaps / maxMaps / background / padding} 为兼容前端镜像保留，pack 不填。
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
