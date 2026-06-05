package moe.hikari.canvas.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import moe.hikari.canvas.state.Fill;
import moe.hikari.canvas.state.LinearGradient;
import moe.hikari.canvas.state.RadialGradient;
import moe.hikari.canvas.state.SolidFill;
import moe.hikari.canvas.state.Stop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * {@link ColorLerp} 双端共享向量 runner + sRGB 线性空间细则单测（0.6 P3）。
 * 数学权威见 {@code docs/rendering.md §9.4}；一致性验证范式见 §9.6。
 *
 * <p>共享 {@code rendering-test/color-lerp-vectors.json}（第三方 Python 参照实现生成，
 * expected 为<b>精确字符串匹配</b>）—— 与前端 vitest 跑同向量、对同 expected hex 串。</p>
 *
 * <p>细则补测：解析失败 step（返左端原样）/ 大小写不敏感输入 / 端点 gamma 往返还原 /
 * Fill 各形态（solid / linear / radial / 类型 / stop 数 / null 降级）。</p>
 */
class ColorLerpTest {

    private static final Path VECTORS = Path.of(System.getProperty("user.dir"))
            .resolve("../rendering-test/color-lerp-vectors.json");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ──────────────────────────────────────────────────────────
    //  共享向量 runner（精确字符串匹配）
    // ──────────────────────────────────────────────────────────

    @TestFactory
    List<org.junit.jupiter.api.DynamicTest> sharedVectors() throws IOException {
        JsonNode root = MAPPER.readTree(Files.newBufferedReader(VECTORS));
        JsonNode cases = root.get("cases");
        assertTrue(cases != null && cases.size() > 0, "color-lerp-vectors.json 应含至少一条 case");
        List<org.junit.jupiter.api.DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) {
            String a = c.get("a").asText();
            String b = c.get("b").asText();
            double t = c.get("t").asDouble();
            String expected = c.get("expected").asText();
            tests.add(dynamicTest("lerp(" + a + "," + b + ")@" + t, () ->
                    assertEquals(expected, ColorLerp.lerpHex(a, b, t),
                            "lerpHex 应与共享向量 expected 精确匹配（字符串）")));
        }
        return tests;
    }

    // ──────────────────────────────────────────────────────────
    //  lerpHex 细则
    // ──────────────────────────────────────────────────────────

    @Test
    void parseFailureReturnsFirstArgVerbatim() {
        // §9.4：解析失败 → step（取左端帧原样返回，不抛错）
        assertEquals("#XYZ", ColorLerp.lerpHex("#XYZ", "#000000", 0.5),
                "左端非法 → 返回左端原样");
        assertEquals("#12", ColorLerp.lerpHex("#12", "#FFFFFF", 0.5),
                "左端长度非法 → 返回左端原样");
        // 右端非法也 step 返左端
        assertEquals("#FF0000", ColorLerp.lerpHex("#FF0000", "not-a-color", 0.5),
                "右端非法 → 返回左端原样");
        // null 入参 → 返左端（即 null）
        assertEquals(null, ColorLerp.lerpHex(null, "#000000", 0.5), "左端 null → 返 null");
    }

    @Test
    void caseInsensitiveInput() {
        // 大小写不敏感输入；输出统一大写
        String upper = ColorLerp.lerpHex("#FF0000", "#0000FF", 0.5);
        String lower = ColorLerp.lerpHex("#ff0000", "#0000ff", 0.5);
        String mixed = ColorLerp.lerpHex("#Ff0000", "#0000Ff", 0.5);
        assertEquals(upper, lower, "小写输入应与大写同结果");
        assertEquals(upper, mixed, "混合大小写输入应与大写同结果");
        assertEquals("#BC00BC", upper, "输出大写（与共享向量一致）");
    }

    @Test
    void endpointsRestoreInputUppercaseForm() {
        // t=0：gamma decode→encode 往返 + round 应精确还原输入色（大写形态）
        // 任意色都成立（向量已覆盖 #123456@0 → #123456）
        assertEquals("#123456", ColorLerp.lerpHex("#123456", "#FEDCBA", 0.0),
                "t=0 还原左端（大写）");
        assertEquals("#FEDCBA", ColorLerp.lerpHex("#123456", "#FEDCBA", 1.0),
                "t=1 还原右端（大写）");
        // 小写输入也应大写还原
        assertEquals("#ABCDEF", ColorLerp.lerpHex("#abcdef", "#000000", 0.0),
                "t=0 小写输入 → 大写还原");
        // 含 alpha 端点还原
        assertEquals("#00FF0080", ColorLerp.lerpHex("#00FF0080", "#0000FF80", 0.0),
                "t=0 带 alpha 还原");
    }

    @Test
    void alphaOutputWhenEitherInputHasAlpha() {
        // 输出含 alpha 当且仅当任一输入含 alpha（§9.4）
        // 左端无 alpha + 右端有 alpha → 输出 8 位（向量 #FFFFFF↔#00000000 已覆盖；此处再断言形态）
        String out = ColorLerp.lerpHex("#FFFFFF", "#00000000", 0.5);
        assertEquals(9, out.length(), "任一输入含 alpha → 输出含 alpha（# + 8 hex）");
        // 两端均无 alpha → 输出 6 位
        String noAlpha = ColorLerp.lerpHex("#000000", "#FFFFFF", 0.5);
        assertEquals(7, noAlpha.length(), "两端无 alpha → 输出 6 hex");
    }

    // ──────────────────────────────────────────────────────────
    //  lerpFill：solid ↔ solid
    // ──────────────────────────────────────────────────────────

    @Test
    void lerpFillSolidToSolid() {
        Fill a = new SolidFill("#FF0000");
        Fill b = new SolidFill("#0000FF");
        Fill out = ColorLerp.lerpFill(a, b, 0.5);
        assertInstanceOf(SolidFill.class, out);
        // 颜色与 lerpHex 同源（共享向量同算法）
        assertEquals(ColorLerp.lerpHex("#FF0000", "#0000FF", 0.5),
                ((SolidFill) out).color(), "solid↔solid 逐分量插值");
    }

    @Test
    void lerpFillSolidWithNullColorSteps() {
        // solid 颜色缺失 → step（返左端 a）
        Fill a = new SolidFill(null);
        Fill b = new SolidFill("#0000FF");
        assertSame(a, ColorLerp.lerpFill(a, b, 0.5), "stop 颜色缺失 → step 返左端");
    }

    // ──────────────────────────────────────────────────────────
    //  lerpFill：linear（同 stop 数逐 stop + angle 线性）
    // ──────────────────────────────────────────────────────────

    @Test
    void lerpFillLinearSameStopCount() {
        LinearGradient a = new LinearGradient(0.0, List.of(
                new Stop(0.0, "#000000"), new Stop(1.0, "#FF0000")));
        LinearGradient b = new LinearGradient(90.0, List.of(
                new Stop(0.0, "#FFFFFF"), new Stop(1.0, "#00FF00")));
        Fill out = ColorLerp.lerpFill(a, b, 0.5);
        assertInstanceOf(LinearGradient.class, out);
        LinearGradient lg = (LinearGradient) out;
        // angle 线性：0 + (90-0)*0.5 = 45
        assertEquals(45.0, lg.angle(), 1e-9, "angle 线性插值");
        assertEquals(2, lg.stops().size(), "stop 数保持");
        // stop[0] position 线性 + 颜色 lerpHex 同源
        assertEquals(0.0, lg.stops().get(0).position(), 1e-9);
        assertEquals(ColorLerp.lerpHex("#000000", "#FFFFFF", 0.5),
                lg.stops().get(0).color(), "stop[0] 颜色 sRGB 线性空间");
        assertEquals(ColorLerp.lerpHex("#FF0000", "#00FF00", 0.5),
                lg.stops().get(1).color(), "stop[1] 颜色 sRGB 线性空间");
    }

    @Test
    void lerpFillLinearStopCountMismatchSteps() {
        LinearGradient a = new LinearGradient(0.0, List.of(
                new Stop(0.0, "#000000"), new Stop(1.0, "#FFFFFF")));
        LinearGradient b = new LinearGradient(0.0, List.of(
                new Stop(0.0, "#000000"), new Stop(0.5, "#888888"), new Stop(1.0, "#FFFFFF")));
        assertSame(a, ColorLerp.lerpFill(a, b, 0.5), "stop 数不一致 → step 返左端");
    }

    @Test
    void lerpFillLinearStopWithNullColorSteps() {
        LinearGradient a = new LinearGradient(0.0, List.of(
                new Stop(0.0, null), new Stop(1.0, "#FFFFFF")));
        LinearGradient b = new LinearGradient(0.0, List.of(
                new Stop(0.0, "#000000"), new Stop(1.0, "#FFFFFF")));
        assertSame(a, ColorLerp.lerpFill(a, b, 0.5), "stop 颜色含 null → step 返左端");
    }

    // ──────────────────────────────────────────────────────────
    //  lerpFill：radial（cx/cy/r 线性）
    // ──────────────────────────────────────────────────────────

    @Test
    void lerpFillRadialCenterRadiusLinear() {
        RadialGradient a = new RadialGradient(0.0, 0.0, 0.5, List.of(
                new Stop(0.0, "#000000"), new Stop(1.0, "#FFFFFF")));
        RadialGradient b = new RadialGradient(1.0, 1.0, 1.5, List.of(
                new Stop(0.0, "#FFFFFF"), new Stop(1.0, "#000000")));
        Fill out = ColorLerp.lerpFill(a, b, 0.5);
        assertInstanceOf(RadialGradient.class, out);
        RadialGradient rg = (RadialGradient) out;
        assertEquals(0.5, rg.cx(), 1e-9, "cx 线性");
        assertEquals(0.5, rg.cy(), 1e-9, "cy 线性");
        assertEquals(1.0, rg.r(), 1e-9, "r 线性：0.5+(1.5-0.5)*0.5");
        assertEquals(ColorLerp.lerpHex("#000000", "#FFFFFF", 0.5),
                rg.stops().get(0).color(), "stop[0] 颜色 sRGB 线性空间");
    }

    // ──────────────────────────────────────────────────────────
    //  lerpFill：类型不一致 / null → step
    // ──────────────────────────────────────────────────────────

    @Test
    void lerpFillTypeMismatchSteps() {
        Fill solid = new SolidFill("#FF0000");
        Fill linear = new LinearGradient(0.0, List.of(
                new Stop(0.0, "#000000"), new Stop(1.0, "#FFFFFF")));
        // solid ↔ linear 类型不一致 → step 返左端
        assertSame(solid, ColorLerp.lerpFill(solid, linear, 0.5),
                "solid↔linear 类型不一致 → step 返左端");
        assertSame(linear, ColorLerp.lerpFill(linear, solid, 0.5),
                "linear↔solid 类型不一致 → step 返左端");
        // linear ↔ radial 也 step
        Fill radial = new RadialGradient(0.5, 0.5, 1.0, List.of(
                new Stop(0.0, "#000000"), new Stop(1.0, "#FFFFFF")));
        assertSame(linear, ColorLerp.lerpFill(linear, radial, 0.5),
                "linear↔radial 类型不一致 → step 返左端");
    }

    @Test
    void lerpFillNullArgsStep() {
        Fill solid = new SolidFill("#FF0000");
        assertSame(solid, ColorLerp.lerpFill(solid, null, 0.5), "右端 null → 返左端");
        assertEquals(null, ColorLerp.lerpFill(null, solid, 0.5), "左端 null → 返 null");
    }
}
