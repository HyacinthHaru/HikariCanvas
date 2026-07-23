package ac.haru.hikaricanvas.canvasfile;

import ac.haru.hikaricanvas.template.TemplateParam;
import ac.haru.hikaricanvas.template.expr.Interpolator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * pack（{@code manifest.kind = "pack"}）参数层：解析 {@code params.json} 声明、按类型校验用户填值、
 * 把 {@code project.json} 里的 {@code ${param}} 占位符替换成实际值。契约见 {@code docs/template-pack.md §4}。
 *
 * <p>三步与套用管线（{@link ProjectImporter#applyPack}）一一对应：</p>
 * <ol>
 *   <li>{@link #parse} —— {@code params.json}（JSON 数组）→ 声明列表；</li>
 *   <li>{@link #resolve} —— 声明 + 用户填值 → 校验过的 id→值 映射（缺省值回填 + 全类型校验，累计报错）；</li>
 *   <li>{@link #substitute} —— {@code project.json} 文本层单遍替换 {@code ${param}}（复用
 *       {@link Interpolator}，含 16KB 单值 / 1MB 累计上限）。</li>
 * </ol>
 *
 * <p><b>为何是纯文本替换（D3）：</b>参数化不挑字段。数值字段以字符串形态写占位符
 * （{@code "x": "${off}"}），替换成 {@code "x": "10"} 后交给 {@link ProjectMaterializer} —— Jackson
 * scalar coercion 默认开，{@code "10"} 自动解析回 int。故本类不做类型感知的字段定位，只在 JSON 文本上
 * 跑一遍占位符替换。{@code ${param}} 无冒号、运行时 {@code ${var:X}} 有冒号，二者天然共存，替换只吃前者。</p>
 *
 * <p><b>为何校验逻辑照搬 {@code TemplateInstantiator.coerceAndValidate}：</b>参数类型体系
 * （string/text/int/float/bool/color/enum/font）与校验规则现成，port 一份保持语义一致；旧模板路径
 * 退役后那份随之删除，这里成为唯一权威（故是复制而非依赖）。coerce 后的值以「toString 即字面量」的形态
 * 存入映射（int→{@code "5"} / float→{@code "45.0"} / bool→{@code "true"} / string/color 原样），
 * 供 {@link Interpolator} 直接拼进 JSON 文本。</p>
 *
 * <p>纯逻辑，不碰 Bukkit / DB / Session（与 {@link CanvasManifest} / {@link ProjectMaterializer}
 * 同范式），可裸测。</p>
 */
public final class PackParamResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 颜色字面量：{@code #RRGGBB} 或 {@code #RRGGBBAA}（与 {@code TemplateInstantiator} 同源）。 */
    private static final Pattern COLOR_RE = Pattern.compile("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$");
    /** 整数字面量（含负号），供 int 类型的字符串填值解析。 */
    private static final Pattern INT_NUMERIC = Pattern.compile("^-?\\d+$");

    private PackParamResolver() {
    }

    /**
     * 单条参数声明：{@code id}（占位符名）+ 复用的 {@link TemplateParam}（类型 + 校验元数据）。
     * {@code id} 单独抽出 —— {@code TemplateParam} 记录本身不含 id（在旧模板里它是 Map 的 key），
     * 而 pack 的 {@code params.json} 把 id 写进每个条目对象。
     */
    public record ParamDef(String id, TemplateParam param) {
    }

    /**
     * 解析 {@code params.json}（JSON 数组）为声明列表。每个条目读 {@code id}（必填、非空白），
     * 其余字段映射为 {@link TemplateParam}（record 上有 {@code @JsonIgnoreProperties(ignoreUnknown)}，
     * 条目里的 {@code id} 被记录映射无害忽略，故这里单独抽 id）。
     *
     * @throws CanvasImportException {@code IMPORT_MALFORMED} —— JSON 坏、非数组、条目非对象或缺 id
     */
    public static List<ParamDef> parse(byte[] paramsJson) throws CanvasImportException {
        JsonNode root;
        try {
            root = MAPPER.readTree(paramsJson);
        } catch (Exception e) {
            throw new CanvasImportException("IMPORT_MALFORMED", "params.json 解析失败: " + e.getMessage());
        }
        if (root == null || !root.isArray()) {
            throw new CanvasImportException("IMPORT_MALFORMED", "params.json 非数组");
        }
        List<ParamDef> out = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            if (!node.isObject()) {
                throw new CanvasImportException("IMPORT_MALFORMED", "params.json 条目非对象");
            }
            JsonNode idNode = node.path("id");
            String id = idNode.isTextual() ? idNode.asText() : null;
            if (id == null || id.isBlank()) {
                throw new CanvasImportException("IMPORT_MALFORMED", "params.json 条目缺 id");
            }
            TemplateParam param;
            try {
                param = MAPPER.convertValue(node, TemplateParam.class);
            } catch (Exception e) {
                throw new CanvasImportException("IMPORT_MALFORMED",
                        "params.json 参数 '" + id + "' 映射失败: " + e.getMessage());
            }
            out.add(new ParamDef(id, param));
        }
        return out;
    }

    /**
     * 对每个声明求值：填值取 {@code userInput}（有则用），否则取 {@link TemplateParam#defaultValue()}；
     * 仍为 {@code null} 且 {@code required} → 记一条错误；否则按类型 coerce + 校验。所有错误累计，
     * 非空即抛（不半态返回）。返回 id→值 映射（值的 {@code toString()} 即拼进 JSON 的字面量）。
     *
     * @throws CanvasImportException {@code IMPORT_INVALID_PARAM} —— 任一参数缺失 / 越界 / 类型不符（消息含全部问题）
     */
    public static Map<String, Object> resolve(List<ParamDef> decls, Map<String, Object> userInput)
            throws CanvasImportException {
        Map<String, Object> input = userInput == null ? Map.of() : userInput;
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (ParamDef decl : decls) {
            String id = decl.id();
            TemplateParam p = decl.param();
            Object raw = input.containsKey(id) ? input.get(id) : p.defaultValue();
            if (raw == null) {
                if (p.required()) {
                    errors.add("param '" + id + "' is required");
                }
                // 非必填缺值：留 null 占位。Interpolator 视 containsKey=true → 替换为空串
                // （与旧模板 validateParams 语义一致，避免把可选占位符当 undeclared 引用报错）。
                out.put(id, null);
                continue;
            }
            out.put(id, coerceAndValidate(id, p, raw, errors));
        }
        if (!errors.isEmpty()) {
            throw new CanvasImportException("IMPORT_INVALID_PARAM", String.join("; ", errors));
        }
        return out;
    }

    /**
     * 在 {@code project.json} 文本上单遍替换 {@code ${param}}（委托 {@link Interpolator}）。
     * 未声明引用 → {@code IMPORT_MALFORMED}（含 param 名）；Interpolator 的 16KB/1MB 上限
     * 触发的 {@link IllegalArgumentException} → 同样归 {@code IMPORT_MALFORMED}。
     *
     * <p><b>替换值按 JSON 字符串转义：</b>project.json 是 JSON，占位符一律嵌在字符串字面量里
     * （数值字段也以 {@code "${x}"} 形态写，靠 materialize 的 Jackson coercion 解回数值）。若把
     * 含引号 / 反斜杠 / 换行的值（多行招牌、带引号文案）裸插进去会破坏 JSON 结构，故先按 JSON 字符串
     * 转义再替换。纯数字值转义后不变，D3 数值 coercion 不受影响。</p>
     */
    public static String substitute(String projectJson, Map<String, Object> values)
            throws CanvasImportException {
        Map<String, Object> escaped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            escaped.put(e.getKey(),
                    e.getValue() == null ? "" : jsonEscapeInner(e.getValue().toString()));
        }
        try {
            return Interpolator.interpolate(projectJson, escaped);
        } catch (Interpolator.MissingParamException e) {
            throw new CanvasImportException("IMPORT_MALFORMED",
                    "project.json references undeclared template param '" + e.paramName() + "'");
        } catch (IllegalArgumentException e) {
            // Interpolator 单值 16KB / 累计 1MB 上限
            throw new CanvasImportException("IMPORT_MALFORMED", e.getMessage());
        }
    }

    /**
     * 把值转义成能安全嵌入 JSON 字符串字面量的形态（引号 / 反斜杠 / 控制字符 / 换行）。
     * 借 Jackson 序列化成带引号的 JSON 串再去掉首尾引号，转义规则与 materialize 端解析对齐。
     */
    private static String jsonEscapeInner(String raw) {
        try {
            String quoted = MAPPER.writeValueAsString(raw);   // "...转义后..."
            return quoted.substring(1, quoted.length() - 1);
        } catch (Exception e) {
            // writeValueAsString(String) 实际不抛；兜底返原值
            return raw;
        }
    }

    // ==================== 类型校验（port 自 TemplateInstantiator.coerceAndValidate） ====================

    private static Object coerceAndValidate(String key, TemplateParam p,
                                            Object raw, List<String> errors) {
        switch (p.type()) {
            case "string", "text" -> {
                String s = raw.toString();
                if (p.minLength() != null && s.length() < p.minLength()) {
                    errors.add("param '" + key + "' length < " + p.minLength());
                }
                if (p.maxLength() != null && s.length() > p.maxLength()) {
                    errors.add("param '" + key + "' length > " + p.maxLength());
                }
                if (p.pattern() != null) {
                    try {
                        if (!Pattern.compile(p.pattern()).matcher(s).matches()) {
                            errors.add("param '" + key + "' doesn't match pattern " + p.pattern());
                        }
                    } catch (Exception e) {
                        errors.add("param '" + key + "' pattern invalid: " + e.getMessage());
                    }
                }
                return s;
            }
            case "int" -> {
                Integer i = asInt(raw);
                if (i == null) {
                    errors.add("param '" + key + "' is not int: " + raw);
                    return 0;
                }
                if (p.min() != null && i < p.min()) errors.add("param '" + key + "' < min");
                if (p.max() != null && i > p.max()) errors.add("param '" + key + "' > max");
                return i;
            }
            case "float" -> {
                Double d = asDouble(raw);
                if (d == null) {
                    errors.add("param '" + key + "' is not float: " + raw);
                    return 0.0;
                }
                if (p.min() != null && d < p.min()) errors.add("param '" + key + "' < min");
                if (p.max() != null && d > p.max()) errors.add("param '" + key + "' > max");
                return d;
            }
            case "bool" -> {
                if (raw instanceof Boolean b) return b;
                if (raw instanceof String s) {
                    if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
                    if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
                }
                errors.add("param '" + key + "' is not bool: " + raw);
                return Boolean.FALSE;
            }
            case "color" -> {
                String s = raw.toString();
                if (!COLOR_RE.matcher(s).matches()) {
                    errors.add("param '" + key + "' invalid color: " + s);
                }
                return s;
            }
            case "enum" -> {
                String s = raw.toString();
                if (p.options() != null) {
                    boolean ok = p.options().stream().anyMatch(
                            o -> String.valueOf(o.value()).equals(s));
                    if (!ok) errors.add("param '" + key + "' not in enum options: " + s);
                }
                return s;
            }
            case "font" -> {
                return raw.toString();
            }
            default -> {
                errors.add("param '" + key + "' has unknown type: " + p.type());
                return raw;
            }
        }
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            if (INT_NUMERIC.matcher(s).matches()) {
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException ignored) {
                    // 合法整数字面量但超 int 范围 → 交由上层当「非 int」处理（返 null）
                }
            }
        }
        return null;
    }

    private static Double asDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                // 非数字串 → 返 null，上层记「不是 float」
            }
        }
        return null;
    }
}
