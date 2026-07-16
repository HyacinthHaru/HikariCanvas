package moe.hikari.canvas.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * timeline.* / keyframe.* op 集中实现（0.6 引入）：
 * create / update / delete + keyframe add / update / delete / move。
 *
 * <p>范式与 {@link LayerOperations} 完全对齐 —— 构造器接 {@code (ProjectState, HistoryStack)}，
 * 每个 op 走 {@code snapshotNow → mutate → commitHistory → bumpVersion → 产 StatePatch}。
 * EditSession 通过字段委托调用，对外 public API 由 EditSession 暴露。契约见
 * {@code docs/timeline.md §2 / §7} 与 {@code docs/protocol.md §5.12 / §5.13}。</p>
 *
 * <p><b>线程安全：</b>不自带锁，依靠 EditSession {@code synchronized(this)} 兜底。</p>
 *
 * <p><b>fps 钳位：</b>{@link #defaultFps} / {@link #maxFps} 由 SessionManager 经
 * {@link #setFpsLimits(Integer, Integer)} 注入（对应 config 段 {@code timeline}）；未注入
 * （null）时退 {@code HikariCanvasConfig.TimelineConfig.defaults()} 的 20 / 60（与之同值，
 * 见 architecture.md §5.5）。op 层静默把 fps clamp 到 {@code [1, maxFps]}（协议契约
 * 「超出按上限截断」，protocol.md §5.12）。</p>
 *
 * <p><b>mutation 范式：</b>{@link Timeline} 不可变，所有改动重建 record 后 {@code replaceTimelineAt}
 * （与 Element / Layer「重建 record 再置换」一致），保证 {@link ProjectSnapshot} 可按引用共享、
 * undo/redo 不互相污染。</p>
 */
final class TimelineOperations {

    /** 单工程时间轴数上限。 */
    static final int MAX_TIMELINES = 16;
    /** 单时间轴关键帧总数上限（全轨道合计）。 */
    static final int MAX_KEYFRAMES_PER_TIMELINE = 2048;
    /** 单属性轨关键帧数上限。 */
    static final int MAX_KEYFRAMES_PER_TRACK = 256;
    /** 时间轴 / 关键帧名字段长度上限。 */
    static final int NAME_MAX = 64;
    /** 时间轴时长下界（ms）。 */
    static final int DURATION_MIN_MS = 100;
    /** 时间轴时长上界（ms，= 1 小时）。 */
    static final int DURATION_MAX_MS = 3_600_000;
    /** 字符串型关键帧值（text / color hex / {@code ${var:X}} 模板）长度上限。 */
    static final int STRING_VALUE_MAX = 256;

    /** trigger params 项数上限。 */
    private static final int TRIGGER_PARAMS_MAX = 8;

    /** 默认 fps 回退值（对应 HikariCanvasConfig.TimelineConfig.defaults() 的 20）。 */
    private static final int FALLBACK_DEFAULT_FPS = 20;
    /** maxFps 回退值（对应 HikariCanvasConfig.TimelineConfig.defaults() 的 60）。 */
    private static final int FALLBACK_MAX_FPS = 60;

    // fill 型关键帧值复用 ElementValidator.parseFillNullable（内含 FillValidator 校验），
    // 不另起一套 fill 解析逻辑。

    private final ProjectState state;
    private final HistoryStack history;

    /** 由 SessionManager 注入的默认 fps；null = 未注入，退 {@link #FALLBACK_DEFAULT_FPS}。 */
    private volatile Integer defaultFps;
    /** 由 SessionManager 注入的 fps 安全上限；null = 未注入，退 {@link #FALLBACK_MAX_FPS}。 */
    private volatile Integer maxFps;

    TimelineOperations(ProjectState state, HistoryStack history) {
        this.state = state;
        this.history = history;
    }

    /**
     * 注入 fps 配置（config 段 {@code timeline}）。任一参数为 null 时该项退回常量缺省。
     * 由 SessionManager 在构造 EditSession 后调用（同 {@code setMaxImagesPerWall} 注入点）。
     */
    void setFpsLimits(Integer defaultFps, Integer maxFps) {
        this.defaultFps = defaultFps;
        this.maxFps = maxFps;
    }

    private int effectiveDefaultFps() {
        Integer d = this.defaultFps;
        return d != null ? d : FALLBACK_DEFAULT_FPS;
    }

    private int effectiveMaxFps() {
        Integer m = this.maxFps;
        return m != null ? m : FALLBACK_MAX_FPS;
    }

    // ---------- internal helpers ----------

    private static EditSession.OpResult err(String code, String msg) {
        return new EditSession.OpResult.Error(code, msg);
    }

    /** JSON Pointer 段转义（RFC 6901 §3）：{@code ~} → {@code ~0}，{@code /} → {@code ~1}。 */
    private static String escapePointer(String s) {
        return s.replace("~", "~0").replace("/", "~1");
    }

    private static String timelinePath(int idx) {
        return "/timelines/" + idx;
    }

    private static String timelineFieldPath(int idx, String field) {
        return "/timelines/" + idx + "/" + field;
    }

    private static String trackPath(int timelineIdx, String elementId) {
        return "/timelines/" + timelineIdx + "/tracks/" + escapePointer(elementId);
    }

    private static String keyframePath(int timelineIdx, String elementId, int k) {
        return "/timelines/" + timelineIdx + "/tracks/" + escapePointer(elementId) + "/" + k;
    }

    /** 生成不与现有集合碰撞的 id（前缀 {@code tl-} / {@code kf-}）。 */
    private static String freshId(String prefix, java.util.Set<String> existing) {
        String id;
        do {
            id = prefix + UUID.randomUUID().toString().substring(0, 8);
        } while (existing.contains(id));
        return id;
    }

    /** 所有现存 timeline id（id 碰撞检测用）。 */
    private java.util.Set<String> timelineIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Timeline t : state.timelines()) ids.add(t.id());
        return ids;
    }

    /** 所有现存 keyframe id（跨所有 timeline 全部轨道；id 碰撞检测用）。 */
    private java.util.Set<String> allKeyframeIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Timeline t : state.timelines()) {
            for (List<Keyframe> track : t.tracks().values()) {
                for (Keyframe kf : track) ids.add(kf.id());
            }
        }
        return ids;
    }

    /** elementId 是否存在于任一 layer。 */
    private boolean elementExists(String elementId) {
        if (elementId == null) return false;
        for (Layer l : state.layers()) {
            for (Element e : l.elements()) {
                if (e.id().equals(elementId)) return true;
            }
        }
        return false;
    }

    // ---------- 校验子（throw ValidationException，外层 catch 转 OpResult.Error）----------

    /** name → 规范化后字符串；{@code blank} 返回 null（调用方自行决定缺省名）。 */
    private static String validateName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String n = raw.trim();
        if (n.length() > NAME_MAX) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "timeline name too long (max " + NAME_MAX + ")");
        }
        return n;
    }

    private static int validateDuration(Integer durationMs) {
        if (durationMs == null) {
            throw new ValidationException("INVALID_PAYLOAD", "durationMs missing");
        }
        if (durationMs < DURATION_MIN_MS || durationMs > DURATION_MAX_MS) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "durationMs out of range [" + DURATION_MIN_MS + ", " + DURATION_MAX_MS
                            + "]: " + durationMs);
        }
        return durationMs;
    }

    /** fps：null → defaultFps；其余静默 clamp 到 {@code [1, maxFps]}（协议「超出按上限截断」）。 */
    private int resolveFps(Integer fps) {
        int f = (fps == null) ? effectiveDefaultFps() : fps;
        int max = effectiveMaxFps();
        if (f < 1) f = 1;
        if (f > max) f = max;
        return f;
    }

    private static LoopMode validateLoopMode(String wire) {
        if (wire == null) return LoopMode.LOOP;
        LoopMode m = LoopMode.fromWire(wire);
        if (m == null) {
            throw new ValidationException("INVALID_PAYLOAD", "unknown loopMode: " + wire);
        }
        return m;
    }

    /** trigger raw（{@code {type, params}}）→ {@link TriggerConfig}；null → MANUAL。 */
    private static TriggerConfig validateTrigger(Map<String, Object> raw) {
        if (raw == null) return TriggerConfig.MANUAL;
        Object typeRaw = raw.get("type");
        if (!(typeRaw instanceof String typeWire)) {
            throw new ValidationException("INVALID_PAYLOAD", "trigger.type must be string");
        }
        TriggerType type = TriggerType.fromWire(typeWire);
        if (type == null) {
            throw new ValidationException("INVALID_PAYLOAD", "unknown trigger.type: " + typeWire);
        }
        Map<String, String> params = Map.of();
        Object paramsRaw = raw.get("params");
        if (paramsRaw != null) {
            if (!(paramsRaw instanceof Map<?, ?> pm)) {
                throw new ValidationException("INVALID_PAYLOAD", "trigger.params must be object");
            }
            if (pm.size() > TRIGGER_PARAMS_MAX) {
                throw new ValidationException("INVALID_PAYLOAD",
                        "trigger.params too many entries (max " + TRIGGER_PARAMS_MAX + ")");
            }
            LinkedHashMap<String, String> out = new LinkedHashMap<>(pm.size());
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                if (!(e.getKey() instanceof String pk) || !(e.getValue() instanceof String pv)) {
                    throw new ValidationException("INVALID_PAYLOAD",
                            "trigger.params key/value must be string");
                }
                if (pk.length() > STRING_VALUE_MAX || pv.length() > STRING_VALUE_MAX) {
                    throw new ValidationException("INVALID_PAYLOAD",
                            "trigger.params key/value too long (max " + STRING_VALUE_MAX + ")");
                }
                out.put(pk, pv);
            }
            params = out;
        }
        // VARIABLE_CHANGE / SCHEDULE 必须带 params.fullName（绑定的变量），否则触发器无从匹配。
        if (type == TriggerType.VARIABLE_CHANGE || type == TriggerType.SCHEDULE) {
            String fn = params.get("fullName");
            if (fn == null || fn.isBlank()) {
                throw new ValidationException("INVALID_PAYLOAD",
                        type.wire() + " trigger requires non-empty params.fullName");
            }
        }
        return new TriggerConfig(type, params);
    }

    /** trigger 规范化 Map（state.patch value 用，回写 wire 形态）。 */
    private static Map<String, Object> triggerToWire(TriggerConfig t) {
        return Map.of("type", t.type().wire(), "params", t.params());
    }

    /**
     * valueRaw 按 property 类别解析成 {@link KfValue}：
     * <ul>
     *   <li>数值类（{@link Keyframe#NUMERIC_PROPERTIES}）：Number → {@link KfValue.Num}（NaN /
     *       Inf 拒）；String → {@link KfValue.Str}（≤256，留 {@code ${var:X}} 模板口子）</li>
     *   <li>color / text：String → {@link KfValue.Str}（≤256）</li>
     *   <li>fill：Map → {@link Fill}（经 {@link ElementValidator#parseFillNullable}，含
     *       {@link FillValidator} 校验）→ {@link KfValue.FillV}；String → {@link KfValue.Str}</li>
     * </ul>
     */

    /** hex 颜色形态校验（#RRGGBB / #RRGGBBAA，与 ColorLerp.parseHex 白名单同源）。 */
    private static boolean isValidHexColor(String s) {
        if (s == null || s.isEmpty() || s.charAt(0) != '#') return false;
        int len = s.length() - 1;
        if (len != 6 && len != 8) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    private static boolean isVarTemplate(String s) {
        return s != null && s.indexOf("${var:") >= 0;
    }

    private static KfValue parseValue(String property, Object valueRaw) {
        if (Keyframe.NUMERIC_PROPERTIES.contains(property)) {
            if (valueRaw instanceof Number n) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new ValidationException("INVALID_PAYLOAD",
                            "numeric keyframe value must be finite: " + property);
                }
                // B1：w/h 关键帧不得超 MAX_DIM，否则渲染线程按 element w×h 分配巨 buffer → OOM
                if (("w".equals(property) || "h".equals(property))
                        && d > ElementValidator.MAX_DIM) {
                    throw new ValidationException("INVALID_PAYLOAD",
                            property + " keyframe value exceeds MAX_DIM ("
                                    + ElementValidator.MAX_DIM + "): " + d);
                }
                return new KfValue.Num(d);
            }
            if (valueRaw instanceof String s) {
                // 非变量模板的字符串必须是严格数值文法——否则双端渲染期
                // 静默退 0 且 Java/TS 解析语义分叉；堵在 op 入口
                if (!isVarTemplate(s) && !StrictNumber.PATTERN.matcher(s.trim()).matches()) {
                    throw new ValidationException("INVALID_PAYLOAD",
                            "numeric keyframe string must be a number or ${var:...}: " + property);
                }
                return strValue(s);
            }
            throw new ValidationException("INVALID_PAYLOAD",
                    "numeric property '" + property + "' value must be number or string");
        }
        if ("color".equals(property)) {
            if (valueRaw instanceof String s) {
                // 非法 hex 会在渲染期静默退化（step / 白色），堵在 op 入口
                if (!isVarTemplate(s) && !isValidHexColor(s)) {
                    throw new ValidationException("INVALID_PAYLOAD",
                            "color keyframe value must be #RRGGBB / #RRGGBBAA or ${var:...}");
                }
                return strValue(s);
            }
            throw new ValidationException("INVALID_PAYLOAD",
                    "property 'color' value must be string");
        }
        if ("text".equals(property)) {
            if (valueRaw instanceof String s) {
                return strValue(s);
            }
            throw new ValidationException("INVALID_PAYLOAD",
                    "property 'text' value must be string");
        }
        if ("fill".equals(property)) {
            if (valueRaw instanceof Map<?, ?>) {
                // parseFillNullable 接 Object（Map / String），内含 FillValidator 校验
                Fill fill = ElementValidator.parseFillNullable(valueRaw);
                if (fill == null) {
                    throw new ValidationException("INVALID_PAYLOAD", "fill keyframe value invalid");
                }
                return new KfValue.FillV(fill);
            }
            if (valueRaw instanceof String s) {
                // 同 color：String 形态会归一化为 SolidFill 走 lerpHex，hex 必须合法
                if (!isVarTemplate(s) && !isValidHexColor(s)) {
                    throw new ValidationException("INVALID_PAYLOAD",
                            "fill keyframe string must be #RRGGBB / #RRGGBBAA or ${var:...}");
                }
                return strValue(s);
            }
            throw new ValidationException("INVALID_PAYLOAD",
                    "property 'fill' value must be object or string");
        }
        // property 已经过 PROPERTIES 白名单，理论不可达
        throw new ValidationException("INVALID_PAYLOAD", "unsupported property: " + property);
    }

    private static KfValue.Str strValue(String s) {
        if (s.length() > STRING_VALUE_MAX) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "string keyframe value too long (max " + STRING_VALUE_MAX + ")");
        }
        return new KfValue.Str(s);
    }

    /**
     * easingRaw → {@link Easing}：
     * <ul>
     *   <li>null → {@link Easing#LINEAR}</li>
     *   <li>Map：{@code type} 走 {@link EasingType#fromWire}（未知 / 缺 → INVALID_EASING）；
     *       {@code CUBIC_BEZIER} 时 {@code bezier} 必须 4 个有限 Number 且 {@code x1,x2 ∈ [0,1]}；
     *       非 {@code CUBIC_BEZIER} 时 {@code bezier} 须为 null</li>
     *   <li>非 Map 非 null → INVALID_EASING</li>
     * </ul>
     */
    private static Easing parseEasing(Object easingRaw) {
        if (easingRaw == null) return Easing.LINEAR;
        if (!(easingRaw instanceof Map<?, ?> m)) {
            throw new ValidationException("INVALID_EASING", "easing must be object");
        }
        Object typeRaw = m.get("type");
        if (!(typeRaw instanceof String typeWire)) {
            throw new ValidationException("INVALID_EASING", "easing.type must be string");
        }
        EasingType type = EasingType.fromWire(typeWire);
        if (type == null) {
            throw new ValidationException("INVALID_EASING", "unknown easing.type: " + typeWire);
        }
        Object bezierRaw = m.get("bezier");
        if (type == EasingType.CUBIC_BEZIER) {
            if (!(bezierRaw instanceof List<?> list) || list.size() != 4) {
                throw new ValidationException("INVALID_EASING",
                        "cubicBezier requires bezier [x1,y1,x2,y2]");
            }
            List<Double> bezier = new ArrayList<>(4);
            for (Object o : list) {
                if (!(o instanceof Number num)) {
                    throw new ValidationException("INVALID_EASING", "bezier entries must be numbers");
                }
                double d = num.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new ValidationException("INVALID_EASING", "bezier entries must be finite");
                }
                bezier.add(d);
            }
            // x1 = bezier[0], x2 = bezier[2] 须 ∈ [0,1]（保证 Bx 单调，与 CSS 一致；y 不限）
            if (bezier.get(0) < 0 || bezier.get(0) > 1 || bezier.get(2) < 0 || bezier.get(2) > 1) {
                throw new ValidationException("INVALID_EASING",
                        "cubicBezier x1/x2 must be in [0,1]");
            }
            return new Easing(EasingType.CUBIC_BEZIER, bezier);
        }
        if (bezierRaw != null) {
            throw new ValidationException("INVALID_EASING",
                    "bezier only allowed for cubicBezier easing");
        }
        return new Easing(type, null);
    }

    // ---------- timeline.create ----------

    EditSession.OpResult createTimeline(String name, Integer durationMs, Integer fps,
                                        String loopModeWire, Map<String, Object> triggerRaw) {
        if (state.timelines().size() >= MAX_TIMELINES) {
            return err("INVALID_PAYLOAD", "max timelines reached: " + MAX_TIMELINES);
        }
        String resolvedName;
        int duration;
        int resolvedFps;
        LoopMode loopMode;
        TriggerConfig trigger;
        try {
            String n = validateName(name);
            resolvedName = (n != null) ? n : "Timeline " + (state.timelines().size() + 1);
            duration = validateDuration(durationMs);
            resolvedFps = resolveFps(fps);
            loopMode = validateLoopMode(loopModeWire);
            trigger = validateTrigger(triggerRaw);
        } catch (ValidationException ve) {
            return err(ve.code, ve.getMessage());
        }

        String id = freshId("tl-", timelineIds());
        Timeline t = new Timeline(id, resolvedName, duration, resolvedFps,
                loopMode, trigger, Map.of());
        int idx = state.timelines().size();

        ProjectSnapshot pre = history.snapshotNow();
        state.addTimeline(t);
        state.activeTimelineId(id);
        history.commitHistory(pre);
        long v = state.bumpVersion();

        StatePatch patch = new StatePatchBuilder()
                .add(timelinePath(idx), t)
                .replace("/activeTimelineId", id)
                .build(v);
        // timeline op 不产生像素变化（P3 起动画 ticker 才驱动重绘）
        return new EditSession.OpResult.Ok(patch, null);
    }

    // ---------- timeline.update ----------

    EditSession.OpResult updateTimeline(String timelineId, Map<String, Object> patch) {
        int idx = state.indexOfTimeline(timelineId);
        if (idx < 0) return err("TIMELINE_NOT_FOUND", "timeline not found: " + timelineId);
        if (patch == null || patch.isEmpty()) return err("INVALID_PAYLOAD", "empty patch");

        Timeline cur = state.timelines().get(idx);
        String name = cur.name();
        int duration = cur.durationMs();
        int fps = cur.fps();
        LoopMode loopMode = cur.loopMode();
        TriggerConfig trigger = cur.trigger();

        try {
            for (var e : patch.entrySet()) {
                String k = e.getKey();
                Object vv = e.getValue();
                switch (k) {
                    case "name" -> {
                        String n = validateName(stringOrThrow(vv, "name"));
                        // blank name 在 update 路径不退缺省，直接拒（创建期才补缺省）
                        if (n == null) {
                            throw new ValidationException("INVALID_PAYLOAD", "name must not be blank");
                        }
                        name = n;
                    }
                    case "durationMs" -> duration = validateDuration(intOrThrow(vv, "durationMs"));
                    case "fps" -> fps = resolveFps(intOrThrow(vv, "fps"));
                    case "loopMode" -> loopMode = validateLoopMode(stringOrThrow(vv, "loopMode"));
                    case "trigger" -> {
                        // 显式 null 拒收：与 name/durationMs 等同级字段一致，防「想清字段 vs
                        // 误传 null」歧义把已有 trigger 静默重置成 manual
                        if (vv == null) {
                            throw new ValidationException("INVALID_PAYLOAD",
                                    "trigger must be object (explicit null not allowed)");
                        }
                        trigger = validateTrigger(mapOrThrow(vv, "trigger"));
                    }
                    default -> throw new ValidationException("INVALID_PAYLOAD",
                            "unknown timeline field: " + k);
                }
            }
            // durationMs 缩短不得低于该 timeline 任意现有 keyframe 的 timeMs
            if (patch.containsKey("durationMs")) {
                int maxKfTime = maxKeyframeTime(cur);
                if (duration < maxKfTime) {
                    throw new ValidationException("INVALID_KEYFRAME_TIME",
                            "durationMs " + duration + " below existing keyframe at " + maxKfTime);
                }
            }
        } catch (ValidationException ve) {
            return err(ve.code, ve.getMessage());
        }

        Timeline updated = new Timeline(cur.id(), name, duration, fps,
                loopMode, trigger, cur.tracks());
        ProjectSnapshot pre = history.snapshotNow();
        state.replaceTimelineAt(idx, updated);
        history.commitHistory(pre);
        long v = state.bumpVersion();

        StatePatchBuilder b = new StatePatchBuilder();
        if (patch.containsKey("name")) b.replace(timelineFieldPath(idx, "name"), name);
        if (patch.containsKey("durationMs")) b.replace(timelineFieldPath(idx, "durationMs"), duration);
        if (patch.containsKey("fps")) b.replace(timelineFieldPath(idx, "fps"), fps);
        if (patch.containsKey("loopMode")) {
            b.replace(timelineFieldPath(idx, "loopMode"), loopMode.wire());
        }
        if (patch.containsKey("trigger")) {
            b.replace(timelineFieldPath(idx, "trigger"), triggerToWire(trigger));
        }
        return new EditSession.OpResult.Ok(b.build(v), null);
    }

    /** 该 timeline 全轨道最大 keyframe timeMs（无 keyframe → 0）。 */
    private static int maxKeyframeTime(Timeline t) {
        int max = 0;
        for (List<Keyframe> track : t.tracks().values()) {
            for (Keyframe kf : track) {
                if (kf.timeMs() > max) max = kf.timeMs();
            }
        }
        return max;
    }

    // ---------- timeline.delete ----------

    EditSession.OpResult deleteTimeline(String timelineId) {
        int idx = state.indexOfTimeline(timelineId);
        if (idx < 0) return err("TIMELINE_NOT_FOUND", "timeline not found: " + timelineId);

        boolean wasActive = timelineId.equals(state.activeTimelineId());

        ProjectSnapshot pre = history.snapshotNow();
        state.removeTimelineAt(idx);
        String newActive = state.activeTimelineId();
        if (wasActive) {
            newActive = state.timelines().isEmpty() ? null : state.timelines().get(0).id();
            state.activeTimelineId(newActive);
        }
        history.commitHistory(pre);
        long v = state.bumpVersion();

        StatePatch patch = new StatePatchBuilder()
                .remove(timelinePath(idx))
                .replace("/activeTimelineId", newActive)
                .build(v);
        return new EditSession.OpResult.Ok(patch, null);
    }

    // ---------- keyframe.add ----------

    EditSession.OpResult addKeyframe(String timelineId, String elementId, String property,
                                     Integer timeMs, Object valueRaw, Object easingRaw,
                                     String coalesceKey) {
        int idx = state.indexOfTimeline(timelineId);
        if (idx < 0) return err("TIMELINE_NOT_FOUND", "timeline not found: " + timelineId);
        if (!elementExists(elementId)) {
            return err("INVALID_ELEMENT", "element not found: " + elementId);
        }
        if (property == null || !Keyframe.PROPERTIES.contains(property)) {
            return err("INVALID_PAYLOAD", "unknown keyframe property: " + property);
        }
        Timeline cur = state.timelines().get(idx);
        if (timeMs == null || timeMs < 0 || timeMs > cur.durationMs()) {
            return err("INVALID_KEYFRAME_TIME",
                    "timeMs out of range [0, " + cur.durationMs() + "]: " + timeMs);
        }

        KfValue value;
        Easing easing;
        try {
            value = parseValue(property, valueRaw);
            easing = parseEasing(easingRaw);
        } catch (ValidationException ve) {
            return err(ve.code, ve.getMessage());
        }

        List<Keyframe> existing = cur.tracks().get(elementId);
        boolean newTrack = (existing == null);
        int trackSize = newTrack ? 0 : existing.size();
        if (trackSize >= MAX_KEYFRAMES_PER_TRACK
                || cur.totalKeyframes() >= MAX_KEYFRAMES_PER_TIMELINE) {
            return err("INVALID_PAYLOAD", "keyframe quota exceeded for this track / timeline");
        }

        String kfId = freshId("kf-", allKeyframeIds());
        Keyframe kf = new Keyframe(kfId, property, timeMs, value, easing);

        // 按 timeMs 升序找插入位 k；相等 timeMs 排在已有之后（rendering.md §9.1 重合帧取后）
        List<Keyframe> newTrackList = new ArrayList<>(trackSize + 1);
        int k = trackSize;
        if (!newTrack) {
            newTrackList.addAll(existing);
            k = trackSize;
            for (int i = 0; i < trackSize; i++) {
                if (timeMs < newTrackList.get(i).timeMs()) {
                    k = i;
                    break;
                }
            }
        }
        newTrackList.add(k, kf);

        // LinkedHashMap 保留既有轨道顺序；新轨道追加在尾
        LinkedHashMap<String, List<Keyframe>> tracks = new LinkedHashMap<>(cur.tracks());
        tracks.put(elementId, newTrackList);
        Timeline updated = cur.withTracks(tracks);

        ProjectSnapshot pre = history.snapshotNow();
        state.replaceTimelineAt(idx, updated);
        // 整体帧批量加帧（拉就设 / + 按钮）由前端传 coalesceKey，让 6 个属性 add 合并成一步撤销；
        // 缺省（单帧 add）走常规非合并 commit（与历史行为一致）。
        if (coalesceKey != null) history.commitHistoryCoalesced(pre, coalesceKey);
        else history.commitHistory(pre);
        long v = state.bumpVersion();

        StatePatchBuilder b = new StatePatchBuilder();
        if (newTrack) {
            // 整轨首帧：add 整个 List<Keyframe>
            b.add(trackPath(idx, elementId), newTrackList);
        } else {
            b.add(keyframePath(idx, elementId, k), kf);
        }
        return new EditSession.OpResult.Ok(b.build(v), null);
    }

    // ---------- keyframe.update ----------

    EditSession.OpResult updateKeyframe(String timelineId, String keyframeId,
                                        Map<String, Object> patch, String coalesceKey) {
        if (patch == null || patch.isEmpty()) return err("INVALID_PAYLOAD", "empty patch");
        return mutateKeyframe(timelineId, keyframeId, patch, coalesceKey);
    }

    // ---------- keyframe.move ----------

    EditSession.OpResult moveKeyframe(String timelineId, String keyframeId, Integer timeMs,
                                      String coalesceKey) {
        // 语义 = 仅改 timeMs 的 update；复用同一私有实现，共享 coalesce key
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("timeMs", timeMs);
        return mutateKeyframe(timelineId, keyframeId, patch, coalesceKey);
    }

    /** keyframe 在某 timeline 内的定位：所属轨 elementId + 轨内 index + 引用。 */
    private record KfLocator(String elementId, int index, Keyframe keyframe) {}

    private static KfLocator findKeyframe(Timeline t, String keyframeId) {
        if (keyframeId == null) return null;
        for (Map.Entry<String, List<Keyframe>> e : t.tracks().entrySet()) {
            List<Keyframe> track = e.getValue();
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).id().equals(keyframeId)) {
                    return new KfLocator(e.getKey(), i, track.get(i));
                }
            }
        }
        return null;
    }

    /**
     * keyframe.update / keyframe.move 的共用实现。允许键 {@code timeMs / value / easing}。
     * timeMs 变化 → 重排该轨 → 整轨 replace patch；否则单帧 replace。
     * coalesce key：前端传 {@code coalesceKey}（整体帧批量 op 共享一步撤销）优先；缺省回退
     * 单帧键 {@code elementId:keyframeId:property}（单属性连续拖 / 滑块，D7，timeline.md §7.2）。
     */
    private EditSession.OpResult mutateKeyframe(String timelineId, String keyframeId,
                                               Map<String, Object> patch, String coalesceKey) {
        int idx = state.indexOfTimeline(timelineId);
        if (idx < 0) return err("TIMELINE_NOT_FOUND", "timeline not found: " + timelineId);
        Timeline cur = state.timelines().get(idx);
        KfLocator loc = findKeyframe(cur, keyframeId);
        if (loc == null) return err("KEYFRAME_NOT_FOUND", "keyframe not found: " + keyframeId);

        Keyframe orig = loc.keyframe();
        int timeMs = orig.timeMs();
        KfValue value = orig.value();
        Easing easing = orig.easing();
        boolean timeChanged = false;

        try {
            for (var e : patch.entrySet()) {
                String k = e.getKey();
                Object vv = e.getValue();
                switch (k) {
                    case "timeMs" -> {
                        int t = intOrThrow(vv, "timeMs");
                        if (t < 0 || t > cur.durationMs()) {
                            throw new ValidationException("INVALID_KEYFRAME_TIME",
                                    "timeMs out of range [0, " + cur.durationMs() + "]: " + t);
                        }
                        timeChanged = (t != orig.timeMs());
                        timeMs = t;
                    }
                    // value 按该 kf 的既有 property 类别解析
                    case "value" -> value = parseValue(orig.property(), vv);
                    case "easing" -> easing = parseEasing(vv);
                    default -> throw new ValidationException("INVALID_PAYLOAD",
                            "unknown keyframe field: " + k);
                }
            }
        } catch (ValidationException ve) {
            return err(ve.code, ve.getMessage());
        }

        Keyframe updatedKf = new Keyframe(orig.id(), orig.property(), timeMs, value, easing);

        List<Keyframe> oldTrack = cur.tracks().get(loc.elementId());
        List<Keyframe> newTrack = new ArrayList<>(oldTrack);
        newTrack.set(loc.index(), updatedKf);
        int newIndex = loc.index();
        if (timeChanged) {
            // 重排该轨（timeMs 升序，相等排在已有之后）
            newTrack.sort((a, b2) -> Integer.compare(a.timeMs(), b2.timeMs()));
            newIndex = newTrack.indexOf(updatedKf);
        }

        LinkedHashMap<String, List<Keyframe>> tracks = new LinkedHashMap<>(cur.tracks());
        tracks.put(loc.elementId(), newTrack);
        Timeline updated = cur.withTracks(tracks);

        ProjectSnapshot pre = history.snapshotNow();
        state.replaceTimelineAt(idx, updated);
        // D7：keyframe 拖动高频合并。整体帧批量 op（整体块拖动 / 拉就设的 update 分支）由前端传
        //   coalesceKey 让多帧共享一步撤销；缺省回退单帧键 elementId:keyframeId:property（单属性拖 / 滑块）。
        history.commitHistoryCoalesced(pre, coalesceKey != null ? coalesceKey
                : loc.elementId() + ":" + orig.id() + ":" + orig.property());
        long v = state.bumpVersion();

        StatePatchBuilder b = new StatePatchBuilder();
        if (timeChanged) {
            // 重排后位置可能变；整轨 replace 最简明
            b.replace(trackPath(idx, loc.elementId()), newTrack);
        } else {
            b.replace(keyframePath(idx, loc.elementId(), newIndex), updatedKf);
        }
        return new EditSession.OpResult.Ok(b.build(v), null);
    }

    // ---------- keyframe.delete ----------

    EditSession.OpResult deleteKeyframe(String timelineId, String keyframeId) {
        int idx = state.indexOfTimeline(timelineId);
        if (idx < 0) return err("TIMELINE_NOT_FOUND", "timeline not found: " + timelineId);
        Timeline cur = state.timelines().get(idx);
        KfLocator loc = findKeyframe(cur, keyframeId);
        if (loc == null) return err("KEYFRAME_NOT_FOUND", "keyframe not found: " + keyframeId);

        List<Keyframe> oldTrack = cur.tracks().get(loc.elementId());
        boolean trackEmptied = (oldTrack.size() == 1);

        LinkedHashMap<String, List<Keyframe>> tracks = new LinkedHashMap<>(cur.tracks());
        if (trackEmptied) {
            tracks.remove(loc.elementId());
        } else {
            List<Keyframe> newTrack = new ArrayList<>(oldTrack);
            newTrack.remove(loc.index());
            tracks.put(loc.elementId(), newTrack);
        }
        Timeline updated = cur.withTracks(tracks);

        ProjectSnapshot pre = history.snapshotNow();
        state.replaceTimelineAt(idx, updated);
        history.commitHistory(pre);
        long v = state.bumpVersion();

        StatePatchBuilder b = new StatePatchBuilder();
        if (trackEmptied) {
            b.remove(trackPath(idx, loc.elementId()));
        } else {
            b.remove(keyframePath(idx, loc.elementId(), loc.index()));
        }
        return new EditSession.OpResult.Ok(b.build(v), null);
    }

    // ---------- 原始字段取值守卫（payload Object → 期望类型，违规 throw INVALID_PAYLOAD）----------

    private static String stringOrThrow(Object v, String field) {
        if (v instanceof String s) return s;
        throw new ValidationException("INVALID_PAYLOAD", field + " must be string");
    }

    private static int intOrThrow(Object v, String field) {
        if (v instanceof Number n) {
            // 越界 / 非有限值守卫：Long/Double 超出 int 范围时 intValue() 静默回绕，
            // 会绕过 timeMs/durationMs/fps 的语义范围检查
            long l = n.longValue();
            double d = n.doubleValue();
            if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE
                    || Double.isNaN(d) || Double.isInfinite(d)) {
                throw new ValidationException("INVALID_PAYLOAD", field + " out of int range");
            }
            return n.intValue();
        }
        throw new ValidationException("INVALID_PAYLOAD", field + " must be number");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrThrow(Object v, String field) {
        if (v == null) return null;
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new ValidationException("INVALID_PAYLOAD", field + " must be object");
    }
}
