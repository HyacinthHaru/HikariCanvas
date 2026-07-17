package ac.haru.hikaricanvas.benchmark;

import ac.haru.hikaricanvas.state.BlendMode;
import ac.haru.hikaricanvas.state.BrushPoint;
import ac.haru.hikaricanvas.state.BrushStrokeElement;
import ac.haru.hikaricanvas.state.CircleElement;
import ac.haru.hikaricanvas.state.Effects;
import ac.haru.hikaricanvas.state.Fill;
import ac.haru.hikaricanvas.state.Glow;
import ac.haru.hikaricanvas.state.IconElement;
import ac.haru.hikaricanvas.state.ImageElement;
import ac.haru.hikaricanvas.state.LinearGradient;
import ac.haru.hikaricanvas.state.Mask;
import ac.haru.hikaricanvas.state.PathElement;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.RadialGradient;
import ac.haru.hikaricanvas.state.RectElement;
import ac.haru.hikaricanvas.state.RenderMode;
import ac.haru.hikaricanvas.state.Shadow;
import ac.haru.hikaricanvas.state.ShapeElement;
import ac.haru.hikaricanvas.state.Stop;
import ac.haru.hikaricanvas.state.Stroke;
import ac.haru.hikaricanvas.state.TextElement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

/**
 * Benchmark 场景工厂。用<b>单一固定 seed</b> 确定性程序生成一组
 * {@link BenchmarkScene}，覆盖全部元素类型 + 全部特效路径 + 真实混合场景 + 尺寸缩放梯度，
 * 供纯服务端 rasterize / palette 量化压测（headless，零 Bukkit / 地图池 / 网络依赖）。
 *
 * <p>设计见 {@code docs/dynamic-data.md §13.3} 与 {@code PROPOSAL.md §5.2.7}。</p>
 *
 * <h2>确定性契约</h2>
 * <ul>
 *   <li>所有随机性源自 {@link #SEED} 这一个常量；每个场景构造前 {@code rng} 用
 *       {@code SEED + 场景固定偏移} 重新播种，使<b>同一场景的内容与构造顺序无关</b>
 *       （即便未来增删场景、改 generate 顺序，已有场景仍逐元素相同）。</li>
 *   <li>元素 id 来自确定性计数器 {@code "e-bench-" + n++}，每个场景内从 0 起。</li>
 *   <li><b>禁用</b>：未播种随机 / wall-clock（{@code currentTimeMillis} / {@code nanoTime}）/
 *       {@code UUID.randomUUID}。同进程内两次 {@link #generate()} 必产生结构相同的场景。</li>
 * </ul>
 *
 * <p><b>注意：</b> {@link ProjectState} 内部的 layer id 由其构造器用 {@code UUID.randomUUID}
 * 生成，不在本类控制范围内——但 layer id 不参与 rasterize 像素输出，故不影响 benchmark
 * 结果的确定性（rasterize 只读 canvas 尺寸 + 元素几何 / 颜色）。</p>
 */
public final class SceneLibrary {

    /** 固定 seed —— ASCII "HIKARI" 的 6 字节（0x48 0x49 0x4B 0x41 0x52 0x49）。 */
    private static final long SEED = 0x4849_4B41_5249L;

    /** 单 tile 边长（px），与 {@code CanvasCompositor.MAP_SIZE} 对齐。 */
    private static final int TILE = 128;

    /** 单元素饱和场景每种元素的实例数。 */
    private static final int SATURATION = 24;

    /** 一组确定性的颜色盘（不含 alpha），供 nextColor 取样。 */
    private static final String[] PALETTE = {
            "#E64553", "#FE640B", "#DF8E1D", "#40A02B", "#179299",
            "#1E66F5", "#7287FD", "#8839EF", "#EA76CB", "#DC8A78",
            "#04A5E5", "#209FB5", "#D20F39", "#FF8C42", "#2E7D32",
            "#1976D2",
    };

    /** 一组确定性的图标资源名（fa-solid pack，IconRegistry 内置覆盖）。 */
    private static final String[] ICONS = {
            "fa-solid/heart", "fa-solid/star", "fa-solid/train", "fa-solid/bolt",
            "fa-solid/house", "fa-solid/gear", "fa-solid/bell", "fa-solid/flag",
            "fa-solid/fire", "fa-solid/leaf", "fa-solid/cloud", "fa-solid/moon",
    };

    /** generate() 结果缓存（确定性重建，故缓存安全）。 */
    private List<BenchmarkScene> cache;

    public SceneLibrary() {
    }

    // ====================================================================
    // 公开 API
    // ====================================================================

    /** 生成全部场景；结果缓存（同进程多次调用返回结构相同的列表）。 */
    public List<BenchmarkScene> generate() {
        if (cache == null) {
            cache = buildAll();
        }
        return cache;
    }

    /**
     * 按 selector 选取场景。
     *
     * <ul>
     *   <li>{@code "all"} / {@code null} / 空白 → {@link #generate()} 全集</li>
     *   <li>等于某个 category 常量（element-isolation / effect / mixed）→ 该类全部场景</li>
     *   <li>否则当作场景 id → {@link #byId(String)} 的单元素或空列表</li>
     * </ul>
     */
    public List<BenchmarkScene> select(String selector) {
        if (selector == null || selector.isBlank() || "all".equalsIgnoreCase(selector.trim())) {
            return generate();
        }
        String sel = selector.trim();
        if (BenchmarkScene.CATEGORY_ELEMENT_ISOLATION.equals(sel)
                || BenchmarkScene.CATEGORY_EFFECT.equals(sel)
                || BenchmarkScene.CATEGORY_MIXED.equals(sel)) {
            List<BenchmarkScene> out = new ArrayList<>();
            for (BenchmarkScene s : generate()) {
                if (s.category().equals(sel)) out.add(s);
            }
            return out;
        }
        return byId(sel).map(List::of).orElseGet(List::of);
    }

    /** 按 id 精确查找。 */
    public Optional<BenchmarkScene> byId(String id) {
        if (id == null) return Optional.empty();
        for (BenchmarkScene s : generate()) {
            if (s.id().equals(id)) return Optional.of(s);
        }
        return Optional.empty();
    }

    /** 全部 category 名（去重，按场景首次出现顺序稳定排列）。 */
    public List<String> categories() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (BenchmarkScene s : generate()) {
            set.add(s.category());
        }
        return new ArrayList<>(set);
    }

    // ====================================================================
    // 场景构建总装
    // ====================================================================

    private List<BenchmarkScene> buildAll() {
        List<BenchmarkScene> out = new ArrayList<>();

        // A) 单元素饱和（每种元素一面 2x2 墙，~24 实例）
        out.add(isolationText());
        out.add(isolationVariableText());
        out.add(isolationRect());
        out.add(isolationCircle());
        out.add(isolationStar());
        out.add(isolationPath());
        out.add(isolationImage());
        out.add(isolationBrush());
        out.add(isolationIcon());

        // B) 特效
        out.add(effectGradient());
        out.add(effectDither());
        out.add(effectBlend());
        out.add(effectOpacity());
        out.add(effectTextEffects());

        // C) 真实混合
        out.add(mixedSubwaySign());
        out.add(mixedWelcomeWall());
        out.add(mixedDenseDashboard());

        // D) 尺寸缩放梯度（同一密集混合构图在 1x1 / 2x2 / 4x4 / 8x8 下渲染）
        out.add(sizeScaled(1));
        out.add(sizeScaled(2));
        out.add(sizeScaled(4));
        out.add(sizeScaled(8));

        return out;
    }

    // ====================================================================
    // A) 单元素饱和场景
    // ====================================================================

    private BenchmarkScene isolationText() {
        Ctx c = ctx(1);
        ProjectState st = new ProjectState(2, 2);
        int w = 2 * TILE, h = 2 * TILE;
        for (int i = 0; i < SATURATION; i++) {
            int ex = c.rng.nextInt(w - 64);
            int ey = c.rng.nextInt(h - 24);
            st.addElement(new TextElement(
                    c.id(), ex, ey, 64, 20, 0, false, true,
                    "Hi" + i, "inter", 14 + c.rng.nextInt(10), nextColor(c),
                    "left", 0f, 1.2f, false, null,
                    null, BlendMode.NORMAL, RenderMode.CLEAN, null, null));
        }
        return new BenchmarkScene("text-saturated-2x2", "Text saturated 2x2",
                BenchmarkScene.CATEGORY_ELEMENT_ISOLATION, "text", 2, 2, st);
    }

    /**
     * 含变量占位符的文本场景。{@code text} 嵌入字面 {@code ${var:user/online}}——
     * 渲染期 Compositor 会按"缺值 fallback"路径处理（无需 VariableStore），主要压测
     * 文本布局 + 占位符解析正则。
     */
    private BenchmarkScene isolationVariableText() {
        Ctx c = ctx(2);
        ProjectState st = new ProjectState(2, 2);
        int w = 2 * TILE, h = 2 * TILE;
        for (int i = 0; i < SATURATION; i++) {
            int ex = c.rng.nextInt(w - 96);
            int ey = c.rng.nextInt(h - 24);
            st.addElement(new TextElement(
                    c.id(), ex, ey, 96, 20, 0, false, true,
                    "在线: ${var:user/online}", "source_han_sans",
                    14 + c.rng.nextInt(8), nextColor(c),
                    "left", 0f, 1.2f, false, null,
                    null, BlendMode.NORMAL, RenderMode.CLEAN, null, null));
        }
        return new BenchmarkScene("variable-text-2x2", "Variable text 2x2",
                BenchmarkScene.CATEGORY_ELEMENT_ISOLATION, "text", 2, 2, st);
    }

    private BenchmarkScene isolationRect() {
        Ctx c = ctx(3);
        ProjectState st = new ProjectState(2, 2);
        int w = 2 * TILE, h = 2 * TILE;
        for (int i = 0; i < SATURATION; i++) {
            int rw = 16 + c.rng.nextInt(48);
            int rh = 16 + c.rng.nextInt(48);
            int ex = c.rng.nextInt(w - rw);
            int ey = c.rng.nextInt(h - rh);
            st.addElement(new RectElement(
                    c.id(), ex, ey, rw, rh, 0, false, true,
                    Fill.solid(nextColor(c)), null,
                    null, BlendMode.NORMAL, RenderMode.CLEAN));
        }
        return new BenchmarkScene("rect-saturated-2x2", "Rect saturated 2x2",
                BenchmarkScene.CATEGORY_ELEMENT_ISOLATION, "rect", 2, 2, st);
    }

    private BenchmarkScene isolationCircle() {
        Ctx c = ctx(4);
        ProjectState st = new ProjectState(2, 2);
        int w = 2 * TILE, h = 2 * TILE;
        for (int i = 0; i < SATURATION; i++) {
            int d = 16 + c.rng.nextInt(48);
            int ex = c.rng.nextInt(w - d);
            int ey = c.rng.nextInt(h - d);
            st.addElement(new CircleElement(
                    c.id(), ex, ey, d, d, 0, false, true,
                    Fill.solid(nextColor(c)),
                    new Stroke(2, nextColor(c)),
                    null, BlendMode.NORMAL, RenderMode.CLEAN));
        }
        return new BenchmarkScene("circle-saturated-2x2", "Circle saturated 2x2",
                BenchmarkScene.CATEGORY_ELEMENT_ISOLATION, "circle", 2, 2, st);
    }

    private BenchmarkScene isolationStar() {
        Ctx c = ctx(5);
        ProjectState st = new ProjectState(2, 2);
        int w = 2 * TILE, h = 2 * TILE;
        for (int i = 0; i < SATURATION; i++) {
            int d = 24 + c.rng.nextInt(40);
            int ex = c.rng.nextInt(w - d);
            int ey = c.rng.nextInt(h - d);
            int sides = 5 + c.rng.nextInt(4); // 5..8 角星
            st.addElement(new ShapeElement(
                    c.id(), ex, ey, d, d, 0, false, true,
                    "star", sides, 0.45f,
                    Fill.solid(nextColor(c)), null,
                    null, BlendMode.NORMAL, RenderMode.CLEAN));
        }
        return new BenchmarkScene("shape-star-saturated-2x2", "Star saturated 2x2",
                BenchmarkScene.CATEGORY_ELEMENT_ISOLATION, "shape", 2, 2, st);
    }

    private BenchmarkScene isolationPath() {
        Ctx c = ctx(6);
        ProjectState st = new ProjectState(2, 2);
        int w = 2 * TILE, h = 2 * TILE;
        for (int i = 0; i < SATURATION; i++) {
            int ex = c.rng.nextInt(w - 64);
            int ey = c.rng.nextInt(h - 64);
            // 相对 element 原点的折线 + 二次曲线（M/L/Q）
            int ax = 8 + c.rng.nextInt(24);
            int ay = 8 + c.rng.nextInt(24);
            int bx = 24 + c.rng.nextInt(32);
            int by = 24 + c.rng.nextInt(32);
            String d = "M0 0 L" + ax + " " + ay + " Q" + bx + " 0 " + bx + " " + by;
            st.addElement(new PathElement(
                    c.id(), ex, ey, 64, 64, 0, false, true,
                    d, null,
                    new Stroke(2 + c.rng.nextInt(3), nextColor(c)),
                    null, (i % 4 == 0) ? "arrow" : null,
                    null, BlendMode.NORMAL, RenderMode.CLEAN, null));
        }
        return new BenchmarkScene("path-saturated-2x2", "Path saturated 2x2",
                BenchmarkScene.CATEGORY_ELEMENT_ISOLATION, "path", 2, 2, st);
    }

    /**
     * 图片元素饱和场景。{@code source} 是确定性 16-hex（满足 {@code ^[0-9a-f]{16}$}）；
     * 真实文件大概率缺失，渲染器走"占位符"路径——这本身就是 benchmark 关心的成本之一
     * （缺图占位绘制 + bbox 计算）。
     */
    private BenchmarkScene isolationImage() {
        Ctx c = ctx(7);
        ProjectState st = new ProjectState(2, 2);
        int w = 2 * TILE, h = 2 * TILE;
        for (int i = 0; i < SATURATION; i++) {
            int sz = 24 + c.rng.nextInt(40);
            int ex = c.rng.nextInt(w - sz);
            int ey = c.rng.nextInt(h - sz);
            Mask mask = (i % 3 == 0)
                    ? new Mask("M0 0 L" + sz + " 0 L" + sz + " " + sz + " Z", false)
                    : null;
            st.addElement(new ImageElement(
                    c.id(), ex, ey, sz, sz, 0, false, true,
                    hex16(c.rng), mask,
                    null, BlendMode.NORMAL, RenderMode.CLEAN));
        }
        return new BenchmarkScene("image-saturated-2x2", "Image saturated 2x2",
                BenchmarkScene.CATEGORY_ELEMENT_ISOLATION, "image", 2, 2, st);
    }

    private BenchmarkScene isolationBrush() {
        Ctx c = ctx(8);
        ProjectState st = new ProjectState(2, 2);
        int w = 2 * TILE, h = 2 * TILE;
        for (int i = 0; i < SATURATION; i++) {
            int bw = 40 + c.rng.nextInt(48);
            int bh = 40 + c.rng.nextInt(48);
            int ex = c.rng.nextInt(w - bw);
            int ey = c.rng.nextInt(h - bh);
            List<BrushPoint> pts = new ArrayList<>();
            int n = 6 + c.rng.nextInt(8);
            for (int p = 0; p < n; p++) {
                double px = c.rng.nextInt(bw);
                double py = c.rng.nextInt(bh);
                double pr = 0.3 + c.rng.nextDouble() * 0.7;
                pts.add(new BrushPoint(px, py, pr));
            }
            st.addElement(new BrushStrokeElement(
                    c.id(), ex, ey, bw, bh, 0, false, true,
                    pts, 4 + c.rng.nextInt(6),
                    Fill.solid(nextColor(c)),
                    true, false,
                    null, BlendMode.NORMAL, RenderMode.CLEAN));
        }
        return new BenchmarkScene("brush-saturated-2x2", "Brush saturated 2x2",
                BenchmarkScene.CATEGORY_ELEMENT_ISOLATION, "brush", 2, 2, st);
    }

    private BenchmarkScene isolationIcon() {
        Ctx c = ctx(9);
        ProjectState st = new ProjectState(2, 2);
        int w = 2 * TILE, h = 2 * TILE;
        for (int i = 0; i < SATURATION; i++) {
            int sz = 20 + c.rng.nextInt(28);
            int ex = c.rng.nextInt(w - sz);
            int ey = c.rng.nextInt(h - sz);
            st.addElement(new IconElement(
                    c.id(), ex, ey, sz, sz, 0, false, true,
                    ICONS[c.rng.nextInt(ICONS.length)], null,
                    null, BlendMode.NORMAL, RenderMode.CLEAN,
                    Fill.solid(nextColor(c))));
        }
        return new BenchmarkScene("icon-saturated-2x2", "Icon saturated 2x2",
                BenchmarkScene.CATEGORY_ELEMENT_ISOLATION, "icon", 2, 2, st);
    }

    // ====================================================================
    // B) 特效场景
    // ====================================================================

    private BenchmarkScene effectGradient() {
        Ctx c = ctx(20);
        ProjectState st = new ProjectState(2, 2);
        int w = 2 * TILE, h = 2 * TILE;
        for (int i = 0; i < 16; i++) {
            int rw = 32 + c.rng.nextInt(48);
            int rh = 32 + c.rng.nextInt(48);
            int ex = c.rng.nextInt(w - rw);
            int ey = c.rng.nextInt(h - rh);
            Fill fill;
            if (i % 2 == 0) {
                fill = new LinearGradient(c.rng.nextInt(360), List.of(
                        new Stop(0.0, nextColor(c)),
                        new Stop(1.0, nextColor(c))));
            } else {
                fill = new RadialGradient(0.5, 0.5, 1.0, List.of(
                        new Stop(0.0, nextColor(c)),
                        new Stop(0.6, nextColor(c)),
                        new Stop(1.0, nextColor(c))));
            }
            st.addElement(new RectElement(
                    c.id(), ex, ey, rw, rh, 0, false, true,
                    fill, null,
                    null, BlendMode.NORMAL, RenderMode.CLEAN));
        }
        return new BenchmarkScene("effect-gradient-2x2", "Effect gradient 2x2",
                BenchmarkScene.CATEGORY_EFFECT, "mixed", 2, 2, st);
    }

    private BenchmarkScene effectDither() {
        Ctx c = ctx(21);
        ProjectState st = new ProjectState(2, 2);
        int w = 2 * TILE, h = 2 * TILE;
        for (int i = 0; i < 16; i++) {
            int rw = 32 + c.rng.nextInt(56);
            int rh = 32 + c.rng.nextInt(56);
            int ex = c.rng.nextInt(w - rw);
            int ey = c.rng.nextInt(h - rh);
            Fill fill = new LinearGradient(45, List.of(
                    new Stop(0.0, nextColor(c)),
                    new Stop(1.0, nextColor(c))));
            st.addElement(new RectElement(
                    c.id(), ex, ey, rw, rh, 0, false, true,
                    fill, null,
                    null, BlendMode.NORMAL, RenderMode.DITHER));
        }
        return new BenchmarkScene("effect-dither-2x2", "Effect dither 2x2",
                BenchmarkScene.CATEGORY_EFFECT, "mixed", 2, 2, st);
    }

    private BenchmarkScene effectBlend() {
        Ctx c = ctx(22);
        ProjectState st = new ProjectState(2, 2);
        int w = 2 * TILE, h = 2 * TILE;
        BlendMode[] modes = {BlendMode.MULTIPLY, BlendMode.SCREEN, BlendMode.OVERLAY};
        for (int i = 0; i < 18; i++) {
            int rw = 48 + c.rng.nextInt(48);
            int rh = 48 + c.rng.nextInt(48);
            int ex = c.rng.nextInt(w - rw);
            int ey = c.rng.nextInt(h - rh);
            st.addElement(new RectElement(
                    c.id(), ex, ey, rw, rh, 0, false, true,
                    Fill.solid(nextColor(c)), null,
                    0.85f, modes[i % modes.length], RenderMode.CLEAN));
        }
        return new BenchmarkScene("effect-blend-2x2", "Effect blend 2x2",
                BenchmarkScene.CATEGORY_EFFECT, "mixed", 2, 2, st);
    }

    private BenchmarkScene effectOpacity() {
        Ctx c = ctx(23);
        ProjectState st = new ProjectState(2, 2);
        int w = 2 * TILE, h = 2 * TILE;
        for (int i = 0; i < 18; i++) {
            int rw = 48 + c.rng.nextInt(56);
            int rh = 48 + c.rng.nextInt(56);
            int ex = c.rng.nextInt(w - rw);
            int ey = c.rng.nextInt(h - rh);
            float op = 0.3f + c.rng.nextFloat() * 0.4f; // 0.3..0.7
            st.addElement(new RectElement(
                    c.id(), ex, ey, rw, rh, 0, false, true,
                    Fill.solid(nextColor(c)), null,
                    op, BlendMode.NORMAL, RenderMode.CLEAN));
        }
        return new BenchmarkScene("effect-opacity-2x2", "Effect opacity 2x2",
                BenchmarkScene.CATEGORY_EFFECT, "mixed", 2, 2, st);
    }

    private BenchmarkScene effectTextEffects() {
        Ctx c = ctx(24);
        ProjectState st = new ProjectState(2, 2);
        int w = 2 * TILE, h = 2 * TILE;
        for (int i = 0; i < 12; i++) {
            int ex = c.rng.nextInt(w - 96);
            int ey = c.rng.nextInt(h - 32);
            Effects fx = new Effects(
                    new Stroke(2, nextColor(c)),
                    new Shadow(2, 2, "#00000080"),
                    new Glow(4, nextColor(c)));
            st.addElement(new TextElement(
                    c.id(), ex, ey, 96, 28, 0, false, true,
                    "霓虹 FX" + i, "smiley_sans", 20 + c.rng.nextInt(8), nextColor(c),
                    "left", 0f, 1.2f, false, fx,
                    null, BlendMode.NORMAL, RenderMode.CLEAN, true, false));
        }
        return new BenchmarkScene("effect-text-2x2", "Effect text 2x2",
                BenchmarkScene.CATEGORY_EFFECT, "mixed", 2, 2, st);
    }

    // ====================================================================
    // C) 真实混合场景
    // ====================================================================

    /** 地铁屏：深色横条 bar + 站名文字 + 几个图标 + 一条彩色线带（2x1）。 */
    private BenchmarkScene mixedSubwaySign() {
        Ctx c = ctx(40);
        ProjectState st = new ProjectState(2, 1);
        int w = 2 * TILE, h = TILE;

        // 深色背景横条
        st.addElement(new RectElement(
                c.id(), 0, 0, w, h, 0, false, true,
                Fill.solid("#101418"), null,
                null, BlendMode.NORMAL, RenderMode.CLEAN));
        // 顶部彩色线带（线路色）
        st.addElement(new RectElement(
                c.id(), 0, 0, w, 8, 0, false, true,
                Fill.solid("#E64553"), null,
                null, BlendMode.NORMAL, RenderMode.CLEAN));
        // 站名大字
        st.addElement(new TextElement(
                c.id(), 16, 36, w - 120, 48, 0, false, true,
                "郑州东 Zhengzhou East", "source_han_sans", 36, "#FFFFFF",
                "left", 0f, 1.1f, false, null,
                null, BlendMode.NORMAL, RenderMode.CLEAN, true, false));
        // 下行小字（车次 / 方向）
        st.addElement(new TextElement(
                c.id(), 16, 92, w - 120, 24, 0, false, true,
                "A01 → 机场  ETA 02:30", "inter", 18, "#A6ADC8",
                "left", 0f, 1.2f, false, null,
                null, BlendMode.NORMAL, RenderMode.CLEAN, null, null));
        // 右侧若干图标
        for (int i = 0; i < 3; i++) {
            st.addElement(new IconElement(
                    c.id(), w - 96 + i * 28, 40, 24, 24, 0, false, true,
                    ICONS[i], null,
                    null, BlendMode.NORMAL, RenderMode.CLEAN,
                    Fill.solid("#F9E2AF")));
        }
        return new BenchmarkScene("subway-sign-2x1", "Subway sign 2x1",
                BenchmarkScene.CATEGORY_MIXED, "mixed", 2, 1, st);
    }

    /** 欢迎墙：LinearGradient 画布背景 + 大标题 + 副标题 + 2-3 图标（4x2）。 */
    private BenchmarkScene mixedWelcomeWall() {
        Ctx c = ctx(41);
        int w = 4 * TILE, h = 2 * TILE;
        Fill bg = new LinearGradient(90, List.of(
                new Stop(0.0, "#1E66F5"),
                new Stop(1.0, "#8839EF")));
        ProjectState st = new ProjectState(4, 2);
        st.canvas(new ProjectState.Canvas(4, 2, bg));

        // 大标题
        st.addElement(new TextElement(
                c.id(), 48, 60, w - 96, 80, 0, false, true,
                "欢迎来到 HikariCraft", "smiley_sans", 64, "#FFFFFF",
                "center", 0f, 1.1f, false, null,
                null, BlendMode.NORMAL, RenderMode.CLEAN, true, false));
        // 副标题
        st.addElement(new TextElement(
                c.id(), 48, 160, w - 96, 36, 0, false, true,
                "在线 ${var:user/online} 人 · 祝你玩得开心", "source_han_sans", 24, "#E6E9EF",
                "center", 0f, 1.2f, false, null,
                null, BlendMode.NORMAL, RenderMode.CLEAN, null, true));
        // 装饰图标行
        for (int i = 0; i < 3; i++) {
            st.addElement(new IconElement(
                    c.id(), w / 2 - 60 + i * 48, 210, 36, 36, 0, false, true,
                    ICONS[(i + 3) % ICONS.length], null,
                    0.9f, BlendMode.NORMAL, RenderMode.CLEAN,
                    Fill.solid("#F9E2AF")));
        }
        return new BenchmarkScene("welcome-wall-4x2", "Welcome wall 4x2",
                BenchmarkScene.CATEGORY_MIXED, "mixed", 4, 2, st);
    }

    /** 密集仪表盘：~50 个小型混合元素（2x2）。 */
    private BenchmarkScene mixedDenseDashboard() {
        Ctx c = ctx(42);
        ProjectState st = new ProjectState(2, 2);
        fillDenseMixed(c, st, 2, 2, 50);
        return new BenchmarkScene("dense-dashboard-2x2", "Dense dashboard 2x2",
                BenchmarkScene.CATEGORY_MIXED, "mixed", 2, 2, st);
    }

    // ====================================================================
    // D) 尺寸缩放梯度
    // ====================================================================

    /**
     * 同一密集混合构图在 {@code tiles x tiles} 下渲染。元素密度随面积线性增长
     * （每 tile ~12 个混合元素），让 RENDER 成本随像素数缩放，供回归曲线拟合。
     */
    private BenchmarkScene sizeScaled(int tiles) {
        Ctx c = ctx(60 + tiles);
        ProjectState st = new ProjectState(tiles, tiles);
        int count = tiles * tiles * 12;
        fillDenseMixed(c, st, tiles, tiles, count);
        String id = "size-" + tiles + "x" + tiles;
        return new BenchmarkScene(id, "Size scaling " + tiles + "x" + tiles,
                BenchmarkScene.CATEGORY_MIXED, "mixed", tiles, tiles, st);
    }

    /**
     * 往 {@code st} 填充 {@code count} 个确定性混合元素（rect / circle / text / icon /
     * shape / path 轮转），坐标恒在画布内。供 dense-dashboard 与 size-* 复用。
     */
    private void fillDenseMixed(Ctx c, ProjectState st, int tilesW, int tilesH, int count) {
        int w = tilesW * TILE, h = tilesH * TILE;
        for (int i = 0; i < count; i++) {
            int sz = 12 + c.rng.nextInt(28);
            int ex = c.rng.nextInt(Math.max(1, w - sz));
            int ey = c.rng.nextInt(Math.max(1, h - sz));
            switch (i % 6) {
                case 0 -> st.addElement(new RectElement(
                        c.id(), ex, ey, sz, sz, 0, false, true,
                        Fill.solid(nextColor(c)), null,
                        null, BlendMode.NORMAL, RenderMode.CLEAN));
                case 1 -> st.addElement(new CircleElement(
                        c.id(), ex, ey, sz, sz, 0, false, true,
                        Fill.solid(nextColor(c)), null,
                        null, BlendMode.NORMAL, RenderMode.CLEAN));
                case 2 -> st.addElement(new TextElement(
                        c.id(), ex, ey, 56, 18, 0, false, true,
                        "v" + i, "inter", 12 + c.rng.nextInt(6), nextColor(c),
                        "left", 0f, 1.2f, false, null,
                        null, BlendMode.NORMAL, RenderMode.CLEAN, null, null));
                case 3 -> st.addElement(new IconElement(
                        c.id(), ex, ey, sz, sz, 0, false, true,
                        ICONS[c.rng.nextInt(ICONS.length)], null,
                        null, BlendMode.NORMAL, RenderMode.CLEAN,
                        Fill.solid(nextColor(c))));
                case 4 -> st.addElement(new ShapeElement(
                        c.id(), ex, ey, sz, sz, 0, false, true,
                        "polygon", 3 + c.rng.nextInt(6), null,
                        Fill.solid(nextColor(c)), null,
                        null, BlendMode.NORMAL, RenderMode.CLEAN));
                default -> st.addElement(new PathElement(
                        c.id(), ex, ey, sz, sz, 0, false, true,
                        "M0 0 L" + sz + " " + sz, null,
                        new Stroke(2, nextColor(c)), null, null,
                        null, BlendMode.NORMAL, RenderMode.CLEAN, null));
            }
        }
    }

    // ====================================================================
    // 确定性辅助
    // ====================================================================

    /** 每个场景的本地构建上下文：独立播种的 rng + 独立 id 计数器。 */
    private static final class Ctx {
        final Random rng;
        int counter;

        Ctx(long seed) {
            this.rng = new Random(seed);
            this.counter = 0;
        }

        /** 取下一个确定性元素 id。 */
        String id() {
            return "e-bench-" + (counter++);
        }
    }

    /**
     * 用 {@code SEED + offset} 播种构建上下文。每个场景用<b>固定唯一 offset</b>，
     * 使其内容与场景生成顺序无关——这是确定性契约的关键。
     */
    private static Ctx ctx(int offset) {
        return new Ctx(SEED + offset * 1_000_003L);
    }

    /** 从固定调色盘取一个确定性颜色。 */
    private static String nextColor(Ctx c) {
        return PALETTE[c.rng.nextInt(PALETTE.length)];
    }

    /** 生成确定性的 16 字符小写 hex（满足 ImageElement.source 的 {@code ^[0-9a-f]{16}$}）。 */
    private static String hex16(Random rng) {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(Character.forDigit(rng.nextInt(16), 16));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
