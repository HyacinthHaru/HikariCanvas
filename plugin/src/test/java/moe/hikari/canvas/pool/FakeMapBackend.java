package moe.hikari.canvas.pool;

import org.bukkit.World;
import org.bukkit.map.MapRenderer;

import java.util.HashMap;
import java.util.Map;

/** 0.9.6：{@link MapBackend} 内存 fake。核心：{@link #createMapCalls} 计 map 铸造次数（idcounts 膨胀）。 */
final class FakeMapBackend implements MapBackend {

    int createMapCalls = 0;
    private int nextId = 1000;
    final Map<Integer, World> maps = new HashMap<>();
    final Map<String, World> worlds = new HashMap<>();

    /** 预置一张"已存在的 map"（模拟重启后 Bukkit 已有的 MapView）——用于 initialize 恢复测试。 */
    void preexisting(int id, World world) {
        maps.put(id, world);
        worlds.putIfAbsent(world.getName(), world);
        if (id >= nextId) nextId = id + 1;
    }

    @Override
    public int createMap(World world, MapRenderer renderer) {
        createMapCalls++;
        int id = nextId++;
        maps.put(id, world);
        worlds.putIfAbsent(world.getName(), world);
        return id;
    }

    @Override public World installRenderer(int mapId, MapRenderer renderer) { return maps.get(mapId); }
    @Override public World mapWorld(int mapId) { return maps.get(mapId); }
    @Override public World worldByName(String name) { return worlds.get(name); }
}
