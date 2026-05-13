package moe.hikari.canvas.template;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import moe.hikari.canvas.template.asset.TemplateAssetService;
import moe.hikari.canvas.template.expr.ExpressionParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单个模板文件的解析 + §9 校验。{@link TemplateRegistry} 调它处理每个 yml。
 *
 * <p><b>解析库：</b> {@code jackson-dataformat-yaml} 2.18.2（M6 决策 2026-05-11，见
 * {@code CLAUDE.md} 与 {@code docs/security.md §4.3}）。已显式 disable polymorphic
 * typing，YAML 中的 {@code !!java/*} tag 会直接抛错而非反射构造任意类。</p>
 *
 * <p><b>失败语义：</b> 任何校验失败返回 {@link Result.Failed}；调用方记 warn log 后
 * 跳过该模板，不影响其他模板加载（§1 加载规则）。</p>
 */
public final class TemplateLoader {

    /** 当前插件支持的最高 spec 版本。 */
    public static final int SUPPORTED_SPEC = 1;

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");
    private static final Pattern PARAM_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$");
    /** 字符串里嵌的 {@code ${param_name}} 引用，捕获组 1 是 param id。 */
    private static final Pattern PARAM_REF = Pattern.compile("\\$\\{([a-z][a-z0-9_]{0,31})\\}");

    private static final Set<String> ALLOWED_PARAM_TYPES = Set.of(
            "string", "text", "int", "float", "bool", "color", "enum", "font");

    /** 实装的 layout type。M7 起 grid 也支持。 */
    private static final Set<String> ALLOWED_LAYOUT_TYPES = Set.of("stack", "free", "grid");

    private final ObjectMapper yamlMapper;

    public TemplateLoader() {
        this.yamlMapper = new YAMLMapper(new YAMLFactory())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // jackson-yaml 默认不开 polymorphic typing；这里不调用 activateDefaultTyping。
    }

    public sealed interface Result {
        record Ok(TemplateSpec spec) implements Result {}
        record Failed(String reason, String detail) implements Result {}
    }

    /** 解析 + 校验单个 YAML 流。流由调用方负责关闭。 */
    public Result load(InputStream yaml) {
        TemplateSpec spec;
        try {
            spec = yamlMapper.readValue(yaml, TemplateSpec.class);
        } catch (IOException e) {
            return new Result.Failed("parse-failed", e.getMessage());
        }
        if (spec == null) {
            return new Result.Failed("parse-failed", "empty document");
        }
        List<String> errors = new ArrayList<>();
        validate(spec, errors);
        if (!errors.isEmpty()) {
            return new Result.Failed("validation-failed", String.join("; ", errors));
        }
        return new Result.Ok(spec);
    }

    // ---------------- 校验 ----------------

    private static void validate(TemplateSpec spec, List<String> errors) {
        // 1) spec 版本
        if (spec.spec() <= 0) {
            errors.add("missing or invalid 'spec'");
        } else if (spec.spec() > SUPPORTED_SPEC) {
            errors.add("spec=" + spec.spec() + " > supported " + SUPPORTED_SPEC);
        }

        // 2) id
        if (spec.id() == null || !ID_PATTERN.matcher(spec.id()).matches()) {
            errors.add("id '" + spec.id() + "' must match " + ID_PATTERN.pattern());
        }

        // 3) name
        if (spec.name() == null || spec.name().isBlank()) {
            errors.add("name is required");
        } else if (spec.name().length() > 64) {
            errors.add("name length > 64");
        }

        // 4) canvas
        if (spec.canvas() == null) {
            errors.add("canvas is required");
        } else {
            validateCanvas(spec.canvas(), errors);
        }

        // 5) params（含 id / type / enum.options 非空）
        Set<String> paramIds = new HashSet<>();
        if (spec.params() != null) {
            for (Map.Entry<String, TemplateParam> e : spec.params().entrySet()) {
                validateParam(e.getKey(), e.getValue(), errors);
                paramIds.add(e.getKey());
            }
        }

        // 6) layout
        if (spec.layout() == null) {
            errors.add("layout is required");
        } else {
            validateLayout(spec.layout(), paramIds, errors);
        }
    }

    private static void validateCanvas(TemplateCanvas c, List<String> errors) {
        String size = c.size() == null ? "auto" : c.size();
        if (!"auto".equals(size) && !"fixed".equals(size)) {
            errors.add("canvas.size must be 'auto' or 'fixed'");
        }
        if ("fixed".equals(size) && c.maps() == null) {
            errors.add("canvas.maps required when size=fixed");
        }
        validateMapsDim("canvas.maps", c.maps(), errors);
        validateMapsDim("canvas.min_maps", c.minMaps(), errors);
        validateMapsDim("canvas.max_maps", c.maxMaps(), errors);
        if (c.background() != null && !c.background().contains("${")
                && !COLOR_PATTERN.matcher(c.background()).matches()) {
            errors.add("canvas.background '" + c.background() + "' not a valid color");
        }
        // padding: 单 int 或 [int,int,int,int]
        if (c.padding() != null) {
            if (c.padding() instanceof Number) {
                // 合法
            } else if (c.padding() instanceof List<?> list) {
                if (list.size() != 4 || list.stream().anyMatch(v -> !(v instanceof Number))) {
                    errors.add("canvas.padding list must be [int,int,int,int]");
                }
            } else {
                errors.add("canvas.padding must be int or [int,int,int,int]");
            }
        }
    }

    private static void validateMapsDim(String key, List<Integer> dim, List<String> errors) {
        if (dim == null) return;  // 缺省由 Loader 用方默认
        if (dim.size() != 2) {
            errors.add(key + " must be [width, height]");
            return;
        }
        for (int i = 0; i < 2; i++) {
            Integer v = dim.get(i);
            if (v == null || v < 1 || v > 16) {
                errors.add(key + "[" + i + "]=" + v + " out of range [1,16]");
            }
        }
    }

    private static void validateParam(String id, TemplateParam p, List<String> errors) {
        if (id == null || !PARAM_ID_PATTERN.matcher(id).matches()) {
            errors.add("param id '" + id + "' must match " + PARAM_ID_PATTERN.pattern());
        }
        if (p == null) {
            errors.add("param '" + id + "' body is null");
            return;
        }
        if (p.type() == null || !ALLOWED_PARAM_TYPES.contains(p.type())) {
            errors.add("param '" + id + "' type '" + p.type() + "' invalid; "
                    + "expected one of " + ALLOWED_PARAM_TYPES);
        }
        if ("enum".equals(p.type()) && (p.options() == null || p.options().isEmpty())) {
            errors.add("param '" + id + "' (enum) requires non-empty options");
        }
        if ("color".equals(p.type()) && p.defaultValue() instanceof String s
                && !s.contains("${") && !COLOR_PATTERN.matcher(s).matches()) {
            errors.add("param '" + id + "' default '" + s + "' not a valid color");
        }
        checkExpression(p.visibleWhen(), errors, "param '" + id + "'.visible_when");
    }

    private static void validateLayout(TemplateLayout layout, Set<String> paramIds,
                                       List<String> errors) {
        String type = layout.type();
        if (type == null || !ALLOWED_LAYOUT_TYPES.contains(type)) {
            errors.add("layout.type '" + type + "' invalid; supports " + ALLOWED_LAYOUT_TYPES);
        }
        if ("grid".equals(type)) {
            if (layout.columns() == null || layout.columns() < 1) {
                errors.add("layout.type=grid requires columns >= 1");
            }
            if (layout.rows() == null || layout.rows() < 1) {
                errors.add("layout.type=grid requires rows >= 1");
            }
        }
        List<TemplateElement> els = layout.elements();
        if (els == null || els.isEmpty()) {
            errors.add("layout.elements is empty");
            return;
        }
        for (int i = 0; i < els.size(); i++) {
            TemplateElement el = els.get(i);
            if (el == null) {
                errors.add("element[" + i + "] is null");
                continue;
            }
            validateElement(i, el, paramIds, errors);
        }
    }

    private static void validateElement(int idx, TemplateElement el,
                                        Set<String> paramIds, List<String> errors) {
        String tag = "element[" + idx + " type=" + el.type() + "]";

        if (el instanceof TemplateElement.Text t) {
            if (t.content() == null) errors.add(tag + " missing 'content'");
            if (t.size() != null && t.size() <= 0) errors.add(tag + " size must be > 0");
            collectParamRefs(t.content(), paramIds, errors, tag + ".content");
            checkColor(t.color(), errors, tag + ".color");
        } else if (el instanceof TemplateElement.Rect r) {
            checkColor(r.fill(), errors, tag + ".fill");
            if (r.stroke() != null) checkColor(r.stroke().color(), errors, tag + ".stroke.color");
        } else if (el instanceof TemplateElement.Line l) {
            if (l.from() == null || l.from().size() != 2) errors.add(tag + " 'from' must be [x,y]");
            if (l.to() == null || l.to().size() != 2) errors.add(tag + " 'to' must be [x,y]");
            checkColor(l.color(), errors, tag + ".color");
        } else if (el instanceof TemplateElement.Icon ic) {
            if (ic.source() == null || ic.source().isBlank()) {
                errors.add(tag + " icon missing 'source'");
            } else if (!ic.source().contains("${")
                    && !TemplateAssetService.isValidName(ic.source())) {
                // 含 ${param} 的留实例化期判；纯静态字符串现在就拦
                errors.add(tag + " icon source '" + ic.source()
                        + "' invalid (must match " + TemplateAssetService.SAFE_NAME.pattern() + ")");
            }
            checkColor(ic.tint(), errors, tag + ".tint");
            collectParamRefs(ic.source(), paramIds, errors, tag + ".source");
        }
        collectParamRefs(el.visibleWhen(), paramIds, errors, tag + ".visible_when");
        checkExpression(el.visibleWhen(), errors, tag + ".visible_when");
    }

    private static final ExpressionParser EXPR_PARSER = new ExpressionParser();

    /** §9 末项：visible_when 表达式可解析。空串/null 跳过。 */
    private static void checkExpression(String src, List<String> errors, String where) {
        if (src == null || src.isBlank()) return;
        try {
            EXPR_PARSER.parse(src);
        } catch (ExpressionParser.ParseException e) {
            errors.add(where + " parse error: " + e.getMessage());
        }
    }

    private static void checkColor(String value, List<String> errors, String where) {
        if (value == null) return;
        if (value.contains("${")) return;     // 含 placeholder，留实例化期判
        if (!COLOR_PATTERN.matcher(value).matches()) {
            errors.add(where + " '" + value + "' not a valid color");
        }
    }

    /** 扫一段字符串中的 ${param} 引用，未声明 → errors。 */
    private static void collectParamRefs(String src, Set<String> paramIds,
                                          List<String> errors, String where) {
        if (src == null) return;
        Matcher m = PARAM_REF.matcher(src);
        while (m.find()) {
            String ref = m.group(1);
            if (!paramIds.contains(ref)) {
                errors.add(where + " references undeclared param '" + ref + "'");
            }
        }
    }
}
