package moe.hikari.canvas.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import moe.hikari.canvas.state.Easing;
import moe.hikari.canvas.state.EasingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * {@link EasingSolver} 双端共享向量 runner + 解析性质单测（0.6 P3）。
 * 数学权威见 {@code docs/rendering.md §9.3}；一致性验证范式见 §9.6。
 *
 * <p>本测试与前端 vitest 共享同一份 {@code rendering-test/easing-vectors.json}（第三方
 * Python 参照实现生成，容差 {@value #TOLERANCE}）——两端各跑同向量、对同 expected，
 * 任一端浮点漂移即红，守住「缓动双端逐位等价」硬纪律（D8）。</p>
 *
 * <p>向量路径与 {@link RendererSnapshotTest} 同范式：gradle test 工作目录 = {@code {root}/plugin}，
 * 故 {@code user.dir}（即 plugin/）上溯一级到仓库根再进 {@code rendering-test/}。</p>
 */
class EasingSolverTest {

    /** 与 easing-vectors.json {@code tolerance} 字段及前端一致。 */
    private static final double TOLERANCE = 1e-6;

    private static final Path VECTORS = Path.of(System.getProperty("user.dir"))
            .resolve("../rendering-test/easing-vectors.json");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ──────────────────────────────────────────────────────────
    //  共享向量 runner（每条 case × 每个 sample 一个 dynamic test）
    // ──────────────────────────────────────────────────────────

    @TestFactory
    List<org.junit.jupiter.api.DynamicTest> sharedVectors() throws IOException {
        JsonNode root = MAPPER.readTree(Files.newBufferedReader(VECTORS));
        // 容差以向量文件自带 tolerance 为准（与生成器锁定一致）
        double tol = root.has("tolerance") ? root.get("tolerance").asDouble() : TOLERANCE;
        assertEquals(TOLERANCE, tol, 0.0, "向量文件 tolerance 应为 1e-6（与两端常量一致）");

        List<org.junit.jupiter.api.DynamicTest> tests = new ArrayList<>();
        JsonNode cases = root.get("cases");
        assertTrue(cases != null && cases.size() > 0, "easing-vectors.json 应含至少一条 case");
        for (JsonNode c : cases) {
            JsonNode easingNode = c.get("easing");
            // easing 节点直接反序列化为 Easing record —— EasingType 的 @JsonProperty wire 映射
            // 与 bezier 字段自动解析，无需手写 type 字符串→enum 表（B1 任务推荐路径）。
            Easing easing = MAPPER.convertValue(easingNode, Easing.class);
            String label = easingNode.get("type").asText()
                    + (easingNode.has("bezier") ? " " + easingNode.get("bezier") : "");
            for (JsonNode sample : c.get("samples")) {
                double t = sample.get(0).asDouble();
                double expected = sample.get(1).asDouble();
                tests.add(dynamicTest(label + " @t=" + t, () ->
                        assertEquals(expected, EasingSolver.ease(easing, t), tol,
                                "缓动 " + label + " 在 t=" + t + " 应与共享向量 expected 一致")));
            }
        }
        return tests;
    }

    // ──────────────────────────────────────────────────────────
    //  解析性质：端点精确
    // ──────────────────────────────────────────────────────────

    @Test
    void endpointsExactForAnyControlPoints() {
        // §9.3 边界捷径：local<=0 → 0、local>=1 → 1，与任意控制点无关
        double[][] ctrls = {
                {0.25, 0.1, 0.25, 1.0},
                {0.34, 1.56, 0.64, 1.0},   // 过冲 y > 1
                {0.9, 0.05, 0.1, 0.95},
                {1.0, 1.0, 0.0, 0.0},
        };
        for (double[] cp : ctrls) {
            assertEquals(0.0, EasingSolver.cubicBezier(cp[0], cp[1], cp[2], cp[3], 0.0), 0.0,
                    "t=0 端点必须精确 0");
            assertEquals(1.0, EasingSolver.cubicBezier(cp[0], cp[1], cp[2], cp[3], 1.0), 0.0,
                    "t=1 端点必须精确 1");
            // 越界捷径
            assertEquals(0.0, EasingSolver.cubicBezier(cp[0], cp[1], cp[2], cp[3], -0.5), 0.0,
                    "t<0 → 0");
            assertEquals(1.0, EasingSolver.cubicBezier(cp[0], cp[1], cp[2], cp[3], 1.5), 0.0,
                    "t>1 → 1");
        }
        // ease 入口对预设同样端点精确
        for (EasingType type : List.of(EasingType.EASE_IN, EasingType.EASE_OUT, EasingType.EASE_IN_OUT)) {
            Easing e = new Easing(type, null);
            assertEquals(0.0, EasingSolver.ease(e, 0.0), 0.0, type + " @t=0");
            assertEquals(1.0, EasingSolver.ease(e, 1.0), 0.0, type + " @t=1");
        }
    }

    // ──────────────────────────────────────────────────────────
    //  解析性质：单调性（easeInOut 在 t 网格上不减）
    // ──────────────────────────────────────────────────────────

    @Test
    void easeInOutMonotonicNonDecreasing() {
        // easeInOut 控制点 (0.42,0,0.58,1) 的 By 在 [0,1] 单调非减（y 不超 [0,1]）
        Easing e = new Easing(EasingType.EASE_IN_OUT, null);
        double prev = EasingSolver.ease(e, 0.0);
        for (int i = 1; i <= 100; i++) {
            double t = i / 100.0;
            double v = EasingSolver.ease(e, t);
            assertTrue(v >= prev - TOLERANCE,
                    "easeInOut 在 t=" + t + " 不应回退（prev=" + prev + " v=" + v + "）");
            prev = v;
        }
    }

    // ──────────────────────────────────────────────────────────
    //  解析性质：对称性（easeInOut(t) + easeInOut(1-t) ≈ 1）
    // ──────────────────────────────────────────────────────────

    @Test
    void easeInOutSymmetricAroundMidpoint() {
        // easeInOut 控制点关于中心 (0.5,0.5) 中心对称 → ease(t)+ease(1-t) = 1
        Easing e = new Easing(EasingType.EASE_IN_OUT, null);
        for (int i = 0; i <= 10; i++) {
            double t = i / 10.0;
            double a = EasingSolver.ease(e, t);
            double b = EasingSolver.ease(e, 1.0 - t);
            assertEquals(1.0, a + b, TOLERANCE,
                    "easeInOut 应关于中点对称：ease(" + t + ")+ease(" + (1.0 - t) + ")");
        }
    }

    // ──────────────────────────────────────────────────────────
    //  宽容路径：坏 bezier / null easing → LINEAR
    // ──────────────────────────────────────────────────────────

    @Test
    void nullEasingFallsBackToLinear() {
        // §9.3：easing == null 按 LINEAR 求值
        assertEquals(0.37, EasingSolver.ease(null, 0.37), 0.0, "null easing → 线性恒等");
        assertEquals(0.0, EasingSolver.ease(null, 0.0), 0.0);
        assertEquals(1.0, EasingSolver.ease(null, 1.0), 0.0);
    }

    @Test
    void linearTypeIsIdentity() {
        Easing e = new Easing(EasingType.LINEAR, null);
        for (double t : new double[] {0.0, 0.13, 0.5, 0.77, 1.0}) {
            assertEquals(t, EasingSolver.ease(e, t), 0.0, "LINEAR 恒等 @t=" + t);
        }
    }

    @Test
    void badBezierFallsBackToLinear() {
        // CUBIC_BEZIER 但 bezier 长度 ≠ 4（持久化宽容路径漏进来的坏 blob）→ 按 local 原样
        Easing tooShort = new Easing(EasingType.CUBIC_BEZIER, List.of(0.25, 0.1));
        assertEquals(0.4, EasingSolver.ease(tooShort, 0.4), 0.0, "bezier 长度=2 → 退线性");

        Easing tooLong = new Easing(EasingType.CUBIC_BEZIER, List.of(0.1, 0.2, 0.3, 0.4, 0.5));
        assertEquals(0.6, EasingSolver.ease(tooLong, 0.6), 0.0, "bezier 长度=5 → 退线性");

        // bezier == null（Easing canonical 构造把含 null 元素的列表退成 null）→ 退线性
        Easing nullBezier = new Easing(EasingType.CUBIC_BEZIER, null);
        assertEquals(0.25, EasingSolver.ease(nullBezier, 0.25), 0.0, "bezier=null → 退线性");
    }
}
