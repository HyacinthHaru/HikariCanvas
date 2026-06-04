package moe.hikari.canvas.state;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.6 P1：通过 EditSession 的 keyframe.* op（add / update / delete / move）行为校验。
 *
 * <p>核心断言：</p>
 * <ul>
 *   <li>add 按 timeMs 升序插入 + 新轨 patch（整列表）+ 已有轨 patch（单帧）</li>
 *   <li>property 白名单拒 + timeMs 越界 INVALID_KEYFRAME_TIME + easing 校验 INVALID_EASING</li>
 *   <li>元素不存在 INVALID_ELEMENT + 轨道 256 上限拒</li>
 *   <li>update value + update timeMs 触发整轨 replace + KEYFRAME_NOT_FOUND</li>
 *   <li>delete 至轨空整轨剪除 patch</li>
 *   <li>move 连续多次（进程内 sub-ms，天然落 500ms 合并窗口）→ undo 一次回到第一次 move 前</li>
 *   <li>keyframe 值引用 {@code "${var:score}"} 字符串可存</li>
 * </ul>
 */
class EditSessionKeyframeTest {

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

    private static String newTimeline(EditSession es, int durationMs) {
        es.createTimeline("T", durationMs, 30, null, null);
        return es.state().timelines().get(0).id();
    }

    private static List<Keyframe> track(EditSession es, String elementId) {
        return es.state().timelines().get(0).tracks().get(elementId);
    }

    // ---------- keyframe.add ----------

    @Test
    void addSortsByTimeMs() {
        EditSession es = newSession();
        String tid = newTimeline(es, 5000);
        String eid = addText(es, "hi");

        // 乱序 add：500 / 100 / 300
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
        // 新轨：路径止于 tracks/<eid>，value 是整个 List
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
                es.addKeyframe(tid, eid, "x", 50, 5, null);   // 插到 index 0
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

        // cubicBezier 缺 bezier → INVALID_EASING
        assertEquals("INVALID_EASING",
                ((EditSession.OpResult.Error) es.addKeyframe(tid, eid, "x", 100, 1,
                        Map.of("type", "cubicBezier"))).code());
        // cubicBezier x 越界 → INVALID_EASING（x1 = 1.5）
        assertEquals("INVALID_EASING",
                ((EditSession.OpResult.Error) es.addKeyframe(tid, eid, "x", 100, 1,
                        Map.of("type", "cubicBezier",
                                "bezier", List.of(1.5, 0.0, 0.5, 1.0)))).code());
        // linear 带 bezier → INVALID_EASING
        assertEquals("INVALID_EASING",
                ((EditSession.OpResult.Error) es.addKeyframe(tid, eid, "x", 100, 1,
                        Map.of("type", "linear",
                                "bezier", List.of(0.0, 0.0, 1.0, 1.0)))).code());
        // 合法 cubicBezier → Ok
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
        // 填满 256（每帧不同 timeMs）
        for (int i = 0; i < 256; i++) {
            EditSession.OpResult r = es.addKeyframe(tid, eid, "x", i * 10, i, null);
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
        }
        assertEquals(256, track(es, eid).size());
        // 第 257 个 → 拒
        EditSession.OpResult over = es.addKeyframe(tid, eid, "x", 2600, 999, null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) over).code());
    }

    @Test
    void addStringValueForFillProperty() {
        EditSession es = newSession();
        String tid = newTimeline(es, 5000);
        String eid = addText(es, "hi");
        // color property = hex 字符串 → KfValue.Str
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
        // 数值类属性允许 ${var:X} 模板字符串（留口子）
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                es.addKeyframe(tid, eid, "opacity", 0, "${var:score}", null);
        assertNotNull(ok);
        Keyframe kf = track(es, eid).get(0);
        assertInstanceOf(KfValue.Str.class, kf.value());
        assertEquals("${var:score}", ((KfValue.Str) kf.value()).value());
    }

    // ---------- keyframe.update ----------

    @Test
    void updateValueSingleFramePatch() {
        EditSession es = newSession();
        String tid = newTimeline(es, 5000);
        String eid = addText(es, "hi");
        es.addKeyframe(tid, eid, "x", 100, 10, null);
        String kid = track(es, eid).get(0).id();

        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                es.updateKeyframe(tid, kid, Map.of("value", 99));
        // value 改、timeMs 不变 → 单帧 replace
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
        String first = track(es, eid).get(0).id();  // @100

        // 把 @100 挪到 @900 → 应排到末尾，整轨 replace
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                es.updateKeyframe(tid, first, Map.of("timeMs", 900));
        PatchOp op = ok.patch().ops().get(0);
        assertEquals("replace", op.op());
        assertEquals("/timelines/0/tracks/" + eid, op.path());   // 整轨路径
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

    // ---------- keyframe.delete ----------

    @Test
    void deleteToEmptyPrunesTrack() {
        EditSession es = newSession();
        String tid = newTimeline(es, 5000);
        String eid = addText(es, "hi");
        es.addKeyframe(tid, eid, "x", 100, 10, null);
        String kid = track(es, eid).get(0).id();

        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok) es.deleteKeyframe(tid, kid);
        // 轨道空 → 整轨剪除
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

    // ---------- keyframe.move（含 coalescing 集成）----------

    @Test
    void moveCoalescesUndoToBeforeFirstMove() {
        EditSession es = newSession();
        String tid = newTimeline(es, 5000);
        String eid = addText(es, "hi");
        es.addKeyframe(tid, eid, "x", 100, 10, null);
        String kid = track(es, eid).get(0).id();
        int beforeMove = track(es, eid).get(0).timeMs();   // 100

        // 连续多次 move（进程内 sub-ms → 同 coalesce key 且落 500ms 窗口 → 合并一步）
        assertInstanceOf(EditSession.OpResult.Ok.class, es.moveKeyframe(tid, kid, 200));
        assertInstanceOf(EditSession.OpResult.Ok.class, es.moveKeyframe(tid, kid, 300));
        assertInstanceOf(EditSession.OpResult.Ok.class, es.moveKeyframe(tid, kid, 400));
        assertEquals(400, track(es, eid).get(0).timeMs());

        // undo 一次 → 回到第一次 move 之前（timeMs 100），而非 300
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
}
