package ac.haru.hikaricanvas.web;

import io.javalin.websocket.WsMessageContext;
import ac.haru.hikaricanvas.render.ProjectionThrottler;
import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionManager;
import ac.haru.hikaricanvas.session.SessionRateLimiter;
import ac.haru.hikaricanvas.state.EditSession;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.template.TemplateEntry;
import ac.haru.hikaricanvas.template.TemplateRegistry;

import java.util.List;
import java.util.Map;

import static ac.haru.hikaricanvas.web.WebHelpers.asPayloadMap;
import static ac.haru.hikaricanvas.web.WebHelpers.intOrNull;
import static ac.haru.hikaricanvas.web.WebHelpers.mapOrEmpty;
import static ac.haru.hikaricanvas.web.WebHelpers.stringOrNull;

/**
 * 编辑 op 分发：{@code element.* / layer.* / canvas.* / undo / redo / history.mark /
 * template.apply / timeline.* / keyframe.*}（共 7 op，§5.12 / §5.13）。
 * <p>从 {@link WebServer} 抽出。每个成功 op 走 ack + pushPatch/pushSnapshot + throttler 投影 + 持久化的标准路径。</p>
 *
 * <p><b>播放控制：</b> {@code timeline.play / pause / seek} 三个 op 在此特判（{@link
 * #handleTimelinePlayback}）。它们<b>不走 OpResult 流</b>——不 mutate {@link ProjectState}、不进
 * history、无 patch、无 throttler 投影，只在 {@link ac.haru.hikaricanvas.render.AnimationTicker} 上启停
 * / 定位后端产帧（protocol.md §5.12）。权限与编辑 op 同权（session 活跃即可，不查 owner，docs/timeline.md §5.2）。</p>
 */
final class EditOpDispatcher {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(EditOpDispatcher.class.getName());

    private final SessionManager sessionManager;
    private final ProjectionThrottler throttler;
    private final SessionRateLimiter rateLimiter;
    private final TemplateRegistry templateRegistry;
    private final ac.haru.hikaricanvas.storage.WallRepo wallRepo;
    private final OpPushCallback push;
    /** 可空——给 template.apply 跨用户拒绝路径写 audit。 */
    private final ac.haru.hikaricanvas.storage.AuditLog auditLog;
    /** 主线程权限解析用宿主插件；可为 null（测试装配走直接调用）。 */
    private final org.bukkit.plugin.Plugin plugin;

    /**
     * 时间轴播放控制引擎（timeline.play/pause/seek 三 op 的目标）。由装配层经
     * {@code WebServer.setAnimationTicker} 注入；null = 三 op 返 {@code INTERNAL_ERROR}
     * （引擎未装配）。volatile：注入在 WebServer 构造之后。
     */
    private volatile ac.haru.hikaricanvas.render.AnimationTicker animationTicker;

    /** 装配 seam（见 {@link #animationTicker}）。 */
    void setAnimationTicker(ac.haru.hikaricanvas.render.AnimationTicker ticker) {
        this.animationTicker = ticker;
    }

    /** timeline.play/pause/seek case 用。 */
    ac.haru.hikaricanvas.render.AnimationTicker animationTicker() {
        return animationTicker;
    }

    /**
     * {@code .canvas} pack 套用引擎（{@code template.apply} 命中 pack 条目时的目标）。由装配层经
     * {@code WebServer} 构造尾段注入（{@link #setProjectImporter}）；null = pack 条目返
     * {@code INTERNAL_ERROR}（导入子系统未装配——无 assetIngest / importConfig 的降级装配，
     * best-effort，同 {@link #animationTicker} 未装配时的播放控制降级）。volatile：注入在 WebServer 构造之后。
     */
    private volatile ac.haru.hikaricanvas.canvasfile.ProjectImporter projectImporter;

    /** 装配 seam（见 {@link #projectImporter}），照 {@link #setAnimationTicker} 范式。 */
    void setProjectImporter(ac.haru.hikaricanvas.canvasfile.ProjectImporter importer) {
        this.projectImporter = importer;
    }

    EditOpDispatcher(SessionManager sessionManager,
                     ProjectionThrottler throttler,
                     SessionRateLimiter rateLimiter,
                     TemplateRegistry templateRegistry,
                     ac.haru.hikaricanvas.storage.WallRepo wallRepo,
                     OpPushCallback push) {
        this(sessionManager, throttler, rateLimiter, templateRegistry, wallRepo, push, null, null);
    }

    EditOpDispatcher(SessionManager sessionManager,
                     ProjectionThrottler throttler,
                     SessionRateLimiter rateLimiter,
                     TemplateRegistry templateRegistry,
                     ac.haru.hikaricanvas.storage.WallRepo wallRepo,
                     OpPushCallback push,
                     ac.haru.hikaricanvas.storage.AuditLog auditLog) {
        this(sessionManager, throttler, rateLimiter, templateRegistry, wallRepo, push, auditLog, null);
    }

    EditOpDispatcher(SessionManager sessionManager,
                     ProjectionThrottler throttler,
                     SessionRateLimiter rateLimiter,
                     TemplateRegistry templateRegistry,
                     ac.haru.hikaricanvas.storage.WallRepo wallRepo,
                     OpPushCallback push,
                     ac.haru.hikaricanvas.storage.AuditLog auditLog,
                     org.bukkit.plugin.Plugin plugin) {
        this.sessionManager = sessionManager;
        this.throttler = throttler;
        this.rateLimiter = rateLimiter;
        this.templateRegistry = templateRegistry;
        this.wallRepo = wallRepo;
        this.push = push;
        this.auditLog = auditLog;
        this.plugin = plugin;
    }

    void dispatch(WsMessageContext ctx, Envelope in, String sessionId) {
        // 输入限流：超 40 msg/2s（≈ 20 msg/s）返 RATE_LIMITED，不进 EditSession
        if (!rateLimiter.allow(sessionId)) {
            ctx.send(Envelope.error(in.id(), "RATE_LIMITED",
                    "input rate exceeded; slow down"));
            return;
        }
        Session s = sessionManager.byId(sessionId);
        if (s == null || s.editSession() == null) {
            ctx.send(Envelope.error(in.id(), "SESSION_CLOSED", "no active edit session"));
            return;
        }
        EditSession es = s.editSession();

        Map<String, Object> payload;
        try {
            payload = asPayloadMap(in.payload());
        } catch (IllegalArgumentException iae) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", iae.getMessage()));
            return;
        }

        // timeline.play/pause/seek 三个播放控制 op 在此特判（限流 + session 校验之后）。
        // 它们不走下方 OpResult switch——不 mutate ProjectState、不进 history、无 patch、无投影，
        // 只在 AnimationTicker 上启停 / 定位后端产帧（protocol.md §5.12）。直接发响应后 return。
        switch (in.op()) {
            case "timeline.play", "timeline.pause", "timeline.seek" -> {
                ctx.send(handleTimelinePlayback(in.op(), in.id(), payload,
                        s.wallId(), s.playerUuid(), s.playerName(), sessionId));
                return;
            }
            default -> { /* 落入下方编辑 op 流 */ }
        }

        // 整个 op 解析 switch 包 try/catch，与 BrushOpDispatcher.dispatch 形态对齐。
        // mapOrEmpty（props/patch/params 类型错）等会抛 IllegalArgumentException，
        // 不包则异常逃逸 → Javalin onError 只 log，前端拿不到 INVALID_PAYLOAD 而是 5s ack_timeout。
        EditSession.OpResult result;
        try {
            result = switch (in.op()) {
            case "element.add" -> {
                String type = stringOrNull(payload.get("type"));
                @SuppressWarnings("unchecked")
                Map<String, Object> props = (Map<String, Object>) mapOrEmpty(payload.get("props"));
                String after = stringOrNull(payload.get("after"));
                String layerId = stringOrNull(payload.get("layerId"));
                yield es.addElement(type, props, after, layerId);
            }
            case "element.update" -> {
                String eid = stringOrNull(payload.get("elementId"));
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) mapOrEmpty(payload.get("patch"));
                yield es.updateElement(eid, p);
            }
            case "element.delete" -> es.deleteElement(stringOrNull(payload.get("elementId")));
            case "element.reorder" -> {
                String eid = stringOrNull(payload.get("elementId"));
                Object idxObj = payload.get("index");
                if (!(idxObj instanceof Number n)) {
                    yield new EditSession.OpResult.Error("INVALID_PAYLOAD", "index must be number");
                }
                yield es.reorderElement(eid, n.intValue());
            }
            case "element.transform" -> {
                String eid = stringOrNull(payload.get("elementId"));
                yield es.transformElement(eid,
                        intOrNull(payload.get("x")),
                        intOrNull(payload.get("y")),
                        intOrNull(payload.get("w")),
                        intOrNull(payload.get("h")),
                        intOrNull(payload.get("rotation")));
            }
            case "element.move-to-layer" -> {
                String eid = stringOrNull(payload.get("elementId"));
                String target = stringOrNull(payload.get("targetLayerId"));
                Integer idx = intOrNull(payload.get("index"));
                yield es.moveElementToLayer(eid, target, idx);
            }
            // ---- layer.* op 族 ----
            case "layer.create" -> {
                String name = stringOrNull(payload.get("name"));
                String afterId = stringOrNull(payload.get("afterLayerId"));
                yield es.createLayer(name, afterId);
            }
            case "layer.delete" -> es.deleteLayer(stringOrNull(payload.get("layerId")));
            case "layer.update" -> {
                String lid = stringOrNull(payload.get("layerId"));
                @SuppressWarnings("unchecked")
                Map<String, Object> lp = (Map<String, Object>) mapOrEmpty(payload.get("patch"));
                yield es.updateLayer(lid, lp);
            }
            case "layer.reorder" -> {
                String lid = stringOrNull(payload.get("layerId"));
                Object idxObj = payload.get("index");
                if (!(idxObj instanceof Number n)) {
                    yield new EditSession.OpResult.Error("INVALID_PAYLOAD", "index must be number");
                }
                yield es.reorderLayer(lid, n.intValue());
            }
            case "layer.duplicate" -> es.duplicateLayer(stringOrNull(payload.get("layerId")));
            case "layer.set-active" -> es.setActiveLayer(stringOrNull(payload.get("layerId")));
            // ---- canvas.* ----
            case "canvas.resize" -> {
                Object wObj = payload.get("widthMaps");
                Object hObj = payload.get("heightMaps");
                if (!(wObj instanceof Number wn) || !(hObj instanceof Number hn)) {
                    yield new EditSession.OpResult.Error(
                            "INVALID_PAYLOAD", "widthMaps/heightMaps must be numbers");
                }
                yield es.resizeCanvas(wn.intValue(), hn.intValue());
            }
            case "canvas.background" -> {
                // 优先看 fill（Fill 对象，solid/linear/radial），
                // 兼容老 color（hex 字符串）。两者皆缺 → INVALID_PAYLOAD。
                Object fillRaw = payload.get("fill");
                if (fillRaw != null) {
                    try {
                        ac.haru.hikaricanvas.state.Fill fill =
                                ac.haru.hikaricanvas.state.ElementValidator.parseFillNullable(fillRaw);
                        yield es.setBackground(fill);
                    } catch (ac.haru.hikaricanvas.state.ValidationException ve) {
                        yield new EditSession.OpResult.Error(ve.code, ve.getMessage());
                    }
                }
                yield es.setBackground(stringOrNull(payload.get("color")));
            }
            case "canvas.tweenFps" -> handleCanvasTweenFps(payload, es);
            case "canvas.grid" -> {
                Object sz = payload.get("size");
                if (sz != null && !(sz instanceof Number)) {
                    yield new EditSession.OpResult.Error("INVALID_PAYLOAD",
                            "size must be number or null");
                }
                yield es.setGridSize(sz == null ? null : ((Number) sz).intValue());
            }
            case "canvas.guides.set" -> {
                Object gs = payload.get("guides");
                if (gs != null && !(gs instanceof List<?>)) {
                    yield new EditSession.OpResult.Error("INVALID_PAYLOAD",
                            "guides must be array");
                }
                yield es.setGuides((List<?>) gs);
            }
            // ---- timeline.* op 族（protocol.md §5.12）----
            case "timeline.create" -> {
                String name = stringOrNull(payload.get("name"));
                Integer durationMs = intOrNull(payload.get("durationMs"));
                Integer fps = intOrNull(payload.get("fps"));
                String loopMode = stringOrNull(payload.get("loopMode"));
                Object triggerRaw = payload.get("trigger");
                @SuppressWarnings("unchecked")
                Map<String, Object> trigger = (triggerRaw == null) ? null
                        : (Map<String, Object>) mapOrEmpty(triggerRaw);
                yield es.createTimeline(name, durationMs, fps, loopMode, trigger);
            }
            case "timeline.update" -> {
                String tid = stringOrNull(payload.get("timelineId"));
                @SuppressWarnings("unchecked")
                Map<String, Object> tp = (Map<String, Object>) mapOrEmpty(payload.get("patch"));
                yield es.updateTimeline(tid, tp);
            }
            case "timeline.delete" -> es.deleteTimeline(stringOrNull(payload.get("timelineId")));
            // ---- keyframe.* op 族（protocol.md §5.13）----
            case "keyframe.add" -> {
                String tid = stringOrNull(payload.get("timelineId"));
                String eid = stringOrNull(payload.get("elementId"));
                String property = stringOrNull(payload.get("property"));
                Integer timeMs = intOrNull(payload.get("timeMs"));
                // value / easing 形态因 property 而异（number / string / object），原样透传
                Object value = payload.get("value");
                Object easing = payload.get("easing");
                // 可选 coalesceKey——整体帧批量加帧（拉就设 / + 按钮）共享一步撤销
                String ck = stringOrNull(payload.get("coalesceKey"));
                yield es.addKeyframe(tid, eid, property, timeMs, value, easing, ck);
            }
            case "keyframe.update" -> {
                String tid = stringOrNull(payload.get("timelineId"));
                String kid = stringOrNull(payload.get("keyframeId"));
                @SuppressWarnings("unchecked")
                Map<String, Object> kp = (Map<String, Object>) mapOrEmpty(payload.get("patch"));
                String ck = stringOrNull(payload.get("coalesceKey"));
                yield es.updateKeyframe(tid, kid, kp, ck);
            }
            case "keyframe.delete" -> {
                String tid = stringOrNull(payload.get("timelineId"));
                String kid = stringOrNull(payload.get("keyframeId"));
                yield es.deleteKeyframe(tid, kid);
            }
            case "keyframe.move" -> {
                String tid = stringOrNull(payload.get("timelineId"));
                String kid = stringOrNull(payload.get("keyframeId"));
                Integer timeMs = intOrNull(payload.get("timeMs"));
                String ck = stringOrNull(payload.get("coalesceKey"));
                yield es.moveKeyframe(tid, kid, timeMs, ck);
            }
            // ---- history / template ----
            case "undo" -> es.undo();
            case "redo" -> es.redo();
            case "history.mark" -> es.historyMark(stringOrNull(payload.get("label")));
            case "template.apply" -> {
                String tpl = stringOrNull(payload.get("templateId"));
                @SuppressWarnings("unchecked")
                Map<String, Object> tp = (Map<String, Object>) mapOrEmpty(payload.get("params"));
                // 跨用户隔离——查 caller 的 use-others bypass 权限
                // 主线程解析权限（Bukkit.getPlayer + hasPermission 主线程专用）；离线 / 超时返 false。
                java.util.UUID callerUuid = s.playerUuid();
                boolean hasBypass = MainThreadPerms.hasPermission(plugin, callerUuid, "canvas.template.use-others");
                yield applyTemplate(sessionId, tpl, tp, callerUuid, s.playerName(), hasBypass);
            }
            default -> new EditSession.OpResult.Error("INVALID_OP", "unreachable: " + in.op());
            };
        } catch (IllegalArgumentException iae) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", iae.getMessage()));
            return;
        } catch (RuntimeException re) {
            // 兜底：op 解析期任意运行期异常不应静默逃逸（前端会 5s ack_timeout）
            ctx.send(Envelope.error(in.id(), "INTERNAL_ERROR", "op processing failed"));
            return;
        }

        switch (result) {
            case EditSession.OpResult.Ok ok -> {
                // 1) ack 给 client id
                ctx.send(Envelope.of("ack", in.id(), Map.of("version", ok.patch().version())));
                // 2) pushPatch（s-N id）——空 ops 的 patch 跳过
                if (!ok.patch().ops().isEmpty()) {
                    push.pushPatch(sessionId, ok.patch());
                }
                // 3) 脏矩形投影经节流器（Bukkit async task 里调 projector.project）
                if (ok.dirty() != null) {
                    throttler.submit(sessionId, ok.dirty());
                }
                // 4) 草稿持久化
                sessionManager.persistWall(sessionId);
            }
            case EditSession.OpResult.OkSnapshot oks -> {
                // undo/redo/template.apply：结构跳变，下行 state.snapshot 全量；
                // 像素全画布重绘经 throttler 排队
                ctx.send(Envelope.of("ack", in.id(), Map.of("version", oks.version())));
                Session sn = sessionManager.byId(sessionId);
                if (sn != null && sn.projectState() != null) {
                    push.pushSnapshot(sessionId, sn.projectState());
                }
                if (oks.dirty() != null) {
                    throttler.submit(sessionId, oks.dirty());
                }
                sessionManager.persistWall(sessionId);
            }
            case EditSession.OpResult.OkBrushStart obs ->
                    // 非 brush op 不应返回 OkBrushStart；进到这里说明 EditSession 实现 bug
                    ctx.send(Envelope.error(in.id(), "UNEXPECTED",
                            "non-brush op returned brush start: strokeId=" + obs.strokeId()));
            case EditSession.OpResult.Error er ->
                    ctx.send(Envelope.error(in.id(), er.code(), er.message()));
        }
    }

    /**
     * {@code canvas.tweenFps} op 核心处理（protocol.md §5.x）。
     *
     * <p>package-private（非 private）以便 dispatcher 单测直接驱动 payload 解析 →
     * {@link EditSession#setTweenFps} 映射，绕开 {@code WsMessageContext}
     * （Javalin final 类，不可 mock）。WebServer switch 必须路由此 op 到
     * {@link #dispatch}，否则前端改帧率后端永远写不进去。</p>
     *
     * @param payload 已解析的 JSON payload（键 "fps"：Number 或 null）
     * @param es      当前 EditSession（从 session 取出）
     * @return 供 dispatch 流使用的 {@link EditSession.OpResult}
     */
    EditSession.OpResult handleCanvasTweenFps(Map<String, Object> payload, EditSession es) {
        // per-wall 补间帧率。fps=[1,60]；0/null → 清回默认（effectiveTweenFps=30）。
        Object fpsRaw = payload.get("fps");
        if (fpsRaw != null && !(fpsRaw instanceof Number)) {
            return new EditSession.OpResult.Error("INVALID_PAYLOAD",
                    "fps must be number or null");
        }
        return es.setTweenFps(fpsRaw == null ? null : ((Number) fpsRaw).intValue());
    }

    /**
     * {@code timeline.play / pause / seek} 三个播放控制 op 的核心处理（protocol.md §5.12）。
     *
     * <p>独立于 OpResult 流：不 mutate {@link ProjectState}、不进 history、不发 patch、不投影。
     * 返回待发送的响应信封（{@code ack} 空 map / {@code error}）——抽成纯函数（不直接碰
     * {@code ctx}）让 op→{@link ac.haru.hikaricanvas.render.AnimationTicker.Result}→错误码映射可单测。</p>
     *
     * <p>权限：与编辑 op 同权（session 活跃即可，不查 owner，docs/timeline.md §5.2）。
     * audit 记 {@code TIMELINE_PLAY / TIMELINE_PAUSE / TIMELINE_SEEK}（照 {@code WALL_LOCK} 形态，
     * auditLog 可空时跳过）。</p>
     *
     * <p>package-private（非 private）以便 dispatcher 单测直接驱动 op→Result→错误码映射，
     * 绕开 {@code WsMessageContext}（Javalin final 类，不可 mock）。</p>
     *
     * @param wallId 当前 session 绑定的 wall（{@code null} → {@code WALL_NOT_FOUND}）
     */
    Envelope handleTimelinePlayback(String op, String inId, Map<String, Object> payload,
                                    String wallId, java.util.UUID callerUuid,
                                    String callerName, String sessionId) {
        if (wallId == null) {
            return Envelope.error(inId, "WALL_NOT_FOUND", "session has no bound wall");
        }
        ac.haru.hikaricanvas.render.AnimationTicker ticker = animationTicker();
        if (ticker == null) {
            return Envelope.error(inId, "INTERNAL_ERROR", "animation engine not assembled");
        }
        String timelineId = stringOrNull(payload.get("timelineId"));   // 缺省 → ticker 取 activeTimelineId

        return switch (op) {
            case "timeline.play" -> {
                ac.haru.hikaricanvas.render.AnimationTicker.Result r = ticker.play(wallId, timelineId);
                recordTimelineAudit("TIMELINE_PLAY", callerUuid, callerName, sessionId,
                        wallId, timelineId, null);
                yield mapPlaybackResult(inId, r);
            }
            case "timeline.pause" -> {
                ticker.pause(wallId);   // 幂等（未注册 = no-op）
                recordTimelineAudit("TIMELINE_PAUSE", callerUuid, callerName, sessionId,
                        wallId, timelineId, null);
                yield Envelope.of("ack", inId, Map.of());
            }
            case "timeline.seek" -> {
                // 区分「字段缺省」与「字段存在但非法」——intOrNull 对
                // 越界 long / NaN 返 null，若直接当缺省会把非法输入静默折叠成 seek 到 0
                Object atMsRaw = payload.get("atMs");
                Integer atMs = intOrNull(atMsRaw);
                if (atMsRaw != null && atMs == null) {
                    yield Envelope.error(inId, "INVALID_PAYLOAD", "atMs out of range");
                }
                long at = atMs == null ? 0L : atMs;   // 缺省 = 0（protocol.md §5.12）
                if (at < 0L) {
                    yield Envelope.error(inId, "INVALID_PAYLOAD", "atMs must be >= 0");
                }
                ac.haru.hikaricanvas.render.AnimationTicker.Result r = ticker.seek(wallId, timelineId, at);
                recordTimelineAudit("TIMELINE_SEEK", callerUuid, callerName, sessionId,
                        wallId, timelineId, at);
                yield mapPlaybackResult(inId, r);
            }
            // 入口 switch 已圈定这三个 op；理论不可达
            default -> Envelope.error(inId, "INVALID_OP", "unreachable: " + op);
        };
    }

    /** play/seek 的 {@link ac.haru.hikaricanvas.render.AnimationTicker.Result} → 协议响应映射。 */
    private static Envelope mapPlaybackResult(String inId,
                                              ac.haru.hikaricanvas.render.AnimationTicker.Result r) {
        return switch (r) {
            case OK -> Envelope.of("ack", inId, Map.of());
            case WALL_NOT_FOUND -> Envelope.error(inId, "WALL_NOT_FOUND", "wall not found");
            case TIMELINE_NOT_FOUND ->
                    Envelope.error(inId, "TIMELINE_NOT_FOUND", "timeline not found");
        };
    }

    /**
     * 播放控制 audit 留痕（auditLog 可空时跳过）。{@code timelineId} 可空；{@code atMs} 仅 seek 传。
     * 形态照 {@code WallOpDispatcher} 的 {@code WALL_LOCK}：details 含 wall_id + timeline_id（可空）。
     */
    private void recordTimelineAudit(String event, java.util.UUID callerUuid, String callerName,
                                     String sessionId, String wallId, String timelineId, Long atMs) {
        if (auditLog == null) return;
        java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("wall_id", wallId);
        if (timelineId != null) details.put("timeline_id", timelineId);   // 缺省 = ticker 取 activeTimelineId
        if (atMs != null) details.put("at_ms", atMs);
        auditLog.record(event, callerUuid == null ? null : callerUuid.toString(),
                callerName, sessionId, null, details);
    }

    /**
     * template.apply 中枢：resolve registry（跨用户隔离）→ applyPackEntry（走 ProjectImporter.applyPack）。
     * 不在主线程跑（持有当前 WS thread），但只做内存/DB I/O，不碰 Bukkit world API。
     */
    private EditSession.OpResult applyTemplate(String sessionId,
                                               String templateId, Map<String, Object> params,
                                               java.util.UUID callerUuid, String callerName,
                                               boolean hasBypass) {
        if (templateId == null || templateId.isBlank()) {
            return new EditSession.OpResult.Error("INVALID_PAYLOAD", "templateId is required");
        }
        TemplateEntry entry;
        try {
            entry = templateRegistry.byIdForApply(templateId, callerUuid, hasBypass);
        } catch (ac.haru.hikaricanvas.template.ForbiddenTemplateException fte) {
            // 包装为 FORBIDDEN，不 echo 内部异常细节
            // 跨用户 template.apply 拒绝留痕（监控异常尝试用他人 template）
            if (auditLog != null) {
                java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
                details.put("operation", "template.apply");
                details.put("template_id", fte.templateId());
                auditLog.record("PERMISSION_DENIED",
                        callerUuid == null ? null : callerUuid.toString(),
                        callerName, sessionId, null, details);
            }
            return new EditSession.OpResult.Error("FORBIDDEN",
                    "template '" + fte.templateId() + "' not accessible");
        }
        if (entry == null) {
            return new EditSession.OpResult.Error("INVALID_PAYLOAD",
                    "unknown template: " + templateId);
        }
        // 模板 = .canvas pack：走 ProjectImporter.applyPack。返回的 OkSnapshot 交 dispatch 的
        // OkSnapshot 分支统一收尾（ack + pushSnapshot + throttler 投影 + persist），applyPackEntry 内不双推。
        return applyPackEntry(sessionId, templateId, entry, params, callerUuid);
    }

    /**
     * pack 条目（{@code .canvas}）套用：定位会话 → {@code ProjectImporter.applyPack}（build 段——unpack +
     * 参数校验 + {@code ${param}} 替换 + materialize + replaceProject，返回 {@code OkSnapshot}）→
     * {@code walls.template_id} 回写。propagate（push / throttler / persist）由 {@link #dispatch} 的
     * {@code OkSnapshot} 分支统一收尾，<b>不在此重复</b>（否则双推）。
     *
     * <p>{@code projectImporter} 未装配（导入子系统缺）→ {@code INTERNAL_ERROR}；会话已关 →
     * {@code SESSION_CLOSED}；{@link ac.haru.hikaricanvas.canvasfile.CanvasImportException} 的稳定码
     * （{@code IMPORT_INVALID_PARAM} / {@code IMPORT_MALFORMED} / ...）原样透传给前端。warnings 为
     * 非致命，P1 仅记 {@code fine}（下行给前端是后续 wire 变更）。</p>
     *
     * <p>package-private（非 private）以便 dispatcher 单测直接驱动 pack 分支（含未装配降级路径），
     * 绕开 {@code WsMessageContext}（Javalin final 类，不可 mock）——照 {@link #handleCanvasTweenFps} 范式。</p>
     */
    EditSession.OpResult applyPackEntry(String sessionId, String templateId, TemplateEntry entry,
                                        Map<String, Object> params, java.util.UUID callerUuid) {
        ac.haru.hikaricanvas.canvasfile.ProjectImporter importer = projectImporter;
        if (importer == null) {
            return new EditSession.OpResult.Error("INTERNAL_ERROR", "pack apply unavailable");
        }
        Session session = sessionManager.byId(sessionId);
        if (session == null) {
            return new EditSession.OpResult.Error("SESSION_CLOSED", "no active edit session");
        }
        try {
            ac.haru.hikaricanvas.canvasfile.ProjectImporter.ApplyResult ar =
                    importer.applyPack(session, entry.packBytes(), params, callerUuid);
            if (!ar.warnings().isEmpty()) {
                LOG.fine("template.apply pack '" + templateId + "' produced "
                        + ar.warnings().size() + " warning(s)");
            }
            // walls.template_id / template_version write-back（best-effort，同 YAML 路径）
            if (ar.result() instanceof EditSession.OpResult.OkSnapshot && session.wallId() != null) {
                wallRepo.setTemplate(session.wallId(), templateId, entry.spec().version());
            }
            return ar.result();
        } catch (ac.haru.hikaricanvas.canvasfile.CanvasImportException e) {
            return new EditSession.OpResult.Error(e.code(), e.getMessage());
        }
    }
}
