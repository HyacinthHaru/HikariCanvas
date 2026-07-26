package ac.haru.hikaricanvas.pool;

import org.bukkit.World;
import org.bukkit.map.MapRenderer;

/**
 * MapPool 与 Bukkit 地图 API 之间的 seam。抽出全部 {@code Bukkit.createMap / getMap /
 * getWorld} 调用，让 MapPool 的池簿记（借出优先复用 FREE、绝不多 {@code createMap} 等核心
 * 不变式）可在无 Bukkit server 的单测里用 fake backend 驱动 + 断言。
 *
 * <p>生产实现 {@link BukkitMapBackend} 逐字委托 Bukkit；测试注入内存 fake。本 seam 不含
 * 主线程判定——{@code MapPool.assertMainThread} 在 Bukkit server 为 null 时早返，单测天然放行。</p>
 */
public interface MapBackend {

    /**
     * {@code Bukkit.createMap(world)} + 清默认 renderer + 装 {@code renderer}；返回新 mapId。
     * <b>这是全池唯一的 map-id 铸造点（idcounts.dat 膨胀点）。</b>
     */
    int createMap(World world, MapRenderer renderer);

    /**
     * initialize 用：{@code Bukkit.getMap(mapId)} → 清默认 renderer + 装 {@code renderer} →
     * 返回该 map 的 world；<b>返回 null 有两种含义</b>——MapView 不存在，或 MapView 存在但其
     * world 未加载（{@code MapView.getWorld()} 此时返 null）。两者必须用
     * {@link #hasMapView(int)} 区分：前者才是真孤儿可删，后者删了会永久丢地图。
     */
    World installRenderer(int mapId, MapRenderer renderer);

    /**
     * {@code Bukkit.getMap(mapId) != null}。
     *
     * <p>存在的唯一理由是给 {@link #installRenderer} 的 null 返回值消歧：MapView 在不在，
     * 与它的 world 加载没加载，是两件事。{@code MapPool.initialize} 在 onEnable 同步执行，
     * 此时由 Multiverse 等插件管理的世界往往尚未加载 —— 把这些 map 当孤儿 DELETE 掉会让
     * mapId 从池簿记永久消失（不再复用 → 重新 createMap → {@code idcounts.dat} 膨胀，
     * 项目核心风险），且该世界的 wall 因 byId 无此 mapId 永久打不开。</p>
     */
    boolean hasMapView(int mapId);

    /** bindToWall 用：{@code Bukkit.getMap(mapId)} 的 world；map 不存在返回 {@code null}。 */
    World mapWorld(int mapId);

    /** {@code Bukkit.getWorld(name)}；未加载返回 {@code null}。 */
    World worldByName(String name);
}
