package ac.haru.hikaricanvas.template.expr;

import ac.haru.hikaricanvas.state.StrictNumber;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link Expr} 求值器。给定参数值映射，把 AST 解释为 {@link Object}（原值）或
 * {@code boolean}（条件分支）。
 *
 * <p>语义与 {@code docs/template-spec.md §6.2} 一致（{@code && || !} 与 truthy 链
 * 是回归红线，保持不动）：</p>
 * <ul>
 *   <li><b>truthy</b>（用于 {@code &&} / {@code ||} / {@code !} / {@code visible_when} 顶层）：
 *       {@code null = false}；{@code Boolean} 取自身；{@code Number != 0 → true}；
 *       {@code String 非空 → true}；其他类型 → true</li>
 *   <li><b>{@code ==} / {@code !=} 相等</b>：<b>双侧均为数值形态</b>
 *       （{@code Number} 或整串匹配 {@link StrictNumber#PATTERN} 的字符串）时走数值等值
 *       （{@code var("score") == 42} 直接可用）；其余链不动——一边 Boolean 时按 truthy
 *       比较，否则 {@code Objects.toString} 后 string 比较</li>
 *   <li><b>{@code Identifier}</b>：从 {@code params} 取值；未声明 → 抛
 *       {@link UndeclaredParamException}（loader 阶段应已拦截，这里是兜底）</li>
 * </ul>
 *
 * <p><b>扩展语义（契约 docs/scripting.md）</b>：</p>
 * <ul>
 *   <li><b>比较 {@code < <= > >=}</b>：两侧任一可按 {@link StrictNumber#PATTERN}
 *       完整匹配（或本身是 {@code Number}）→ 双侧 {@code StrictNumber.parse} 数值比较；
 *       否则 {@code String.compareTo} 字典序（null 当空串）</li>
 *   <li><b>算术 {@code + - * /} 恒数值</b>：双侧 {@code StrictNumber} 强转（{@code +}
 *       不做字符串拼接）；除零 → {@code 0.0} + FINE log，不抛</li>
 *   <li><b>{@code var("fullName")}</b>：走构造注入的 {@code varResolver}；resolver 为
 *       null（模板路径）→ 抛 {@link VarUnsupportedException}；resolver 返 null（变量
 *       miss）→ eval 为 {@code null}（truthy=false）</li>
 * </ul>
 */
public final class ExpressionEvaluator {

    /** 引用了未在 params 中声明的标识符。Loader 应已校验，这里是运行时兜底。 */
    public static final class UndeclaredParamException extends RuntimeException {
        public UndeclaredParamException(String name) {
            super("undeclared parameter '" + name + "' in expression");
        }
    }

    /** 在无 resolver 的路径（模板 visible_when）里用了 {@code var(...)}。 */
    public static final class VarUnsupportedException extends RuntimeException {
        public VarUnsupportedException() {
            super("var(...) is not available in this context (no variable resolver)");
        }
    }

    /** {@code var("fullName")} 解析器；null 结果 = 变量 miss（eval 为 null）。 */
    private final @Nullable Function<String, String> varResolver;
    /** 除零 FINE log 用；可 null（跳过记录）。 */
    private final @Nullable Logger log;

    /** 模板路径兼容构造：无 resolver，{@code var(...)} 求值抛 {@link VarUnsupportedException}。 */
    public ExpressionEvaluator() {
        this(null, null);
    }

    /** 脚本路径构造——注入变量 resolver（fullName → 当前值 / null）。 */
    public ExpressionEvaluator(@Nullable Function<String, String> varResolver,
                               @Nullable Logger log) {
        this.varResolver = varResolver;
        this.log = log;
    }

    public boolean evalBoolean(Expr expr, Map<String, Object> params) {
        return truthy(eval(expr, params));
    }

    public Object eval(Expr expr, Map<String, Object> params) {
        if (expr instanceof Expr.Literal l) {
            return l.value();
        }
        if (expr instanceof Expr.Identifier id) {
            if (params == null || !params.containsKey(id.name())) {
                throw new UndeclaredParamException(id.name());
            }
            return params.get(id.name());
        }
        if (expr instanceof Expr.VarRef ref) {
            if (varResolver == null) {
                throw new VarUnsupportedException();
            }
            return varResolver.apply(ref.fullName());
        }
        if (expr instanceof Expr.Not n) {
            return !truthy(eval(n.inner(), params));
        }
        if (expr instanceof Expr.Neg neg) {
            return norm(-toNumber(eval(neg.inner(), params)));
        }
        if (expr instanceof Expr.Binary b) {
            return switch (b.op()) {
                case AND -> truthy(eval(b.left(), params)) && truthy(eval(b.right(), params));
                case OR  -> truthy(eval(b.left(), params)) || truthy(eval(b.right(), params));
                case EQ  -> equals(eval(b.left(), params), eval(b.right(), params));
                case NE  -> !equals(eval(b.left(), params), eval(b.right(), params));
                case LT  -> compare(eval(b.left(), params), eval(b.right(), params)) < 0;
                case LE  -> compare(eval(b.left(), params), eval(b.right(), params)) <= 0;
                case GT  -> compare(eval(b.left(), params), eval(b.right(), params)) > 0;
                case GE  -> compare(eval(b.left(), params), eval(b.right(), params)) >= 0;
                // 四则结果统一过 norm：MUL 可产 -0.0（-1 * 0），ADD/SUB/MUL 可溢出 ±Infinity
                case ADD -> norm(toNumber(eval(b.left(), params)) + toNumber(eval(b.right(), params)));
                case SUB -> norm(toNumber(eval(b.left(), params)) - toNumber(eval(b.right(), params)));
                case MUL -> norm(toNumber(eval(b.left(), params)) * toNumber(eval(b.right(), params)));
                case DIV -> divide(toNumber(eval(b.left(), params)),
                                   toNumber(eval(b.right(), params)));
            };
        }
        throw new IllegalStateException("unknown Expr subtype: " + expr.getClass());
    }

    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0d;
        if (v instanceof CharSequence s) return s.length() > 0;
        return true;
    }

    static boolean equals(Object a, Object b) {
        if (a == null || b == null) return a == b;
        // 双侧均为数值形态 → 数值等值（含 Number-Number 老分支，被此分支语义覆盖收编）。
        // 注意必须"双侧"——若任一侧即走数值，"abc" == 0 会因
        // StrictNumber parse 失败强转 0.0 而误判 true
        if (isNumeric(a) && isNumeric(b)) {
            return Double.compare(toNumber(a), toNumber(b)) == 0;
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return truthy(a) == truthy(b);
        }
        return a.toString().equals(b.toString());
    }

    // ──────────────────────────────────────────────────────────
    //  比较 / 算术语义
    // ──────────────────────────────────────────────────────────

    /**
     * 序比较：任一侧数值形态 → 双侧 {@link StrictNumber#parse} 数值比较
     * （非数值侧强转，parse 失败为 0.0）；两侧均非数值 → 字典序（null 当空串）。
     */
    private static int compare(Object a, Object b) {
        if (isNumeric(a) || isNumeric(b)) {
            return Double.compare(toNumber(a), toNumber(b));
        }
        return str(a).compareTo(str(b));
    }

    /** 数值形态判定：Number 本身,或字符串整串匹配 {@link StrictNumber#PATTERN}。 */
    private static boolean isNumeric(Object v) {
        if (v instanceof Number) return true;
        if (v instanceof CharSequence s) {
            return StrictNumber.PATTERN.matcher(s.toString().trim()).matches();
        }
        return false;
    }

    /** 数值强转（算术恒数值；与渲染/op 层共用 StrictNumber 单一文法权威）。 */
    private static double toNumber(Object v) {
        double d = (v instanceof Number n) ? n.doubleValue()
                : StrictNumber.parse(v == null ? null : v.toString());
        return norm(d);
    }

    /**
     * 数值归一单点：
     * <ul>
     *   <li>{@code -0.0 → 0.0}（"-0" 字符串 / {@code -1 * 0} 等路径），防
     *       {@code Double.compare} 把 ±0 分出大小让 {@code "-var(x) < 0"} 假阳</li>
     *   <li>非有限值（超长字面量溢出 / 运算溢出的 ±Infinity / NaN）→ {@code 0.0}，
     *       与 StrictNumber.parse 的 fallback 终点一致，不外泄</li>
     * </ul>
     * 所有数值产出口（toNumber / Neg / 四则结果）统一走这里，归一逻辑不再散点重复。
     */
    private static double norm(double d) {
        if (!Double.isFinite(d)) return 0.0;
        return d == 0d ? 0.0 : d;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    /** 除零 → 0.0 + FINE log，不抛（条件求值不允许炸链）。 */
    private double divide(double a, double b) {
        if (b == 0d) {
            if (log != null) {
                log.log(Level.FINE, "expression division by zero: " + a + " / 0 -> 0.0");
            }
            return 0.0;
        }
        // 溢出 ±Infinity / NaN（如极小除数）/ -0.0 统一走 norm 收敛
        return norm(a / b);
    }
}
