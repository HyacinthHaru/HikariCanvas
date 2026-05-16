package moe.hikari.canvas.state;

import moe.hikari.canvas.render.DirtyRegion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static moe.hikari.canvas.state.ElementValidator.boolValue;
import static moe.hikari.canvas.state.ElementValidator.floatValue;
import static moe.hikari.canvas.state.ElementValidator.parseBlendMode;
import static moe.hikari.canvas.state.ElementValidator.requireStringValue;

/**
 * layer.* op 集中实现：create / delete / update / reorder / duplicate / set-active。
 *
 * <p>从原 {@code EditSession} god class 抽出（2026-05-14 重构）。EditSession 通过
 * 字段委托调用每个 op，对外公共 API 签名保持完全不变。</p>
 *
 * <p><b>线程安全：</b>不自带锁，依靠 EditSession {@code synchronized(this)} 兜底。</p>
 *
 * <p><b>容量上限：</b>{@link #MAX_LAYERS} / {@link #MAX_LAYER_NAME} 软限保护内存与
 * SQLite/PDC 序列化开销。</p>
 */
final class LayerOperations {

    static final int MAX_LAYERS = 64;
    static final int MAX_LAYER_NAME = 64;

    private final ProjectState state;
    private final HistoryStack history;

    LayerOperations(ProjectState state, HistoryStack history) {
        this.state = state;
        this.history = history;
    }

    // ---------- internal helpers ----------

    private static String layerPath(int layerIdx) {
        return "/layers/" + layerIdx;
    }

    private static String layerFieldPath(int layerIdx, String field) {
        return "/layers/" + layerIdx + "/" + field;
    }

    private int findLayerIdx(String layerId) {
        if (layerId == null) return -1;
        List<Layer> ls = state.layers();
        for (int i = 0; i < ls.size(); i++) {
            if (ls.get(i).id().equals(layerId)) return i;
        }
        return -1;
    }

    private static EditSession.OpResult err(String code, String msg) {
        return new EditSession.OpResult.Error(code, msg);
    }

    // ---------- layer.create ----------

    EditSession.OpResult createLayer(String name, String afterLayerId) {
        if (state.layers().size() >= MAX_LAYERS) {
            return err("INVALID_PAYLOAD", "max layers reached: " + MAX_LAYERS);
        }
        String layerName;
        if (name == null || name.isBlank()) {
            layerName = "Layer " + (state.layers().size() + 1);
        } else if (name.length() > MAX_LAYER_NAME) {
            return err("INVALID_PAYLOAD", "layer name too long (max " + MAX_LAYER_NAME + ")");
        } else {
            layerName = name;
        }

        int insertIdx;
        if (afterLayerId == null || afterLayerId.isEmpty()) {
            insertIdx = state.layers().size();
        } else {
            int afterIdx = findLayerIdx(afterLayerId);
            if (afterIdx < 0) {
                return err("LAYER_NOT_FOUND", "afterLayerId not found: " + afterLayerId);
            }
            insertIdx = afterIdx + 1;
        }

        String id = "l-" + UUID.randomUUID().toString().substring(0, 8);
        Layer newLayer = new Layer(id, layerName, true, false, 1.0f, BlendMode.NORMAL,
                new ArrayList<>());

        ProjectSnapshot pre = history.snapshotNow();
        state.insertLayer(insertIdx, newLayer);
        history.commitHistory(pre);
        long v = state.bumpVersion();

        StatePatch patch = new StatePatchBuilder()
                .add(layerPath(insertIdx), newLayer)
                .build(v);
        // 空层创建不产生像素变化
        return new EditSession.OpResult.Ok(patch, null);
    }

    // ---------- layer.delete ----------

    EditSession.OpResult deleteLayer(String layerId) {
        if (layerId == null) return err("INVALID_PAYLOAD", "layerId missing");
        int idx = findLayerIdx(layerId);
        if (idx < 0) return err("LAYER_NOT_FOUND", "layer not found: " + layerId);
        if (state.layers().size() <= 1) {
            return err("LAST_LAYER", "cannot delete the last layer");
        }

        Layer doomed = state.layers().get(idx);
        boolean hadVisibleContent = doomed.visible() && !doomed.elements().isEmpty();
        boolean wasActive = layerId.equals(state.activeLayerId());

        ProjectSnapshot pre = history.snapshotNow();
        state.removeLayer(idx);
        String newActive = wasActive ? state.layers().get(0).id() : null;
        if (newActive != null) state.activeLayerId(newActive);
        history.commitHistory(pre);
        long v = state.bumpVersion();

        StatePatchBuilder b = new StatePatchBuilder()
                .remove(layerPath(idx));
        if (newActive != null) {
            b.replace("/activeLayerId", newActive);
        }
        DirtyRegion dirty = hadVisibleContent ? DirtyRegion.fullCanvas(state) : null;
        return new EditSession.OpResult.Ok(b.build(v), dirty);
    }

    // ---------- layer.update ----------

    EditSession.OpResult updateLayer(String layerId, Map<String, Object> patch) {
        if (layerId == null) return err("INVALID_PAYLOAD", "layerId missing");
        if (patch == null || patch.isEmpty()) return err("INVALID_PAYLOAD", "empty patch");
        int idx = findLayerIdx(layerId);
        if (idx < 0) return err("LAYER_NOT_FOUND", "layer not found: " + layerId);

        Layer cur = state.layers().get(idx);
        String name = cur.name();
        boolean visible = cur.visible();
        boolean locked = cur.locked();
        float opacity = cur.opacity();
        BlendMode blendMode = cur.blendMode();

        try {
            for (var e : patch.entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                switch (k) {
                    case "name" -> {
                        String n = requireStringValue(v, k);
                        if (n.length() > MAX_LAYER_NAME) {
                            throw new ValidationException("INVALID_PAYLOAD",
                                    "name too long (max " + MAX_LAYER_NAME + ")");
                        }
                        name = n;
                    }
                    case "visible" -> visible = boolValue(v, k);
                    case "locked" -> locked = boolValue(v, k);
                    case "opacity" -> {
                        float o = floatValue(v, k);
                        if (!Float.isFinite(o) || o < 0f || o > 1f) {
                            throw new ValidationException("INVALID_PAYLOAD",
                                    "opacity must be in [0,1]: " + o);
                        }
                        opacity = o;
                    }
                    case "blendMode" -> blendMode = parseBlendMode(v);
                    default -> throw new ValidationException("INVALID_PAYLOAD",
                            "unknown layer field: " + k);
                }
            }
        } catch (ValidationException ve) {
            return err(ve.code, ve.getMessage());
        }

        Layer updated = new Layer(cur.id(), name, visible, locked, opacity, blendMode,
                cur.elements());

        ProjectSnapshot pre = history.snapshotNow();
        state.replaceLayer(idx, updated);
        history.commitHistory(pre);
        long v = state.bumpVersion();

        StatePatchBuilder b = new StatePatchBuilder();
        for (var e : patch.entrySet()) {
            String k = e.getKey();
            Object value = (k.equals("blendMode") && e.getValue() instanceof String s)
                    ? s.toLowerCase()
                    : e.getValue();
            b.replace(layerFieldPath(idx, k), value);
        }
        boolean pixelAffecting = patch.containsKey("visible")
                || patch.containsKey("opacity")
                || patch.containsKey("blendMode");
        DirtyRegion dirty = (pixelAffecting && !updated.elements().isEmpty())
                ? DirtyRegion.fullCanvas(state)
                : null;
        return new EditSession.OpResult.Ok(b.build(v), dirty);
    }

    // ---------- layer.reorder ----------

    EditSession.OpResult reorderLayer(String layerId, int newIndex) {
        if (layerId == null) return err("INVALID_PAYLOAD", "layerId missing");
        int from = findLayerIdx(layerId);
        if (from < 0) return err("LAYER_NOT_FOUND", "layer not found: " + layerId);
        int size = state.layers().size();
        int to = Math.max(0, Math.min(newIndex, size - 1));
        if (to == from) {
            long v = state.bumpVersion();
            return new EditSession.OpResult.Ok(new StatePatch(v, List.of()), null);
        }
        Layer moved = state.layers().get(from);

        ProjectSnapshot pre = history.snapshotNow();
        state.moveLayer(from, to);
        history.commitHistory(pre);
        long v = state.bumpVersion();
        StatePatch patch = new StatePatchBuilder()
                .remove(layerPath(from))
                .add(layerPath(to), moved)
                .build(v);
        DirtyRegion dirty = moved.visible() && !moved.elements().isEmpty()
                ? DirtyRegion.fullCanvas(state)
                : null;
        return new EditSession.OpResult.Ok(patch, dirty);
    }

    // ---------- layer.duplicate ----------

    /** 复制层，所有元素分配新 id；插入到原层之上。 */
    EditSession.OpResult duplicateLayer(String layerId) {
        if (layerId == null) return err("INVALID_PAYLOAD", "layerId missing");
        if (state.layers().size() >= MAX_LAYERS) {
            return err("INVALID_PAYLOAD", "max layers reached: " + MAX_LAYERS);
        }
        int idx = findLayerIdx(layerId);
        if (idx < 0) return err("LAYER_NOT_FOUND", "layer not found: " + layerId);
        Layer src = state.layers().get(idx);

        String newId = "l-" + UUID.randomUUID().toString().substring(0, 8);
        String newName = src.name() + " copy";
        if (newName.length() > MAX_LAYER_NAME) {
            newName = newName.substring(0, MAX_LAYER_NAME);
        }
        List<Element> copiedElements = new ArrayList<>(src.elements().size());
        for (Element e : src.elements()) {
            copiedElements.add(EditSession.cloneElementWithNewId(e));
        }
        Layer copy = new Layer(newId, newName, src.visible(), false,
                src.opacity(), src.blendMode(), copiedElements);

        int insertIdx = idx + 1;
        ProjectSnapshot pre = history.snapshotNow();
        state.insertLayer(insertIdx, copy);
        history.commitHistory(pre);
        long v = state.bumpVersion();
        StatePatch patch = new StatePatchBuilder()
                .add(layerPath(insertIdx), copy)
                .build(v);
        DirtyRegion dirty = copy.visible() && !copy.elements().isEmpty()
                ? DirtyRegion.fullCanvas(state)
                : null;
        return new EditSession.OpResult.Ok(patch, dirty);
    }

    // ---------- layer.set-active ----------

    /**
     * 切换活动层。仅改 {@code activeLayerId}，<b>不</b>进 undo 栈（纯 UI 状态）。
     * 空 ops 还是要返回 patch 让前端同步 activeLayerId 镜像。
     */
    EditSession.OpResult setActiveLayer(String layerId) {
        if (layerId == null || layerId.isEmpty()) {
            return err("INVALID_PAYLOAD", "layerId missing");
        }
        int idx = findLayerIdx(layerId);
        if (idx < 0) return err("LAYER_NOT_FOUND", "layer not found: " + layerId);
        if (layerId.equals(state.activeLayerId())) {
            long v = state.bumpVersion();
            return new EditSession.OpResult.Ok(new StatePatch(v, List.of()), null);
        }
        state.activeLayerId(layerId);
        long v = state.bumpVersion();
        StatePatch patch = new StatePatchBuilder()
                .replace("/activeLayerId", layerId)
                .build(v);
        return new EditSession.OpResult.Ok(patch, null);
    }
}
