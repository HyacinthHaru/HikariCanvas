package moe.hikari.canvas.pool;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.ArrayList;

/** 0.9.6：{@link MapBackend} 生产实现，逐字委托 Bukkit（与 0.9.6 前 MapPool 内联调用等价）。 */
public final class BukkitMapBackend implements MapBackend {

    @Override
    public int createMap(World world, MapRenderer renderer) {
        MapView view = Bukkit.createMap(world);
        new ArrayList<>(view.getRenderers()).forEach(view::removeRenderer);
        view.addRenderer(renderer);
        return view.getId();
    }

    @Override
    public World installRenderer(int mapId, MapRenderer renderer) {
        MapView view = Bukkit.getMap(mapId);
        if (view == null) return null;
        new ArrayList<>(view.getRenderers()).forEach(view::removeRenderer);
        view.addRenderer(renderer);
        return view.getWorld();
    }

    @Override
    public World mapWorld(int mapId) {
        MapView view = Bukkit.getMap(mapId);
        return view == null ? null : view.getWorld();
    }

    @Override
    public World worldByName(String name) {
        return Bukkit.getWorld(name);
    }
}
