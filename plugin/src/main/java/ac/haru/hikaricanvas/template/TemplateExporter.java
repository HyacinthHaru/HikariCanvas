package ac.haru.hikaricanvas.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ac.haru.hikaricanvas.canvasfile.CanvasImportException;
import ac.haru.hikaricanvas.canvasfile.CanvasManifest;
import ac.haru.hikaricanvas.canvasfile.PackParamResolver;
import ac.haru.hikaricanvas.canvasfile.ProjectImporter;
import ac.haru.hikaricanvas.canvasfile.ProjectMaterializer;
import ac.haru.hikaricanvas.state.Layer;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.TextElement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把当前 {@link ProjectState} 反向导出为一个 {@code .canvas} pack（{@code manifest.kind="pack"}）：
 * {@code project.json}（工程本体，参数化字段写 {@code ${param}}）+ {@code params.json}（参数声明数组）
 * + {@code manifest.json}。纯函数；不碰文件 IO / DB / Registry（由 {@link TemplatePublisher} 协调）。
 *
 * <h2>参数化策略（v1）</h2>
 * <ol>
 *   <li>按 z-order 扫所有 {@link TextElement} → 默认 paramId {@code text_1 / text_2 / ...}</li>
 *   <li>{@link ParamConfig#textActions} 允许用户对每个 autoId 选 {@code keep}（参数化 +
 *       重命名）或 {@code drop}（取消参数化，保持静态文本）</li>
 *   <li>{@code keep} 时 {@code project.json} 内对应 element.text 替换为 {@code "${finalParamId}"}；
 *       {@code params.json} 加入 {@code {id, type:text, default:原 text 值}}</li>
 * </ol>
 *
 * <p>套用走 {@code ProjectImporter.applyPack}——填参数 → {@code ${param}} 替换 → materialize。
 * 导出末尾用默认参数跑一遍这条链自校验（{@code ROUNDTRIP_FAILED}），确保产出的 pack 可套用。</p>
 *
 * <p>非 text 字段（color / fontSize / 坐标等）的参数化留待前端「标记可参数化字段」步骤（后续阶段）。</p>
 */
public final class TemplateExporter {

    /** templateId 形如 {@code user-<uuid8>-<slug>}。 */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,31}$");

    /** paramId 必须符合 {@code [a-z][a-z0-9_]{0,31}}。 */
    private static final Pattern PARAM_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

    /** 单个 autoId 的处理决策。 */
    public record AutoTextAction(
            /** {@code "keep"} 或 {@code "drop"}。 */
            String action,
            /** keep 时 final paramId；null = 使用 autoId 原名（如 text_1）。 */
            String name,
            /** keep 时人类标签；null = autoId。 */
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

    /** 一次成功导出的产物：pack 字节 + 落盘相对路径。 */
    public record ExportResult(
            String templateId,
            String packRelativePath,   // "user-templates/<uuid>/<slug>.canvas"
            byte[] packBytes
    ) {}

    public sealed interface Result {
        record Ok(ExportResult result) implements Result {}
        record Failed(String code, String message) implements Result {}
    }

    private final ObjectMapper mapper;   // ProjectState / 参数 → JSON（NON_NULL）

    public TemplateExporter() {
        this.mapper = new ObjectMapper().findAndRegisterModules();
        this.mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    }

    /**
     * 主入口。slug 校验 → 收集 autoText → 构造 project.json（含 {@code ${param}}）+ params.json +
     * manifest → 默认参数套用一遍自校验 roundtrip → zip 成 pack 字节。
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
        // 2) 决定每个 autoId 的最终命名 / drop → params 声明 + flat 索引到 paramId 的映射
        List<ParamDecl> params = new ArrayList<>();
        Map<String, Boolean> seen = new java.util.HashMap<>();
        Map<Integer, String> elementToParamId = new java.util.HashMap<>();
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
            if (seen.putIfAbsent(finalId, Boolean.TRUE) != null) {
                return new Result.Failed("DUPLICATE_PARAM_ID",
                        "param id '" + finalId + "' used twice");
            }
            String label = (action != null && action.label() != null) ? action.label() : ref.autoId;
            String desc = action != null ? action.description() : null;
            params.add(new ParamDecl(finalId, label, desc, ref.text));
            elementToParamId.put(ref.elementIndexFlat, finalId);
        }

        // 3) ProjectState → Map（深拷贝；本地修改不污染原 state）
        Map<String, Object> projectMap = mapper.convertValue(state, new TypeReference<Map<String, Object>>() {});

        // 4) 在 project map 内按 (layer, element) 位置把每个参数化 TextElement 的 text 换成 "${paramId}"
        replaceTextWithParams(projectMap, elementToParamId);

        // 5) 序列化三件（manifest / params.json / project.json）
        // templateId 写进 manifest.id，让注册表登记时的条目 key 与 DB template_id 对齐。
        String uuidShort = ownerUuid.toString().replace("-", "").substring(0, 8);
        String templateId = "user-" + uuidShort + "-" + slug;
        byte[] manifestBytes;
        byte[] paramsBytes;
        byte[] projectBytes;
        try {
            manifestBytes = mapper.writeValueAsBytes(buildManifest(templateId, displayName, state));
            paramsBytes = params.isEmpty() ? null : mapper.writeValueAsBytes(buildParamsArray(params));
            projectBytes = mapper.writeValueAsBytes(projectMap);
        } catch (IOException e) {
            return new Result.Failed("SERIALIZE_FAILED", e.getMessage());
        }

        // 6) 自校验 roundtrip：用默认参数走一遍 applyPack 的解析链（校验 params + 替换 + materialize）
        Result validation = validateRoundtrip(manifestBytes, paramsBytes, projectBytes, state);
        if (validation != null) return validation;

        // 7) zip 成 pack 字节
        byte[] packBytes;
        try {
            packBytes = zipPack(manifestBytes, paramsBytes, projectBytes);
        } catch (IOException e) {
            return new Result.Failed("SERIALIZE_FAILED", "zip: " + e.getMessage());
        }

        String relPath = "user-templates/" + ownerUuid + "/" + slug + ".canvas";
        return new Result.Ok(new ExportResult(templateId, relPath, packBytes));
    }

    // ---------------- 构造 ----------------

    private static Map<String, Object> buildManifest(String templateId, String displayName, ProjectState state) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("spec", 1);
        manifest.put("kind", "pack");
        manifest.put("id", templateId);
        manifest.put("created_at", System.currentTimeMillis());
        manifest.put("name", displayName);
        Map<String, Object> wall = new LinkedHashMap<>();
        wall.put("width", state.canvas().widthMaps());
        wall.put("height", state.canvas().heightMaps());
        manifest.put("wall", wall);
        return manifest;
    }

    /** params.json 是 {@code [{id, type, label, default, ...}]} 数组，id 在首位。 */
    private List<Map<String, Object>> buildParamsArray(List<ParamDecl> params) {
        List<Map<String, Object>> arr = new ArrayList<>(params.size());
        for (ParamDecl p : params) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("type", "text");
            m.put("label", p.label());
            if (p.description() != null) m.put("description", p.description());
            m.put("default", p.defaultText());
            arr.add(m);
        }
        return arr;
    }

    /**
     * 自校验：用声明的默认参数跑一遍 {@code ProjectImporter.applyPack} 的解析链
     * （manifest 校验 + params 校验 + {@code ${param}} 替换 + materialize），确保导出的 pack 可套用。
     * 任一步 {@link CanvasImportException} → {@code ROUNDTRIP_FAILED}；成功返回 {@code null}。
     */
    private Result validateRoundtrip(byte[] manifestBytes, byte[] paramsBytes,
                                     byte[] projectBytes, ProjectState state) {
        try {
            CanvasManifest.parse(manifestBytes, ProjectImporter.CANVAS_SPEC_MAX);
            Map<String, Object> values;
            if (paramsBytes != null) {
                values = PackParamResolver.resolve(PackParamResolver.parse(paramsBytes), Map.of());
            } else {
                values = Map.of();
            }
            String substituted = PackParamResolver.substitute(
                    new String(projectBytes, StandardCharsets.UTF_8), values);
            ProjectMaterializer.materialize(substituted.getBytes(StandardCharsets.UTF_8),
                    state.canvas().widthMaps(), state.canvas().heightMaps());
            return null;
        } catch (CanvasImportException e) {
            return new Result.Failed("ROUNDTRIP_FAILED", e.code() + ": " + e.getMessage());
        }
    }

    private static byte[] zipPack(byte[] manifest, byte[] params, byte[] project) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            putEntry(z, "manifest.json", manifest);
            if (params != null) putEntry(z, "params.json", params);
            putEntry(z, "project.json", project);
        }
        return bos.toByteArray();
    }

    private static void putEntry(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    // ---------------- helpers ----------------

    /** 一个参数化 text 元素的声明。 */
    private record ParamDecl(String id, String label, String description, String defaultText) {}

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
     * 把 project map 中位置匹配的 text element 的 {@code text} 字段替换为 {@code "${paramId}"}。
     * 用 flat index（layer.element 平铺顺序）与 {@link #collectTextElements} 计数对齐。
     */
    @SuppressWarnings("unchecked")
    private static void replaceTextWithParams(Map<String, Object> projectMap,
                                              Map<Integer, String> elementToParamId) {
        if (elementToParamId.isEmpty()) return;
        Object layersObj = projectMap.get("layers");
        if (!(layersObj instanceof List<?> layers)) return;
        int flat = 0;
        for (Object layerObj : layers) {
            // layer 本身不计入 flat（与 collectTextElements 仅在 element 处 flat++ 的口径一致）。
            // 非 Map layer 直接 continue 不递增 flat，否则会与索引错位，把 ${paramId} 写错元素。
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
