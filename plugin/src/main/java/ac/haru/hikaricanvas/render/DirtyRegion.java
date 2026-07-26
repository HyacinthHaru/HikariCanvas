package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.CircleElement;
import ac.haru.hikaricanvas.state.Effects;
import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.PathElement;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.ShapeElement;
import ac.haru.hikaricanvas.state.Stroke;
import ac.haru.hikaricanvas.state.TextElement;

import java.util.ArrayList;
import java.util.List;

/**
 * 画布坐标系里的矩形区域，用于标记某次 op 影响的像素范围。
 * {@link #coveredMapIndices} 把它切成 slotIndex 列表交给
 * {@link CanvasProjector} 做逐图重绘。
 *
 * <p>契约见 {@code docs/architecture.md §5.2}。粒度 = map 级：
 * region 与任一 map 相交就整张重绘；格内 partial 未实装。</p>
 */
public record DirtyRegion(int x, int y, int w, int h) {

    public static final int MAP_SIZE = 128;

    public DirtyRegion {
        if (w < 0 || h < 0) {
            throw new IllegalArgumentException("region dims must be non-negative: " + w + "x" + h);
        }
    }

    /**
     * 元素 bbox → DirtyRegion。考虑 rotation、文字效果与描边溢出对像素范围的扩张。
     *
     * <p>扩张顺序：</p>
     * <ol>
     *   <li>{@link TextElement#effects()} 让字形像素溢出 bbox——
     *       {@code shadow} 按 {@code (dx, dy)} 单向外扩、{@code stroke} 按 width/2 四向外扩、
     *       {@code glow} 按 radius 四向外扩</li>
     *   <li>圆 / 多边形 / path 的描边四向外扩 {@code ceil(width/2)}；path 带箭头或圆点
     *       marker 时再按 marker 尺寸外扩</li>
     *   <li>{@code rotation ∈ {90, 270}} → 外接 = 边长 {@code max(w, h)} 方形中心对齐</li>
     * </ol>
     *
     * <p>其他 rotation（0 / 180）bbox 不变。</p>
     *
     * <p><b>为什么矩形不扩而圆 / 多边形 / path 要扩：</b>RectRenderer 用 4 条 fillRect 画边框，
     * 边框完全在 bbox 内部；CircleRenderer / ShapeRenderer / PathRenderer 用 {@code BasicStroke}，
     * 描边以路径为中心两侧各分一半，向外溢出 width/2（描边最粗 128 → 溢出 64 px）。不扩的话
     * move / delete 只推 bbox 覆盖到的 map，溢进相邻 map 的那部分旧描边就留在游戏内地图上，
     * 要等某次全量重绘才消失——前端每帧全量重画看不到这个残影，双端于是不一致
     * （{@code architecture.md §5.1.5}「最后一帧 100% 正确」）。</p>
     *
     * <p><b>已知未覆盖：</b>PathElement 的 {@code d} 坐标可以画到 w/h 之外（改 d 时 bbox 不跟着变，
     * 见 PathElement 注释）。真要覆盖得把 d 解析一遍算实际范围，这里不做。</p>
     */
    public static DirtyRegion of(Element e) {
        int x = e.x(), y = e.y(), w = e.w(), h = e.h();

        // Step 1：TextElement.effects 四向外扩
        if (e instanceof TextElement t && t.effects() != null) {
            int[] pad = computeEffectPadding(t.effects()); // [left, top, right, bottom]
            x -= pad[0];
            y -= pad[1];
            w += pad[0] + pad[2];
            h += pad[1] + pad[3];
        }

        // Step 2：BasicStroke 中心对齐描边 + path marker 的溢出
        int outset = strokeOutset(e);
        if (outset > 0) {
            x -= outset;
            y -= outset;
            w += outset * 2;
            h += outset * 2;
        }

        // Step 3：rotation 外接。任意角度按旋转后四角外接矩形算。
        int rot = ((e.rotation() % 360) + 360) % 360;
        if (rot != 0 && rot != 180) {
            double rad = Math.toRadians(rot);
            double cos = Math.abs(Math.cos(rad));
            double sin = Math.abs(Math.sin(rad));
            int newW = (int) Math.ceil(w * cos + h * sin);
            int newH = (int) Math.ceil(w * sin + h * cos);
            int cx = x + w / 2;
            int cy = y + h / 2;
            return new DirtyRegion(cx - newW / 2, cy - newH / 2, newW, newH);
        }
        return new DirtyRegion(x, y, w, h);
    }

    /**
     * 元素描边（以及 path marker）向 bbox 外溢出的像素数，四向取同一个最大值。
     *
     * <p>矩形返 0（fillRect 边框画在 bbox 内）；文字的 effects.stroke 由
     * {@link #computeEffectPadding} 负责，不在这里重复算。</p>
     */
    private static int strokeOutset(Element e) {
        if (e instanceof CircleElement c) return halfStrokeWidth(c.stroke());
        if (e instanceof ShapeElement sh) return halfStrokeWidth(sh.stroke());
        if (e instanceof PathElement p) return pathOutset(p);
        return 0;
    }

    /** {@code ceil(width / 2)}；无描边返 0。 */
    private static int halfStrokeWidth(Stroke s) {
        if (s == null) return 0;
        return (Math.max(0, s.width()) + 1) / 2;
    }

    /**
     * path 除描边外还要算 marker：箭头从端点朝外延伸一个 size，圆点以端点为心铺一个半径，
     * 而端点本身就可能贴在 bbox 边上。取两种 marker 的较大值再加一个线宽留余量。
     */
    private static int pathOutset(PathElement p) {
        int outset = halfStrokeWidth(p.stroke());
        if (p.markerStart() == null && p.markerEnd() == null) return outset;
        int width = p.stroke() == null ? 0 : Math.max(0, p.stroke().width());
        double diag = Math.hypot(p.w(), p.h());
        int marker = Math.max(
                MarkerRenderer.arrowSize(width, diag),
                MarkerRenderer.dotRadius(width, diag));
        return Math.max(outset, marker + width);
    }

    private static int[] computeEffectPadding(Effects fx) {
        int left = 0, top = 0, right = 0, bottom = 0;
        if (fx.shadow() != null) {
            int dx = fx.shadow().dx();
            int dy = fx.shadow().dy();
            if (dx > 0) right = Math.max(right, dx); else left = Math.max(left, -dx);
            if (dy > 0) bottom = Math.max(bottom, dy); else top = Math.max(top, -dy);
        }
        if (fx.stroke() != null) {
            int sw = fx.stroke().width();
            int ext = (sw + 1) / 2;  // stroke 一半溢出字形轮廓外
            left = Math.max(left, ext);
            right = Math.max(right, ext);
            top = Math.max(top, ext);
            bottom = Math.max(bottom, ext);
        }
        if (fx.glow() != null) {
            int r = fx.glow().radius();
            left = Math.max(left, r);
            right = Math.max(right, r);
            top = Math.max(top, r);
            bottom = Math.max(bottom, r);
        }
        return new int[] { left, top, right, bottom };
    }

    /** 整个画布（canvas.background op / canvas resize / 初次 compose 全量）。 */
    public static DirtyRegion fullCanvas(ProjectState state) {
        return new DirtyRegion(0, 0,
                state.canvas().widthMaps() * MAP_SIZE,
                state.canvas().heightMaps() * MAP_SIZE);
    }

    /** 两个 region 的最小包围矩形；{@code other == null} 返回自身。 */
    public DirtyRegion union(DirtyRegion other) {
        if (other == null) return this;
        int x0 = Math.min(this.x, other.x);
        int y0 = Math.min(this.y, other.y);
        int x1 = Math.max(this.x + this.w, other.x + other.w);
        int y1 = Math.max(this.y + this.h, other.y + other.h);
        return new DirtyRegion(x0, y0, x1 - x0, y1 - y0);
    }

    /**
     * 返回与本 region 相交的全部 slotIndex（按 FrameDeployer 的
     * {@code slotIndex = row * widthMaps + col} 约定）。
     *
     * <p>region 若完全越出画布，返回空列表。</p>
     */
    public List<Integer> coveredMapIndices(int widthMaps, int heightMaps) {
        List<Integer> out = new ArrayList<>();
        if (w <= 0 || h <= 0) return out;
        int col0 = Math.max(0, x / MAP_SIZE);
        int col1 = Math.min(widthMaps - 1, (x + w - 1) / MAP_SIZE);
        int row0 = Math.max(0, y / MAP_SIZE);
        int row1 = Math.min(heightMaps - 1, (y + h - 1) / MAP_SIZE);
        if (col0 > col1 || row0 > row1) return out;
        for (int r = row0; r <= row1; r++) {
            for (int c = col0; c <= col1; c++) {
                out.add(r * widthMaps + c);
            }
        }
        return out;
    }
}
