package moe.hikari.canvas.render;

import moe.hikari.canvas.state.Effects;
import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.TextElement;

import java.util.ArrayList;
import java.util.List;

/**
 * 画布坐标系里的矩形区域，用于标记某次 op 影响的像素范围。
 * {@link #coveredMapIndices} 把它切成 slotIndex 列表交给
 * {@link CanvasProjector} 做逐图重绘。
 *
 * <p>契约见 {@code docs/architecture.md §5.2}。M3-T7 粒度 = map 级：
 * region 与任一 map 相交就整张重绘；T8+ 才考虑格内 partial。</p>
 */
public record DirtyRegion(int x, int y, int w, int h) {

    public static final int MAP_SIZE = 128;

    public DirtyRegion {
        if (w < 0 || h < 0) {
            throw new IllegalArgumentException("region dims must be non-negative: " + w + "x" + h);
        }
    }

    /**
     * 元素 bbox → DirtyRegion。考虑 rotation 与 TextElement.effects 对像素范围的扩张。
     *
     * <p>扩张顺序：</p>
     * <ol>
     *   <li>M4-T8/T9/T10：{@link TextElement#effects()} 让字形像素溢出 bbox——
     *       {@code shadow} 按 {@code (dx, dy)} 单向外扩、{@code stroke} 按 width/2 四向外扩、
     *       {@code glow} 按 radius 四向外扩</li>
     *   <li>M4-T6：{@code rotation ∈ {90, 270}} → 外接 = 边长 {@code max(w, h)} 方形中心对齐</li>
     * </ol>
     *
     * <p>其他 rotation（0 / 180）bbox 不变。stroke 为 TextElement.effects.stroke；
     * RectElement 的 stroke 不在外扩——那是 bbox 内部的边框，不溢出。</p>
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

        // Step 2：rotation 外接
        int rot = e.rotation();
        if (rot == 90 || rot == 270) {
            int cx = x + w / 2;
            int cy = y + h / 2;
            int side = Math.max(w, h);
            return new DirtyRegion(cx - side / 2, cy - side / 2, side, side);
        }
        return new DirtyRegion(x, y, w, h);
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
