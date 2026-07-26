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
 *       {@link Interpolator}，含 16KB 单值上限 / 1MB 净膨胀上限）。</li>
 * </ol>
 *
 * <p><b>为何是纯文本替换（D3）：</b>参数化不挑字段。数值字段以字符串形态写占位符
 * （{@code "x": "${off}"}），替换成 {@code "x": "10"} 后交给 {@link ProjectMaterializer} —— Jackson
 * scalar coercion 默认开，{@code "10"} 自动解析回 int。故本类不做类型感知的字段定位，只在 JSON 文本上
 * 跑一遍占位符替换。{@code ${param}} 无冒号、运行时 {@code ${var:X}} 有冒号，二者天然共存，替换只吃前者。</p>
 *
 * <p><b>类型校验：</b>参数类型体系（string/text/int/float/bool/color/enum/font）+ 校验规则
 * （长度 / 范围 / 颜色格式 / enum 成员）在此实现。coerce 后的值以「toString 即字面量」的形态存入映射
 * （int→{@code "5"} / float→{@code "45.0"} / bool→{@code "true"} / string/color 原样），供
 * {@link Interpolator} 直接拼进 JSON 文本。</p>
 *
 * <p>纯逻辑，不碰 Bukkit / DB / Session（与 {@link CanvasManifest} / {@link ProjectMaterializer}
 * 同范式），可裸测。</p>
 */
public final class PackParamResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 颜色字面量：{@code #RRGGBB} 或 {@code #RRGGBBAA}。 */
    private static final Pattern COLOR_RE = Pattern.compile("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$");
    /** 整数字面量（含负号），供 int 类型的字符串填值解析。 */
    private static final Pattern INT_NUMERIC = Pattern.compile("^-?\\d+$");

    /**
     * {@code params.json} 里 {@code pattern} 字段的最大长度。正则本体来自不可信 pack，
     * 长度先卡一道，避免把病态巨型正则送进 {@link Pattern#compile}。
     */
    static final int MAX_PATTERN_LEN = 256;

    /**
     * 单次正则匹配允许消耗的字符读取步数。正则引擎每读一个字符计一步，超限即中止。
     *
     * <p>不可信 pack 可以同时提供正则与被匹配串（{@code default} 值就够了，纯导入即触发），
     * 于是 {@code (a+)+$} 这类经典灾难回溯能把 Jetty 工作线程钉死、CPU 打满。步数上限把
     * 指数级回溯变成"跑一会儿就放弃"，且是确定性的——不依赖超时线程，也不会因机器快慢飘。
     * 正常正则匹配一条 ≤16 KiB 的串远用不到这个量级。</p>
     */
    static final int MAX_MATCH_STEPS = 200_000;

    /**
     * 合法的参数类型全集。1.0 起冻结的契约——**不要往里加**，加了会让已导出的用户 pack
     * 在旧版本插件上打不开。这里只用来在解析期拒掉缺失 / 拼错的 type。
     */
    private static final java.util.Set<String> VALID_TYPES = java.util.Set.of(
            "string", "text", "int", "float", "bool", "color", "enum", "font");

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
     * <p>{@code type} 同样必填且必须在 {@link #VALID_TYPES} 内。缺 type 的 pack 以前能顺利注册进
     * Gallery，直到套用时才在类型 switch 上 NPE——HTTP 导入退化成 500、WS {@code template.apply}
     * 更糟（一帧回音都没有，前端只能挂等超时）。在解析期就拒掉，报错也说得清是哪个参数。
     * 注意这不是给类型体系加约束：null / 拼错的值本来就不在契约里。</p>
     *
     * @throws CanvasImportException {@code IMPORT_MALFORMED} —— JSON 坏、非数组、条目非对象、
     *         缺 id，或 type 缺失 / 不在合法取值内
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
            if (param.type() == null || !VALID_TYPES.contains(param.type())) {
                throw new CanvasImportException("IMPORT_MALFORMED",
                        "params.json 参数 '" + id + "' 的 type 缺失或非法: " + param.type()
                                + "（可用: string/text/int/float/bool/color/enum/font）");
            }
            out.add(new ParamDef(id, param));
        }
        return out;
    }

    /**
     * 对每个声明求值：填值取 {@code userInput}（非 null 才算填了），否则取
     * {@link TemplateParam#defaultValue()}；仍为 {@code null} 且 {@code required} → 记一条错误；
     * 否则按类型 coerce + 校验。所有错误累计，非空即抛（不半态返回）。返回 id→值 映射
     * （值的 {@code toString()} 即拼进 JSON 的字面量）。
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
            // 显式传 null（前端表单没填的字段常这样发）与压根没传同等对待——都回落默认值。
            // 用 containsKey 判断会让 {"size": null} 跳过默认值与全部校验，最后替换成空串。
            Object raw = input.get(id);
            if (raw == null) raw = p.defaultValue();
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
     * 未声明引用 → {@code IMPORT_MALFORMED}（含 param 名）；Interpolator 的 16KB 单值 / 1MB
     * 净膨胀上限触发的 {@link IllegalArgumentException} → 同样归 {@code IMPORT_MALFORMED}。
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
            // Interpolator 单值 16KB / 净膨胀 1MB 上限
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

    // ==================== 类型校验 ====================

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
                    matchGuarded(key, p.pattern(), s, errors);
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

    /**
     * 用不可信 pack 提供的正则匹配不可信填值，带长度闸 + 步数闸。任何问题只往
     * {@code errors} 里记一条，不上抛、不卡线程。
     */
    private static void matchGuarded(String key, String pattern, String value, List<String> errors) {
        if (pattern.length() > MAX_PATTERN_LEN) {
            errors.add("param '" + key + "' pattern too long: "
                    + pattern.length() + " > " + MAX_PATTERN_LEN);
            return;
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (RuntimeException e) {
            errors.add("param '" + key + "' pattern invalid: " + e.getMessage());
            return;
        }
        try {
            if (!compiled.matcher(new StepLimited(value, MAX_MATCH_STEPS)).matches()) {
                errors.add("param '" + key + "' doesn't match pattern " + pattern);
            }
        } catch (StepLimitExceeded e) {
            // 走到这里基本就是灾难回溯（或者串太长配上太贵的正则）。当作校验不通过处理，
            // 并把原因说清楚，免得 pack 作者对着"不匹配"一头雾水。
            errors.add("param '" + key + "' pattern too expensive to evaluate: " + pattern);
        } catch (RuntimeException e) {
            errors.add("param '" + key + "' pattern failed: " + e.getMessage());
        }
    }

    /** 步数用尽的信号；只在本类内部使用，不外泄给调用方。 */
    private static final class StepLimitExceeded extends RuntimeException {
        StepLimitExceeded() {
            super("regex step limit exceeded", null, false, false);
        }
    }

    /**
     * 只读字符序列包装：每次 {@code charAt} 计一步，超过预算就抛 {@link StepLimitExceeded}。
     * 正则引擎的回溯全部体现为反复读字符，故这是给 {@link java.util.regex.Matcher} 上硬闸的
     * 标准做法——比起另起线程 + 超时，它确定、可测，也不留悬挂线程。
     */
    private static final class StepLimited implements CharSequence {
        private final CharSequence delegate;
        private final int[] budget;

        StepLimited(CharSequence delegate, int budget) {
            this(delegate, new int[]{budget});
        }

        private StepLimited(CharSequence delegate, int[] budget) {
            this.delegate = delegate;
            this.budget = budget;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (--budget[0] < 0) throw new StepLimitExceeded();
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            // 共用同一份预算，子串也不能绕过闸门
            return new StepLimited(delegate.subSequence(start, end), budget);
        }

        @Override
        public String toString() {
            return delegate.toString();
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

    /**
     * float 填值解析。<b>只接受有限值</b>——{@code NaN} / {@code Infinity} 会让上层
     * {@code d < min} / {@code d > max} 两条比较全部为假，等于 min/max 静默失效，
     * 之后 NaN 还会顺着替换写进 project.json。故一律当"不是 float"处理。
     */
    private static Double asDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (o instanceof String s) {
            try {
                double d = Double.parseDouble(s);
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException ignored) {
                // 非数字串 → 返 null，上层记「不是 float」
            }
        }
        return null;
    }
}
