package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间轴 record 族序列化契约与关键帧唯一性 / 边界不变量单测。契约见 {@code docs/timeline.md §2}
 * 与 {@code docs/protocol.md §7}。
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

    // ---------- KfValue 单测（三态分流 + 非预期形态降级）----------

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
        // 宽容路径：array 等非预期形态返回 null 交插值器守卫兜底，避免整面墙加载失败。
        KfValue v = M.readValue("[1,2,3]", KfValue.class);
        assertNull(v, "array 形态 KfValue 应返回 null 而非抛异常");
    }

    @Test
    void kfValueBooleanInputDegradesLeniently() throws Exception {
        KfValue v = M.readValue("true", KfValue.class);
        assertNull(v, "boolean 形态 KfValue 应返回 null 而非抛异常");
    }

    @Test
    void unknownFillTypeInKeyframeValue_deserializesToNull() throws Exception {
        KfValue v = M.readValue("{\"type\":\"plaidGradient\",\"color\":\"#fff\"}", KfValue.class);
        assertNull(v, "未知 Fill type 应退 null 交插值器兜底，不能抛硬错");
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
    void unknownEasingType_deserializesToLinear() throws Exception {
        Easing e = M.readValue("{\"type\":\"elasticOutFromTheFuture\"}", Easing.class);
        assertSame(EasingType.LINEAR, e.type(), "认不出的 type 应兜成 LINEAR");
    }

    @Test
    void knownEasingType_stillParses() throws Exception {
        assertSame(EasingType.EASE_IN_OUT,
                M.readValue("{\"type\":\"easeInOut\"}", Easing.class).type());
        assertEquals("easeInOut",
                M.writeValueAsString(EasingType.EASE_IN_OUT).replace("\"", ""),
                "序列化 wire 形态不能被 @JsonCreator 带偏");
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
        String json = M.writeValueAsString(new ProjectState(1, 1));
        assertFalse(json.contains("timelines"), "静态工程序列化应省略 timelines（NON_EMPTY）");
        assertFalse(json.contains("activeTimelineId"), "静态工程序列化应省略 activeTimelineId（NON_NULL）");
        assertTrue(json.contains("\"protocolVersion\":3"), "当前 protocolVersion 固定 3");
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

        ProjectSnapshot snap = new ProjectSnapshot(
                ps.canvas(), ps.layers(), ps.activeLayerId(),
                ps.timelines(), ps.activeTimelineId(), null);

        Timeline b = new Timeline("tl-b", "second", 2000, 20,
                LoopMode.ONCE, TriggerConfig.MANUAL, Map.of());
        ps.addTimeline(b);
        ps.activeTimelineId("tl-b");
        assertEquals(2, ps.timelines().size());
        assertEquals("tl-b", ps.activeTimelineId());

        ps.restore(snap);
        assertEquals(1, ps.timelines().size(), "restore 应回到快照时刻的单 timeline");
        assertEquals(a, ps.timelines().get(0));
        assertEquals("tl-a", ps.activeTimelineId());
    }

    // ---------- Timeline tracks 与持久化坏 blob 宽容路径 ----------

    @Test
    void timelineTrackWithNullKeyframeFiltersNullsNoNpe() {
        List<Keyframe> withNulls = new ArrayList<>();
        Keyframe good1 = kf("k-1", "x", 0, KfValue.of(0.0), Easing.LINEAR);
        Keyframe good2 = kf("k-2", "x", 500, KfValue.of(10.0), Easing.LINEAR);
        withNulls.add(good1);
        withNulls.add(null);
        withNulls.add(good2);
        withNulls.add(null);

        Timeline tl = new Timeline("tl-null", "test", 1000, 20,
                LoopMode.LOOP, TriggerConfig.MANUAL,
                Map.of("e-1", withNulls));

        List<Keyframe> track = tl.tracks().get("e-1");
        assertEquals(2, track.size(), "null 关键帧应被过滤，合法元素保留");
        assertEquals(good1, track.get(0));
        assertEquals(good2, track.get(1));
    }

    @Test
    void timelineTrackValueNullFallsBackToEmptyList() {
        Map<String, List<Keyframe>> withNullTrack = new HashMap<>();
        withNullTrack.put("e-null", null);

        Timeline tl = new Timeline("tl-nulltrack", "test", 1000, 20,
                LoopMode.LOOP, TriggerConfig.MANUAL, withNullTrack);

        List<Keyframe> track = tl.tracks().get("e-null");
        assertTrue(track.isEmpty(), "轨道值为 null 应退空列表");
    }

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

    @Test
    void projectStateDeserializationWithNullKeyframeInTrackSucceeds() throws Exception {
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
        ProjectState ps = M.readValue(json, ProjectState.class);
        assertEquals(1, ps.timelines().size(), "含 null 关键帧的 blob 应能正常加载 timeline");
        List<Keyframe> track = ps.timelines().get(0).tracks().get("e-1");
        assertEquals(2, track.size(), "null 关键帧被过滤后剩 2 个合法关键帧");
    }

    @Test
    void easingBezierWithNullElementDegradesLeniently() throws Exception {
        Easing e = M.readValue(
                "{\"type\":\"cubicBezier\",\"bezier\":[0.42,null,1.0,1.0]}", Easing.class);
        assertEquals(EasingType.CUBIC_BEZIER, e.type());
        assertNull(e.bezier(), "bezier 含 null 元素 → 整体退 null，不抛 NPE");
    }

    @Test
    void triggerParamsWithNullValueFilteredLeniently() throws Exception {
        TriggerConfig t = M.readValue(
                "{\"type\":\"variableChange\",\"params\":{\"fullName\":\"user/x\",\"junk\":null}}",
                TriggerConfig.class);
        assertEquals(TriggerType.VARIABLE_CHANGE, t.type());
        assertEquals(Map.of("fullName", "user/x"), t.params(),
                "null 值项应被滤掉，合法项保留");
    }

    /**
     * 关键帧唯一性不变量（{@code docs/timeline.md §2.1}）与关键帧数值 / bezier 边界守卫：
     * 同一 {@code (element, property, timeMs)} 上只允许存在一帧。
     */
    @Nested
    class KeyframeUniquenessAndBounds {

        private EditSession newSession() {
            EditSession es = new EditSession(new ProjectState(2, 1));
            es.setTimelineFpsLimits(20, 60);
            return es;
        }

        private String addText(EditSession es, String content) {
            EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                    es.addElement("text", Map.of("text", content), null, null);
            return ((Map<?, ?>) ok.patch().ops().get(0).value()).get("id").toString();
        }

        private String newTimeline(EditSession es, int durationMs) {
            es.createTimeline("T", durationMs, 30, null, null);
            return es.state().timelines().get(0).id();
        }

        private List<Keyframe> track(EditSession es, String elementId) {
            return es.state().timelines().get(0).tracks().get(elementId);
        }

        @Test
        void addKeyframe_sameElementPropertyTime_overwritesInsteadOfAppending() {
            EditSession es = newSession();
            String el = addText(es, "hi");
            String tl = newTimeline(es, 1000);

            assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.addKeyframe(tl, el, "x", 500, 10, null, null));
            assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.addKeyframe(tl, el, "x", 500, 99, null, null));

            List<Keyframe> t = track(es, el);
            assertEquals(1, t.size(), "同一 (property, timeMs) 只能有一帧，实际 " + t);
            assertEquals(99.0, ((KfValue.Num) t.get(0).value()).value(), 1e-9,
                    "后写的值必须生效（覆盖语义）");
        }

        @Test
        void addKeyframe_overwriteKeepsKeyframeId() {
            EditSession es = newSession();
            String el = addText(es, "hi");
            String tl = newTimeline(es, 1000);

            es.addKeyframe(tl, el, "x", 500, 10, null, null);
            String firstId = track(es, el).get(0).id();
            es.addKeyframe(tl, el, "x", 500, 99, null, null);
            assertEquals(firstId, track(es, el).get(0).id());
        }

        @Test
        void addKeyframe_differentPropertySameTime_coexists() {
            EditSession es = newSession();
            String el = addText(es, "hi");
            String tl = newTimeline(es, 1000);

            es.addKeyframe(tl, el, "x", 500, 10, null, null);
            es.addKeyframe(tl, el, "y", 500, 20, null, null);
            assertEquals(2, track(es, el).size(), "x 与 y 是两条属性轨，同时刻可以各有一帧");
        }

        @Test
        void moveKeyframe_ontoExistingSameTime_movedFrameWins() {
            EditSession es = newSession();
            String el = addText(es, "hi");
            String tl = newTimeline(es, 1000);

            es.addKeyframe(tl, el, "x", 100, 11, null, null);
            es.addKeyframe(tl, el, "x", 900, 99, null, null);
            String leftId = track(es, el).get(0).id();

            assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.moveKeyframe(tl, leftId, 900, null));

            List<Keyframe> t = track(es, el);
            assertEquals(1, t.size(), "撞帧后只应剩一帧，实际 " + t);
            assertEquals(leftId, t.get(0).id(), "胜出的必须是被拖过来的那一帧");
            assertEquals(11.0, ((KfValue.Num) t.get(0).value()).value(), 1e-9,
                    "生效的值必须是被拖那一帧的值");
        }

        @Test
        void moveKeyframe_ontoOtherPropertySameTime_bothSurvive() {
            EditSession es = newSession();
            String el = addText(es, "hi");
            String tl = newTimeline(es, 1000);

            es.addKeyframe(tl, el, "x", 100, 11, null, null);
            es.addKeyframe(tl, el, "y", 900, 22, null, null);
            String xId = track(es, el).stream()
                    .filter(k -> "x".equals(k.property())).findFirst().orElseThrow().id();

            assertInstanceOf(EditSession.OpResult.Ok.class, es.moveKeyframe(tl, xId, 900, null));
            assertEquals(2, track(es, el).size(), "不同 property 不冲突");
        }

        @Test
        void moveKeyframe_normalMoveStillSorts() {
            EditSession es = newSession();
            String el = addText(es, "hi");
            String tl = newTimeline(es, 1000);

            es.addKeyframe(tl, el, "x", 100, 1, null, null);
            es.addKeyframe(tl, el, "x", 500, 2, null, null);
            es.addKeyframe(tl, el, "x", 900, 3, null, null);
            String firstId = track(es, el).get(0).id();

            assertInstanceOf(EditSession.OpResult.Ok.class, es.moveKeyframe(tl, firstId, 700, null));
            List<Keyframe> t = track(es, el);
            assertEquals(3, t.size());
            assertEquals(List.of(500, 700, 900),
                    t.stream().map(Keyframe::timeMs).toList());
        }

        @Test
        void keyframe_wOverMaxDim_numberRejected() {
            EditSession es = newSession();
            String el = addText(es, "hi");
            String tl = newTimeline(es, 1000);
            EditSession.OpResult r = es.addKeyframe(tl, el, "w", 0,
                    ElementValidator.MAX_DIM + 1, null, null);
            assertEquals("INVALID_PAYLOAD",
                    assertInstanceOf(EditSession.OpResult.Error.class, r).code());
        }

        @Test
        void keyframe_wOverMaxDim_numericStringAlsoRejected() {
            EditSession es = newSession();
            String el = addText(es, "hi");
            String tl = newTimeline(es, 1000);
            EditSession.OpResult r = es.addKeyframe(tl, el, "h", 0,
                    String.valueOf(ElementValidator.MAX_DIM + 1), null, null);
            assertEquals("INVALID_PAYLOAD",
                    assertInstanceOf(EditSession.OpResult.Error.class, r).code(),
                    "字符串形态必须走同一道闸");
        }

        @Test
        void keyframe_numericStringWithinBound_accepted() {
            EditSession es = newSession();
            String el = addText(es, "hi");
            String tl = newTimeline(es, 1000);
            assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.addKeyframe(tl, el, "w", 0, "128", null, null));
        }

        @Test
        void keyframe_varTemplateForDimension_stillAccepted() {
            EditSession es = newSession();
            String el = addText(es, "hi");
            String tl = newTimeline(es, 1000);
            assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.addKeyframe(tl, el, "w", 0, "${var:user/w}", null, null));
        }

        @Test
        void easing_hugeBezierY_rejected() {
            EditSession es = newSession();
            String el = addText(es, "hi");
            String tl = newTimeline(es, 1000);

            Map<String, Object> easing = Map.of(
                    "type", "cubicBezier",
                    "bezier", List.of(0.0, 1e308, 1.0, 0.0));
            EditSession.OpResult r = es.addKeyframe(tl, el, "x", 0, 1, easing, null);
            assertEquals("INVALID_EASING",
                    assertInstanceOf(EditSession.OpResult.Error.class, r).code());
        }

        @Test
        void easing_moderateOvershootBezierY_accepted() {
            EditSession es = newSession();
            String el = addText(es, "hi");
            String tl = newTimeline(es, 1000);

            Map<String, Object> easing = Map.of(
                    "type", "cubicBezier",
                    "bezier", List.of(0.68, -0.55, 0.265, 1.55));
            assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.addKeyframe(tl, el, "x", 0, 1, easing, null));
        }

        @Test
        void easing_bezierYBoundaryExactlyAtLimit() {
            EditSession es = newSession();
            String el = addText(es, "hi");
            String tl = newTimeline(es, 1000);

            assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.addKeyframe(tl, el, "x", 0, 1,
                            Map.of("type", "cubicBezier",
                                    "bezier", List.of(0.0, -100.0, 1.0, 100.0)), null));
            EditSession.OpResult over = es.addKeyframe(tl, el, "x", 10, 1,
                    Map.of("type", "cubicBezier",
                            "bezier", List.of(0.0, 0.0, 1.0, 100.5)), null);
            assertEquals("INVALID_EASING",
                    assertInstanceOf(EditSession.OpResult.Error.class, over).code());
        }
    }
}
