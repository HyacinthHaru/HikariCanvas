package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.pool.MapPool;
import ac.haru.hikaricanvas.state.Fill;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.SolidFill;
import ac.haru.hikaricanvas.storage.WallRepo;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.awt.image.BufferedImage;
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
 * <p><b>失败时按发生在 bind 之前还是之后分两种处置：</b></p>
 * <ul>
 *   <li><b>bind 尚未成功</b>（world 未加载 / {@code bindToWall} 拒绝或抛异常）：
 *       {@link MapPool#bindToWall} 是原子的，池状态未发生半态修改，直接记录日志并跳过恢复。</li>
 *   <li><b>bind 已成功、后续渲染失败</b>（元素数据损坏、瞬时 OOM、AWT 抛错等）：
 *       <b>保留绑定关系</b>，只记录告警。这批地图在 DB 里本来就属于这面 wall（walls 行还在，
 *       泄漏检测认得它、不会回收）；若放回 FREE，下一次 confirm 会把同一张地图借给别的 wall，
 *       导致两面墙共用一张图互相覆盖像素，且原 wall 下次启动 bind 直接失败。</li>
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
     * {@code WallRestorerTest} 注入 fake（返回 JDK Proxy World 或 {@code null} 模拟世界未加载），
     * 无需启动 Bukkit server 即可覆盖启动期恢复路径。
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
                // 兜底保护，确保单 wall 异常不影响其它 wall 恢复
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
     * 单 wall 恢复：bind 池 → compose 像素。bind 之前失败时池状态未变更，直接跳过；
     * bind 之后（渲染阶段）失败则保留绑定关系并记录告警，理由见类注释。
     * 两种情况 WallRepo 行都不删除，留待下次重启再 retry。
     */
    private boolean restoreOne(WallRepo.Wall w) {
        List<Integer> mapIds = w.mapIds();
        if (mapIds.isEmpty()) {
            log.fine("wall " + w.wallId() + " has no map_ids — skip");
            return false;
        }

        boolean bindCommitted = false;
        try {
            World world = worldResolver.apply(w.key().world());
            if (world == null) {
                log.warning("WallRestorer: world '" + w.key().world()
                        + "' not loaded for wall " + w.wallId() + " — skipping restore");
                return false;
            }

            if (!mapPool.bindToWall(w.wallId(), mapIds, world)) {
                log.warning("WallRestorer: pool bind refused for " + w.wallId() + " mapIds=" + mapIds);
                return false;
            }
            bindCommitted = true;

            ProjectState state = w.state();
            int widthMaps = Math.max(1, state.canvas().widthMaps());
            if (isPristine(state)) {
                int total = mapIds.size();
                for (int i = 0; i < total; i++) {
                    renderer.update(mapIds.get(i), placeholder.render(i, total));
                }
                return true;
            }
            // 传 wallId 让 ${var:user/X} 注入；若 setVariableSupport 尚未注入，interpolator 为 null，
            // rasterize 自然跳过占位符替换。
            BufferedImage img = compositor.rasterize(state, w.wallId());
            int total = mapIds.size();
            for (int i = 0; i < total; i++) {
                byte[] pixels = compositor.toPaletteSlice(img, i, widthMaps);
                renderer.update(mapIds.get(i), pixels);
            }
            return true;
        } catch (RuntimeException | Error ex) {
            if (bindCommitted) {
                log.log(Level.SEVERE,
                        "WallRestorer: wall " + w.wallId() + " failed while rendering; "
                                + "its " + mapIds.size() + " map(s) retained for this wall "
                                + mapIds + " (returning them to the free pool would let another"
                                + " wall grab them) — wall row preserved in DB for retry on"
                                + " next start",
                        ex);
            } else {
                log.log(Level.SEVERE,
                        "WallRestorer: wall " + w.wallId() + " restore skipped before pool"
                                + " binding was committed — wall row preserved in DB for retry on"
                                + " next start",
                        ex);
            }
            return false;
        }
    }

    private static boolean isPristine(ProjectState state) {
        // 见 CanvasProjector.isPristine 同款判定
        return state.isPristineAcrossLayers() && isWhiteSolid(state.canvas().background());
    }

    /**
     * background 是 {@link Fill} 联合类型后的判等。
     * 只把 {@code SolidFill #FFFFFF[FF]} 视为"白色背景"；渐变 / 半透明都不算 pristine。
     */
    private static boolean isWhiteSolid(Fill bg) {
        if (!(bg instanceof SolidFill s)) return false;
        String c = s.color();
        if (c == null) return false;
        return "#FFFFFF".equalsIgnoreCase(c) || "#FFFFFFFF".equalsIgnoreCase(c);
    }
}
