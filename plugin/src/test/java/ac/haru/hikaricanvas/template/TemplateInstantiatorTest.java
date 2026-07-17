package ac.haru.hikaricanvas.template;

import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.RectElement;
import ac.haru.hikaricanvas.state.SolidFill;
import ac.haru.hikaricanvas.state.TextElement;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M6-C 实例化引擎测试。覆盖：
 * <ul>
 *   <li>param 校验 / 必填 / default 填充 / 类型 coerce</li>
 *   <li>canvas size=fixed 必须匹配；size=auto 必须 [min,max] 范围内</li>
 *   <li>stack 布局累加；free 布局百分比解析</li>
 *   <li>visible_when 不可见元素 skip</li>
 *   <li>${param} 在 content/color/background 中插值</li>
 *   <li>spec §10 subway_station 完整 fixture 走通</li>
 *   <li>C1-P2-11: 常规布局 materialize 后跑元素级校验（超长文本/越界坐标/越界尺寸）</li>
 *   <li>C2-P2-12: resolveDimension 超 int 数字串不回绕、不外泄 JDK 报文；超大百分比不回绕</li>
 * </ul>
 */
class TemplateInstantiatorTest {

    private final TemplateLoader loader = new TemplateLoader();
    private final TemplateInstantiator instantiator = new TemplateInstantiator();

    private TemplateSpec parse(String yaml) {
        TemplateLoader.Result r = loader.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        if (r instanceof TemplateLoader.Result.Failed f) {
            fail("template parse failed: " + f.detail());
        }
        return ((TemplateLoader.Result.Ok) r).spec();
    }

    private static Map<String, Object> params(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static TemplateInstantiator.Result.Ok ok(TemplateInstantiator.Result r) {
        if (r instanceof TemplateInstantiator.Result.Failed f) {
            fail("expected ok but failed " + f.code() + ": " + f.errors());
        }
        return (TemplateInstantiator.Result.Ok) r;
    }

    private static TemplateInstantiator.Result.Failed failed(TemplateInstantiator.Result r) {
        assertInstanceOf(TemplateInstantiator.Result.Failed.class, r);
        return (TemplateInstantiator.Result.Failed) r;
    }

    @Test
    void instantiatesMinimalText() {
        TemplateSpec spec = parse("""
                spec: 1
                id: hello_min
                name: hello
                canvas:
                  size: auto
                  background: "#FFFFFF"
                params:
                  greeting:
                    type: string
                    label: greeting
                    default: "Hi"
                layout:
                  type: stack
                  direction: vertical
                  elements:
                    - type: text
                      content: "${greeting} world"
                      size: 24
                      color: "#000000"
                """);
        var r = ok(instantiator.instantiate(spec, params(), 4, 1));
        assertEquals("#FFFFFF", r.backgroundColor());
        assertEquals(1, r.elements().size());
        TextElement t = assertInstanceOf(TextElement.class, r.elements().get(0));
        assertEquals("Hi world", t.text());
        // stack 撑满 content 宽度（无 padding，contentW = 4×128 = 512）
        assertEquals(512, t.w());
    }

    @Test
    void requiredParamMissing() {
        TemplateSpec spec = parse("""
                spec: 1
                id: req_param
                name: req
                canvas: { size: auto }
                params:
                  must:
                    type: string
                    label: x
                    required: true
                layout:
                  type: stack
                  elements:
                    - type: text
                      content: "${must}"
                      size: 12
                      color: "#000000"
                """);
        var f = failed(instantiator.instantiate(spec, params(), 2, 1));
        assertEquals("INVALID_PARAM", f.code());
        assertTrue(f.errors().toString().contains("required"));
    }

    @Test
    void defaultFillsMissingParam() {
        TemplateSpec spec = parse("""
                spec: 1
                id: default_fill
                name: deff
                canvas: { size: auto }
                params:
                  bg:
                    type: color
                    label: bg
                    default: "#112233"
                layout:
                  type: stack
                  elements:
                    - type: text
                      content: hi
                      size: 12
                      color: "${bg}"
                """);
        var r = ok(instantiator.instantiate(spec, params(), 2, 1));
        TextElement t = (TextElement) r.elements().get(0);
        assertEquals("#112233", t.color());
    }

    @Test
    void fixedCanvasMustMatchWall() {
        TemplateSpec spec = parse("""
                spec: 1
                id: fixed_canvas
                name: fixed
                canvas:
                  size: fixed
                  maps: [4, 1]
                layout:
                  type: stack
                  elements:
                    - type: text
                      content: hi
                      size: 12
                      color: "#000000"
                """);
        // wall 不是 4x1 → 拒
        var f = failed(instantiator.instantiate(spec, params(), 3, 1));
        assertEquals("CANVAS_MISMATCH", f.code());
        // wall 是 4x1 → 通过
        ok(instantiator.instantiate(spec, params(), 4, 1));
    }

    @Test
    void autoCanvasRespectsBounds() {
        TemplateSpec spec = parse("""
                spec: 1
                id: auto_range
                name: ar
                canvas:
                  size: auto
                  min_maps: [2, 1]
                  max_maps: [6, 2]
                layout:
                  type: stack
                  elements:
                    - type: text
                      content: hi
                      size: 12
                      color: "#000000"
                """);
        assertEquals("CANVAS_MISMATCH",
                failed(instantiator.instantiate(spec, params(), 1, 1)).code());
        assertEquals("CANVAS_MISMATCH",
                failed(instantiator.instantiate(spec, params(), 8, 1)).code());
        ok(instantiator.instantiate(spec, params(), 4, 1));
    }

    @Test
    void stackVerticalAccumulates() {
        TemplateSpec spec = parse("""
                spec: 1
                id: stack_v
                name: stack vertical
                canvas:
                  size: auto
                  padding: 0
                layout:
                  type: stack
                  direction: vertical
                  gap: 4
                  elements:
                    - type: rect
                      h: 12
                      fill: "#FF0000"
                    - type: text
                      content: hi
                      size: 24
                      color: "#000000"
                """);
        var r = ok(instantiator.instantiate(spec, params(), 4, 1));
        assertEquals(2, r.elements().size());
        RectElement rect = (RectElement) r.elements().get(0);
        TextElement txt = (TextElement) r.elements().get(1);
        // rect y=0, h=12; text y = 12 + 4(gap) = 16
        assertEquals(0, rect.y());
        assertEquals(12, rect.h());
        assertEquals(16, txt.y());
        // text 自然高度 = 24 * 1.2 * 1line = 28.8 → ceil = 29
        assertEquals(29, txt.h());
    }

    @Test
    void freeLayoutPercentResolvesAgainstContent() {
        TemplateSpec spec = parse("""
                spec: 1
                id: free_pct
                name: free
                canvas:
                  size: auto
                  padding: 0
                layout:
                  type: free
                  elements:
                    - type: rect
                      x: 0
                      y: 0
                      w: 50%
                      h: 100%
                      fill: "#FF0000"
                """);
        var r = ok(instantiator.instantiate(spec, params(), 4, 1));
        RectElement rect = (RectElement) r.elements().get(0);
        // canvas 4x1 = 512x128, 0 padding → contentW=512 contentH=128
        assertEquals(256, rect.w());  // 50% of 512
        assertEquals(128, rect.h());  // 100% of 128
    }

    @Test
    void visibleWhenSkipsHidden() {
        TemplateSpec spec = parse("""
                spec: 1
                id: visible_when_demo
                name: visible_when
                canvas: { size: auto }
                params:
                  show:
                    type: bool
                    label: show
                    default: false
                layout:
                  type: stack
                  elements:
                    - type: text
                      content: visible
                      size: 12
                      color: "#000000"
                    - type: text
                      content: hidden
                      size: 12
                      color: "#000000"
                      visible_when: "show == true"
                """);
        var r = ok(instantiator.instantiate(spec, params(), 2, 1));
        assertEquals(1, r.elements().size());
        assertEquals("visible", ((TextElement) r.elements().get(0)).text());

        var r2 = ok(instantiator.instantiate(spec, params("show", true), 2, 1));
        assertEquals(2, r2.elements().size());
    }

    @Test
    void visibleLiteralFalseSkips() {
        TemplateSpec spec = parse("""
                spec: 1
                id: visible_literal
                name: visible literal
                canvas: { size: auto }
                layout:
                  type: stack
                  elements:
                    - type: text
                      content: keep
                      size: 12
                      color: "#000000"
                    - type: text
                      content: drop
                      size: 12
                      color: "#000000"
                      visible: false
                """);
        var r = ok(instantiator.instantiate(spec, params(), 2, 1));
        assertEquals(1, r.elements().size());
        assertEquals("keep", ((TextElement) r.elements().get(0)).text());
    }

    @Test
    void backgroundInterpolation() {
        TemplateSpec spec = parse("""
                spec: 1
                id: bg_demo
                name: bg
                canvas:
                  size: auto
                  background: "${tint}"
                params:
                  tint:
                    type: color
                    label: tint
                    default: "#ABCDEF"
                layout:
                  type: stack
                  elements:
                    - type: text
                      content: hi
                      size: 12
                      color: "#000000"
                """);
        var r = ok(instantiator.instantiate(spec, params(), 2, 1));
        assertEquals("#ABCDEF", r.backgroundColor());
    }

    @Test
    void paramTypeCoerceBool() {
        TemplateSpec spec = parse("""
                spec: 1
                id: param_bool
                name: pb
                canvas: { size: auto }
                params:
                  flag:
                    type: bool
                    label: flag
                layout:
                  type: stack
                  elements:
                    - type: text
                      content: hi
                      size: 12
                      color: "#000000"
                      visible_when: "flag"
                """);
        // 字符串 "true" 应被 coerce 为 boolean
        var r = ok(instantiator.instantiate(spec, params("flag", "true"), 2, 1));
        assertEquals(1, r.elements().size());
        // 字符串 "false" 应可见性=false
        var r2 = ok(instantiator.instantiate(spec, params("flag", "false"), 2, 1));
        assertEquals(0, r2.elements().size());
    }

    @Test
    void paramRangeViolation() {
        TemplateSpec spec = parse("""
                spec: 1
                id: range_check
                name: rng
                canvas: { size: auto }
                params:
                  n:
                    type: int
                    label: n
                    min: 1
                    max: 10
                layout:
                  type: stack
                  elements:
                    - type: text
                      content: "${n}"
                      size: 12
                      color: "#000000"
                """);
        assertEquals("INVALID_PARAM",
                failed(instantiator.instantiate(spec, params("n", 99), 2, 1)).code());
        assertEquals("INVALID_PARAM",
                failed(instantiator.instantiate(spec, params("n", 0), 2, 1)).code());
        ok(instantiator.instantiate(spec, params("n", 5), 2, 1));
    }

    @Test
    void rectInStackNeedsExplicitH() {
        TemplateSpec spec = parse("""
                spec: 1
                id: rect_stack
                name: rect stack
                canvas: { size: auto }
                layout:
                  type: stack
                  elements:
                    - type: rect
                      fill: "#FF0000"
                """);
        // 没显式 h → INVALID_LAYOUT
        assertEquals("INVALID_LAYOUT",
                failed(instantiator.instantiate(spec, params(), 2, 1)).code());
    }

    @Test
    void subwayStationFixture() {
        TemplateSpec spec = parse("""
                spec: 1
                id: subway_station
                name: 地铁站牌
                canvas:
                  size: auto
                  min_maps: [3, 1]
                  max_maps: [8, 2]
                  background: "#FFFFFF"
                  padding: [8, 8, 8, 8]
                params:
                  name:
                    type: string
                    label: 站名
                    required: true
                    max_length: 8
                  line_color:
                    type: color
                    label: 线路色
                    default: "#E4002B"
                  show_english:
                    type: bool
                    label: 显示英文
                    default: true
                  english_name:
                    type: string
                    label: 英文站名
                    required: false
                    max_length: 32
                    visible_when: "show_english == true"
                layout:
                  type: stack
                  direction: vertical
                  gap: 4
                  elements:
                    - type: rect
                      h: 12
                      fill: "${line_color}"
                    - type: text
                      content: "${name}"
                      font: sourcehan
                      size: 48
                      color: "#000000"
                      align: center
                    - type: text
                      content: "${english_name}"
                      font: sourcehan
                      size: 16
                      color: "#666666"
                      align: center
                      visible_when: "show_english == true"
                """);
        var r = ok(instantiator.instantiate(spec,
                params("name", "人民广场", "english_name", "People's Square"), 4, 1));
        assertEquals("#FFFFFF", r.backgroundColor());
        assertEquals(3, r.elements().size());
        RectElement bar = (RectElement) r.elements().get(0);
        assertEquals(new SolidFill("#E4002B"), bar.fill());
        // padding 8 → content 起点 y=8
        assertEquals(8, bar.y());
        assertEquals(12, bar.h());
        // text 在 bar 下 12 + 4 gap = 24
        TextElement zh = (TextElement) r.elements().get(1);
        assertEquals(8 + 12 + 4, zh.y());
        assertEquals("人民广场", zh.text());
        assertEquals(48, zh.fontSize());
        // english 显示
        TextElement en = (TextElement) r.elements().get(2);
        assertEquals("People's Square", en.text());
        assertEquals(16, en.fontSize());
        // english_name 隐藏路径
        var r2 = ok(instantiator.instantiate(spec,
                params("name", "Hide", "show_english", false), 4, 1));
        assertEquals(2, r2.elements().size());
    }

    @Test
    void enumOptionsEnforced() {
        TemplateSpec spec = parse("""
                spec: 1
                id: enum_demo
                name: enum demo
                canvas: { size: auto }
                params:
                  mode:
                    type: enum
                    label: mode
                    options:
                      - { label: A, value: a }
                      - { label: B, value: b }
                layout:
                  type: stack
                  elements:
                    - type: text
                      content: "${mode}"
                      size: 12
                      color: "#000000"
                """);
        assertEquals("INVALID_PARAM",
                failed(instantiator.instantiate(spec, params("mode", "c"), 2, 1)).code());
        ok(instantiator.instantiate(spec, params("mode", "a"), 2, 1));
    }

    @Test
    void textEffectsMaterialized() {
        TemplateSpec spec = parse("""
                spec: 1
                id: fx_demo
                name: effects
                canvas: { size: auto }
                layout:
                  type: stack
                  elements:
                    - type: text
                      content: hi
                      size: 24
                      color: "#000000"
                      effects:
                        stroke:
                          width: 2
                          color: "#FFFFFF"
                        shadow:
                          dx: 1
                          dy: 1
                          color: "#808080"
                """);
        var r = ok(instantiator.instantiate(spec, params(), 2, 1));
        TextElement t = (TextElement) r.elements().get(0);
        assertEquals(2, t.effects().stroke().width());
        assertEquals("#FFFFFF", t.effects().stroke().color());
        assertEquals(1, t.effects().shadow().dx());
        assertNull(t.effects().glow());
    }

    @Test
    void lineSkippedInV1() {
        TemplateSpec spec = parse("""
                spec: 1
                id: line_skip
                name: line v1 skip
                canvas: { size: auto }
                layout:
                  type: free
                  elements:
                    - type: line
                      from: [0, 0]
                      to: [128, 0]
                      width: 2
                      color: "#000000"
                    - type: text
                      content: keep
                      size: 12
                      color: "#000000"
                """);
        var r = ok(instantiator.instantiate(spec, params(), 2, 1));
        // line v1 跳过 → 只剩 text
        assertEquals(1, r.elements().size());
        assertEquals("keep", ((TextElement) r.elements().get(0)).text());
    }

    // ==================== C1-P2-11: 常规布局元素级校验 ====================

    /**
     * C1-P2-11: 插值后 text content 超 MAX_TEXT_LEN(256) 时返回 INVALID_TEMPLATE，
     * 不进入渲染层。
     */
    @Test
    void tooLongTextContentInStackRejected() {
        String longValue = "A".repeat(300);
        TemplateSpec spec = parse("""
                spec: 1
                id: long_text
                name: long text
                canvas: { size: auto }
                params:
                  msg:
                    type: string
                    label: msg
                    default: "x"
                layout:
                  type: stack
                  elements:
                    - type: text
                      content: "${msg}"
                      size: 12
                      color: "#000000"
                """);
        var f = failed(instantiator.instantiate(spec, params("msg", longValue), 2, 1));
        assertEquals("INVALID_TEMPLATE", f.code());
        assertTrue(f.errors().toString().contains("256"),
                "error should mention MAX_TEXT_LEN: " + f.errors());
    }

    /**
     * C1-P2-11: free 布局中传入超 int 的 x 坐标字符串，被 clampInt 到 Integer.MAX_VALUE，
     * 再被 validateElementForTemplateApply 拒，返回 INVALID_TEMPLATE（不含 JDK 报文）。
     */
    @Test
    void freeLayoutOversizedCoordRejected() {
        TemplateSpec spec = parse("""
                spec: 1
                id: coord_overflow
                name: coord overflow
                canvas: { size: auto }
                params:
                  xpos:
                    type: string
                    label: xpos
                    default: "0"
                layout:
                  type: free
                  elements:
                    - type: rect
                      x: "${xpos}"
                      y: 0
                      w: 10
                      h: 10
                      fill: "#FF0000"
                """);
        var f = failed(instantiator.instantiate(spec, params("xpos", "99999999999"), 2, 1));
        assertEquals("INVALID_TEMPLATE", f.code());
        assertFalse(f.errors().toString().contains("For input string"),
                "should not expose JDK NFE message: " + f.errors());
    }

    /**
     * C1-P2-11: free 布局中 w 超 MAX_DIM(10000) 被 validateElementForTemplateApply 拒。
     */
    @Test
    void freeLayoutOversizedDimRejected() {
        TemplateSpec spec = parse("""
                spec: 1
                id: dim_overflow
                name: dim overflow
                canvas: { size: auto }
                layout:
                  type: free
                  elements:
                    - type: rect
                      x: 0
                      y: 0
                      w: 20000
                      h: 10
                      fill: "#FF0000"
                """);
        var f = failed(instantiator.instantiate(spec, params(), 2, 1));
        assertEquals("INVALID_TEMPLATE", f.code());
    }

    /**
     * C1-P2-11: fontSize / lineHeight / letterSpacing 超范围时 clamp 到合法区间，
     * 不报错——模板静态字面值的宽容策略。
     */
    @Test
    void textFieldsClampedNotRejected() {
        // 验证「字段 clamp」：size/lineHeight/letterSpacing 超边界被 clamp 而非拒。
        // 用温和超限值——auto 布局算出的 h(≈ size×lineHeight 单行) 不爆 MAX_DIM(10000)。
        // 极端尺寸(如 size 9999×lineHeight 99 → h 近百万)会让 layout 算出的 h 超 MAX_DIM
        // 被 validateElementForTemplateApply 拒，那是「尺寸超限拒」语义，由
        // freeLayoutOversizedDimRejected 覆盖，与此处「字段 clamp」分开测。
        TemplateSpec spec = parse("""
                spec: 1
                id: clamp_text
                name: clamp text
                canvas: { size: auto }
                layout:
                  type: stack
                  elements:
                    - type: text
                      content: hi
                      size: 600
                      line_height: 5.0
                      letter_spacing: 200.0
                      color: "#000000"
                """);
        var r = ok(instantiator.instantiate(spec, params(), 2, 1));
        assertEquals(1, r.elements().size());
        TextElement t = (TextElement) r.elements().get(0);
        assertEquals(512, t.fontSize());
        assertEquals(4.0f, t.lineHeight(), 0.001f);
        assertEquals(128.0f, t.letterSpacing(), 0.001f);
    }

    // ==================== C2-P2-12: resolveDimension 溢出防护 ====================

    /**
     * C2-P2-12: 超 int 数字串（"99999999999"）在 resolveDimension/resolveDimensionWithAuto
     * 不抛 NFE、不外泄 JDK 报文，走 clampInt → Integer.MAX_VALUE，
     * 再被 validateElementForTemplateApply 拒为 INVALID_TEMPLATE。
     */
    @Test
    void oversizedIntStringInFreeDimNoJdkMessage() {
        TemplateSpec spec = parse("""
                spec: 1
                id: overflow_w
                name: overflow w
                canvas: { size: auto }
                params:
                  sz:
                    type: string
                    label: sz
                    default: "10"
                layout:
                  type: free
                  elements:
                    - type: rect
                      x: 0
                      y: 0
                      w: "${sz}"
                      h: 10
                      fill: "#FF0000"
                """);
        var f = failed(instantiator.instantiate(spec, params("sz", "99999999999"), 2, 1));
        assertEquals("INVALID_TEMPLATE", f.code());
        assertFalse(f.errors().toString().contains("For input string"),
                "JDK NFE message must not leak: " + f.errors());
        // 正常尺寸仍然通过
        ok(instantiator.instantiate(spec, params("sz", "100"), 2, 1));
    }

    /**
     * C2-P2-12: 超大百分比（"9999999999%"）百分比分支不回绕——
     * Math.round 返 long，StrictNumber.clampInt 钳位到 Integer.MAX_VALUE（正数），
     * 不会出现负数的回绕结果。
     */
    @Test
    void hugePercentageInFreeDimNoWrap() {
        TemplateSpec spec = parse("""
                spec: 1
                id: huge_pct
                name: huge pct
                canvas:
                  size: auto
                  padding: 0
                params:
                  sz:
                    type: string
                    label: sz
                    default: "10"
                layout:
                  type: free
                  elements:
                    - type: rect
                      x: 0
                      y: 0
                      w: "${sz}"
                      h: 10
                      fill: "#FF0000"
                """);
        var f = failed(instantiator.instantiate(spec, params("sz", "9999999999%"), 2, 1));
        assertEquals("INVALID_TEMPLATE", f.code());
        // clampInt 结果为正数（Integer.MAX_VALUE），错误消息不含负号（回绕征兆）
        assertFalse(f.errors().toString().contains("-"),
                "result should not be negative (wrap-around): " + f.errors());
    }
}
