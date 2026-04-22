package moe.hikari.canvas.render;

import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.ProjectState;

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
     * 元素 bbox → DirtyRegion。M4-T6：考虑 rotation 后的外接矩形。
     *
     * <ul>
     *   <li>{@code rotation = 0 / 180}：直接 bbox（w/h 不变）</li>
     *   <li>{@code rotation = 90 / 270}：内容 w 和 h 对调，外接 bbox = 边长 {@code max(w, h)} 的方形，中心对齐原 bbox 中心</li>
     * </ul>
     *
     * <p>其他 rotation 不支持（EditSession 仅接受 0/90/180/270），fallback 到原 bbox。</p>
     */
    public static DirtyRegion of(Element e) {
        int rot = e.rotation();
        if (rot == 90 || rot == 270) {
            int cx = e.x() + e.w() / 2;
            int cy = e.y() + e.h() / 2;
            int side = Math.max(e.w(), e.h());
            return new DirtyRegion(cx - side / 2, cy - side / 2, side, side);
        }
        return new DirtyRegion(e.x(), e.y(), e.w(), e.h());
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
