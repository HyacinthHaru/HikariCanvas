package moe.hikari.canvas.canvasfile;

import moe.hikari.canvas.HikariCanvasConfig;
import moe.hikari.canvas.render.ProjectionThrottler;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.state.EditSession;
import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.Keyframe;
import moe.hikari.canvas.state.Layer;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.Timeline;
import moe.hikari.canvas.storage.AuditLog;
import moe.hikari.canvas.storage.WallRepo;
import moe.hikari.canvas.web.OpPushCallback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 0.8-A2 Task 12：{@code .canvas} 工程导入编排——把前两批的零件串成完整导入链。
 *
 * <p>流程（contract {@code docs/import-export.md §3.2}）：</p>
 * <ol>
 *   <li>{@link CanvasArchive#unpack} 流式安全解包（三闸 + 路径校验 + 白名单）；</li>
 *   <li>{@link CanvasManifest#parse} 解析 manifest 并校验 spec ≤ {@link #CANVAS_SPEC_MAX}；</li>
 *   <li>{@link ProjectMaterializer#materialize} 把 untrusted {@code project.json} 物化为校验过的
 *       {@link ProjectState}，并与会话墙尺寸做匹配；</li>
 *   <li>{@link AssetIngest#ingestAll} 摄入 {@code assets/*.png}（magic + 隔离解码 + 配额 + 落 hash）；</li>
 *   <li>孤儿关键帧轨丢弃：引用不存在 elementId 的 track 剔除 + {@code orphan-track-dropped} warn；</li>
 *   <li>{@code session.editSession().replaceProject} 整体替换（保留多层 / 时间轴，返回 OkSnapshot）；</li>
 *   <li>{@code push.pushSnapshot} 全量广播下行（照 {@code EditOpDispatcher} 的 OkSnapshot 分支）；</li>
 *   <li>{@code throttler.submit(sessionId, oks.dirty())} 游戏内地图全画布重绘（照
 *       {@code EditOpDispatcher} OkSnapshot 分支的 {@code throttler.submit}，否则玩家在游戏里看不到
 *       新内容、要等墙重载）；</li>
 *   <li>{@code wallRepo.updateState} 持久化（照 {@code SessionManager#persistWall} 的 DB 写）；</li>
 *   <li>{@code auditLog.record("PROJECT_IMPORT", ...)} 留痕。</li>
 * </ol>
 *
 * <p><b>scripts.json 处理不在本批</b>（留 A4 Task 18），编排里先不接脚本。</p>
 *
 * <p>装配（{@code WebServer} 构造时 new，依赖均已就位）：{@link OpPushCallback} 用 WebServer 内部
 * 的 push（同 dispatcher 共享）；{@link WallRepo} 持久化；{@link AssetIngest} 由 bootstrap 注入。
 * <b>不依赖 SessionManager</b>——持久化走 {@code wallRepo.updateState}（{@code persistWall} 的核心
 * DB 写），保持测试可裸装配（temp DB 即可，无须 MapPool / WallResolver / Bukkit）。
 *
 * <p>{@link ProjectionThrottler} 可空（best-effort，与 {@code auditLog} 同范式）：装配时由
 * {@code WebServer} 注入与 {@code EditOpDispatcher} <b>同一实例</b>，导入成功即排队游戏内全画布重绘；
 * 测试可传 {@code null} 裸跑（投影是副作用，不影响 replaceProject / pushSnapshot / 持久化主链）。</p>
 *
 * <p><b>已知限制（投影接了、动画没接）：</b>throttler 只投静态像素帧；{@code persistWall} 的
 * Ticker 自动播刷新与触发器 rebuild <b>不</b>在导入路径触发。故导入工程里的时间轴动画不会自动起播——
 * 需手动播一次，或随墙下次加载 / 会话回收时自然起播。</p>
 */
public final class ProjectImporter {

    /**
     * 当前插件支持的 {@code .canvas} 工程格式上限（与前端 {@code CANVAS_SPEC = 1} 对应）。
     * manifest.spec 高于此值 → {@code IMPORT_SPEC_UNSUPPORTED}（提示升级插件）。
     */
    public static final int CANVAS_SPEC_MAX = 1;

    private static final long MB = 1024L * 1024L;

    private final HikariCanvasConfig.ImportConfig importConfig;
    private final AssetIngest assetIngest;
    private final OpPushCallback push;
    private final WallRepo wallRepo;
    /** 可空：留痕 best-effort（与 dispatcher 的 auditLog 可空范式一致）。 */
    private final AuditLog auditLog;
    /**
     * 可空：游戏内地图投影节流器（best-effort，与 {@code auditLog} 同范式）。装配时由
     * {@code WebServer} 注入与 {@code EditOpDispatcher} 同一实例，导入成功即排队全画布重绘。
     * {@code null} 时跳过投影（前端编辑器仍经 pushSnapshot 更新，但游戏里要等墙重载才见新内容）。
     */
    private final ProjectionThrottler throttler;

    public ProjectImporter(HikariCanvasConfig.ImportConfig importConfig,
                           AssetIngest assetIngest,
                           OpPushCallback push,
                           WallRepo wallRepo,
                           AuditLog auditLog,
                           ProjectionThrottler throttler) {
        this.importConfig = importConfig;
        this.assetIngest = assetIngest;
        this.push = push;
        this.wallRepo = wallRepo;
        this.auditLog = auditLog;
        this.throttler = throttler;
    }

    /**
     * 把一个 {@code .canvas} 字节包导入到目标会话，整体替换其工程。
     *
     * @param session     目标会话（必须已绑 wall + 持活 {@code editSession}）
     * @param canvasBytes 上传的 {@code .canvas}（zip）原始字节
     * @param uploader    导入者 uuid（assets 落盘 owner + audit 主体）
     * @return 导入结果（含非致命 warnings）
     * @throws CanvasImportException 致命失败（带稳定 {@code IMPORT_*} 码，端点据此映射 HTTP status）
     */
    public ImportResult importInto(Session session, byte[] canvasBytes, UUID uploader)
            throws CanvasImportException {
        // 1) 解包（用 importConfig 的 MB 闸换算成 byte）
        CanvasArchive.Limits limits = new CanvasArchive.Limits(
                importConfig.canvasMaxMb() * MB,
                importConfig.canvasMaxEntryMb() * MB,
                importConfig.canvasMaxTotalMb() * MB);
        Map<String, byte[]> entries = CanvasArchive.unpack(canvasBytes, limits);

        // 2) manifest 解析 + spec 兼容校验
        CanvasManifest manifest = CanvasManifest.parse(entries.get("manifest.json"), CANVAS_SPEC_MAX);

        // 3) project.json 物化 + 元素校验 + 尺寸匹配（喂会话当前墙尺寸）
        ProjectState.Canvas sessionCanvas = session.projectState().canvas();
        ProjectState imported = ProjectMaterializer.materialize(
                entries.get("project.json"),
                sessionCanvas.widthMaps(), sessionCanvas.heightMaps());

        List<ImportWarning> warnings = new ArrayList<>();

        // 4) assets/*.png 摄入（落盘；缺/拒静默跳过，差额生成 asset-quota warning）
        int requestedAssets = countRequestedAssetPngs(entries);
        Set<String> storedHashes = assetIngest.ingestAll(entries, uploader);
        int skipped = requestedAssets - storedHashes.size();
        if (skipped > 0) {
            warnings.add(new ImportWarning("asset-quota",
                    skipped + " image(s) skipped (quota full or undecodable)"));
        }

        // 5) 孤儿关键帧轨丢弃 + warn（timelines 为 null/空则跳过）
        imported = stripOrphanTracksAndCollect(imported, warnings);

        // 6) 整体替换会话工程（保留多层 / 时间轴；OkSnapshot）
        EditSession.OpResult result = session.editSession().replaceProject(imported);

        // 7) 全量快照广播（照 EditOpDispatcher 的 OkSnapshot 分支：从 session.projectState() 读）
        push.pushSnapshot(session.id(), session.projectState());

        // 7b) 游戏内地图全画布重绘（照 EditOpDispatcher OkSnapshot 分支的 throttler.submit；
        //     否则玩家在游戏里看不到新内容，要等墙重载）。throttler 可空 → best-effort。
        if (throttler != null
                && result instanceof EditSession.OpResult.OkSnapshot oks
                && oks.dirty() != null) {
            throttler.submit(session.id(), oks.dirty());
        }

        // 8) 持久化（照 SessionManager#persistWall 的 DB 写）
        if (session.wallId() != null) {
            wallRepo.updateState(session.wallId(), session.projectState());
        }

        // 9) audit 留痕
        if (auditLog != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("wall_id", session.wallId());
            details.put("spec", manifest.spec());
            details.put("elements", countElements(session.projectState()));
            details.put("assets", storedHashes.size());
            auditLog.record("PROJECT_IMPORT",
                    uploader == null ? null : uploader.toString(),
                    session.playerName(), session.id(), null, details);
        }

        return new ImportResult(warnings);
    }

    /**
     * 扫导入工程每条 timeline 的 tracks（key = elementId），对照所有 layer 的 elementId 集合，
     * 丢弃引用不存在元素的 track 并为每条加一条 {@code orphan-track-dropped} warning。
     *
     * <p>{@code timelines} 为 null / 空时原样返回。仅当确有孤儿轨需要剔除时才重建
     * {@link ProjectState}（避免无谓拷贝）。重建保留 version / canvas / layers / activeLayerId /
     * activeTimelineId / tweenFps，仅替换 timelines 列表。</p>
     */
    static ProjectState stripOrphanTracksAndCollect(ProjectState imported,
                                                    List<ImportWarning> warnings) {
        List<Timeline> timelines = imported.timelines();
        if (timelines == null || timelines.isEmpty()) {
            return imported;
        }

        // 收集所有 layer 的 elementId
        Set<String> liveElementIds = new HashSet<>();
        for (Layer layer : imported.layers()) {
            for (Element el : layer.elements()) {
                if (el.id() != null) liveElementIds.add(el.id());
            }
        }

        boolean changed = false;
        List<Timeline> cleaned = new ArrayList<>(timelines.size());
        for (Timeline tl : timelines) {
            Map<String, List<Keyframe>> tracks = tl.tracks();
            // 找孤儿 key
            Map<String, List<Keyframe>> kept = new LinkedHashMap<>();
            boolean tlChanged = false;
            for (Map.Entry<String, List<Keyframe>> e : tracks.entrySet()) {
                String elementId = e.getKey();
                if (liveElementIds.contains(elementId)) {
                    kept.put(elementId, e.getValue());
                } else {
                    warnings.add(new ImportWarning("orphan-track-dropped", elementId));
                    tlChanged = true;
                }
            }
            if (tlChanged) {
                changed = true;
                cleaned.add(tl.withTracks(kept));
            } else {
                cleaned.add(tl);
            }
        }

        if (!changed) {
            return imported;
        }
        // 重建 ProjectState，仅替换 timelines（其余字段照搬）
        return new ProjectState(
                imported.version(),
                imported.canvas(),
                null,                       // v1Elements：走 layers 路径，不用
                imported.layers(),
                imported.activeLayerId(),
                null,                       // history：导入不带历史
                cleaned,
                imported.activeTimelineId(),
                imported.tweenFps());
    }

    /** 解包条目里属于 {@code assets/<file>.png} 的真实图片张数（与 AssetIngest 的 isAssetPng 同口径）。 */
    private static int countRequestedAssetPngs(Map<String, byte[]> entries) {
        int n = 0;
        for (String name : entries.keySet()) {
            if (name != null
                    && name.startsWith("assets/")
                    && name.endsWith(".png")
                    && name.length() > "assets/".length() + ".png".length()) {
                n++;
            }
        }
        return n;
    }

    /** 工程全 layer 元素总数（audit details 用）。 */
    private static int countElements(ProjectState state) {
        int n = 0;
        for (Layer layer : state.layers()) {
            n += layer.elements().size();
        }
        return n;
    }
}
