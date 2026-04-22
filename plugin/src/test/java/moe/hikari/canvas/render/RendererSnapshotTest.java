package moe.hikari.canvas.render;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import moe.hikari.canvas.state.ProjectState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双端渲染一致性的 Java 端部分（M4-T11），契约对应 {@code docs/rendering.md §8}。
 *
 * <h2>流程</h2>
 * <ol>
 *   <li>从 {@code src/test/resources/fixtures/*.json} 读 ProjectState</li>
 *   <li>{@link CanvasCompositor#rasterize} 产出 BufferedImage</li>
 *   <li>写到 {@code build/test-results/snapshot/actual/*.png}</li>
 *   <li>与 {@code src/test/resources/expected/*.png} 逐像素比</li>
 *   <li>差异比 ≥ 0.5% 则测试失败；首次运行（expected 不存在）自动复制 actual 为 baseline 并告警</li>
 * </ol>
 *
 * <h2>更新 expected</h2>
 * 若渲染行为有意变更，手工查看 {@code build/test-results/snapshot/actual} + 对比
 * {@code build/test-results/snapshot/diff}（红点标差异），确认无误后把 actual 覆盖到
 * {@code src/test/resources/expected}，git commit。
 */
class RendererSnapshotTest {

    private static final double TOLERANCE = 0.005; // 0.5%
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path FIXTURES_DIR = PROJECT_ROOT.resolve("src/test/resources/fixtures");
    private static final Path EXPECTED_DIR = PROJECT_ROOT.resolve("src/test/resources/expected");
    private static final Path ACTUAL_DIR = PROJECT_ROOT.resolve("build/test-results/snapshot/actual");
    private static final Path DIFF_DIR = PROJECT_ROOT.resolve("build/test-results/snapshot/diff");

    private static CanvasCompositor compositor;
    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() throws IOException {
        Logger log = Logger.getLogger(RendererSnapshotTest.class.getName());
        PaletteLut paletteLut = PaletteLut.loadFromClasspath("/palette.json");
        FontRegistry fontRegistry = new FontRegistry(log);
        fontRegistry.loadBuiltIn();
        compositor = new CanvasCompositor(paletteLut, fontRegistry, log);
        mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        Files.createDirectories(ACTUAL_DIR);
        Files.createDirectories(DIFF_DIR);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "01-hello-world",
            "02-chinese-text",
            "03-effects-stroke",
            "04-effects-shadow",
            "05-effects-glow"
    })
    void snapshot(String fixtureName) throws IOException {
        Path fixturePath = FIXTURES_DIR.resolve(fixtureName + ".json");
        assertTrue(Files.exists(fixturePath), "fixture missing: " + fixturePath);

        ProjectState state = mapper.readValue(fixturePath.toFile(), ProjectState.class);
        BufferedImage actual = compositor.rasterize(state);

        Path actualPath = ACTUAL_DIR.resolve(fixtureName + ".png");
        ImageIO.write(actual, "png", actualPath.toFile());

        Path expectedPath = EXPECTED_DIR.resolve(fixtureName + ".png");
        if (!Files.exists(expectedPath)) {
            // 首次跑：把 actual 作为 baseline，告警但不失败；调用方 review 后 git add expected
            Files.createDirectories(expectedPath.getParent());
            Files.copy(actualPath, expectedPath);
            System.err.println("[SNAPSHOT] BASELINE CREATED: " + expectedPath
                    + " — review and `git add` to pin; test passes this run");
            return;
        }

        BufferedImage expected = ImageIO.read(expectedPath.toFile());
        assertEquals(expected.getWidth(), actual.getWidth(),
                fixtureName + " width mismatch (expected " + expected.getWidth()
                        + ", got " + actual.getWidth() + ")");
        assertEquals(expected.getHeight(), actual.getHeight(),
                fixtureName + " height mismatch (expected " + expected.getHeight()
                        + ", got " + actual.getHeight() + ")");

        DiffResult diff = pixelDiff(actual, expected);
        Path diffPath = DIFF_DIR.resolve(fixtureName + ".png");
        ImageIO.write(diff.image(), "png", diffPath.toFile());

        double ratio = (double) diff.differentPixels() / (actual.getWidth() * actual.getHeight());
        assertTrue(ratio < TOLERANCE,
                String.format("%s diff=%.3f%% > %.1f%% (actual=%s, expected=%s, diff=%s)",
                        fixtureName, ratio * 100, TOLERANCE * 100,
                        actualPath.getFileName(), expectedPath.getFileName(), diffPath.getFileName()));
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
                    // 灰阶保留参考（半亮度）便于目测定位
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
