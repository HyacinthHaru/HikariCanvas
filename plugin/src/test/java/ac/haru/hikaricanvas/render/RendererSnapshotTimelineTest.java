package ac.haru.hikaricanvas.render;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.Timeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多帧 snapshot —— 时间轴插值的像素级一致性防线（0.6 P3，契约 {@code docs/rendering.md §9.6}）。
 *
 * <h2>与 {@link RendererSnapshotTest} 的关系</h2>
 * 原 snapshot 测试无时间维（读 fixture → {@code rasterize(state)} 一次 → 比单张 PNG），
 * 测不了缓动 / 插值是否真正生效（§9.6）。本测试补「时间维前置层」：同一条 timeline 在
 * {@code t = 0 / 250 / 500 / 750ms} 各 {@link KeyframeInterpolator#interpolate} 出一个临时
 * {@link ProjectState}，再 {@link CanvasCompositor#rasterize} 出一张 PNG 比基线，验证
 * §9.1 取值 / §9.3 缓动 / §9.4 色彩与 Fill 插值的端到端落地。
 *
 * <h2>fixture 设计（§9.6 纯几何约束）</h2>
 * {@code 14-timeline-easing.json} 只用纯几何元素（rect / circle / shape star / path）—— 文本
 * 元素因 AWT 字体度量跨平台差异已在 CI 跳过（{@link RendererSnapshotTest} §8.4 同因），故本
 * fixture 不含文本，**无需平台脆弱跳过机制**，CI Linux 与本地 macOS 一致跑全套。4 个元素分别
 * 覆盖 P3 全部插值类别：
 * <ul>
 *   <li>{@code e-mover}（rect）：x 轨 LINEAR + fill 轨 solid 色彩插值（§9.2 数值 + §9.4 solid）</li>
 *   <li>{@code e-fader}（circle）：opacity 轨 easeInOut（§9.3 缓动接入）</li>
 *   <li>{@code e-grower}（star）：w/h 轨 easeOut（多数值属性 + 非线性缓动）</li>
 *   <li>{@code e-grad}（path）：fill 轨 linear 渐变同 stop 数逐 stop 插值（§9.4 Fill）</li>
 * </ul>
 *
 * <h2>流程与容差</h2>
 * 复用 {@link RendererSnapshotTest} 的 PROJECT_ROOT / 0.5% 容差 / 首跑生成 baseline 范式。
 * 像素比 ≥ 0.5% 则失败；{@code expected/14-timeline-easing-t<t>.png} 不存在时把 actual 复制为
 * baseline 并告警（首跑通过）。
 *
 * <h2>假绿防御</h2>
 * 单纯比 4 张基线不能排除「插值器没生效但基线也一起错」的假绿。{@link #framesDiffer} 加一条
 * 互证断言：t=0 与 t=250 两帧的 actual 必须有像素差（mover 已位移 / fader 已变淡 / grower 已变大），
 * 否则即使各帧自比基线通过也判失败。
 */
class RendererSnapshotTimelineTest {

    private static final double TOLERANCE = 0.005; // 0.5%，沿用 §8.2 / RendererSnapshotTest
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path FIXTURES_DIR = PROJECT_ROOT.resolve("src/test/resources/fixtures");
    private static final Path EXPECTED_DIR = PROJECT_ROOT.resolve("src/test/resources/expected");
    private static final Path ACTUAL_DIR = PROJECT_ROOT.resolve("build/test-results/snapshot/actual");
    private static final Path DIFF_DIR = PROJECT_ROOT.resolve("build/test-results/snapshot/diff");

    private static final String FIXTURE = "14-timeline-easing";
    private static final int[] SAMPLE_TIMES = {0, 250, 500, 750};

    private static CanvasCompositor compositor;
    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() throws IOException {
        Logger log = Logger.getLogger(RendererSnapshotTimelineTest.class.getName());
        PaletteLut paletteLut = PaletteLut.loadFromClasspath("/palette.json");
        FontRegistry fontRegistry = new FontRegistry(log);
        fontRegistry.loadBuiltIn();
        compositor = new CanvasCompositor(paletteLut, fontRegistry, log);
        mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        Files.createDirectories(ACTUAL_DIR);
        Files.createDirectories(DIFF_DIR);
    }

    /** 读 fixture + 取激活 timeline（fixture 顶层 activeTimelineId 指向唯一一条）。 */
    private static ProjectState loadFixture() throws IOException {
        Path fixturePath = FIXTURES_DIR.resolve(FIXTURE + ".json");
        assertTrue(Files.exists(fixturePath), "fixture missing: " + fixturePath);
        ProjectState state = mapper.readValue(fixturePath.toFile(), ProjectState.class);
        assertEquals(1, state.timelines().size(), "fixture 应恰含一条 timeline");
        assertNotNull(state.activeTimelineId(), "fixture 应设 activeTimelineId");
        return state;
    }

    private static Timeline activeTimeline(ProjectState state) {
        int i = state.indexOfTimeline(state.activeTimelineId());
        assertTrue(i >= 0, "activeTimelineId 应命中 timelines 列表");
        return state.timelines().get(i);
    }

    /** 插值到 timeMs → rasterize → 写 actual PNG，返回该帧位图。 */
    private static BufferedImage renderAt(ProjectState base, Timeline timeline, int timeMs)
            throws IOException {
        ProjectState frame = KeyframeInterpolator.interpolate(base, timeline, timeMs);
        BufferedImage actual = compositor.rasterize(frame);
        Path actualPath = ACTUAL_DIR.resolve(FIXTURE + "-t" + timeMs + ".png");
        ImageIO.write(actual, "png", actualPath.toFile());
        return actual;
    }

    /** 多帧 snapshot：对每个采样时刻插值 + rasterize + 比基线。 */
    @Test
    void timelineSnapshotMatchesBaseline() throws IOException {
        ProjectState base = loadFixture();
        Timeline timeline = activeTimeline(base);
        for (int t : SAMPLE_TIMES) {
            BufferedImage actual = renderAt(base, timeline, t);
            assertFrameMatchesBaseline(actual, t);
        }
    }

    /**
     * 假绿互证：插值器若没生效，所有采样时刻产出同一张图，各帧自比基线仍可能全绿（基线也错）。
     * 此断言独立要求 t=0 与 t=250 两帧有像素差（mover x 位移 + fader opacity 衰减 + grower 变大），
     * 把这种假绿挡在外面。
     */
    @Test
    void framesDiffer() throws IOException {
        ProjectState base = loadFixture();
        Timeline timeline = activeTimeline(base);
        BufferedImage frame0 = renderAt(base, timeline, 0);
        BufferedImage frame250 = renderAt(base, timeline, 250);
        int diff = countDifferentPixels(frame0, frame250);
        assertTrue(diff > 0,
                "t=0 与 t=250 应有像素差（插值生效证据）—— 若相等说明插值器未应用或 fixture 退化");
    }

    /** 与 {@code expected/14-timeline-easing-t<t>.png} 比；首跑生成基线并告警。 */
    private void assertFrameMatchesBaseline(BufferedImage actual, int timeMs) throws IOException {
        Path expectedPath = EXPECTED_DIR.resolve(FIXTURE + "-t" + timeMs + ".png");
        Path actualPath = ACTUAL_DIR.resolve(FIXTURE + "-t" + timeMs + ".png");
        if (!Files.exists(expectedPath)) {
            Files.createDirectories(expectedPath.getParent());
            Files.copy(actualPath, expectedPath);
            System.err.println("[SNAPSHOT] BASELINE CREATED: " + expectedPath
                    + " — review and `git add` to pin; test passes this run");
            return;
        }

        BufferedImage expected = ImageIO.read(expectedPath.toFile());
        assertEquals(expected.getWidth(), actual.getWidth(),
                FIXTURE + " t" + timeMs + " width mismatch");
        assertEquals(expected.getHeight(), actual.getHeight(),
                FIXTURE + " t" + timeMs + " height mismatch");

        DiffResult diff = pixelDiff(actual, expected);
        Path diffPath = DIFF_DIR.resolve(FIXTURE + "-t" + timeMs + ".png");
        ImageIO.write(diff.image(), "png", diffPath.toFile());

        double ratio = (double) diff.differentPixels() / (actual.getWidth() * actual.getHeight());
        assertTrue(ratio < TOLERANCE,
                String.format("%s t%d diff=%.3f%% > %.1f%% (actual=%s, expected=%s, diff=%s)",
                        FIXTURE, timeMs, ratio * 100, TOLERANCE * 100,
                        actualPath.getFileName(), expectedPath.getFileName(), diffPath.getFileName()));
    }

    private static int countDifferentPixels(BufferedImage a, BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        int diff = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((a.getRGB(x, y) & 0xFFFFFF) != (b.getRGB(x, y) & 0xFFFFFF)) diff++;
            }
        }
        return diff;
    }

    private record DiffResult(BufferedImage image, int differentPixels) {}

    private static DiffResult pixelDiff(BufferedImage a, BufferedImage b) {
        int w = a.getWidth();
        int h = a.getHeight();
        BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int different = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y) & 0xFFFFFF;
                int pb = b.getRGB(x, y) & 0xFFFFFF;
                if (pa != pb) {
                    different++;
                    diff.setRGB(x, y, 0xFF0000); // 差异红
                } else {
                    int gray = ((pa >> 16) & 0xff) / 4
                            + ((pa >> 8) & 0xff) / 4
                            + (pa & 0xff) / 4;
                    diff.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
                }
            }
        }
        return new DiffResult(diff, different);
    }
}
