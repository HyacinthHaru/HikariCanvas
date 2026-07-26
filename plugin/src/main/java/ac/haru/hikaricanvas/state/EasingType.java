package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 缓动函数类型。数学权威定义见 {@code docs/rendering.md §9.3}；
 * 协议契约见 {@code docs/protocol.md §7}。
 *
 * <p>{@code EASE_IN / EASE_OUT / EASE_IN_OUT} 是 {@code CUBIC_BEZIER} 的预设控制点
 * （取 CSS 同名关键字标准值，见 rendering.md §9.3 表）。求值算法双端逐位等价，
 * 实现在 {@code KeyframeInterpolator}（Java）与 {@code interpolation.ts}（TS）。</p>
 *
 * <p>序列化采用 camelCase 字符串 —— 同 {@link BlendMode} 的 {@code @JsonProperty}
 * 显式映射范式。</p>
 *
 * <p><b>反序列化对未知值宽容</b>（{@link #fromJson}）：认不出来的 type 返 {@code null}，
 * 由 {@link Easing} 的紧凑构造器兜成 {@code LINEAR}。这是 {@link Easing} 明写的承诺
 * 「坏数据不在反序列化期抛硬错」的必要一环——靠 {@code @JsonProperty} 映射的话，
 * 未知值会直接抛 {@code InvalidFormatException} 穿透上来，整面墙加载失败而不是降级到线性。
 * 触发路径很现实：新版本存的墙回滚旧 jar 打开。放在枚举自己身上而不是靠各处 ObjectMapper
 * 配 {@code READ_UNKNOWN_ENUM_VALUES_AS_NULL}，是因为漏配一个 mapper 就前功尽弃。</p>
 *
 * @since 0.6
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

    /**
     * Jackson 反序列化入口（delegating creator）：未知 wire 值返 {@code null} 而不抛，
     * 交 {@link Easing} 紧凑构造器兜成 {@code LINEAR}。见类注释。
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    static EasingType fromJson(String s) {
        return fromWire(s);
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
