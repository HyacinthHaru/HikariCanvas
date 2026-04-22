package moe.hikari.canvas.render;

import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.state.ProjectState;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.logging.Logger;

/**
 * 把 {@link DirtyRegion} 翻译成实际的像素推送：
 * {@link CanvasCompositor#rasterize} 整张大画布一次 → 对 region 覆盖的每个 mapIndex
 * {@link CanvasCompositor#toPaletteSlice} 量化切片 → 写入 {@link HikariCanvasRenderer}。
 * 下一 tick Paper 自动 sync 给 viewer。
 *
 * <p><b>M4-T4 变化（相对 M3）：</b> rasterize 和 quantize 分两阶段——一次 rasterize
 * 复用给多个 mapIndex；只有被 region 覆盖的 map 才做量化，节省 mapping 成本。</p>
 *
 * <p><b>线程：</b> {@link HikariCanvasRenderer#update} 线程安全（ConcurrentMap）；
 * {@link CanvasCompositor} 每次分配独立 BufferedImage。可在 Jetty WS 线程或 BukkitScheduler
 * async task 里调用，不必切主线程。</p>
 */
public final class CanvasProjector {

    private final HikariCanvasRenderer canvasRenderer;
    private final CanvasCompositor compositor;
    private final Logger log;

    public CanvasProjector(HikariCanvasRenderer canvasRenderer, CanvasCompositor compositor, Logger log) {
        this.canvasRenderer = canvasRenderer;
        this.compositor = compositor;
        this.log = log;
    }

    /**
     * 渲染 region 覆盖的所有 maps。{@code region == null} 直接返回（op 未产生像素变化）。
     *
     * @return 实际更新的 mapId 数量
     */
    public int project(Session session, DirtyRegion region) {
        if (region == null) return 0;
        ProjectState state = session.projectState();
        List<Integer> mapIds = session.mapIds();
        if (state == null || mapIds == null || mapIds.isEmpty()) return 0;

        int widthMaps = state.canvas().widthMaps();
        int heightMaps = state.canvas().heightMaps();
        List<Integer> indices = region.coveredMapIndices(widthMaps, heightMaps);
        if (indices.isEmpty()) return 0;

        BufferedImage img;
        try {
            img = compositor.rasterize(state);
        } catch (Exception e) {
            log.warning("CanvasProjector: rasterize failed err=" + e.getMessage());
            return 0;
        }

        int updated = 0;
        for (Integer idx : indices) {
            if (idx < 0 || idx >= mapIds.size()) continue;
            int mapId = mapIds.get(idx);
            try {
                byte[] pixels = compositor.toPaletteSlice(img, idx, widthMaps);
                canvasRenderer.update(mapId, pixels);
                updated++;
            } catch (Exception e) {
                log.warning("CanvasProjector: quantize failed mapIndex=" + idx
                        + " mapId=" + mapId + " err=" + e.getMessage());
            }
        }
        return updated;
    }

    /** 渲染全部 maps（canvas.background / snapshot 后 / reset 时用）。 */
    public int projectAll(Session session) {
        if (session.projectState() == null) return 0;
        return project(session, DirtyRegion.fullCanvas(session.projectState()));
    }
}
