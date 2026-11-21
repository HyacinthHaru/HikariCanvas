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
 * Benchmark 场景工厂：用单一固定 seed 确定性生成 21 个 {@link BenchmarkScene}，覆盖单元素隔离、
 * 特效、真实混合与尺寸缩放梯度四类场景（见 {@code docs/benchmark.md §3.2}）。
 *
 * <h2>确定性契约</h2>
 * <ul>
 *   <li>每个场景使用 {@code SEED + 场景固定偏移} 独立播种，使单场景内容与构建顺序解耦。</li>
 *   <li>元素 id 由场景内独立计数器 {@code "e-bench-" + n++} 生成，不使用时钟或随机 UUID。</li>
 *   <li>{@link ProjectState} 默认图层 id 虽由内部 UUID 生成，但不参与像素光栅化，不影响渲染确定性。</li>
 * </ul>
 */
public final class SceneLibrary {

    /**
     * 单个程序生成的压测场景：完整的 {@link ProjectState} 与分类/尺寸元数据。
     *
     * @param id                  稳定标识（如 {@code "text-saturated-2x2"}）
     * @param name                人类可读名称
     * @param category            场景分组（见 {@link #CATEGORY_ELEMENT_ISOLATION} 等常量）
     * @param dominantElementType 主元素类型（供单元素边际成本归因）
     * @param tilesWide           画布宽（128px map tile 数）
     * @param tilesHigh           画布高（128px map tile 数）
     * @param state               待光栅化的 {@link ProjectState}
     */
    public record BenchmarkScene(
            String id,
            String name,
            String category,
            String dominantElementType,
            int tilesWide,
            int tilesHigh,
            ProjectState state
    ) {
        /** 单元素饱和场景：铺满同一种元素，用于推导单元素边际成本。 */
        public static final String CATEGORY_ELEMENT_ISOLATION = "element-isolation";

        /** 特效场景：渐变、抖动、混合模式、半透明与文字描边/阴影/发光。 */
        public static final String CATEGORY_EFFECT = "effect";

        /** 混合与尺寸梯度场景：地铁屏、欢迎墙、密集仪表盘及 1×1..8×8 缩放梯度。 */
        public static final String CATEGORY_MIXED = "mixed";

        public BenchmarkScene {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("scene id must not be blank");
            }
            if (state == null) {
                throw new IllegalArgumentException("scene state must not be null");
            }
            if (tilesWide < 1 || tilesHigh < 1) {
                throw new IllegalArgumentException("scene must be at least 1x1 tile");
            }
        }

        /** 画布总像素数。 */
        public int pixelCount() {
            return tilesWide * TILE * tilesHigh * TILE;
        }

        /** 地图分块总数。 */
        public int tileCount() {
            return tilesWide * tilesHigh;
        }
    }

    /** 固定 seed —— ASCII "HIKARI" 的 6 字节（0x48 0x49 0x4B 0x41 0x52 0x49）。 */
    private static final long SEED = 0x4849_4B41_5249L;

    /** 单 tile 边长（px），与 {@code CanvasCompositor.MAP_SIZE} 对齐。 */
    private static final int TILE = 128;

    /** 单元素饱和场景每种元素的实例数。 */
    private static final int SATURATION = 24;

    private static final String[] PALETTE = {
            "#E64553", "#FE640B", "#DF8E1D", "#40A02B", "#179299",
            "#1E66F5", "#7287FD", "#8839EF", "#EA76CB", "#DC8A78",
            "#04A5E5", "#209FB5", "#D20F39", "#FF8C42", "#2E7D32",
            "#1976D2",
    };

    private static final String[] ICONS = {
            "fa-solid/heart", "fa-solid/star", "fa-solid/train", "fa-solid/bolt",
            "fa-solid/house", "fa-solid/gear", "fa-solid/bell", "fa-solid/flag",
            "fa-solid/fire", "fa-solid/leaf", "fa-solid/cloud", "fa-solid/moon",
    };

    private List<BenchmarkScene> cache;

    public SceneLibrary() {
    }

    /** 生成全部场景；同实例内缓存复用。 */
    public List<BenchmarkScene> generate() {
        if (cache == null) {
            cache = buildAll();
        }
        return cache;
    }

    /**
     * 按 selector 选取场景：{@code "all"} / null / 空白返回全集；匹配 category 名返回该分类全部场景；
     * 否则按场景 id 精确查找。
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

    /** 按场景 id 精确查找。 */
    public Optional<BenchmarkScene> byId(String id) {
        if (id == null) return Optional.empty();
        for (BenchmarkScene s : generate()) {
            if (s.id().equals(id)) return Optional.of(s);
        }
        return Optional.empty();
    }

    /** 返回全部场景分类名（按首次出现顺序稳定去重）。 */
    public List<String> categories() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (BenchmarkScene s : generate()) {
            set.add(s.category());
        }
        return new ArrayList<>(set);
    }

    private List<BenchmarkScene> buildAll() {
        List<BenchmarkScene> out = new ArrayList<>();

        // 单元素饱和（2x2 墙，每类 24 实例）
        out.add(isolationText());
        out.add(isolationVariableText());
        out.add(isolationRect());
        out.add(isolationCircle());
        out.add(isolationStar());
        out.add(isolationPath());
        out.add(isolationImage());
        out.add(isolationBrush());
        out.add(isolationIcon());

        // 特效
        out.add(effectGradient());
        out.add(effectDither());
        out.add(effectBlend());
        out.add(effectOpacity());
        out.add(effectTextEffects());

        // 真实混合
        out.add(mixedSubwaySign());
        out.add(mixedWelcomeWall());
        out.add(mixedDenseDashboard());

        // 尺寸缩放梯度（1x1 / 2x2 / 4x4 / 8x8）
        out.add(sizeScaled(1));
        out.add(sizeScaled(2));
        out.add(sizeScaled(4));
        out.add(sizeScaled(8));

        return out;
    }

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

    /** 含 {@code ${var:...}} 占位符的文本场景，按缺值 fallback 路径测量占位符解析与排版开销。 */
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
            int sides = 5 + c.rng.nextInt(4);
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
     * 图片与蒙版饱和场景：{@code source} 为确定性 16 位十六进制哈希（满足 {@code ^[0-9a-f]{16}$}），
     * 由 {@link BenchCompositor} 注入的 256×256 合成 RGB 渐变图驱动缩放与蒙版裁剪路径。
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
            float op = 0.3f + c.rng.nextFloat() * 0.4f;
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

    /** 地铁屏（2×1）：深色横条 + 线路色带 + 站名/车次文字 + 右侧图标。 */
    private BenchmarkScene mixedSubwaySign() {
        Ctx c = ctx(40);
        ProjectState st = new ProjectState(2, 1);
        int w = 2 * TILE, h = TILE;

        st.addElement(new RectElement(
                c.id(), 0, 0, w, h, 0, false, true,
                Fill.solid("#101418"), null,
                null, BlendMode.NORMAL, RenderMode.CLEAN));
        st.addElement(new RectElement(
                c.id(), 0, 0, w, 8, 0, false, true,
                Fill.solid("#E64553"), null,
                null, BlendMode.NORMAL, RenderMode.CLEAN));
        st.addElement(new TextElement(
                c.id(), 16, 36, w - 120, 48, 0, false, true,
                "郑州东 Zhengzhou East", "source_han_sans", 36, "#FFFFFF",
                "left", 0f, 1.1f, false, null,
                null, BlendMode.NORMAL, RenderMode.CLEAN, true, false));
        st.addElement(new TextElement(
                c.id(), 16, 92, w - 120, 24, 0, false, true,
                "A01 → 机场  ETA 02:30", "inter", 18, "#A6ADC8",
                "left", 0f, 1.2f, false, null,
                null, BlendMode.NORMAL, RenderMode.CLEAN, null, null));
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

    /** 欢迎墙（4×2）：线性渐变画布背景 + 标题/副标题 + 图标行。 */
    private BenchmarkScene mixedWelcomeWall() {
        Ctx c = ctx(41);
        int w = 4 * TILE;
        Fill bg = new LinearGradient(90, List.of(
                new Stop(0.0, "#1E66F5"),
                new Stop(1.0, "#8839EF")));
        ProjectState st = new ProjectState(4, 2);
        st.canvas(new ProjectState.Canvas(4, 2, bg));

        st.addElement(new TextElement(
                c.id(), 48, 60, w - 96, 80, 0, false, true,
                "欢迎来到 HikariCraft", "smiley_sans", 64, "#FFFFFF",
                "center", 0f, 1.1f, false, null,
                null, BlendMode.NORMAL, RenderMode.CLEAN, true, false));
        st.addElement(new TextElement(
                c.id(), 48, 160, w - 96, 36, 0, false, true,
                "在线 ${var:user/online} 人 · 祝你玩得开心", "source_han_sans", 24, "#E6E9EF",
                "center", 0f, 1.2f, false, null,
                null, BlendMode.NORMAL, RenderMode.CLEAN, null, true));
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

    /** 密集仪表盘（2×2）：50 个混合元素。 */
    private BenchmarkScene mixedDenseDashboard() {
        Ctx c = ctx(42);
        ProjectState st = new ProjectState(2, 2);
        fillDenseMixed(c, st, 2, 2, 50);
        return new BenchmarkScene("dense-dashboard-2x2", "Dense dashboard 2x2",
                BenchmarkScene.CATEGORY_MIXED, "mixed", 2, 2, st);
    }

    /** 尺寸梯度场景：每 tile 12 个混合元素，面积与元素密度同步缩放。 */
    private BenchmarkScene sizeScaled(int tiles) {
        Ctx c = ctx(60 + tiles);
        ProjectState st = new ProjectState(tiles, tiles);
        int count = tiles * tiles * 12;
        fillDenseMixed(c, st, tiles, tiles, count);
        String id = "size-" + tiles + "x" + tiles;
        return new BenchmarkScene(id, "Size scaling " + tiles + "x" + tiles,
                BenchmarkScene.CATEGORY_MIXED, "mixed", tiles, tiles, st);
    }

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

    private static final class Ctx {
        final Random rng;
        int counter;

        Ctx(long seed) {
            this.rng = new Random(seed);
            this.counter = 0;
        }

        String id() {
            return "e-bench-" + (counter++);
        }
    }

    private static Ctx ctx(int offset) {
        return new Ctx(SEED + offset * 1_000_003L);
    }

    private static String nextColor(Ctx c) {
        return PALETTE[c.rng.nextInt(PALETTE.length)];
    }

    private static String hex16(Random rng) {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(Character.forDigit(rng.nextInt(16), 16));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
