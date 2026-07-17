package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * 径向渐变。
 *
 * <p><b>坐标系：</b> {@code cx}、{@code cy} 是元素 bbox 内的归一化中心位置（{@code [0, 1]}）；
 * {@code 0.5, 0.5} 即 bbox 中心。{@code r} 是渐变半径相对于 bbox 短边一半的倍数
 * （即 {@code r = 1.0} ⇒ 半径 = {@code min(w, h) / 2}），范围 {@code (0, 2]}，允许超出 bbox。</p>
 *
 * @param cx    归一化中心 x，{@code [0, 1]}
 * @param cy    归一化中心 y，{@code [0, 1]}
 * @param r     归一化半径，{@code (0, 2]}
 * @param stops 颜色停止点列表；长度 {@code [2, 8]}，{@code position} 单调非递减
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(using = JsonDeserializer.None.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RadialGradient(
        @JsonProperty("cx") double cx,
        @JsonProperty("cy") double cy,
        @JsonProperty("r") double r,
        @JsonProperty("stops") List<Stop> stops
) implements Fill {

    @Override
    public String type() {
        return "radial";
    }
}
