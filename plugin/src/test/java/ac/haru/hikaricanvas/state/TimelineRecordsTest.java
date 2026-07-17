package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.6 P1：时间轴 record 族的纯 Jackson 序列化契约单测。契约见 {@code docs/timeline.md §2}
 * 与 {@code docs/protocol.md §7}。
 *
 * <p>不接数据库 / Bukkit，用裸 {@link ObjectMapper}（{@link Timeline} / {@link Keyframe} /
 * {@link KfValue} / {@link Easing} 等记录的注解自带 wire 形态 + 多态 union 分流），
 * 覆盖以下不变量：</p>
 * <ul>
 *   <li>{@link Timeline} 全量 round-trip（record 语义 equals）</li>
 *   <li>enum wire 形态走 camelCase（{@code pingPong} / {@code easeInOut} / {@code variableChange}）</li>
 *   <li>{@link KfValue} 三态分流（number → Num、string → Str、object → FillV）；boolean/数组等非预期形态宽容退 null</li>
 *   <li>{@link Easing#LINEAR} 省略 {@code bezier} 键；空 tracks 序列化为 {@code {}}</li>
 *   <li>{@link ProjectState} v2 旧 blob 兼容（无 timelines 字段）+ 静态工程序列化省略</li>
 * </ul>
 */
class TimelineRecordsTest {

    /** 裸 mapper —— 时间轴 record 的注解（@JsonSerialize/@JsonDeserialize/@JsonProperty）自带一切。 */
    private static final ObjectMapper M = new ObjectMapper();

    private static Keyframe kf(String id, String property, int timeMs, KfValue value, Easing easing) {
        return new Keyframe(id, property, timeMs, value, easing);
    }

    // ---------- Timeline 全量 round-trip ----------

    @Test
    void timelineFullRoundTrip() throws Exception {
        // 2 元素轨道、KfValue 三态混排、easing linear + cubicBezier
        Easing bezier = new Easing(EasingType.CUBIC_BEZIER, List.of(0.42, 0.0, 1.0, 1.0));
        Timeline original = new Timeline(
                "tl-1", "Welcome", 4000, 20,
                LoopMode.PING_PONG,
                new TriggerConfig(TriggerType.VARIABLE_CHANGE, Map.of("fullName", "user/x")),
                Map.of(
                        "e-1", List.of(
                                kf("k-1", "opacity", 0, KfValue.of(0.0), Easing.LINEAR),
                                kf("k-2", "opacity", 2000, KfValue.of(1.0), bezier)),
                        "e-2", List.of(
                                kf("k-3", "text", 0, KfValue.of("${var:x}"), Easing.LINEAR),
                                kf("k-4", "fill", 1000, KfValue.of(new SolidFill("#FF0000")), Easing.LINEAR))));

        String json = M.writeValueAsString(original);
        Timeline back = M.readValue(json, Timeline.class);

        assertEquals(original, back, "Timeline record 语义相等应 round-trip 还原");
    }

    @Test
    void kfValueThreeStatesSurviveRoundTrip() throws Exception {
        // Num / Str("${var:x}") / FillV(SolidFill) 三态分别 round-trip 保持具体子类型
        Timeline original = new Timeline(
                "tl-2", "vals", 1000, 20, LoopMode.LOOP, TriggerConfig.MANUAL,
                Map.of("e-1", List.of(
                        kf("k-1", "x", 0, KfValue.of(3.0), Easing.LINEAR),
                        kf("k-2", "text", 100, KfValue.of("${var:x}"), Easing.LINEAR),
                        kf("k-3", "fill", 200, KfValue.of(new SolidFill("#00FF00")), Easing.LINEAR))));
        Timeline back = M.readValue(M.writeValueAsString(original), Timeline.class);

        List<Keyframe> track = back.tracks().get("e-1");
        assertInstanceOf(KfValue.Num.class, track.get(0).value());
        assertInstanceOf(KfValue.Str.class, track.get(1).value());
        assertInstanceOf(KfValue.FillV.class, track.get(2).value());
        assertEquals("${var:x}", ((KfValue.Str) track.get(1).value()).value());
    }

    // ---------- enum wire 形态 ----------

    @Test
    void enumsSerializeToCamelCaseWire() throws Exception {
        Timeline tl = new Timeline(
                "tl-3", "wire", 1000, 20,
                LoopMode.PING_PONG,
                new TriggerConfig(TriggerType.VARIABLE_CHANGE, Map.of("fullName", "user/x")),
                Map.of("e-1", List.of(
                        kf("k-1", "x", 0, KfValue.of(1.0),
                                new Easing(EasingType.EASE_IN_OUT, null)))));
        String json = M.writeValueAsString(tl);

        // wire 形态而非 Java enum name（PING_PONG / VARIABLE_CHANGE / EASE_IN_OUT）
        assertTrue(json.contains("\"pingPong\""), "LoopMode 应输出 camelCase wire");
        assertTrue(json.contains("\"variableChange\""), "TriggerType 应输出 camelCase wire");
        assertTrue(json.contains("\"easeInOut\""), "EasingType 应输出 camelCase wire");
        assertFalse(json.contains("PING_PONG"));
        assertFalse(json.contains("VARIABLE_CHANGE"));
        assertFalse(json.contains("EASE_IN_OUT"));
    }

    @Test
    void enumWireDeserializesBackToEnum() throws Exception {
        Timeline tl = new Timeline(
                "tl-4", "wire", 1000, 20,
                LoopMode.PING_PONG,
                new TriggerConfig(TriggerType.VARIABLE_CHANGE, Map.of()),
                Map.of("e-1", List.of(
                        kf("k-1", "x", 0, KfValue.of(1.0),
                                new Easing(EasingType.EASE_IN_OUT, null)))));
        Timeline back = M.readValue(M.writeValueAsString(tl), Timeline.class);

        assertEquals(LoopMode.PING_PONG, back.loopMode());
        assertEquals(TriggerType.VARIABLE_CHANGE, back.trigger().type());
        assertEquals(EasingType.EASE_IN_OUT, back.tracks().get("e-1").get(0).easing().type());
    }

    // ---------- KfValue 单测（三态分流 + 数组拒绝）----------

    @Test
    void kfValueNumberDeserializesToNum() throws Exception {
        KfValue v = M.readValue("3", KfValue.class);
        assertInstanceOf(KfValue.Num.class, v);
        assertEquals(3.0, ((KfValue.Num) v).value(), 0.0);
    }

    @Test
    void kfValueStringDeserializesToStr() throws Exception {
        KfValue v = M.readValue("\"abc\"", KfValue.class);
        assertInstanceOf(KfValue.Str.class, v);
        assertEquals("abc", ((KfValue.Str) v).value());
    }

    @Test
    void kfValueSolidObjectDeserializesToFillV() throws Exception {
        KfValue v = M.readValue("{\"type\":\"solid\",\"color\":\"#FF0000\"}", KfValue.class);
        assertInstanceOf(KfValue.FillV.class, v);
        Fill fill = ((KfValue.FillV) v).fill();
        assertInstanceOf(SolidFill.class, fill);
        assertEquals("#FF0000", ((SolidFill) fill).color());
    }

    @Test
    void kfValueArrayInputDegradesLeniently() throws Exception {
        // 宽容路径（C1 修复）：array 等非预期形态不再抛 JsonMappingException（会冒泡致
        // 整面墙加载失败），改返回 null 交插值器守卫兜底。
        KfValue v = M.readValue("[1,2,3]", KfValue.class);
        assertNull(v, "array 形态 KfValue 应返回 null 而非抛异常");
    }

    @Test
    void kfValueBooleanInputDegradesLeniently() throws Exception {
        // 同上：boolean 也属非预期形态，返回 null 而非抛异常。
        KfValue v = M.readValue("true", KfValue.class);
        assertNull(v, "boolean 形态 KfValue 应返回 null 而非抛异常");
    }

    // ---------- Easing / Timeline wire 细节 ----------

    @Test
    void linearEasingOmitsBezierKey() throws Exception {
        String json = M.writeValueAsString(Easing.LINEAR);
        assertFalse(json.contains("bezier"), "LINEAR 缓动 wire 形态应省略 bezier 键");
        assertTrue(json.contains("\"linear\""));
    }

    @Test
    void cubicBezierRoundTripsBezierArray() throws Exception {
        Easing e = new Easing(EasingType.CUBIC_BEZIER, List.of(0.42, 0.0, 1.0, 1.0));
        Easing back = M.readValue(M.writeValueAsString(e), Easing.class);
        assertEquals(EasingType.CUBIC_BEZIER, back.type());
        assertEquals(List.of(0.42, 0.0, 1.0, 1.0), back.bezier());
    }

    @Test
    void emptyTracksSerializeToEmptyObject() throws Exception {
        Timeline tl = new Timeline("tl-5", "empty", 1000, 20,
                LoopMode.LOOP, TriggerConfig.MANUAL, Map.of());
        String json = M.writeValueAsString(tl);
        assertTrue(json.contains("\"tracks\":{}"), "空 tracks 应序列化为 {}（键存在）");
    }

    // ---------- ProjectState v3 / v2 兼容 ----------

    @Test
    void projectStateV2BlobWithoutTimelinesStaysStatic() throws Exception {
        // 手写无 timelines/activeTimelineId 的 v2 JSON：零迁移加法 → 读为空列表 + null
        String v2 = """
                {
                  "version": 5,
                  "protocolVersion": 2,
                  "canvas": {"widthMaps": 1, "heightMaps": 1, "background": "#FFFFFF"},
                  "layers": [
                    {"id": "l-1", "name": "Default Layer", "visible": true, "locked": false,
                     "opacity": 1.0, "blendMode": "normal", "elements": []}
                  ],
                  "activeLayerId": "l-1",
                  "history": {"undoDepth": 0, "redoDepth": 0}
                }
                """;
        ProjectState ps = M.readValue(v2, ProjectState.class);
        assertTrue(ps.timelines().isEmpty(), "旧 v2 blob 无 timelines 字段 → 空列表");
        assertNull(ps.activeTimelineId(), "旧 v2 blob 无 activeTimelineId 字段 → null");
    }

    @Test
    void staticProjectStateOmitsTimelineFields() throws Exception {
        // new ProjectState(1,1) 是纯静态工程：序列化不含 timelines / activeTimelineId
        String json = M.writeValueAsString(new ProjectState(1, 1));
        assertFalse(json.contains("timelines"), "静态工程序列化应省略 timelines（NON_EMPTY）");
        assertFalse(json.contains("activeTimelineId"), "静态工程序列化应省略 activeTimelineId（NON_NULL）");
        assertTrue(json.contains("\"protocolVersion\":3"), "0.6 起 protocolVersion 固定 3");
    }

    @Test
    void projectStateWithTimelinesRoundTrips() throws Exception {
        ProjectState ps = new ProjectState(1, 1);
        Timeline tl = new Timeline("tl-1", "anim", 2000, 20,
                LoopMode.LOOP, TriggerConfig.MANUAL,
                Map.of("e-1", List.of(kf("k-1", "x", 0, KfValue.of(1.0), Easing.LINEAR))));
        ps.addTimeline(tl);
        ps.activeTimelineId("tl-1");

        ProjectState back = M.readValue(M.writeValueAsString(ps), ProjectState.class);
        assertEquals(1, back.timelines().size());
        assertEquals(tl, back.timelines().get(0));
        assertEquals("tl-1", back.activeTimelineId());
    }

    @Test
    void activeTimelineIdPointingToMissingTimelineNullsOut() throws Exception {
        // 序列化一份 activeTimelineId 指向不存在 timeline 的 blob → 反序列化构造器归 null
        String json = """
                {
                  "version": 1,
                  "protocolVersion": 3,
                  "canvas": {"widthMaps": 1, "heightMaps": 1, "background": "#FFFFFF"},
                  "layers": [
                    {"id": "l-1", "name": "Default Layer", "visible": true, "locked": false,
                     "opacity": 1.0, "blendMode": "normal", "elements": []}
                  ],
                  "activeLayerId": "l-1",
                  "timelines": [],
                  "activeTimelineId": "tl-missing"
                }
                """;
        ProjectState ps = M.readValue(json, ProjectState.class);
        assertNull(ps.activeTimelineId(), "activeTimelineId 指向不存在 timeline → 归 null");
    }

    // ---------- ProjectState.restore 还原 timelines ----------

    @Test
    void restoreRecoversTimelinesFromSnapshot() {
        ProjectState ps = new ProjectState(1, 1);
        Timeline a = new Timeline("tl-a", "first", 1000, 20,
                LoopMode.LOOP, TriggerConfig.MANUAL,
                Map.of("e-1", List.of(kf("k-1", "x", 0, KfValue.of(0.0), Easing.LINEAR))));
        ps.addTimeline(a);
        ps.activeTimelineId("tl-a");

        // 直接拍快照（snapshot 时刻：仅 tl-a + active = tl-a）
        ProjectSnapshot snap = new ProjectSnapshot(
                ps.canvas(), ps.layers(), ps.activeLayerId(),
                ps.timelines(), ps.activeTimelineId(), null);

        // 改动：再追加 tl-b 并切换激活
        Timeline b = new Timeline("tl-b", "second", 2000, 20,
                LoopMode.ONCE, TriggerConfig.MANUAL, Map.of());
        ps.addTimeline(b);
        ps.activeTimelineId("tl-b");
        assertEquals(2, ps.timelines().size());
        assertEquals("tl-b", ps.activeTimelineId());

        // restore → 回到快照时刻
        ps.restore(snap);
        assertEquals(1, ps.timelines().size(), "restore 应回到快照时刻的单 timeline");
        assertEquals(a, ps.timelines().get(0));
        assertEquals("tl-a", ps.activeTimelineId());
    }

    // ---------- Timeline tracks null 元素宽容路径（C1 修复）----------

    /**
     * tracks 轨道列表含 null 元素时构造 Timeline 不应 NPE（旧 List.copyOf 会抛）；
     * null 元素被静默过滤，合法关键帧保留且顺序不变。
     */
    @Test
    void timelineTrackWithNullKeyframeFiltersNullsNoNpe() {
        List<Keyframe> withNulls = new ArrayList<>();
        Keyframe good1 = kf("k-1", "x", 0, KfValue.of(0.0), Easing.LINEAR);
        Keyframe good2 = kf("k-2", "x", 500, KfValue.of(10.0), Easing.LINEAR);
        withNulls.add(good1);
        withNulls.add(null);   // 损坏的 null 元素
        withNulls.add(good2);
        withNulls.add(null);

        // 构造不应 NPE
        Timeline tl = new Timeline("tl-null", "test", 1000, 20,
                LoopMode.LOOP, TriggerConfig.MANUAL,
                Map.of("e-1", withNulls));

        List<Keyframe> track = tl.tracks().get("e-1");
        assertEquals(2, track.size(), "null 关键帧应被过滤，合法元素保留");
        assertEquals(good1, track.get(0));
        assertEquals(good2, track.get(1));
    }

    /**
     * tracks 轨道列表为 null 本身（整条轨道 null）→ 退空列表，不抛。
     */
    @Test
    void timelineTrackValueNullFallsBackToEmptyList() {
        Map<String, List<Keyframe>> withNullTrack = new java.util.HashMap<>();
        withNullTrack.put("e-null", null);

        Timeline tl = new Timeline("tl-nulltrack", "test", 1000, 20,
                LoopMode.LOOP, TriggerConfig.MANUAL, withNullTrack);

        List<Keyframe> track = tl.tracks().get("e-null");
        assertTrue(track.isEmpty(), "轨道值为 null 应退空列表");
    }

    /**
     * 全轨道都是正常元素时 null 过滤逻辑不影响结果（顺序 / 内容不变）。
     */
    @Test
    void timelineTrackWithNoNullsUnaffected() {
        Keyframe kf1 = kf("k-1", "opacity", 0, KfValue.of(0.0), Easing.LINEAR);
        Keyframe kf2 = kf("k-2", "opacity", 1000, KfValue.of(1.0), Easing.LINEAR);

        Timeline tl = new Timeline("tl-clean", "test", 2000, 20,
                LoopMode.LOOP, TriggerConfig.MANUAL,
                Map.of("e-1", List.of(kf1, kf2)));

        List<Keyframe> track = tl.tracks().get("e-1");
        assertEquals(List.of(kf1, kf2), track, "无 null 元素的轨道内容应完整保留");
    }

    /**
     * 持久化 blob 里 tracks 某轨含 null 关键帧（Jackson 反序列化结果）时，
     * Timeline 构造不失败，整个 ProjectState 能正常加载。
     */
    @Test
    void projectStateDeserializationWithNullKeyframeInTrackSucceeds() throws Exception {
        // 手写一个轨道含 null 元素的 project_json 形态（模拟数据损坏 blob）
        String json = """
                {
                  "version": 1,
                  "protocolVersion": 3,
                  "canvas": {"widthMaps": 1, "heightMaps": 1, "background": "#FFFFFF"},
                  "layers": [
                    {"id": "l-1", "name": "Default Layer", "visible": true, "locked": false,
                     "opacity": 1.0, "blendMode": "normal", "elements": []}
                  ],
                  "activeLayerId": "l-1",
                  "timelines": [
                    {
                      "id": "tl-1", "name": "anim", "durationMs": 1000, "fps": 20,
                      "loopMode": "loop",
                      "tracks": {
                        "e-1": [
                          {"id": "k-1", "property": "x", "timeMs": 0, "value": 1.0,
                           "easing": {"type": "linear"}},
                          null,
                          {"id": "k-2", "property": "x", "timeMs": 500, "value": 5.0,
                           "easing": {"type": "linear"}}
                        ]
                      }
                    }
                  ]
                }
                """;
        // 不抛异常即通过（整墙加载不失败）
        ProjectState ps = M.readValue(json, ProjectState.class);
        assertEquals(1, ps.timelines().size(), "含 null 关键帧的 blob 应能正常加载 timeline");
        List<Keyframe> track = ps.timelines().get(0).tracks().get("e-1");
        assertEquals(2, track.size(), "null 关键帧被过滤后剩 2 个合法关键帧");
    }

    // ---------- 坏 blob 宽容路径（P1 审查确认项 #5）----------

    /**
     * 持久化 blob 里 bezier 含 null 元素时不得抛硬错（否则整面墙加载失败）。
     * 宽容语义：退 {@code bezier=null}；WS 路径的语义校验仍在 op 层拒 INVALID_EASING。
     */
    @Test
    void easingBezierWithNullElementDegradesLeniently() throws Exception {
        ObjectMapper m = new ObjectMapper();
        Easing e = m.readValue(
                "{\"type\":\"cubicBezier\",\"bezier\":[0.42,null,1.0,1.0]}", Easing.class);
        assertEquals(EasingType.CUBIC_BEZIER, e.type());
        assertNull(e.bezier(), "bezier 含 null 元素 → 整体退 null，不抛 NPE");
    }

    /** 持久化 blob 里 trigger params 含 null 值时静默滤掉该项，不得抛硬错。 */
    @Test
    void triggerParamsWithNullValueFilteredLeniently() throws Exception {
        ObjectMapper m = new ObjectMapper();
        TriggerConfig t = m.readValue(
                "{\"type\":\"variableChange\",\"params\":{\"fullName\":\"user/x\",\"junk\":null}}",
                TriggerConfig.class);
        assertEquals(TriggerType.VARIABLE_CHANGE, t.type());
        assertEquals(Map.of("fullName", "user/x"), t.params(),
                "null 值项应被滤掉，合法项保留");
    }
}
