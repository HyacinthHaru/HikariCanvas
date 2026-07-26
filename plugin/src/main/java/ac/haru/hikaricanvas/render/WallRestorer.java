package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.pool.MapPool;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.storage.WallRepo;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 服务器启动末尾跑一次，把 walls 表里每行的 ProjectState compose 成像素写回 mapIds 对应的
 * MapView 缓存（{@link HikariCanvasRenderer}），同时把 mapIds 在 {@link MapPool} 里 bind 到
 * {@code wall:<wall_id>} 防 leak 扫描归还。
 *
 * <p><b>失败时怎么处置地图，按失败发生在 bind 之前还是之后分两种：</b></p>
 * <ul>
 *   <li><b>bind 还没成功</b>（world 解析不到 / {@code bindToWall} 自己抛）：这一轮已经借到手的
 *       mapId 全部 {@link MapPool#releaseToFree} 回 FREE，不留半态预留。</li>
 *   <li><b>bind 已成功、后面的渲染炸了</b>（元素数据损坏、瞬时 OOM、AWT 抛错……）：
 *       <b>保留绑定关系</b>，只记录告警。这批地图在 DB 里本来就属于这面 wall（walls 行还在，
 *       泄漏检测认得它、不会回收），把它们放回 FREE 才是真正的灾难——下一次 confirm 会把
 *       同一张地图借给别的 wall，两面墙共用一张图互相覆盖像素，而原 wall 的 map_ids 指向已被
 *       抢走的地图，下次启动 bind 直接失败，这面墙就再也恢复不了了。</li>
 * </ul>
 *
 * <p>失败的 wall_id 被记入 {@link #failedRestoreWallIds()}（线程安全只读快照）；
 * wand 路径在玩家与该 wall 交互时可调用 {@link #isRestorationFailed(String)} 做 ActionBar 提示。
 * 失败的 wall 在 DB 里不删除——下次重启再 retry。</p>
 */
public final class WallRestorer {

    private final Logger log;
    private final WallRepo wallRepo;
    private final MapPool mapPool;
    private final HikariCanvasRenderer renderer;
    private final CanvasCompositor compositor;
    private final PlaceholderRenderer placeholder;
    /**
     * world 名 → {@link World} 解析 seam（生产默认 {@link Bukkit#getWorld}）。
     * WallRestorerTest 注入 fake（返回 JDK Proxy World / 返 null 模拟世界未加载），
     * 让「restore 失败 → releaseToFree 回滚」守卫无需 Bukkit server 即可跑。
     */
    private final Function<String, World> worldResolver;

    /** restore 失败的 wall_id（不可变 publish 后由 isRestorationFailed 读）。CopyOnWrite 语义足够：写一次读多次。 */
    private volatile Set<String> failedRestoreWallIds = Collections.emptySet();

    public WallRestorer(Logger log, WallRepo wallRepo, MapPool mapPool,
                        HikariCanvasRenderer renderer, CanvasCompositor compositor,
                        PlaceholderRenderer placeholder) {
        this(log, wallRepo, mapPool, renderer, compositor, placeholder, Bukkit::getWorld);
    }

    public WallRestorer(Logger log, WallRepo wallRepo, MapPool mapPool,
                        HikariCanvasRenderer renderer, CanvasCompositor compositor,
                        PlaceholderRenderer placeholder,
                        Function<String, World> worldResolver) {
        this.log = log;
        this.wallRepo = wallRepo;
        this.mapPool = mapPool;
        this.renderer = renderer;
        this.compositor = compositor;
        this.placeholder = placeholder;
        this.worldResolver = worldResolver;
    }

    /** 启动期一次性执行。返回恢复的 wall 数。 */
    public int restore() {
        List<WallRepo.Wall> all = wallRepo.loadAll();
        int ok = 0;
        Set<String> failed = new HashSet<>();
        for (WallRepo.Wall w : all) {
            boolean ok1;
            try {
                ok1 = restoreOne(w);
            } catch (Exception ex) {
                // restoreOne 内部已尝试 release + log；这里兜底，确保单 wall 失败不影响其它
                log.log(Level.SEVERE, "wall restore unexpected failure: " + w.wallId(), ex);
                ok1 = false;
            }
            if (ok1) {
                ok++;
            } else {
                failed.add(w.wallId());
            }
        }
        this.failedRestoreWallIds = failed.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(failed);
        log.info("WallRestorer: restored " + ok + "/" + all.size() + " wall(s)"
                + (failed.isEmpty() ? "" : "; failed=" + failed));
        return ok;
    }

    /**
     * 返回 wand listener / WS 路径用的"启动期 restore 失败"wall_id 集合（不可变快照）。
     * 玩家与这些 wall 交互时应提示「Wall failed to restore on startup, see server log」。
     */
    public Set<String> failedRestoreWallIds() {
        return failedRestoreWallIds;
    }

    /** 单 wall_id 是否启动期 restore 失败。 */
    public boolean isRestorationFailed(String wallId) {
        return wallId != null && failedRestoreWallIds.contains(wallId);
    }

    /**
     * 单 wall 恢复：bind 池 → compose 像素。bind 之前失败 → 把本轮已借到的 mapId 全 release
     * 回 FREE；bind 之后（渲染阶段）失败 → 保留绑定只记告警，理由见类 javadoc。
     * 两种情况 WallRepo 行都不删除，留待下次重启再 retry。
     */
    private boolean restoreOne(WallRepo.Wall w) {
        List<Integer> mapIds = w.mapIds();
        if (mapIds.isEmpty()) {
            log.fine("wall " + w.wallId() + " has no map_ids — skip");
            return false;
        }

        // 这一轮已经借到手、但绑定关系还没落定的 mapId。bindToWall 目前是原子的
        // （先全量校验 + 单事务落盘，再改内存），所以真正走到"要回滚"这一步的只有
        // bindToWall 自己抛异常的情况；保留这个列表是为了 bind 将来变成多步时不出漏子。
        List<Integer> bound = new ArrayList<>();
        boolean bindCommitted = false;
        try {
            // bindToWall 需校验 world；先解析 wall 所在 world
            World world = worldResolver.apply(w.key().world());
            if (world == null) {
                log.warning("WallRestorer: world '" + w.key().world()
                        + "' not loaded for wall " + w.wallId() + " — skipping restore");
                return false;
            }

            boolean bindOk = mapPool.bindToWall(w.wallId(), mapIds, world);
            if (!bindOk) {
                // bindToWall 返回 false 表示 mapIds 已被别 wall 占（应该不可能——启动期没人借）
                // 或者 mapId 不在池里（孤儿）。不能确定哪些已经 bind 成功，保守起见也释放整批：
                // bindToWall 是原子的（先全扫描再全更新），失败时 0 个 bind，
                // 所以 bound 列表保持空，下面 release 不会跑——安全。
                log.warning("WallRestorer: pool bind refused for " + w.wallId() + " mapIds=" + mapIds);
                return false;
            }
            bound.addAll(mapIds);
            bindCommitted = true;  // 绑定关系已落定：后面再失败也不动这些地图

            ProjectState state = w.state();
            int widthMaps = Math.max(1, state.canvas().widthMaps());
            if (isPristine(state)) {
                int total = mapIds.size();
                for (int i = 0; i < total; i++) {
                    renderer.update(mapIds.get(i), placeholder.render(i, total));
                }
                return true;
            }
            // 传 wallId 让 ${var:user/X} 注入；启动期 variable store 加载在 compositor
            // setVariableSupport 之前，restorer 仍可能在 setVariableSupport 前调用 —— 此时 interpolator
            // 为 null，rasterize 走原行为；setVariableSupport 注入后再次 restore（极少见）也安全。
            BufferedImage img = compositor.rasterize(state, w.wallId());
            int total = mapIds.size();
            for (int i = 0; i < total; i++) {
                byte[] pixels = compositor.toPaletteSlice(img, i, widthMaps);
                renderer.update(mapIds.get(i), pixels);
            }
            return true;
        } catch (RuntimeException | Error ex) {
            if (bindCommitted) {
                // 渲染阶段炸的。地图仍然是这面 wall 的（walls 行还在，泄漏检测认得它），
                // 放回 FREE 会让下一次 confirm 把同一张图借给别人 → 两面墙共用一张地图，
                // 且这面墙下次启动 bind 必失败、永久恢复不了。所以只报错，不动池。
                log.log(Level.SEVERE,
                        "WallRestorer: wall " + w.wallId() + " failed while rendering; "
                                + "its " + bound.size() + " map(s) stay bound to this wall "
                                + bound + " (returning them to the free pool would let another"
                                + " wall grab them) — wall row preserved in DB for retry on"
                                + " next start",
                        ex);
                return false;
            }
            // bind 还没落定就失败：把这一轮已借到手的 mapId 全部还回 FREE，不留半态预留。
            List<Integer> releasedNow = new ArrayList<>();
            for (int mid : bound) {
                try {
                    if (mapPool.releaseToFree(mid)) releasedNow.add(mid);
                } catch (Exception relEx) {
                    log.log(Level.SEVERE,
                            "WallRestorer: failed to release map_id=" + mid
                                    + " after wall " + w.wallId() + " restore error", relEx);
                }
            }
            log.log(Level.SEVERE,
                    "WallRestorer: wall " + w.wallId() + " restore failed before the pool"
                            + " binding was committed; released " + releasedNow.size() + "/"
                            + bound.size() + " map(s) back to FREE pool: " + releasedNow
                            + " — wall row preserved in DB for retry on next start",
                    ex);
            // 不重抛——单 wall 失败应该让其它 wall 继续 restore（caller loop 期望 boolean）
            return false;
        }
    }

    private static boolean isPristine(ProjectState state) {
        // 见 CanvasProjector.isPristine 同款判定
        return state.isPristineAcrossLayers() && isWhiteSolid(state.canvas().background());
    }

    /**
     * background 是 {@link ac.haru.hikaricanvas.state.Fill} 联合类型后的判等。
     * 只把 {@code SolidFill #FFFFFF[FF]} 视为"白色背景"；渐变 / 半透明都不算 pristine。
     */
    private static boolean isWhiteSolid(ac.haru.hikaricanvas.state.Fill bg) {
        if (!(bg instanceof ac.haru.hikaricanvas.state.SolidFill s)) return false;
        String c = s.color();
        if (c == null) return false;
        return "#FFFFFF".equalsIgnoreCase(c) || "#FFFFFFFF".equalsIgnoreCase(c);
    }
}
