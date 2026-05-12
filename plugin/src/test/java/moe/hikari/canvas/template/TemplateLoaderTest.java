package moe.hikari.canvas.template;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M6-A 解析 + 校验单元测试。覆盖 {@code docs/template-spec.md §9} 的关键拒绝路径，
 * 防止后续 M6-B/C 改动悄悄破坏 v1 schema 兼容性。
 */
class TemplateLoaderTest {

    private static TemplateLoader.Result load(String yaml) {
        return new TemplateLoader().load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static TemplateSpec okOrFail(TemplateLoader.Result r) {
        if (r instanceof TemplateLoader.Result.Failed f) {
            fail("expected ok but failed: " + f.reason() + " — " + f.detail());
        }
        return ((TemplateLoader.Result.Ok) r).spec();
    }

    private static String failureDetail(TemplateLoader.Result r) {
        assertInstanceOf(TemplateLoader.Result.Failed.class, r);
        return ((TemplateLoader.Result.Failed) r).detail();
    }

    @Test
    void parsesMinimalValidTemplate() {
        var spec = okOrFail(load("""
                spec: 1
                id: subway_station
                name: 地铁站牌
                canvas:
                  size: auto
                  min_maps: [3, 1]
                  max_maps: [8, 2]
                  background: "#FFFFFF"
                params:
                  name:
                    type: string
                    label: 站名
                    required: true
                layout:
                  type: stack
                  direction: vertical
                  elements:
                    - type: text
                      content: "${name}"
                      font: sourcehan
                      size: 48
                      color: "#000000"
                """));
        assertEquals(1, spec.spec());
        assertEquals("subway_station", spec.id());
        assertEquals("地铁站牌", spec.name());
        assertEquals("auto", spec.canvas().size());
        assertNotNull(spec.params().get("name"));
        assertEquals("stack", spec.layout().type());
        assertEquals(1, spec.layout().elements().size());
        assertInstanceOf(TemplateElement.Text.class, spec.layout().elements().get(0));
    }

    @Test
    void rejectsUnsupportedSpec() {
        assertTrue(failureDetail(load("""
                spec: 99
                id: x_template
                name: bogus
                canvas: { size: auto }
                layout: { type: stack, elements: [{ type: text, content: hi, size: 12, color: "#000000" }] }
                """)).contains("spec=99"));
    }

    @Test
    void rejectsBadId() {
        assertTrue(failureDetail(load("""
                spec: 1
                id: Bad-ID
                name: x
                canvas: { size: auto }
                layout: { type: stack, elements: [{ type: text, content: hi, size: 12, color: "#000000" }] }
                """)).contains("id"));
    }

    @Test
    void rejectsGridLayoutInV1() {
        // M6 决策：grid 推迟到 M7
        assertTrue(failureDetail(load("""
                spec: 1
                id: grid_demo
                name: grid demo
                canvas: { size: auto }
                layout: { type: grid, elements: [{ type: text, content: hi, size: 12, color: "#000000" }] }
                """)).contains("layout.type"));
    }

    @Test
    void rejectsUndeclaredParamRef() {
        assertTrue(failureDetail(load("""
                spec: 1
                id: ref_demo
                name: ref demo
                canvas: { size: auto }
                layout:
                  type: stack
                  elements:
                    - type: text
                      content: "${not_declared}"
                      size: 12
                      color: "#000000"
                """)).contains("not_declared"));
    }

    @Test
    void rejectsBadColorOnConstant() {
        assertTrue(failureDetail(load("""
                spec: 1
                id: color_demo
                name: bad color
                canvas:
                  size: auto
                  background: "not-a-color"
                layout:
                  type: stack
                  elements:
                    - type: text
                      content: hi
                      size: 12
                      color: "#000000"
                """)).toLowerCase().contains("color"));
    }

    @Test
    void acceptsParamRefInColor() {
        // 含 ${...} 的颜色字段不再立即拒绝；留实例化期判
        var spec = okOrFail(load("""
                spec: 1
                id: tinted
                name: tinted
                canvas: { size: auto, background: "${bg}" }
                params:
                  bg:
                    type: color
                    label: 底色
                    default: "#FFFFFF"
                layout:
                  type: stack
                  elements:
                    - type: rect
                      w: 100%
                      h: 12
                      fill: "${bg}"
                """));
        assertEquals("${bg}", spec.canvas().background());
    }

    @Test
    void rejectsBadParamId() {
        assertTrue(failureDetail(load("""
                spec: 1
                id: bad_param
                name: bad param
                canvas: { size: auto }
                params:
                  BadParam:
                    type: string
                    label: x
                layout: { type: stack, elements: [{ type: text, content: hi, size: 12, color: "#000000" }] }
                """)).contains("BadParam"));
    }

    @Test
    void rejectsMissingLayout() {
        assertTrue(failureDetail(load("""
                spec: 1
                id: no_layout
                name: no layout
                canvas: { size: auto }
                """)).contains("layout"));
    }

    @Test
    void rejectsEmptyEnumOptions() {
        assertTrue(failureDetail(load("""
                spec: 1
                id: enum_demo
                name: enum demo
                canvas: { size: auto }
                params:
                  mode:
                    type: enum
                    label: 模式
                layout: { type: stack, elements: [{ type: text, content: hi, size: 12, color: "#000000" }] }
                """)).contains("options"));
    }

    @Test
    void rejectsMapsOutOfRange() {
        assertTrue(failureDetail(load("""
                spec: 1
                id: huge
                name: huge
                canvas:
                  size: auto
                  min_maps: [0, 1]
                  max_maps: [20, 2]
                layout: { type: stack, elements: [{ type: text, content: hi, size: 12, color: "#000000" }] }
                """)).contains("out of range"));
    }

    @Test
    void textElementMissingContent() {
        assertTrue(failureDetail(load("""
                spec: 1
                id: notext
                name: no text
                canvas: { size: auto }
                layout:
                  type: stack
                  elements:
                    - type: text
                      size: 12
                      color: "#000000"
                """)).contains("content"));
    }

    @Test
    void unknownTopLevelFieldIgnored() {
        // FAIL_ON_UNKNOWN_PROPERTIES disabled → 新加字段不会让旧插件解析失败
        var r = load("""
                spec: 1
                id: tolerant
                name: tolerant
                future_field: surprise
                canvas: { size: auto }
                layout: { type: stack, elements: [{ type: text, content: hi, size: 12, color: "#000000" }] }
                """);
        assertNotNull(okOrFail(r));
    }

    @Test
    void rejectsBadVisibleWhenSyntax() {
        // M6-B 把表达式语法校验接进 §9 校验
        String detail = failureDetail(load("""
                spec: 1
                id: bad_expr
                name: bad expression
                canvas: { size: auto }
                params:
                  flag:
                    type: bool
                    label: x
                layout:
                  type: stack
                  elements:
                    - type: text
                      content: hi
                      size: 12
                      color: "#000000"
                      visible_when: "flag &&"
                """));
        assertTrue(detail.contains("parse error"));
    }

    @Test
    void rejectsYamlJavaTagPolymorphism() {
        // docs/security.md §4.3：jackson-yaml 默认应拒绝 !!java/* tag
        var r = load("""
                spec: 1
                id: rce_attempt
                name: !!java.lang.RuntimeException "hi"
                canvas: { size: auto }
                layout: { type: stack, elements: [] }
                """);
        assertInstanceOf(TemplateLoader.Result.Failed.class, r,
                "YAML !!java/* tag must NOT result in reflection-based construction");
    }
}
