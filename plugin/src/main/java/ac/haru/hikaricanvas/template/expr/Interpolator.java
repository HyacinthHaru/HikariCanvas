package ac.haru.hikaricanvas.template.expr;

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
 *
 * <p><b>DoS 防御：</b></p>
 * <ul>
 *   <li>单参数 {@code toString()} 长度 &gt; {@link #MAX_VALUE_LEN}（16 KiB）→
 *       抛 {@link IllegalArgumentException}。防恶意 user-template 把超大值塞进
 *       text element 的 content 字段。</li>
 *   <li>输出比模板本身多出 &gt; {@link #MAX_OUTPUT_LEN}（1 MiB）→
 *       抛 {@link IllegalArgumentException}。防 {@code "${a}${a}${a}..."}
 *       式倍增展开。<b>额度只算替换带来的膨胀，不算模板里原样穿过的静态文本</b>——
 *       否则一份 3 MB 的 {@code project.json} 只要含一个 {@code ${param}} 就必然
 *       在插值这层被拒，而 zip 解包那层明明允许它到 10 MB。</li>
 * </ul>
 * <p>{@code IllegalArgumentException} 由调用方（{@code PackParamResolver.substitute} 等）上游
 * try-catch 包装为结构化错误码，不向客户端 echo 内部异常细节。</p>
 */
public final class Interpolator {

    /** {@code ${param}} 引用正则（名字 {@code [a-z][a-z0-9_]{0,31}}）。 */
    private static final Pattern REF =
            Pattern.compile("\\$\\{([a-z][a-z0-9_]{0,31})\\}");

    /** 单参数替换值的最大字符数（16 KiB）。 */
    public static final int MAX_VALUE_LEN = 16 * 1024;

    /**
     * 单次 interpolation 允许的<b>净膨胀</b>上限（1 MiB）：输出长度最多比模板长度多这么多。
     * 模板原样穿过的静态文本不占额度，只有替换值写进去的部分才算。
     */
    public static final int MAX_OUTPUT_LEN = 1024 * 1024;

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
        // 额度 = 模板长度 + 1 MiB 净膨胀。静态文本原样穿过不该扣额度，倍增展开仍被挡住。
        final long cap = (long) template.length() + MAX_OUTPUT_LEN;
        Matcher m = REF.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 16);
        int last = 0;
        while (m.find()) {
            appendChecked(out, template, last, m.start(), cap);
            String name = m.group(1);
            if (values == null || !values.containsKey(name)) {
                throw new MissingParamException(name);
            }
            Object val = values.get(name);
            String s = val == null ? "" : val.toString();
            // 单参数值长度上限——防超大 string 注入
            if (s.length() > MAX_VALUE_LEN) {
                throw new IllegalArgumentException(
                        "interpolated value for '" + name + "' too large: "
                                + s.length() + " > " + MAX_VALUE_LEN);
            }
            // 净膨胀上限——防 ${a}${a}${a}... 倍增展开
            if ((long) out.length() + s.length() > cap) {
                throw new IllegalArgumentException(
                        "interpolated output exceeds limit " + MAX_OUTPUT_LEN);
            }
            out.append(s);
            last = m.end();
        }
        appendChecked(out, template, last, template.length(), cap);
        return out.toString();
    }

    /** 追加一段静态文本，同时守住净膨胀额度。 */
    private static void appendChecked(StringBuilder out, String src, int start, int end, long cap) {
        int add = end - start;
        if ((long) out.length() + add > cap) {
            throw new IllegalArgumentException(
                    "interpolated output exceeds limit " + MAX_OUTPUT_LEN);
        }
        out.append(src, start, end);
    }
}
