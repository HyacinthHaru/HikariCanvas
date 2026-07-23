package ac.haru.hikaricanvas.template.expr;

import java.util.ArrayList;
import java.util.List;

/**
 * 模板/脚本表达式解析器。文法（模板 {@code visible_when}
 * 拿到无破坏超集）：
 *
 * <pre>
 *   or      := and ( "||" and )*
 *   and     := equ ( "&&" equ )*
 *   equ     := cmp ( ("==" | "!=") cmp )*
 *   cmp     := add ( ("&lt;" | "&lt;=" | "&gt;" | "&gt;=") add )?     —— 禁止连串（见下）
 *   add     := mul ( ("+" | "-") mul )*
 *   mul     := unary ( ("*" | "/") unary )*
 *   unary   := "!" unary | "-" unary | primary
 *   primary := "var" "(" string ")" | ident | number | string
 *            | "true" | "false" | "(" or ")"
 * </pre>
 *
 * <p>优先级（高 → 低）：{@code ! -}（一元）→ {@code * /} → {@code + -} →
 * 比较 → {@code == !=} → {@code &&} → {@code ||}。与 C 系语言一致。</p>
 *
 * <p><b>cmp 禁止连串</b>：{@code 1 < 2 < 3} 直接 parse error——左结合会得出
 * {@code (1<2)<3} = {@code true<3} 的布尔参与比较语义，最干净的处理是禁写。</p>
 *
 * <p><b>{@code var} 关键字函数</b>：{@code var("user/score")} 解析为
 * {@link Expr.VarRef}；裸 {@code var}（后面不跟 {@code (}）直接拒——脚本条件里
 * {@code var} 是保留字，不允许当参数标识符（防 typo 静默当 Identifier 走
 * UndeclaredParam 路径）。</p>
 *
 * <p><b>负号</b>：lexer 不再把 {@code -3} 整体读成负数 token（否则 {@code 1-2}
 * 会被切成 {@code 1} 和 {@code -2} 两个 NUMBER 破坏二元减法），统一发 MINUS token
 * 由 {@code unary} 处理；数字字面量上的一元负号在 parser 内常量折叠为
 * {@code Literal(-n)}，保持既有 AST 形态（{@code parse("-1.5")} 仍是 Literal）。</p>
 *
 * <p><b>失败行为：</b> 任何 lex/parse 错误抛 {@link ParseException}，
 * 调用方（{@code script.engine.ConditionEvaluator} 等）应把它当校验失败处理。</p>
 *
 * <p>实例无状态，可安全复用。</p>
 */
public final class ExpressionParser {

    /** 表达式解析错误。{@code position} 是源字符串中的 0-based 索引。 */
    public static final class ParseException extends RuntimeException {
        private final int position;

        public ParseException(String message, int position) {
            super(message + " (at position " + position + ")");
            this.position = position;
        }

        public int position() { return position; }
    }

    public Expr parse(String src) {
        if (src == null) throw new ParseException("expression is null", 0);
        List<Token> tokens = new Lexer(src).run();
        Parser p = new Parser(tokens);
        Expr expr = p.parseOr();
        Token next = p.peek();
        if (next.kind() != TokenKind.EOF) {
            throw new ParseException("unexpected token '" + next.text() + "'", next.pos());
        }
        return expr;
    }

    // ============================ Lexer ============================

    private enum TokenKind {
        IDENT, NUMBER, STRING, TRUE, FALSE,
        EQ, NE, AND, OR, NOT,
        LT, LE, GT, GE, PLUS, MINUS, STAR, SLASH,
        LPAREN, RPAREN, EOF
    }

    private record Token(TokenKind kind, String text, int pos) {}

    private static final class Lexer {
        private final String src;
        private int i = 0;

        Lexer(String src) { this.src = src; }

        List<Token> run() {
            List<Token> out = new ArrayList<>();
            while (i < src.length()) {
                char c = src.charAt(i);
                if (Character.isWhitespace(c)) { i++; continue; }
                int start = i;
                if (c == '"' || c == '\'') { out.add(readString(c, start)); continue; }
                // 负号不再并入数字（见类注释），数字仅从 digit / '.' 开始
                if (Character.isDigit(c)
                        || (c == '.' && i + 1 < src.length() && Character.isDigit(src.charAt(i + 1)))) {
                    out.add(readNumber(start));
                    continue;
                }
                if (isIdentStart(c)) { out.add(readIdentOrKeyword(start)); continue; }
                if (c == '=' && peek(1) == '=') { i += 2; out.add(new Token(TokenKind.EQ, "==", start)); continue; }
                if (c == '!' && peek(1) == '=') { i += 2; out.add(new Token(TokenKind.NE, "!=", start)); continue; }
                if (c == '&' && peek(1) == '&') { i += 2; out.add(new Token(TokenKind.AND, "&&", start)); continue; }
                if (c == '|' && peek(1) == '|') { i += 2; out.add(new Token(TokenKind.OR, "||", start)); continue; }
                if (c == '<' && peek(1) == '=') { i += 2; out.add(new Token(TokenKind.LE, "<=", start)); continue; }
                if (c == '>' && peek(1) == '=') { i += 2; out.add(new Token(TokenKind.GE, ">=", start)); continue; }
                if (c == '<') { i++; out.add(new Token(TokenKind.LT, "<", start)); continue; }
                if (c == '>') { i++; out.add(new Token(TokenKind.GT, ">", start)); continue; }
                if (c == '+') { i++; out.add(new Token(TokenKind.PLUS, "+", start)); continue; }
                if (c == '-') { i++; out.add(new Token(TokenKind.MINUS, "-", start)); continue; }
                if (c == '*') { i++; out.add(new Token(TokenKind.STAR, "*", start)); continue; }
                if (c == '/') { i++; out.add(new Token(TokenKind.SLASH, "/", start)); continue; }
                if (c == '!') { i++; out.add(new Token(TokenKind.NOT, "!", start)); continue; }
                if (c == '(') { i++; out.add(new Token(TokenKind.LPAREN, "(", start)); continue; }
                if (c == ')') { i++; out.add(new Token(TokenKind.RPAREN, ")", start)); continue; }
                throw new ParseException("unexpected character '" + c + "'", start);
            }
            out.add(new Token(TokenKind.EOF, "", src.length()));
            return out;
        }

        private char peek(int offset) {
            int j = i + offset;
            return j < src.length() ? src.charAt(j) : '\0';
        }

        private static boolean isIdentStart(char c) {
            return c == '_' || Character.isLetter(c);
        }

        private static boolean isIdentCont(char c) {
            return c == '_' || Character.isLetterOrDigit(c);
        }

        private Token readIdentOrKeyword(int start) {
            int j = i + 1;
            while (j < src.length() && isIdentCont(src.charAt(j))) j++;
            String word = src.substring(i, j);
            i = j;
            return switch (word) {
                case "true" -> new Token(TokenKind.TRUE, word, start);
                case "false" -> new Token(TokenKind.FALSE, word, start);
                default -> new Token(TokenKind.IDENT, word, start);
            };
        }

        private Token readNumber(int start) {
            int j = i;
            while (j < src.length() && Character.isDigit(src.charAt(j))) j++;
            if (j < src.length() && src.charAt(j) == '.') {
                j++;
                while (j < src.length() && Character.isDigit(src.charAt(j))) j++;
            }
            String num = src.substring(i, j);
            i = j;
            return new Token(TokenKind.NUMBER, num, start);
        }

        private Token readString(char quote, int start) {
            StringBuilder sb = new StringBuilder();
            i++; // consume opening quote
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == '\\' && i + 1 < src.length()) {
                    char n = src.charAt(i + 1);
                    sb.append(switch (n) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case '\\' -> '\\';
                        case '"' -> '"';
                        case '\'' -> '\'';
                        default -> n;
                    });
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    i++;
                    return new Token(TokenKind.STRING, sb.toString(), start);
                }
                sb.append(c);
                i++;
            }
            throw new ParseException("unterminated string literal", start);
        }
    }

    // ============================ Parser ============================

    /**
     * 递归下降嵌套深度上限。每层 {@code (} 进入 {@code parseOr}、每个 {@code !}/{@code -}
     * 进入 {@code parseUnary} 都自增 depth，超限抛 {@link ParseException}（被调用方正常 catch），
     * 把原本未设防的无界递归（海量 {@code (} / {@code !} → StackOverflowError）降级为普通校验失败。
     * 64 远大于任何合理表达式嵌套深度。
     */
    private static final int MAX_DEPTH = 64;

    private static final class Parser {
        private final List<Token> tokens;
        private int p = 0;
        private int depth = 0;

        Parser(List<Token> tokens) { this.tokens = tokens; }

        Token peek() { return tokens.get(p); }

        /** 进入一个会加深递归的层级，超 {@link #MAX_DEPTH} 抛 ParseException。 */
        private void enterDepth() {
            if (++depth > MAX_DEPTH) {
                throw new ParseException("expression nesting too deep (> " + MAX_DEPTH + ")",
                        peek().pos());
            }
        }

        private Token consume(TokenKind expected) {
            Token t = peek();
            if (t.kind() != expected) {
                throw new ParseException("expected " + expected + " got " + t.kind() + " '" + t.text() + "'", t.pos());
            }
            p++;
            return t;
        }

        Expr parseOr() {
            enterDepth();
            try {
                Expr left = parseAnd();
                while (peek().kind() == TokenKind.OR) {
                    p++;
                    Expr right = parseAnd();
                    left = new Expr.Binary(Expr.Op.OR, left, right);
                }
                return left;
            } finally {
                depth--;
            }
        }

        Expr parseAnd() {
            Expr left = parseEquality();
            while (peek().kind() == TokenKind.AND) {
                p++;
                Expr right = parseEquality();
                left = new Expr.Binary(Expr.Op.AND, left, right);
            }
            return left;
        }

        Expr parseEquality() {
            Expr left = parseComparison();
            while (peek().kind() == TokenKind.EQ || peek().kind() == TokenKind.NE) {
                Expr.Op op = peek().kind() == TokenKind.EQ ? Expr.Op.EQ : Expr.Op.NE;
                p++;
                Expr right = parseComparison();
                left = new Expr.Binary(op, left, right);
            }
            return left;
        }

        /** 比较层。禁止连串——第二个比较符直接 parse error（见类注释）。 */
        Expr parseComparison() {
            Expr left = parseAdditive();
            Expr.Op op = cmpOp(peek().kind());
            if (op == null) return left;
            p++;
            Expr right = parseAdditive();
            Expr result = new Expr.Binary(op, left, right);
            Token next = peek();
            if (cmpOp(next.kind()) != null) {
                throw new ParseException(
                        "chained comparison is not allowed (use && to combine)", next.pos());
            }
            return result;
        }

        private static Expr.Op cmpOp(TokenKind k) {
            return switch (k) {
                case LT -> Expr.Op.LT;
                case LE -> Expr.Op.LE;
                case GT -> Expr.Op.GT;
                case GE -> Expr.Op.GE;
                default -> null;
            };
        }

        Expr parseAdditive() {
            Expr left = parseMultiplicative();
            while (peek().kind() == TokenKind.PLUS || peek().kind() == TokenKind.MINUS) {
                Expr.Op op = peek().kind() == TokenKind.PLUS ? Expr.Op.ADD : Expr.Op.SUB;
                p++;
                Expr right = parseMultiplicative();
                left = new Expr.Binary(op, left, right);
            }
            return left;
        }

        Expr parseMultiplicative() {
            Expr left = parseUnary();
            while (peek().kind() == TokenKind.STAR || peek().kind() == TokenKind.SLASH) {
                Expr.Op op = peek().kind() == TokenKind.STAR ? Expr.Op.MUL : Expr.Op.DIV;
                p++;
                Expr right = parseUnary();
                left = new Expr.Binary(op, left, right);
            }
            return left;
        }

        Expr parseUnary() {
            if (peek().kind() == TokenKind.NOT) {
                enterDepth();
                try {
                    p++;
                    return new Expr.Not(parseUnary());
                } finally {
                    depth--;
                }
            }
            if (peek().kind() == TokenKind.MINUS) {
                enterDepth();
                try {
                    p++;
                    Expr inner = parseUnary();
                    // 数字字面量常量折叠：parse("-1.5") 仍是 Literal(-1.5)（既有 AST 形态）
                    if (inner instanceof Expr.Literal lit && lit.value() instanceof Double d) {
                        return new Expr.Literal(-d);
                    }
                    return new Expr.Neg(inner);
                } finally {
                    depth--;
                }
            }
            return parsePrimary();
        }

        Expr parsePrimary() {
            Token t = peek();
            switch (t.kind()) {
                case TRUE  -> { p++; return new Expr.Literal(Boolean.TRUE); }
                case FALSE -> { p++; return new Expr.Literal(Boolean.FALSE); }
                case NUMBER -> {
                    p++;
                    try {
                        return new Expr.Literal(Double.parseDouble(t.text()));
                    } catch (NumberFormatException nfe) {
                        throw new ParseException("invalid number '" + t.text() + "'", t.pos());
                    }
                }
                case STRING -> { p++; return new Expr.Literal(t.text()); }
                case IDENT -> {
                    // var 是保留字函数——var("fullName") → VarRef；
                    // 裸 var（不跟 LPAREN）直接拒，不退化成 Identifier
                    if ("var".equals(t.text())) {
                        p++;
                        if (peek().kind() != TokenKind.LPAREN) {
                            throw new ParseException(
                                    "'var' is reserved; expected var(\"fullName\")", t.pos());
                        }
                        p++; // consume LPAREN
                        Token arg = consume(TokenKind.STRING);
                        consume(TokenKind.RPAREN);
                        return new Expr.VarRef(arg.text());
                    }
                    p++;
                    return new Expr.Identifier(t.text());
                }
                case LPAREN -> {
                    p++;
                    Expr inner = parseOr();
                    consume(TokenKind.RPAREN);
                    return inner;
                }
                default -> throw new ParseException("unexpected token '" + t.text() + "'", t.pos());
            }
        }
    }
}
