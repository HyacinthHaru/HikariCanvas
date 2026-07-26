package ac.haru.hikaricanvas.state;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 关键帧唯一性不变量（{@code docs/timeline.md §2.1}）：
 * 一个 {@code (element, property, timeMs)} 上只允许存在一帧。
 *
 * <ul>
 *   <li>{@code keyframe.add} 撞上同键 → 覆盖那一帧，不追加</li>
 *   <li>{@code keyframe.move} 拖到同键已有帧上 → 被拖的胜出，原地那一帧删掉</li>
 * </ul>
 *
 * <p>没有这条不变量时的用户可见症状：同一时刻堆出多个帧、UI 上重叠成一个点，
 * 用户改的是被遮住的那一个 —— 「这个时刻怎么调都不动」，而且没有正常入口能删掉压在下面的帧。</p>
 *
 * <p>顺带守 w/h 关键帧的 MAX_DIM 闸（字符串形态曾经绕过）与 cubicBezier 的 y 幅度上限。</p>
 */
class KeyframeUniquenessTest {

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

    // ---------- add 去重 ----------

    /** 同 (property, timeMs) 再 add 一次 = 覆盖，不追加。 */
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

    /** 覆盖时保留原 keyframe id —— 前端手里的 id 不该在覆盖后失效。 */
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

    /** 不同 property 在同一 timeMs 上互不影响（它们是各自的轨）。 */
    @Test
    void addKeyframe_differentPropertySameTime_coexists() {
        EditSession es = newSession();
        String el = addText(es, "hi");
        String tl = newTimeline(es, 1000);

        es.addKeyframe(tl, el, "x", 500, 10, null, null);
        es.addKeyframe(tl, el, "y", 500, 20, null, null);
        assertEquals(2, track(es, el).size(), "x 与 y 是两条属性轨，同时刻可以各有一帧");
    }

    // ---------- move 撞帧 ----------

    /**
     * 把左边的帧拖到右边同 property 同 timeMs 的帧上：拖过来的胜出。
     *
     * <p>老实现用 {@code List.sort} 的稳定性想表达「相等排在已有之后」，
     * 但稳定排序只保持原相对顺序 —— 被拖的那个仍排在前面，取值按「重合取后」拿的是后面那个，
     * 用户刚拖进去的值直接被遮掉，画面纹丝不动。</p>
     */
    @Test
    void moveKeyframe_ontoExistingSameTime_movedFrameWins() {
        EditSession es = newSession();
        String el = addText(es, "hi");
        String tl = newTimeline(es, 1000);

        es.addKeyframe(tl, el, "x", 100, 11, null, null);   // 左：要被拖走的
        es.addKeyframe(tl, el, "x", 900, 99, null, null);   // 右：目标位上已有的
        String leftId = track(es, el).get(0).id();

        assertInstanceOf(EditSession.OpResult.Ok.class,
                es.moveKeyframe(tl, leftId, 900, null));

        List<Keyframe> t = track(es, el);
        assertEquals(1, t.size(), "撞帧后只应剩一帧，实际 " + t);
        assertEquals(leftId, t.get(0).id(), "胜出的必须是被拖过来的那一帧");
        assertEquals(11.0, ((KfValue.Num) t.get(0).value()).value(), 1e-9,
                "生效的值必须是被拖那一帧的值");
    }

    /** 拖到别的 property 占着的同一时刻不算撞帧，两帧都在。 */
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

    /** 常规 move（目标时刻没帧）仍按 timeMs 升序重排。 */
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

    // ---------- w/h MAX_DIM 闸覆盖字符串形态 ----------

    /** 数值形态早就挡住了。 */
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

    /**
     * 字符串形态以前完全绕过 MAX_DIM 闸（那道判断只在 Number 分支里），
     * 插值出的巨大 w×h 会流到未裁剪的分配路径。
     */
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

    /** 合法范围内的数值串照常通过。 */
    @Test
    void keyframe_numericStringWithinBound_accepted() {
        EditSession es = newSession();
        String el = addText(es, "hi");
        String tl = newTimeline(es, 1000);
        assertInstanceOf(EditSession.OpResult.Ok.class,
                es.addKeyframe(tl, el, "w", 0, "128", null, null));
    }

    /** {@code ${var:...}} 形态挡不住（值到渲染期才知道），但不能因此被误拒。 */
    @Test
    void keyframe_varTemplateForDimension_stillAccepted() {
        EditSession es = newSession();
        String el = addText(es, "hi");
        String tl = newTimeline(es, 1000);
        assertInstanceOf(EditSession.OpResult.Ok.class,
                es.addKeyframe(tl, el, "w", 0, "${var:user/w}", null, null));
    }

    // ---------- cubicBezier y 幅度上限 ----------

    /**
     * y 无界时 {@code cy = 3·y1} 会溢出成 Infinity → {@code ay} 变 NaN → eased = NaN，
     * 之后 Java 侧 {@code (int)NaN = 0} 而 JS 侧 {@code Math.round(NaN) = NaN}，
     * 直接破掉双端逐位等价（rendering.md §9.3）。
     */
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

    /** 正常的超调控制点（back 类曲线）必须照常通过。 */
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

    /** 边界值恰好 ±100 放行，101 拒。 */
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

    // ---------- 未知 easing type 不在反序列化期硬错 ----------

    /**
     * 版本降级打开（新版本存的墙回滚旧 jar）时，未知 easing type 必须降级成 LINEAR，
     * 而不是抛 InvalidFormatException 把整面墙的加载搞挂。
     */
    @Test
    void unknownEasingType_deserializesToLinear() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper m =
                new com.fasterxml.jackson.databind.ObjectMapper();
        Easing e = m.readValue("{\"type\":\"elasticOutFromTheFuture\"}", Easing.class);
        assertSame(EasingType.LINEAR, e.type(), "认不出的 type 应兜成 LINEAR");
    }

    /** 已知 type 照常解析。 */
    @Test
    void knownEasingType_stillParses() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper m =
                new com.fasterxml.jackson.databind.ObjectMapper();
        assertSame(EasingType.EASE_IN_OUT,
                m.readValue("{\"type\":\"easeInOut\"}", Easing.class).type());
        assertEquals("easeInOut",
                m.writeValueAsString(EasingType.EASE_IN_OUT).replace("\"", ""),
                "序列化 wire 形态不能被 @JsonCreator 带偏");
    }

    /** 未知 Fill type 的关键帧值同理：退 null，不炸整个 ProjectState。 */
    @Test
    void unknownFillTypeInKeyframeValue_deserializesToNull() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper m =
                new com.fasterxml.jackson.databind.ObjectMapper();
        KfValue v = m.readValue("{\"type\":\"plaidGradient\",\"color\":\"#fff\"}", KfValue.class);
        org.junit.jupiter.api.Assertions.assertNull(v,
                "未知 Fill type 应退 null 交插值器兜底，不能抛硬错");
    }
}
