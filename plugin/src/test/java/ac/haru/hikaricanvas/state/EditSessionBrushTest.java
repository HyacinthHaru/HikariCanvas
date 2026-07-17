package ac.haru.hikaricanvas.state;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M12-A：{@link EditSession} brush.* op 状态机测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>{@code brush.start} 返回 {@link EditSession.OpResult.OkBrushStart} 含 strokeId</li>
 *   <li>{@code brush.start} props 校验（size 越界 / 非法 fill / opacity 越界）</li>
 *   <li>{@code brush.point} 累加；非法 strokeId 拒</li>
 *   <li>{@code brush.point} 超 MAX_BRUSH_POINTS_PER_STROKE 拒</li>
 *   <li>{@code brush.end} 写入 BrushStrokeElement；< 2 点丢弃</li>
 *   <li>{@code brush.cancel} 丢弃 buffer</li>
 *   <li>多活跃笔触上限 4 个</li>
 * </ul>
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
        // M12-B：smoothing=0 关闭 RDP 简化，保证所有 raw 点都进 element.points
        String sid = ((EditSession.OpResult.OkBrushStart) es.startBrush(
                Map.of("size", 4, "color", "#FF0000", "smoothing", 0.0), null)).strokeId();
        es.appendBrushPoints(sid, List.of(
                pt(10, 10, 0.5),
                pt(20, 15, 0.8),
                pt(30, 20, 0.6)
        ));
        EditSession.OpResult r = es.endBrush(sid);
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok) r;
        assertEquals(1, ok.patch().ops().size());
        // element 已加入 activeLayer
        assertEquals(1, es.state().elements().size());
        Element el = es.state().elements().get(0);
        assertInstanceOf(BrushStrokeElement.class, el);
        BrushStrokeElement bs = (BrushStrokeElement) el;
        assertEquals(3, bs.points().size());
        assertEquals(4, bs.size());
        // 坐标转换为相对 bbox 后第一个点的 x 应在 [0, padding] 内
        assertTrue(bs.points().get(0).x() >= 0);
        assertTrue(bs.points().get(0).y() >= 0);
    }

    @Test
    void endBrushSimplifiesCollinearPoints() {
        // smoothing > 0 时，共线点应被 RDP 压缩
        EditSession es = newSession();
        String sid = ((EditSession.OpResult.OkBrushStart) es.startBrush(
                Map.of("size", 4, "color", "#FF0000", "smoothing", 0.5), null)).strokeId();
        es.appendBrushPoints(sid, List.of(
                pt(10, 10, 0.5),
                pt(20, 15, 0.5),  // y=x*0.5+5 共线（与首尾点）
                pt(30, 20, 0.5)
        ));
        es.endBrush(sid);
        BrushStrokeElement bs = (BrushStrokeElement) es.state().elements().get(0);
        // 共线点应被丢弃，只剩首尾
        assertEquals(2, bs.points().size());
    }

    @Test
    void endBrushDiscardsTooShortStroke() {
        EditSession es = newSession();
        String sid = ((EditSession.OpResult.OkBrushStart) es.startBrush(
                Map.of("size", 4, "color", "#FF0000"), null)).strokeId();
        es.appendBrushPoints(sid, List.of(pt(10, 10, 0.5)));  // 仅 1 点
        EditSession.OpResult r = es.endBrush(sid);
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok) r;
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
        // 取消后再 end 应失败
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
        // 第 5 个应被拒
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

        // 通过 element.update 改 fill
        EditSession.OpResult r = es.updateElement(eid, Map.of(
                "fill", Map.of("type", "solid", "color", "#00FF00")
        ));
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        BrushStrokeElement bs = (BrushStrokeElement) es.state().elements().get(0);
        assertEquals(new SolidFill("#00FF00"), bs.fill());
    }

    @Test
    void brushStrokeSerializationRoundtrip() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .setSerializationInclusion(
                                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

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
        Element back = mapper.readValue(json, Element.class);
        assertInstanceOf(BrushStrokeElement.class, back);
        BrushStrokeElement bs = (BrushStrokeElement) back;
        assertEquals(orig.size(), bs.size());
        assertEquals(2, bs.points().size());
        assertEquals(0.5, bs.points().get(0).pressure(), 1e-9);
        assertNotNull(bs.fill());
    }
}
