package ac.haru.hikaricanvas.template.preview;

import ac.haru.hikaricanvas.state.BlendMode;
import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.RectElement;
import ac.haru.hikaricanvas.state.SolidFill;
import ac.haru.hikaricanvas.state.TextElement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TemplatePreviewService#stateOf} 装配逻辑的钉死测试。
 *
 * <p>历史 bug：{@code stateOf} 曾用 {@code Map.of("elements", elements)} 把强类型
 * {@code List<Element>} 的值类型擦成 {@code Object} 再 {@code mapper.convertValue(...)}。
 * Jackson 的 {@code @JsonTypeInfo(property="type")} 只在静态类型为 {@link Element} 时才写出
 * {@code type} 判别符——走 Object 值路径时不写 → 反序列化 {@link ProjectState#elements()}
 * （{@code List<Element>} 多态）找不到 {@code type} → 抛
 * {@code InvalidTypeIdException}（{@code IllegalArgumentException} 包裹）。任何产出 ≥1
 * 元素的模板预览都会崩。修法是直接构造 {@link ProjectState} 不再序列化。</p>
 *
 * <p>本测试只钉 {@code stateOf} 组装逻辑，不引入 Bukkit / 真实渲染依赖。</p>
 */
class TemplatePreviewServiceTest {

    /** {@code stateOf} 不读 registry / compositor，仅用于承载可调用实例。 */
    private static TemplatePreviewService service() {
        return new TemplatePreviewService(Logger.getLogger("test-preview"), null, null);
    }

    private static TextElement text(String id) {
        return new TextElement(id, 5, 5, 100, 20, 0, false, true,
                "hello", "ark_pixel", 12, "#000000", "left",
                0f, 1.2f, false, null,
                null, null, null, null, null);
    }

    private static RectElement rect(String id) {
        return new RectElement(id, 0, 0, 50, 50, 0, false, true,
                new SolidFill("#FF0000"), null,
                null, null, null);
    }

    /**
     * 核心回归：含真实 element（{@link TextElement} + {@link RectElement}）时 {@code stateOf}
     * 不得抛异常，且返回的 {@link ProjectState#elements()} 保留元素、类型正确。
     *
     * <p>用旧的坏 {@code stateOf} 跑此测试会以 {@code IllegalArgumentException}（包裹
     * {@code InvalidTypeIdException}）失败，证明它抓得住 bug。</p>
     */
    @Test
    void stateOfPreservesElementTypesWithRealElements() {
        TemplatePreviewService svc = service();
        List<Element> elements = List.of(text("e-txt"), rect("e-rect"));

        ProjectState state = assertDoesNotThrow(() ->
                svc.stateOf(4, 1, "#123456", elements));

        List<Element> out = state.elements();
        assertEquals(2, out.size(), "两个元素都应保留");
        assertInstanceOf(TextElement.class, out.get(0), "第一个元素应是 TextElement");
        assertInstanceOf(RectElement.class, out.get(1), "第二个元素应是 RectElement");
        assertEquals("hello", ((TextElement) out.get(0)).text());
        assertEquals("e-rect", out.get(1).id());
    }

    /** 空元素列表仍应返回纯背景 ProjectState（锁住别改坏空路径）。 */
    @Test
    void stateOfWithEmptyElementsReturnsBackgroundOnly() {
        TemplatePreviewService svc = service();

        ProjectState state = assertDoesNotThrow(() ->
                svc.stateOf(2, 1, "#ABCDEF", List.of()));

        assertNotNull(state);
        assertTrue(state.elements().isEmpty(), "无 element 时应为空");
        assertEquals(2, state.canvas().widthMaps());
        assertEquals(1, state.canvas().heightMaps());
    }

    /** null 背景应兜底为白色，且能正常装配元素。 */
    @Test
    void stateOfNullBackgroundDefaultsToWhite() {
        TemplatePreviewService svc = service();

        ProjectState state = assertDoesNotThrow(() ->
                svc.stateOf(1, 1, null, List.of(text("e-1"))));

        assertNotNull(state.canvas().background());
        assertEquals(1, state.elements().size());
    }

    @Test
    void stateOfKeepsRectFillIntact() {
        TemplatePreviewService svc = service();
        ProjectState state = svc.stateOf(3, 1, "#000000", List.of(rect("e-r")));

        RectElement r = assertInstanceOf(RectElement.class, state.elements().get(0));
        assertInstanceOf(SolidFill.class, r.fill());
        assertEquals(BlendMode.NORMAL, r.effectiveBlendMode());
    }
}
