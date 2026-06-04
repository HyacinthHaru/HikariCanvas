package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 时间轴触发配置（0.6 引入）。契约见 {@code docs/timeline.md §2.5 / §5} 与
 * {@code docs/protocol.md §7}。
 *
 * <p>{@code params} 按触发类型携带参数（如 {@code VARIABLE_CHANGE} 的 {@code fullName}）。
 * P1 仅存储；{@code VARIABLE_CHANGE} 的 {@code fullName} 须经
 * {@code VariableInterpolator.resolveFullName} 注入 wallId 才能匹配内部形式 —— 该注入发生在
 * P5 listener 注册时，存储侧保留用户原始输入（timeline.md §5.2 R）。</p>
 *
 * <p>记录不可变：{@code params} 在 canonical 构造器里防御拷贝。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TriggerConfig(
        @JsonProperty("type") TriggerType type,
        @JsonProperty("params") Map<String, String> params
) {

    /** 缺省触发：手动播放、无参数。 */
    public static final TriggerConfig MANUAL = new TriggerConfig(TriggerType.MANUAL, Map.of());

    public TriggerConfig {
        if (type == null) type = TriggerType.MANUAL;
        if (params == null || params.isEmpty()) {
            params = Map.of();
        } else {
            // 宽容路径：持久化 blob 里 params 含 null 键/值时不抛硬错（Map.copyOf 会 NPE
            // 致整面墙加载失败；审查确认项 #5）。静默滤掉 null 项；WS 路径的校验在 op 层。
            LinkedHashMap<String, String> clean = new LinkedHashMap<>(params.size());
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    clean.put(e.getKey(), e.getValue());
                }
            }
            params = Collections.unmodifiableMap(clean);
        }
    }
}
