package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 缓动函数类型（0.6 引入）。数学权威定义见 {@code docs/rendering.md §9.3}；
 * 协议契约见 {@code docs/protocol.md §7}。
 *
 * <p>{@code EASE_IN / EASE_OUT / EASE_IN_OUT} 是 {@code CUBIC_BEZIER} 的预设控制点
 * （取 CSS 同名关键字标准值，见 rendering.md §9.3 表）。求值算法双端逐位等价，
 * P3 落地 {@code KeyframeInterpolator}（Java）与 {@code interpolation.ts}（TS）；
 * P1 仅做数据模型与校验。</p>
 *
 * <p>序列化采用 camelCase 字符串 —— 同 {@link BlendMode} 的 {@code @JsonProperty}
 * 显式映射范式。</p>
 */
public enum EasingType {
    @JsonProperty("linear") LINEAR("linear"),
    @JsonProperty("easeIn") EASE_IN("easeIn"),
    @JsonProperty("easeOut") EASE_OUT("easeOut"),
    @JsonProperty("easeInOut") EASE_IN_OUT("easeInOut"),
    @JsonProperty("cubicBezier") CUBIC_BEZIER("cubicBezier");

    private final String wire;

    EasingType(String wire) {
        this.wire = wire;
    }

    /** 协议 wire 形态字符串。 */
    public String wire() {
        return wire;
    }

    /** 从 wire 字符串解析（WS payload 手动解析用）；未知 / null 返回 {@code null}。 */
    public static EasingType fromWire(String s) {
        if (s == null) return null;
        for (EasingType t : values()) {
            if (t.wire.equals(s)) return t;
        }
        return null;
    }
}
