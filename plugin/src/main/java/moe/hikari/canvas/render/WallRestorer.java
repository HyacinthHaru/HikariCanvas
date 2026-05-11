package moe.hikari.canvas.render;

import moe.hikari.canvas.pool.MapPool;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.storage.WallRepo;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * M5.5：服务器启动末尾跑一次，把 walls 表里每行的 ProjectState compose 成像素写回 mapIds 对应的
 * MapView 缓存（{@link HikariCanvasRenderer}），同时把 mapIds 在 {@link MapPool} 里 bind 到
 * {@code wall:<wall_id>} 防 leak 扫描归还。
 *
 * <p>替代 M5-D7 引入的 {@code DraftRestorer}（M5.5 wall 模型不再区分草稿与已发布）。</p>
 */
public final class WallRestorer {

    private final Logger log;
    private final WallRepo wallRepo;
    private final MapPool mapPool;
    private final HikariCanvasRenderer renderer;
    private final CanvasCompositor compositor;
    private final PlaceholderRenderer placeholder;

    public WallRestorer(Logger log, WallRepo wallRepo, MapPool mapPool,
                        HikariCanvasRenderer renderer, CanvasCompositor compositor,
                        PlaceholderRenderer placeholder) {
        this.log = log;
        this.wallRepo = wallRepo;
        this.mapPool = mapPool;
        this.renderer = renderer;
        this.compositor = compositor;
        this.placeholder = placeholder;
    }

    /** 启动期一次性执行。返回恢复的 wall 数。 */
    public int restore() {
        List<WallRepo.Wall> all = wallRepo.loadAll();
        int ok = 0;
        for (WallRepo.Wall w : all) {
            try {
                if (restoreOne(w)) ok++;
            } catch (Exception ex) {
                log.log(Level.WARNING, "wall restore failed: " + w.wallId(), ex);
            }
        }
        log.info("WallRestorer: restored " + ok + "/" + all.size() + " wall(s)");
        return ok;
    }

    private boolean restoreOne(WallRepo.Wall w) {
        List<Integer> mapIds = w.mapIds();
        if (mapIds.isEmpty()) {
            log.fine("wall " + w.wallId() + " has no map_ids — skip");
            return false;
        }
        if (!mapPool.bindToWall(w.wallId(), mapIds)) {
            log.warning("WallRestorer: pool bind refused for " + w.wallId() + " mapIds=" + mapIds);
            // 仍然继续 compose 像素（视觉先恢复，异常池状态走 cleanup 处理）
        }

        ProjectState state = w.state();
        int widthMaps = Math.max(1, state.canvas().widthMaps());
        if (isPristine(state)) {
            int total = mapIds.size();
            for (int i = 0; i < total; i++) {
                renderer.update(mapIds.get(i), placeholder.render(i, total));
            }
            return true;
        }
        BufferedImage img = compositor.rasterize(state);
        int total = mapIds.size();
        for (int i = 0; i < total; i++) {
            byte[] pixels = compositor.toPaletteSlice(img, i, widthMaps);
            renderer.update(mapIds.get(i), pixels);
        }
        return true;
    }

    private static boolean isPristine(ProjectState state) {
        return state.elements().isEmpty()
                && "#FFFFFF".equalsIgnoreCase(state.canvas().background());
    }
}
