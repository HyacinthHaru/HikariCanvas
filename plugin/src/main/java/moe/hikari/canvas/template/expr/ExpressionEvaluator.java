package moe.hikari.canvas.template.expr;

import java.util.Map;

/**
 * {@link Expr} 求值器。给定参数值映射，把 AST 解释为 {@link Object}（原值）或
 * {@code boolean}（条件分支）。
 *
 * <p>语义与 {@code docs/template-spec.md §6.2} 一致：</p>
 * <ul>
 *   <li><b>truthy</b>（用于 {@code &&} / {@code ||} / {@code !} / {@code visible_when} 顶层）：
 *       {@code null = false}；{@code Boolean} 取自身；{@code Number != 0 → true}；
 *       {@code String 非空 → true}；其他类型 → true</li>
 *   <li><b>{@code ==} / {@code !=} 相等</b>：两侧都是 {@code Number} 时按 double 比较，
 *       一边 Boolean 时按 boolean 比较，否则 {@code Objects.toString} 后 string 比较</li>
 *   <li><b>{@code Identifier}</b>：从 {@code params} 取值；未声明 → 抛
 *       {@link UndeclaredParamException}（loader 阶段应已拦截，这里是兜底）</li>
 * </ul>
 */
public final class ExpressionEvaluator {

    /** 引用了未在 params 中声明的标识符。Loader 应已校验，这里是运行时兜底。 */
    public static final class UndeclaredParamException extends RuntimeException {
        public UndeclaredParamException(String name) {
            super("undeclared parameter '" + name + "' in expression");
        }
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
        if (expr instanceof Expr.Not n) {
            return !truthy(eval(n.inner(), params));
        }
        if (expr instanceof Expr.Binary b) {
            return switch (b.op()) {
                case AND -> truthy(eval(b.left(), params)) && truthy(eval(b.right(), params));
                case OR  -> truthy(eval(b.left(), params)) || truthy(eval(b.right(), params));
                case EQ  -> equals(eval(b.left(), params), eval(b.right(), params));
                case NE  -> !equals(eval(b.left(), params), eval(b.right(), params));
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
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return truthy(a) == truthy(b);
        }
        return a.toString().equals(b.toString());
    }
}
