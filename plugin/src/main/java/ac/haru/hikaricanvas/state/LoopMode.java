package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 时间轴播放模式。契约见 {@code docs/timeline.md §2.1} 与 {@code docs/protocol.md §7}。
 *
 * <p>序列化采用 camelCase 字符串（{@code "once"} / {@code "loop"} / {@code "pingPong"}）以贴合
 * 协议契约 —— 同 {@link BlendMode} 的 {@code @JsonProperty} 显式映射范式，非 Java enum name 直出。</p>
 *
 * @since 0.6
 */
public enum LoopMode {
    @JsonProperty("once") ONCE("once"),
    @JsonProperty("loop") LOOP("loop"),
    @JsonProperty("pingPong") PING_PONG("pingPong");

    private final String wire;

    LoopMode(String wire) {
        this.wire = wire;
    }

    /** 协议 wire 形态字符串。 */
    public String wire() {
        return wire;
    }

    /** 从 wire 字符串解析（WS payload 手动解析用）；未知 / null 返回 {@code null}。 */
    public static LoopMode fromWire(String s) {
        if (s == null) return null;
        for (LoopMode m : values()) {
            if (m.wire.equals(s)) return m;
        }
        return null;
    }
}
