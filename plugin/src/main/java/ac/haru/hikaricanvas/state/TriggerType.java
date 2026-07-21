package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 时间轴触发方式。契约见 {@code docs/timeline.md §5} 与 {@code docs/protocol.md §7}。
 *
 * <p>只覆盖时间轴自身的三种触发；玩家靠近这类游戏事件触发走脚本系统的触发器。
 * 扩展时按 enum 加法追加，wire 形态向后兼容。</p>
 *
 * <p>序列化采用 camelCase 字符串 —— 同 {@link BlendMode} 的 {@code @JsonProperty}
 * 显式映射范式。</p>
 *
 * @since 0.6
 */
public enum TriggerType {
    @JsonProperty("manual") MANUAL("manual"),
    @JsonProperty("variableChange") VARIABLE_CHANGE("variableChange"),
    @JsonProperty("schedule") SCHEDULE("schedule");

    private final String wire;

    TriggerType(String wire) {
        this.wire = wire;
    }

    /** 协议 wire 形态字符串。 */
    public String wire() {
        return wire;
    }

    /** 从 wire 字符串解析（WS payload 手动解析用）；未知 / null 返回 {@code null}。 */
    public static TriggerType fromWire(String s) {
        if (s == null) return null;
        for (TriggerType t : values()) {
            if (t.wire.equals(s)) return t;
        }
        return null;
    }
}
