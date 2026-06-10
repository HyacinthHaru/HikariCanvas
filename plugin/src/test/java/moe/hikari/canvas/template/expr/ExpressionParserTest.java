package moe.hikari.canvas.template.expr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExpressionParserTest {

    private final ExpressionParser p = new ExpressionParser();

    @Test
    void parsesIdentifier() {
        Expr e = p.parse("show_english");
        assertInstanceOf(Expr.Identifier.class, e);
        assertEquals("show_english", ((Expr.Identifier) e).name());
    }

    @Test
    void parsesBoolLiterals() {
        assertEquals(Boolean.TRUE, ((Expr.Literal) p.parse("true")).value());
        assertEquals(Boolean.FALSE, ((Expr.Literal) p.parse("false")).value());
    }

    @Test
    void parsesStringLiteral() {
        assertEquals("Bond, James", ((Expr.Literal) p.parse("\"Bond, James\"")).value());
        assertEquals("a\nb", ((Expr.Literal) p.parse("\"a\\nb\"")).value());
        assertEquals("apostrophe's", ((Expr.Literal) p.parse("\"apostrophe's\"")).value());
    }

    @Test
    void parsesNumber() {
        assertEquals(3.0, ((Expr.Literal) p.parse("3")).value());
        assertEquals(-1.5, ((Expr.Literal) p.parse("-1.5")).value());
    }

    @Test
    void parsesEqualityChain() {
        Expr e = p.parse("show_english == true");
        Expr.Binary b = assertInstanceOf(Expr.Binary.class, e);
        assertEquals(Expr.Op.EQ, b.op());
    }

    @Test
    void precedence_andTighterThanOr() {
        // a || b && c  ==  a || (b && c)
        Expr e = p.parse("a || b && c");
        Expr.Binary or = assertInstanceOf(Expr.Binary.class, e);
        assertEquals(Expr.Op.OR, or.op());
        Expr.Binary and = assertInstanceOf(Expr.Binary.class, or.right());
        assertEquals(Expr.Op.AND, and.op());
    }

    @Test
    void precedence_equalityTighterThanAnd() {
        Expr e = p.parse("a == 1 && b == 2");
        Expr.Binary and = assertInstanceOf(Expr.Binary.class, e);
        assertEquals(Expr.Op.AND, and.op());
        assertInstanceOf(Expr.Binary.class, and.left());
        assertInstanceOf(Expr.Binary.class, and.right());
    }

    @Test
    void notUnary() {
        Expr e = p.parse("!flag");
        assertInstanceOf(Expr.Not.class, e);
    }

    @Test
    void parenthesizedExpr() {
        Expr e = p.parse("(a || b) && c");
        Expr.Binary and = assertInstanceOf(Expr.Binary.class, e);
        assertEquals(Expr.Op.AND, and.op());
        assertEquals(Expr.Op.OR, ((Expr.Binary) and.left()).op());
    }

    @Test
    void specCanonicalExample() {
        // 来自 docs/template-spec.md §6.2: show_english == true && name != ""
        Expr e = p.parse("show_english == true && name != \"\"");
        Expr.Binary and = assertInstanceOf(Expr.Binary.class, e);
        assertEquals(Expr.Op.AND, and.op());
        assertEquals(Expr.Op.EQ, ((Expr.Binary) and.left()).op());
        assertEquals(Expr.Op.NE, ((Expr.Binary) and.right()).op());
    }

    @Test
    void rejectsTrailingTokens() {
        var ex = assertThrows(ExpressionParser.ParseException.class,
                () -> p.parse("a b"));
        // position should point to 'b'
        assertEquals(2, ex.position());
    }

    @Test
    void rejectsUnmatchedParen() {
        assertThrows(ExpressionParser.ParseException.class, () -> p.parse("(a == 1"));
    }

    @Test
    void rejectsBadOperator() {
        assertThrows(ExpressionParser.ParseException.class, () -> p.parse("a & b"));
    }

    // ──────────────────────────────────────────────────────────
    //  0.7.0-P2(K2)：比较 / 算术 / var() 文法扩展
    //  （原 rejectsArithmetic 已过时——文法升级为支持算术的超集）
    // ──────────────────────────────────────────────────────────

    @Test
    void arithmeticNowParses() {
        // 0.7.0-P2 前这是 ParseException;现在是合法 ADD
        Expr e = p.parse("a + 1");
        Expr.Binary add = assertInstanceOf(Expr.Binary.class, e);
        assertEquals(Expr.Op.ADD, add.op());
    }

    @Test
    void precedence_mulTighterThanAdd_addTighterThanCmp_cmpTighterThanEq() {
        // 1 + 2 * 3 == 7  →  EQ( ADD(1, MUL(2,3)), 7 )
        Expr e = p.parse("1 + 2 * 3 == 7");
        Expr.Binary eq = assertInstanceOf(Expr.Binary.class, e);
        assertEquals(Expr.Op.EQ, eq.op());
        Expr.Binary add = assertInstanceOf(Expr.Binary.class, eq.left());
        assertEquals(Expr.Op.ADD, add.op());
        Expr.Binary mul = assertInstanceOf(Expr.Binary.class, add.right());
        assertEquals(Expr.Op.MUL, mul.op());

        // a == 1 < 2  →  EQ( a, LT(1,2) )——cmp 比 == 结合更紧
        Expr e2 = p.parse("a == 1 < 2");
        Expr.Binary eq2 = assertInstanceOf(Expr.Binary.class, e2);
        assertEquals(Expr.Op.EQ, eq2.op());
        assertEquals(Expr.Op.LT, ((Expr.Binary) eq2.right()).op());
    }

    @Test
    void parensOverrideArithmeticPrecedence() {
        // (1 + 2) * 3  →  MUL( ADD, 3 )
        Expr e = p.parse("(1 + 2) * 3");
        Expr.Binary mul = assertInstanceOf(Expr.Binary.class, e);
        assertEquals(Expr.Op.MUL, mul.op());
        assertEquals(Expr.Op.ADD, ((Expr.Binary) mul.left()).op());
    }

    @Test
    void comparisonOperatorsParse() {
        for (var pair : java.util.Map.of(
                "a < 1", Expr.Op.LT, "a <= 1", Expr.Op.LE,
                "a > 1", Expr.Op.GT, "a >= 1", Expr.Op.GE).entrySet()) {
            Expr e = p.parse(pair.getKey());
            assertEquals(pair.getValue(), ((Expr.Binary) e).op(), pair.getKey());
        }
    }

    @Test
    void negativeNumberLiteralStillFoldsToLiteral() {
        // 负号改走 unary 后常量折叠,既有 AST 形态不变
        assertEquals(-3.0, ((Expr.Literal) p.parse("-3")).value());
        Expr e = p.parse("-3 < 0");
        Expr.Binary lt = assertInstanceOf(Expr.Binary.class, e);
        assertEquals(-3.0, ((Expr.Literal) lt.left()).value());
    }

    @Test
    void binarySubtractionWithoutSpacesParses() {
        // lexer 不再把 "-2" 整体吞成负数 → 1-2 是合法 SUB
        Expr e = p.parse("1-2");
        Expr.Binary sub = assertInstanceOf(Expr.Binary.class, e);
        assertEquals(Expr.Op.SUB, sub.op());
        assertEquals(1.0, ((Expr.Literal) sub.left()).value());
        assertEquals(2.0, ((Expr.Literal) sub.right()).value());
    }

    @Test
    void unaryMinusOnNonLiteralBuildsNegNode() {
        Expr e = p.parse("-var(\"x\")");
        Expr.Neg neg = assertInstanceOf(Expr.Neg.class, e);
        assertInstanceOf(Expr.VarRef.class, neg.inner());
        // 标识符同理
        assertInstanceOf(Expr.Neg.class, p.parse("-a"));
    }

    @Test
    void chainedComparisonRejected() {
        // K2 语义:cmp 层禁止连串(1<2<3 的左结合语义是坑,直接 parse error)
        assertThrows(ExpressionParser.ParseException.class, () -> p.parse("1 < 2 < 3"));
        assertThrows(ExpressionParser.ParseException.class, () -> p.parse("a >= b > c"));
    }

    @Test
    void varFunctionParses() {
        Expr e = p.parse("var(\"user/score\")");
        assertEquals("user/score", assertInstanceOf(Expr.VarRef.class, e).fullName());
        // 含 namespace 斜杠 + 单引号形态
        assertEquals("schedule/eta_seconds",
                ((Expr.VarRef) p.parse("var('schedule/eta_seconds')")).fullName());
        // 嵌在比较里
        Expr cmp = p.parse("var(\"user/score\") >= 10");
        assertInstanceOf(Expr.VarRef.class, ((Expr.Binary) cmp).left());
    }

    @Test
    void bareVarRejected() {
        // var 是保留字:裸用 / 当标识符比较 都拒
        assertThrows(ExpressionParser.ParseException.class, () -> p.parse("var"));
        assertThrows(ExpressionParser.ParseException.class, () -> p.parse("var == 1"));
    }

    @Test
    void varWithNonStringArgRejected() {
        assertThrows(ExpressionParser.ParseException.class, () -> p.parse("var(x)"));
        assertThrows(ExpressionParser.ParseException.class, () -> p.parse("var(1)"));
        assertThrows(ExpressionParser.ParseException.class, () -> p.parse("var(\"x\""));
    }

    @Test
    void malformedArithmeticRejected() {
        assertThrows(ExpressionParser.ParseException.class, () -> p.parse("1 +"));
        assertThrows(ExpressionParser.ParseException.class, () -> p.parse("* 3"));
        assertThrows(ExpressionParser.ParseException.class, () -> p.parse("1 ** 2"));
    }

    @Test
    void varKeywordPrefixedIdentifierStillIdentifier() {
        // 只有恰好 "var" 是保留字;variable / var_x 仍是普通标识符
        assertEquals("variable", ((Expr.Identifier) p.parse("variable")).name());
        assertEquals("var_x", ((Expr.Identifier) p.parse("var_x")).name());
    }
}
