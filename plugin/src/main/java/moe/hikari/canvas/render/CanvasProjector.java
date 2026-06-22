package moe.hikari.canvas.render;

import moe.hikari.canvas.deploy.MapPacketSender;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.storage.WallRepo;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 把 {@link DirtyRegion} 翻译成实际的像素推送：
 * {@link CanvasCompositor#rasterize} 整张大画布一次 → 对 region 覆盖的每个 mapIndex
 * {@link CanvasCompositor#toPaletteSlice} 量化切片 → 写入 {@link HikariCanvasRenderer}。
 * 下一 tick Paper 自动 sync 给 viewer。
 *
 * <p><b>M4-T4 变化（相对 M3）：</b> rasterize 和 quantize 分两阶段——一次 rasterize
 * 复用给多个 mapIndex；只有被 region 覆盖的 map 才做量化，节省 mapping 成本。</p>
 *
 * <p><b>线程：</b> {@link HikariCanvasRenderer#update} 线程安全（ConcurrentMap）；
 * {@link CanvasCompositor} 每次分配独立 BufferedImage。可在 Jetty WS 线程或 BukkitScheduler
 * async task 里调用，不必切主线程。</p>
 *
 * <p><b>0.6 P2：</b>实现 {@link AnimationTicker.FrameRenderer}——AnimationTicker 的逐帧出口
 * （{@link #hasViewers} / {@link #renderFrame}，含 per-map 帧间 diff + 新观察者全量补发），
 * 见 docs/architecture.md §5.5。</p>
 */
public final class CanvasProjector implements AnimationTicker.FrameRenderer {

    private final HikariCanvasRenderer canvasRenderer;
    private final CanvasCompositor compositor;
    private final PlaceholderRenderer placeholderRenderer;
    private final Logger log;
    /**
     * 0.4.0 方案 B 自适应渲染：主动给 chunk-loaded viewer 推 ClientboundMapItemDataPacket。
     * Paper 默认 MapView sync 间隔 250ms-5s 不可控，秒精度倒计时场景不够稳；本路径补齐"渲染完
     * 立刻推帧"语义。可空（测试 / 旧构造器路径）—— null 时跳过主动推送，回落 Paper 默认 sync。
     */
    private final MapPacketSender mapPacketSender;
    /**
     * 0.4.0 方案 B：拿 wall 元数据（world / origin）算 viewer 候选。可空——同上。
     */
    private final WallRepo wallRepo;
    /**
     * 0.4.0 方案 B：viewer 检测的 chunk 曼哈顿距离阈值。8 chunks ≈ 默认 view distance 内。
     * 超出阈值的玩家由 Paper 默认 sync 兜底。
     */
    private static final int VIEWER_CHUNK_DISTANCE = 8;

    /** M1-M28 兼容构造器：不带主动推帧能力，回落 Paper 默认 sync。 */
    public CanvasProjector(HikariCanvasRenderer canvasRenderer,
                           CanvasCompositor compositor,
                           PlaceholderRenderer placeholderRenderer,
                           Logger log) {
        this(canvasRenderer, compositor, placeholderRenderer, log, null, null);
    }

    /**
     * 0.4.0 方案 B：完整构造器；mapPacketSender / wallRepo 任一为 null 即关闭主动推帧（fallback）。
     */
    public CanvasProjector(HikariCanvasRenderer canvasRenderer,
                           CanvasCompositor compositor,
                           PlaceholderRenderer placeholderRenderer,
                           Logger log,
                           MapPacketSender mapPacketSender,
                           WallRepo wallRepo) {
        this.canvasRenderer = canvasRenderer;
        this.compositor = compositor;
        this.placeholderRenderer = placeholderRenderer;
        this.log = log;
        this.mapPacketSender = mapPacketSender;
        this.wallRepo = wallRepo;
    }

    /**
     * 渲染 region 覆盖的所有 maps。{@code region == null} 直接返回（op 未产生像素变化）。
     *
     * @return 实际更新的 mapId 数量
     */
    public int project(Session session, DirtyRegion region) {
        if (region == null) return 0;
        ProjectState state = session.projectState();
        List<Integer> mapIds = session.mapIds();
        if (state == null || mapIds == null || mapIds.isEmpty()) return 0;

        int widthMaps = state.canvas().widthMaps();
        int heightMaps = state.canvas().heightMaps();
        List<Integer> indices = region.coveredMapIndices(widthMaps, heightMaps);
        if (indices.isEmpty()) return 0;

        // M4 小修：{@code ProjectState} 回到"pristine 初始态"时，不走 compositor
        // 渲空白，而是重绘 placeholder（灰底 + HIKARICANVAS 水印 + slot 标签），
        // 保留 confirm 阶段的视觉提示。触发条件：elements 空 && background=#FFFFFF（session 刚 confirm 时就是这个状态，undo 到底也会回到这）。
        // 0.4.0 方案 B：viewer 列表只算一次给本次 project 用（pristine / 正常分支共享）。
        // 计算稍贵（遍历 world.getPlayers()）；只在 mapPacketSender + wallRepo 齐全时算。
        List<Player> viewers = findViewersForWall(session.wallId());

        if (isPristine(state)) {
            int updated = 0;
            int total = mapIds.size();
            for (Integer idx : indices) {
                if (idx < 0 || idx >= total) continue;
                try {
                    byte[] pixels = placeholderRenderer.render(idx, total);
                    int mapId = mapIds.get(idx);
                    canvasRenderer.update(mapId, pixels);
                    pushToViewers(viewers, mapId, pixels);
                    updated++;
                } catch (Exception e) {
                    log.warning("CanvasProjector: placeholder render failed mapIndex=" + idx
                            + " err=" + e.getMessage());
                }
            }
            return updated;
        }

        BufferedImage img;
        try {
            // 0.4.0-P1-C：传 session.wallId() 让 compositor 把 ${var:user/X} 注入当前 wallId，
            // 渲染末尾 VariableStore.markWallReferences 倒排索引联动。session.wallId() 在 SELECTING
            // 阶段为 null —— compositor 内部对 null wallId 走"无 user 变量解析 + 不写倒排索引"分支。
            img = compositor.rasterize(state, session.wallId());
        } catch (Exception e) {
            log.warning("CanvasProjector: rasterize failed err=" + e.getMessage());
            return 0;
        }

        int updated = 0;
        for (Integer idx : indices) {
            if (idx < 0 || idx >= mapIds.size()) continue;
            int mapId = mapIds.get(idx);
            try {
                byte[] pixels = compositor.toPaletteSlice(img, idx, widthMaps);
                canvasRenderer.update(mapId, pixels);
                // 0.4.0 方案 B：渲染完立刻给 chunk-loaded viewer 推帧（Paper 默认 sync
                // 250ms-5s 不稳，秒精度倒计时场景必须主动）。
                pushToViewers(viewers, mapId, pixels);
                updated++;
            } catch (Exception e) {
                log.warning("CanvasProjector: quantize failed mapIndex=" + idx
                        + " mapId=" + mapId + " err=" + e.getMessage());
            }
        }
        return updated;
    }

    /**
     * 0.4.0 方案 B：主动给 viewer 推 ClientboundMapItemDataPacket。
     * 单 viewer 推送失败不影响其他 viewer / 其他 map。
     */
    private void pushToViewers(List<Player> viewers, int mapId, byte[] pixels) {
        if (viewers == null || viewers.isEmpty() || mapPacketSender == null) return;
        for (Player p : viewers) {
            try {
                mapPacketSender.sendFullMap(p, mapId, pixels);
            } catch (Exception e) {
                log.warning("CanvasProjector: push packet failed mapId=" + mapId
                        + " viewer=" + p.getName() + " err=" + e.getMessage());
            }
        }
    }

    /**
     * 0.4.0 方案 B：找 chunk-loaded 范围内的 viewer 候选。
     *
     * <p>策略：从 {@link WallRepo} 拿 wall 元数据 → 取 world + origin chunk → 取 world 当前在线
     * 玩家 → 按曼哈顿 chunk 距离阈值 {@link #VIEWER_CHUNK_DISTANCE} 过滤。Paper 的 world.getPlayers
     * / Player.getLocation 是线程安全只读，可在 ProjectionThrottler async 线程直接调，省去主线程
     * 调度的 50ms 抖动。</p>
     *
     * <p>线程安全：方法本身只读 Bukkit API + 本地集合；可重入。</p>
     *
     * @return 候选 viewer 列表（可能空）；wallId 为 null / wall 不存在 / world 未加载 → 空表，
     *         调用方应自然 fallback Paper 默认 sync。
     */
    private List<Player> findViewersForWall(String wallId) {
        if (wallId == null || mapPacketSender == null || wallRepo == null) {
            return List.of();
        }
        WallRepo.Wall w;
        try {
            w = wallRepo.loadById(wallId).orElse(null);
        } catch (Exception e) {
            return List.of();
        }
        return findViewersFor(w);
    }

    /**
     * 0.6 P2：已持有 Wall 元数据时的 viewer 查询重载——AnimationTicker 缓存 Wall 后逐帧调，
     * 免去 {@link #findViewersForWall} 内的 loadById（20fps 下每 tick 一次 DB 读不可接受，
     * docs/architecture.md §5.5 状态缓存）。线程安全性与原方法一致（只读 Bukkit API）。
     */
    private List<Player> findViewersFor(WallRepo.Wall w) {
        if (w == null || w.key() == null || mapPacketSender == null) return List.of();
        World world = Bukkit.getWorld(w.key().world());
        if (world == null) return List.of();
        int wallChunkX = w.key().originX() >> 4;
        int wallChunkZ = w.key().originZ() >> 4;
        List<Player> out = new ArrayList<>();
        // P2-11：world.getPlayers() 取列表 + for-each 迭代本身也包 try-catch，收窄非主线程
        // 读 Bukkit 玩家列表时瞬态 CME/RuntimeException 的逃逸面（避免异常冒泡到
        // ProjectionThrottler.flushLocked 把整帧投影判为失败）。返回已收集到的部分候选。
        try {
            for (Player p : world.getPlayers()) {
                try {
                    org.bukkit.Location loc = p.getLocation();
                    int pChunkX = loc.getBlockX() >> 4;
                    int pChunkZ = loc.getBlockZ() >> 4;
                    int dx = Math.abs(pChunkX - wallChunkX);
                    int dz = Math.abs(pChunkZ - wallChunkZ);
                    if (dx + dz <= VIEWER_CHUNK_DISTANCE) {
                        out.add(p);
                    }
                } catch (Exception ignored) {
                    // 玩家瞬间下线 / 跨维度切换 → Location 可能短暂不可用；跳过该 viewer
                }
            }
        } catch (Exception ignored) {
            // world.getPlayers() 与主线程 join/quit 并发时的瞬态迭代异常；返回已收集部分
        }
        return out;
    }

    /**
     * pristine = 与 {@code SessionManager.confirm} 时构造的初始 state 等价。
     * Ultrareview 2026-05-25 #6：用 isPristineAcrossLayers 跨所有 visible+opacity>0 layer 判 elements
     * 是否全空，避免 active layer 空 + 其他 layer 有内容时被误判为 pristine。
     */
    private static boolean isPristine(ProjectState state) {
        return state.isPristineAcrossLayers() && isWhiteSolid(state.canvas().background());
    }

    /** M17 F5：判 background 是否纯白 SolidFill（渐变 / 非白 / 半透明都不算 pristine）。 */
    private static boolean isWhiteSolid(moe.hikari.canvas.state.Fill bg) {
        if (!(bg instanceof moe.hikari.canvas.state.SolidFill s)) return false;
        String c = s.color();
        if (c == null) return false;
        return "#FFFFFF".equalsIgnoreCase(c) || "#FFFFFFFF".equalsIgnoreCase(c);
    }

    /** 渲染全部 maps（canvas.background / snapshot 后 / reset 时用）。 */
    public int projectAll(Session session) {
        if (session.projectState() == null) return 0;
        return project(session, DirtyRegion.fullCanvas(session.projectState()));
    }

    /**
     * Ultrareview 2026-05-25 #1：无活跃 editor session 时按 wallId + ProjectState 直接渲染
     * 全 canvas。用于 VariableStore 变化时，被引用的 wall 当前没人在编辑也要刷新像素到
     * deployed maps。
     *
     * <p>不进 ProjectionThrottler 节流——这条路径仅在"无活跃 session"时走，外部触发频率
     * 已被上游（Provider refresh interval 或 Plugin Push API rate limit）控制。</p>
     *
     * <p><b>线程：</b> 调用方需保证不与同 wallId 的活跃 session 并发触发；典型用法 = 在
     * HikariCanvas 注入的 callback 中先 {@code submitFullCanvasDirtyByWall}，若该方法
     * 返回 0（无活跃 session）再调本方法。{@code compositor.rasterize} 与 {@code renderer.update}
     * 都是线程安全的。</p>
     *
     * @return 实际更新的 mapId 数量；wall 不存在 / state 缺失 / map 列表空 → 0
     */
    public int projectByWall(String wallId) {
        if (wallId == null || wallRepo == null) return 0;
        WallRepo.Wall w;
        try {
            w = wallRepo.loadById(wallId).orElse(null);
        } catch (Exception e) {
            log.warning("CanvasProjector.projectByWall: load failed wallId=" + wallId
                    + " err=" + e.getMessage());
            return 0;
        }
        if (w == null) return 0;
        ProjectState state = w.state();
        List<Integer> mapIds = w.mapIds();
        if (state == null || mapIds == null || mapIds.isEmpty()) return 0;

        int widthMaps = state.canvas().widthMaps();
        // 全画布渲染：viewer push 走同款路径与活跃 session 一致
        List<Player> viewers = findViewersForWall(wallId);

        // pristine 状态 → placeholder（与活跃 session 路径对称）
        if (state.isPristineAcrossLayers() && isWhitePristineBg(state)) {
            int total = mapIds.size();
            int updated = 0;
            for (int i = 0; i < total; i++) {
                try {
                    byte[] pixels = placeholderRenderer.render(i, total);
                    int mapId = mapIds.get(i);
                    canvasRenderer.update(mapId, pixels);
                    pushToViewers(viewers, mapId, pixels);
                    updated++;
                } catch (Exception e) {
                    log.warning("CanvasProjector.projectByWall: placeholder failed mapIndex=" + i
                            + " wallId=" + wallId + " err=" + e.getMessage());
                }
            }
            return updated;
        }

        BufferedImage img;
        try {
            img = compositor.rasterize(state, wallId);
        } catch (Exception e) {
            log.warning("CanvasProjector.projectByWall: rasterize failed wallId=" + wallId
                    + " err=" + e.getMessage());
            return 0;
        }
        int updated = 0;
        int total = mapIds.size();
        for (int i = 0; i < total; i++) {
            int mapId = mapIds.get(i);
            try {
                byte[] pixels = compositor.toPaletteSlice(img, i, widthMaps);
                canvasRenderer.update(mapId, pixels);
                pushToViewers(viewers, mapId, pixels);
                updated++;
            } catch (Exception e) {
                log.warning("CanvasProjector.projectByWall: quantize failed mapIndex=" + i
                        + " mapId=" + mapId + " wallId=" + wallId
                        + " err=" + e.getMessage());
            }
        }
        return updated;
    }

    /** Pristine 判定的背景部分，等同 {@link #isPristine}/{@link #isWhiteSolid} 但只看背景。 */
    private static boolean isWhitePristineBg(ProjectState state) {
        return isWhiteSolid(state.canvas().background());
    }

    // ---------- 0.6 P2：AnimationTicker.FrameRenderer 实现（仅 Ticker 线程调） ----------

    /**
     * 动画逐帧出口（docs/architecture.md §5.5）：viewer 查询（空且非 force → 跳过，
     * <b>不 rasterize</b>——viewer-gated 真正省 CPU 的点；单次扫描，审查确认项 #9）→
     * rasterize 插值帧 → per-map 量化 → <b>帧间 diff</b>（只 update / 只发像素变了的 map，
     * §3.4「基本的不浪费」）→ 新观察者出现时全量补发（diff 跳过的 map 对新 viewer 是缺帧）。
     *
     * <p>主 buffer 经 {@link CanvasCompositor#rasterize} 借自 {@link BufferPool}
     * （Ticker 线程 = 池 owner），切片完归还 —— 这是池化的释放点（buffer 逃逸出
     * rasterize，须由本方法 finally 归还）。</p>
     */
    @Override
    public int renderFrame(WallRepo.Wall wall, ProjectState frame,
                           AnimationTicker.FrameDiff diff, boolean force) {
        if (wall == null || frame == null) return 0;
        List<Integer> mapIds = wall.mapIds();
        if (mapIds == null || mapIds.isEmpty()) return 0;
        int widthMaps = frame.canvas().widthMaps();
        String wallId = wall.wallId();

        List<Player> viewers = findViewersFor(wall);
        if (viewers.isEmpty() && !force) {
            return -1;   // viewer-gated：跳帧不渲染（位置在 Ticker 侧按墙钟推进）
        }
        // 新观察者出现 → 本帧全量补发（unchanged map 对新 viewer 是缺帧）
        boolean forceFullPush = force;
        java.util.Set<java.util.UUID> viewerIds = new java.util.HashSet<>(viewers.size());
        for (Player p : viewers) viewerIds.add(p.getUniqueId());
        if (diff.lastViewerIds == null || !diff.lastViewerIds.containsAll(viewerIds)) {
            forceFullPush = true;
        }
        diff.lastViewerIds = viewerIds;

        int total = mapIds.size();
        if (diff.lastFrames == null || diff.lastFrames.length != total) {
            diff.lastFrames = new byte[total][];
            forceFullPush = true;
        }

        BufferedImage img;
        try {
            img = compositor.rasterize(frame, wallId);
        } catch (Exception e) {
            log.warning("CanvasProjector.renderFrame: rasterize failed wallId=" + wallId
                    + " err=" + e.getMessage());
            return 0;
        }
        int updated = 0;
        try {
            for (int i = 0; i < total; i++) {
                int mapId = mapIds.get(i);
                try {
                    byte[] pixels = compositor.toPaletteSlice(img, i, widthMaps);
                    boolean changed = diff.lastFrames[i] == null
                            || !java.util.Arrays.equals(diff.lastFrames[i], pixels);
                    if (changed) {
                        canvasRenderer.update(mapId, pixels);
                        diff.lastFrames[i] = pixels;
                        updated++;
                    }
                    if (changed || forceFullPush) {
                        // forceFullPush 且本帧未变（changed=false）时 diff.lastFrames[i] 可能尚无
                        // 基准 → 用本帧 pixels 兜底，杜绝 pushToViewers(...,null) 让新观察者缺帧
                        // （docs/architecture.md §5.5）。
                        if (!changed && diff.lastFrames[i] == null) diff.lastFrames[i] = pixels;
                        pushToViewers(viewers, mapId, diff.lastFrames[i]);
                    }
                } catch (Exception e) {
                    log.warning("CanvasProjector.renderFrame: quantize failed mapIndex=" + i
                            + " mapId=" + mapId + " wallId=" + wallId
                            + " err=" + e.getMessage());
                }
            }
        } finally {
            compositor.releaseToPool(img);
        }
        return updated;
    }
}
