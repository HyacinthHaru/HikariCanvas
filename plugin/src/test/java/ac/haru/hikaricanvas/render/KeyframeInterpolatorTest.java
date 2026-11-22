package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.BlendMode;
import ac.haru.hikaricanvas.state.BrushPoint;
import ac.haru.hikaricanvas.state.BrushStrokeElement;
import ac.haru.hikaricanvas.state.CircleElement;
import ac.haru.hikaricanvas.state.Easing;
import ac.haru.hikaricanvas.state.EasingType;
import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.Fill;
import ac.haru.hikaricanvas.state.IconElement;
import ac.haru.hikaricanvas.state.ImageElement;
import ac.haru.hikaricanvas.state.Keyframe;
import ac.haru.hikaricanvas.state.KfValue;
import ac.haru.hikaricanvas.state.Layer;
import ac.haru.hikaricanvas.state.LinearGradient;
import ac.haru.hikaricanvas.state.LoopMode;
import ac.haru.hikaricanvas.state.PathElement;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.RectElement;
import ac.haru.hikaricanvas.state.RenderMode;
import ac.haru.hikaricanvas.state.ShapeElement;
import ac.haru.hikaricanvas.state.SolidFill;
import ac.haru.hikaricanvas.state.Stop;
import ac.haru.hikaricanvas.state.TextElement;
import ac.haru.hikaricanvas.state.Timeline;
import ac.haru.hikaricanvas.state.TriggerConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KeyframeInterpolator} 纯函数单测。数学权威见 {@code docs/rendering.md §9}；
 * 管线定位见 {@code docs/architecture.md §5.5}。
 *
 * <p>覆盖 {@code mapTime}、{@code sampleNumeric}、{@code sampleColor}、{@code sampleFill}、
 * {@code sampleText}、缓动接入与 {@code interpolate / withAnimated} 全子类语义。</p>
 */
class KeyframeInterpolatorTest {

    // ---------- mapTime ----------

    @Test
    void mapTime_onceClampsToRange() {
        assertEquals(0, KeyframeInterpolator.mapTime(-100L, 1000, LoopMode.ONCE), "负位置 → 钳到 0");
        assertEquals(0, KeyframeInterpolator.mapTime(0L, 1000, LoopMode.ONCE), "0 → 0");
        assertEquals(400, KeyframeInterpolator.mapTime(400L, 1000, LoopMode.ONCE), "中段原样");
        assertEquals(1000, KeyframeInterpolator.mapTime(1000L, 1000, LoopMode.ONCE), "恰好末尾 → durationMs");
        assertEquals(1000, KeyframeInterpolator.mapTime(5000L, 1000, LoopMode.ONCE), "越尾 → 钳末帧");
    }

    @Test
    void mapTime_loopWrapsModulo() {
        assertEquals(0, KeyframeInterpolator.mapTime(0L, 1000, LoopMode.LOOP), "0 → 0");
        assertEquals(300, KeyframeInterpolator.mapTime(300L, 1000, LoopMode.LOOP), "区间内原样");
        assertEquals(0, KeyframeInterpolator.mapTime(1000L, 1000, LoopMode.LOOP), "恰好 = duration → 取模回 0");
        assertEquals(250, KeyframeInterpolator.mapTime(2250L, 1000, LoopMode.LOOP), "多周期后取模");
        assertEquals(900, KeyframeInterpolator.mapTime(-100L, 1000, LoopMode.LOOP), "负位置 floorMod → 900");
    }

    @Test
    void mapTime_pingPongTriangle() {
        // 周期 2×duration = 2000，三角波 0 → 1000 → 0
        assertEquals(0, KeyframeInterpolator.mapTime(0L, 1000, LoopMode.PING_PONG), "起点");
        assertEquals(400, KeyframeInterpolator.mapTime(400L, 1000, LoopMode.PING_PONG), "上行段");
        assertEquals(1000, KeyframeInterpolator.mapTime(1000L, 1000, LoopMode.PING_PONG), "波峰");
        assertEquals(700, KeyframeInterpolator.mapTime(1300L, 1000, LoopMode.PING_PONG), "下行段：2000-1300=700");
        assertEquals(0, KeyframeInterpolator.mapTime(2000L, 1000, LoopMode.PING_PONG), "波谷（周期回 0）");
        // 跨多周期：5400 mod 2000 = 1400 → 下行 2000-1400 = 600
        assertEquals(600, KeyframeInterpolator.mapTime(5400L, 1000, LoopMode.PING_PONG), "跨多周期下行段");
        // 跨多周期上行：4300 mod 2000 = 300（≤ duration）→ 300
        assertEquals(300, KeyframeInterpolator.mapTime(4300L, 1000, LoopMode.PING_PONG), "跨多周期上行段");
    }

    @Test
    void mapTime_nonPositiveDurationReturnsZero() {
        assertEquals(0, KeyframeInterpolator.mapTime(500L, 0, LoopMode.LOOP), "durationMs=0 → 0");
        assertEquals(0, KeyframeInterpolator.mapTime(500L, -10, LoopMode.ONCE), "durationMs<0 → 0");
    }

    // ---------- sampleNumeric ----------

    private static Keyframe numKf(int timeMs, double value) {
        return new Keyframe("kf-" + timeMs, "x", timeMs, KfValue.of(value), Easing.LINEAR);
    }

    private static Keyframe kf(String prop, int timeMs, KfValue value) {
        return new Keyframe(prop + "-" + timeMs, prop, timeMs, value, Easing.LINEAR);
    }

    private static Keyframe kf(String prop, int timeMs, KfValue value, Easing easing) {
        return new Keyframe(prop + "-" + timeMs, prop, timeMs, value, easing);
    }

    @Test
    void sampleNumeric_beforeFirstAndAfterLastNoExtrapolation() {
        List<Keyframe> kfs = List.of(numKf(100, 10.0), numKf(300, 30.0));
        assertEquals(10.0, KeyframeInterpolator.sampleNumeric(kfs, 0, null), 1e-9, "首帧前 → 首帧值");
        assertEquals(10.0, KeyframeInterpolator.sampleNumeric(kfs, 100, null), 1e-9, "正好首帧 → 首帧值");
        assertEquals(30.0, KeyframeInterpolator.sampleNumeric(kfs, 300, null), 1e-9, "正好末帧 → 末帧值");
        assertEquals(30.0, KeyframeInterpolator.sampleNumeric(kfs, 9999, null), 1e-9, "末帧后 → 末帧值（不外插）");
    }

    @Test
    void sampleNumeric_linearWithinSegment() {
        List<Keyframe> kfs = List.of(numKf(0, 0.0), numKf(1000, 100.0));
        assertEquals(25.0, KeyframeInterpolator.sampleNumeric(kfs, 250, null), 1e-9, "区间内 LINEAR 手算");
        assertEquals(50.0, KeyframeInterpolator.sampleNumeric(kfs, 500, null), 1e-9, "区间中点");
        List<Keyframe> three = List.of(numKf(0, 0.0), numKf(1000, 100.0), numKf(2000, 40.0));
        assertEquals(55.0, KeyframeInterpolator.sampleNumeric(three, 1750, null), 1e-9, "第二段 LINEAR 手算");
    }

    @Test
    void sampleNumeric_coincidentFramesTakeLater() {
        List<Keyframe> kfs = List.of(numKf(0, 0.0), numKf(500, 10.0), numKf(500, 90.0), numKf(1000, 100.0));
        assertEquals(90.0, KeyframeInterpolator.sampleNumeric(kfs, 500, null), 1e-9, "重合帧取后一帧值");
    }

    @Test
    void sampleNumeric_strValuesResolved() {
        // rendering.md §9.5：Str 值参与求值——无 resolver 时变量引用退 0、纯数字字符串本地解析
        List<Keyframe> kfs = List.of(
                numKf(0, 0.0),
                new Keyframe("kf-str", "x", 500, KfValue.of("${var:foo}"), Easing.LINEAR),
                numKf(1000, 100.0));
        assertEquals(0.0, KeyframeInterpolator.sampleNumeric(kfs, 300, null), 1e-9,
                "无 resolver 变量引用退 0");
        assertEquals(25.0, KeyframeInterpolator.sampleNumeric(kfs, 250, raw -> 50.0), 1e-9,
                "resolver 注入后变量值参与插值");

        List<Keyframe> plain = List.of(
                new Keyframe("k0", "x", 0, KfValue.of("10"), Easing.LINEAR),
                new Keyframe("k1", "x", 1000, KfValue.of("20"), Easing.LINEAR));
        assertEquals(15.0, KeyframeInterpolator.sampleNumeric(plain, 500, null), 1e-9,
                "纯数字字符串本地解析");

        List<Keyframe> bad = List.of(
                numKf(0, 0.0),
                new Keyframe("kf-fill", "x", 1000,
                        KfValue.of(Fill.solid("#FF0000")), Easing.LINEAR));
        assertNull(KeyframeInterpolator.sampleNumeric(bad, 500, null), "FillV 错型 → 整轨 null");
    }

    @Test
    void sampleNumeric_emptyOrNullTrackReturnsNull() {
        assertNull(KeyframeInterpolator.sampleNumeric(List.of(), 100, null), "空轨 → null");
        assertNull(KeyframeInterpolator.sampleNumeric(null, 100, null), "null 轨 → null");
    }

    // ---------- interpolate ----------

    /** 最小文本元素（六数值字段可被动画覆盖；其余字段保留校验用）。 */
    private static TextElement text(String id, int x, int y, int w, int h, int rotation, Float opacity) {
        return new TextElement(id, x, y, w, h, rotation, false, true,
                "hi", "inter", 24, "#000000", "left", 0f, 1.2f, false, null,
                opacity, BlendMode.NORMAL, RenderMode.CLEAN, null, null);
    }

    private static TextElement text(String id) {
        return text(id, 0, 0, 100, 50, 0, 1.0f);
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
                new ProjectState.History(0, 0), null, null, null);
    }

    /** 单元素单轨 timeline；tracks key = elementId，value = 该元素的混排关键帧。 */
    private static Timeline timeline(int durationMs, String elementId, Keyframe... kfs) {
        Map<String, List<Keyframe>> tracks = new LinkedHashMap<>();
        tracks.put(elementId, List.of(kfs));
        return new Timeline("tl-1", "T", durationMs, 20, LoopMode.LOOP, TriggerConfig.MANUAL, tracks);
    }

    @Test
    void interpolate_appliesSixNumericProperties() {
        TextElement t = text("e-1", 0, 0, 100, 50, 0, 1.0f);
        ProjectState base = state(layer("l-1", t));
        Timeline tl = timeline(1000, "e-1",
                new Keyframe("k-x0", "x", 0, KfValue.of(0.0), Easing.LINEAR),
                new Keyframe("k-x1", "x", 1000, KfValue.of(200.0), Easing.LINEAR),
                new Keyframe("k-y0", "y", 0, KfValue.of(0.0), Easing.LINEAR),
                new Keyframe("k-y1", "y", 1000, KfValue.of(80.0), Easing.LINEAR),
                new Keyframe("k-w0", "w", 0, KfValue.of(100.0), Easing.LINEAR),
                new Keyframe("k-w1", "w", 1000, KfValue.of(300.0), Easing.LINEAR),
                new Keyframe("k-h0", "h", 0, KfValue.of(50.0), Easing.LINEAR),
                new Keyframe("k-h1", "h", 1000, KfValue.of(150.0), Easing.LINEAR),
                new Keyframe("k-r0", "rotation", 0, KfValue.of(0.0), Easing.LINEAR),
                new Keyframe("k-r1", "rotation", 1000, KfValue.of(90.0), Easing.LINEAR),
                new Keyframe("k-o0", "opacity", 0, KfValue.of(0.0), Easing.LINEAR),
                new Keyframe("k-o1", "opacity", 1000, KfValue.of(1.0), Easing.LINEAR));

        ProjectState out = KeyframeInterpolator.interpolate(base, tl, 500);
        Element e = out.layers().get(0).elements().get(0);
        assertEquals(100, e.x(), "x: 0→200 @0.5 = 100");
        assertEquals(40, e.y(), "y: 0→80 @0.5 = 40");
        assertEquals(200, e.w(), "w: 100→300 @0.5 = 200");
        assertEquals(100, e.h(), "h: 50→150 @0.5 = 100");
        assertEquals(45, e.rotation(), "rotation: 0→90 @0.5 = 45");
        assertEquals(0.5f, e.effectiveOpacity(), 1e-6, "opacity: 0→1 @0.5 = 0.5");
    }

    @Test
    void interpolate_unanimatedElementsAndLayersShareReference() {
        TextElement animated = text("e-1", 0, 0, 100, 50, 0, 1.0f);
        TextElement frozen = text("e-2", 10, 10, 20, 20, 0, 1.0f);
        Layer l1 = layer("l-1", animated, frozen);
        Layer l2 = layer("l-2", text("e-3", 5, 5, 5, 5, 0, 1.0f));
        ProjectState base = state(l1, l2);
        Timeline tl = timeline(1000, "e-1",
                new Keyframe("k0", "x", 0, KfValue.of(0.0), Easing.LINEAR),
                new Keyframe("k1", "x", 1000, KfValue.of(200.0), Easing.LINEAR));

        ProjectState out = KeyframeInterpolator.interpolate(base, tl, 500);
        assertSame(frozen, out.layers().get(0).elements().get(1), "同层未动画 element 按引用共享");
        assertSame(l2, out.layers().get(1), "无任何动画元素的 layer 按引用共享");
        assertTrue(out.layers().get(0).elements().get(0) != animated, "被动画 element 应为新 record");
    }

    @Test
    void interpolate_noInterpolableTrackReturnsBaseSame() {
        TextElement t = text("e-1", 0, 0, 100, 50, 0, 1.0f);
        ProjectState base = state(layer("l-1", t));
        Timeline tlNoElem = timeline(1000, "e-MISSING",
                new Keyframe("k0", "x", 0, KfValue.of(0.0), Easing.LINEAR),
                new Keyframe("k1", "x", 1000, KfValue.of(200.0), Easing.LINEAR));
        assertSame(base, KeyframeInterpolator.interpolate(base, tlNoElem, 500),
                "无可应用动画 → 返回 base 同一引用");

        // 变量颜色轨不支持插值 → 整轨跳过，验证返回 base 同引用
        Timeline tlVarColor = timeline(1000, "e-1",
                new Keyframe("kc", "color", 0, KfValue.of("${var:c}"), Easing.LINEAR));
        assertSame(base, KeyframeInterpolator.interpolate(base, tlVarColor, 500),
                "变量颜色轨跳过 → 返回 base 同一引用");

        Timeline empty = new Timeline("tl-e", "E", 1000, 20, LoopMode.LOOP, TriggerConfig.MANUAL, Map.of());
        assertSame(base, KeyframeInterpolator.interpolate(base, empty, 500),
                "空 tracks → 返回 base 同一引用");
    }

    @Test
    void interpolate_opacityClampedXyRounded() {
        TextElement t = text("e-1", 0, 0, 100, 50, 0, 1.0f);
        ProjectState base = state(layer("l-1", t));
        Timeline tl = timeline(1000, "e-1",
                new Keyframe("ko0", "opacity", 0, KfValue.of(2.0), Easing.LINEAR),
                new Keyframe("ko1", "opacity", 1000, KfValue.of(3.0), Easing.LINEAR),
                new Keyframe("kx0", "x", 0, KfValue.of(0.0), Easing.LINEAR),
                new Keyframe("kx1", "x", 1000, KfValue.of(101.0), Easing.LINEAR));
        ProjectState out = KeyframeInterpolator.interpolate(base, tl, 500);
        Element e = out.layers().get(0).elements().get(0);
        assertEquals(1.0f, e.effectiveOpacity(), 1e-6, "opacity 越界 → 钳 [0,1]");
        assertEquals(51, e.x(), "x=50.5 四舍五入到 51");
    }

    @Test
    void interpolate_colorAndNumericTracksApplyTogether() {
        TextElement t = text("e-1", 0, 0, 100, 50, 0, 1.0f);
        ProjectState base = state(layer("l-1", t));
        Timeline tl = timeline(1000, "e-1",
                new Keyframe("kc0", "color", 0, KfValue.of("#FF0000"), Easing.LINEAR),
                new Keyframe("kc1", "color", 1000, KfValue.of("#00FF00"), Easing.LINEAR),
                new Keyframe("kx0", "x", 0, KfValue.of(0.0), Easing.LINEAR),
                new Keyframe("kx1", "x", 1000, KfValue.of(60.0), Easing.LINEAR));
        ProjectState out = KeyframeInterpolator.interpolate(base, tl, 500);
        TextElement e = (TextElement) out.layers().get(0).elements().get(0);
        assertEquals(30, e.x(), "x 数值轨生效");
        assertEquals(ColorLerp.lerpHex("#FF0000", "#00FF00", 0.5),
                e.color(), "color 轨参与插值");
    }

    @Test
    void interpolate_outputDropsTimelines() {
        TextElement t = text("e-1", 0, 0, 100, 50, 0, 1.0f);
        Map<String, List<Keyframe>> tracks = new LinkedHashMap<>();
        tracks.put("e-1", List.of(
                new Keyframe("k0", "x", 0, KfValue.of(0.0), Easing.LINEAR),
                new Keyframe("k1", "x", 1000, KfValue.of(200.0), Easing.LINEAR)));
        Timeline tl = new Timeline("tl-1", "T", 1000, 20, LoopMode.LOOP, TriggerConfig.MANUAL, tracks);
        List<Layer> ls = new ArrayList<>(List.of(layer("l-1", t)));
        ProjectState base = new ProjectState(1L,
                new ProjectState.Canvas(2, 2, Fill.solid("#FFFFFF")),
                null, ls, "l-1", new ProjectState.History(0, 0),
                List.of(tl), "tl-1", null);
        assertEquals(1, base.timelines().size(), "前置：base 含一条 timeline");

        ProjectState out = KeyframeInterpolator.interpolate(base, tl, 500);
        assertTrue(out != base, "应产出新 state");
        assertTrue(out.timelines().isEmpty(), "输出临时 state 不含 timelines");
        assertNull(out.activeTimelineId(), "输出临时 state 不含 activeTimelineId");
    }

    // ---------- withAnimated：8 子类各一 case ----------

    private static KeyframeInterpolator.AnimatedValues allProps() {
        Map<String, Double> p = new LinkedHashMap<>();
        p.put("x", 11.0);
        p.put("y", 22.0);
        p.put("w", 33.0);
        p.put("h", 44.0);
        p.put("rotation", 55.0);
        p.put("opacity", 0.5);
        KeyframeInterpolator.AnimatedValues v = new KeyframeInterpolator.AnimatedValues();
        v.numbers = p;
        return v;
    }

    private static void assertNumericApplied(Element e) {
        assertEquals(11, e.x());
        assertEquals(22, e.y());
        assertEquals(33, e.w());
        assertEquals(44, e.h());
        assertEquals(55, e.rotation());
        assertEquals(0.5f, e.effectiveOpacity(), 1e-6);
    }

    @Test
    void withAnimated_textPreservesNonNumericFields() {
        TextElement src = new TextElement("e", 1, 2, 3, 4, 5, false, true,
                "hello", "inter", 24, "#123456", "center", 1.5f, 1.3f, true, null,
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN, Boolean.TRUE, Boolean.FALSE);
        TextElement out = (TextElement) KeyframeInterpolator.withAnimated(src, allProps());
        assertNumericApplied(out);
        assertEquals("hello", out.text(), "text 保留");
        assertEquals("inter", out.fontId(), "fontId 保留");
        assertEquals(24, out.fontSize(), "fontSize 保留");
        assertEquals("#123456", out.color(), "color 保留");
        assertEquals("center", out.align(), "align 保留");
        assertEquals(Boolean.TRUE, out.bold(), "bold 保留");
        assertEquals(Boolean.FALSE, out.italic(), "italic 保留");
    }

    @Test
    void withAnimated_rectPreservesFillStroke() {
        Fill fill = Fill.solid("#ABCDEF");
        RectElement src = new RectElement("e", 1, 2, 3, 4, 5, false, true,
                fill, null, 1.0f, BlendMode.MULTIPLY, RenderMode.DITHER);
        RectElement out = (RectElement) KeyframeInterpolator.withAnimated(src, allProps());
        assertNumericApplied(out);
        assertSame(fill, out.fill(), "fill 保留");
        assertEquals(BlendMode.MULTIPLY, out.blendMode(), "blendMode 保留");
        assertEquals(RenderMode.DITHER, out.renderMode(), "renderMode 保留");
    }

    @Test
    void withAnimated_iconPreservesSourceFill() {
        Fill fill = Fill.solid("#FF8800");
        IconElement src = new IconElement("e", 1, 2, 3, 4, 5, false, true,
                "fa-solid/heart", null, 1.0f, BlendMode.NORMAL, RenderMode.CLEAN, fill);
        IconElement out = (IconElement) KeyframeInterpolator.withAnimated(src, allProps());
        assertNumericApplied(out);
        assertEquals("fa-solid/heart", out.source(), "source 保留");
        assertSame(fill, out.fill(), "fill 保留");
    }

    @Test
    void withAnimated_pathPreservesDAndMarkers() {
        PathElement src = new PathElement("e", 1, 2, 3, 4, 5, false, true,
                "M0 0 L10 10 Z", Fill.solid("#000000"), null, "arrow", "dot",
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null);
        PathElement out = (PathElement) KeyframeInterpolator.withAnimated(src, allProps());
        assertNumericApplied(out);
        assertEquals("M0 0 L10 10 Z", out.d(), "d 保留");
        assertEquals("arrow", out.markerStart(), "markerStart 保留");
        assertEquals("dot", out.markerEnd(), "markerEnd 保留");
    }

    @Test
    void withAnimated_circlePreservesFill() {
        Fill fill = Fill.solid("#00AAFF");
        CircleElement src = new CircleElement("e", 1, 2, 3, 4, 5, false, true,
                fill, null, 1.0f, BlendMode.NORMAL, RenderMode.CLEAN);
        CircleElement out = (CircleElement) KeyframeInterpolator.withAnimated(src, allProps());
        assertNumericApplied(out);
        assertSame(fill, out.fill(), "fill 保留");
    }

    @Test
    void withAnimated_shapePreservesKindSidesInnerRatio() {
        ShapeElement src = new ShapeElement("e", 1, 2, 3, 4, 5, false, true,
                "star", 7, 0.4f, Fill.solid("#FFFF00"), null,
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN);
        ShapeElement out = (ShapeElement) KeyframeInterpolator.withAnimated(src, allProps());
        assertNumericApplied(out);
        assertEquals("star", out.kind(), "kind 保留");
        assertEquals(7, out.sides(), "sides 保留");
        assertEquals(0.4f, out.innerRatio(), 1e-6, "innerRatio 保留");
    }

    @Test
    void withAnimated_brushPreservesPointsSizePressure() {
        List<BrushPoint> pts = List.of(new BrushPoint(0, 0, 0.5), new BrushPoint(10, 10, 0.7));
        BrushStrokeElement src = new BrushStrokeElement("e", 1, 2, 3, 4, 5, false, true,
                pts, 8, Fill.solid("#222222"), true, false,
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN);
        BrushStrokeElement out = (BrushStrokeElement) KeyframeInterpolator.withAnimated(src, allProps());
        assertNumericApplied(out);
        assertSame(pts, out.points(), "points 保留");
        assertEquals(8, out.size(), "size 保留");
        assertTrue(out.pressureSize(), "pressureSize 保留");
    }

    @Test
    void withAnimated_imagePreservesSourceMask() {
        ImageElement src = new ImageElement("e", 1, 2, 3, 4, 5, false, true,
                "0123456789abcdef", null, 1.0f, BlendMode.NORMAL, RenderMode.CLEAN);
        ImageElement out = (ImageElement) KeyframeInterpolator.withAnimated(src, allProps());
        assertNumericApplied(out);
        assertEquals("0123456789abcdef", out.source(), "source 保留");
        assertNull(out.mask(), "mask 保留 null");
    }

    @Test
    void withAnimated_partialPropsKeepUnlistedFromBase() {
        TextElement src = text("e", 7, 8, 9, 10, 11, 0.3f);
        KeyframeInterpolator.AnimatedValues v = new KeyframeInterpolator.AnimatedValues();
        v.numbers = Map.of("x", 99.0);
        Element out = KeyframeInterpolator.withAnimated(src, v);
        assertEquals(99, out.x(), "x 被覆盖");
        assertEquals(8, out.y(), "y 保留基值");
        assertEquals(9, out.w(), "w 保留基值");
        assertEquals(10, out.h(), "h 保留基值");
        assertEquals(11, out.rotation(), "rotation 保留基值");
        assertEquals(0.3f, out.effectiveOpacity(), 1e-6, "opacity 保留基值");
    }

    @Test
    void interpolate_nullArgsReturnBase() {
        TextElement t = text("e-1", 0, 0, 100, 50, 0, 1.0f);
        ProjectState base = state(layer("l-1", t));
        assertNull(KeyframeInterpolator.interpolate(null, timeline(1000, "e-1"), 0),
                "base null → 返回 base(null)");
        assertSame(base, KeyframeInterpolator.interpolate(base, null, 0),
                "timeline null → 返回 base");
        assertNotNull(base, "前置 sanity");
    }

    /**
     * color / fill / text 离散轨与缓动曲线端到端单测（数学权威见 {@code docs/rendering.md §9.2 / §9.4}）。
     */
    @Nested
    class ColorFillTextAndEasing {

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
            assertEquals(ColorLerp.lerpHex("#FF0000", "#0000FF", 0.5),
                    KeyframeInterpolator.sampleColor(kfs, 500), "区间中点 sRGB 线性空间");
        }

        @Test
        void sampleColor_coincidentFramesTakeLater() {
            List<Keyframe> kfs = List.of(
                    kf("color", 0, KfValue.of("#000000")),
                    kf("color", 500, KfValue.of("#111111")),
                    kf("color", 500, KfValue.of("#FF0000")),
                    kf("color", 1000, KfValue.of("#FFFFFF")));
            assertEquals("#FF0000", KeyframeInterpolator.sampleColor(kfs, 500), "重合帧取后一帧色");
        }

        @Test
        void sampleColor_variableTrackReturnsNull() {
            List<Keyframe> kfs = List.of(
                    kf("color", 0, KfValue.of("#FF0000")),
                    kf("color", 1000, KfValue.of("${var:theme}")));
            assertNull(KeyframeInterpolator.sampleColor(kfs, 500), "含变量颜色 → 整轨 null");
        }

        @Test
        void sampleColor_nonStrValueReturnsNull() {
            List<Keyframe> kfs = List.of(
                    kf("color", 0, KfValue.of("#FF0000")),
                    kf("color", 1000, KfValue.of(42.0)));
            assertNull(KeyframeInterpolator.sampleColor(kfs, 500), "含 Num 错型 → 整轨 null");
        }

        @Test
        void sampleColor_illegalHexStepsToLeft() {
            List<Keyframe> kfs = List.of(
                    kf("color", 0, KfValue.of("#FF0000")),
                    kf("color", 1000, KfValue.of("not-a-color")));
            assertEquals("#FF0000", KeyframeInterpolator.sampleColor(kfs, 500),
                    "右端非法 hex → lerpHex 内 step 返左端");
        }

        @Test
        void sampleFill_fillVAndStrHexNormalized() {
            List<Keyframe> kfs = List.of(
                    kf("fill", 0, KfValue.of("#FF0000")),
                    kf("fill", 1000, KfValue.of(new SolidFill("#0000FF"))));
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
            assertEquals(ColorLerp.lerpHex("#000000", "#FFFFFF", 0.5),
                    lg.stops().get(0).color(), "linear stop[0] 颜色同源");
        }

        @Test
        void sampleFill_typeMismatchSteps() {
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
            List<Keyframe> kfs = List.of(
                    kf("fill", 0, KfValue.of("#FF0000")),
                    kf("fill", 1000, KfValue.of("${var:bg}")));
            assertNull(KeyframeInterpolator.sampleFill(kfs, 500), "含变量 fill → 整轨 null");
        }

        @Test
        void sampleText_stepSemantics() {
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
            assertEquals(im, e, "image 的 fill/color 轨错配静默忽略 → 重建 record 各字段保持原值");
        }

        @Test
        void interpolate_easingAffectsNumericValue() {
            TextElement t = text("e-1");
            ProjectState base = state(layer("l-1", t));
            Easing easeIn = new Easing(EasingType.EASE_IN, null);
            Timeline tl = timeline(1000, "e-1",
                    kf("x", 0, KfValue.of(0.0), easeIn),
                    kf("x", 1000, KfValue.of(200.0)));
            ProjectState out = KeyframeInterpolator.interpolate(base, tl, 500);
            Element e = out.layers().get(0).elements().get(0);
            double eased = EasingSolver.ease(easeIn, 0.5);
            int expectedX = (int) Math.round(200.0 * eased);
            assertEquals(expectedX, e.x(), "easeIn 接入：x 走缓动曲线");
            assertTrue(e.x() < 100, "easeIn @0.5 应慢于线性中点（x<100）");
            assertTrue(eased < 0.5, "前置 sanity：easeIn(0.5) < 0.5");
        }

        /**
         * 全等重合帧（损坏 blob 防御面——id/timeMs/value 全同的两帧）下取值不得错位：
         * Span 自带索引，确保区间左端落在重合组末位，与 TS 端索引语义一致。
         */
        @Test
        void sampleNumeric_identicalCoincidentFramesNoMisalignment() {
            Keyframe dup1 = new Keyframe("dup", "x", 500, KfValue.of(999.0), Easing.LINEAR);
            Keyframe dup2 = new Keyframe("dup", "x", 500, KfValue.of(999.0), Easing.LINEAR);
            List<Keyframe> kfs = List.of(
                    new Keyframe("a", "x", 0, KfValue.of(0.0), Easing.LINEAR),
                    dup1, dup2,
                    new Keyframe("z", "x", 1000, KfValue.of(1000.0), Easing.LINEAR));
            assertEquals(999.2, KeyframeInterpolator.sampleNumeric(kfs, 600, null), 1e-9,
                    "全等重合帧不得经 equals 反查错位");
        }

        @Test
        void interpolate_largeNumericValueClampsToIntRange() {
            // 超 int 范围的有限浮点关键帧值经 clampInt 钳到 Integer.MAX_VALUE，避免窄化回绕成负数。
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
}
