package moe.hikari.canvas.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import moe.hikari.canvas.state.Layer;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.TextElement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M14 创意工坊：把 {@link ProjectState} 反向序列化为 {@link TemplateSpec}（rawState 模式）
 * + YAML 字符串。纯函数；不碰文件 IO / DB / Registry（由 {@link TemplatePublisher} 协调）。
 *
 * <h2>参数化策略（v1）</h2>
 * <ol>
 *   <li>按 z-order 扫所有 {@link TextElement} → 默认 paramId {@code text_1 / text_2 / ...}</li>
 *   <li>{@link ParamConfig#textActions} 允许用户对每个 autoId 选 {@code keep}（参数化 +
 *       重命名）或 {@code drop}（取消参数化，保持静态文本）</li>
 *   <li>{@code keep} 时 rawState 内对应 element.text 替换为 {@code "${finalParamId}"}；
 *       params 段加入 {@code TemplateParam(type=text, default=原 text 值)}</li>
 * </ol>
 *
 * <p>v1 非 text 字段（color / fontSize / x/y/w/h 等）手工标注为参数 → <b>v1.x 留 future</b>，
 * 需要 RawState JsonNode 通用 interpolator（按字段类型转 number/string）。</p>
 */
public final class TemplateExporter {

    /** templateId 形如 {@code user-<uuid8>-<slug>}，长度 16-46 字符；符合 {@link TemplateLoader#ID_PATTERN}。 */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,31}$");

    /** paramId 必须符合 TemplateLoader.PARAM_ID_PATTERN：{@code [a-z][a-z0-9_]{0,31}}。 */
    private static final Pattern PARAM_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

    /** 单个 autoId 的处理决策。 */
    public record AutoTextAction(
            /** {@code "keep"} 或 {@code "drop"}。 */
            String action,
            /** keep 时 final paramId；null = 使用 autoId 原名（如 text_1）。 */
            String name,
            /** keep 时人类标签；null = "Text N"。 */
            String label,
            /** keep 时描述；可空。 */
            String description
    ) {}

    /** 用户的导出参数调整。{@code textActions: Map<autoId, AutoTextAction>}。 */
    public record ParamConfig(Map<String, AutoTextAction> textActions) {
        public static ParamConfig empty() {
            return new ParamConfig(Map.of());
        }
    }

    /** 一次成功导出的全部产物。 */
    public record ExportResult(
            String templateId,
            String yamlRelativePath,   // "user-templates/<uuid>/<slug>.yml"
            String yamlString,
            TemplateSpec spec
    ) {}

    public sealed interface Result {
        record Ok(ExportResult result) implements Result {}
        record Failed(String code, String message) implements Result {}
    }

    private final ObjectMapper mapper;       // 用于 ProjectState → Map
    private final TemplateLoader yamlLoader; // 用于 serializeToYaml + validate roundtrip

    public TemplateExporter(TemplateLoader yamlLoader) {
        this.mapper = new ObjectMapper().findAndRegisterModules();
        this.mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        this.yamlLoader = yamlLoader;
    }

    /**
     * 主入口。slug 校验 → 收集 autoText → 构造 spec + rawState → serializeToYaml → 自校验 roundtrip。
     */
    public Result export(UUID ownerUuid, String ownerName,
                         String slug, String displayName, String description,
                         ParamConfig paramConfig, ProjectState state) {
        if (state == null) {
            return new Result.Failed("INVALID_PAYLOAD", "projectState is null");
        }
        if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
            return new Result.Failed("INVALID_SLUG",
                    "slug must match [a-z0-9][a-z0-9-]{1,31}: " + slug);
        }
        if (displayName == null || displayName.isBlank()) {
            return new Result.Failed("INVALID_PAYLOAD", "displayName is required");
        }
        if (displayName.length() > 64) {
            return new Result.Failed("INVALID_PAYLOAD", "displayName length > 64");
        }
        ParamConfig cfg = paramConfig == null ? ParamConfig.empty() : paramConfig;

        // 1) 收集所有 TextElement + 分配 autoId
        List<TextRef> textRefs = collectTextElements(state);
        // 2) 决定每个 autoId 的最终命名 / drop
        Map<String, TemplateParam> params = new LinkedHashMap<>();
        Map<Integer, String> elementToParamId = new java.util.HashMap<>();  // textRef.index → finalParamId
        for (TextRef ref : textRefs) {
            AutoTextAction action = cfg.textActions() == null ? null : cfg.textActions().get(ref.autoId);
            String act = action == null ? "keep" : action.action();
            if (!"keep".equals(act)) continue;
            String finalId = (action != null && action.name() != null && !action.name().isBlank())
                    ? action.name()
                    : ref.autoId;
            if (!PARAM_ID_PATTERN.matcher(finalId).matches()) {
                return new Result.Failed("INVALID_PARAM_ID",
                        "param id '" + finalId + "' must match [a-z][a-z0-9_]{0,31}");
            }
            if (params.containsKey(finalId)) {
                return new Result.Failed("DUPLICATE_PARAM_ID",
                        "param id '" + finalId + "' used twice");
            }
            String label = (action != null && action.label() != null) ? action.label() : ref.autoId;
            String desc = action != null ? action.description() : null;
            params.put(finalId, new TemplateParam("text", label, desc, false,
                    ref.text, null, null, null, null, null, null, null, null, null, null, null));
            elementToParamId.put(ref.elementIndexFlat, finalId);
        }

        // 3) ProjectState → Map（深拷贝；本地修改不污染原 state）
        Map<String, Object> rawState = mapper.convertValue(state, new TypeReference<Map<String, Object>>() {});

        // 4) 在 rawState 内按 (layer, element) 位置定位每个 TextElement 替换 text → "${paramId}"
        replaceTextWithParams(rawState, elementToParamId);

        // 5) 构造 TemplateSpec
        String uuidShort = ownerUuid.toString().replace("-", "").substring(0, 8);
        String templateId = "user-" + uuidShort + "-" + slug;
        TemplateSpec spec = new TemplateSpec(
                /*spec*/ 1,
                /*id*/ templateId,
                /*name*/ displayName,
                /*description*/ description,
                /*version*/ 1,
                /*author*/ ownerName,
                /*tags*/ null,
                /*preview*/ null,
                /*canvas*/ null,
                /*params*/ params.isEmpty() ? null : params,
                /*layout*/ null,
                /*rawState*/ rawState
        );

        // 6) 序列化 + 自校验 roundtrip（确保导出的 YAML 能被 TemplateLoader 再读回）
        String yaml;
        try {
            yaml = yamlLoader.serializeToYaml(spec);
        } catch (IOException e) {
            return new Result.Failed("SERIALIZE_FAILED", e.getMessage());
        }
        TemplateLoader.Result roundtrip = yamlLoader.load(
                new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (roundtrip instanceof TemplateLoader.Result.Failed f) {
            return new Result.Failed("ROUNDTRIP_FAILED", f.reason() + ": " + f.detail());
        }

        String relPath = "user-templates/" + ownerUuid + "/" + slug + ".yml";
        return new Result.Ok(new ExportResult(templateId, relPath, yaml, spec));
    }

    // ---------------- helpers ----------------

    /** 单个 TextElement 的临时引用：autoId + text + flat 索引（layer.element 拍平后的位置）。 */
    private record TextRef(String autoId, String text, int elementIndexFlat) {}

    private static List<TextRef> collectTextElements(ProjectState state) {
        List<TextRef> out = new ArrayList<>();
        int n = 1;
        int flat = 0;
        for (Layer layer : state.layers()) {
            for (var el : layer.elements()) {
                if (el instanceof TextElement t) {
                    out.add(new TextRef("text_" + n, t.text(), flat));
                    n++;
                }
                flat++;
            }
        }
        return out;
    }

    /**
     * 把 rawState 中位置匹配的 text element 的 {@code text} 字段替换为 {@code "${paramId}"}。
     * 用 flat index（layer.element 平铺顺序）与 {@link #collectTextElements} 计数对齐。
     */
    @SuppressWarnings("unchecked")
    private static void replaceTextWithParams(Map<String, Object> rawState,
                                              Map<Integer, String> elementToParamId) {
        if (elementToParamId.isEmpty()) return;
        Object layersObj = rawState.get("layers");
        if (!(layersObj instanceof List<?> layers)) return;
        int flat = 0;
        for (Object layerObj : layers) {
            // P3-24：layer 本身不计入 flat（与 collectTextElements 仅在 element 处 flat++
            // 的口径一致）。非 Map layer 直接 continue 不递增 flat，否则会与
            // collectTextElements 的索引错位，把 ${paramId} 写到错误的 text element。
            if (!(layerObj instanceof Map<?, ?> layerMap)) continue;
            Object elementsObj = layerMap.get("elements");
            if (!(elementsObj instanceof List<?> elements)) continue;
            for (Object elObj : elements) {
                if (elObj instanceof Map<?, ?> elMap) {
                    String paramId = elementToParamId.get(flat);
                    if (paramId != null && "text".equals(elMap.get("type"))) {
                        ((Map<String, Object>) elMap).put("text", "${" + paramId + "}");
                    }
                }
                flat++;
            }
        }
    }
}
