package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * 关键帧。契约见 {@code docs/timeline.md §2.3} 与 {@code docs/protocol.md §7}。
 *
 * <p>同一元素的不同 property 关键帧混在该元素的轨道列表里（按 {@code timeMs} 升序），
 * 前端按 {@code (elementId, property)} 分组渲染成多条属性子轨（timeline.md §2.2）。
 * {@code easing} 是到<b>下一个</b>关键帧的缓动 —— 末帧的 easing 无意义（rendering.md §9.1）。</p>
 *
 * <p>插值类别（rendering.md §9.2）：数值类（x/y/w/h/rotation/opacity）线性 + easing；
 * 颜色 / Fill 类 sRGB 线性空间分量插值；离散类（text）step 取最近帧。</p>
 *
 * @since 0.6
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Keyframe(
        @JsonProperty("id") String id,
        @JsonProperty("property") String property,
        @JsonProperty("timeMs") int timeMs,
        @JsonProperty("value") KfValue value,
        @JsonProperty("easing") Easing easing
) {

    /** 可加关键帧的属性白名单（rendering.md §9.2）。 */
    public static final Set<String> PROPERTIES = Set.of(
            "x", "y", "w", "h", "rotation", "opacity", "color", "fill", "text");

    /** 数值类属性（线性 + easing 插值）。 */
    public static final Set<String> NUMERIC_PROPERTIES = Set.of(
            "x", "y", "w", "h", "rotation", "opacity");

    public Keyframe {
        if (easing == null) easing = Easing.LINEAR;
    }
}
