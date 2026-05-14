package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * 线性渐变。
 *
 * <p><b>角度约定：</b> {@code angle} ∈ {@code [0, 360)}，单位度数。
 * {@code 0°} 渐变方向沿 +x（从左到右），{@code 90°} 沿 +y（画布坐标系 Y 朝下，即从上到下），
 * 顺时针为正。渐变线穿过元素 bbox 中心；线段长度由角度投影到 bbox 对角线决定。</p>
 *
 * @param angle 角度，单位度数；规范化到 {@code [0, 360)}
 * @param stops 颜色停止点列表；长度 {@code [2, 8]}，{@code position} 单调非递减
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(using = JsonDeserializer.None.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LinearGradient(
        @JsonProperty("angle") double angle,
        @JsonProperty("stops") List<Stop> stops
) implements Fill {

    @Override
    public String type() {
        return "linear";
    }
}
