package moe.hikari.canvas.template.expr;

/**
 * 模板表达式 AST。契约见 {@code docs/template-spec.md §6.2}。
 *
 * <p>支持的运算 = {@code ==} / {@code !=} / {@code &&} / {@code ||} / {@code !}，
 * 加括号分组。不支持函数调用、算术、字段访问。叶子节点 = {@code ident} /
 * {@code string} / {@code number} / {@code true} / {@code false}。</p>
 *
 * <p>由 {@link ExpressionParser} 构造，由 {@link ExpressionEvaluator} 解释。</p>
 */
public sealed interface Expr
        permits Expr.Literal, Expr.Identifier, Expr.Not, Expr.Binary {

    /** {@code string} / {@code number} / {@code true} / {@code false} 字面量。 */
    record Literal(Object value) implements Expr {}

    /** 标识符（参数引用）。 */
    record Identifier(String name) implements Expr {}

    /** {@code !expr}。 */
    record Not(Expr inner) implements Expr {}

    /** 二元运算 {@code left op right}。 */
    record Binary(Op op, Expr left, Expr right) implements Expr {}

    /** 二元运算符。优先级见 {@link ExpressionParser}。 */
    enum Op { EQ, NE, AND, OR }
}
