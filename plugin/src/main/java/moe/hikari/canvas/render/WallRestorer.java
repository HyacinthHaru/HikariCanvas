package moe.hikari.canvas.render;

import moe.hikari.canvas.pool.MapPool;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.storage.WallRepo;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * M5.5：服务器启动末尾跑一次，把 walls 表里每行的 ProjectState compose 成像素写回 mapIds 对应的
 * MapView 缓存（{@link HikariCanvasRenderer}），同时把 mapIds 在 {@link MapPool} 里 bind 到
 * {@code wall:<wall_id>} 防 leak 扫描归还。
 *
 * <p>替代 M5-D7 引入的 {@code DraftRestorer}（M5.5 wall 模型不再区分草稿与已发布）。</p>
 *
 * <p><b>M16 P2.5 池泄漏修复：</b> 启动期 restore 某 wall 的 bind / compose / IO 任一步抛异常时，
 * 必须把这一轮已经在 {@link MapPool} 里 bind 过的 mapId 全部 {@link MapPool#releaseToFree}
 * 回 FREE。否则它们留在 RESERVED 状态（owner = wall:&lt;id&gt;）但 wall 也没真正恢复 →
 * 必须等周期 detectLeaks 扫到才能回收，中间窗口期视为软泄漏；同时极端情况下"半态" wall
 * 也可能误导 wand 路径。</p>
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

    /** restore 失败的 wall_id（不可变 publish 后由 isRestorationFailed 读）。CopyOnWrite 语义足够：写一次读多次。 */
    private volatile Set<String> failedRestoreWallIds = Collections.emptySet();

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
     * 单 wall 恢复：bind 池 → compose 像素。任一步异常时把<b>本轮已 bind 的全部 mapId</b>
     * release 回 FREE，避免 RESERVED 软泄漏 + 防止 wall 留在"半态"。WallRepo 行不删除，
     * 留待下次重启再 retry。
     */
    private boolean restoreOne(WallRepo.Wall w) {
        List<Integer> mapIds = w.mapIds();
        if (mapIds.isEmpty()) {
            log.fine("wall " + w.wallId() + " has no map_ids — skip");
            return false;
        }

        // 这一轮已经"成功 bind 到本 wall"的 mapId（含 bind 成功但后续 compose 失败的情况）。
        // 任一步抛异常 → 全部 release 回 FREE。
        List<Integer> bound = new ArrayList<>();
        try {
            // P2.4 要求 bindToWall 校验 world；先解析 wall 所在 world
            World world = Bukkit.getWorld(w.key().world());
            if (world == null) {
                log.warning("WallRestorer: world '" + w.key().world()
                        + "' not loaded for wall " + w.wallId() + " — skipping restore");
                return false;
            }

            boolean bindOk = mapPool.bindToWall(w.wallId(), mapIds, world);
            if (!bindOk) {
                // bindToWall 返回 false 表示 mapIds 已被别 wall 占（应该不可能——启动期没人借）
                // 或者 mapId 不在池里（孤儿）。不能确定哪些已经 bind 成功，保守起见也释放整批：
                // bindToWall 是原子的（M16 前 / 后版本都是先全扫描再全更新），失败时 0 个 bind，
                // 所以 bound 列表保持空，下面 release 不会跑——安全。
                log.warning("WallRestorer: pool bind refused for " + w.wallId() + " mapIds=" + mapIds);
                return false;
            }
            bound.addAll(mapIds);  // bind 成功 → 全部记录待 rollback

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
        } catch (RuntimeException | Error ex) {
            // 关键修复：把本轮已 bind 的 mapId 全 release 回 FREE，避免软泄漏
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
                    "WallRestorer: wall " + w.wallId() + " restore failed; "
                            + "released " + releasedNow.size() + "/" + bound.size()
                            + " map(s) back to FREE pool: " + releasedNow
                            + " — wall row preserved in DB for retry on next start",
                    ex);
            // 不重抛——单 wall 失败应该让其它 wall 继续 restore（caller loop 期望 boolean）
            return false;
        }
    }

    private static boolean isPristine(ProjectState state) {
        return state.elements().isEmpty() && isWhiteSolid(state.canvas().background());
    }

    /**
     * M17 F5：background 升级为 {@link moe.hikari.canvas.state.Fill} 联合类型后的判等。
     * 只把 {@code SolidFill #FFFFFF[FF]} 视为"白色背景"；渐变 / 半透明都不算 pristine。
     */
    private static boolean isWhiteSolid(moe.hikari.canvas.state.Fill bg) {
        if (!(bg instanceof moe.hikari.canvas.state.SolidFill s)) return false;
        String c = s.color();
        if (c == null) return false;
        return "#FFFFFF".equalsIgnoreCase(c) || "#FFFFFFFF".equalsIgnoreCase(c);
    }
}
