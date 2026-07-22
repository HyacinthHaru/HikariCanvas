package ac.haru.hikaricanvas.canvasfile;

import ac.haru.hikaricanvas.HikariCanvasConfig;
import ac.haru.hikaricanvas.render.ProjectionThrottler;
import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.state.EditSession;
import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.Keyframe;
import ac.haru.hikaricanvas.state.Layer;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.Timeline;
import ac.haru.hikaricanvas.storage.AuditLog;
import ac.haru.hikaricanvas.storage.WallRepo;
import ac.haru.hikaricanvas.web.OpPushCallback;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code .canvas} 工程导入编排——把各零件串成完整导入链。
 *
 * <p>流程（contract {@code docs/import-export.md §3.2}）：</p>
 * <ol>
 *   <li>{@link CanvasArchive#unpack} 流式安全解包（三闸 + 路径校验 + 白名单）；</li>
 *   <li>{@link CanvasManifest#parse} 解析 manifest 并校验 spec ≤ {@link #CANVAS_SPEC_MAX}
 *       （{@code kind} 可为 {@code project} 或 {@code pack}）；</li>
 *   <li><b>（pack 前置段）</b>{@link #applySubstitution}：条目里有 {@code params.json} 时——解析声明、
 *       校验用户填值、在 {@code project.json} 文本层单遍替换 {@code ${param}}（{@link PackParamResolver}）；
 *       无 {@code params.json}（普通 project）时 no-op，字节原样穿透，与旧路径逐字节等价；</li>
 *   <li>{@link ProjectMaterializer#materialize} 把 untrusted {@code project.json} 物化为校验过的
 *       {@link ProjectState}，并与会话墙尺寸做匹配；</li>
 *   <li>{@link MissingResourceScanner#scan} 扫缺字体 / 缺 user 图标 / 缺 userglobal 变量
 *       （{@code missing-font} / {@code missing-icon} / {@code missing-variable} warn）；可空跳过；</li>
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
 * <p><b>build / propagate 拆分：</b>步骤 3~6.5 是「解析 + 物化 + 落会话工程」的 <b>build 段</b>
 * （{@link #buildFromEntries}）；步骤 7~9 是「广播 + 投影 + 落库 + 留痕」的 <b>propagate 段</b>
 * （{@link #propagate}）。{@link #importInto} 串完整两段；{@link #applyPack}（模板套用入口）只跑 build 段，
 * propagate 交给调用它的 WS dispatcher 自己的 push / throttler / persist 尾段——避免双推。两条入口
 * 在 manifest 解析后都先过 {@link #applySubstitution}：{@code importInto} 传空参数（pack 用 default 收敛、
 * project 无 params.json 直接 no-op），{@code applyPack} 传用户填值。</p>
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
    /**
     * scripts.json 导入器（wallId 重绑 + 重校验 + 落库）。可空 —— ScriptStore 未配置
     * 的装配（旧测试 / 无脚本子系统）传 null，此时工程包里的 scripts.json 被静默忽略
     * （工程本体照常导入）。
     */
    private final ScriptImporter scriptImporter;
    /** 可空：留痕 best-effort（与 dispatcher 的 auditLog 可空范式一致）。 */
    private final AuditLog auditLog;
    /**
     * 可空：游戏内地图投影节流器（best-effort，与 {@code auditLog} 同范式）。装配时由
     * {@code WebServer} 注入与 {@code EditOpDispatcher} 同一实例，导入成功即排队全画布重绘。
     * {@code null} 时跳过投影（前端编辑器仍经 pushSnapshot 更新，但游戏里要等墙重载才见新内容）。
     */
    private final ProjectionThrottler throttler;
    /**
     * 导入时扫缺字体 / 图标 / 全局变量并产出 {@code missing-*} warning
     * （{@code docs/import-export.md §3.2 step 8}）。可空（best-effort，与 {@code auditLog} 同范式）：
     * 装配时由 {@code WebServer} 注入（内部各 registry 也各自可空降级）；{@code null} 时跳过缺资源扫描
     * （工程照常导入，仅不提示哪些资源缺）。
     */
    private final MissingResourceScanner missingResourceScanner;

    public ProjectImporter(HikariCanvasConfig.ImportConfig importConfig,
                           AssetIngest assetIngest,
                           OpPushCallback push,
                           WallRepo wallRepo,
                           ScriptImporter scriptImporter,
                           AuditLog auditLog,
                           ProjectionThrottler throttler,
                           MissingResourceScanner missingResourceScanner) {
        this.importConfig = importConfig;
        this.assetIngest = assetIngest;
        this.push = push;
        this.wallRepo = wallRepo;
        this.scriptImporter = scriptImporter;
        this.auditLog = auditLog;
        this.throttler = throttler;
        this.missingResourceScanner = missingResourceScanner;
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

        // 2) manifest 解析 + spec 兼容校验（kind = project / pack）
        CanvasManifest manifest = CanvasManifest.parse(entries.get("manifest.json"), CANVAS_SPEC_MAX);

        // 2.5) pack 前置段：空参数——pack 用 default 收敛当工程导入，project 无 params.json
        //      直接 no-op（entries 字节原样穿透，与重构前逐字节等价）。
        applySubstitution(entries, Map.of());

        // 3~6.5) build 段（物化 → 缺资源扫描 → assets → 孤儿轨 → replaceProject → scripts）
        Built built = buildFromEntries(session, entries, manifest, uploader);

        // 7~9) propagate 段（广播 → 投影 → 落库 → 留痕）
        propagate(session, built, manifest, uploader);

        return new ImportResult(built.warnings());
    }

    /**
     * 把一个 {@code kind="pack"} 模板包用给定参数套用到目标会话，整体替换其工程。
     *
     * <p>与 {@link #importInto} 同走 unpack → manifest → {@link #applySubstitution} →
     * {@link #buildFromEntries} 的 build 段，但 <b>不跑 propagate 段</b>：调用方
     * （{@code template.apply} 的 WS dispatcher）拿到 {@link ApplyResult#result()}（{@code OkSnapshot}）后，
     * 走自己的 push / {@code throttler.submit} / persist 尾段（与 undo/redo 等 OkSnapshot 类 op 一致）。
     * 若此处再 propagate 会与之双推。</p>
     *
     * @param session    目标会话（必须已绑 wall + 持活 {@code editSession}）
     * @param packBytes  模板包（{@code .canvas} zip）原始字节
     * @param userParams 用户填的参数值（id→value）；缺的参数回退声明的 {@code default}，可空当空 map
     * @param uploader   套用者 uuid（assets 落盘 owner）
     * @return build 段结果（{@code OkSnapshot} + 非致命 warnings）；propagate 由调用方接
     * @throws CanvasImportException 致命失败（带稳定 {@code IMPORT_*} 码，参数校验失败为 {@code IMPORT_INVALID_PARAM}）
     */
    public ApplyResult applyPack(Session session, byte[] packBytes,
                                 Map<String, Object> userParams, UUID uploader)
            throws CanvasImportException {
        CanvasArchive.Limits limits = new CanvasArchive.Limits(
                importConfig.canvasMaxMb() * MB,
                importConfig.canvasMaxEntryMb() * MB,
                importConfig.canvasMaxTotalMb() * MB);
        Map<String, byte[]> entries = CanvasArchive.unpack(packBytes, limits);
        CanvasManifest manifest = CanvasManifest.parse(entries.get("manifest.json"), CANVAS_SPEC_MAX);
        applySubstitution(entries, userParams);
        Built built = buildFromEntries(session, entries, manifest, uploader);
        return new ApplyResult(built.result(), built.warnings());
    }

    /**
     * pack 参数前置段：条目里有 {@code params.json} 时，解析声明 + 校验用户填值 + 在 {@code project.json}
     * 文本层单遍替换 {@code ${param}}，把替换结果写回 {@code entries}。
     *
     * <p>无 {@code params.json}（普通 project，或无参数 pack）→ 直接 return，{@code entries} 一字节不动——
     * 保证既有 project 导入路径与重构前逐字节等价（这是既有 9 条用例不受影响的根据）。</p>
     */
    private void applySubstitution(Map<String, byte[]> entries, Map<String, Object> userParams)
            throws CanvasImportException {
        byte[] pj = entries.get("params.json");
        if (pj == null) return;
        List<PackParamResolver.ParamDef> decls = PackParamResolver.parse(pj);
        Map<String, Object> values = PackParamResolver.resolve(decls, userParams);
        String projectJson = new String(entries.get("project.json"), StandardCharsets.UTF_8);
        String substituted = PackParamResolver.substitute(projectJson, values);
        entries.put("project.json", substituted.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * build 段（步骤 3~6.5）：materialize → 缺资源扫描 → asset 摄入 → 孤儿轨丢弃 → replaceProject →
     * scripts 导入。逻辑与重构前 {@link #importInto} 内联段完全一致，仅抽成方法供 {@code importInto} 与
     * {@link #applyPack} 共用。
     */
    private Built buildFromEntries(Session session, Map<String, byte[]> entries,
                                   CanvasManifest manifest, UUID uploader)
            throws CanvasImportException {
        // 3) project.json 物化 + 元素校验 + 尺寸匹配（喂会话当前墙尺寸）
        ProjectState.Canvas sessionCanvas = session.projectState().canvas();
        ProjectState imported = ProjectMaterializer.materialize(
                entries.get("project.json"),
                sessionCanvas.widthMaps(), sessionCanvas.heightMaps());

        List<ImportWarning> warnings = new ArrayList<>();

        // 3.5) 缺资源扫描（docs/import-export.md §3.2 step 8）——materialize 之后、
        //      replaceProject 之前。引用本服缺字体 / 缺 user 图标 / 缺 userglobal 变量 → missing-* 提示。
        //      scanner 可空（best-effort）→ 跳过；内部各 registry 亦各自可空降级。导入照常完成。
        if (missingResourceScanner != null) {
            warnings.addAll(missingResourceScanner.scan(imported));
        }

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

        // 6.5) scripts.json 导入（wallId 重绑到目标墙 + 重校验 + 落 wall_scripts）。
        //      scriptImporter 可空（ScriptStore 未配的装配）→ 跳过脚本，工程本体照常导入。
        //      脚本是 wall-scoped 状态、与 ProjectState 解耦，不进 snapshot；这里只落库。
        if (scriptImporter != null && session.wallId() != null
                && entries.containsKey("scripts.json")) {
            warnings.addAll(scriptImporter.importScripts(
                    entries.get("scripts.json"), session.wallId()));
        }

        return new Built(result, warnings, storedHashes.size());
    }

    /**
     * propagate 段（步骤 7~9）：全量快照广播 → 游戏内地图全画布重绘 → 持久化 → audit 留痕。
     * 逻辑与重构前 {@link #importInto} 内联段完全一致。仅 {@code importInto} 调用；{@link #applyPack}
     * 不调（其调用方自带 push / throttler / persist 尾段）。
     */
    private void propagate(Session session, Built built, CanvasManifest manifest, UUID uploader) {
        // 7) 全量快照广播（照 EditOpDispatcher 的 OkSnapshot 分支：从 session.projectState() 读）
        push.pushSnapshot(session.id(), session.projectState());

        // 7b) 游戏内地图全画布重绘（照 EditOpDispatcher OkSnapshot 分支的 throttler.submit；
        //     否则玩家在游戏里看不到新内容，要等墙重载）。throttler 可空 → best-effort。
        if (throttler != null
                && built.result() instanceof EditSession.OpResult.OkSnapshot oks
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
            details.put("assets", built.assetCount());
            auditLog.record("PROJECT_IMPORT",
                    uploader == null ? null : uploader.toString(),
                    session.playerName(), session.id(), null, details);
        }
    }

    /**
     * build 段产物：会话工程替换结果（{@code OkSnapshot}）+ 累计 warnings + 落盘 asset 张数
     * （audit details 用）。propagate 段与 {@link #applyPack} 调用方据此收尾。
     */
    private record Built(EditSession.OpResult result, List<ImportWarning> warnings, int assetCount) {
    }

    /**
     * {@link #applyPack} 的返回：build 段结果 + 非致命 warnings。调用方（{@code template.apply} dispatcher）
     * 拿 {@code result}（{@code OkSnapshot}）走自己的 push / throttler / persist 尾段。
     */
    public record ApplyResult(EditSession.OpResult result, List<ImportWarning> warnings) {
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
