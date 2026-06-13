package moe.hikari.canvas.render;

import moe.hikari.canvas.state.BlendMode;
import moe.hikari.canvas.state.BrushPoint;
import moe.hikari.canvas.state.BrushStrokeElement;
import moe.hikari.canvas.state.CircleElement;
import moe.hikari.canvas.state.Easing;
import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.Fill;
import moe.hikari.canvas.state.IconElement;
import moe.hikari.canvas.state.ImageElement;
import moe.hikari.canvas.state.Keyframe;
import moe.hikari.canvas.state.KfValue;
import moe.hikari.canvas.state.Layer;
import moe.hikari.canvas.state.LoopMode;
import moe.hikari.canvas.state.PathElement;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.RectElement;
import moe.hikari.canvas.state.RenderMode;
import moe.hikari.canvas.state.ShapeElement;
import moe.hikari.canvas.state.TextElement;
import moe.hikari.canvas.state.Timeline;
import moe.hikari.canvas.state.TriggerConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KeyframeInterpolator} 纯函数单测（0.6 P2）。数学权威见 {@code docs/rendering.md §9}；
 * 管线定位见 {@code docs/architecture.md §5.5}。
 *
 * <p>三组覆盖：</p>
 * <ul>
 *   <li>{@code mapTime}：ONCE 钳 / LOOP 取模 / PING_PONG 三角波 / durationMs≤0 降级（rendering.md §9）</li>
 *   <li>{@code sampleNumeric}：边界不外插 / 区间 LINEAR 手算 / 重合帧取后 / 含 Str 整轨 null / 空轨 null</li>
 *   <li>{@code interpolate}：六数值属性应用 + 引用共享（assertSame）+ opacity 钳 + 四舍五入 +
 *       非数值轨跳过 + 输出去 timelines；{@code withAnimated} 8 子类各一 case</li>
 * </ul>
 *
 * <p>纯算术 —— 不依赖 Bukkit / DB / 时钟，同包访问 package-private 的
 * {@code sampleNumeric} / {@code withAnimated}。</p>
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
        // local = (250-0)/(1000-0) = 0.25 → 0 + 100*0.25 = 25
        assertEquals(25.0, KeyframeInterpolator.sampleNumeric(kfs, 250, null), 1e-9, "区间内 LINEAR 手算");
        assertEquals(50.0, KeyframeInterpolator.sampleNumeric(kfs, 500, null), 1e-9, "区间中点");
        // 三帧：区间 [1000,2000] 上 t=1750 → local=0.75 → 100+(40-100)*0.75 = 55
        List<Keyframe> three = List.of(numKf(0, 0.0), numKf(1000, 100.0), numKf(2000, 40.0));
        assertEquals(55.0, KeyframeInterpolator.sampleNumeric(three, 1750, null), 1e-9, "第二段 LINEAR 手算");
    }

    @Test
    void sampleNumeric_coincidentFramesTakeLater() {
        // 相邻 timeMs 相等（重合帧）：取后一帧值（落入重合组末位）
        List<Keyframe> kfs = List.of(numKf(0, 0.0), numKf(500, 10.0), numKf(500, 90.0), numKf(1000, 100.0));
        // t=500 命中重合组：返回最大 i 使 timeMs<=500 的帧，但 t 不是首/末帧 → 进区间逻辑；
        // i 落在第二个 500（index 2），区间 [500(idx2)=90, 1000=100]，local=(500-500)/(1000-500)=0 → 90
        assertEquals(90.0, KeyframeInterpolator.sampleNumeric(kfs, 500, null), 1e-9, "重合帧取后一帧值");
    }

    @Test
    void sampleNumeric_strValuesResolved() {
        // P3（§9.5）：Str 值参与求值——无 resolver 时变量引用退 0、纯数字字符串本地解析
        List<Keyframe> kfs = List.of(
                numKf(0, 0.0),
                new Keyframe("kf-str", "x", 500, KfValue.of("${var:foo}"), Easing.LINEAR),
                numKf(1000, 100.0));
        // 区间 [0,500)：0 → 0（${var:foo} 无 resolver 退 0），t=300 → 0
        assertEquals(0.0, KeyframeInterpolator.sampleNumeric(kfs, 300, null), 1e-9,
                "无 resolver 变量引用退 0");
        // 注入 resolver：${var:foo} → 50 → 区间 [0,500) 在 t=250 → 25
        assertEquals(25.0, KeyframeInterpolator.sampleNumeric(kfs, 250, raw -> 50.0), 1e-9,
                "resolver 注入后变量值参与插值");

        // 纯数字字符串无 resolver 也可用
        List<Keyframe> plain = List.of(
                new Keyframe("k0", "x", 0, KfValue.of("10"), Easing.LINEAR),
                new Keyframe("k1", "x", 1000, KfValue.of("20"), Easing.LINEAR));
        assertEquals(15.0, KeyframeInterpolator.sampleNumeric(plain, 500, null), 1e-9,
                "纯数字字符串本地解析");

        // 错型（FillV）仍整轨跳过
        List<Keyframe> bad = List.of(
                numKf(0, 0.0),
                new Keyframe("kf-fill", "x", 1000,
                        KfValue.of(moe.hikari.canvas.state.Fill.solid("#FF0000")), Easing.LINEAR));
        assertNull(KeyframeInterpolator.sampleNumeric(bad, 500, null), "FillV 错型 → 整轨 null");
    }

    @Test
    void sampleNumeric_emptyOrNullTrackReturnsNull() {
        assertNull(KeyframeInterpolator.sampleNumeric(List.of(), 100, null), "空轨 → null");
        assertNull(KeyframeInterpolator.sampleNumeric(null, 100, null), "null 轨 → null");
    }

    // ---------- interpolate ----------

    /** addText 风格的最小文本元素（六数值字段可被动画覆盖；其余字段保留校验用）。 */
    private static TextElement text(String id, int x, int y, int w, int h, int rotation, Float opacity) {
        return new TextElement(id, x, y, w, h, rotation, false, true,
                "hi", "inter", 24, "#000000", "left", 0f, 1.2f, false, null,
                opacity, BlendMode.NORMAL, RenderMode.CLEAN, null, null);
    }

    private static Layer layer(String id, Element... elements) {
        List<Element> list = new ArrayList<>(List.of(elements));
        return new Layer(id, "L", true, false, 1.0f, BlendMode.NORMAL, null, list);
    }

    private static ProjectState state(Layer... layers) {
        List<Layer> ls = new ArrayList<>(List.of(layers));
        // 6 字段构造：version / canvas / v1Elements / v2Layers / activeLayerId / history / timelines / activeTimelineId
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
        // 在 t=500（区间中点）每属性从初值线性到目标值
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
        Layer l2 = layer("l-2", text("e-3", 5, 5, 5, 5, 0, 1.0f));   // 整层无动画
        ProjectState base = state(l1, l2);
        Timeline tl = timeline(1000, "e-1",
                new Keyframe("k0", "x", 0, KfValue.of(0.0), Easing.LINEAR),
                new Keyframe("k1", "x", 1000, KfValue.of(200.0), Easing.LINEAR));

        ProjectState out = KeyframeInterpolator.interpolate(base, tl, 500);
        // 无动画的 element 引用共享
        assertSame(frozen, out.layers().get(0).elements().get(1), "同层未动画 element 按引用共享");
        // 无动画的整层引用共享
        assertSame(l2, out.layers().get(1), "无任何动画元素的 layer 按引用共享");
        // 被动画的 element 是新实例
        assertTrue(out.layers().get(0).elements().get(0) != animated, "被动画 element 应为新 record");
    }

    @Test
    void interpolate_noInterpolableTrackReturnsBaseSame() {
        TextElement t = text("e-1", 0, 0, 100, 50, 0, 1.0f);
        ProjectState base = state(layer("l-1", t));
        // 轨 key 指向不存在的元素 → animated 命中但无元素替换 → 返回 base 同引用
        Timeline tlNoElem = timeline(1000, "e-MISSING",
                new Keyframe("k0", "x", 0, KfValue.of(0.0), Easing.LINEAR),
                new Keyframe("k1", "x", 1000, KfValue.of(200.0), Easing.LINEAR));
        assertSame(base, KeyframeInterpolator.interpolate(base, tlNoElem, 500),
                "无可应用动画 → 返回 base 同一引用");

        // P3：color 轨已生效；改用「变量颜色轨」（P3 不支持 → 整轨跳过）验证 base 同引用
        Timeline tlVarColor = timeline(1000, "e-1",
                new Keyframe("kc", "color", 0, KfValue.of("${var:c}"), Easing.LINEAR));
        assertSame(base, KeyframeInterpolator.interpolate(base, tlVarColor, 500),
                "变量颜色轨 P3 跳过 → 返回 base 同一引用");

        // 空 tracks timeline → 直接返回 base
        Timeline empty = new Timeline("tl-e", "E", 1000, 20, LoopMode.LOOP, TriggerConfig.MANUAL, Map.of());
        assertSame(base, KeyframeInterpolator.interpolate(base, empty, 500),
                "空 tracks → 返回 base 同一引用");
    }

    @Test
    void interpolate_opacityClampedXyRounded() {
        TextElement t = text("e-1", 0, 0, 100, 50, 0, 1.0f);
        ProjectState base = state(layer("l-1", t));
        // opacity 目标 2.0（越界）→ 钳到 1.0；x 用非整中点检验四舍五入
        Timeline tl = timeline(1000, "e-1",
                new Keyframe("ko0", "opacity", 0, KfValue.of(2.0), Easing.LINEAR),
                new Keyframe("ko1", "opacity", 1000, KfValue.of(3.0), Easing.LINEAR),
                new Keyframe("kx0", "x", 0, KfValue.of(0.0), Easing.LINEAR),
                new Keyframe("kx1", "x", 1000, KfValue.of(101.0), Easing.LINEAR));
        ProjectState out = KeyframeInterpolator.interpolate(base, tl, 500);
        Element e = out.layers().get(0).elements().get(0);
        assertEquals(1.0f, e.effectiveOpacity(), 1e-6, "opacity 越界 → 钳 [0,1]");
        // x: 0→101 @0.5 = 50.5 → round = 51（HALF_UP）
        assertEquals(51, e.x(), "x=50.5 四舍五入到 51");
    }

    @Test
    void interpolate_colorTrackAppliesP3() {
        // 元素同时有数值轨（x）与 color 轨：P3 起两者都生效
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
        // P3：color 轨生效——#FF0000→#00FF00 中点（sRGB 线性空间，共享向量同源算法）
        assertEquals(moe.hikari.canvas.render.ColorLerp.lerpHex("#FF0000", "#00FF00", 0.5),
                e.color(), "P3 起 color 轨参与插值");
    }

    @Test
    void interpolate_outputDropsTimelines() {
        TextElement t = text("e-1", 0, 0, 100, 50, 0, 1.0f);
        // 给 base 一个 activeTimelineId + 含 timeline 的 state（走完整构造注入 timelines）
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

    /** 六数值属性全覆盖的 props，用于断言动画字段被改。 */
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
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN);
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
        // 仅给 x：y/w/h/rotation/opacity 应保留元素基值
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
}
