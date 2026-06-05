package moe.hikari.canvas.render;

import moe.hikari.canvas.state.BlendMode;
import moe.hikari.canvas.state.Easing;
import moe.hikari.canvas.state.EasingType;
import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.Fill;
import moe.hikari.canvas.state.ImageElement;
import moe.hikari.canvas.state.Keyframe;
import moe.hikari.canvas.state.KfValue;
import moe.hikari.canvas.state.Layer;
import moe.hikari.canvas.state.LinearGradient;
import moe.hikari.canvas.state.LoopMode;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.RectElement;
import moe.hikari.canvas.state.RenderMode;
import moe.hikari.canvas.state.SolidFill;
import moe.hikari.canvas.state.Stop;
import moe.hikari.canvas.state.TextElement;
import moe.hikari.canvas.state.Timeline;
import moe.hikari.canvas.state.TriggerConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KeyframeInterpolator} P3 新增轨（color / fill / text 离散）+ 缓动接入端到端单测。
 * 数学权威见 {@code docs/rendering.md §9.2 / §9.4}；P2 的 mapTime / sampleNumeric / withAnimated
 * 行为已由同包 {@code KeyframeInterpolatorTest} 覆盖，本类只补 P3 新增面。
 *
 * <p>纯算术 —— 同包访问 package-private 的 {@code sampleColor / sampleFill / sampleText}；
 * color / fill 的 expected 颜色用 {@link ColorLerp} 同源算（共享向量同算法），避免 magic hex。</p>
 */
class KeyframeInterpolatorP3Test {

    // ──────────────────────────────────────────────────────────
    //  helper：自建一套构造范式（不复用 KeyframeInterpolatorTest 的私有 helper）
    // ──────────────────────────────────────────────────────────

    private static Keyframe kf(String prop, int timeMs, KfValue value) {
        return new Keyframe(prop + "-" + timeMs, prop, timeMs, value, Easing.LINEAR);
    }

    private static Keyframe kf(String prop, int timeMs, KfValue value, Easing easing) {
        return new Keyframe(prop + "-" + timeMs, prop, timeMs, value, easing);
    }

    private static TextElement text(String id) {
        return new TextElement(id, 0, 0, 100, 50, 0, false, true,
                "hi", "inter", 24, "#000000", "left", 0f, 1.2f, false, null,
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null, null);
    }

    private static RectElement rect(String id) {
        return new RectElement(id, 0, 0, 100, 50, 0, false, true,
                Fill.solid("#112233"), null, 1.0f, BlendMode.NORMAL, RenderMode.CLEAN);
    }

    private static ImageElement image(String id) {
        return new ImageElement(id, 0, 0, 100, 50, 0, false, true,
                "0123456789abcdef", null, 1.0f, BlendMode.NORMAL, RenderMode.CLEAN);
    }

    private static Layer layer(String id, Element... elements) {
        List<Element> list = new ArrayList<>(List.of(elements));
        return new Layer(id, "L", true, false, 1.0f, BlendMode.NORMAL, null, list);
    }

    private static ProjectState state(Layer... layers) {
        List<Layer> ls = new ArrayList<>(List.of(layers));
        return new ProjectState(1L,
                new ProjectState.Canvas(2, 2, Fill.solid("#FFFFFF")),
                null, ls, layers[0].id(),
                new ProjectState.History(0, 0), null, null);
    }

    private static Timeline timeline(int durationMs, String elementId, Keyframe... kfs) {
        Map<String, List<Keyframe>> tracks = new LinkedHashMap<>();
        tracks.put(elementId, List.of(kfs));
        return new Timeline("tl-1", "T", durationMs, 20, LoopMode.LOOP, TriggerConfig.MANUAL, tracks);
    }

    // ──────────────────────────────────────────────────────────
    //  sampleColor
    // ──────────────────────────────────────────────────────────

    @Test
    void sampleColor_boundariesTakeEndFrames() {
        List<Keyframe> kfs = List.of(
                kf("color", 100, KfValue.of("#FF0000")),
                kf("color", 300, KfValue.of("#00FF00")));
        assertEquals("#FF0000", KeyframeInterpolator.sampleColor(kfs, 0), "首帧前 → 首帧色");
        assertEquals("#FF0000", KeyframeInterpolator.sampleColor(kfs, 100), "正好首帧");
        assertEquals("#00FF00", KeyframeInterpolator.sampleColor(kfs, 300), "正好末帧");
        assertEquals("#00FF00", KeyframeInterpolator.sampleColor(kfs, 9999), "末帧后（不外插）");
    }

    @Test
    void sampleColor_withinSegmentMatchesColorLerp() {
        List<Keyframe> kfs = List.of(
                kf("color", 0, KfValue.of("#FF0000")),
                kf("color", 1000, KfValue.of("#0000FF")));
        // local=0.5 → 与 ColorLerp 同源（共享向量同算法）
        assertEquals(ColorLerp.lerpHex("#FF0000", "#0000FF", 0.5),
                KeyframeInterpolator.sampleColor(kfs, 500), "区间中点 sRGB 线性空间");
    }

    @Test
    void sampleColor_coincidentFramesTakeLater() {
        // 重合帧组取后位（spanAt 末位语义）
        List<Keyframe> kfs = List.of(
                kf("color", 0, KfValue.of("#000000")),
                kf("color", 500, KfValue.of("#111111")),
                kf("color", 500, KfValue.of("#FF0000")),
                kf("color", 1000, KfValue.of("#FFFFFF")));
        // t=500 落入第二个 500（index 2），区间 [#FF0000, #FFFFFF]，local=0 → #FF0000
        assertEquals("#FF0000", KeyframeInterpolator.sampleColor(kfs, 500), "重合帧取后一帧色");
    }

    @Test
    void sampleColor_variableTrackReturnsNull() {
        // 含 ${var:} 整轨 null（P3 不支持变量颜色）
        List<Keyframe> kfs = List.of(
                kf("color", 0, KfValue.of("#FF0000")),
                kf("color", 1000, KfValue.of("${var:theme}")));
        assertNull(KeyframeInterpolator.sampleColor(kfs, 500), "含变量颜色 → 整轨 null");
    }

    @Test
    void sampleColor_nonStrValueReturnsNull() {
        // 非 Str（Num）整轨 null
        List<Keyframe> kfs = List.of(
                kf("color", 0, KfValue.of("#FF0000")),
                kf("color", 1000, KfValue.of(42.0)));
        assertNull(KeyframeInterpolator.sampleColor(kfs, 500), "含 Num 错型 → 整轨 null");
    }

    @Test
    void sampleColor_illegalHexStepsToLeft() {
        // 非法 hex 在 lerpHex 内 step（返左端）——轨本身全是 Str，不整轨跳过
        List<Keyframe> kfs = List.of(
                kf("color", 0, KfValue.of("#FF0000")),
                kf("color", 1000, KfValue.of("not-a-color")));
        assertEquals("#FF0000", KeyframeInterpolator.sampleColor(kfs, 500),
                "右端非法 hex → lerpHex 内 step 返左端");
    }

    // ──────────────────────────────────────────────────────────
    //  sampleFill
    // ──────────────────────────────────────────────────────────

    @Test
    void sampleFill_fillVAndStrHexNormalized() {
        // FillV 与 Str hex 混排：Str hex 归一化为 SolidFill
        List<Keyframe> kfs = List.of(
                kf("fill", 0, KfValue.of("#FF0000")),                         // Str → SolidFill
                kf("fill", 1000, KfValue.of(new SolidFill("#0000FF"))));      // FillV
        Fill out = KeyframeInterpolator.sampleFill(kfs, 500);
        assertInstanceOf(SolidFill.class, out);
        assertEquals(ColorLerp.lerpHex("#FF0000", "#0000FF", 0.5),
                ((SolidFill) out).color(), "Str hex 归一化后与 FillV 同样插值");
    }

    @Test
    void sampleFill_linearSameStopCountMidpoint() {
        LinearGradient g0 = new LinearGradient(0.0, List.of(
                new Stop(0.0, "#000000"), new Stop(1.0, "#FF0000")));
        LinearGradient g1 = new LinearGradient(90.0, List.of(
                new Stop(0.0, "#FFFFFF"), new Stop(1.0, "#00FF00")));
        List<Keyframe> kfs = List.of(
                kf("fill", 0, KfValue.of(g0)),
                kf("fill", 1000, KfValue.of(g1)));
        Fill out = KeyframeInterpolator.sampleFill(kfs, 500);
        assertInstanceOf(LinearGradient.class, out);
        LinearGradient lg = (LinearGradient) out;
        assertEquals(45.0, lg.angle(), 1e-9, "angle 线性中点");
        // 手算一个 stop 颜色用 ColorLerp 同源断言
        assertEquals(ColorLerp.lerpHex("#000000", "#FFFFFF", 0.5),
                lg.stops().get(0).color(), "linear stop[0] 颜色同源");
    }

    @Test
    void sampleFill_typeMismatchSteps() {
        // 类型不一致 → lerpFill 内 step（返左端 a）
        List<Keyframe> kfs = List.of(
                kf("fill", 0, KfValue.of(new SolidFill("#FF0000"))),
                kf("fill", 1000, KfValue.of(new LinearGradient(0.0, List.of(
                        new Stop(0.0, "#000000"), new Stop(1.0, "#FFFFFF"))))));
        Fill out = KeyframeInterpolator.sampleFill(kfs, 500);
        assertInstanceOf(SolidFill.class, out);
        assertEquals("#FF0000", ((SolidFill) out).color(), "类型不一致 → step 返左端 fill");
    }

    @Test
    void sampleFill_variableTrackReturnsNull() {
        // 含变量 → 整轨跳过
        List<Keyframe> kfs = List.of(
                kf("fill", 0, KfValue.of("#FF0000")),
                kf("fill", 1000, KfValue.of("${var:bg}")));
        assertNull(KeyframeInterpolator.sampleFill(kfs, 500), "含变量 fill → 整轨 null");
    }

    // ──────────────────────────────────────────────────────────
    //  sampleText（离散 step）
    // ──────────────────────────────────────────────────────────

    @Test
    void sampleText_stepSemantics() {
        // step：t 在两帧间取前帧；首帧前取首帧
        List<Keyframe> kfs = List.of(
                kf("text", 100, KfValue.of("A")),
                kf("text", 500, KfValue.of("B")),
                kf("text", 900, KfValue.of("C")));
        assertEquals("A", KeyframeInterpolator.sampleText(kfs, 0), "首帧前 → 首帧");
        assertEquals("A", KeyframeInterpolator.sampleText(kfs, 100), "正好首帧");
        assertEquals("A", KeyframeInterpolator.sampleText(kfs, 300), "两帧间取前帧（不插值）");
        assertEquals("B", KeyframeInterpolator.sampleText(kfs, 500), "正好第二帧");
        assertEquals("B", KeyframeInterpolator.sampleText(kfs, 700), "第二段取前帧");
        assertEquals("C", KeyframeInterpolator.sampleText(kfs, 900), "末帧");
        assertEquals("C", KeyframeInterpolator.sampleText(kfs, 9999), "末帧后取末帧");
    }

    @Test
    void sampleText_keepsVariableLiteral() {
        // 内容含 ${var:X}：sampleText 不 resolve（交 rasterize 的 maybeInterpolateText）
        List<Keyframe> kfs = List.of(
                kf("text", 0, KfValue.of("score=${var:user/x}")),
                kf("text", 1000, KfValue.of("done")));
        assertEquals("score=${var:user/x}", KeyframeInterpolator.sampleText(kfs, 500),
                "text 轨保留变量字面，不在插值期 resolve");
    }

    @Test
    void sampleText_nonStrReturnsNull() {
        List<Keyframe> kfs = List.of(
                kf("text", 0, KfValue.of("A")),
                kf("text", 1000, KfValue.of(1.0)));
        assertNull(KeyframeInterpolator.sampleText(kfs, 500), "含 Num 错型 → 整轨 null");
    }

    // ──────────────────────────────────────────────────────────
    //  interpolate 端到端
    // ──────────────────────────────────────────────────────────

    @Test
    void interpolate_textColorAndTextTracksApply() {
        TextElement t = text("e-1");
        ProjectState base = state(layer("l-1", t));
        Timeline tl = timeline(1000, "e-1",
                kf("color", 0, KfValue.of("#FF0000")),
                kf("color", 1000, KfValue.of("#0000FF")),
                kf("text", 0, KfValue.of("start")),
                kf("text", 1000, KfValue.of("end")));
        ProjectState out = KeyframeInterpolator.interpolate(base, tl, 500);
        TextElement e = (TextElement) out.layers().get(0).elements().get(0);
        assertEquals(ColorLerp.lerpHex("#FF0000", "#0000FF", 0.5), e.color(), "color 轨生效");
        assertEquals("start", e.text(), "text 离散轨 step 取前帧（@500 区间 [0,1000) 取首帧）");
    }

    @Test
    void interpolate_rectFillTrackApplies() {
        RectElement r = rect("e-1");
        ProjectState base = state(layer("l-1", r));
        Timeline tl = timeline(1000, "e-1",
                kf("fill", 0, KfValue.of("#FF0000")),
                kf("fill", 1000, KfValue.of("#0000FF")));
        ProjectState out = KeyframeInterpolator.interpolate(base, tl, 500);
        RectElement e = (RectElement) out.layers().get(0).elements().get(0);
        assertInstanceOf(SolidFill.class, e.fill());
        assertEquals(ColorLerp.lerpHex("#FF0000", "#0000FF", 0.5),
                ((SolidFill) e.fill()).color(), "rect fill 轨生效");
    }

    @Test
    void interpolate_imageFillAndColorTracksIgnored() {
        // ImageElement 无 fill / color 字段：错配轨静默忽略（withAnimated 重建 image 但不应用 fill/color）。
        // 注意：fill/color 轨求值非 null → animated 非空 → withAnimated 仍重建 record（新实例），
        // 但 image record 各字段保持原值 → 值相等。
        ImageElement im = image("e-1");
        ProjectState base = state(layer("l-1", im));
        Timeline tl = timeline(1000, "e-1",
                kf("fill", 0, KfValue.of("#FF0000")),
                kf("fill", 1000, KfValue.of("#0000FF")),
                kf("color", 0, KfValue.of("#000000")),
                kf("color", 1000, KfValue.of("#FFFFFF")));
        ProjectState out = KeyframeInterpolator.interpolate(base, tl, 500);
        Element e = out.layers().get(0).elements().get(0);
        assertInstanceOf(ImageElement.class, e);
        // fill/color 对 image 无适用字段——错配静默忽略 → 重建后 record 与原 image 值相等
        assertEquals(im, e, "image 的 fill/color 轨错配静默忽略 → 重建 record 各字段保持原值");
    }

    @Test
    void interpolate_easingAffectsNumericValue() {
        // easeIn 在 t=0.5 的 x 值 ≠ 线性中点；用 EasingSolver.ease 同源算 expected
        TextElement t = text("e-1");
        ProjectState base = state(layer("l-1", t));
        Easing easeIn = new Easing(EasingType.EASE_IN, null);
        Timeline tl = timeline(1000, "e-1",
                kf("x", 0, KfValue.of(0.0), easeIn),
                kf("x", 1000, KfValue.of(200.0)));
        ProjectState out = KeyframeInterpolator.interpolate(base, tl, 500);
        Element e = out.layers().get(0).elements().get(0);
        // local=0.5 → eased = EasingSolver.ease(easeIn, 0.5)；x = round(0 + 200*eased)
        double eased = EasingSolver.ease(easeIn, 0.5);
        int expectedX = (int) Math.round(200.0 * eased);
        assertEquals(expectedX, e.x(), "easeIn 接入：x 走缓动曲线");
        // 哨兵：与线性中点 100 不同（easeIn @0.5 < 0.5）
        assertTrue(e.x() < 100, "easeIn @0.5 应慢于线性中点（x<100）");
        assertTrue(eased < 0.5, "前置 sanity：easeIn(0.5) < 0.5");
    }

    /**
     * P3 审查确认项 #0/#4 回归：全等重合帧（损坏 blob 防御面——id/timeMs/value 全同的
     * 两帧）下取值不得错位。修复前 Java 用 equals 反查 indexOf 命中第一个全等帧，
     * 与 TS 端 ai 索引分叉（核验者实证：t=600 时 Java 999.0 vs TS 999.2）；修复后
     * Span 自带索引，与 TS 同构。
     */
    @Test
    void sampleNumeric_identicalCoincidentFramesNoMisalignment() {
        Keyframe dup1 = new Keyframe("dup", "x", 500, KfValue.of(999.0), Easing.LINEAR);
        Keyframe dup2 = new Keyframe("dup", "x", 500, KfValue.of(999.0), Easing.LINEAR);
        List<Keyframe> kfs = List.of(
                new Keyframe("a", "x", 0, KfValue.of(0.0), Easing.LINEAR),
                dup1, dup2,
                new Keyframe("z", "x", 1000, KfValue.of(1000.0), Easing.LINEAR));
        // t=600：区间左端 = 重合组末位（index 2，值 999），右端 index 3（值 1000），
        // local = (600-500)/(1000-500) = 0.2 → 999 + 1×0.2 = 999.2（TS 同语义）
        assertEquals(999.2, KeyframeInterpolator.sampleNumeric(kfs, 600, null), 1e-9,
                "全等重合帧不得经 equals 反查错位");
    }

    @Test
    void interpolate_largeNumericValueClampsToIntRange() {
        // P3 审查 #2：x 关键帧值 3e9（finite，op 层只校验 isFinite 放行）→ round 后超 int；
        // ix 不钳会 (int) 收窄回绕成负数而 TS 不回绕，双端分叉。clampInt 钳到 Integer.MAX_VALUE。
        RectElement r = rect("r1");
        ProjectState base = state(layer("L", r));
        Timeline tl = timeline(1000, "r1",
                kf("x", 0, KfValue.of(3_000_000_000.0)),
                kf("x", 1000, KfValue.of(3_000_000_000.0)));
        ProjectState out = KeyframeInterpolator.interpolate(base, tl, 500);
        Element e = out.layers().get(0).elements().get(0);
        assertEquals(Integer.MAX_VALUE, e.x(), "x=3e9 钳到 Integer.MAX_VALUE（非回绕负值）");
    }
}
