package moe.hikari.canvas.render;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 所有 HikariCanvas 管理的 MapView 共享一个 {@link MapRenderer} 实例。
 * Paper/Bukkit 每 tick 会调 {@link #render} 生成 per-viewer canvas，
 * 如果我们不参与，默认 renderer（或空 canvas）就把客户端看到的内容覆盖成空白。
 *
 * <p>策略：不提供默认渲染，只在被外部显式 {@link #update(int, byte[])} 之后
 * 在 render 里把该 mapId 的像素写回 canvas。没更新过的 mapId 返回空白。</p>
 *
 * <p>线程安全：{@link ConcurrentHashMap} 保证 render（主线程）与 update
 * （WS 线程 / scheduler）不打架。</p>
 *
 * <p>{@code super(false)} = non-contextual，同一 view 所有 viewer 共享 canvas。
 * M2 阶段 Placeholder / paint 所有玩家看到同一张，这样省 CPU。M3 编辑实时投影
 * 时再评估是否需要 per-player。</p>
 */
public final class HikariCanvasRenderer extends MapRenderer {

    private final ConcurrentMap<Integer, byte[]> pixelsByMapId = new ConcurrentHashMap<>();

    public HikariCanvasRenderer() {
        super(false);
    }

    /** 外部推送像素；下一 tick Paper 会把它 sync 给所有 viewer。 */
    public void update(int mapId, byte[] pixels) {
        if (pixels == null || pixels.length != 128 * 128) {
            throw new IllegalArgumentException("expected 128*128 pixels");
        }
        pixelsByMapId.put(mapId, pixels);
    }

    /** 清除某 mapId 的缓存（地图归还池 / 删除时调）。 */
    public void clear(int mapId) {
        pixelsByMapId.remove(mapId);
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        byte[] pixels = pixelsByMapId.get(map.getId());
        if (pixels == null) return;
        for (int i = 0; i < pixels.length; i++) {
            canvas.setPixel(i % 128, i / 128, pixels[i]);
        }
    }
}
