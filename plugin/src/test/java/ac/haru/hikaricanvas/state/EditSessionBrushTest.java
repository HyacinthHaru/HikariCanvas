package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EditSession} 笔刷状态机（{@code brush.start / point / end / cancel}）与过期缓冲清理测试。
 */
class EditSessionBrushTest {

    private static EditSession newSession() {
        return new EditSession(new ProjectState(2, 1));
    }

    private static BrushPoint pt(double x, double y, double p) {
        return new BrushPoint(x, y, p);
    }

    @Test
    void startBrushReturnsStrokeId() {
        EditSession es = newSession();
        EditSession.OpResult r = es.startBrush(Map.of(
                "size", 4,
                "color", "#FF0000"
        ), null);
        assertInstanceOf(EditSession.OpResult.OkBrushStart.class, r);
        String id = ((EditSession.OpResult.OkBrushStart) r).strokeId();
        assertTrue(id.startsWith("s-"));
    }

    @Test
    void startBrushRejectsOversizedSize() {
        EditSession es = newSession();
        EditSession.OpResult r = es.startBrush(Map.of(
                "size", 999,
                "color", "#FF0000"
        ), null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void startBrushAcceptsFillObject() {
        EditSession es = newSession();
        EditSession.OpResult r = es.startBrush(Map.of(
                "size", 4,
                "fill", Map.of("type", "linear", "angle", 0.0, "stops", List.of(
                        Map.of("position", 0.0, "color", "#FF0000"),
                        Map.of("position", 1.0, "color", "#0000FF")))
        ), null);
        assertInstanceOf(EditSession.OpResult.OkBrushStart.class, r);
    }

    @Test
    void appendBrushPointsAccumulates() {
        EditSession es = newSession();
        String sid = ((EditSession.OpResult.OkBrushStart) es.startBrush(
                Map.of("size", 4, "color", "#000000"), null)).strokeId();
        EditSession.OpResult r = es.appendBrushPoints(sid,
                List.of(pt(10, 10, 0.5), pt(20, 20, 0.6)));
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
    }

    @Test
    void appendBrushPointsRejectsUnknownStroke() {
        EditSession es = newSession();
        EditSession.OpResult r = es.appendBrushPoints("s-nonexistent",
                List.of(pt(10, 10, 0.5)));
        assertEquals("INVALID_STROKE", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void endBrushCreatesElement() {
        EditSession es = newSession();
        // smoothing=0 关闭 RDP 简化，保证所有 raw 点都进 element.points
        String sid = ((EditSession.OpResult.OkBrushStart) es.startBrush(
                Map.of("size", 4, "color", "#FF0000", "smoothing", 0.0), null)).strokeId();
        es.appendBrushPoints(sid, List.of(
                pt(10, 10, 0.5),
                pt(20, 15, 0.8),
                pt(30, 20, 0.6)
        ));
        EditSession.OpResult r = es.endBrush(sid);
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);
        assertEquals(1, ok.patch().ops().size());
        assertEquals(1, es.state().elements().size());
        BrushStrokeElement bs = assertInstanceOf(BrushStrokeElement.class, es.state().elements().get(0));
        assertEquals(3, bs.points().size());
        assertEquals(4, bs.size());
        assertTrue(bs.points().get(0).x() >= 0);
        assertTrue(bs.points().get(0).y() >= 0);
    }

    @Test
    void endBrushSimplifiesCollinearPoints() {
        EditSession es = newSession();
        String sid = ((EditSession.OpResult.OkBrushStart) es.startBrush(
                Map.of("size", 4, "color", "#FF0000", "smoothing", 0.5), null)).strokeId();
        es.appendBrushPoints(sid, List.of(
                pt(10, 10, 0.5),
                pt(20, 15, 0.5),
                pt(30, 20, 0.5)
        ));
        es.endBrush(sid);
        BrushStrokeElement bs = (BrushStrokeElement) es.state().elements().get(0);
        assertEquals(2, bs.points().size());
    }

    @Test
    void endBrushDiscardsTooShortStroke() {
        EditSession es = newSession();
        String sid = ((EditSession.OpResult.OkBrushStart) es.startBrush(
                Map.of("size", 4, "color", "#FF0000"), null)).strokeId();
        es.appendBrushPoints(sid, List.of(pt(10, 10, 0.5)));
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, es.endBrush(sid));
        assertTrue(ok.patch().ops().isEmpty());
        assertEquals(0, es.state().elements().size());
    }

    @Test
    void endBrushRejectsUnknownStroke() {
        EditSession es = newSession();
        EditSession.OpResult r = es.endBrush("s-fake");
        assertEquals("INVALID_STROKE", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void cancelBrushDiscardsBuffer() {
        EditSession es = newSession();
        String sid = ((EditSession.OpResult.OkBrushStart) es.startBrush(
                Map.of("size", 4, "color", "#FF0000"), null)).strokeId();
        es.appendBrushPoints(sid, List.of(pt(10, 10, 0.5), pt(20, 20, 0.5)));
        EditSession.OpResult r = es.cancelBrush(sid);
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        assertEquals(0, es.state().elements().size());
        EditSession.OpResult r2 = es.endBrush(sid);
        assertEquals("INVALID_STROKE", ((EditSession.OpResult.Error) r2).code());
    }

    @Test
    void maxActiveStrokesLimit() {
        EditSession es = newSession();
        for (int i = 0; i < 4; i++) {
            assertInstanceOf(EditSession.OpResult.OkBrushStart.class,
                    es.startBrush(Map.of("size", 4, "color", "#000000"), null));
        }
        EditSession.OpResult r = es.startBrush(
                Map.of("size", 4, "color", "#000000"), null);
        assertEquals("TOO_MANY_STROKES", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void updateBrushPatchAllowsFillChange() {
        EditSession es = newSession();
        String sid = ((EditSession.OpResult.OkBrushStart) es.startBrush(
                Map.of("size", 4, "color", "#FF0000"), null)).strokeId();
        es.appendBrushPoints(sid, List.of(pt(10, 10, 0.5), pt(20, 20, 0.5)));
        es.endBrush(sid);
        String eid = es.state().elements().get(0).id();

        EditSession.OpResult r = es.updateElement(eid, Map.of(
                "fill", Map.of("type", "solid", "color", "#00FF00")
        ));
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        BrushStrokeElement bs = (BrushStrokeElement) es.state().elements().get(0);
        assertEquals(new SolidFill("#00FF00"), bs.fill());
    }

    @Test
    void brushStrokeSerializationRoundtrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);

        BrushStrokeElement orig = new BrushStrokeElement(
                "e-test", 10, 20, 100, 80, 0, false, true,
                List.of(pt(5, 5, 0.5), pt(50, 30, 0.8)),
                4,
                new SolidFill("#FF0000"),
                true, false,
                null, null, null);
        String json = mapper.writeValueAsString(orig);
        assertTrue(json.contains("\"type\":\"brush\""));
        assertTrue(json.contains("\"size\":4"));
        BrushStrokeElement bs = assertInstanceOf(BrushStrokeElement.class,
                mapper.readValue(json, Element.class));
        assertEquals(orig.size(), bs.size());
        assertEquals(2, bs.points().size());
        assertEquals(0.5, bs.points().get(0).pressure(), 1e-9);
        assertNotNull(bs.fill());
    }

    // ──────────────────────────────────────────────────────────
    //  过期笔触缓冲清理（purgeStaleStrokes）
    // ──────────────────────────────────────────────────────────

    @Nested
    class StaleStrokePurge {

        private static String startDefaultBrush(EditSession es) {
            return ((EditSession.OpResult.OkBrushStart) es.startBrush(
                    Map.of("size", 4, "color", "#FF0000"), null)).strokeId();
        }

        @Test
        void freshStrokeIsNotPurged() {
            EditSession es = newSession();
            String sid = startDefaultBrush(es);
            assertEquals(1, es.activeStrokeCountForTest());
            es.purgeStaleStrokes();
            assertEquals(1, es.activeStrokeCountForTest());
            es.cancelBrush(sid);
        }

        @Test
        void staleStrokeIsPurged() {
            EditSession es = newSession();
            String sid = startDefaultBrush(es);
            es.overrideStrokeActivityForTest(sid, 0);
            es.purgeStaleStrokes();
            assertEquals(0, es.activeStrokeCountForTest());
            EditSession.OpResult r = es.endBrush(sid);
            assertEquals("INVALID_STROKE",
                    assertInstanceOf(EditSession.OpResult.Error.class, r).code());
        }

        @Test
        void mixedStrokesPurgeOnlyStale() {
            EditSession es = newSession();
            String fresh = startDefaultBrush(es);
            String stale = startDefaultBrush(es);
            assertEquals(2, es.activeStrokeCountForTest());
            es.overrideStrokeActivityForTest(stale, 0);
            es.purgeStaleStrokes();
            assertEquals(1, es.activeStrokeCountForTest());
            es.appendBrushPoints(fresh, List.of(pt(10, 10, 0.5), pt(20, 20, 0.5)));
            assertInstanceOf(EditSession.OpResult.Ok.class, es.endBrush(fresh));
            assertInstanceOf(EditSession.OpResult.Error.class, es.endBrush(stale));
        }

        @Test
        void startBrushTriggersPurge() {
            EditSession es = newSession();
            String sid = startDefaultBrush(es);
            es.overrideStrokeActivityForTest(sid, 0);
            String fresh = startDefaultBrush(es);
            assertNotNull(fresh);
            assertEquals(1, es.activeStrokeCountForTest());
            es.cancelBrush(fresh);
        }
    }
}
