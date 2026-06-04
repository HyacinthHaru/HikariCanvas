package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 关键帧缓动（0.6 引入）。数学权威定义见 {@code docs/rendering.md §9.3}；
 * 协议契约见 {@code docs/protocol.md §7}。
 *
 * <p>{@code bezier} 仅 {@link EasingType#CUBIC_BEZIER} 使用：{@code [x1, y1, x2, y2]} 四元，
 * 约束 {@code x1, x2 ∈ [0, 1]}（保证 Bx 在 [0,1] 单调，与 CSS 一致；y 不限）。语义校验在
 * op 层（{@code TimelineOperations}）做 —— record 本身对持久化 blob 保持宽容，坏数据不在
 * 反序列化期抛硬错。</p>
 *
 * <p>非 {@code CUBIC_BEZIER} 类型 {@code bezier} 应为 {@code null}（序列化时省略，
 * {@code LINEAR} 缓动 wire 形态为 {@code {"type":"linear"}}）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Easing(
        @JsonProperty("type") EasingType type,
        @JsonProperty("bezier") List<Double> bezier
) {

    /** 缺省缓动：线性。 */
    public static final Easing LINEAR = new Easing(EasingType.LINEAR, null);

    public Easing {
        if (type == null) type = EasingType.LINEAR;
        if (bezier != null) {
            // 宽容路径：持久化 blob 里 bezier 含 null 元素时不抛硬错（List.copyOf 会 NPE
            // 致整面墙加载失败，违反本类「坏数据不在反序列化期抛硬错」的承诺；审查确认项 #5）。
            // 退 null —— 渲染期按无控制点处理；WS 路径的语义校验仍在 op 层拒 INVALID_EASING。
            boolean hasNull = false;
            for (Double d : bezier) {
                if (d == null) { hasNull = true; break; }
            }
            bezier = hasNull ? null : List.copyOf(bezier);
        }
    }
}
