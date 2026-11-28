/**
 * 模板与脚本条件表达式前端解析与求值器。
 *
 * 1:1 镜像后端 `ExpressionParser` + `ExpressionEvaluator` + `StrictNumber`
 * （契约见 `docs/template-pack.md` 与 `docs/scripting.md`）：
 *   - 运算符与优先级（低 → 高）：`||` → `&&` → `== !=` → `< <= > >=`（禁止连串）
 *     → `+ -` → `* /` → 一元 `! -` → primary（括号、字面量、标识符、`var("...")`）
 *   - 词法：独立 `MINUS` token、支持 `.5` 前导点小数、源串禁写 `1.2.3` 与科学计数法、
 *     转义 `\n \t \r \\ \" \'`、`var` 为保留关键字、递归嵌套上限 `MAX_DEPTH = 64`
 *   - 求值：`&&` / `||` 惰性短路、`params` 自有属性校验（未声明抛 `UndeclaredParamError`）、
 *     `StrictNumber` ASCII 空白修剪与数值文法、双侧数值相等 / 单侧数值序比较、除零得 `0`、
 *     `norm()` 统一消除 `-0` 与非有限值
 *   - `evalBoolean` 作为模板表单 `visible_when` 入口保持容错（空串或异常一律返 `true`）
 */

export type BinaryOp =
    | 'EQ' | 'NE' | 'AND' | 'OR'
    | 'LT' | 'LE' | 'GT' | 'GE'
    | 'ADD' | 'SUB' | 'MUL' | 'DIV';

export type Expr =
    | { kind: 'literal'; value: string | number | boolean }
    | { kind: 'identifier'; name: string }
    | { kind: 'var'; fullName: string }
    | { kind: 'not'; inner: Expr }
    | { kind: 'neg'; inner: Expr }
    | { kind: 'binary'; op: BinaryOp; left: Expr; right: Expr };

export type VarResolver = (fullName: string) => string | null | undefined;

/** 词法或语法解析错误，`position` 为源字符串中的 0-based 偏移。 */
export class ParseError extends Error {
    readonly position: number;

    constructor(message: string, position: number) {
        super(`${message} (at position ${position})`);
        this.name = 'ParseError';
        this.position = position;
    }
}

/** 引用了未在 `params` 中声明的标识符。 */
export class UndeclaredParamError extends Error {
    readonly paramName: string;

    constructor(paramName: string) {
        super(`undeclared parameter '${paramName}' in expression`);
        this.name = 'UndeclaredParamError';
        this.paramName = paramName;
    }
}

/**
 * 模板参数 `visible_when` 容错求值入口：空表达式或任何解析 / 求值错误时保守返回 `true`。
 */
export function evalBoolean(
    expr: string | undefined | null,
    params: Record<string, unknown>,
    varResolver?: VarResolver,
): boolean {
    if (!expr || !expr.trim()) return true;
    try {
        const ast = parseExpression(expr);
        return truthy(evaluateExpression(ast, params, varResolver));
    } catch {
        return true;
    }
}

/** 严格解析表达式为 AST；任何词法 / 语法 / 深度错误均抛 {@link ParseError}。 */
export function parseExpression(src: string): Expr {
    if (src == null) throw new ParseError('expression is null', 0);
    const tokens = lex(src);
    const parser = new Parser(tokens);
    const expr = parser.parseOr();
    const next = parser.peek();
    if (next.kind !== 'EOF') {
        throw new ParseError(`unexpected token '${next.text}'`, next.pos);
    }
    return expr;
}

/** 严格求值 AST 返回原始值；未声明参数抛 {@link UndeclaredParamError}。 */
export function evaluateExpression(
    expr: Expr,
    params: Record<string, unknown>,
    varResolver?: VarResolver,
): unknown {
    switch (expr.kind) {
        case 'literal':
            return expr.value;
        case 'identifier':
            if (params == null || !Object.prototype.hasOwnProperty.call(params, expr.name)) {
                throw new UndeclaredParamError(expr.name);
            }
            return params[expr.name];
        case 'var':
            if (!varResolver) {
                throw new Error('var(...) is not available without a variable resolver');
            }
            return varResolver(expr.fullName) ?? null;
        case 'not':
            return !truthy(evaluateExpression(expr.inner, params, varResolver));
        case 'neg':
            return norm(-toNumber(evaluateExpression(expr.inner, params, varResolver)));
        case 'binary':
            switch (expr.op) {
                case 'AND':
                    return truthy(evaluateExpression(expr.left, params, varResolver))
                        && truthy(evaluateExpression(expr.right, params, varResolver));
                case 'OR':
                    return truthy(evaluateExpression(expr.left, params, varResolver))
                        || truthy(evaluateExpression(expr.right, params, varResolver));
                case 'EQ':
                    return equals(
                        evaluateExpression(expr.left, params, varResolver),
                        evaluateExpression(expr.right, params, varResolver),
                    );
                case 'NE':
                    return !equals(
                        evaluateExpression(expr.left, params, varResolver),
                        evaluateExpression(expr.right, params, varResolver),
                    );
                case 'LT':
                    return compare(
                        evaluateExpression(expr.left, params, varResolver),
                        evaluateExpression(expr.right, params, varResolver),
                    ) < 0;
                case 'LE':
                    return compare(
                        evaluateExpression(expr.left, params, varResolver),
                        evaluateExpression(expr.right, params, varResolver),
                    ) <= 0;
                case 'GT':
                    return compare(
                        evaluateExpression(expr.left, params, varResolver),
                        evaluateExpression(expr.right, params, varResolver),
                    ) > 0;
                case 'GE':
                    return compare(
                        evaluateExpression(expr.left, params, varResolver),
                        evaluateExpression(expr.right, params, varResolver),
                    ) >= 0;
                case 'ADD':
                    return norm(
                        toNumber(evaluateExpression(expr.left, params, varResolver))
                        + toNumber(evaluateExpression(expr.right, params, varResolver)),
                    );
                case 'SUB':
                    return norm(
                        toNumber(evaluateExpression(expr.left, params, varResolver))
                        - toNumber(evaluateExpression(expr.right, params, varResolver)),
                    );
                case 'MUL':
                    return norm(
                        toNumber(evaluateExpression(expr.left, params, varResolver))
                        * toNumber(evaluateExpression(expr.right, params, varResolver)),
                    );
                case 'DIV':
                    return divide(
                        toNumber(evaluateExpression(expr.left, params, varResolver)),
                        toNumber(evaluateExpression(expr.right, params, varResolver)),
                    );
            }
    }
}

/** 严格求值 AST 并按契约转换为布尔值。 */
export function evaluateBooleanStrict(
    expr: Expr,
    params: Record<string, unknown>,
    varResolver?: VarResolver,
): boolean {
    return truthy(evaluateExpression(expr, params, varResolver));
}

// ============================ Lexer ============================

type TokenKind =
    | 'IDENT' | 'NUMBER' | 'STRING' | 'TRUE' | 'FALSE'
    | 'EQ' | 'NE' | 'AND' | 'OR' | 'NOT'
    | 'LT' | 'LE' | 'GT' | 'GE' | 'PLUS' | 'MINUS' | 'STAR' | 'SLASH'
    | 'LPAREN' | 'RPAREN' | 'EOF';

interface Token {
    kind: TokenKind;
    text: string;
    pos: number;
}

const IDENT_START_RE = /^[\p{L}_]$/u;
const IDENT_CONT_RE = /^[\p{L}\p{Nd}_0-9]$/u;

function isDigit(c: string): boolean {
    return c >= '0' && c <= '9';
}

function isIdentStart(c: string): boolean {
    return IDENT_START_RE.test(c);
}

function isIdentCont(c: string): boolean {
    return IDENT_CONT_RE.test(c);
}

function lex(src: string): Token[] {
    const out: Token[] = [];
    let i = 0;

    const peek = (offset: number): string => {
        const j = i + offset;
        return j < src.length ? src[j] : '\0';
    };

    while (i < src.length) {
        const c = src[i];
        if (/\s/.test(c)) {
            i++;
            continue;
        }
        const start = i;
        if (c === '"' || c === "'") {
            let val = '';
            i++;
            let closed = false;
            while (i < src.length) {
                const ch = src[i];
                if (ch === '\\' && i + 1 < src.length) {
                    const next = src[i + 1];
                    switch (next) {
                        case 'n': val += '\n'; break;
                        case 't': val += '\t'; break;
                        case 'r': val += '\r'; break;
                        case '\\': val += '\\'; break;
                        case '"': val += '"'; break;
                        case "'": val += "'"; break;
                        default: val += next; break;
                    }
                    i += 2;
                    continue;
                }
                if (ch === c) {
                    i++;
                    closed = true;
                    break;
                }
                val += ch;
                i++;
            }
            if (!closed) {
                throw new ParseError('unterminated string literal', start);
            }
            out.push({ kind: 'STRING', text: val, pos: start });
            continue;
        }
        // 负号统一发 MINUS token 由 unary 处理（保证 1-2 是二元减法而非两个相连字面量）
        if (isDigit(c) || (c === '.' && i + 1 < src.length && isDigit(src[i + 1]))) {
            let j = i;
            while (j < src.length && isDigit(src[j])) j++;
            if (j < src.length && src[j] === '.') {
                j++;
                while (j < src.length && isDigit(src[j])) j++;
            }
            out.push({ kind: 'NUMBER', text: src.slice(i, j), pos: start });
            i = j;
            continue;
        }
        if (isIdentStart(c)) {
            let j = i + 1;
            while (j < src.length && isIdentCont(src[j])) j++;
            const word = src.slice(i, j);
            i = j;
            if (word === 'true') out.push({ kind: 'TRUE', text: word, pos: start });
            else if (word === 'false') out.push({ kind: 'FALSE', text: word, pos: start });
            else out.push({ kind: 'IDENT', text: word, pos: start });
            continue;
        }
        if (c === '=' && peek(1) === '=') { i += 2; out.push({ kind: 'EQ', text: '==', pos: start }); continue; }
        if (c === '!' && peek(1) === '=') { i += 2; out.push({ kind: 'NE', text: '!=', pos: start }); continue; }
        if (c === '&' && peek(1) === '&') { i += 2; out.push({ kind: 'AND', text: '&&', pos: start }); continue; }
        if (c === '|' && peek(1) === '|') { i += 2; out.push({ kind: 'OR', text: '||', pos: start }); continue; }
        if (c === '<' && peek(1) === '=') { i += 2; out.push({ kind: 'LE', text: '<=', pos: start }); continue; }
        if (c === '>' && peek(1) === '=') { i += 2; out.push({ kind: 'GE', text: '>=', pos: start }); continue; }
        if (c === '<') { i++; out.push({ kind: 'LT', text: '<', pos: start }); continue; }
        if (c === '>') { i++; out.push({ kind: 'GT', text: '>', pos: start }); continue; }
        if (c === '+') { i++; out.push({ kind: 'PLUS', text: '+', pos: start }); continue; }
        if (c === '-') { i++; out.push({ kind: 'MINUS', text: '-', pos: start }); continue; }
        if (c === '*') { i++; out.push({ kind: 'STAR', text: '*', pos: start }); continue; }
        if (c === '/') { i++; out.push({ kind: 'SLASH', text: '/', pos: start }); continue; }
        if (c === '!') { i++; out.push({ kind: 'NOT', text: '!', pos: start }); continue; }
        if (c === '(') { i++; out.push({ kind: 'LPAREN', text: '(', pos: start }); continue; }
        if (c === ')') { i++; out.push({ kind: 'RPAREN', text: ')', pos: start }); continue; }
        throw new ParseError(`unexpected character '${c}'`, start);
    }

    out.push({ kind: 'EOF', text: '', pos: src.length });
    return out;
}

// ============================ Parser ============================

const MAX_DEPTH = 64;

function cmpOp(kind: TokenKind): BinaryOp | null {
    switch (kind) {
        case 'LT': return 'LT';
        case 'LE': return 'LE';
        case 'GT': return 'GT';
        case 'GE': return 'GE';
        default: return null;
    }
}

class Parser {
    private readonly tokens: Token[];
    private p = 0;
    private depth = 0;

    constructor(tokens: Token[]) {
        this.tokens = tokens;
    }

    peek(): Token {
        return this.tokens[this.p];
    }

    private enterDepth(): void {
        if (++this.depth > MAX_DEPTH) {
            throw new ParseError(`expression nesting too deep (> ${MAX_DEPTH})`, this.peek().pos);
        }
    }

    private consume(expected: TokenKind): Token {
        const t = this.peek();
        if (t.kind !== expected) {
            throw new ParseError(`expected ${expected} got ${t.kind} '${t.text}'`, t.pos);
        }
        this.p++;
        return t;
    }

    parseOr(): Expr {
        this.enterDepth();
        try {
            let left = this.parseAnd();
            while (this.peek().kind === 'OR') {
                this.p++;
                const right = this.parseAnd();
                left = { kind: 'binary', op: 'OR', left, right };
            }
            return left;
        } finally {
            this.depth--;
        }
    }

    private parseAnd(): Expr {
        let left = this.parseEquality();
        while (this.peek().kind === 'AND') {
            this.p++;
            const right = this.parseEquality();
            left = { kind: 'binary', op: 'AND', left, right };
        }
        return left;
    }

    private parseEquality(): Expr {
        let left = this.parseComparison();
        while (this.peek().kind === 'EQ' || this.peek().kind === 'NE') {
            const op: BinaryOp = this.peek().kind === 'EQ' ? 'EQ' : 'NE';
            this.p++;
            const right = this.parseComparison();
            left = { kind: 'binary', op, left, right };
        }
        return left;
    }

    private parseComparison(): Expr {
        const left = this.parseAdditive();
        const op = cmpOp(this.peek().kind);
        if (op === null) return left;
        this.p++;
        const right = this.parseAdditive();
        const result: Expr = { kind: 'binary', op, left, right };
        const next = this.peek();
        if (cmpOp(next.kind) !== null) {
            throw new ParseError('chained comparison is not allowed (use && to combine)', next.pos);
        }
        return result;
    }

    private parseAdditive(): Expr {
        let left = this.parseMultiplicative();
        while (this.peek().kind === 'PLUS' || this.peek().kind === 'MINUS') {
            const op: BinaryOp = this.peek().kind === 'PLUS' ? 'ADD' : 'SUB';
            this.p++;
            const right = this.parseMultiplicative();
            left = { kind: 'binary', op, left, right };
        }
        return left;
    }

    private parseMultiplicative(): Expr {
        let left = this.parseUnary();
        while (this.peek().kind === 'STAR' || this.peek().kind === 'SLASH') {
            const op: BinaryOp = this.peek().kind === 'STAR' ? 'MUL' : 'DIV';
            this.p++;
            const right = this.parseUnary();
            left = { kind: 'binary', op, left, right };
        }
        return left;
    }

    private parseUnary(): Expr {
        if (this.peek().kind === 'NOT') {
            this.enterDepth();
            try {
                this.p++;
                return { kind: 'not', inner: this.parseUnary() };
            } finally {
                this.depth--;
            }
        }
        if (this.peek().kind === 'MINUS') {
            this.enterDepth();
            try {
                this.p++;
                const inner = this.parseUnary();
                if (inner.kind === 'literal' && typeof inner.value === 'number') {
                    const neg = -inner.value;
                    return { kind: 'literal', value: neg === 0 ? 0 : neg };
                }
                return { kind: 'neg', inner };
            } finally {
                this.depth--;
            }
        }
        return this.parsePrimary();
    }

    private parsePrimary(): Expr {
        const t = this.peek();
        switch (t.kind) {
            case 'TRUE':
                this.p++;
                return { kind: 'literal', value: true };
            case 'FALSE':
                this.p++;
                return { kind: 'literal', value: false };
            case 'NUMBER': {
                this.p++;
                const v = Number.parseFloat(t.text);
                if (Number.isNaN(v)) {
                    throw new ParseError(`invalid number '${t.text}'`, t.pos);
                }
                return { kind: 'literal', value: v };
            }
            case 'STRING':
                this.p++;
                return { kind: 'literal', value: t.text };
            case 'IDENT': {
                if (t.text === 'var') {
                    this.p++;
                    if (this.peek().kind !== 'LPAREN') {
                        throw new ParseError("'var' is reserved; expected var(\"fullName\")", t.pos);
                    }
                    this.p++;
                    const arg = this.consume('STRING');
                    this.consume('RPAREN');
                    return { kind: 'var', fullName: arg.text };
                }
                this.p++;
                return { kind: 'identifier', name: t.text };
            }
            case 'LPAREN': {
                this.p++;
                const inner = this.parseOr();
                this.consume('RPAREN');
                return inner;
            }
            default:
                throw new ParseError(`unexpected token '${t.text}'`, t.pos);
        }
    }
}

// ============================ Evaluator & StrictNumber ============================

// 对齐 Java String.trim()（仅剥 U+0000..U+0020），避免 JS trim() 把 NBSP / 全角空格也剥掉造成双端分叉。
const ASCII_TRIM_RE = /^[\x00-\x20]+|[\x00-\x20]+$/g;
const STRICT_NUMBER_RE = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;

function asciiTrim(s: string): string {
    return s.replace(ASCII_TRIM_RE, '');
}

function norm(d: number): number {
    if (!Number.isFinite(d)) return 0;
    return d === 0 ? 0 : d;
}

function parseStrictNumber(raw: string | null | undefined): number {
    if (raw == null) return 0;
    const trimmed = asciiTrim(raw);
    if (!STRICT_NUMBER_RE.test(trimmed)) return 0;
    return norm(Number.parseFloat(trimmed));
}

function isNumeric(v: unknown): boolean {
    if (typeof v === 'number') return true;
    if (typeof v === 'string') {
        return STRICT_NUMBER_RE.test(asciiTrim(v));
    }
    return false;
}

function toNumber(v: unknown): number {
    if (typeof v === 'number') return norm(v);
    return parseStrictNumber(v == null ? null : String(v));
}

function truthy(v: unknown): boolean {
    if (v == null) return false;
    if (typeof v === 'boolean') return v;
    if (typeof v === 'number') return v !== 0;
    if (typeof v === 'string') return v.length > 0;
    return true;
}

function equals(a: unknown, b: unknown): boolean {
    if (a == null || b == null) return a == null && b == null;
    if (isNumeric(a) && isNumeric(b)) {
        return toNumber(a) === toNumber(b);
    }
    if (typeof a === 'boolean' || typeof b === 'boolean') {
        return truthy(a) === truthy(b);
    }
    return String(a) === String(b);
}

function compare(a: unknown, b: unknown): number {
    if (isNumeric(a) || isNumeric(b)) {
        const na = toNumber(a);
        const nb = toNumber(b);
        return na < nb ? -1 : na > nb ? 1 : 0;
    }
    const sa = a == null ? '' : String(a);
    const sb = b == null ? '' : String(b);
    return sa < sb ? -1 : sa > sb ? 1 : 0;
}

function divide(a: number, b: number): number {
    if (b === 0) return 0;
    return norm(a / b);
}
