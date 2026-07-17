package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 时间轴（0.6 引入）。契约见 {@code docs/timeline.md §2.1} 与 {@code docs/protocol.md §7}；
 * 持久化形态见 {@code docs/data-model.md §2.4.2}（序列化进 project_json blob，无独立表）。
 *
 * <p>{@code tracks} 的 key 是 elementId，值是该元素<b>所有属性混排</b>、按 {@code timeMs}
 * 升序的关键帧列表（方案 B：关键帧不进 Element record，{@code Element} 8 个子类零改动）。</p>
 *
 * <p><b>不可变性：</b> canonical 构造器深冻结 {@code tracks}（LinkedHashMap 保序 +
 * 每轨 {@link List#copyOf}）。mutator 路径（{@code TimelineOperations}）整体重建 record ——
 * 与 Element 的「重建 record 再置换」范式一致；{@code ProjectSnapshot} 因此可以按引用共享
 * Timeline 实例，undo/redo 不互相污染。</p>
 *
 * <p>{@code fps} 受 config {@code timeline.max-fps} 钳（op 层 clamp，默 60；默认值 20，
 * 见 architecture.md §5.5）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Timeline(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("durationMs") int durationMs,
        @JsonProperty("fps") int fps,
        @JsonProperty("loopMode") LoopMode loopMode,
        @JsonProperty("trigger") TriggerConfig trigger,
        @JsonProperty("tracks") Map<String, List<Keyframe>> tracks
) {

    public Timeline {
        if (loopMode == null) loopMode = LoopMode.LOOP;
        if (trigger == null) trigger = TriggerConfig.MANUAL;
        if (tracks == null || tracks.isEmpty()) {
            tracks = Map.of();
        } else {
            LinkedHashMap<String, List<Keyframe>> frozen = new LinkedHashMap<>(tracks.size());
            for (Map.Entry<String, List<Keyframe>> e : tracks.entrySet()) {
                // 宽容路径：持久化 blob 里轨道列表含 null 元素时不抛硬错
                // （List.copyOf 对 null 元素会 NPE → 整面墙加载失败，违反「坏数据不在反序列化期
                // 抛硬错」承诺；参照 Easing.java 的 null 过滤范式）。null 关键帧被静默过滤；
                // WS 路径的输入校验仍在 op 层（TimelineOperations）拒绝非法关键帧。
                List<Keyframe> raw = e.getValue();
                frozen.put(e.getKey(),
                        raw == null ? List.of()
                                    : raw.stream()
                                         .filter(java.util.Objects::nonNull)
                                         .toList());
            }
            tracks = Collections.unmodifiableMap(frozen);
        }
    }

    /** 以新 tracks 重建（keyframe mutator 用；其余字段不变）。 */
    public Timeline withTracks(Map<String, List<Keyframe>> newTracks) {
        return new Timeline(id, name, durationMs, fps, loopMode, trigger, newTracks);
    }

    /** 全部轨道的关键帧总数（配额校验用）。 */
    public int totalKeyframes() {
        int n = 0;
        for (List<Keyframe> track : tracks.values()) n += track.size();
        return n;
    }
}
