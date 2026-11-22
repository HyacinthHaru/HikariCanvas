package ac.haru.hikaricanvas.state;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EditSession} 时间轴（{@code timeline.*}）与关键帧（{@code keyframe.*}）操作测试套件。
 */
class EditSessionTimelineTest {

    private static EditSession newSession() {
        EditSession es = new EditSession(new ProjectState(2, 1));
        es.setTimelineFpsLimits(20, 60);
        return es;
    }

    private static String addText(EditSession es, String content) {
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                es.addElement("text", Map.of("text", content), null, null);
        return ((Map<?, ?>) ok.patch().ops().get(0).value()).get("id").toString();
    }

    // ──────────────────────────────────────────────────────────
    //  timeline.*（create / update / delete / undo）
    // ──────────────────────────────────────────────────────────

    @Nested
    class TimelineOps {

        private static EditSession.OpResult.Ok createOk(EditSession es, String name,
                                                        Integer durationMs, Integer fps,
                                                        String loopMode, Map<String, Object> trigger) {
            EditSession.OpResult r = es.createTimeline(name, durationMs, fps, loopMode, trigger);
            return assertInstanceOf(EditSession.OpResult.Ok.class, r);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> timelineValueOf(EditSession.OpResult.Ok ok) {
            return (Map<String, Object>) ok.patch().ops().get(0).value();
        }

        @Test
        void createDefaultsAndFpsClamp() {
            EditSession es = newSession();
            EditSession.OpResult.Ok ok = createOk(es, null, 5000, 120, null, null);

            assertEquals(1, es.state().timelines().size());
            Timeline t = es.state().timelines().get(0);
            assertEquals("Timeline 1", t.name());
            assertEquals(5000, t.durationMs());
            assertEquals(60, t.fps());
            assertEquals(LoopMode.LOOP, t.loopMode());
            assertEquals(TriggerType.MANUAL, t.trigger().type());
            assertTrue(t.id().startsWith("tl-"), "id 前缀 tl-");
            assertEquals(t.id(), es.state().activeTimelineId());

            assertEquals(2, ok.patch().ops().size());
            assertEquals("add", ok.patch().ops().get(0).op());
            assertEquals("/timelines/0", ok.patch().ops().get(0).path());
            assertEquals("replace", ok.patch().ops().get(1).op());
            assertEquals("/activeTimelineId", ok.patch().ops().get(1).path());
            assertEquals(t.id(), ok.patch().ops().get(1).value());
            assertNull(ok.dirty());

            Map<String, Object> val = timelineValueOf(ok);
            assertEquals(t.id(), val.get("id"));
            assertEquals(60, ((Number) val.get("fps")).intValue());
            assertEquals("loop", val.get("loopMode"));
        }

        @Test
        void createFpsDefaultsWhenNull() {
            EditSession es = newSession();
            createOk(es, "Anim", 1000, null, "once", null);
            assertEquals(20, es.state().timelines().get(0).fps());
            assertEquals(LoopMode.ONCE, es.state().timelines().get(0).loopMode());
        }

        @Test
        void createWithTrigger() {
            EditSession es = newSession();
            Map<String, Object> trigger = Map.of(
                    "type", "variableChange",
                    "params", Map.of("fullName", "user/alarm"));
            createOk(es, "Alarm", 800, 30, "pingPong", trigger);
            Timeline t = es.state().timelines().get(0);
            assertEquals(TriggerType.VARIABLE_CHANGE, t.trigger().type());
            assertEquals("user/alarm", t.trigger().params().get("fullName"));
            assertEquals(LoopMode.PING_PONG, t.loopMode());
        }

        @Test
        void variableChangeTriggerWithoutFullNameRejected() {
            EditSession es = newSession();
            Map<String, Object> noFullName = Map.of("type", "variableChange");
            assertEquals("INVALID_PAYLOAD",
                    ((EditSession.OpResult.Error) es.createTimeline("x", 1000, 30, null, noFullName)).code());
            Map<String, Object> blank = Map.of("type", "schedule", "params", Map.of("fullName", "  "));
            assertEquals("INVALID_PAYLOAD",
                    ((EditSession.OpResult.Error) es.createTimeline("x", 1000, 30, null, blank)).code());
        }

        @Test
        void createDurationOutOfRangeRejected() {
            EditSession es = newSession();
            assertEquals("INVALID_PAYLOAD",
                    ((EditSession.OpResult.Error) es.createTimeline("x", 50, 30, null, null)).code());
            assertEquals("INVALID_PAYLOAD",
                    ((EditSession.OpResult.Error)
                            es.createTimeline("x", 3_600_001, 30, null, null)).code());
            assertEquals("INVALID_PAYLOAD",
                    ((EditSession.OpResult.Error) es.createTimeline("x", null, 30, null, null)).code());
        }

        @Test
        void createUnknownLoopModeRejected() {
            EditSession es = newSession();
            assertEquals("INVALID_PAYLOAD",
                    ((EditSession.OpResult.Error)
                            es.createTimeline("x", 1000, 30, "bogus", null)).code());
        }

        @Test
        void createSeventeenthRejected() {
            EditSession es = newSession();
            for (int i = 0; i < 16; i++) {
                createOk(es, "T" + i, 1000, 30, null, null);
            }
            assertEquals(16, es.state().timelines().size());
            EditSession.OpResult r = es.createTimeline("overflow", 1000, 30, null, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
            assertEquals(16, es.state().timelines().size());
        }

        @Test
        void updateFields() {
            EditSession es = newSession();
            createOk(es, "T", 2000, 30, "loop", null);
            String tid = es.state().timelines().get(0).id();

            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("name", "Renamed");
            patch.put("durationMs", 3000);
            patch.put("fps", 90);
            patch.put("loopMode", "once");
            EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.updateTimeline(tid, patch));

            Timeline t = es.state().timelines().get(0);
            assertEquals("Renamed", t.name());
            assertEquals(3000, t.durationMs());
            assertEquals(60, t.fps());
            assertEquals(LoopMode.ONCE, t.loopMode());
            assertNull(ok.dirty());
            assertEquals("replace", ok.patch().ops().get(0).op());
        }

        @Test
        void updateUnknownFieldRejected() {
            EditSession es = newSession();
            createOk(es, "T", 2000, 30, null, null);
            String tid = es.state().timelines().get(0).id();
            assertEquals("INVALID_PAYLOAD",
                    ((EditSession.OpResult.Error)
                            es.updateTimeline(tid, Map.of("bogus", 1))).code());
        }

        @Test
        void updateTimelineNotFound() {
            EditSession es = newSession();
            assertEquals("TIMELINE_NOT_FOUND",
                    ((EditSession.OpResult.Error)
                            es.updateTimeline("tl-nope", Map.of("name", "x"))).code());
        }

        @Test
        void updateTriggerExplicitNullRejected() {
            EditSession es = newSession();
            createOk(es, "T", 2000, 30, null,
                    Map.of("type", "variableChange", "params", Map.of("fullName", "user/x")));
            String tid = es.state().timelines().get(0).id();

            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("trigger", null);
            EditSession.OpResult r = es.updateTimeline(tid, patch);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
            assertEquals(TriggerType.VARIABLE_CHANGE,
                    es.state().timelines().get(0).trigger().type());
        }

        @Test
        void updateDurationLongOverflowRejected() {
            EditSession es = newSession();
            createOk(es, "T", 2000, 30, null, null);
            String tid = es.state().timelines().get(0).id();
            EditSession.OpResult r = es.updateTimeline(
                    tid, Map.of("durationMs", 4_294_970_296L));
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
            assertEquals(2000, es.state().timelines().get(0).durationMs());
        }

        @Test
        void updateDurationBelowKeyframeRejected() {
            EditSession es = newSession();
            createOk(es, "T", 5000, 30, null, null);
            String tid = es.state().timelines().get(0).id();
            String eid = addText(es, "hi");
            assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.addKeyframe(tid, eid, "x", 4000, 10, null));
            EditSession.OpResult r = es.updateTimeline(tid, Map.of("durationMs", 3000));
            assertEquals("INVALID_KEYFRAME_TIME", ((EditSession.OpResult.Error) r).code());
            assertEquals(5000, es.state().timelines().get(0).durationMs());
        }

        @Test
        void deleteTransfersActive() {
            EditSession es = newSession();
            createOk(es, "A", 1000, 30, null, null);
            String a = es.state().timelines().get(0).id();
            createOk(es, "B", 1000, 30, null, null);
            String b = es.state().timelines().get(1).id();
            assertEquals(b, es.state().activeTimelineId());

            EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.deleteTimeline(b));
            assertEquals(1, es.state().timelines().size());
            assertEquals(a, es.state().activeTimelineId());
            assertEquals("remove", ok.patch().ops().get(0).op());
            assertEquals("/timelines/1", ok.patch().ops().get(0).path());
            assertEquals("replace", ok.patch().ops().get(1).op());
            assertEquals(a, ok.patch().ops().get(1).value());
        }

        @Test
        void deleteLastSetsActiveNull() {
            EditSession es = newSession();
            createOk(es, "A", 1000, 30, null, null);
            String a = es.state().timelines().get(0).id();
            EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.deleteTimeline(a));
            assertTrue(es.state().timelines().isEmpty());
            assertNull(es.state().activeTimelineId());
            assertNull(ok.patch().ops().get(1).value());
        }

        @Test
        void deleteNotFound() {
            EditSession es = newSession();
            assertEquals("TIMELINE_NOT_FOUND",
                    ((EditSession.OpResult.Error) es.deleteTimeline("tl-nope")).code());
        }

        @Test
        void undoRedoRestoresTimelines() {
            EditSession es = newSession();
            createOk(es, "A", 1000, 30, null, null);
            String a = es.state().timelines().get(0).id();
            assertEquals(1, es.state().timelines().size());

            assertInstanceOf(EditSession.OpResult.OkSnapshot.class, es.undo());
            assertTrue(es.state().timelines().isEmpty());
            assertNull(es.state().activeTimelineId());

            assertInstanceOf(EditSession.OpResult.OkSnapshot.class, es.redo());
            assertEquals(1, es.state().timelines().size());
            assertEquals(a, es.state().timelines().get(0).id());
            assertEquals(a, es.state().activeTimelineId());
        }

        @Test
        void fpsClampWithoutInjectionUsesFallback() {
            EditSession es = new EditSession(new ProjectState(2, 1));
            EditSession.OpResult.Ok ok = createOk(es, "T", 1000, 999, null, null);
            assertEquals(60, es.state().timelines().get(0).fps());
            assertNotNull(ok);
        }
    }

    // ──────────────────────────────────────────────────────────
    //  keyframe.*（add / update / delete / move / 批量合并撤销 / MAX_DIM）
    // ──────────────────────────────────────────────────────────

    @Nested
    class KeyframeOps {

        private static String newTimeline(EditSession es, int durationMs) {
            es.createTimeline("T", durationMs, 30, null, null);
            return es.state().timelines().get(0).id();
        }

        private static List<Keyframe> track(EditSession es, String elementId) {
            return es.state().timelines().get(0).tracks().get(elementId);
        }

        @Test
        void addSortsByTimeMs() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");

            assertInstanceOf(EditSession.OpResult.Ok.class, es.addKeyframe(tid, eid, "x", 500, 50, null));
            assertInstanceOf(EditSession.OpResult.Ok.class, es.addKeyframe(tid, eid, "x", 100, 10, null));
            assertInstanceOf(EditSession.OpResult.Ok.class, es.addKeyframe(tid, eid, "x", 300, 30, null));

            List<Keyframe> kfs = track(es, eid);
            assertEquals(3, kfs.size());
            assertEquals(100, kfs.get(0).timeMs());
            assertEquals(300, kfs.get(1).timeMs());
            assertEquals(500, kfs.get(2).timeMs());
        }

        @Test
        void addNewTrackPatchIsWholeList() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");

            EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                    es.addKeyframe(tid, eid, "opacity", 0, 1.0, null);
            assertEquals(1, ok.patch().ops().size());
            PatchOp op = ok.patch().ops().get(0);
            assertEquals("add", op.op());
            assertEquals("/timelines/0/tracks/" + eid, op.path());
            assertInstanceOf(List.class, op.value());
            assertEquals(1, ((List<?>) op.value()).size());
            assertNull(ok.dirty());
        }

        @Test
        void addExistingTrackPatchIsSingleKeyframe() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");
            es.addKeyframe(tid, eid, "x", 100, 10, null);

            EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                    es.addKeyframe(tid, eid, "x", 50, 5, null);
            PatchOp op = ok.patch().ops().get(0);
            assertEquals("add", op.op());
            assertEquals("/timelines/0/tracks/" + eid + "/0", op.path());
            assertInstanceOf(Map.class, op.value());
        }

        @Test
        void addPropertyWhitelistRejected() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");
            assertEquals("INVALID_PAYLOAD",
                    ((EditSession.OpResult.Error)
                            es.addKeyframe(tid, eid, "bogus", 100, 1, null)).code());
        }

        @Test
        void addTimeOutOfRangeRejected() {
            EditSession es = newSession();
            String tid = newTimeline(es, 1000);
            String eid = addText(es, "hi");
            assertEquals("INVALID_KEYFRAME_TIME",
                    ((EditSession.OpResult.Error)
                            es.addKeyframe(tid, eid, "x", 2000, 1, null)).code());
            assertEquals("INVALID_KEYFRAME_TIME",
                    ((EditSession.OpResult.Error)
                            es.addKeyframe(tid, eid, "x", -1, 1, null)).code());
        }

        @Test
        void addUnknownElementRejected() {
            EditSession es = newSession();
            String tid = newTimeline(es, 1000);
            assertEquals("INVALID_ELEMENT",
                    ((EditSession.OpResult.Error)
                            es.addKeyframe(tid, "e-nope", "x", 100, 1, null)).code());
        }

        @Test
        void addTimelineNotFound() {
            EditSession es = newSession();
            String eid = addText(es, "hi");
            assertEquals("TIMELINE_NOT_FOUND",
                    ((EditSession.OpResult.Error)
                            es.addKeyframe("tl-nope", eid, "x", 100, 1, null)).code());
        }

        @Test
        void addEasingValidation() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");

            assertEquals("INVALID_EASING",
                    ((EditSession.OpResult.Error) es.addKeyframe(tid, eid, "x", 100, 1,
                            Map.of("type", "cubicBezier"))).code());
            assertEquals("INVALID_EASING",
                    ((EditSession.OpResult.Error) es.addKeyframe(tid, eid, "x", 100, 1,
                            Map.of("type", "cubicBezier",
                                    "bezier", List.of(1.5, 0.0, 0.5, 1.0)))).code());
            assertEquals("INVALID_EASING",
                    ((EditSession.OpResult.Error) es.addKeyframe(tid, eid, "x", 100, 1,
                            Map.of("type", "linear",
                                    "bezier", List.of(0.0, 0.0, 1.0, 1.0)))).code());
            EditSession.OpResult okR = es.addKeyframe(tid, eid, "x", 100, 1,
                    Map.of("type", "cubicBezier", "bezier", List.of(0.25, 0.1, 0.25, 1.0)));
            EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, okR);
            assertNotNull(ok);
            Keyframe kf = track(es, eid).get(0);
            assertEquals(EasingType.CUBIC_BEZIER, kf.easing().type());
            assertEquals(4, kf.easing().bezier().size());
        }

        @Test
        void addTrackQuotaRejected() {
            EditSession es = newSession();
            String tid = newTimeline(es, 3_600_000);
            String eid = addText(es, "hi");
            for (int i = 0; i < 256; i++) {
                EditSession.OpResult r = es.addKeyframe(tid, eid, "x", i * 10, i, null);
                assertInstanceOf(EditSession.OpResult.Ok.class, r);
            }
            assertEquals(256, track(es, eid).size());
            EditSession.OpResult over = es.addKeyframe(tid, eid, "x", 2600, 999, null);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) over).code());
        }

        @Test
        void addStringValueForFillProperty() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");
            EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                    es.addKeyframe(tid, eid, "color", 0, "#FF0000", null);
            assertNotNull(ok);
            Keyframe kf = track(es, eid).get(0);
            assertInstanceOf(KfValue.Str.class, kf.value());
            assertEquals("#FF0000", ((KfValue.Str) kf.value()).value());
        }

        @Test
        void addVariableTemplateValue() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");
            EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                    es.addKeyframe(tid, eid, "opacity", 0, "${var:score}", null);
            assertNotNull(ok);
            Keyframe kf = track(es, eid).get(0);
            assertInstanceOf(KfValue.Str.class, kf.value());
            assertEquals("${var:score}", ((KfValue.Str) kf.value()).value());
        }

        @Test
        void updateValueSingleFramePatch() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");
            es.addKeyframe(tid, eid, "x", 100, 10, null);
            String kid = track(es, eid).get(0).id();

            EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                    es.updateKeyframe(tid, kid, Map.of("value", 99));
            PatchOp op = ok.patch().ops().get(0);
            assertEquals("replace", op.op());
            assertEquals("/timelines/0/tracks/" + eid + "/0", op.path());
            Keyframe kf = track(es, eid).get(0);
            assertInstanceOf(KfValue.Num.class, kf.value());
            assertEquals(99.0, ((KfValue.Num) kf.value()).value());
        }

        @Test
        void updateTimeMsReordersWholeTrack() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");
            es.addKeyframe(tid, eid, "x", 100, 10, null);
            es.addKeyframe(tid, eid, "x", 500, 50, null);
            String first = track(es, eid).get(0).id();

            EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                    es.updateKeyframe(tid, first, Map.of("timeMs", 900));
            PatchOp op = ok.patch().ops().get(0);
            assertEquals("replace", op.op());
            assertEquals("/timelines/0/tracks/" + eid, op.path());
            assertInstanceOf(List.class, op.value());

            List<Keyframe> kfs = track(es, eid);
            assertEquals(500, kfs.get(0).timeMs());
            assertEquals(900, kfs.get(1).timeMs());
        }

        @Test
        void updateKeyframeNotFound() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            assertEquals("KEYFRAME_NOT_FOUND",
                    ((EditSession.OpResult.Error)
                            es.updateKeyframe(tid, "kf-nope", Map.of("value", 1))).code());
        }

        @Test
        void updateUnknownFieldRejected() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");
            es.addKeyframe(tid, eid, "x", 100, 10, null);
            String kid = track(es, eid).get(0).id();
            assertEquals("INVALID_PAYLOAD",
                    ((EditSession.OpResult.Error)
                            es.updateKeyframe(tid, kid, Map.of("bogus", 1))).code());
        }

        @Test
        void deleteToEmptyPrunesTrack() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");
            es.addKeyframe(tid, eid, "x", 100, 10, null);
            String kid = track(es, eid).get(0).id();

            EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok) es.deleteKeyframe(tid, kid);
            PatchOp op = ok.patch().ops().get(0);
            assertEquals("remove", op.op());
            assertEquals("/timelines/0/tracks/" + eid, op.path());
            assertNull(es.state().timelines().get(0).tracks().get(eid));
        }

        @Test
        void deleteOneOfManyKeepsTrack() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");
            es.addKeyframe(tid, eid, "x", 100, 10, null);
            es.addKeyframe(tid, eid, "x", 500, 50, null);
            String first = track(es, eid).get(0).id();

            EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok) es.deleteKeyframe(tid, first);
            PatchOp op = ok.patch().ops().get(0);
            assertEquals("remove", op.op());
            assertEquals("/timelines/0/tracks/" + eid + "/0", op.path());
            assertEquals(1, track(es, eid).size());
            assertEquals(500, track(es, eid).get(0).timeMs());
        }

        @Test
        void deleteKeyframeNotFound() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            assertEquals("KEYFRAME_NOT_FOUND",
                    ((EditSession.OpResult.Error) es.deleteKeyframe(tid, "kf-nope")).code());
        }

        @Test
        void moveCoalescesUndoToBeforeFirstMove() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");
            es.addKeyframe(tid, eid, "x", 100, 10, null);
            String kid = track(es, eid).get(0).id();
            int beforeMove = track(es, eid).get(0).timeMs();

            assertInstanceOf(EditSession.OpResult.Ok.class, es.moveKeyframe(tid, kid, 200));
            assertInstanceOf(EditSession.OpResult.Ok.class, es.moveKeyframe(tid, kid, 300));
            assertInstanceOf(EditSession.OpResult.Ok.class, es.moveKeyframe(tid, kid, 400));
            assertEquals(400, track(es, eid).get(0).timeMs());

            assertInstanceOf(EditSession.OpResult.OkSnapshot.class, es.undo());
            assertEquals(beforeMove, track(es, eid).get(0).timeMs());
        }

        @Test
        void moveTimeOutOfRangeRejected() {
            EditSession es = newSession();
            String tid = newTimeline(es, 1000);
            String eid = addText(es, "hi");
            es.addKeyframe(tid, eid, "x", 100, 10, null);
            String kid = track(es, eid).get(0).id();
            assertEquals("INVALID_KEYFRAME_TIME",
                    ((EditSession.OpResult.Error) es.moveKeyframe(tid, kid, 5000)).code());
        }

        @Test
        void moveKeyframeNotFound() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            assertEquals("KEYFRAME_NOT_FOUND",
                    ((EditSession.OpResult.Error) es.moveKeyframe(tid, "kf-nope", 100)).code());
        }

        @Test
        void batchedAddsWithCoalesceKeyUndoAsOneStep() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");

            String ck = "integ:" + eid + ":2000";
            for (String p : List.of("x", "y", "w", "h", "rotation", "opacity")) {
                assertInstanceOf(EditSession.OpResult.Ok.class,
                        es.addKeyframe(tid, eid, p, 2000, 1, null, ck));
            }
            assertEquals(6, track(es, eid).size());

            assertInstanceOf(EditSession.OpResult.OkSnapshot.class, es.undo());
            assertNull(track(es, eid), "整体帧 6 个属性应合并一步撤销、轨道清空");
        }

        @Test
        void addsWithoutCoalesceKeyUndoSeparately() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");

            es.addKeyframe(tid, eid, "x", 100, 1, null);
            es.addKeyframe(tid, eid, "y", 100, 2, null);
            assertEquals(2, track(es, eid).size());

            es.undo();
            assertEquals(1, track(es, eid).size(), "无 coalesceKey 应各自一步撤销");
        }

        @Test
        void batchedMovesWithCoalesceKeyUndoAsOneStep() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");
            es.addKeyframe(tid, eid, "x", 1000, 10, null);
            es.addKeyframe(tid, eid, "y", 1000, 20, null);
            String kx = track(es, eid).stream().filter(k -> k.property().equals("x")).findFirst().orElseThrow().id();
            String ky = track(es, eid).stream().filter(k -> k.property().equals("y")).findFirst().orElseThrow().id();

            String ck = "integ-move:" + eid + ":1000";
            assertInstanceOf(EditSession.OpResult.Ok.class, es.moveKeyframe(tid, kx, 3000, ck));
            assertInstanceOf(EditSession.OpResult.Ok.class, es.moveKeyframe(tid, ky, 3000, ck));
            for (Keyframe k : track(es, eid)) assertEquals(3000, k.timeMs());

            assertInstanceOf(EditSession.OpResult.OkSnapshot.class, es.undo());
            for (Keyframe k : track(es, eid)) assertEquals(1000, k.timeMs(), "整体块拖动应一步撤销回原时刻");
        }

        @Test
        void addKeyframeW_overMaxDim_rejected() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");
            int overMax = ElementValidator.MAX_DIM + 1;
            EditSession.OpResult r = es.addKeyframe(tid, eid, "w", 100, overMax, null);
            assertEquals("INVALID_PAYLOAD",
                    ((EditSession.OpResult.Error) r).code(),
                    "w keyframe > MAX_DIM 应被拒");
        }

        @Test
        void addKeyframeH_overMaxDim_rejected() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");
            int overMax = ElementValidator.MAX_DIM + 1;
            EditSession.OpResult r = es.addKeyframe(tid, eid, "h", 100, overMax, null);
            assertEquals("INVALID_PAYLOAD",
                    ((EditSession.OpResult.Error) r).code(),
                    "h keyframe > MAX_DIM 应被拒");
        }

        @Test
        void addKeyframeW_exactlyMaxDim_accepted() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");
            EditSession.OpResult r = es.addKeyframe(tid, eid, "w", 100, ElementValidator.MAX_DIM, null);
            assertInstanceOf(EditSession.OpResult.Ok.class, r, "w keyframe = MAX_DIM 应允许");
        }

        @Test
        void addKeyframeH_exactlyMaxDim_accepted() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");
            EditSession.OpResult r = es.addKeyframe(tid, eid, "h", 100, ElementValidator.MAX_DIM, null);
            assertInstanceOf(EditSession.OpResult.Ok.class, r, "h keyframe = MAX_DIM 应允许");
        }

        @Test
        void addKeyframeX_overMaxCoord_notBlockedByMaxDim() {
            EditSession es = newSession();
            String tid = newTimeline(es, 5000);
            String eid = addText(es, "hi");
            int overMax = ElementValidator.MAX_DIM + 1;
            EditSession.OpResult r = es.addKeyframe(tid, eid, "x", 100, overMax, null);
            assertInstanceOf(EditSession.OpResult.Ok.class, r,
                    "x keyframe > MAX_DIM 不受 MAX_DIM 尺寸守卫影响");
        }
    }
}
