package moe.hikari.canvas.state;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.6 P1：通过 EditSession 的 timeline.* op（create / update / delete）行为校验。
 *
 * <p>核心断言：</p>
 * <ul>
 *   <li>create 默认值 + fps clamp（max-fps 截断）+ activeTimelineId 置新 id + patch 形态 + tl- 前缀</li>
 *   <li>第 17 条 create 拒绝（MAX_TIMELINES）</li>
 *   <li>update 各字段 + 未知键拒 + durationMs 缩短到 keyframe 之下拒 INVALID_KEYFRAME_TIME + TIMELINE_NOT_FOUND</li>
 *   <li>delete + active 转移 + patch 形态</li>
 *   <li>undo / redo 完整还原 timelines</li>
 * </ul>
 */
class EditSessionTimelineTest {

    private static EditSession newSession() {
        EditSession es = new EditSession(new ProjectState(2, 1));
        // 注入与 HikariCanvasConfig.TimelineConfig.defaults() 同值的 fps 配置
        es.setTimelineFpsLimits(20, 60);
        return es;
    }

    private static EditSession.OpResult.Ok createOk(EditSession es, String name,
                                                    Integer durationMs, Integer fps,
                                                    String loopMode, Map<String, Object> trigger) {
        EditSession.OpResult r = es.createTimeline(name, durationMs, fps, loopMode, trigger);
        return assertInstanceOf(EditSession.OpResult.Ok.class, r);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> timelineValueOf(EditSession.OpResult.Ok ok) {
        // create patch 第 0 条 = add /timelines/<i>，value 是 Timeline 序列化后的 Map
        return (Map<String, Object>) ok.patch().ops().get(0).value();
    }

    // ---------- timeline.create ----------

    @Test
    void createDefaultsAndFpsClamp() {
        EditSession es = newSession();
        // fps=120 越上限 → 静默截断到 60；name 留空 → 默认 "Timeline 1"
        EditSession.OpResult.Ok ok = createOk(es, null, 5000, 120, null, null);

        assertEquals(1, es.state().timelines().size());
        Timeline t = es.state().timelines().get(0);
        assertEquals("Timeline 1", t.name());
        assertEquals(5000, t.durationMs());
        assertEquals(60, t.fps());                  // clamp 到 maxFps
        assertEquals(LoopMode.LOOP, t.loopMode());  // 默认 LOOP
        assertEquals(TriggerType.MANUAL, t.trigger().type());
        assertTrue(t.id().startsWith("tl-"), "id 前缀 tl-");
        // activeTimelineId 置为新建 id
        assertEquals(t.id(), es.state().activeTimelineId());

        // patch 形态：add /timelines/0 + replace /activeTimelineId
        assertEquals(2, ok.patch().ops().size());
        assertEquals("add", ok.patch().ops().get(0).op());
        assertEquals("/timelines/0", ok.patch().ops().get(0).path());
        assertEquals("replace", ok.patch().ops().get(1).op());
        assertEquals("/activeTimelineId", ok.patch().ops().get(1).path());
        assertEquals(t.id(), ok.patch().ops().get(1).value());
        // timeline op 不产生像素变化
        assertNull(ok.dirty());

        Map<String, Object> val = timelineValueOf(ok);
        assertEquals(t.id(), val.get("id"));
        assertEquals(60, ((Number) val.get("fps")).intValue());
        assertEquals("loop", val.get("loopMode"));   // wire 形态
    }

    @Test
    void createFpsDefaultsWhenNull() {
        EditSession es = newSession();
        createOk(es, "Anim", 1000, null, "once", null);
        assertEquals(20, es.state().timelines().get(0).fps());   // null → defaultFps 20
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
        // 0.6 P5：VARIABLE_CHANGE / SCHEDULE 必须带 params.fullName，否则触发器无从匹配
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

    // ---------- timeline.update ----------

    @Test
    void updateFields() {
        EditSession es = newSession();
        createOk(es, "T", 2000, 30, "loop", null);
        String tid = es.state().timelines().get(0).id();

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("name", "Renamed");
        patch.put("durationMs", 3000);
        patch.put("fps", 90);               // 越上限 → clamp 60
        patch.put("loopMode", "once");
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class,
                es.updateTimeline(tid, patch));

        Timeline t = es.state().timelines().get(0);
        assertEquals("Renamed", t.name());
        assertEquals(3000, t.durationMs());
        assertEquals(60, t.fps());
        assertEquals(LoopMode.ONCE, t.loopMode());
        assertNull(ok.dirty());
        // per-field replace patch
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

    /**
     * P1 审查确认项：trigger 显式传 null 必须拒（不支持「null = 清字段」语义），
     * 不得静默把已有 trigger 重置成 manual。Map.of 不允许 null 值，用 LinkedHashMap 构造。
     */
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
        // all-or-nothing：原 trigger 不变
        assertEquals(TriggerType.VARIABLE_CHANGE,
                es.state().timelines().get(0).trigger().type());
    }

    /** P1 审查确认项：JSON 整数超出 int 范围不得静默回绕，应拒 INVALID_PAYLOAD。 */
    @Test
    void updateDurationLongOverflowRejected() {
        EditSession es = newSession();
        createOk(es, "T", 2000, 30, null, null);
        String tid = es.state().timelines().get(0).id();
        // 2^32 + 3000：intValue() 回绕后是 3000（合法范围内），守卫缺失时会被静默接受
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
        // 先加一个真实元素 + keyframe @ 4000ms
        String eid = addText(es, "hi");
        EditSession.OpResult kf = es.addKeyframe(tid, eid, "x", 4000, 10, null);
        assertInstanceOf(EditSession.OpResult.Ok.class, kf);
        // 把 duration 缩到 3000 < 4000 → 拒
        EditSession.OpResult r = es.updateTimeline(tid, Map.of("durationMs", 3000));
        assertEquals("INVALID_KEYFRAME_TIME", ((EditSession.OpResult.Error) r).code());
        // duration 仍是 5000（all-or-nothing）
        assertEquals(5000, es.state().timelines().get(0).durationMs());
    }

    // ---------- timeline.delete ----------

    @Test
    void deleteTransfersActive() {
        EditSession es = newSession();
        createOk(es, "A", 1000, 30, null, null);
        String a = es.state().timelines().get(0).id();
        createOk(es, "B", 1000, 30, null, null);
        String b = es.state().timelines().get(1).id();
        // activeTimelineId 现在指向 B（最后建的）
        assertEquals(b, es.state().activeTimelineId());

        // 删 B（活动）→ active 转移到剩余第一条 A
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class,
                es.deleteTimeline(b));
        assertEquals(1, es.state().timelines().size());
        assertEquals(a, es.state().activeTimelineId());
        // patch：remove /timelines/1 + replace /activeTimelineId = a
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

    // ---------- undo / redo ----------

    @Test
    void undoRedoRestoresTimelines() {
        EditSession es = newSession();
        createOk(es, "A", 1000, 30, null, null);
        String a = es.state().timelines().get(0).id();
        assertEquals(1, es.state().timelines().size());

        // undo → timelines 空 + activeTimelineId null
        assertInstanceOf(EditSession.OpResult.OkSnapshot.class, es.undo());
        assertTrue(es.state().timelines().isEmpty());
        assertNull(es.state().activeTimelineId());

        // redo → 回来（同 id）
        assertInstanceOf(EditSession.OpResult.OkSnapshot.class, es.redo());
        assertEquals(1, es.state().timelines().size());
        assertEquals(a, es.state().timelines().get(0).id());
        assertEquals(a, es.state().activeTimelineId());
    }

    @Test
    void fpsClampWithoutInjectionUsesFallback() {
        // 未注入 fps 配置（不调 setTimelineFpsLimits）→ 退常量缺省 20 / 60
        EditSession es = new EditSession(new ProjectState(2, 1));
        EditSession.OpResult.Ok ok = createOk(es, "T", 1000, 999, null, null);
        assertEquals(60, es.state().timelines().get(0).fps());
        assertNotNull(ok);
    }

    private static String addText(EditSession es, String content) {
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                es.addElement("text", Map.of("text", content), null, null);
        return ((Map<?, ?>) ok.patch().ops().get(0).value()).get("id").toString();
    }
}
