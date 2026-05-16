package moe.hikari.canvas.state;

import moe.hikari.canvas.render.DirtyRegion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static moe.hikari.canvas.state.ElementValidator.boolFieldOrDefault;
import static moe.hikari.canvas.state.ElementValidator.floatValue;
import static moe.hikari.canvas.state.ElementValidator.intFieldOrDefault;
import static moe.hikari.canvas.state.ElementValidator.parseBlendModeNullable;
import static moe.hikari.canvas.state.ElementValidator.parseFillNullable;
import static moe.hikari.canvas.state.ElementValidator.parseRenderModeNullable;
import static moe.hikari.canvas.state.ElementValidator.validateBrushSize;

/**
 * M12 笔刷 op 状态机：{@code brush.start / brush.point / brush.end / brush.cancel}。
 *
 * <p>从原 {@code EditSession} god class 抽出（2026-05-14 重构）。EditSession 通过
 * 字段委托所有 brush.* op 与 {@code purgeStaleStrokes}；公共 API 保持不变。</p>
 *
 * <p><b>状态机：</b>每个活跃笔触持 {@link StrokeBuffer}（strokeId → buffer），
 * {@code brush.start} 分配；{@code brush.point} 累积 raw points；{@code brush.end}
 * RDP 简化 + bbox 计算 + 写入目标 layer.elements；{@code brush.cancel} 或 timeout 丢弃。</p>
 *
 * <p><b>线程安全：</b>不自带锁，依靠 EditSession {@code synchronized(this)} 兜底。</p>
 *
 * <p><b>容量：</b>{@link #MAX_ACTIVE_STROKES} 防恶意 brush.start 不发 end；
 * {@link #MAX_BRUSH_POINTS_PER_STROKE} 防长按 + 拖动产生无限点；
 * {@link #STROKE_TIMEOUT_MS} idle timeout 防客户端断线泄漏。</p>
 */
final class BrushSession {

    /** 单笔触最大采样点数（防长按 + 拖动产生无限点占满内存）。 */
    static final int MAX_BRUSH_POINTS_PER_STROKE = 5000;
    /** 单 session 同时活跃笔触数上限（防恶意 brush.start 不发 end）。 */
    static final int MAX_ACTIVE_STROKES = 4;
    /** 笔触 buffer 闲置 timeout（ms）；超过后认为客户端掉线，brush.* op 拒。 */
    static final long STROKE_TIMEOUT_MS = 30_000;

    /**
     * 服务端笔触缓冲。{@code brush.start} 时分配，{@code brush.point} 累加，
     * {@code brush.end} 时简化并写入 layer 的 elements 列表；{@code brush.cancel} 或 timeout
     * 直接丢弃。
     *
     * <p>{@link #rawPoints} 中的坐标是<b>画布绝对坐标</b>（前端发送时即按 stage 坐标计算）；
     * {@code brush.end} 时转换为 element bbox 相对坐标写入 {@link BrushStrokeElement#points}。</p>
     */
    static final class StrokeBuffer {
        final String strokeId;
        final String layerId;          // null = activeLayer 解析（endBrush 时取当前 activeLayer）
        final int size;
        final Fill fill;
        final float opacity;
        final boolean pressureSize;
        final boolean pressureOpacity;
        final float smoothing;           // [0, 1]，brush.end 时 RDP epsilon = smoothing × 4 px
        final BlendMode blendMode;
        final RenderMode renderMode;
        final List<BrushPoint> rawPoints = new ArrayList<>();
        long lastActivityMs;

        StrokeBuffer(String strokeId, String layerId, int size, Fill fill, float opacity,
                     boolean pressureSize, boolean pressureOpacity, float smoothing,
                     BlendMode blendMode, RenderMode renderMode) {
            this.strokeId = strokeId;
            this.layerId = layerId;
            this.size = size;
            this.fill = fill;
            this.opacity = opacity;
            this.pressureSize = pressureSize;
            this.pressureOpacity = pressureOpacity;
            this.smoothing = smoothing;
            this.blendMode = blendMode;
            this.renderMode = renderMode;
            this.lastActivityMs = System.currentTimeMillis();
        }
    }

    private final ProjectState state;
    private final HistoryStack history;
    private final Map<String, StrokeBuffer> strokes = new HashMap<>();

    BrushSession(ProjectState state, HistoryStack history) {
        this.state = state;
        this.history = history;
    }

    // ---------- internal helpers ----------

    private int findLayerIdx(String layerId) {
        if (layerId == null) return -1;
        List<Layer> ls = state.layers();
        for (int i = 0; i < ls.size(); i++) {
            if (ls.get(i).id().equals(layerId)) return i;
        }
        return -1;
    }

    private static String elementPath(int layerIdx, int elIdx) {
        return "/layers/" + layerIdx + "/elements/" + elIdx;
    }

    private static EditSession.OpResult err(String code, String msg) {
        return new EditSession.OpResult.Error(code, msg);
    }

    // ---------- brush.start ----------

    /**
     * brush.start：分配 strokeId 并初始化笔触 buffer。返回 {@link EditSession.OpResult.OkBrushStart}
     * 携带 strokeId（无 patch / dirty）；session 仍 ACTIVE。
     */
    EditSession.OpResult startBrush(Map<String, Object> props, String layerId) {
        if (props == null) props = Map.of();
        purgeStaleStrokes();
        if (strokes.size() >= MAX_ACTIVE_STROKES) {
            return err("TOO_MANY_STROKES", "active stroke count >= " + MAX_ACTIVE_STROKES);
        }
        int size;
        Fill fill;
        float opacity;
        boolean pressureSize;
        boolean pressureOpacity;
        float smoothing;
        BlendMode blendMode;
        RenderMode renderMode;
        try {
            size = intFieldOrDefault(props, "size", 4);
            validateBrushSize(size);
            // fill 优先；缺省时退到 color 字段（兼容性简化）
            Object fillRaw = props.get("fill");
            if (fillRaw == null && props.containsKey("color")) {
                fillRaw = props.get("color");
            }
            fill = parseFillNullable(fillRaw);
            if (fill == null) fill = new SolidFill("#000000"); // 笔刷必须有色
            opacity = props.containsKey("opacity")
                    ? floatValue(props.get("opacity"), "opacity")
                    : 1.0f;
            if (opacity < 0f || opacity > 1f) {
                return err("INVALID_PAYLOAD", "opacity out of [0, 1]: " + opacity);
            }
            pressureSize = boolFieldOrDefault(props, "pressureSize", true);
            pressureOpacity = boolFieldOrDefault(props, "pressureOpacity", false);
            smoothing = props.containsKey("smoothing")
                    ? floatValue(props.get("smoothing"), "smoothing")
                    : 0.5f;
            if (smoothing < 0f || smoothing > 1f) {
                return err("INVALID_PAYLOAD", "smoothing out of [0, 1]: " + smoothing);
            }
            blendMode = parseBlendModeNullable(props.get("blendMode"));
            renderMode = parseRenderModeNullable(props.get("renderMode"));
        } catch (ValidationException ve) {
            return err(ve.code, ve.getMessage());
        }
        // layerId 校验
        if (layerId != null && !layerId.isEmpty()) {
            int idx = findLayerIdx(layerId);
            if (idx < 0) return err("LAYER_NOT_FOUND", "layer not found: " + layerId);
            if (state.layers().get(idx).locked()) {
                return err("LAYER_LOCKED", "target layer locked: " + layerId);
            }
        } else if (state.activeLayer().locked()) {
            return err("LAYER_LOCKED", "active layer locked");
        }

        String strokeId = "s-" + UUID.randomUUID().toString().substring(0, 8);
        StrokeBuffer buf = new StrokeBuffer(strokeId, layerId, size, fill, opacity,
                pressureSize, pressureOpacity, smoothing, blendMode, renderMode);
        strokes.put(strokeId, buf);
        return new EditSession.OpResult.OkBrushStart(strokeId);
    }

    // ---------- brush.point ----------

    /** brush.point：累加点到 buffer。空 patch（不 emit），客户端不期待 ack。 */
    EditSession.OpResult appendBrushPoints(String strokeId, List<BrushPoint> points) {
        StrokeBuffer buf = strokes.get(strokeId);
        if (buf == null) return err("INVALID_STROKE", "unknown strokeId: " + strokeId);
        if (points == null || points.isEmpty()) {
            return new EditSession.OpResult.Ok(new StatePatch(state.version(), List.of()), null);
        }
        if (buf.rawPoints.size() + points.size() > MAX_BRUSH_POINTS_PER_STROKE) {
            strokes.remove(strokeId);
            return err("STROKE_TOO_LONG", "stroke point count > " + MAX_BRUSH_POINTS_PER_STROKE);
        }
        buf.rawPoints.addAll(points);
        buf.lastActivityMs = System.currentTimeMillis();
        return new EditSession.OpResult.Ok(new StatePatch(state.version(), List.of()), null);
    }

    // ---------- brush.end ----------

    /**
     * brush.end：把 buffer 内 raw points 经 RDP 简化转换为 {@link BrushStrokeElement} 写入 layer。
     *
     * <p>bbox 计算：minX/maxX/minY/maxY of points + padding={@code ceil(size/2)}
     * （笔触半宽，保证 stroke 不出 bbox）。points 坐标从画布绝对转为 element 相对。</p>
     */
    EditSession.OpResult endBrush(String strokeId) {
        StrokeBuffer buf = strokes.remove(strokeId);
        if (buf == null) return err("INVALID_STROKE", "unknown strokeId: " + strokeId);
        if (buf.rawPoints.size() < 2) {
            // 太短的笔触（点 < 2）丢弃，不生成元素，返回空 patch
            return new EditSession.OpResult.Ok(new StatePatch(state.version(), List.of()), null);
        }

        // M12-B：RDP 简化。epsilon = smoothing × 4 px。
        double epsilon = buf.smoothing * 4.0;
        List<BrushPoint> simplified = RdpSimplifier.simplify(buf.rawPoints, epsilon);
        if (simplified.size() < 2) {
            simplified = List.of(buf.rawPoints.get(0),
                    buf.rawPoints.get(buf.rawPoints.size() - 1));
        }

        // 计算 bbox（基于简化后的点）
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (BrushPoint p : simplified) {
            if (p.x() < minX) minX = p.x();
            if (p.x() > maxX) maxX = p.x();
            if (p.y() < minY) minY = p.y();
            if (p.y() > maxY) maxY = p.y();
        }
        int pad = (buf.size + 1) / 2;
        int bx = (int) Math.floor(minX) - pad;
        int by = (int) Math.floor(minY) - pad;
        int bw = (int) Math.ceil(maxX) - bx + pad;
        int bh = (int) Math.ceil(maxY) - by + pad;
        if (bw < 1) bw = 1;
        if (bh < 1) bh = 1;

        List<BrushPoint> rel = new ArrayList<>(simplified.size());
        for (BrushPoint p : simplified) {
            rel.add(new BrushPoint(p.x() - bx, p.y() - by, p.pressure()));
        }

        // 目标 layer 解析
        Layer target;
        int layerIdx;
        if (buf.layerId == null || buf.layerId.isEmpty()) {
            target = state.activeLayer();
            layerIdx = findLayerIdx(target.id());
        } else {
            layerIdx = findLayerIdx(buf.layerId);
            if (layerIdx < 0) return err("LAYER_NOT_FOUND", "layer not found: " + buf.layerId);
            target = state.layers().get(layerIdx);
        }
        if (target.locked()) return err("LAYER_LOCKED", "target layer locked: " + target.id());

        String id = "e-" + UUID.randomUUID();
        Float elementOpacity = buf.opacity < 1.0f ? buf.opacity : null;
        BrushStrokeElement element = new BrushStrokeElement(
                id, bx, by, bw, bh, 0, false, true,
                rel, buf.size, buf.fill,
                buf.pressureSize, buf.pressureOpacity,
                elementOpacity, buf.blendMode, buf.renderMode);

        ProjectSnapshot pre = history.snapshotNow();
        int insertIdx = target.elements().size();
        target.elements().add(insertIdx, element);
        history.commitHistory(pre);
        long v = state.bumpVersion();

        StatePatch patch = new StatePatchBuilder()
                .add(elementPath(layerIdx, insertIdx), element)
                .build(v);
        return new EditSession.OpResult.Ok(patch, DirtyRegion.of(element));
    }

    // ---------- brush.cancel ----------

    /** brush.cancel：丢弃 buffer，不生成 element。 */
    EditSession.OpResult cancelBrush(String strokeId) {
        StrokeBuffer removed = strokes.remove(strokeId);
        if (removed == null) return err("INVALID_STROKE", "unknown strokeId: " + strokeId);
        return new EditSession.OpResult.Ok(new StatePatch(state.version(), List.of()), null);
    }

    // ---------- purge / test hooks ----------

    /**
     * 清理超过 {@link #STROKE_TIMEOUT_MS} 闲置的笔触 buffer（防客户端断线泄漏内存）。
     */
    void purgeStaleStrokes() {
        long cutoff = System.currentTimeMillis() - STROKE_TIMEOUT_MS;
        strokes.values().removeIf(b -> b.lastActivityMs < cutoff);
    }

    /** 当前活跃 stroke 数。EditSessionBrushPurgeTest 用。 */
    int activeStrokeCountForTest() {
        return strokes.size();
    }

    /** 把某 stroke 的 lastActivityMs 强制设置为 ms。purgeStaleStrokes 回归测试用。 */
    void overrideStrokeActivityForTest(String strokeId, long ms) {
        StrokeBuffer b = strokes.get(strokeId);
        if (b != null) b.lastActivityMs = ms;
    }
}
