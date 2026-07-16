package moe.hikari.canvas.pool;

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
     * 返回该 map 的 world；map 不存在返回 {@code null}（对应 initialize 的 missingMapView 分支）。
     */
    World installRenderer(int mapId, MapRenderer renderer);

    /** bindToWall 用：{@code Bukkit.getMap(mapId)} 的 world；map 不存在返回 {@code null}。 */
    World mapWorld(int mapId);

    /** {@code Bukkit.getWorld(name)}；未加载返回 {@code null}。 */
    World worldByName(String name);
}
