package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 服务端权威的编辑工程状态。契约见 {@code docs/protocol.md §7} 与 {@code docs/architecture.md §10.5}。
 *
 * <p><b>M8 v2 形态：</b> 顶层是 {@code layers: Layer[]} + {@code activeLayerId}；
 * 老的扁平 {@code elements: Element[]} 不再是字段，但仍保留同名访问器/mutator 转发到
 * {@code activeLayer().elements}，让 M8-A 阶段未升级到 v2 path 的调用方（如 EditSession）
 * 零修改即可继续按"活动层"语义工作。</p>
 *
 * <p>生命周期：在 {@code SessionManager.confirm} 里构造，随 {@code Session} 一起 forget。</p>
 *
 * <p><b>并发与可变性：</b> 所有 mutator 均 package-private 风格；外部只能经由
 * {@link EditSession} 在 {@code SessionManager} 的 {@code synchronized} 段内调用。
 * {@link #elements()} 返回的列表为不可变视图。</p>
 *
 * <p><b>Jackson 序列化：</b> 使用 {@code FIELD} 可见性 + {@code NON_NULL}/{@code NON_EMPTY} 控制 v2 形态输出
 * （{@code version / protocolVersion / canvas / layers / activeLayerId / history}）。
 * {@link #JsonCreator} 同时识别 v1（{@code elements} 字段）与 v2（{@code layers} 字段）
 * 入参 —— v1 形态自动包装成单一 "Default Layer"，详见 {@code docs/data-model.md §2.4.1}。</p>
 */
@JsonAutoDetect(
        fieldVisibility = Visibility.ANY,
        getterVisibility = Visibility.NONE,
        isGetterVisibility = Visibility.NONE,
        setterVisibility = Visibility.NONE,
        creatorVisibility = Visibility.NONE
)
public final class ProjectState {

    /** 协议版本字段，序列化输出固定 2（M8 起切断 v1）。 */
    public static final int PROTOCOL_VERSION = 2;

    /** 默认层名（创建 wall / v1 migrate / template.apply 都用同一文案）。 */
    public static final String DEFAULT_LAYER_NAME = "Default Layer";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Canvas(
            int widthMaps,
            int heightMaps,
            Fill background,
            Integer gridSize,       // null / 0 = 不显示网格
            List<Guide> guides      // null/empty = 无参考线
    ) {
        public Canvas {
            // M15.1 P0-Render-1：widthMaps/heightMaps 上限校验，防远程 OOM（48GB heap）。
            // 32×32 = 1024 maps 上限符合 PROPOSAL.md §3.1 设计上限。
            if (widthMaps < 1 || widthMaps > 32) {
                throw new IllegalArgumentException("widthMaps out of range [1, 32]: " + widthMaps);
            }
            if (heightMaps < 1 || heightMaps > 32) {
                throw new IllegalArgumentException("heightMaps out of range [1, 32]: " + heightMaps);
            }
            if (background == null) background = Fill.solid("#FFFFFF");
            if (guides == null) guides = List.of();
        }

        /**
         * M17 F5：Jackson 反序列化入口。{@code background} 字段类型从 {@code String}
         * 升级为 {@link Fill}（solid / linear / radial）。{@link FillDeserializer} 同时
         * 接受 string（M0-M16 旧形态自动 wrap 为 {@link SolidFill}）与 object（v2 新形态），
         * 保留旧 .canvas 工程文件向后兼容。
         */
        @JsonCreator
        public static Canvas fromJson(
                @JsonProperty("widthMaps") int widthMaps,
                @JsonProperty("heightMaps") int heightMaps,
                @JsonProperty("background") Fill background,
                @JsonProperty("gridSize") Integer gridSize,
                @JsonProperty("guides") List<Guide> guides) {
            return new Canvas(widthMaps, heightMaps,
                    background == null ? Fill.solid("#FFFFFF") : background,
                    gridSize,
                    guides == null ? List.of() : guides);
        }

        /** v1 兼容快捷构造：等价于 5 参数版本传 {@code null} / {@code List.of()}。 */
        public Canvas(int widthMaps, int heightMaps, Fill background) {
            this(widthMaps, heightMaps, background, null, List.of());
        }

        /**
         * M17 F5 兼容构造：旧 String 入口仍可用，内部 wrap 为 {@link SolidFill}。
         * 调用方应在新代码里直接传 {@link Fill}；保留此构造避开测试 / 全栈大规模重写。
         */
        public Canvas(int widthMaps, int heightMaps, String backgroundColor) {
            this(widthMaps, heightMaps, Fill.solid(backgroundColor), null, List.of());
        }
    }

    public record History(int undoDepth, int redoDepth) {}

    private long version;
    /** 始终输出 2（@JsonProperty 字段名固定）。 */
    private final int protocolVersion = PROTOCOL_VERSION;
    private Canvas canvas;
    private final List<Layer> layers;
    private String activeLayerId;
    private History history;

    public ProjectState(int widthMaps, int heightMaps) {
        this(widthMaps, heightMaps, "#FFFFFF");
    }

    public ProjectState(int widthMaps, int heightMaps, String background) {
        this.version = 0;
        this.canvas = new Canvas(widthMaps, heightMaps, background);
        Layer def = newDefaultLayer(new ArrayList<>());
        this.layers = new ArrayList<>();
        this.layers.add(def);
        this.activeLayerId = def.id();
        this.history = new History(0, 0);
    }

    /**
     * Jackson 反序列化入口。
     *
     * <p>形态识别：</p>
     * <ul>
     *   <li>v2：JSON 含 {@code layers} 字段（非空数组）→ 直接采用；{@code activeLayerId}
     *       缺失则取第一层 id</li>
     *   <li>v1：JSON 含 {@code elements} 字段、不含 {@code layers} → 自动包成
     *       一个 "Default Layer" 单层（参见 {@code docs/data-model.md §2.4.1}）</li>
     *   <li>两者皆空 → 创建一个空的 "Default Layer"</li>
     * </ul>
     *
     * <p>缺字段容错：{@code canvas} 为 null 退默认；{@code history} 为 null 退 0/0。</p>
     */
    @JsonCreator
    public ProjectState(
            @JsonProperty("version") long version,
            @JsonProperty("canvas") Canvas canvas,
            @JsonProperty("elements") List<Element> v1Elements,
            @JsonProperty("layers") List<Layer> v2Layers,
            @JsonProperty("activeLayerId") String activeLayerId,
            @JsonProperty("history") History history) {
        this.version = version;
        this.canvas = canvas != null ? canvas : new Canvas(1, 1, Fill.solid("#FFFFFF"));
        this.history = history != null ? history : new History(0, 0);

        if (v2Layers != null && !v2Layers.isEmpty()) {
            this.layers = new ArrayList<>(v2Layers);
            this.activeLayerId = (activeLayerId != null && containsLayer(activeLayerId))
                    ? activeLayerId
                    : this.layers.get(0).id();
        } else {
            // v1 migrate 路径 —— v1Elements 可能为 null（极简空工程）
            Layer def = newDefaultLayer(v1Elements != null ? new ArrayList<>(v1Elements) : new ArrayList<>());
            this.layers = new ArrayList<>();
            this.layers.add(def);
            this.activeLayerId = def.id();
        }
    }

    private boolean containsLayer(String id) {
        for (Layer l : layers) if (l.id().equals(id)) return true;
        return false;
    }

    private static Layer newDefaultLayer(List<Element> elements) {
        String id = "l-" + UUID.randomUUID().toString().substring(0, 8);
        return new Layer(id, DEFAULT_LAYER_NAME, true, false, 1.0f, BlendMode.NORMAL, elements);
    }

    // ---------- Java-side accessors（项目无前缀 getter 风格）----------

    public long version() { return version; }
    public int protocolVersion() { return protocolVersion; }
    public Canvas canvas() { return canvas; }
    public History history() { return history; }

    public List<Layer> layers() { return Collections.unmodifiableList(layers); }
    public String activeLayerId() { return activeLayerId; }

    /** 取当前活动层；activeLayerId 不存在时（应被构造器修正）回退到第一层。 */
    public Layer activeLayer() {
        for (Layer l : layers) {
            if (l.id().equals(activeLayerId)) return l;
        }
        return layers.get(0);
    }

    /**
     * <b>兼容视图：</b> 等价于 {@code activeLayer().elements()} 的只读副本。M8-A 阶段
     * EditSession 与渲染器都继续通过此 API 访问"当前活动层的元素"；M8-C 起新协议路径会
     * 直接走 {@link #layers()}。
     */
    public List<Element> elements() {
        return Collections.unmodifiableList(activeLayer().elements());
    }

    /**
     * Ultrareview 2026-05-25 #6：多图层 pristine 判定。等价于"所有 layer 都不会贡献像素"——
     * empty / hidden / opacity=0。
     *
     * <p>背景：旧 {@link #elements()} 只返回 active layer，多图层工程若 active layer 空但其他
     * visible layer 有内容会被误判为 pristine，启动 restore / projector 走 placeholder 抹掉真实内容。</p>
     */
    public boolean isPristineAcrossLayers() {
        for (Layer l : layers) {
            if (!l.visible()) continue;
            if (l.opacity() == 0f) continue;
            if (!l.elements().isEmpty()) return false;
        }
        return true;
    }

    public int indexOfElement(String elementId) {
        List<Element> es = activeLayer().elements();
        for (int i = 0; i < es.size(); i++) {
            if (es.get(i).id().equals(elementId)) return i;
        }
        return -1;
    }

    // ---------- package-private mutators（EditSession 使用）----------

    /** 每次成功变更后递增 version；返回新版本号。 */
    public long bumpVersion() {
        return ++version;
    }

    public void canvas(Canvas c) {
        this.canvas = c;
    }

    public void history(History h) {
        this.history = h;
    }

    public void activeLayerId(String layerId) {
        if (containsLayer(layerId)) this.activeLayerId = layerId;
    }

    // ---------- M8-C：layer 级 mutator（EditSession 使用）----------

    /** 插入新层到 {@code index} 位置；不修改 activeLayerId。 */
    public void insertLayer(int index, Layer layer) {
        layers.add(index, layer);
    }

    /** 删除指定 index 的层；调用方负责处理 activeLayerId 转移（若必要）。 */
    public Layer removeLayer(int index) {
        return layers.remove(index);
    }

    /** 替换指定 index 的层（同 id 的属性更新）。 */
    public void replaceLayer(int index, Layer layer) {
        layers.set(index, layer);
    }

    /** 把 [from] 位置的层移到 [to] 位置。reorderLayer op 用。 */
    public void moveLayer(int from, int to) {
        if (from == to) return;
        Layer l = layers.remove(from);
        layers.add(to, l);
    }

    /**
     * 整体替换 layers 树 + activeLayerId。{@code template.apply} 走的路径。
     * 不做 layer.id 校验；调用方应保证传入的 layers 至少含一个、activeLayerId 在其中。
     */
    public void replaceAllLayers(List<Layer> newLayers, String newActiveLayerId) {
        layers.clear();
        layers.addAll(newLayers);
        this.activeLayerId = newActiveLayerId;
    }

    public void addElement(Element e) {
        activeLayer().elements().add(e);
    }

    public void addElement(int index, Element e) {
        activeLayer().elements().add(index, e);
    }

    public void clearElements() {
        activeLayer().elements().clear();
    }

    public Element removeElementAt(int index) {
        return activeLayer().elements().remove(index);
    }

    public void replaceElementAt(int index, Element e) {
        activeLayer().elements().set(index, e);
    }

    /** 把 [from] 位置的元素移到 [to] 位置。reorder op 用。 */
    public void moveElement(int from, int to) {
        if (from == to) return;
        List<Element> es = activeLayer().elements();
        Element e = es.remove(from);
        es.add(to, e);
    }

    /**
     * 从 {@link ProjectSnapshot} 整体恢复 state（undo/redo 用）。
     *
     * <p><b>M8-C 升级：</b> snapshot 现存完整 {@code layers + activeLayerId} 树。restore
     * 整体替换 canvas / layers / activeLayerId 三件套，保证跨层 op（layer.create / delete /
     * reorder / element.move-to-layer 等）都能正确撤销。</p>
     *
     * <p>这里再深拷贝一次 layers —— snapshot 内的 layers 是 {@link List#copyOf} 不可变视图，
     * 而 ProjectState.layers 是 mutable ArrayList（后续 EditSession op 要继续 mutate），
     * 直接 addAll 会共享 element ArrayList 引用，下次再 snapshot 又会读到被改后的状态。</p>
     *
     * <p>{@code version} 不回滚 —— 恢复后调用方应 {@link #bumpVersion} 标记新版本。</p>
     */
    public void restore(ProjectSnapshot snap) {
        this.canvas = snap.canvas();
        this.layers.clear();
        for (Layer l : snap.layers()) {
            this.layers.add(new Layer(
                    l.id(), l.name(), l.visible(), l.locked(),
                    l.opacity(), l.blendMode(), l.colorTag(),
                    new ArrayList<>(l.elements())));
        }
        // activeLayerId 若 snapshot 里没了对应层，回退到第一层
        if (snap.activeLayerId() != null && containsLayer(snap.activeLayerId())) {
            this.activeLayerId = snap.activeLayerId();
        } else if (!this.layers.isEmpty()) {
            this.activeLayerId = this.layers.get(0).id();
        }
    }
}
