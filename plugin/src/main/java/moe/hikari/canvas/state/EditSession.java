package moe.hikari.canvas.state;

import moe.hikari.canvas.render.DirtyRegion;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.Variable;
import moe.hikari.canvas.variable.VariableException;
import moe.hikari.canvas.variable.VariablePatch;
import moe.hikari.canvas.variable.VariableStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static moe.hikari.canvas.state.ElementValidator.boolFieldOrDefault;
import static moe.hikari.canvas.state.ElementValidator.boolFieldOrNull;
import static moe.hikari.canvas.state.ElementValidator.boolValue;
import static moe.hikari.canvas.state.ElementValidator.buildEffects;
import static moe.hikari.canvas.state.ElementValidator.buildStroke;
import static moe.hikari.canvas.state.ElementValidator.floatFieldOrDefault;
import static moe.hikari.canvas.state.ElementValidator.floatValue;
import static moe.hikari.canvas.state.ElementValidator.intFieldOrDefault;
import static moe.hikari.canvas.state.ElementValidator.intValue;
import static moe.hikari.canvas.state.ElementValidator.isValidColor;
import static moe.hikari.canvas.state.ElementValidator.parseBlendModeNullable;
import static moe.hikari.canvas.state.ElementValidator.parseFillNullable;
import static moe.hikari.canvas.state.ElementValidator.parseMarkerNullable;
import static moe.hikari.canvas.state.ElementValidator.parseMaskNullable;
import static moe.hikari.canvas.state.ElementValidator.parseOpacityNullable;
import static moe.hikari.canvas.state.ElementValidator.parseRenderModeNullable;
import static moe.hikari.canvas.state.ElementValidator.requireString;
import static moe.hikari.canvas.state.ElementValidator.requireStringValue;
import static moe.hikari.canvas.state.ElementValidator.stringFieldOrDefault;
import static moe.hikari.canvas.state.ElementValidator.validateAlign;
import static moe.hikari.canvas.state.ElementValidator.validateBrushSize;
import static moe.hikari.canvas.state.ElementValidator.validateColor;
import static moe.hikari.canvas.state.ElementValidator.validateCoord;
import static moe.hikari.canvas.state.ElementValidator.validateDim;
import static moe.hikari.canvas.state.ElementValidator.validateFontSize;
import static moe.hikari.canvas.state.ElementValidator.validateImageSource;
import static moe.hikari.canvas.state.ElementValidator.validateInnerRatio;
import static moe.hikari.canvas.state.ElementValidator.validateLetterSpacing;
import static moe.hikari.canvas.state.ElementValidator.validateLineHeight;
import static moe.hikari.canvas.state.ElementValidator.validatePathD;
import static moe.hikari.canvas.state.ElementValidator.validateRotation;
import static moe.hikari.canvas.state.ElementValidator.validateShapeKind;
import static moe.hikari.canvas.state.ElementValidator.validateSides;
import static moe.hikari.canvas.state.ElementValidator.validateText;

/**
 * 权威编辑会话：WS 上行 op → {@link ProjectState} mutation → 产出 {@link StatePatch}。
 * 契约见 {@code docs/protocol.md §5.3 / §5.4}。
 *
 * <p>1 个 EditSession ↔ 1 个 {@code moe.hikari.canvas.session.Session}；随 session 生灭。</p>
 *
 * <p><b>M8-C 升级：</b> 所有 element / layer / canvas op 切换到 v2 path
 * （{@code /layers/{i}/elements/{j}/...}）。新增 layer.* op 族 + element.move-to-layer
 * + canvas.grid + canvas.guides.set。{@code locked} 层内 element op 全部拒
 * {@code LAYER_LOCKED}。</p>
 *
 * <p><b>并发：</b> Javalin WS handler 跑在 Jetty 线程池，同一连接也可能出现 op pipeline。
 * 所有 {@code apply*} 方法 {@code synchronized(this)} 保证 {@link ProjectState} 单线程变更。</p>
 *
 * <p><b>验证层级：</b> 这一层做字段格式与范围 sanity 校验（color / rotation / text len / 数值区间）；
 * 业务不变式（跨 session 排他、池容量等）由 SessionManager / MapPool 负责。</p>
 */
public final class EditSession {

    // ---------- 校验常量 ----------
    // 元素级校验常量（COLOR_RE / MAX_TEXT_LEN / MAX_COORD / MAX_DIM / MIN_/MAX_LETTER_SPACING /
    // MIN_/MAX_LINE_HEIGHT / MAX_SHADOW_OFFSET / MAX_GLOW_RADIUS / MASK_NUMBER_RE / MAX_MASK_VERTICES /
    // IMAGE_SOURCE_RE / MIN_/MAX_SIDES / MIN_/MAX_INNER_RATIO / MIN_/MAX_BRUSH_SIZE / FILL_MAPPER）
    // 2026-05-14 重构后集中于 {@link ElementValidator}。

    // layer 容量常量集中于 {@link LayerOperations}；保留单工程参考线上限本地化（仅 canvas op 用）。
    /** 单工程参考线数上限（防意外刷爆）。 */
    private static final int MAX_GUIDES = 256;

    private final ProjectState state;

    /**
     * P2-8：单 wall image 元素数上限（按 {@code source} 去重，data-model.md §2.6）。
     * {@code null} 或 {@code <= 0} = 不强制（缺省放行）—— 兼容未注入配置的测试 / SELECTING
     * 阶段。生产路径由 {@code SessionManager} 经 {@link #setMaxImagesPerWall(int)} 注入
     * {@code HikariCanvasConfig.ImageConfig.maxPerWall()}。强制点必须在此（element.add type=image
     * 才真正把图片挂到 wall；上传仅入磁盘 / DB，详见 ImageQuotaService javadoc）。
     */
    private volatile Integer maxImagesPerWall;

    /**
     * 0.7.2-P2（F10）：单 wall 元素总数上限（克隆元素时强制）。{@code null} 或 {@code <= 0} =
     * 不强制（缺省放行）—— 兼容未注入配置的测试 / SELECTING 阶段。生产路径由
     * {@code SessionManager} 经 {@link #setMaxElementsPerWall(int)} 注入
     * {@code HikariCanvasConfig.ScriptsConfig.maxElementsPerWall()}。强制点在 {@link #cloneElement}
     * （脚本反复克隆是唯一能无界堆元素的入口；普通 element.add 由前端交互天然有界，不在此强制
     * 以免限制正常编辑）。
     */
    private volatile Integer maxElementsPerWall;

    /** T11 历史栈（past + future + commit/undo/redo/mark）。2026-05-14 抽出。 */
    private final HistoryStack history;

    /** layer.* op 模块（create / delete / update / reorder / duplicate / set-active）。2026-05-14 抽出。 */
    private final LayerOperations layerOps;

    /** M12 brush.* op 状态机模块。2026-05-14 抽出。 */
    private final BrushSession brushOps;

    /** 0.6 P1：timeline.* / keyframe.* op 模块。 */
    private final TimelineOperations timelineOps;

    public EditSession(ProjectState state) {
        this.state = state;
        this.history = new HistoryStack(state);
        this.layerOps = new LayerOperations(state, history);
        this.brushOps = new BrushSession(state, history);
        this.timelineOps = new TimelineOperations(state, history);
    }

    public ProjectState state() {
        return state;
    }

    /**
     * P2-8：注入单 wall image 元素数上限（按 {@code source} 去重）。{@code <= 0} = 不限。
     * 由 {@code SessionManager} 在 confirm / open 构造 EditSession 后调用，传入
     * {@code HikariCanvasConfig.ImageConfig.maxPerWall()}。未调用时字段为 null → 放行
     * （测试 / 老路径行为不变）。
     */
    public void setMaxImagesPerWall(int maxPerWall) {
        this.maxImagesPerWall = maxPerWall;
    }

    /**
     * 0.7.2-P2（F10）：注入单 wall 元素总数上限。{@code <= 0} = 不限。由 {@code SessionManager}
     * 在 confirm / open 构造 EditSession 后调用（路径 A 活跃 session），以及 headless 路径
     * （{@code ElementPropertyApplier} 临时 EditSession）注入，传入
     * {@code HikariCanvasConfig.ScriptsConfig.maxElementsPerWall()}。未调用时字段为 null → 放行。
     */
    public void setMaxElementsPerWall(int max) {
        this.maxElementsPerWall = max;
    }

    /**
     * 0.6 P1：注入时间轴 fps 配置（config 段 {@code timeline}）。由 {@code SessionManager}
     * 在 confirm / open 构造 EditSession 后调用，传入
     * {@code HikariCanvasConfig.TimelineConfig.{defaultFps(), maxFps()}}。任一参数为 null
     * 时该项退回 {@link TimelineOperations} 的常量缺省（20 / 60），与未注入行为一致
     * （测试 / 老路径不受影响）。
     */
    public void setTimelineFpsLimits(Integer defaultFps, Integer maxFps) {
        this.timelineOps.setFpsLimits(defaultFps, maxFps);
    }

    // ---------- 结果类型 ----------

    public sealed interface OpResult {
        /**
         * 普通 op：下行 {@code state.patch}。
         *
         * @param patch 下行给前端的 state.patch（空 ops 表示"仅 version 推进"）
         * @param dirty 受影响的画布矩形；{@code null} = 无像素变化（如 canvas.resize no-op）
         */
        record Ok(StatePatch patch, DirtyRegion dirty) implements OpResult {}

        /**
         * M12 brush.start 专用结果：ack 中需要带 {@code strokeId} 回前端，且无 patch / dirty。
         * 其他 brush.* op（point / end / cancel）走 {@link Ok} 路径。
         */
        record OkBrushStart(String strokeId) implements OpResult {}

        /**
         * 结构性跳变（undo/redo/template.apply）：下行 {@code state.snapshot} 全量状态。
         * patch 在这种跳变里无法用 JSON Patch 简洁表达，直接发全量更可靠。
         *
         * @param version 跳变后新的 {@link ProjectState#version()}
         * @param dirty   像素层面受影响的区域；跳变一般 = full canvas
         */
        record OkSnapshot(long version, DirtyRegion dirty) implements OpResult {}

        record Error(String code, String message) implements OpResult {}
    }

    // ---------- 内部反查 ----------

    /** 元素在 {@link ProjectState} 树里的定位：层索引 + 层内索引 + 引用。 */
    private record Locator(int layerIdx, int elementIdx, Layer layer, Element element) {}

    private Locator findElement(String elementId) {
        if (elementId == null) return null;
        List<Layer> ls = state.layers();
        for (int li = 0; li < ls.size(); li++) {
            List<Element> es = ls.get(li).elements();
            for (int ei = 0; ei < es.size(); ei++) {
                if (es.get(ei).id().equals(elementId)) {
                    return new Locator(li, ei, ls.get(li), es.get(ei));
                }
            }
        }
        return null;
    }

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

    private static String elementFieldPath(int layerIdx, int elIdx, String field) {
        return "/layers/" + layerIdx + "/elements/" + elIdx + "/" + field;
    }

    // layerPath / layerFieldPath 已整体迁出至 {@link LayerOperations}。

    // ---------- element.add ----------

    /**
     * 新增元素。{@code afterId} 为 null 时追加到末尾；否则插入到该元素之后。
     * {@code layerId} 为 null 时落到 activeLayer。
     *
     * @return {@code INVALID_ELEMENT} 若 {@code afterId} 指向不存在的元素；
     *         {@code LAYER_NOT_FOUND} 若 {@code layerId} 不存在；
     *         {@code LAYER_LOCKED} 若目标层被锁
     */
    public synchronized OpResult addElement(String type, Map<String, Object> props,
                                            String afterId, String layerId) {
        if (type == null) return err("INVALID_PAYLOAD", "element type missing");
        if (props == null) props = Map.of();

        Layer target;
        int layerIdx;
        if (layerId == null || layerId.isEmpty()) {
            target = state.activeLayer();
            layerIdx = findLayerIdx(target.id());
        } else {
            layerIdx = findLayerIdx(layerId);
            if (layerIdx < 0) return err("LAYER_NOT_FOUND", "layer not found: " + layerId);
            target = state.layers().get(layerIdx);
        }
        if (target.locked()) return err("LAYER_LOCKED", "target layer is locked: " + target.id());

        int insertIdx;
        if (afterId == null || afterId.isEmpty()) {
            insertIdx = target.elements().size();
        } else {
            int afterIdxInLayer = indexOfElementInLayer(target, afterId);
            if (afterIdxInLayer < 0) {
                return err("INVALID_ELEMENT", "after element not found in target layer: " + afterId);
            }
            insertIdx = afterIdxInLayer + 1;
        }

        String id = "e-" + UUID.randomUUID();
        Element element;
        try {
            element = switch (type) {
                case "text" -> buildText(id, props);
                case "rect" -> buildRect(id, props);
                case "path" -> buildPath(id, props);
                case "circle" -> buildCircle(id, props);
                case "shape" -> buildShape(id, props);
                case "image" -> buildImage(id, props);
                case "icon" -> buildIcon(id, props);
                default -> throw new ValidationException("INVALID_ELEMENT", "unknown element type: " + type);
            };
        } catch (ValidationException ve) {
            return err(ve.code, ve.getMessage());
        }

        // P2-8：服务端强制 images.max-per-wall（前端建议值之外的硬约束，防脚本客户端用
        // element.add type=image 在单 wall 堆叠任意多 image 元素拖慢 rasterize）。统计目标 wall
        // 跨所有 layer 的 image 元素，按 source 去重（data-model.md §2.6 v1 简化口径）；新增的
        // source 若是全新文件且现有去重数已达上限则拒。强制点在此而非上传路径——上传仅入磁盘 / DB，
        // 真正挂到 wall 的是本 op（见 ImageQuotaService javadoc）。maxImagesPerWall 未注入（null）
        // 或 <= 0 时放行。
        if (element instanceof ImageElement imgEl) {
            OpResult quotaReject = checkImagePerWallQuota(imgEl.source());
            if (quotaReject != null) return quotaReject;
        }

        ProjectSnapshot pre = snapshotNow();
        target.elements().add(insertIdx, element);
        commitHistory(pre);
        long v = state.bumpVersion();

        StatePatch patch = new StatePatchBuilder()
                .add(elementPath(layerIdx, insertIdx), element)
                .build(v);
        return new OpResult.Ok(patch, DirtyRegion.of(element));
    }

    private static int indexOfElementInLayer(Layer l, String elementId) {
        List<Element> es = l.elements();
        for (int i = 0; i < es.size(); i++) {
            if (es.get(i).id().equals(elementId)) return i;
        }
        return -1;
    }

    /**
     * P2-8：单 wall image 元素配额校验（按 {@code source} 去重）。
     *
     * <p>返回非 null 即拒绝（{@code QUOTA_PER_WALL}）；返回 null 表示放行。{@code maxImagesPerWall}
     * 为 null 或 {@code <= 0} 时不限。统计当前工程跨所有 layer 的 ImageElement 去重 source 集合：
     * 若 {@code incomingSource} 已存在于集合（复用已引用文件，不增加去重数）则始终放行；否则当
     * 现有去重数 {@code >= max} 时拒（新增这一个会越界）。与 {@code ImageQuotaService.check} 的
     * {@code imagesInTargetWall >= maxPerWall} 口径一致。</p>
     */
    private OpResult checkImagePerWallQuota(String incomingSource) {
        Integer max = this.maxImagesPerWall;
        if (max == null || max <= 0) return null;
        java.util.Set<String> distinctSources = new java.util.HashSet<>();
        for (Layer l : state.layers()) {
            for (Element e : l.elements()) {
                if (e instanceof ImageElement im && im.source() != null) {
                    distinctSources.add(im.source());
                }
            }
        }
        // 复用已引用文件不增加去重数 → 放行（即便已达上限）。
        if (incomingSource != null && distinctSources.contains(incomingSource)) return null;
        if (distinctSources.size() >= max) {
            return err("QUOTA_PER_WALL",
                    "wall already references " + distinctSources.size()
                            + " image file(s); max " + max);
        }
        return null;
    }

    // ---------- element.update ----------

    /**
     * 字段级部分更新。{@code patch} 的每个 key 代表要修改的字段名，value 为新值。
     * 逐字段校验；失败时**不变更** state（all-or-nothing 语义）。
     *
     * <p>支持的字段：</p>
     * <ul>
     *   <li>共通：{@code x / y / w / h / rotation / locked / visible /
     *       opacity / blendMode / renderMode}（v2 新字段 M8-C 起接 patch）</li>
     *   <li>Text：{@code text / fontId / fontSize / color / align /
     *       letterSpacing / lineHeight / vertical / effects}</li>
     *   <li>Rect：{@code fill / stroke}</li>
     *   <li>Icon：{@code source / tint}</li>
     * </ul>
     */
    public synchronized OpResult updateElement(String elementId, Map<String, Object> patch) {
        if (elementId == null) return err("INVALID_PAYLOAD", "elementId missing");
        if (patch == null || patch.isEmpty()) return err("INVALID_PAYLOAD", "empty patch");

        Locator loc = findElement(elementId);
        if (loc == null) return err("INVALID_ELEMENT", "element not found: " + elementId);
        if (loc.layer.locked()) return err("LAYER_LOCKED", "owning layer is locked");

        Element updated;
        try {
            updated = switch (loc.element) {
                case TextElement t -> applyTextPatch(t, patch);
                case RectElement r -> applyRectPatch(r, patch);
                case IconElement ic -> applyIconPatch(ic, patch);
                case PathElement p -> applyPathPatch(p, patch);
                case CircleElement c -> applyCirclePatch(c, patch);
                case ShapeElement sh -> applyShapePatch(sh, patch);
                case BrushStrokeElement b -> applyBrushPatch(b, patch);
                case ImageElement im -> applyImagePatch(im, patch);
            };
        } catch (ValidationException ve) {
            return err(ve.code, ve.getMessage());
        }

        ProjectSnapshot pre = snapshotNow();
        loc.layer.elements().set(loc.elementIdx, updated);
        commitHistory(pre);
        long v = state.bumpVersion();

        StatePatchBuilder b = new StatePatchBuilder();
        for (var e : patch.entrySet()) {
            String path = elementFieldPath(loc.layerIdx, loc.elementIdx, e.getKey());
            Object value = e.getValue();
            if (value == null) {
                b.remove(path);
            } else {
                b.replace(path, value);
            }
        }
        // 脏矩形 = 旧 bbox ∪ 新 bbox，覆盖元素"从旧位移到新位"的扫过区域
        DirtyRegion dirty = DirtyRegion.of(loc.element).union(DirtyRegion.of(updated));
        return new OpResult.Ok(b.build(v), dirty);
    }

    // ---------- element.delete ----------

    public synchronized OpResult deleteElement(String elementId) {
        if (elementId == null) return err("INVALID_PAYLOAD", "elementId missing");
        Locator loc = findElement(elementId);
        if (loc == null) return err("INVALID_ELEMENT", "element not found: " + elementId);
        if (loc.layer.locked()) return err("LAYER_LOCKED", "owning layer is locked");

        ProjectSnapshot pre = snapshotNow();
        loc.layer.elements().remove(loc.elementIdx);
        commitHistory(pre);
        long v = state.bumpVersion();
        return new OpResult.Ok(
                new StatePatchBuilder().remove(elementPath(loc.layerIdx, loc.elementIdx)).build(v),
                DirtyRegion.of(loc.element));
    }

    // ---------- element.clone（0.7.2-P2 脚本克隆元素） ----------

    /**
     * 0.7.2-P2（F10）：克隆元素（新 id + 位置偏移 {@code offsetX/offsetY}），副本追加到
     * <b>源元素所在 layer 末尾</b>（非 activeLayer——脚本无"当前层"概念，跟随源元素最直观）。
     *
     * <p>复用 {@link #cloneElementWithNewId}（深拷贝 + 重生成 id）→ {@link #withOffset} 把
     * 副本 x/y 平移；x/y 超 {@code [-MAX_COORD, MAX_COORD]} 钳回边界（不拒绝——脚本批量克隆
     * 偏移累加易越界，钳位比报错友好且不破坏数据）。</p>
     *
     * <p>F10 配额：每墙元素总数达 {@code maxElementsPerWall} 时拒 {@code QUOTA_EXCEEDED}
     * （脚本反复克隆是唯一能无界堆元素的入口）。layer.locked 拒 {@code LAYER_LOCKED}。</p>
     *
     * @return {@code INVALID_ELEMENT} 元素不存在；{@code LAYER_LOCKED} 源层锁定；
     *         {@code QUOTA_EXCEEDED} 元素数超上限；否则 {@code Ok}（add patch）
     */
    public synchronized OpResult cloneElement(String elementId, int offsetX, int offsetY) {
        if (elementId == null) return err("INVALID_PAYLOAD", "elementId missing");
        Locator loc = findElement(elementId);
        if (loc == null) return err("INVALID_ELEMENT", "element not found: " + elementId);
        if (loc.layer.locked()) return err("LAYER_LOCKED", "owning layer is locked");
        Integer max = this.maxElementsPerWall;
        if (max != null && max > 0 && totalElements() >= max) {
            return err("QUOTA_EXCEEDED", "wall already has " + totalElements()
                    + " element(s); max " + max);
        }

        Element cloned = withOffset(cloneElementWithNewId(loc.element), offsetX, offsetY);

        ProjectSnapshot pre = snapshotNow();
        loc.layer.elements().add(cloned); // 追加到源元素所在 layer 末尾
        commitHistory(pre);
        long v = state.bumpVersion();

        int newIdx = loc.layer.elements().size() - 1;
        StatePatch patch = new StatePatchBuilder()
                .add(elementPath(loc.layerIdx, newIdx), cloned)
                .build(v);
        return new OpResult.Ok(patch, DirtyRegion.of(cloned));
    }

    /** 单 wall 跨所有 layer 的元素总数（F10 配额判定）。 */
    private int totalElements() {
        int n = 0;
        for (Layer l : state.layers()) {
            n += l.elements().size();
        }
        return n;
    }

    /**
     * 把元素 x/y 平移 {@code (dx, dy)}，钳回 {@code [-MAX_COORD, MAX_COORD]}（升 long 防 int
     * 回绕）。逐类型 record 重建（镜像 {@link #cloneElementWithNewId} 的 sealed switch，仅改
     * x/y，其余字段原样透传）。
     */
    private static Element withOffset(Element src, int dx, int dy) {
        int nx = (int) Math.max(-ElementValidator.MAX_COORD,
                Math.min(ElementValidator.MAX_COORD, (long) src.x() + dx));
        int ny = (int) Math.max(-ElementValidator.MAX_COORD,
                Math.min(ElementValidator.MAX_COORD, (long) src.y() + dy));
        if (nx == src.x() && ny == src.y()) return src; // 偏移 0（或全被钳到同值）免重建
        return switch (src) {
            case TextElement t -> new TextElement(t.id(),
                    nx, ny, t.w(), t.h(), t.rotation(), t.locked(), t.visible(),
                    t.text(), t.fontId(), t.fontSize(), t.color(), t.align(),
                    t.letterSpacing(), t.lineHeight(), t.vertical(), t.effects(),
                    t.opacity(), t.blendMode(), t.renderMode(),
                    t.bold(), t.italic());
            case RectElement r -> new RectElement(r.id(),
                    nx, ny, r.w(), r.h(), r.rotation(), r.locked(), r.visible(),
                    r.fill(), r.stroke(),
                    r.opacity(), r.blendMode(), r.renderMode());
            case IconElement ic -> new IconElement(ic.id(),
                    nx, ny, ic.w(), ic.h(), ic.rotation(), ic.locked(), ic.visible(),
                    ic.source(), ic.tint(),
                    ic.opacity(), ic.blendMode(), ic.renderMode(), ic.fill());
            case PathElement p -> new PathElement(p.id(),
                    nx, ny, p.w(), p.h(), p.rotation(), p.locked(), p.visible(),
                    p.d(), p.fill(), p.stroke(), p.markerStart(), p.markerEnd(),
                    p.opacity(), p.blendMode(), p.renderMode());
            case CircleElement c -> new CircleElement(c.id(),
                    nx, ny, c.w(), c.h(), c.rotation(), c.locked(), c.visible(),
                    c.fill(), c.stroke(),
                    c.opacity(), c.blendMode(), c.renderMode());
            case ShapeElement sh -> new ShapeElement(sh.id(),
                    nx, ny, sh.w(), sh.h(), sh.rotation(), sh.locked(), sh.visible(),
                    sh.kind(), sh.sides(), sh.innerRatio(),
                    sh.fill(), sh.stroke(),
                    sh.opacity(), sh.blendMode(), sh.renderMode());
            case BrushStrokeElement b -> new BrushStrokeElement(b.id(),
                    nx, ny, b.w(), b.h(), b.rotation(), b.locked(), b.visible(),
                    b.points(), b.size(), b.fill(),
                    b.pressureSize(), b.pressureOpacity(),
                    b.opacity(), b.blendMode(), b.renderMode());
            case ImageElement im -> new ImageElement(im.id(),
                    nx, ny, im.w(), im.h(), im.rotation(), im.locked(), im.visible(),
                    im.source(), im.mask(),
                    im.opacity(), im.blendMode(), im.renderMode());
        };
    }

    // ---------- element.reorder ----------

    /**
     * 把元素移动到所在层的 {@code newIndex} 位置（0 = 底层）。
     * 超出范围时 clamp 到 {@code [0, size-1]}。跨层用 {@link #moveElementToLayer}。
     */
    public synchronized OpResult reorderElement(String elementId, int newIndex) {
        if (elementId == null) return err("INVALID_PAYLOAD", "elementId missing");
        Locator loc = findElement(elementId);
        if (loc == null) return err("INVALID_ELEMENT", "element not found: " + elementId);
        if (loc.layer.locked()) return err("LAYER_LOCKED", "owning layer is locked");

        int size = loc.layer.elements().size();
        int to = Math.max(0, Math.min(newIndex, size - 1));
        if (to == loc.elementIdx) {
            // 无实际变化；仍 bump version 保持简单
            long v = state.bumpVersion();
            return new OpResult.Ok(new StatePatch(v, List.of()), null);
        }

        ProjectSnapshot pre = snapshotNow();
        Element moved = loc.layer.elements().remove(loc.elementIdx);
        loc.layer.elements().add(to, moved);
        commitHistory(pre);
        long v = state.bumpVersion();

        StatePatch patch = new StatePatchBuilder()
                .remove(elementPath(loc.layerIdx, loc.elementIdx))
                .add(elementPath(loc.layerIdx, to), moved)
                .build(v);
        return new OpResult.Ok(patch, DirtyRegion.of(moved));
    }

    /**
     * 0.7.3：把元素移到所在层末尾（front = 最后渲染 = 显示最上）。
     * 已在末尾则仍 bump version 返 Ok（幂等）。layer.locked → LAYER_LOCKED。
     * 元素不存在 → INVALID_ELEMENT。
     */
    public synchronized OpResult moveElementToFront(String elementId) {
        if (elementId == null) return err("INVALID_PAYLOAD", "elementId missing");
        Locator loc = findElement(elementId);
        if (loc == null) return err("INVALID_ELEMENT", "element not found: " + elementId);
        if (loc.layer.locked()) return err("LAYER_LOCKED", "owning layer is locked");
        return reorderElement(elementId, loc.layer.elements().size() - 1);
    }

    /**
     * 0.7.3：把元素移到所在层开头（back = 最先渲染 = 显示最下）。
     * 已在开头则仍 bump version 返 Ok（幂等）。layer.locked → LAYER_LOCKED。
     * 元素不存在 → INVALID_ELEMENT。
     */
    public synchronized OpResult moveElementToBack(String elementId) {
        if (elementId == null) return err("INVALID_PAYLOAD", "elementId missing");
        Locator loc = findElement(elementId);
        if (loc == null) return err("INVALID_ELEMENT", "element not found: " + elementId);
        if (loc.layer.locked()) return err("LAYER_LOCKED", "owning layer is locked");
        return reorderElement(elementId, 0);
    }

    // ---------- element.transform ----------

    /**
     * 几何变换特化 op：是 {@link #updateElement} 在 {@code {x,y,w,h,rotation}} 五字段上的等价调用。
     * 任一字段为 {@code null} = 不修改。
     */
    public synchronized OpResult transformElement(String elementId,
                                                  Integer x, Integer y,
                                                  Integer w, Integer h,
                                                  Integer rotation) {
        if (x == null && y == null && w == null && h == null && rotation == null) {
            return err("INVALID_PAYLOAD", "transform has no fields");
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        if (x != null) patch.put("x", x);
        if (y != null) patch.put("y", y);
        if (w != null) patch.put("w", w);
        if (h != null) patch.put("h", h);
        if (rotation != null) patch.put("rotation", rotation);
        return updateElement(elementId, patch);
    }

    // ---------- element.move-to-layer ----------

    /**
     * 跨层移动元素。{@code index == null} = 落到目标层底层（index 0），其他值 clamp 到合法范围。
     *
     * <p>错误码：{@code INVALID_ELEMENT} / {@code LAYER_NOT_FOUND} / {@code LAYER_LOCKED}
     * （源层或目标层任一锁住都拒）。源层 == 目标层等价于 {@link #reorderElement}，但允许调用，
     * 仅按层内 reorder 处理。</p>
     */
    public synchronized OpResult moveElementToLayer(String elementId, String targetLayerId,
                                                    Integer index) {
        if (elementId == null) return err("INVALID_PAYLOAD", "elementId missing");
        if (targetLayerId == null || targetLayerId.isEmpty()) {
            return err("INVALID_PAYLOAD", "targetLayerId missing");
        }
        Locator loc = findElement(elementId);
        if (loc == null) return err("INVALID_ELEMENT", "element not found: " + elementId);
        if (loc.layer.locked()) return err("LAYER_LOCKED", "source layer is locked");

        int targetIdx = findLayerIdx(targetLayerId);
        if (targetIdx < 0) return err("LAYER_NOT_FOUND", "target layer not found: " + targetLayerId);
        Layer target = state.layers().get(targetIdx);
        if (target.locked()) return err("LAYER_LOCKED", "target layer is locked");

        // 同层移动 = reorder（保证 patch 输出一致）
        if (loc.layerIdx == targetIdx) {
            int to = index == null ? 0 : Math.max(0,
                    Math.min(index, loc.layer.elements().size() - 1));
            return reorderElementUnsafe(loc, to);
        }

        int to = index == null ? 0 : Math.max(0, Math.min(index, target.elements().size()));

        ProjectSnapshot pre = snapshotNow();
        Element moved = loc.layer.elements().remove(loc.elementIdx);
        target.elements().add(to, moved);
        commitHistory(pre);
        long v = state.bumpVersion();

        StatePatch patch = new StatePatchBuilder()
                .remove(elementPath(loc.layerIdx, loc.elementIdx))
                .add(elementPath(targetIdx, to), moved)
                .build(v);
        return new OpResult.Ok(patch, DirtyRegion.of(moved));
    }

    /** 内部 helper：已经 findElement 过的 reorder，避免再扫一遍。 */
    private OpResult reorderElementUnsafe(Locator loc, int to) {
        if (to == loc.elementIdx) {
            long v = state.bumpVersion();
            return new OpResult.Ok(new StatePatch(v, List.of()), null);
        }
        ProjectSnapshot pre = snapshotNow();
        Element moved = loc.layer.elements().remove(loc.elementIdx);
        loc.layer.elements().add(to, moved);
        commitHistory(pre);
        long v = state.bumpVersion();
        StatePatch patch = new StatePatchBuilder()
                .remove(elementPath(loc.layerIdx, loc.elementIdx))
                .add(elementPath(loc.layerIdx, to), moved)
                .build(v);
        return new OpResult.Ok(patch, DirtyRegion.of(moved));
    }

    // ---------- layer.* op（实现见 {@link LayerOperations}） ----------

    /** 见 {@link LayerOperations#createLayer(String, String)}。 */
    public synchronized OpResult createLayer(String name, String afterLayerId) {
        return layerOps.createLayer(name, afterLayerId);
    }

    /** 见 {@link LayerOperations#deleteLayer(String)}。 */
    public synchronized OpResult deleteLayer(String layerId) {
        return layerOps.deleteLayer(layerId);
    }

    /** 见 {@link LayerOperations#updateLayer(String, Map)}。 */
    public synchronized OpResult updateLayer(String layerId, Map<String, Object> patch) {
        return layerOps.updateLayer(layerId, patch);
    }

    /** 见 {@link LayerOperations#reorderLayer(String, int)}。 */
    public synchronized OpResult reorderLayer(String layerId, int newIndex) {
        return layerOps.reorderLayer(layerId, newIndex);
    }

    /** 见 {@link LayerOperations#duplicateLayer(String)}。 */
    public synchronized OpResult duplicateLayer(String layerId) {
        return layerOps.duplicateLayer(layerId);
    }

    /** 见 {@link LayerOperations#setActiveLayer(String)}。 */
    public synchronized OpResult setActiveLayer(String layerId) {
        return layerOps.setActiveLayer(layerId);
    }

    // ---------- 0.6 P1：timeline.* / keyframe.* op（实现见 {@link TimelineOperations}） ----------
    //
    // timeline.play / pause / seek 留 P2（AnimationTicker 未建）：dispatcher 不加这些 case，
    // 落到现有 INVALID_OP 路径即可。

    /** 见 {@link TimelineOperations#createTimeline(String, Integer, Integer, String, Map)}。 */
    public synchronized OpResult createTimeline(String name, Integer durationMs, Integer fps,
                                                String loopMode, Map<String, Object> trigger) {
        return timelineOps.createTimeline(name, durationMs, fps, loopMode, trigger);
    }

    /** 见 {@link TimelineOperations#updateTimeline(String, Map)}。 */
    public synchronized OpResult updateTimeline(String timelineId, Map<String, Object> patch) {
        return timelineOps.updateTimeline(timelineId, patch);
    }

    /** 见 {@link TimelineOperations#deleteTimeline(String)}。 */
    public synchronized OpResult deleteTimeline(String timelineId) {
        return timelineOps.deleteTimeline(timelineId);
    }

    /** 见 {@link TimelineOperations#addKeyframe}。{@code coalesceKey} 可空（整体帧批量加帧合并撤销）。 */
    public synchronized OpResult addKeyframe(String timelineId, String elementId, String property,
                                             Integer timeMs, Object value, Object easing,
                                             String coalesceKey) {
        return timelineOps.addKeyframe(timelineId, elementId, property, timeMs, value, easing, coalesceKey);
    }

    /** 兼容旧签名（无 coalesceKey）：单帧加帧不合并撤销。 */
    public synchronized OpResult addKeyframe(String timelineId, String elementId, String property,
                                             Integer timeMs, Object value, Object easing) {
        return addKeyframe(timelineId, elementId, property, timeMs, value, easing, null);
    }

    /** 见 {@link TimelineOperations#updateKeyframe}。{@code coalesceKey} 可空（整体帧批量改值合并撤销）。 */
    public synchronized OpResult updateKeyframe(String timelineId, String keyframeId,
                                                Map<String, Object> patch, String coalesceKey) {
        return timelineOps.updateKeyframe(timelineId, keyframeId, patch, coalesceKey);
    }

    /** 兼容旧签名（无 coalesceKey）：单帧改值按默认单帧键合并。 */
    public synchronized OpResult updateKeyframe(String timelineId, String keyframeId,
                                                Map<String, Object> patch) {
        return updateKeyframe(timelineId, keyframeId, patch, null);
    }

    /** 见 {@link TimelineOperations#deleteKeyframe(String, String)}。 */
    public synchronized OpResult deleteKeyframe(String timelineId, String keyframeId) {
        return timelineOps.deleteKeyframe(timelineId, keyframeId);
    }

    /** 见 {@link TimelineOperations#moveKeyframe}。{@code coalesceKey} 可空（整体块拖动合并撤销）。 */
    public synchronized OpResult moveKeyframe(String timelineId, String keyframeId, Integer timeMs,
                                              String coalesceKey) {
        return timelineOps.moveKeyframe(timelineId, keyframeId, timeMs, coalesceKey);
    }

    /** 兼容旧签名（无 coalesceKey）：单帧移动按默认单帧键合并。 */
    public synchronized OpResult moveKeyframe(String timelineId, String keyframeId, Integer timeMs) {
        return moveKeyframe(timelineId, keyframeId, timeMs, null);
    }

    // ---------- canvas.resize ----------

    /**
     * M3 只接受尺寸等于当前值的 no-op resize（保持 op channel 通畅 + 前端试探能通过）。
     * 真正的动态扩缩容涉及 MapPool 借还和物品框增删，留给后续 milestone。
     */
    public synchronized OpResult resizeCanvas(int widthMaps, int heightMaps) {
        ProjectState.Canvas c = state.canvas();
        if (widthMaps != c.widthMaps() || heightMaps != c.heightMaps()) {
            return err("POOL_EXHAUSTED",
                    "canvas resize to " + widthMaps + "x" + heightMaps
                    + " not supported (wall fixed at " + c.widthMaps() + "x" + c.heightMaps() + ")");
        }
        long v = state.bumpVersion();
        return new OpResult.Ok(new StatePatch(v, List.of()), null);
    }

    // ---------- canvas.background ----------

    /**
     * 旧入口：{@code "#RRGGBB"} hex 字符串。M17 F5 起内部 wrap 成 {@link SolidFill} 后调
     * {@link #setBackground(Fill)}。WS op {@code canvas.background} 的 {@code color} 字段
     * 仍走这条；新协议字段 {@code fill} 走 Fill 直传。
     */
    public synchronized OpResult setBackground(String color) {
        if (!isValidColor(color)) return err("INVALID_PAYLOAD", "invalid color: " + color);
        return setBackground(new SolidFill(color));
    }

    /**
     * M17 F5：Fill 联合类型入口（solid / linear / radial）。{@link FillValidator} 校验后
     * 整体替换 canvas.background；dirty = 全画布、history mark。
     */
    public synchronized OpResult setBackground(Fill fill) {
        if (fill == null) return err("INVALID_PAYLOAD", "background fill is null");
        try {
            FillValidator.validate(fill);
        } catch (ValidationException ve) {
            return err(ve.code, ve.getMessage());
        }
        ProjectState.Canvas c = state.canvas();
        ProjectSnapshot pre = snapshotNow();
        state.canvas(new ProjectState.Canvas(c.widthMaps(), c.heightMaps(), fill,
                c.gridSize(), c.guides()));
        commitHistory(pre);
        long v = state.bumpVersion();
        return new OpResult.Ok(
                new StatePatchBuilder().replace("/canvas/background", fill).build(v),
                DirtyRegion.fullCanvas(state));
    }

    // ---------- canvas.tweenFps ----------

    /**
     * 0.7.1：per-wall 补间帧率。payload {@code fps} [1,60]，超范围 clamp + INVALID_PAYLOAD；
     * null / 0 → 清回默认（projectState.tweenFps = null → effectiveTweenFps() = 30）。
     * 不进 undo 历史（同 activeTimelineId 语义；属运行时配置非内容编辑）。
     * 广播 state.patch {@code /tweenFps}。
     */
    public synchronized OpResult setTweenFps(Integer fps) {
        if (fps != null && fps != 0 && (fps < 1 || fps > 60)) {
            return err("INVALID_PAYLOAD", "tweenFps out of range [1, 60]: " + fps);
        }
        Integer normalized = (fps == null || fps == 0) ? null : fps;
        state.tweenFps(normalized);
        long v = state.bumpVersion();
        return new OpResult.Ok(
                new StatePatchBuilder().replace("/tweenFps", normalized).build(v),
                null);
    }

    // ---------- canvas.grid ----------

    /** {@code size} = 0 / null → 关闭网格。 */
    public synchronized OpResult setGridSize(Integer size) {
        if (size != null && (size < 0 || size > 512)) {
            return err("INVALID_PAYLOAD", "gridSize out of range 0..512: " + size);
        }
        ProjectState.Canvas c = state.canvas();
        Integer normalized = (size == null || size == 0) ? null : size;
        ProjectSnapshot pre = snapshotNow();
        state.canvas(new ProjectState.Canvas(c.widthMaps(), c.heightMaps(),
                c.background(), normalized, c.guides()));
        commitHistory(pre);
        long v = state.bumpVersion();
        // gridSize 仅前端预览，不影响 MC 像素
        return new OpResult.Ok(
                new StatePatchBuilder().replace("/canvas/gridSize", normalized).build(v),
                null);
    }

    // ---------- canvas.guides.set ----------

    /** 整组替换 guides。空列表 / null 都清空。 */
    public synchronized OpResult setGuides(List<?> rawGuides) {
        if (rawGuides == null) rawGuides = List.of();
        if (rawGuides.size() > MAX_GUIDES) {
            return err("INVALID_PAYLOAD", "too many guides (max " + MAX_GUIDES + ")");
        }
        List<Guide> guides = new ArrayList<>(rawGuides.size());
        try {
            for (Object o : rawGuides) {
                if (!(o instanceof Map<?, ?> m)) {
                    throw new ValidationException("INVALID_PAYLOAD", "guide must be object");
                }
                Object ax = m.get("axis");
                if (!(ax instanceof String axisStr) || (!axisStr.equals("x") && !axisStr.equals("y"))) {
                    throw new ValidationException("INVALID_PAYLOAD",
                            "guide.axis must be 'x' or 'y'");
                }
                Object pos = m.get("position");
                if (!(pos instanceof Number pn)) {
                    throw new ValidationException("INVALID_PAYLOAD",
                            "guide.position must be number");
                }
                guides.add(new Guide(axisStr, pn.intValue()));
            }
        } catch (ValidationException ve) {
            return err(ve.code, ve.getMessage());
        }

        ProjectState.Canvas c = state.canvas();
        ProjectSnapshot pre = snapshotNow();
        state.canvas(new ProjectState.Canvas(c.widthMaps(), c.heightMaps(),
                c.background(), c.gridSize(), guides));
        commitHistory(pre);
        long v = state.bumpVersion();
        return new OpResult.Ok(
                new StatePatchBuilder().replace("/canvas/guides", guides).build(v),
                null);
    }

    // ---------- template.apply ----------

    /**
     * 替换 ProjectState 内容（背景 + 整 layers 树）。{@code template.apply} 的"replace 语义"。
     *
     * <p>M8-C 升级：清空所有层 → 生成一个 Default Layer 包住 {@code elements} → activeLayerId
     * 指向它。符合 {@code docs/architecture.md §10.5}。</p>
     *
     * <p>M17 F5：{@code backgroundColor} String 入口保留兼容（自动 wrap 为 SolidFill）；
     * 推荐新调用方走 {@link #replaceContent(Fill, List)} 直传 Fill。</p>
     *
     * @param backgroundColor 新背景色 hex（null = 保留当前）
     * @param elements        新 element 列表（已由 TemplateInstantiator 物化）
     */
    public synchronized OpResult replaceContent(String backgroundColor, List<Element> elements) {
        return replaceContent(backgroundColor == null ? null : new SolidFill(backgroundColor), elements);
    }

    /** M17 F5：Fill 入口的 replaceContent。{@code null} 保留当前 background。 */
    public synchronized OpResult replaceContent(Fill background, List<Element> elements) {
        ProjectSnapshot pre = snapshotNow();
        ProjectState.Canvas c = state.canvas();
        state.canvas(new ProjectState.Canvas(c.widthMaps(), c.heightMaps(),
                background == null ? c.background() : background,
                c.gridSize(), c.guides()));
        // 重建：单个 Default Layer 包新 elements，activeLayerId 指向它
        String newLayerId = "l-" + UUID.randomUUID().toString().substring(0, 8);
        Layer defLayer = new Layer(newLayerId, ProjectState.DEFAULT_LAYER_NAME,
                true, false, 1.0f, BlendMode.NORMAL,
                new ArrayList<>(elements == null ? List.of() : elements));
        state.replaceAllLayers(List.of(defLayer), newLayerId);
        commitHistory(pre);
        long v = state.bumpVersion();
        return new OpResult.OkSnapshot(v, DirtyRegion.fullCanvas(state));
    }

    // ---------- undo / redo / history.mark ----------

    /** 见 {@link HistoryStack#undo()}。 */
    public synchronized OpResult undo() {
        return history.undo();
    }

    /** 见 {@link HistoryStack#redo()}。 */
    public synchronized OpResult redo() {
        return history.redo();
    }

    /** 见 {@link HistoryStack#historyMark(String)}。 */
    public synchronized OpResult historyMark(String label) {
        return history.historyMark(label);
    }

    // ---------- 历史栈内部 helpers（thin delegates，保留以最小化 op 内调用点改动）----------

    private ProjectSnapshot snapshotNow() {
        return history.snapshotNow();
    }

    private void commitHistory(ProjectSnapshot preSnapshot) {
        history.commitHistory(preSnapshot);
    }

    // ---------- 构造与更新辅助 ----------

    private Element buildText(String id, Map<String, Object> p) {
        String text = requireString(p, "text", true);
        validateText(text);
        int x = intFieldOrDefault(p, "x", 0); validateCoord(x, "x");
        int y = intFieldOrDefault(p, "y", 0); validateCoord(y, "y");
        int w = intFieldOrDefault(p, "w", 128); validateDim(w, "w");
        int h = intFieldOrDefault(p, "h", 32); validateDim(h, "h");
        int rotation = intFieldOrDefault(p, "rotation", 0); validateRotation(rotation);
        boolean locked = boolFieldOrDefault(p, "locked", false);
        boolean visible = boolFieldOrDefault(p, "visible", true);
        String fontId = stringFieldOrDefault(p, "fontId", "ark_pixel");
        int fontSize = intFieldOrDefault(p, "fontSize", 12); validateFontSize(fontSize);
        String color = stringFieldOrDefault(p, "color", "#000000"); validateColor(color);
        String align = stringFieldOrDefault(p, "align", "left"); validateAlign(align);
        float letterSpacing = floatFieldOrDefault(p, "letterSpacing", 0f);
        validateLetterSpacing(letterSpacing);
        float lineHeight = floatFieldOrDefault(p, "lineHeight", 1.2f);
        validateLineHeight(lineHeight);
        boolean vertical = boolFieldOrDefault(p, "vertical", false);
        Effects effects = buildEffects(p.get("effects"));
        // 0.4.6 P3：bold / italic 字段（向下兼容 null）
        Boolean bold = boolFieldOrNull(p, "bold");
        Boolean italic = boolFieldOrNull(p, "italic");
        // Ultrareview 2026-05-25 #12：build 路径需保留 v2 BaseElement 通用字段
        // （opacity / blendMode / renderMode），否则 element.add 携带的视觉字段被服务端丢弃。
        Float opacity = parseOpacityNullable(p.get("opacity"));
        BlendMode blendMode = parseBlendModeNullable(p.get("blendMode"));
        RenderMode renderMode = parseRenderModeNullable(p.get("renderMode"));
        return new TextElement(id, x, y, w, h, rotation, locked, visible,
                text, fontId, fontSize, color, align,
                letterSpacing, lineHeight, vertical, effects,
                opacity, blendMode, renderMode,
                bold, italic);
    }

    private Element buildRect(String id, Map<String, Object> p) {
        int x = intFieldOrDefault(p, "x", 0); validateCoord(x, "x");
        int y = intFieldOrDefault(p, "y", 0); validateCoord(y, "y");
        int w = intFieldOrDefault(p, "w", 64); validateDim(w, "w");
        int h = intFieldOrDefault(p, "h", 64); validateDim(h, "h");
        int rotation = intFieldOrDefault(p, "rotation", 0); validateRotation(rotation);
        boolean locked = boolFieldOrDefault(p, "locked", false);
        boolean visible = boolFieldOrDefault(p, "visible", true);
        Fill fill = parseFillNullable(p.get("fill"));
        Stroke stroke = buildStroke(p.get("stroke"));
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "rect needs fill or non-zero stroke");
        }
        // Ultrareview 2026-05-25 #12：保留 v2 BaseElement 通用字段
        Float opacity = parseOpacityNullable(p.get("opacity"));
        BlendMode blendMode = parseBlendModeNullable(p.get("blendMode"));
        RenderMode renderMode = parseRenderModeNullable(p.get("renderMode"));
        return new RectElement(id, x, y, w, h, rotation, locked, visible, fill, stroke,
                opacity, blendMode, renderMode);
    }

    // ---------- M9 PathElement ----------

    private Element buildPath(String id, Map<String, Object> p) {
        int x = intFieldOrDefault(p, "x", 0); validateCoord(x, "x");
        int y = intFieldOrDefault(p, "y", 0); validateCoord(y, "y");
        int w = intFieldOrDefault(p, "w", 100); validateDim(w, "w");
        int h = intFieldOrDefault(p, "h", 100); validateDim(h, "h");
        int rotation = intFieldOrDefault(p, "rotation", 0); validateRotation(rotation);
        boolean locked = boolFieldOrDefault(p, "locked", false);
        boolean visible = boolFieldOrDefault(p, "visible", true);
        String d = requireString(p, "d", true);
        validatePathD(d);
        Fill fill = parseFillNullable(p.get("fill"));
        Stroke stroke = buildStroke(p.get("stroke"));
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "path needs fill or non-zero stroke");
        }
        String markerStart = parseMarkerNullable(p.get("markerStart"));
        String markerEnd = parseMarkerNullable(p.get("markerEnd"));
        // Ultrareview 2026-05-25 #12：保留 v2 BaseElement 通用字段
        Float opacity = parseOpacityNullable(p.get("opacity"));
        BlendMode blendMode = parseBlendModeNullable(p.get("blendMode"));
        RenderMode renderMode = parseRenderModeNullable(p.get("renderMode"));
        return new PathElement(id, x, y, w, h, rotation, locked, visible,
                d, fill, stroke, markerStart, markerEnd,
                opacity, blendMode, renderMode);
    }

    private PathElement applyPathPatch(PathElement orig, Map<String, Object> patch) {
        int x = orig.x(); int y = orig.y(); int w = orig.w(); int h = orig.h();
        int rotation = orig.rotation();
        boolean locked = orig.locked(); boolean visible = orig.visible();
        String d = orig.d();
        Fill fill = orig.fill();
        Stroke stroke = orig.stroke();
        String markerStart = orig.markerStart();
        String markerEnd = orig.markerEnd();
        Float opacity = orig.opacity();
        BlendMode blendMode = orig.blendMode();
        RenderMode renderMode = orig.renderMode();

        for (var e : patch.entrySet()) {
            String k = e.getKey(); Object v = e.getValue();
            switch (k) {
                case "x" -> { x = intValue(v, k); validateCoord(x, k); }
                case "y" -> { y = intValue(v, k); validateCoord(y, k); }
                case "w" -> { w = intValue(v, k); validateDim(w, k); }
                case "h" -> { h = intValue(v, k); validateDim(h, k); }
                case "rotation" -> { rotation = intValue(v, k); validateRotation(rotation); }
                case "locked" -> locked = boolValue(v, k);
                case "visible" -> visible = boolValue(v, k);
                case "d" -> { d = requireStringValue(v, k); validatePathD(d); }
                case "fill" -> fill = parseFillNullable(v);
                case "stroke" -> stroke = buildStroke(v);
                case "markerStart" -> markerStart = parseMarkerNullable(v);
                case "markerEnd" -> markerEnd = parseMarkerNullable(v);
                case "opacity" -> opacity = parseOpacityNullable(v);
                case "blendMode" -> blendMode = parseBlendModeNullable(v);
                case "renderMode" -> renderMode = parseRenderModeNullable(v);
                default -> throw new ValidationException("INVALID_PAYLOAD",
                        "unknown path field: " + k);
            }
        }
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "path needs fill or non-zero stroke");
        }
        return new PathElement(orig.id(), x, y, w, h, rotation, locked, visible,
                d, fill, stroke, markerStart, markerEnd,
                opacity, blendMode, renderMode);
    }

    // ---------- M9 CircleElement ----------

    private Element buildCircle(String id, Map<String, Object> p) {
        int x = intFieldOrDefault(p, "x", 0); validateCoord(x, "x");
        int y = intFieldOrDefault(p, "y", 0); validateCoord(y, "y");
        int w = intFieldOrDefault(p, "w", 64); validateDim(w, "w");
        int h = intFieldOrDefault(p, "h", 64); validateDim(h, "h");
        int rotation = intFieldOrDefault(p, "rotation", 0); validateRotation(rotation);
        boolean locked = boolFieldOrDefault(p, "locked", false);
        boolean visible = boolFieldOrDefault(p, "visible", true);
        Fill fill = parseFillNullable(p.get("fill"));
        Stroke stroke = buildStroke(p.get("stroke"));
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "circle needs fill or non-zero stroke");
        }
        // Ultrareview 2026-05-25 #12：保留 v2 BaseElement 通用字段
        Float opacity = parseOpacityNullable(p.get("opacity"));
        BlendMode blendMode = parseBlendModeNullable(p.get("blendMode"));
        RenderMode renderMode = parseRenderModeNullable(p.get("renderMode"));
        return new CircleElement(id, x, y, w, h, rotation, locked, visible,
                fill, stroke,
                opacity, blendMode, renderMode);
    }

    private CircleElement applyCirclePatch(CircleElement orig, Map<String, Object> patch) {
        int x = orig.x(); int y = orig.y(); int w = orig.w(); int h = orig.h();
        int rotation = orig.rotation();
        boolean locked = orig.locked(); boolean visible = orig.visible();
        Fill fill = orig.fill();
        Stroke stroke = orig.stroke();
        Float opacity = orig.opacity();
        BlendMode blendMode = orig.blendMode();
        RenderMode renderMode = orig.renderMode();

        for (var e : patch.entrySet()) {
            String k = e.getKey(); Object v = e.getValue();
            switch (k) {
                case "x" -> { x = intValue(v, k); validateCoord(x, k); }
                case "y" -> { y = intValue(v, k); validateCoord(y, k); }
                case "w" -> { w = intValue(v, k); validateDim(w, k); }
                case "h" -> { h = intValue(v, k); validateDim(h, k); }
                case "rotation" -> { rotation = intValue(v, k); validateRotation(rotation); }
                case "locked" -> locked = boolValue(v, k);
                case "visible" -> visible = boolValue(v, k);
                case "fill" -> fill = parseFillNullable(v);
                case "stroke" -> stroke = buildStroke(v);
                case "opacity" -> opacity = parseOpacityNullable(v);
                case "blendMode" -> blendMode = parseBlendModeNullable(v);
                case "renderMode" -> renderMode = parseRenderModeNullable(v);
                default -> throw new ValidationException("INVALID_PAYLOAD",
                        "unknown circle field: " + k);
            }
        }
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "circle needs fill or non-zero stroke");
        }
        return new CircleElement(orig.id(), x, y, w, h, rotation, locked, visible,
                fill, stroke,
                opacity, blendMode, renderMode);
    }

    // ---------- M9 ShapeElement ----------

    private Element buildShape(String id, Map<String, Object> p) {
        int x = intFieldOrDefault(p, "x", 0); validateCoord(x, "x");
        int y = intFieldOrDefault(p, "y", 0); validateCoord(y, "y");
        int w = intFieldOrDefault(p, "w", 80); validateDim(w, "w");
        int h = intFieldOrDefault(p, "h", 80); validateDim(h, "h");
        int rotation = intFieldOrDefault(p, "rotation", 0); validateRotation(rotation);
        boolean locked = boolFieldOrDefault(p, "locked", false);
        boolean visible = boolFieldOrDefault(p, "visible", true);
        String kind = stringFieldOrDefault(p, "kind", "polygon");
        validateShapeKind(kind);
        int sides = intFieldOrDefault(p, "sides", kind.equals("star") ? 5 : 6);
        validateSides(sides);
        Float innerRatio = null;
        Object irRaw = p.get("innerRatio");
        if (irRaw != null) {
            innerRatio = ((Number) irRaw).floatValue();
            validateInnerRatio(innerRatio);
        }
        Fill fill = parseFillNullable(p.get("fill"));
        Stroke stroke = buildStroke(p.get("stroke"));
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "shape needs fill or non-zero stroke");
        }
        // Ultrareview 2026-05-25 #12：保留 v2 BaseElement 通用字段
        Float opacity = parseOpacityNullable(p.get("opacity"));
        BlendMode blendMode = parseBlendModeNullable(p.get("blendMode"));
        RenderMode renderMode = parseRenderModeNullable(p.get("renderMode"));
        return new ShapeElement(id, x, y, w, h, rotation, locked, visible,
                kind, sides, innerRatio,
                fill, stroke,
                opacity, blendMode, renderMode);
    }

    private ShapeElement applyShapePatch(ShapeElement orig, Map<String, Object> patch) {
        int x = orig.x(); int y = orig.y(); int w = orig.w(); int h = orig.h();
        int rotation = orig.rotation();
        boolean locked = orig.locked(); boolean visible = orig.visible();
        String kind = orig.kind();
        int sides = orig.sides();
        Float innerRatio = orig.innerRatio();
        Fill fill = orig.fill();
        Stroke stroke = orig.stroke();
        Float opacity = orig.opacity();
        BlendMode blendMode = orig.blendMode();
        RenderMode renderMode = orig.renderMode();

        for (var e : patch.entrySet()) {
            String k = e.getKey(); Object v = e.getValue();
            switch (k) {
                case "x" -> { x = intValue(v, k); validateCoord(x, k); }
                case "y" -> { y = intValue(v, k); validateCoord(y, k); }
                case "w" -> { w = intValue(v, k); validateDim(w, k); }
                case "h" -> { h = intValue(v, k); validateDim(h, k); }
                case "rotation" -> { rotation = intValue(v, k); validateRotation(rotation); }
                case "locked" -> locked = boolValue(v, k);
                case "visible" -> visible = boolValue(v, k);
                case "kind" -> { kind = requireStringValue(v, k); validateShapeKind(kind); }
                case "sides" -> { sides = intValue(v, k); validateSides(sides); }
                case "innerRatio" -> {
                    if (v == null) innerRatio = null;
                    else { innerRatio = floatValue(v, k); validateInnerRatio(innerRatio); }
                }
                case "fill" -> fill = parseFillNullable(v);
                case "stroke" -> stroke = buildStroke(v);
                case "opacity" -> opacity = parseOpacityNullable(v);
                case "blendMode" -> blendMode = parseBlendModeNullable(v);
                case "renderMode" -> renderMode = parseRenderModeNullable(v);
                default -> throw new ValidationException("INVALID_PAYLOAD",
                        "unknown shape field: " + k);
            }
        }
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "shape needs fill or non-zero stroke");
        }
        return new ShapeElement(orig.id(), x, y, w, h, rotation, locked, visible,
                kind, sides, innerRatio,
                fill, stroke,
                opacity, blendMode, renderMode);
    }

    // ---------- M12 BrushStrokeElement op handlers（实现见 {@link BrushSession}） ----------

    /** 见 {@link BrushSession#startBrush(Map, String)}。 */
    public synchronized OpResult startBrush(Map<String, Object> props, String layerId) {
        return brushOps.startBrush(props, layerId);
    }

    /** 见 {@link BrushSession#appendBrushPoints(String, List)}。 */
    public synchronized OpResult appendBrushPoints(String strokeId, List<BrushPoint> points) {
        return brushOps.appendBrushPoints(strokeId, points);
    }

    /** 见 {@link BrushSession#endBrush(String)}。 */
    public synchronized OpResult endBrush(String strokeId) {
        return brushOps.endBrush(strokeId);
    }

    /** 见 {@link BrushSession#cancelBrush(String)}。 */
    public synchronized OpResult cancelBrush(String strokeId) {
        return brushOps.cancelBrush(strokeId);
    }

    /**
     * 清理超过 idle 阈值的笔触 buffer。{@link moe.hikari.canvas.session.SessionReaper}
     * 定时调用本接口。
     */
    public synchronized void purgeStaleStrokes() {
        brushOps.purgeStaleStrokes();
    }

    // ---------- 测试 hook（package-private，仅 test 包内访问）----------

    /** 当前活跃 stroke 数。EditSessionBrushPurgeTest 用。 */
    int activeStrokeCountForTest() {
        return brushOps.activeStrokeCountForTest();
    }

    /** 把某 stroke 的 lastActivityMs 强制设置为 ms。purgeStaleStrokes 回归测试用。 */
    void overrideStrokeActivityForTest(String strokeId, long ms) {
        brushOps.overrideStrokeActivityForTest(strokeId, ms);
    }

    private BrushStrokeElement applyBrushPatch(BrushStrokeElement orig, Map<String, Object> patch) {
        int x = orig.x(); int y = orig.y();
        int rotation = orig.rotation();
        boolean locked = orig.locked(); boolean visible = orig.visible();
        Float opacity = orig.opacity();
        BlendMode blendMode = orig.blendMode();
        RenderMode renderMode = orig.renderMode();
        Fill fill = orig.fill();
        boolean pressureSize = orig.pressureSize();
        boolean pressureOpacity = orig.pressureOpacity();
        int size = orig.size();

        for (var e : patch.entrySet()) {
            String k = e.getKey(); Object v = e.getValue();
            switch (k) {
                case "x" -> { x = intValue(v, k); validateCoord(x, k); }
                case "y" -> { y = intValue(v, k); validateCoord(y, k); }
                case "rotation" -> { rotation = intValue(v, k); validateRotation(rotation); }
                case "locked" -> locked = boolValue(v, k);
                case "visible" -> visible = boolValue(v, k);
                case "size" -> { size = intValue(v, k); validateBrushSize(size); }
                case "fill" -> {
                    fill = parseFillNullable(v);
                    if (fill == null) {
                        throw new ValidationException("INVALID_PAYLOAD", "brush.fill must be non-null");
                    }
                }
                case "pressureSize" -> pressureSize = boolValue(v, k);
                case "pressureOpacity" -> pressureOpacity = boolValue(v, k);
                case "opacity" -> opacity = parseOpacityNullable(v);
                case "blendMode" -> blendMode = parseBlendModeNullable(v);
                case "renderMode" -> renderMode = parseRenderModeNullable(v);
                default -> throw new ValidationException("INVALID_PAYLOAD",
                        "unknown brush field: " + k);
            }
        }
        return new BrushStrokeElement(orig.id(), x, y, orig.w(), orig.h(), rotation,
                locked, visible, orig.points(), size, fill, pressureSize, pressureOpacity,
                opacity, blendMode, renderMode);
    }

    // ---------- 共享 helpers ----------

    private TextElement applyTextPatch(TextElement t, Map<String, Object> patch) {
        String text = t.text();
        int x = t.x(); int y = t.y(); int w = t.w(); int h = t.h();
        int rotation = t.rotation();
        boolean locked = t.locked(); boolean visible = t.visible();
        String fontId = t.fontId(); int fontSize = t.fontSize();
        String color = t.color(); String align = t.align();
        float letterSpacing = t.letterSpacing();
        float lineHeight = t.lineHeight();
        boolean vertical = t.vertical();
        Effects effects = t.effects();
        Float opacity = t.opacity();
        BlendMode blendMode = t.blendMode();
        RenderMode renderMode = t.renderMode();
        Boolean bold = t.bold();
        Boolean italic = t.italic();

        for (var e : patch.entrySet()) {
            String k = e.getKey(); Object v = e.getValue();
            switch (k) {
                case "text" -> { text = requireStringValue(v, k); validateText(text); }
                case "x" -> { x = intValue(v, k); validateCoord(x, k); }
                case "y" -> { y = intValue(v, k); validateCoord(y, k); }
                case "w" -> { w = intValue(v, k); validateDim(w, k); }
                case "h" -> { h = intValue(v, k); validateDim(h, k); }
                case "rotation" -> { rotation = intValue(v, k); validateRotation(rotation); }
                case "locked" -> locked = boolValue(v, k);
                case "visible" -> visible = boolValue(v, k);
                case "fontId" -> fontId = requireStringValue(v, k);
                case "fontSize" -> { fontSize = intValue(v, k); validateFontSize(fontSize); }
                case "color" -> { color = requireStringValue(v, k); validateColor(color); }
                case "align" -> { align = requireStringValue(v, k); validateAlign(align); }
                case "letterSpacing" -> {
                    letterSpacing = floatValue(v, k); validateLetterSpacing(letterSpacing);
                }
                case "lineHeight" -> {
                    lineHeight = floatValue(v, k); validateLineHeight(lineHeight);
                }
                case "vertical" -> vertical = boolValue(v, k);
                case "effects" -> effects = buildEffects(v);
                case "opacity" -> opacity = parseOpacityNullable(v);
                case "blendMode" -> blendMode = parseBlendModeNullable(v);
                case "renderMode" -> renderMode = parseRenderModeNullable(v);
                // 0.4.6 P3：bold / italic patch 字段（null 视同 false / 关闭）
                case "bold" -> bold = (v == null) ? null : boolValue(v, k);
                case "italic" -> italic = (v == null) ? null : boolValue(v, k);
                default -> throw new ValidationException("INVALID_PAYLOAD",
                        "unknown text field: " + k);
            }
        }
        return new TextElement(t.id(), x, y, w, h, rotation, locked, visible,
                text, fontId, fontSize, color, align,
                letterSpacing, lineHeight, vertical, effects,
                opacity, blendMode, renderMode, bold, italic);
    }

    private RectElement applyRectPatch(RectElement r, Map<String, Object> patch) {
        int x = r.x(); int y = r.y(); int w = r.w(); int h = r.h();
        int rotation = r.rotation();
        boolean locked = r.locked(); boolean visible = r.visible();
        Fill fill = r.fill();
        Stroke stroke = r.stroke();
        Float opacity = r.opacity();
        BlendMode blendMode = r.blendMode();
        RenderMode renderMode = r.renderMode();

        for (var e : patch.entrySet()) {
            String k = e.getKey(); Object v = e.getValue();
            switch (k) {
                case "x" -> { x = intValue(v, k); validateCoord(x, k); }
                case "y" -> { y = intValue(v, k); validateCoord(y, k); }
                case "w" -> { w = intValue(v, k); validateDim(w, k); }
                case "h" -> { h = intValue(v, k); validateDim(h, k); }
                case "rotation" -> { rotation = intValue(v, k); validateRotation(rotation); }
                case "locked" -> locked = boolValue(v, k);
                case "visible" -> visible = boolValue(v, k);
                case "fill" -> fill = parseFillNullable(v);
                case "stroke" -> stroke = buildStroke(v);
                case "opacity" -> opacity = parseOpacityNullable(v);
                case "blendMode" -> blendMode = parseBlendModeNullable(v);
                case "renderMode" -> renderMode = parseRenderModeNullable(v);
                default -> throw new ValidationException("INVALID_PAYLOAD",
                        "unknown rect field: " + k);
            }
        }
        if (fill == null && (stroke == null || stroke.width() == 0)) {
            throw new ValidationException("INVALID_ELEMENT", "rect needs fill or non-zero stroke");
        }
        return new RectElement(r.id(), x, y, w, h, rotation, locked, visible, fill, stroke,
                opacity, blendMode, renderMode);
    }

    // ---------- M7 IconElement（M26 升级矢量 + Fill 联合类型） ----------

    /**
     * 构造 IconElement。M26 升级后 source 走 {@link IconElement#isValidSource}（pack/name 形态
     * 或 legacy 单段），fill 优先于 tint：
     * <ul>
     *   <li>同次 add 给了 {@code fill} → 走 {@link FillValidator}；{@code tint} 仅作为冗余字段
     *       存入 record（向后兼容旧客户端读取）</li>
     *   <li>没 fill 但有 {@code tint}（旧客户端 / 旧模板） → 自动 wrap {@link SolidFill}</li>
     *   <li>两者都没 → fill = null（IconRenderer 退回 pack 默认色 / 黑）</li>
     * </ul>
     *
     * <p>M26-B（2026-05-17）补丁：M26.1 加 IconElement record 时漏改 addElement switch，所有
     * {@code element.add type=icon} fall through default 返 {@code INVALID_ELEMENT}。本方法补上。</p>
     */
    private Element buildIcon(String id, Map<String, Object> p) {
        int x = intFieldOrDefault(p, "x", 0); validateCoord(x, "x");
        int y = intFieldOrDefault(p, "y", 0); validateCoord(y, "y");
        int w = intFieldOrDefault(p, "w", 32); validateDim(w, "w");
        int h = intFieldOrDefault(p, "h", 32); validateDim(h, "h");
        int rotation = intFieldOrDefault(p, "rotation", 0); validateRotation(rotation);
        boolean locked = boolFieldOrDefault(p, "locked", false);
        boolean visible = boolFieldOrDefault(p, "visible", true);
        String source = requireString(p, "source", true);
        if (!IconElement.isValidSource(source)) {
            throw new ValidationException("INVALID_PAYLOAD",
                    "icon source must match " + IconElement.SOURCE_RE.pattern()
                            + " (≤" + IconElement.SOURCE_MAX_LEN + "): " + source);
        }
        // tint legacy 兼容：若 props 提供 tint，校验 + 留作 record 字段
        String tint = null;
        if (p.containsKey("tint") && p.get("tint") != null) {
            tint = requireStringValue(p.get("tint"), "tint");
            validateColor(tint);
        }
        // fill 优先；缺则从 tint 升级；两者都缺则 null（pack 默认色）
        Fill fill = parseFillNullable(p.get("fill"));
        if (fill == null && tint != null) {
            fill = new SolidFill(tint);
        }
        Float opacity = parseOpacityNullable(p.get("opacity"));
        BlendMode blendMode = parseBlendModeNullable(p.get("blendMode"));
        RenderMode renderMode = parseRenderModeNullable(p.get("renderMode"));
        return new IconElement(id, x, y, w, h, rotation, locked, visible,
                source, tint, opacity, blendMode, renderMode, fill);
    }

    private IconElement applyIconPatch(IconElement ic, Map<String, Object> patch) {
        int x = ic.x(); int y = ic.y(); int w = ic.w(); int h = ic.h();
        int rotation = ic.rotation();
        boolean locked = ic.locked(); boolean visible = ic.visible();
        String source = ic.source();
        String tint = ic.tint();
        Float opacity = ic.opacity();
        BlendMode blendMode = ic.blendMode();
        RenderMode renderMode = ic.renderMode();
        Fill fill = ic.fill();

        for (var e : patch.entrySet()) {
            String k = e.getKey(); Object v = e.getValue();
            switch (k) {
                case "x" -> { x = intValue(v, k); validateCoord(x, k); }
                case "y" -> { y = intValue(v, k); validateCoord(y, k); }
                case "w" -> { w = intValue(v, k); validateDim(w, k); }
                case "h" -> { h = intValue(v, k); validateDim(h, k); }
                case "rotation" -> { rotation = intValue(v, k); validateRotation(rotation); }
                case "locked" -> locked = boolValue(v, k);
                case "visible" -> visible = boolValue(v, k);
                case "source" -> {
                    source = requireStringValue(v, k);
                    if (!IconElement.isValidSource(source)) {
                        throw new ValidationException("INVALID_PAYLOAD",
                                "icon source must match " + IconElement.SOURCE_RE.pattern()
                                        + " (≤" + IconElement.SOURCE_MAX_LEN + "): " + source);
                    }
                }
                // M26 deprecated：仍接受 tint 入参（兼容旧客户端 patch），并同步升级到 fill
                case "tint" -> {
                    if (v == null) { tint = null; }
                    else {
                        tint = requireStringValue(v, k); validateColor(tint);
                        // tint 入参等价于 SolidFill；若同次 patch 也带了 fill，下面 fill 分支覆盖之
                        fill = new SolidFill(tint);
                    }
                }
                // M26：新协议入口
                case "fill" -> fill = parseFillNullable(v);
                case "opacity" -> opacity = parseOpacityNullable(v);
                case "blendMode" -> blendMode = parseBlendModeNullable(v);
                case "renderMode" -> renderMode = parseRenderModeNullable(v);
                default -> throw new ValidationException("INVALID_PAYLOAD",
                        "unknown icon field: " + k);
            }
        }
        return new IconElement(ic.id(), x, y, w, h, rotation, locked, visible, source, tint,
                opacity, blendMode, renderMode, fill);
    }

    // ---------- M13 ImageElement ----------

    private Element buildImage(String id, Map<String, Object> p) {
        int x = intFieldOrDefault(p, "x", 0); validateCoord(x, "x");
        int y = intFieldOrDefault(p, "y", 0); validateCoord(y, "y");
        int w = intFieldOrDefault(p, "w", 128); validateDim(w, "w");
        int h = intFieldOrDefault(p, "h", 128); validateDim(h, "h");
        int rotation = intFieldOrDefault(p, "rotation", 0); validateRotation(rotation);
        boolean locked = boolFieldOrDefault(p, "locked", false);
        boolean visible = boolFieldOrDefault(p, "visible", true);
        String source = requireString(p, "source", true);
        validateImageSource(source);
        Mask mask = parseMaskNullable(p.get("mask"));
        // Ultrareview 2026-05-25 #12：保留 v2 BaseElement 通用字段
        Float opacity = parseOpacityNullable(p.get("opacity"));
        BlendMode blendMode = parseBlendModeNullable(p.get("blendMode"));
        RenderMode renderMode = parseRenderModeNullable(p.get("renderMode"));
        return new ImageElement(id, x, y, w, h, rotation, locked, visible,
                source, mask,
                opacity, blendMode, renderMode);
    }

    private ImageElement applyImagePatch(ImageElement orig, Map<String, Object> patch) {
        int x = orig.x(); int y = orig.y(); int w = orig.w(); int h = orig.h();
        int rotation = orig.rotation();
        boolean locked = orig.locked(); boolean visible = orig.visible();
        String source = orig.source();
        Mask mask = orig.mask();
        Float opacity = orig.opacity();
        BlendMode blendMode = orig.blendMode();
        RenderMode renderMode = orig.renderMode();

        for (var e : patch.entrySet()) {
            String k = e.getKey(); Object v = e.getValue();
            switch (k) {
                case "x" -> { x = intValue(v, k); validateCoord(x, k); }
                case "y" -> { y = intValue(v, k); validateCoord(y, k); }
                case "w" -> { w = intValue(v, k); validateDim(w, k); }
                case "h" -> { h = intValue(v, k); validateDim(h, k); }
                case "rotation" -> { rotation = intValue(v, k); validateRotation(rotation); }
                case "locked" -> locked = boolValue(v, k);
                case "visible" -> visible = boolValue(v, k);
                case "source" -> {
                    source = requireStringValue(v, k);
                    validateImageSource(source);
                }
                case "mask" -> mask = parseMaskNullable(v);
                case "opacity" -> opacity = parseOpacityNullable(v);
                case "blendMode" -> blendMode = parseBlendModeNullable(v);
                case "renderMode" -> renderMode = parseRenderModeNullable(v);
                default -> throw new ValidationException("INVALID_PAYLOAD",
                        "unknown image field: " + k);
            }
        }
        return new ImageElement(orig.id(), x, y, w, h, rotation, locked, visible,
                source, mask, opacity, blendMode, renderMode);
    }

    // 2026-05-14 重构：package-private 以便 {@link LayerOperations#duplicateLayer} 跨类调用。
    static Element cloneElementWithNewId(Element src) {
        String newId = "e-" + UUID.randomUUID();
        return switch (src) {
            case TextElement t -> new TextElement(newId,
                    t.x(), t.y(), t.w(), t.h(), t.rotation(), t.locked(), t.visible(),
                    t.text(), t.fontId(), t.fontSize(), t.color(), t.align(),
                    t.letterSpacing(), t.lineHeight(), t.vertical(), t.effects(),
                    t.opacity(), t.blendMode(), t.renderMode(),
                    t.bold(), t.italic());
            case RectElement r -> new RectElement(newId,
                    r.x(), r.y(), r.w(), r.h(), r.rotation(), r.locked(), r.visible(),
                    r.fill(), r.stroke(),
                    r.opacity(), r.blendMode(), r.renderMode());
            case IconElement ic -> new IconElement(newId,
                    ic.x(), ic.y(), ic.w(), ic.h(), ic.rotation(), ic.locked(), ic.visible(),
                    ic.source(), ic.tint(),
                    ic.opacity(), ic.blendMode(), ic.renderMode(), ic.fill());
            case PathElement p -> new PathElement(newId,
                    p.x(), p.y(), p.w(), p.h(), p.rotation(), p.locked(), p.visible(),
                    p.d(), p.fill(), p.stroke(), p.markerStart(), p.markerEnd(),
                    p.opacity(), p.blendMode(), p.renderMode());
            case CircleElement c -> new CircleElement(newId,
                    c.x(), c.y(), c.w(), c.h(), c.rotation(), c.locked(), c.visible(),
                    c.fill(), c.stroke(),
                    c.opacity(), c.blendMode(), c.renderMode());
            case ShapeElement sh -> new ShapeElement(newId,
                    sh.x(), sh.y(), sh.w(), sh.h(), sh.rotation(), sh.locked(), sh.visible(),
                    sh.kind(), sh.sides(), sh.innerRatio(),
                    sh.fill(), sh.stroke(),
                    sh.opacity(), sh.blendMode(), sh.renderMode());
            case BrushStrokeElement b -> new BrushStrokeElement(newId,
                    b.x(), b.y(), b.w(), b.h(), b.rotation(), b.locked(), b.visible(),
                    b.points(), b.size(), b.fill(),
                    b.pressureSize(), b.pressureOpacity(),
                    b.opacity(), b.blendMode(), b.renderMode());
            case ImageElement im -> new ImageElement(newId,
                    im.x(), im.y(), im.w(), im.h(), im.rotation(), im.locked(), im.visible(),
                    im.source(), im.mask(),
                    im.opacity(), im.blendMode(), im.renderMode());
        };
    }

    // 校验 helpers（validateColor / parseFill / validateRotation / validate* /
    // requireString / intFieldOrDefault / intValue / boolValue 等）已整体迁出至
    // {@link ElementValidator}（2026-05-14 god class 拆分）。
    //
    // ValidationException：M11 提取为 top-level 同包类（让 FillValidator 共用），见 ValidationException.java。

    // ---------- 0.4.0-P1 variable.* op handlers ----------
    //
    // user 变量按 per-wall 隔离：所有方法签名要 wallId（dispatcher 从 Session.wallId 传入）。
    // 内部 namespace = "user:<wallId>"；对外协议 fullName = "user:<wallId>/<name>"。
    // state.patch 走 /variables/<encoded-fullName>...（fullName 内 '/' 用 JSON Pointer 标准
    // ~1 转义）。变量变更不算 element bbox 变化，dirty 由 VariableStore.wallDirtyCallback
    // 在 setValue / update / bind / delete 时触发 → SessionManager 解耦到 ProjectionThrottler。

    /**
     * 把 fullName 编码成 JSON Pointer 段（RFC 6901 §3）：{@code ~} → {@code ~0}，
     * {@code /} → {@code ~1}。其他字符保持原文。
     */
    private static String encodeJsonPointer(String s) {
        return s.replace("~", "~0").replace("/", "~1");
    }

    private static String variablePath(String fullName) {
        return "/variables/" + encodeJsonPointer(fullName);
    }

    /**
     * 把 Variable record 序列化为 JSON Patch value 用的 Map（Jackson 自动处理 record →
     * object）。把 referencedByWalls 视为 Set<String>，无需特殊处理。
     *
     * <p>0.4.3：方法签名加 store 参数（仅 createGlobal / updateGlobal 等路径传入）让
     * {@code userglobal/*} 携带 owner 信息；其他路径透传 null store 即可，行为不变。</p>
     */
    private static Map<String, Object> variableToMap(Variable v) {
        return variableToMap(v, null);
    }

    private static Map<String, Object> variableToMap(Variable v,
            @org.jetbrains.annotations.Nullable VariableStore store) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("namespace", v.namespace());
        m.put("key", v.key());
        m.put("type", v.type().name());
        if (v.defaultValue() != null) m.put("defaultValue", v.defaultValue());
        if (v.currentValue() != null) m.put("currentValue", v.currentValue());
        m.put("updatedAt", v.updatedAt());
        m.put("ttl", v.ttl());
        if (v.source() != null) m.put("source", v.source());
        // referencedByWalls 是服务端倒排索引细节，前端不需要（前端是单 wall 视角）；不下发。
        // 0.4.3：userglobal 注入 owner 信息（store 可空 → 不查 / 也不注入）
        if (store != null
                && VariableStore.USER_GLOBAL_NAMESPACE.equals(v.namespace())) {
            store.getGlobalOwner(v.key()).ifPresent(info -> {
                m.put("ownerUuid", info.ownerUuid().toString());
                m.put("ownerName", info.ownerName());
            });
        }
        return m;
    }

    /**
     * 校验 fullName 属于本 wall（防止 wall A 改 wall B 的 user 变量）。
     * 必须以 {@code user:<thisWallId>/} 开头。
     *
     * @throws VariableException VARIABLE_NAMESPACE_DENIED 若不属于本 wall
     */
    private static void requireUserVarBelongsToWall(String fullName, String wallId) {
        if (wallId == null) {
            throw new VariableException(VariableException.Code.VARIABLE_NAMESPACE_DENIED,
                    "session has no bound wall; user variable op rejected");
        }
        String expectedPrefix = "user:" + wallId + "/";
        if (fullName == null || !fullName.startsWith(expectedPrefix)) {
            throw new VariableException(VariableException.Code.VARIABLE_NAMESPACE_DENIED,
                    "variable does not belong to this wall (expected prefix '"
                            + expectedPrefix + "'): " + fullName);
        }
    }

    /**
     * 创建 user/* 变量（namespace 自动 {@code "user:<wallId>"}）。
     * dispatcher 在调用前应做协议层权限检查（{@code canvas.var.write.own} 等）。
     *
     * <p>成功 → patch {@code add /variables/<encoded fullName> <Variable JSON>}。
     * 变量变更不影响画布像素，因此 OpResult.Ok 的 dirty = null。</p>
     */
    public synchronized OpResult createVariable(VariableStore store, String wallId,
                                                String name, VarType type,
                                                String defaultValue) {
        if (store == null) return err("INTERNAL_ERROR", "variable store unavailable");
        if (wallId == null) return err("WALL_NOT_FOUND", "session has no bound wall");
        if (name == null || name.isEmpty()) return err("INVALID_PAYLOAD", "variable name missing");
        if (type == null) return err("INVALID_PAYLOAD", "variable type missing");

        Variable created;
        try {
            created = store.create("user:" + wallId, name, type, defaultValue, "manual");
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }
        long v = state.bumpVersion();
        StatePatch patch = new StatePatchBuilder()
                .add(variablePath(created.fullName()), variableToMap(created))
                .build(v);
        return new OpResult.Ok(patch, null);
    }

    /** 更新 user/* 变量的 type / defaultValue。currentValue 走 {@link #setUserVariableValue}。 */
    public synchronized OpResult updateUserVariable(VariableStore store, String wallId,
                                                    String fullName, VariablePatch patch) {
        if (store == null) return err("INTERNAL_ERROR", "variable store unavailable");
        try {
            requireUserVarBelongsToWall(fullName, wallId);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }
        if (patch == null) return err("INVALID_PAYLOAD", "patch missing");

        Variable updated;
        try {
            updated = store.update(fullName, patch);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }
        long v = state.bumpVersion();
        // type / defaultValue 可能同时变；直接 replace 整个 Variable 节点最简明
        StatePatch sp = new StatePatchBuilder()
                .replace(variablePath(updated.fullName()), variableToMap(updated))
                .build(v);
        return new OpResult.Ok(sp, null);
    }

    /**
     * 设当前值（user/* 手动 set）。TTL 由 store 沿用旧值（用户手动 set 默认永久；插件
     * push 才传 TTL，那条路径走 store.setValue(..., ttl) 直调，不经此 op）。
     */
    public synchronized OpResult setUserVariableValue(VariableStore store, String wallId,
                                                      String fullName, String value) {
        if (store == null) return err("INTERNAL_ERROR", "variable store unavailable");
        try {
            requireUserVarBelongsToWall(fullName, wallId);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }

        try {
            store.setValue(fullName, value, null);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }
        long v = state.bumpVersion();
        // 只发 currentValue 增量；前端 mirror 用 JSON Patch 局部 replace。
        // P3-9：value==null 也用 replace(path, null)（与 SessionManager VALUE_SET 同形），
        // 前端 applyVariablePatches 的 replace&&sub==='currentValue' 分支可处理；
        // 旧的 remove .../currentValue 形态前端无对应分支会被丢弃，故消除该分叉。
        StatePatchBuilder b = new StatePatchBuilder();
        String path = variablePath(fullName) + "/currentValue";
        b.replace(path, value);
        return new OpResult.Ok(b.build(v), null);
    }

    /** 删除 user/* 变量。引用该变量的 element 渲染期走 fallback 链。 */
    public synchronized OpResult deleteUserVariable(VariableStore store, String wallId,
                                                    String fullName) {
        if (store == null) return err("INTERNAL_ERROR", "variable store unavailable");
        try {
            requireUserVarBelongsToWall(fullName, wallId);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }

        try {
            store.delete(fullName);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }
        long v = state.bumpVersion();
        StatePatch sp = new StatePatchBuilder()
                .remove(variablePath(fullName))
                .build(v);
        return new OpResult.Ok(sp, null);
    }

    /**
     * 绑定 user/* 变量到插件 namespace（{@code boundTo} = 插件 plain name），或解绑
     * （{@code boundTo} = null）。{@code canvas.var.bind} 权限敏感，dispatcher 层做检查。
     */
    public synchronized OpResult bindUserVariable(VariableStore store, String wallId,
                                                  String fullName, String boundTo) {
        if (store == null) return err("INTERNAL_ERROR", "variable store unavailable");
        try {
            requireUserVarBelongsToWall(fullName, wallId);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }

        try {
            store.bind(fullName, boundTo);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }
        Variable updated = store.get(fullName).orElse(null);
        long v = state.bumpVersion();
        StatePatchBuilder b = new StatePatchBuilder();
        if (updated != null) {
            // source 字段承担 boundTo 语义（VariableStore.bind 写 source 槽位）
            String path = variablePath(fullName) + "/source";
            if (updated.source() == null) {
                b.remove(path);
            } else {
                b.replace(path, updated.source());
            }
        }
        return new OpResult.Ok(b.build(v), null);
    }

    // ---------- 0.4.3 全局用户变量 op handlers (userglobal/*) ----------
    //
    // 与 user 变量（per-wall）的关键区别：
    //   - namespace = "userglobal"（不含冒号 + wallId 后缀）
    //   - 持久化主键 = name 单字段（全服唯一）；wall 删除不动它
    //   - ACL 走 canvas.var.global.{create, write.own/any, delete.own/any}（PluginYml 已加）
    //   - state.patch 广播 → 所有 session（不止本 wall），SessionManager P3 路由层完成
    //
    // 路径区分：
    //   - createGlobalVariable：协议侧 payload scope='global' → dispatcher 调本方法
    //   - update/set/delete/bindGlobalVariable：dispatcher 按 fullName.startsWith("userglobal/") 路由

    /** 全局用户变量 fullName = {@code userglobal/<key>}。 */
    public static final String USERGLOBAL_PREFIX = VariableStore.USER_GLOBAL_NAMESPACE + "/";

    /** 校验 fullName 属于 userglobal namespace。 */
    private static void requireUserGlobalNamespace(String fullName) {
        if (fullName == null || !fullName.startsWith(USERGLOBAL_PREFIX)) {
            throw new VariableException(VariableException.Code.VARIABLE_NAMESPACE_DENIED,
                    "expected userglobal/* prefix: " + fullName);
        }
    }

    /**
     * 0.4.3：创建全局用户变量 {@code userglobal/<key>}。owner = 调用者（UUID + 名）。
     * dispatcher 在调用前应做 {@code canvas.var.global.create} 权限检查。
     */
    public synchronized OpResult createGlobalVariable(VariableStore store,
                                                      java.util.UUID ownerUuid,
                                                      String ownerName,
                                                      String key, VarType type,
                                                      String defaultValue) {
        if (store == null) return err("INTERNAL_ERROR", "variable store unavailable");
        if (ownerUuid == null) return err("FORBIDDEN", "caller uuid required for userglobal");
        if (ownerName == null || ownerName.isEmpty()) ownerName = ownerUuid.toString();
        if (key == null || key.isEmpty()) return err("INVALID_PAYLOAD", "variable name missing");
        if (type == null) return err("INVALID_PAYLOAD", "variable type missing");

        Variable created;
        try {
            created = store.createGlobal(ownerUuid, ownerName, key, type, defaultValue);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }
        long v = state.bumpVersion();
        StatePatch patch = new StatePatchBuilder()
                .add(variablePath(created.fullName()), variableToMap(created, store))
                .build(v);
        return new OpResult.Ok(patch, null);
    }

    /** 0.4.3：更新 {@code userglobal/*} 的 type / defaultValue。 */
    public synchronized OpResult updateGlobalVariable(VariableStore store,
                                                      String fullName, VariablePatch patch) {
        if (store == null) return err("INTERNAL_ERROR", "variable store unavailable");
        try {
            requireUserGlobalNamespace(fullName);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }
        if (patch == null) return err("INVALID_PAYLOAD", "patch missing");
        Variable updated;
        try {
            updated = store.update(fullName, patch);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }
        long v = state.bumpVersion();
        StatePatch sp = new StatePatchBuilder()
                .replace(variablePath(updated.fullName()), variableToMap(updated, store))
                .build(v);
        return new OpResult.Ok(sp, null);
    }

    /** 0.4.3：设当前值（{@code userglobal/*}）。 */
    public synchronized OpResult setGlobalVariableValue(VariableStore store,
                                                        String fullName, String value) {
        if (store == null) return err("INTERNAL_ERROR", "variable store unavailable");
        try {
            requireUserGlobalNamespace(fullName);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }
        try {
            store.setValue(fullName, value, null);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }
        long v = state.bumpVersion();
        // P3-9：与 setUserVariableValue 同步——value==null 也用 replace(path, null)，
        // 对齐 SessionManager VALUE_SET wire 形态，前端可处理。
        StatePatchBuilder b = new StatePatchBuilder();
        String path = variablePath(fullName) + "/currentValue";
        b.replace(path, value);
        return new OpResult.Ok(b.build(v), null);
    }

    /** 0.4.3：删除 {@code userglobal/*}。 */
    public synchronized OpResult deleteGlobalVariable(VariableStore store, String fullName) {
        if (store == null) return err("INTERNAL_ERROR", "variable store unavailable");
        try {
            requireUserGlobalNamespace(fullName);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }
        try {
            store.delete(fullName);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }
        long v = state.bumpVersion();
        StatePatch sp = new StatePatchBuilder()
                .remove(variablePath(fullName))
                .build(v);
        return new OpResult.Ok(sp, null);
    }

    /** 0.4.3：把 {@code userglobal/*} 绑定到插件 namespace 或解绑。 */
    public synchronized OpResult bindGlobalVariable(VariableStore store,
                                                    String fullName, String boundTo) {
        if (store == null) return err("INTERNAL_ERROR", "variable store unavailable");
        try {
            requireUserGlobalNamespace(fullName);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }
        try {
            store.bind(fullName, boundTo);
        } catch (VariableException ve) {
            return err(mapVariableErrorCode(ve.code()), ve.getMessage());
        }
        Variable updated = store.get(fullName).orElse(null);
        long v = state.bumpVersion();
        StatePatchBuilder b = new StatePatchBuilder();
        if (updated != null) {
            String path = variablePath(fullName) + "/source";
            if (updated.source() == null) {
                b.remove(path);
            } else {
                b.replace(path, updated.source());
            }
        }
        return new OpResult.Ok(b.build(v), null);
    }

    /**
     * VariableException 内部 8 个 code → 协议层 4 个错误码。其余视为 INVALID_PAYLOAD。
     * 见 {@code docs/protocol.md §6.1} 错误码表 + {@code docs/dynamic-data.md §15}。
     */
    private static String mapVariableErrorCode(VariableException.Code c) {
        return switch (c) {
            case VARIABLE_NOT_FOUND -> "VARIABLE_NOT_FOUND";
            case VARIABLE_EXISTS -> "VARIABLE_EXISTS";
            case VARIABLE_TYPE_MISMATCH -> "VARIABLE_TYPE_MISMATCH";
            case VARIABLE_NAMESPACE_DENIED -> "VARIABLE_NAMESPACE_DENIED";
            case VARIABLE_NAME_INVALID,
                 VARIABLE_VALUE_TOO_LONG,
                 QUOTA_EXCEEDED,
                 TTL_INVALID -> "INVALID_PAYLOAD";
        };
    }

    private static OpResult.Error err(String code, String msg) {
        return new OpResult.Error(code, msg);
    }

    /**
     * M15.4 P0-23：模板 raw_state 反序列化得到的 element 通过本方法二次校验。
     * 2026-05-14 god class 重构后实现位于 {@link ElementValidator#validateElementForTemplateApply}；
     * 保留本 wrapper 以维持 {@code moe.hikari.canvas.template.TemplateInstantiator} 等外部调用方
     * 的 API 不变。
     *
     * @throws ValidationException 任一字段不合法
     */
    public static void validateElementForTemplateApply(Element el) {
        ElementValidator.validateElementForTemplateApply(el);
    }

}
