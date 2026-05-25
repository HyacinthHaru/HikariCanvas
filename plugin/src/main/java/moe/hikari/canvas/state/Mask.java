package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * M13 {@link ImageElement} 的可选蒙版。{@code d} 为 SVG path 字符串（M/L/Q/C/Z 子集，
 * 相对 element bbox {@code (0, 0)..(w, h)}），复用 {@link PathDValidator} 校验
 * （含 4096 字符长度上限）；{@code inverted=false} 时显示 mask 内部像素（默认），
 * {@code true} 时显示 mask 外部（用 element bbox 减去 mask 形状）。
 *
 * <p>契约见 {@code docs/protocol.md §7} ImageElement 段、{@code docs/security.md §4.5 (i)}、
 * {@code docs/rendering.md §4.4}。</p>
 *
 * <p>v1 RightPanel dropdown 暴露 {@code none / circle / roundedRect / ellipse} 4 预设；
 * 2026-05-25 起新增 lasso 自由路径（Alt + 拖动 image element 在画布上直接画）；
 * 数据模型仍是 SVG path d 字符串，向下兼容。</p>
 *
 * <p>2026-05-25 引入 {@link #featherPx}：蒙版边缘羽化（高斯模糊蒙版 alpha 通道，让裁切
 * 边变成平滑过渡而不是硬边）。{@code null / 0} = 无羽化（向下兼容）；{@code 1..32} =
 * 边缘渐变像素数。详见 {@code docs/rendering.md §4.4}。</p>
 *
 * @param d          SVG path d 字符串（M/L/Q/C/Z 子集，相对 element bbox）；4096 字符长度上限
 * @param inverted   {@code true} = 显示蒙版外像素
 * @param featherPx  羽化半径（像素）。{@code null} 或 ≤ 0 = 无羽化；范围 [1, 32]
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Mask(String d, boolean inverted, Integer featherPx) {

    /** 双参构造器：兼容 0.4.6 之前的代码 / 测试。 */
    public Mask(String d, boolean inverted) {
        this(d, inverted, null);
    }

    /**
     * Jackson 反序列化入口。{@code featherPx} 缺失时 null；旧 .canvas 文件 / 旧前端发
     * 来的 patch 不带此字段自动走 null path，行为与 0.4.6 之前完全一致。
     */
    @JsonCreator
    public static Mask of(
            @JsonProperty("d") String d,
            @JsonProperty("inverted") boolean inverted,
            @JsonProperty("featherPx") Integer featherPx) {
        return new Mask(d, inverted, featherPx);
    }

    /** 是否启用羽化（featherPx 非 null 且 > 0）。 */
    public boolean hasFeather() {
        return featherPx != null && featherPx > 0;
    }

    /** 羽化半径（保证返非负 int；null / 负值返 0）。 */
    public int featherPxOrZero() {
        return (featherPx == null || featherPx < 0) ? 0 : Math.min(featherPx, 32);
    }
}
