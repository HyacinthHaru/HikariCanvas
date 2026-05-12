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

    @Test
    void rejectsArithmetic() {
        // 文法明确不支持算术
        assertThrows(ExpressionParser.ParseException.class, () -> p.parse("a + 1"));
    }
}
