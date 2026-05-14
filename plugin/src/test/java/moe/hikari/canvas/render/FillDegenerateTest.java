package moe.hikari.canvas.render;

import moe.hikari.canvas.state.EditSession;
import moe.hikari.canvas.state.ProjectState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M11-E review 回归：用户可在 FillInput 把所有 stops 拉到 position=1.0（FillValidator 允许相等位置）。
 * 此时 {@code monotonicFractions} 仍产出非严格递增的 fractions（{@code [1.0, 1.0]}），
 * 触发 AWT {@code LinearGradientPaint} / {@code RadialGradientPaint} 的 IAE。
 *
 * <p>修复：{@link CanvasCompositor#buildLinearPaint} / {@code buildRadialPaint} try-catch IAE
 * 兜底首 stop 纯色 {@link java.awt.Paint}。本测试验证退化形态不再让 rasterize 崩。</p>
 */
class FillDegenerateTest {

    private static CanvasCompositor compositor;

    @BeforeAll
    static void setUp() throws IOException {
        Logger log = Logger.getLogger(FillDegenerateTest.class.getName());
        PaletteLut paletteLut = PaletteLut.loadFromClasspath("/palette.json");
        FontRegistry fontRegistry = new FontRegistry(log);
        fontRegistry.loadBuiltIn();
        compositor = new CanvasCompositor(paletteLut, fontRegistry, log);
    }

    @Test
    void rasterizeRectWithLinearStopsAllAtOne() {
        // 用户极端拖动产物：stops 全 position=1.0（FillValidator 允许）
        EditSession es = new EditSession(new ProjectState(1, 1));
        EditSession.OpResult r = es.addElement("rect", Map.of(
                "x", 0, "y", 0, "w", 64, "h", 64,
                "fill", Map.of(
                        "type", "linear",
                        "angle", 45.0,
                        "stops", List.of(
                                Map.of("position", 1.0, "color", "#FF0000"),
                                Map.of("position", 1.0, "color", "#0000FF")
                        )
                )
        ), null, null);
        assertNotNull(r);
        BufferedImage img = assertDoesNotThrow(() -> compositor.rasterize(es.state()));
        assertNotNull(img);
    }

    @Test
    void rasterizeRectWithLinearStopsAllAtZero() {
        EditSession es = new EditSession(new ProjectState(1, 1));
        es.addElement("rect", Map.of(
                "x", 0, "y", 0, "w", 64, "h", 64,
                "fill", Map.of(
                        "type", "linear",
                        "angle", 90.0,
                        "stops", List.of(
                                Map.of("position", 0.0, "color", "#00FF00"),
                                Map.of("position", 0.0, "color", "#FFFFFF")
                        )
                )
        ), null, null);
        BufferedImage img = assertDoesNotThrow(() -> compositor.rasterize(es.state()));
        assertNotNull(img);
    }

    @Test
    void rasterizeCircleWithRadialStopsAllAtOne() {
        EditSession es = new EditSession(new ProjectState(1, 1));
        es.addElement("circle", Map.of(
                "x", 8, "y", 8, "w", 48, "h", 48,
                "fill", Map.of(
                        "type", "radial",
                        "cx", 0.5, "cy", 0.5, "r", 1.0,
                        "stops", List.of(
                                Map.of("position", 1.0, "color", "#FFFFFF"),
                                Map.of("position", 1.0, "color", "#000000")
                        )
                )
        ), null, null);
        BufferedImage img = assertDoesNotThrow(() -> compositor.rasterize(es.state()));
        assertNotNull(img);
    }

    @Test
    void rasterizeRectWithZeroDimBboxLinearGradient() {
        // 0 宽元素 + 渐变：4 角投影到方向向量 minP=maxP=0 → x1==x2 && y1==y2 → 纯色 fallback
        // 这是 M11-B 早就有的检查，本测试只是固化
        EditSession es = new EditSession(new ProjectState(1, 1));
        es.addElement("rect", Map.of(
                "x", 10, "y", 10, "w", 0, "h", 0,
                "fill", Map.of(
                        "type", "linear",
                        "angle", 0.0,
                        "stops", List.of(
                                Map.of("position", 0.0, "color", "#FF0000"),
                                Map.of("position", 1.0, "color", "#0000FF")
                        )
                ),
                "stroke", Map.of("width", 1, "color", "#000000")
        ), null, null);
        BufferedImage img = assertDoesNotThrow(() -> compositor.rasterize(es.state()));
        assertNotNull(img);
    }
}
