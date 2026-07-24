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
 * <h2>参数化策略</h2>
 * <ol>
 *   <li>按 z-order 扫所有 {@link TextElement} → 默认 paramId {@code text_1 / text_2 / ...}</li>
 *   <li>{@link ParamConfig#textActions} 允许用户对每个 autoId 选 {@code keep}（参数化 +
 *       重命名）或 {@code drop}（取消参数化，保持静态文本）</li>
 *   <li>{@code keep} 时 {@code project.json} 内对应 element.text 替换为 {@code "${finalParamId}"}；
 *       {@code params.json} 加入 {@code {id, type:text, default:原 text 值}}</li>
 *   <li>{@link ParamConfig#fieldMarks} 允许把 text 元素的 {@code color / fontSize / fontId}
 *       样式字段一并参数化：派生 paramId = 该 text 的 finalId（或 autoId）+ {@code _color /
 *       _fontsize / _font} 后缀，type 分别 {@code color / int / font}，default = 元素当前值。
 *       数值字段以字符串形态写占位符（{@code "fontSize": "${...}"}），套用后靠 Jackson coercion
 *       解回 int（见 {@link PackParamResolver} D3）。</li>
 * </ol>
 *
 * <p>套用走 {@code ProjectImporter.applyPack}——填参数 → {@code ${param}} 替换 → materialize。
 * 导出末尾用默认参数跑一遍这条链自校验（{@code ROUNDTRIP_FAILED}），确保产出的 pack 可套用。</p>
 *
 * <p>坐标 / 尺寸 / 效果等其余字段的参数化留待后续阶段。</p>
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

    /**
     * 把某个 autoId 对应 text 元素的一个样式字段标记为可参数化字段。
     *
     * @param autoId 目标 text 元素的 autoId（如 {@code text_1}）
     * @param field  字段名，{@code "color" | "fontSize" | "fontId"}（= TextElement 的 wire 字段名）
     */
    public record FieldMark(String autoId, String field) {}

    /**
     * 用户的导出参数调整。
     *
     * @param textActions {@code Map<autoId, AutoTextAction>}：每个 text 元素内容的 keep / drop / 改名
     * @param fieldMarks  标记要参数化的 text 元素样式字段（color / fontSize / fontId）
     */
    public record ParamConfig(Map<String, AutoTextAction> textActions, List<FieldMark> fieldMarks) {
        /** 向后兼容：仅配 text 内容参数，无字段标记。 */
        public ParamConfig(Map<String, AutoTextAction> textActions) {
            this(textActions, List.of());
        }

        public static ParamConfig empty() {
            return new ParamConfig(Map.of(), List.of());
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
        Map<String, AutoTextAction> textActions = cfg.textActions() == null ? Map.of() : cfg.textActions();
        List<FieldMark> fieldMarks = cfg.fieldMarks() == null ? List.of() : cfg.fieldMarks();

        // 1) 收集所有 TextElement + 分配 autoId
        List<TextRef> textRefs = collectTextElements(state);
        Map<String, TextRef> byAutoId = new java.util.HashMap<>();
        for (TextRef ref : textRefs) byAutoId.put(ref.autoId(), ref);

        // 2) 汇总所有参数声明：先 text 内容参数（keep/drop/改名），再样式字段参数。paramId 全局去重。
        List<ParamDecl> params = new ArrayList<>();
        Map<String, Boolean> seen = new java.util.HashMap<>();

        // 2a) text 内容参数
        for (TextRef ref : textRefs) {
            AutoTextAction action = textActions.get(ref.autoId());
            String act = action == null ? "keep" : action.action();
            if (!"keep".equals(act)) continue;
            String finalId = (action != null && action.name() != null && !action.name().isBlank())
                    ? action.name()
                    : ref.autoId();
            if (!PARAM_ID_PATTERN.matcher(finalId).matches()) {
                return new Result.Failed("INVALID_PARAM_ID",
                        "param id '" + finalId + "' must match [a-z][a-z0-9_]{0,31}");
            }
            if (seen.putIfAbsent(finalId, Boolean.TRUE) != null) {
                return new Result.Failed("DUPLICATE_PARAM_ID",
                        "param id '" + finalId + "' used twice");
            }
            String label = (action != null && action.label() != null) ? action.label() : ref.autoId();
            String desc = action != null ? action.description() : null;
            params.add(new ParamDecl(finalId, "text", ref.elementIndexFlat(), "text",
                    label, desc, ref.element().text()));
        }

        // 2b) 样式字段参数（color / fontSize / fontId）：派生 id = 前缀 + 后缀，type / default 按字段定
        for (FieldMark fm : fieldMarks) {
            if (fm == null || fm.autoId() == null || fm.field() == null) continue;
            TextRef ref = byAutoId.get(fm.autoId());
            if (ref == null) continue;   // 找不到目标 text 元素 → 容错跳过
            String field = fm.field();
            String suffix;
            String type;
            String jsonField;
            Object defaultValue;
            if ("color".equals(field)) {
                suffix = "_color"; type = "color"; jsonField = "color";
                defaultValue = ref.element().color();
            } else if ("fontSize".equals(field)) {
                suffix = "_fontsize"; type = "int"; jsonField = "fontSize";
                defaultValue = ref.element().fontSize();
            } else if ("fontId".equals(field)) {
                suffix = "_font"; type = "font"; jsonField = "fontId";
                defaultValue = ref.element().fontId();
            } else {
                continue;   // 未知字段 → 容错跳过
            }
            String derivedId = paramPrefixFor(textActions, fm.autoId()) + suffix;
            if (!PARAM_ID_PATTERN.matcher(derivedId).matches()) {
                return new Result.Failed("INVALID_PARAM_ID",
                        "derived param id '" + derivedId + "' must match [a-z][a-z0-9_]{0,31}");
            }
            if (seen.putIfAbsent(derivedId, Boolean.TRUE) != null) {
                return new Result.Failed("DUPLICATE_PARAM_ID",
                        "param id '" + derivedId + "' used twice");
            }
            params.add(new ParamDecl(derivedId, type, ref.elementIndexFlat(), jsonField,
                    fm.autoId() + " " + field, null, defaultValue));
        }

        // 3) ProjectState → Map（深拷贝；本地修改不污染原 state）
        Map<String, Object> projectMap = mapper.convertValue(state, new TypeReference<Map<String, Object>>() {});

        // 4) 在 project map 内按 (flat, jsonField) 把参数化字段的值换成 "${paramId}"
        //    映射：flat 索引 → (json 字段名 → paramId)；一个元素可同时参数化 text + color + fontSize + fontId
        Map<Integer, Map<String, String>> fieldReplacements = new java.util.HashMap<>();
        for (ParamDecl p : params) {
            fieldReplacements.computeIfAbsent(p.flat(), k -> new LinkedHashMap<>())
                    .put(p.jsonField(), p.id());
        }
        replaceFieldsWithParams(projectMap, fieldReplacements);

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
            m.put("type", p.type());
            m.put("label", p.label());
            if (p.description() != null) m.put("description", p.description());
            m.put("default", p.defaultValue());
            // fontSize 走 int 类型：加 min:1 防 0 / 负字号（materialize 端对 text.fontSize 无强校验，此处兜底）
            if ("int".equals(p.type())) m.put("min", 1);
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

    /**
     * 一条参数声明。
     *
     * @param id           paramId（占位符名）
     * @param type         params.json 的 type（{@code text / color / int / font}）
     * @param flat         目标元素 flat 索引（layer.element 拍平后的位置）
     * @param jsonField    project.json 内要替换的字段名（{@code text / color / fontSize / fontId}）
     * @param label        人类标签
     * @param description  描述；可空
     * @param defaultValue 默认值（text/color/font → String，fontSize → int）
     */
    private record ParamDecl(String id, String type, int flat, String jsonField,
                             String label, String description, Object defaultValue) {}

    /** 单个 TextElement 的临时引用：autoId + 元素本体 + flat 索引（layer.element 拍平后的位置）。 */
    private record TextRef(String autoId, TextElement element, int elementIndexFlat) {}

    private static List<TextRef> collectTextElements(ProjectState state) {
        List<TextRef> out = new ArrayList<>();
        int n = 1;
        int flat = 0;
        for (Layer layer : state.layers()) {
            for (var el : layer.elements()) {
                if (el instanceof TextElement t) {
                    out.add(new TextRef("text_" + n, t, flat));
                    n++;
                }
                flat++;
            }
        }
        return out;
    }

    /**
     * text 内容参数化后的最终 id（keep + 改名用 {@code name}，否则用 {@code autoId}）——作样式字段
     * 派生 paramId 的前缀。drop / 缺省场景一律回退 autoId。
     */
    private static String paramPrefixFor(Map<String, AutoTextAction> textActions, String autoId) {
        AutoTextAction a = textActions == null ? null : textActions.get(autoId);
        if (a != null && "keep".equals(a.action()) && a.name() != null && !a.name().isBlank()) {
            return a.name();
        }
        return autoId;
    }

    /**
     * 把 project map 中位置匹配的 text element 的若干字段值替换为 {@code "${paramId}"}。
     * {@code fieldReplacements}：flat 索引 → (json 字段名 → paramId)。
     * 用 flat index（layer.element 平铺顺序）与 {@link #collectTextElements} 计数对齐。
     */
    @SuppressWarnings("unchecked")
    private static void replaceFieldsWithParams(Map<String, Object> projectMap,
                                                Map<Integer, Map<String, String>> fieldReplacements) {
        if (fieldReplacements.isEmpty()) return;
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
                    Map<String, String> fields = fieldReplacements.get(flat);
                    if (fields != null && "text".equals(elMap.get("type"))) {
                        for (Map.Entry<String, String> fe : fields.entrySet()) {
                            // 数值字段（fontSize）也以字符串形态写占位符，套用后靠 Jackson coercion 解回 int。
                            ((Map<String, Object>) elMap).put(fe.getKey(), "${" + fe.getValue() + "}");
                        }
                    }
                }
                flat++;
            }
        }
    }
}
