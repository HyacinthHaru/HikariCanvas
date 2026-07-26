package ac.haru.hikaricanvas.canvasfile;

import com.fasterxml.jackson.databind.ObjectMapper;
import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.ElementValidator;
import ac.haru.hikaricanvas.state.ImageElement;
import ac.haru.hikaricanvas.state.Keyframe;
import ac.haru.hikaricanvas.state.Layer;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.Timeline;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * project.json（untrusted）→ 校验过的 ProjectState。
 *
 * <p>除了逐元素跑 {@link ElementValidator#validateElementForTemplateApply}，还要卡住
 * <b>数量</b>：zip 那层只数字节，一份合规的 10 MiB project.json 照样能塞几万个元素、上千个图层，
 * 之后每次墙加载 / 合成都要重演一遍。数量闸见 {@link Limits}，契约在
 * {@code docs/import-export.md §5.1a}。</p>
 *
 * <p>闸放在这里而不是 {@code EditSession.replaceProject}，是因为工程导入
 * （{@code POST /api/project/import}）与模板套用（{@code template.apply}）都经过本方法，
 * 一处设闸两条入口都挡住。</p>
 */
public final class ProjectMaterializer {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private ProjectMaterializer() {}

    /**
     * 导入期的结构数量闸。
     *
     * <p>前四项与 WS 编辑 op 的上限同值（{@code LayerOperations} / {@code TimelineOperations}
     * 里的常量；那两个类是包内可见，跨包取不到，故此处写同值并在改动时一并对齐）。
     * {@code maxElements} 是结构性天花板，{@code maxImageSources} 对应
     * {@code config.images.max-per-wall}（与 {@code element.add type=image} 同一配额）。</p>
     *
     * @param maxLayers               图层数上限
     * @param maxLayerName            图层名长度上限
     * @param maxTimelines            时间轴数上限
     * @param maxKeyframesPerTimeline 单时间轴关键帧总数上限
     * @param maxKeyframesPerTrack    单轨关键帧数上限
     * @param maxElements             全工程元素总数上限
     * @param maxImageSources         单墙引用的不同图片文件数上限；{@code <= 0} = 不限
     */
    public record Limits(int maxLayers, int maxLayerName, int maxTimelines,
                         int maxKeyframesPerTimeline, int maxKeyframesPerTrack,
                         int maxElements, int maxImageSources) {

        /** 与 {@code LayerOperations.MAX_LAYERS} 同值。 */
        public static final int DEFAULT_MAX_LAYERS = 64;
        /** 与 {@code LayerOperations.MAX_LAYER_NAME} 同值。 */
        public static final int DEFAULT_MAX_LAYER_NAME = 64;
        /** 与 {@code TimelineOperations.MAX_TIMELINES} 同值。 */
        public static final int DEFAULT_MAX_TIMELINES = 16;
        /** 与 {@code TimelineOperations.MAX_KEYFRAMES_PER_TIMELINE} 同值。 */
        public static final int DEFAULT_MAX_KEYFRAMES_PER_TIMELINE = 2048;
        /** 与 {@code TimelineOperations.MAX_KEYFRAMES_PER_TRACK} 同值。 */
        public static final int DEFAULT_MAX_KEYFRAMES_PER_TRACK = 256;
        /** 元素总数天花板。正常人工作图远低于此，只用来挡"一份文件塞几万个元素"。 */
        public static final int DEFAULT_MAX_ELEMENTS = 4096;

        /** 结构闸取默认值、图片数不限（预览 / 导出自校验等不落库的路径用）。 */
        public static Limits defaults() {
            return withMaxImageSources(0);
        }

        /** 结构闸取默认值，图片数按 {@code config.images.max-per-wall} 定（导入落库路径用）。 */
        public static Limits withMaxImageSources(int maxImageSources) {
            return new Limits(DEFAULT_MAX_LAYERS, DEFAULT_MAX_LAYER_NAME, DEFAULT_MAX_TIMELINES,
                    DEFAULT_MAX_KEYFRAMES_PER_TIMELINE, DEFAULT_MAX_KEYFRAMES_PER_TRACK,
                    DEFAULT_MAX_ELEMENTS, maxImageSources);
        }
    }

    /** 用默认闸物化（不限单墙图片数）。 */
    public static ProjectState materialize(byte[] projectJson, int sessionWallW, int sessionWallH)
            throws CanvasImportException {
        return materialize(projectJson, sessionWallW, sessionWallH, Limits.defaults());
    }

    public static ProjectState materialize(byte[] projectJson, int sessionWallW, int sessionWallH,
                                           Limits limits) throws CanvasImportException {
        ProjectState state;
        try {
            // @JsonCreator 处理 v1/v2/v3 迁移；Canvas record 对 widthMaps/heightMaps 做 [1,32] 硬校验
            // （越界直接抛 IllegalArgumentException → 下方 catch 归类 IMPORT_MALFORMED）。
            state = MAPPER.readValue(projectJson, ProjectState.class);
        } catch (Exception e) {
            throw new CanvasImportException("IMPORT_MALFORMED", "project.json 解析失败: " + e.getMessage());
        }
        if (state.canvas().widthMaps() > sessionWallW || state.canvas().heightMaps() > sessionWallH) {
            throw new CanvasImportException("IMPORT_SIZE_MISMATCH",
                "工程尺寸 " + state.canvas().widthMaps() + "x" + state.canvas().heightMaps()
                    + " 超过当前墙 " + sessionWallW + "x" + sessionWallH + "，请开匹配尺寸的新会话");
        }
        validateStructure(state, limits == null ? Limits.defaults() : limits);
        try {
            for (Layer layer : state.layers()) {
                for (var el : layer.elements()) {
                    ElementValidator.validateElementForTemplateApply(el);  // 不信任任何元素数值
                }
            }
        } catch (RuntimeException e) {
            // ValidationException(extends RuntimeException) 与任何其它运行期异常一律归 MALFORMED
            throw new CanvasImportException("IMPORT_MALFORMED", "元素校验失败: " + e.getMessage());
        }
        return state;
    }

    /**
     * 图层 / 时间轴 / 关键帧 / 元素总数 / 图片数的数量与附属字段校验。
     * 逐元素的字段校验由 {@link ElementValidator} 负责，这里只管"多少个"和图层自身那几个字段。
     */
    private static void validateStructure(ProjectState state, Limits limits)
            throws CanvasImportException {
        List<Layer> layers = state.layers();
        if (layers.size() > limits.maxLayers()) {
            throw new CanvasImportException("IMPORT_MALFORMED",
                    "图层数 " + layers.size() + " 超过上限 " + limits.maxLayers());
        }
        int totalElements = 0;
        Set<String> imageSources = new HashSet<>();
        for (Layer layer : layers) {
            String name = layer.name();
            if (name != null && name.length() > limits.maxLayerName()) {
                throw new CanvasImportException("IMPORT_MALFORMED",
                        "图层名超长（上限 " + limits.maxLayerName() + " 字符）");
            }
            float opacity = layer.opacity();
            // 非有限值或越界都得挡：>1 会在合成时算出噪点像素，<0 会让整层凭空消失。
            if (!Float.isFinite(opacity) || opacity < 0f || opacity > 1f) {
                throw new CanvasImportException("IMPORT_MALFORMED",
                        "图层 opacity 必须在 0~1 之间，实际 " + opacity);
            }
            totalElements += layer.elements().size();
            if (totalElements > limits.maxElements()) {
                throw new CanvasImportException("IMPORT_MALFORMED",
                        "元素总数超过上限 " + limits.maxElements());
            }
            for (Element el : layer.elements()) {
                if (el instanceof ImageElement im && im.source() != null) {
                    imageSources.add(im.source());
                }
            }
        }
        if (limits.maxImageSources() > 0 && imageSources.size() > limits.maxImageSources()) {
            throw new CanvasImportException("IMPORT_MALFORMED",
                    "引用图片数 " + imageSources.size() + " 超过单墙上限 " + limits.maxImageSources());
        }

        List<Timeline> timelines = state.timelines();
        if (timelines != null) {
            if (timelines.size() > limits.maxTimelines()) {
                throw new CanvasImportException("IMPORT_MALFORMED",
                        "时间轴数 " + timelines.size() + " 超过上限 " + limits.maxTimelines());
            }
            for (Timeline tl : timelines) {
                int total = 0;
                for (Map.Entry<String, List<Keyframe>> e : tl.tracks().entrySet()) {
                    int n = e.getValue() == null ? 0 : e.getValue().size();
                    if (n > limits.maxKeyframesPerTrack()) {
                        throw new CanvasImportException("IMPORT_MALFORMED",
                                "单轨关键帧数 " + n + " 超过上限 " + limits.maxKeyframesPerTrack());
                    }
                    total += n;
                }
                if (total > limits.maxKeyframesPerTimeline()) {
                    throw new CanvasImportException("IMPORT_MALFORMED",
                            "单条时间轴关键帧总数 " + total + " 超过上限 "
                                    + limits.maxKeyframesPerTimeline());
                }
            }
        }
    }
}
