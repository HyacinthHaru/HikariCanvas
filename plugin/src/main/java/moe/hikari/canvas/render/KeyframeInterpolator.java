package moe.hikari.canvas.render;

import moe.hikari.canvas.state.BrushStrokeElement;
import moe.hikari.canvas.state.CircleElement;
import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.IconElement;
import moe.hikari.canvas.state.ImageElement;
import moe.hikari.canvas.state.Keyframe;
import moe.hikari.canvas.state.KfValue;
import moe.hikari.canvas.state.Layer;
import moe.hikari.canvas.state.LoopMode;
import moe.hikari.canvas.state.PathElement;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.RectElement;
import moe.hikari.canvas.state.ShapeElement;
import moe.hikari.canvas.state.TextElement;
import moe.hikari.canvas.state.Timeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关键帧插值器（0.6 P2 引入）。数学权威定义见 {@code docs/rendering.md §9}；
 * 管线定位见 {@code docs/architecture.md §5.5}（输出帧 = Rasterize(Interpolate(state, timeMs))）。
 *
 * <p><b>纯函数</b>：不依赖 Bukkit / DB / 时钟，任意线程可调；输出是临时 {@link ProjectState}
 * （record copy，只改值不改结构、不含 timelines、不持久化），与 {@code CanvasCompositor.maybeInterpolateText}
 * 的「record copy」范式一致。</p>
 *
 * <p><b>P2 范围</b>（docs/timeline.md §10 / D4）：仅数值类属性
 * （{@link Keyframe#NUMERIC_PROPERTIES}：x/y/w/h/rotation/opacity）+ LINEAR 缓动。
 * 其余降级语义、P3 接管：</p>
 * <ul>
 *   <li>非 LINEAR 缓动（EASE_* / CUBIC_BEZIER）一律按 LINEAR 求值（P3 落 cubic-bezier 双端求解器）</li>
 *   <li>颜色 / Fill / 离散类（text）属性轨整轨跳过（保持元素基值；P3 落 sRGB 线性插值与 step）</li>
 *   <li>数值轨中含 {@code ${var:X}} 字符串值（{@link KfValue.Str}）→ 整轨跳过（P3 接
 *       {@code VariableInterpolator.resolveAsNumber} 后参与插值，rendering.md §9.5）</li>
 * </ul>
 *
 * <p>前端镜像 {@code interpolation.ts} 在 P3/P4 落地（编辑器本地预览）；游戏内输出以本类为唯一权威（D9）。</p>
 */
public final class KeyframeInterpolator {

    private KeyframeInterpolator() {}

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

    /**
     * 求单条属性轨在时刻 {@code t} 的插值数值（rendering.md §9.1 取值规则）：
     *
     * <ul>
     *   <li>{@code t ≤ 首帧.timeMs} → 首帧值；{@code t ≥ 末帧.timeMs} → 末帧值（不外插）</li>
     *   <li>区间内：{@code local = (t − a.timeMs) / (b.timeMs − a.timeMs)}，
     *       {@code v = a + (b − a) × local}（P2 一律 LINEAR）</li>
     *   <li>重合帧（相邻 timeMs 相等）：取后一帧值（实现上「最大的 timeMs ≤ t」天然落在重合组末位，
     *       区间右端严格大于 t，无除零）</li>
     * </ul>
     *
     * @param kfs 单 (element, property) 轨，按 timeMs 升序（P1 op 层保证）
     * @return 插值结果；轨为空 / 含非 {@link KfValue.Num} 值时返回 {@code null}（整轨跳过，P2 降级语义）
     */
    static Double sampleNumeric(List<Keyframe> kfs, int t) {
        if (kfs == null || kfs.isEmpty()) return null;
        for (Keyframe k : kfs) {
            if (!(k.value() instanceof KfValue.Num)) return null;   // 含变量引用/错型值 → 整轨跳过（P3 接管）
        }
        Keyframe first = kfs.get(0);
        Keyframe last = kfs.get(kfs.size() - 1);
        if (t <= first.timeMs()) return ((KfValue.Num) first.value()).value();
        if (t >= last.timeMs()) return ((KfValue.Num) last.value()).value();

        // 找最大的 i 使 kfs[i].timeMs <= t；重合组中天然取末位（取后一帧语义）
        int i = 0;
        for (int j = 1; j < kfs.size(); j++) {
            if (kfs.get(j).timeMs() <= t) i = j;
            else break;
        }
        Keyframe a = kfs.get(i);
        Keyframe b = kfs.get(i + 1);   // 必存在且 b.timeMs > t >= a.timeMs（上面末帧分支兜住）
        double local = (t - a.timeMs()) / (double) (b.timeMs() - a.timeMs());
        double va = ((KfValue.Num) a.value()).value();
        double vb = ((KfValue.Num) b.value()).value();
        // P2：所有 easing 按 LINEAR 求值（eased = local）；P3 落 rendering.md §9.3 求解器
        return va + (vb - va) * local;
    }

    /**
     * 入口：算 {@code timeline} 在画布时刻 {@code timeMs} 的临时 {@link ProjectState}。
     *
     * <p>只重建「含被动画元素的 layer」与「被动画的 element」；未涉及的 Layer / Element
     * 按引用共享（record 不可变）。无任何可插值轨时直接返回 {@code base}（零分配 fast path）。</p>
     *
     * <p>返回的临时 state 不含 timelines / activeTimelineId（rasterize 不读它们），
     * <b>不得</b>持久化或交给 EditSession。</p>
     */
    public static ProjectState interpolate(ProjectState base, Timeline timeline, int timeMs) {
        if (base == null || timeline == null || timeline.tracks().isEmpty()) return base;

        // elementId → (property → 插值结果)
        Map<String, Map<String, Double>> animated = new HashMap<>();
        for (Map.Entry<String, List<Keyframe>> track : timeline.tracks().entrySet()) {
            // 同元素轨内多属性混排（方案 B）：按 property 分组后逐属性求值
            Map<String, List<Keyframe>> byProp = new LinkedHashMap<>();
            for (Keyframe k : track.getValue()) {
                if (!Keyframe.NUMERIC_PROPERTIES.contains(k.property())) continue;  // P2 仅数值类
                byProp.computeIfAbsent(k.property(), p -> new ArrayList<>()).add(k);
            }
            Map<String, Double> values = null;
            for (Map.Entry<String, List<Keyframe>> prop : byProp.entrySet()) {
                Double v = sampleNumeric(prop.getValue(), timeMs);
                if (v == null || !Double.isFinite(v)) continue;
                if (values == null) values = new HashMap<>();
                values.put(prop.getKey(), v);
            }
            if (values != null) animated.put(track.getKey(), values);
        }
        if (animated.isEmpty()) return base;

        List<Layer> outLayers = new ArrayList<>(base.layers().size());
        boolean any = false;
        for (Layer l : base.layers()) {
            List<Element> replaced = null;
            List<Element> src = l.elements();
            for (int i = 0; i < src.size(); i++) {
                Element e = src.get(i);
                Map<String, Double> props = animated.get(e.id());
                if (props == null) continue;
                if (replaced == null) replaced = new ArrayList<>(src);
                replaced.set(i, withAnimated(e, props));
                any = true;
            }
            outLayers.add(replaced == null ? l
                    : new Layer(l.id(), l.name(), l.visible(), l.locked(),
                            l.opacity(), l.blendMode(), l.colorTag(), replaced));
        }
        if (!any) return base;

        return new ProjectState(base.version(), base.canvas(), null,
                outLayers, base.activeLayerId(), base.history(), null, null);
    }

    // ---------- 元素重建（8 sealed 子类逐型替换数值属性）----------

    private static int ix(Map<String, Double> p, String key, int fallback) {
        Double v = p.get(key);
        return v == null ? fallback : (int) Math.round(v);
    }

    private static Float opacityOf(Map<String, Double> p, Float fallback) {
        Double v = p.get("opacity");
        if (v == null) return fallback;
        return (float) Math.min(1.0, Math.max(0.0, v));
    }

    /**
     * 用插值结果重建元素 record（仅 x/y/w/h/rotation/opacity 六数值属性；其余字段原样保留）。
     * x/y/w/h/rotation 四舍五入回 int（record 字段类型）；opacity 钳 [0,1]。
     * w/h 插出非正值不钳——渲染器入口已有 {@code w<=0} 守卫（M16.3），语义为「元素暂不可见」。
     */
    static Element withAnimated(Element e, Map<String, Double> p) {
        int x = ix(p, "x", e.x());
        int y = ix(p, "y", e.y());
        int w = ix(p, "w", e.w());
        int h = ix(p, "h", e.h());
        int rotation = ix(p, "rotation", e.rotation());
        Float opacity = opacityOf(p, e.opacity());
        return switch (e) {
            case TextElement t -> new TextElement(t.id(), x, y, w, h, rotation,
                    t.locked(), t.visible(), t.text(), t.fontId(), t.fontSize(), t.color(),
                    t.align(), t.letterSpacing(), t.lineHeight(), t.vertical(), t.effects(),
                    opacity, t.blendMode(), t.renderMode(), t.bold(), t.italic());
            case RectElement r -> new RectElement(r.id(), x, y, w, h, rotation,
                    r.locked(), r.visible(), r.fill(), r.stroke(),
                    opacity, r.blendMode(), r.renderMode());
            case IconElement ic -> new IconElement(ic.id(), x, y, w, h, rotation,
                    ic.locked(), ic.visible(), ic.source(), ic.tint(),
                    opacity, ic.blendMode(), ic.renderMode(), ic.fill());
            case PathElement pa -> new PathElement(pa.id(), x, y, w, h, rotation,
                    pa.locked(), pa.visible(), pa.d(), pa.fill(), pa.stroke(),
                    pa.markerStart(), pa.markerEnd(),
                    opacity, pa.blendMode(), pa.renderMode());
            case CircleElement c -> new CircleElement(c.id(), x, y, w, h, rotation,
                    c.locked(), c.visible(), c.fill(), c.stroke(),
                    opacity, c.blendMode(), c.renderMode());
            case ShapeElement sh -> new ShapeElement(sh.id(), x, y, w, h, rotation,
                    sh.locked(), sh.visible(), sh.kind(), sh.sides(), sh.innerRatio(),
                    sh.fill(), sh.stroke(), opacity, sh.blendMode(), sh.renderMode());
            case BrushStrokeElement br -> new BrushStrokeElement(br.id(), x, y, w, h, rotation,
                    br.locked(), br.visible(), br.points(), br.size(), br.fill(),
                    br.pressureSize(), br.pressureOpacity(),
                    opacity, br.blendMode(), br.renderMode());
            case ImageElement im -> new ImageElement(im.id(), x, y, w, h, rotation,
                    im.locked(), im.visible(), im.source(), im.mask(),
                    opacity, im.blendMode(), im.renderMode());
        };
    }
}
