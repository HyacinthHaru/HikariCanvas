package ac.haru.hikaricanvas.pool;

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
    /**
     * MapView 存在、但其 world 未加载的 mapId（{@code MapView.getWorld()} 返 null）。
     * 模拟 Multiverse 等插件管理的世界在 onEnable 时尚未加载的真实场景。
     */
    final java.util.Set<Integer> mapViewsWithUnloadedWorld = new java.util.HashSet<>();

    /**
     * 让后续 {@link #createMap} 从 {@code id} 开始发号。
     *
     * <p>「重启」类测试会新建一个 fake backend，其 {@code nextId} 从头开始，铸出的新 id 会与
     * 上一轮的 id 撞车，让「原来那些行是否被删」的断言失真。用本方法把新 backend 的号段推开。</p>
     */
    void seedNextId(int id) {
        if (id > nextId) nextId = id;
    }

    /** 预置一张"已存在的 map"（模拟重启后 Bukkit 已有的 MapView）——用于 initialize 恢复测试。 */
    void preexisting(int id, World world) {
        maps.put(id, world);
        worlds.putIfAbsent(world.getName(), world);
        if (id >= nextId) nextId = id + 1;
    }

    /**
     * 预置一张 MapView 存在但 world 未加载的 map：{@code installRenderer} / {@code mapWorld}
     * 返 null（与"MapView 不存在"同形），但 {@code hasMapView} 为 true。
     */
    void preexistingWithUnloadedWorld(int id) {
        mapViewsWithUnloadedWorld.add(id);
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

    @Override
    public boolean hasMapView(int mapId) {
        return maps.containsKey(mapId) || mapViewsWithUnloadedWorld.contains(mapId);
    }
}
