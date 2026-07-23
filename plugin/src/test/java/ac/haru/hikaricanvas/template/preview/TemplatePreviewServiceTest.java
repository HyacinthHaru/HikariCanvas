package ac.haru.hikaricanvas.template.preview;

import ac.haru.hikaricanvas.state.BlendMode;
import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.RectElement;
import ac.haru.hikaricanvas.state.SolidFill;
import ac.haru.hikaricanvas.state.TextElement;
import ac.haru.hikaricanvas.template.TemplateEntry;
import ac.haru.hikaricanvas.template.TemplateSource;
import ac.haru.hikaricanvas.template.TemplateSpec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    // ---- 预览态变量占位符收敛 ----

    @Test
    void previewResolveUsesFallback() {
        assertEquals("在线 5 人",
                TemplatePreviewService.previewResolve("在线 ${var:server.online|fallback=5} 人"));
    }

    @Test
    void previewResolveEmptyFallbackYieldsEmpty() {
        assertEquals("剩余  秒",
                TemplatePreviewService.previewResolve("剩余 ${var:schedule.eta_seconds|fallback=} 秒"));
    }

    @Test
    void previewResolveNoFallbackUsesNameTail() {
        // 无 fallback → 取变量名末段（server.online → online）
        assertEquals("online",
                TemplatePreviewService.previewResolve("${var:server.online}"));
    }

    @Test
    void previewResolveMultipleAndPlainUnchanged() {
        assertEquals("A 0 B --:--",
                TemplatePreviewService.previewResolve(
                        "A ${var:server.online|fallback=0} B ${var:server.time|fallback=--:--}"));
        assertEquals("纯文本无占位符",
                TemplatePreviewService.previewResolve("纯文本无占位符"));
    }

    @Test
    void previewResolveLeavesTemplateParamAlone() {
        // ${param}（无 var: 前缀）不是运行时变量，预览态不该动它（模板参数已在更早阶段替换）
        assertEquals("${shop_name}",
                TemplatePreviewService.previewResolve("${shop_name}"));
    }

    // ---- pack 预览态 state 装配（packStateForPreview，不需 compositor / registry） ----

    /** {@code manifest.kind="pack"}，wall 2×1。 */
    private static final String PACK_MANIFEST =
            "{\"spec\":1,\"kind\":\"pack\",\"created_at\":1,\"name\":\"Preview Demo\","
                    + "\"wall\":{\"width\":2,\"height\":1}}";
    /** title：string，default {@code 你好}。 */
    private static final String PACK_PARAMS =
            "[{\"id\":\"title\",\"type\":\"string\",\"label\":\"标题\",\"default\":\"你好\",\"max_length\":8}]";

    /**
     * 带参 pack：{@code ${title}} 应替成 default，{@code ${var:server.online|fallback=5}} 应收敛成 5，
     * canvas 尺寸取自 manifest wall（2×1）。
     */
    @Test
    void packStateForPreviewSubstitutesDefaultsAndConvergesVars() throws Exception {
        TemplatePreviewService svc = service();
        byte[] pack = zipPack(PACK_MANIFEST, PACK_PARAMS,
                projectWithText("\"${title} 在线 ${var:server.online|fallback=5} 人\""));

        ProjectState state = svc.packStateForPreview(packEntry(pack));

        assertEquals(2, state.canvas().widthMaps(), "canvas 宽取自 manifest wall.width");
        assertEquals(1, state.canvas().heightMaps(), "canvas 高取自 manifest wall.height");
        TextElement t = assertInstanceOf(TextElement.class,
                state.layers().get(0).elements().get(0));
        assertEquals("你好 在线 5 人", t.text(),
                "${title}→default，${var:...|fallback=5}→5");
    }

    /** 无 params.json 的 pack 仍应能装配 state（substitute 用空 map，${var:...} 照常收敛）。 */
    @Test
    void packStateForPreviewWithoutParamsStillBuilds() throws Exception {
        TemplatePreviewService svc = service();
        byte[] pack = zipPack(PACK_MANIFEST, /*params*/ null,
                projectWithText("\"静态 ${var:server.tps|fallback=20.0}\""));

        ProjectState state = svc.packStateForPreview(packEntry(pack));

        assertNotNull(state);
        assertEquals(2, state.canvas().widthMaps());
        TextElement t = assertInstanceOf(TextElement.class,
                state.layers().get(0).elements().get(0));
        assertEquals("静态 20.0", t.text(), "无参 pack：${var:...|fallback=20.0}→20.0");
    }

    // ---- pack 装配用 helpers ----

    /**
     * 合成 spec 供 {@link TemplateEntry#pack} 载体用；{@code packStateForPreview} 只读 packBytes，
     * spec 内容与本测试无关，给个最小可用的即可。
     */
    private static TemplateEntry packEntry(byte[] packBytes) {
        TemplateSpec synthetic = new TemplateSpec(1, "pack-preview", "Pack Preview",
                null, null, null, null, null, null, null, null);
        return TemplateEntry.pack(synthetic, TemplateSource.BUILTIN, "test:pack",
                Optional.empty(), packBytes);
    }

    /** 单文本元素工程；{@code textJson} 为 JSON 层的 text 字段值（含引号）。 */
    private static String projectWithText(String textJson) {
        return "{\"version\":3,\"canvas\":{\"widthMaps\":2,\"heightMaps\":1,\"background\":\"#FFFFFF\"},"
                + "\"layers\":[{\"id\":\"l1\",\"name\":\"L\",\"visible\":true,\"locked\":false,"
                + "\"opacity\":1.0,\"blendMode\":\"normal\",\"elements\":[{\"id\":\"e1\",\"type\":\"text\","
                + "\"x\":0,\"y\":0,\"w\":80,\"h\":40,\"rotation\":0,\"text\":" + textJson
                + ",\"fontId\":\"ark_pixel\",\"fontSize\":24,\"color\":\"#000000\",\"align\":\"left\","
                + "\"letterSpacing\":0.0,\"lineHeight\":1.2,\"vertical\":false}]}],\"activeLayerId\":\"l1\"}";
    }

    /** manifest + （可选）params.json + project.json 打成 .canvas zip；params 为 null 则不写该条目。 */
    private static byte[] zipPack(String manifest, String params, String project) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            writeEntry(z, "manifest.json", manifest);
            if (params != null) writeEntry(z, "params.json", params);
            writeEntry(z, "project.json", project);
        }
        return bos.toByteArray();
    }

    private static void writeEntry(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
