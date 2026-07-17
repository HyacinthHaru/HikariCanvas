package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.BrushStrokeElement;
import ac.haru.hikaricanvas.state.CircleElement;
import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.Fill;
import ac.haru.hikaricanvas.state.IconElement;
import ac.haru.hikaricanvas.state.ImageElement;
import ac.haru.hikaricanvas.state.Keyframe;
import ac.haru.hikaricanvas.state.KfValue;
import ac.haru.hikaricanvas.state.Layer;
import ac.haru.hikaricanvas.state.LoopMode;
import ac.haru.hikaricanvas.state.PathElement;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.RectElement;
import ac.haru.hikaricanvas.state.ShapeElement;
import ac.haru.hikaricanvas.state.StrictNumber;
import ac.haru.hikaricanvas.state.TextElement;
import ac.haru.hikaricanvas.state.Timeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关键帧插值器（缓动 + 颜色/Fill/离散轨 + 变量 resolve）。
 * 数学权威定义见 {@code docs/rendering.md §9}；管线定位见 {@code docs/architecture.md §5.5}
 * （输出帧 = Rasterize(Interpolate(state, timeMs))）。
 *
 * <p><b>纯函数</b>：不依赖 Bukkit / DB / 时钟，任意线程可调；输出是临时 {@link ProjectState}
 * （record copy，只改值不改结构、不含 timelines、不持久化）。变量支持经 {@link NumberResolver}
 * seam 注入（生产 = {@code VariableInterpolator::resolveAsNumber} 经 AnimationTicker 按 wall
 * 绑定；测试 / snapshot 路径不注 = 无变量支持）。</p>
 *
 * <p><b>支持范围</b>（rendering.md §9.2–§9.5）：</p>
 * <ul>
 *   <li>缓动：{@link EasingSolver}（LINEAR / EASE_* 预设 / CUBIC_BEZIER，双端逐位等价）</li>
 *   <li>数值轨（x/y/w/h/rotation/opacity）：{@code v = a + (b−a)×eased}；{@code ${var:X}} /
 *       数字字符串值经 resolver 求值（无 resolver 时纯数字仍可用、变量引用退 0——§9.5 链终点）</li>
 *   <li>颜色轨（{@code color}，仅 TextElement）：sRGB 线性空间插值（{@link ColorLerp}）；
 *       含 {@code ${var:}} 的颜色值不支持，整轨跳过</li>
 *   <li>Fill 轨（6 类几何/图标/笔刷元素）：同类型同 stop 数逐 stop 插值，否则 step</li>
 *   <li>离散轨（{@code text}，仅 TextElement）：step 取 {@code timeMs ≤ t} 最近帧（首帧前取
 *       首帧）；内容里的 {@code ${var:X}} 由 rasterize 的 {@code maybeInterpolateText} 统一 resolve</li>
 * </ul>
 *
 * <p>前端镜像：{@code web/src/timeline/interpolation.ts}（编辑器本地预览用）；游戏内输出以
 * 本类为唯一权威（D9）。</p>
 */
public final class KeyframeInterpolator {

    private KeyframeInterpolator() {}

    /**
     * 数值轨 {@link KfValue.Str} 值（{@code ${var:X}} 模板 / 数字字符串）的求值 seam。
     * 生产装配 {@code VariableInterpolator::resolveAsNumber}（已按 wall 绑定）；null = 无变量支持。
     */
    @FunctionalInterface
    public interface NumberResolver {
        double resolve(String raw);
    }

    /**
     * loopMode 时间映射：把从播放起点累计的墙钟位置 {@code posMs} 映射进 {@code [0, durationMs]}。
     *
     * <ul>
     *   <li>{@code ONCE}：钳到 {@code [0, durationMs]}（播完停在末帧；Ticker 据
     *       {@code posMs >= durationMs} 自动注销）</li>
     *   <li>{@code LOOP}：{@code floorMod(posMs, durationMs)}</li>
     *   <li>{@code PING_PONG}：周期 {@code 2×durationMs} 的三角波（0→d→0）</li>
     * </ul>
     */
    public static int mapTime(long posMs, int durationMs, LoopMode mode) {
        if (durationMs <= 0) return 0;
        if (mode == null) mode = LoopMode.LOOP;
        return switch (mode) {
            case ONCE -> (int) Math.min(Math.max(posMs, 0L), durationMs);
            case LOOP -> (int) Math.floorMod(posMs, durationMs);
            case PING_PONG -> {
                long period = 2L * durationMs;
                long m = Math.floorMod(posMs, period);
                yield (int) (m <= durationMs ? m : period - m);
            }
        };
    }

    // ---------- 取值核心（rendering.md §9.1） ----------

    /**
     * 区间定位结果：{@code b == null} 表示边界命中（直接取 {@code a} 的值，不插值）；
     * 否则 {@code eased} 为经 {@code a.easing()} 映射后的进度。{@code ai} 是 {@code a}
     * 在轨内的真实索引（镜像 TS 端 Span.ai——用 equals 反查
     * indexOf 在全等重合帧下会错位，双端取值分叉）。
     */
    private record Span(Keyframe a, int ai, Keyframe b, double eased) {}

    /**
     * §9.1 取值规则的共享实现：首帧前 / 末帧后取端帧（不外插）；区间内算
     * {@code local = (t − a.timeMs) / (b.timeMs − a.timeMs)} 并经左端帧 easing 映射；
     * 重合帧组天然取末位（最大的 {@code timeMs ≤ t}），区间右端严格大于 t，无除零。
     */
    private static Span spanAt(List<Keyframe> kfs, int t) {
        Keyframe first = kfs.get(0);
        Keyframe last = kfs.get(kfs.size() - 1);
        if (t <= first.timeMs()) return new Span(first, 0, null, 0.0);
        if (t >= last.timeMs()) return new Span(last, kfs.size() - 1, null, 0.0);
        int i = 0;
        for (int j = 1; j < kfs.size(); j++) {
            if (kfs.get(j).timeMs() <= t) i = j;
            else break;
        }
        Keyframe a = kfs.get(i);
        Keyframe b = kfs.get(i + 1);   // 必存在且 b.timeMs > t >= a.timeMs（末帧分支兜住）
        double local = (t - a.timeMs()) / (double) (b.timeMs() - a.timeMs());
        return new Span(a, i, b, EasingSolver.ease(a.easing(), local));
    }

    /**
     * 求单条数值属性轨在时刻 {@code t} 的插值结果。值可为 {@link KfValue.Num} 或
     * {@link KfValue.Str}（经 {@code resolver} 求值；无 resolver 时纯数字字符串本地解析、
     * 变量引用退 0）。含 {@link KfValue.FillV} 等错型值 → 整轨跳过（返 null）。
     */
    static Double sampleNumeric(List<Keyframe> kfs, int t, NumberResolver resolver) {
        if (kfs == null || kfs.isEmpty()) return null;
        double[] values = new double[kfs.size()];
        for (int i = 0; i < kfs.size(); i++) {
            Double v = numericValueOf(kfs.get(i).value(), resolver);
            if (v == null) return null;   // 错型值 → 整轨跳过
            values[i] = v;
        }
        Span s = spanAt(kfs, t);
        if (s.b() == null) return values[s.ai()];
        double va = values[s.ai()];
        double vb = values[s.ai() + 1];
        return va + (vb - va) * s.eased();
    }

    private static Double numericValueOf(KfValue v, NumberResolver resolver) {
        if (v instanceof KfValue.Num n) return n.value();
        if (v instanceof KfValue.Str s) {
            String raw = s.value();
            if (resolver != null) return resolver.resolve(raw);
            // 无变量支持：纯数字字符串仍可用；变量引用退 fallback 链终点 0（§9.5）
            if (raw != null && raw.indexOf("${var:") >= 0) return 0.0;
            return StrictNumber.parse(raw);
        }
        return null;
    }

    /**
     * 颜色轨采样（仅 TextElement.color 用）。全轨必须是 hex 字符串（{@link KfValue.Str}）；
     * 含 {@code ${var:}} 或错型 → 整轨跳过（不支持变量颜色）。区间内
     * {@link ColorLerp#lerpHex}（内部解析失败自然 step）。
     */
    static String sampleColor(List<Keyframe> kfs, int t) {
        if (kfs == null || kfs.isEmpty()) return null;
        for (Keyframe k : kfs) {
            if (!(k.value() instanceof KfValue.Str s)) return null;
            if (s.value() == null || s.value().indexOf("${var:") >= 0) return null;
        }
        Span sp = spanAt(kfs, t);
        String a = ((KfValue.Str) kfs.get(sp.ai()).value()).value();
        if (sp.b() == null) return a;
        String b = ((KfValue.Str) kfs.get(sp.ai() + 1).value()).value();
        return ColorLerp.lerpHex(a, b, sp.eased());
    }

    /**
     * Fill 轨采样。值为 {@link KfValue.FillV}，或 {@link KfValue.Str} hex（归一化为 SolidFill，
     * op 层允许的形态）；含 {@code ${var:}} 或错型 → 整轨跳过。区间内
     * {@link ColorLerp#lerpFill}（类型 / stop 数不一致内部自然 step）。
     */
    static Fill sampleFill(List<Keyframe> kfs, int t) {
        if (kfs == null || kfs.isEmpty()) return null;
        Fill[] fills = new Fill[kfs.size()];
        for (int i = 0; i < kfs.size(); i++) {
            KfValue v = kfs.get(i).value();
            if (v instanceof KfValue.FillV f && f.fill() != null) {
                fills[i] = f.fill();
            } else if (v instanceof KfValue.Str s && s.value() != null
                    && s.value().indexOf("${var:") < 0) {
                fills[i] = Fill.solid(s.value());
            } else {
                return null;
            }
        }
        Span sp = spanAt(kfs, t);
        if (sp.b() == null) return fills[sp.ai()];
        return ColorLerp.lerpFill(fills[sp.ai()], fills[sp.ai() + 1], sp.eased());
    }

    /**
     * 离散轨采样（text）：step 取 {@code timeMs ≤ t} 的最近帧；首帧前取首帧（§9.2 边界）。
     * 全轨必须是 {@link KfValue.Str}（内容可含 {@code ${var:X}}——由 rasterize 的
     * {@code maybeInterpolateText} 每帧统一 resolve，§9.5）。
     */
    static String sampleText(List<Keyframe> kfs, int t) {
        if (kfs == null || kfs.isEmpty()) return null;
        for (Keyframe k : kfs) {
            if (!(k.value() instanceof KfValue.Str)) return null;
        }
        Keyframe pick = kfs.get(0);
        for (Keyframe k : kfs) {
            if (k.timeMs() <= t) pick = k;
            else break;
        }
        return ((KfValue.Str) pick.value()).value();
    }

    // ---------- 入口 ----------

    /** 每元素的插值结果集（has* 区分「未动画」与「动画到 null」——后者不会发生但防御留位）。 */
    static final class AnimatedValues {
        Map<String, Double> numbers;
        String color;
        boolean hasColor;
        Fill fill;
        boolean hasFill;
        String text;
        boolean hasText;

        boolean isEmpty() {
            return (numbers == null || numbers.isEmpty()) && !hasColor && !hasFill && !hasText;
        }
    }

    /** 兼容入口：无变量支持（snapshot / 测试路径）。 */
    public static ProjectState interpolate(ProjectState base, Timeline timeline, int timeMs) {
        return interpolate(base, timeline, timeMs, null);
    }

    /**
     * 入口：算 {@code timeline} 在画布时刻 {@code timeMs} 的临时 {@link ProjectState}。
     *
     * <p>只重建「含被动画元素的 layer」与「被动画的 element」；未涉及的 Layer / Element
     * 按引用共享（record 不可变）。无任何可插值轨时直接返回 {@code base}（零分配 fast path）。</p>
     *
     * <p>返回的临时 state 不含 timelines / activeTimelineId（rasterize 不读它们），
     * <b>不得</b>持久化或交给 EditSession。</p>
     *
     * @param resolver 数值轨变量求值 seam（AnimationTicker 注入按 wall 绑定的
     *                 {@code VariableInterpolator::resolveAsNumber}）；null = 无变量支持
     */
    public static ProjectState interpolate(ProjectState base, Timeline timeline, int timeMs,
                                           NumberResolver resolver) {
        if (base == null || timeline == null || timeline.tracks().isEmpty()) return base;

        // 帧内同一 raw 只 resolve 一次（memo）——变量 push 落在两次读
        // 之间会让同帧的 va/vb 取自不同快照（单帧撕裂）；memo 保证整帧读同一变量快照
        if (resolver != null) {
            NumberResolver delegate = resolver;
            Map<String, Double> memo = new HashMap<>();
            resolver = raw -> memo.computeIfAbsent(raw, delegate::resolve);
        }

        Map<String, AnimatedValues> animated = new HashMap<>();
        for (Map.Entry<String, List<Keyframe>> track : timeline.tracks().entrySet()) {
            // 同元素轨内多属性混排：按 property 分组后逐属性求值
            Map<String, List<Keyframe>> byProp = new LinkedHashMap<>();
            for (Keyframe k : track.getValue()) {
                if (!Keyframe.PROPERTIES.contains(k.property())) continue;
                byProp.computeIfAbsent(k.property(), p -> new ArrayList<>()).add(k);
            }
            AnimatedValues out = new AnimatedValues();
            for (Map.Entry<String, List<Keyframe>> prop : byProp.entrySet()) {
                String name = prop.getKey();
                List<Keyframe> kfs = prop.getValue();
                if (Keyframe.NUMERIC_PROPERTIES.contains(name)) {
                    Double v = sampleNumeric(kfs, timeMs, resolver);
                    if (v == null || !Double.isFinite(v)) continue;
                    if (out.numbers == null) out.numbers = new HashMap<>();
                    out.numbers.put(name, v);
                } else if ("color".equals(name)) {
                    String c = sampleColor(kfs, timeMs);
                    if (c != null) {
                        out.color = c;
                        out.hasColor = true;
                    }
                } else if ("fill".equals(name)) {
                    Fill f = sampleFill(kfs, timeMs);
                    if (f != null) {
                        out.fill = f;
                        out.hasFill = true;
                    }
                } else if ("text".equals(name)) {
                    String s = sampleText(kfs, timeMs);
                    if (s != null) {
                        out.text = s;
                        out.hasText = true;
                    }
                }
            }
            if (!out.isEmpty()) animated.put(track.getKey(), out);
        }
        if (animated.isEmpty()) return base;

        List<Layer> outLayers = new ArrayList<>(base.layers().size());
        boolean any = false;
        for (Layer l : base.layers()) {
            List<Element> replaced = null;
            List<Element> src = l.elements();
            for (int i = 0; i < src.size(); i++) {
                Element e = src.get(i);
                AnimatedValues v = animated.get(e.id());
                if (v == null) continue;
                if (replaced == null) replaced = new ArrayList<>(src);
                replaced.set(i, withAnimated(e, v));
                any = true;
            }
            outLayers.add(replaced == null ? l
                    : new Layer(l.id(), l.name(), l.visible(), l.locked(),
                            l.opacity(), l.blendMode(), l.colorTag(), replaced));
        }
        if (!any) return base;

        return new ProjectState(base.version(), base.canvas(), null,
                outLayers, base.activeLayerId(), base.history(), null, null, null);
    }

    // ---------- 元素重建（8 sealed 子类逐型替换） ----------

    private static int ix(Map<String, Double> p, String key, int fallback) {
        if (p == null) return fallback;
        Double v = p.get(key);
        return v == null ? fallback : StrictNumber.clampInt(Math.round(v));
    }

    private static Float opacityOf(Map<String, Double> p, Float fallback) {
        if (p == null) return fallback;
        Double v = p.get("opacity");
        if (v == null) return fallback;
        return (float) Math.min(1.0, Math.max(0.0, v));
    }

    /**
     * 用插值结果重建元素 record。数值六属性全类型适用；{@code color}/{@code text} 仅
     * TextElement；{@code fill} 仅 Rect/Icon/Path/Circle/Shape/Brush（按 record 字段
     * 适用性，错配的轨静默忽略——op 层不做属性×类型校验，渲染侧兜底）。
     * x/y/w/h/rotation 四舍五入回 int；opacity 钳 [0,1]。w/h 插出非正值不钳——渲染器
     * 入口已有 {@code w<=0} 守卫，语义为「元素暂不可见」。
     */
    static Element withAnimated(Element e, AnimatedValues v) {
        Map<String, Double> p = v.numbers;
        int x = ix(p, "x", e.x());
        int y = ix(p, "y", e.y());
        int w = ix(p, "w", e.w());
        int h = ix(p, "h", e.h());
        int rotation = ix(p, "rotation", e.rotation());
        Float opacity = opacityOf(p, e.opacity());
        return switch (e) {
            case TextElement t -> new TextElement(t.id(), x, y, w, h, rotation,
                    t.locked(), t.visible(),
                    v.hasText ? v.text : t.text(),
                    t.fontId(), t.fontSize(),
                    v.hasColor ? v.color : t.color(),
                    t.align(), t.letterSpacing(), t.lineHeight(), t.vertical(), t.effects(),
                    opacity, t.blendMode(), t.renderMode(), t.bold(), t.italic());
            case RectElement r -> new RectElement(r.id(), x, y, w, h, rotation,
                    r.locked(), r.visible(),
                    v.hasFill ? v.fill : r.fill(),
                    r.stroke(), opacity, r.blendMode(), r.renderMode());
            case IconElement ic -> new IconElement(ic.id(), x, y, w, h, rotation,
                    ic.locked(), ic.visible(), ic.source(), ic.tint(),
                    opacity, ic.blendMode(), ic.renderMode(),
                    v.hasFill ? v.fill : ic.fill());
            case PathElement pa -> new PathElement(pa.id(), x, y, w, h, rotation,
                    pa.locked(), pa.visible(), pa.d(),
                    v.hasFill ? v.fill : pa.fill(),
                    pa.stroke(), pa.markerStart(), pa.markerEnd(),
                    opacity, pa.blendMode(), pa.renderMode(), pa.fillRule());
            case CircleElement c -> new CircleElement(c.id(), x, y, w, h, rotation,
                    c.locked(), c.visible(),
                    v.hasFill ? v.fill : c.fill(),
                    c.stroke(), opacity, c.blendMode(), c.renderMode());
            case ShapeElement sh -> new ShapeElement(sh.id(), x, y, w, h, rotation,
                    sh.locked(), sh.visible(), sh.kind(), sh.sides(), sh.innerRatio(),
                    v.hasFill ? v.fill : sh.fill(),
                    sh.stroke(), opacity, sh.blendMode(), sh.renderMode());
            case BrushStrokeElement br -> new BrushStrokeElement(br.id(), x, y, w, h, rotation,
                    br.locked(), br.visible(), br.points(), br.size(),
                    v.hasFill ? v.fill : br.fill(),
                    br.pressureSize(), br.pressureOpacity(),
                    opacity, br.blendMode(), br.renderMode());
            case ImageElement im -> new ImageElement(im.id(), x, y, w, h, rotation,
                    im.locked(), im.visible(), im.source(), im.mask(),
                    opacity, im.blendMode(), im.renderMode());
        };
    }
}
