package ac.haru.hikaricanvas.template.expr;

/**
 * 模板/脚本表达式 AST。契约见 {@code docs/template-spec.md §6.2}（模板原文法）+
 * {@code docs/scripting.md}（条件文法超集）。
 *
 * <p>支持的运算 = {@code ==} / {@code !=} / {@code &&} / {@code ||} / {@code !}
 * / 比较 {@code < <= > >=} / 算术 {@code + - * /} / 一元负号 / {@code var("fullName")}
 * 函数节点，加括号分组。叶子节点 = {@code ident} / {@code string} / {@code number} /
 * {@code true} / {@code false} / {@code var(...)}。模板 visible_when 拿到的是无破坏超集。</p>
 *
 * <p>由 {@link ExpressionParser} 构造，由 {@link ExpressionEvaluator} 解释。</p>
 */
public sealed interface Expr
        permits Expr.Literal, Expr.Identifier, Expr.VarRef, Expr.Not, Expr.Neg, Expr.Binary {

    /** {@code string} / {@code number} / {@code true} / {@code false} 字面量。 */
    record Literal(Object value) implements Expr {}

    /** 标识符（参数引用）。 */
    record Identifier(String name) implements Expr {}

    /**
     * {@code var("fullName")} 函数节点——脚本条件里读变量当前值。
     * 求值需注入 resolver（{@link ExpressionEvaluator} 可选构造参数）；
     * 模板路径（无 resolver）求值时报错。
     */
    record VarRef(String fullName) implements Expr {}

    /** {@code !expr}。 */
    record Not(Expr inner) implements Expr {}

    /**
     * 一元负号 {@code -expr}（非字面量场景，如 {@code -var("x")}）。
     * 数字字面量的负号在 parser 内常量折叠为 {@link Literal}（保持既有 AST 形态）。
     */
    record Neg(Expr inner) implements Expr {}

    /** 二元运算 {@code left op right}。 */
    record Binary(Op op, Expr left, Expr right) implements Expr {}

    /** 二元运算符。优先级见 {@link ExpressionParser}。 */
    enum Op { EQ, NE, AND, OR, LT, LE, GT, GE, ADD, SUB, MUL, DIV }
}
