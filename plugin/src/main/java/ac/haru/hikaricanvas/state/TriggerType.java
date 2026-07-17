package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 时间轴触发方式（0.6 引入）。契约见 {@code docs/timeline.md §5} 与 {@code docs/protocol.md §7}。
 *
 * <p>0.6 范围 = {@code MANUAL / VARIABLE_CHANGE / SCHEDULE} 三种（D5）；{@code PLAYER_NEAR}
 * 推迟 0.7（需从零建事件层，与 0.7 Scratch 触发系统合并实施）—— 届时按 enum 加法扩展，
 * wire 形态向后兼容。</p>
 *
 * <p>P1 仅存储触发配置；触发行为（listener 注册 / 去抖）P5 落地。序列化采用 camelCase
 * 字符串 —— 同 {@link BlendMode} 的 {@code @JsonProperty} 显式映射范式。</p>
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
