package moe.hikari.canvas.template.expr;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字符串字段中 {@code ${param_name}} 占位符的插值。契约见 {@code docs/template-spec.md §6.1}。
 *
 * <p><b>规则：</b></p>
 * <ul>
 *   <li>{@code ${param}} 完整替换为 {@code params.get(param).toString()}</li>
 *   <li>{@code param} 在 {@code params} 中不存在 → 抛 {@link MissingParamException}
 *       （Loader 校验 §9 已扫描 undeclared refs，运行时遇到通常是 instantiate
 *       阶段缺值，由调用方决定是 fallback default 还是上抛）</li>
 *   <li>非 {@code ${...}} 字符原样保留</li>
 *   <li>对 {@code null} / 不含 {@code "${"} 的字符串直接返回原值，零分配</li>
 * </ul>
 *
 * <p>不支持嵌套（{@code ${a${b}}}）与默认值语法（{@code ${a:-x}}）—— v2+ 议题。</p>
 */
public final class Interpolator {

    /** 与 {@link moe.hikari.canvas.template.TemplateLoader} 的 PARAM_REF 同形。 */
    private static final Pattern REF =
            Pattern.compile("\\$\\{([a-z][a-z0-9_]{0,31})\\}");

    public static final class MissingParamException extends RuntimeException {
        private final String paramName;

        public MissingParamException(String paramName) {
            super("missing value for parameter '" + paramName + "'");
            this.paramName = paramName;
        }

        public String paramName() { return paramName; }
    }

    private Interpolator() {
    }

    public static String interpolate(String template, Map<String, Object> values) {
        if (template == null || !template.contains("${")) return template;
        Matcher m = REF.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 16);
        int last = 0;
        while (m.find()) {
            out.append(template, last, m.start());
            String name = m.group(1);
            if (values == null || !values.containsKey(name)) {
                throw new MissingParamException(name);
            }
            Object val = values.get(name);
            out.append(val == null ? "" : val.toString());
            last = m.end();
        }
        out.append(template, last, template.length());
        return out.toString();
    }
}
