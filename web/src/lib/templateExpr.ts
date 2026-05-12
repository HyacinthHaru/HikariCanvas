/**
 * 模板表达式前端镜像。覆盖 {@code docs/template-spec.md §6} 的子集：
 * 运算符 {@code == != && || !}、括号、字面量（string/number/bool）、参数引用。
 *
 * <p>这是 Java {@link moe.hikari.canvas.template.expr.ExpressionEvaluator} 的 TS 等价物，
 * 用于 TemplateParamForm 的 visible_when 实时求值。</p>
 *
 * <p>解析失败时返回 {@code true}（保守地显示字段，避免参数面板莫名隐藏）。</p>
 */

export function evalBoolean(expr: string | undefined | null, params: Record<string, unknown>): boolean {
    if (!expr || !expr.trim()) return true;
    try {
        const tokens = lex(expr);
        const [ast, next] = parseOr(tokens, 0);
        if (next !== tokens.length) return true;
        return truthy(evaluate(ast, params));
    } catch {
        return true;
    }
}

// ---------- AST ----------

type Expr =
    | { kind: 'lit'; value: unknown }
    | { kind: 'id'; name: string }
    | { kind: 'not'; inner: Expr }
    | { kind: 'bin'; op: 'EQ' | 'NE' | 'AND' | 'OR'; left: Expr; right: Expr };

// ---------- lexer ----------

type Tok =
    | { k: 'IDENT' | 'NUMBER' | 'STRING'; v: string }
    | { k: 'TRUE' | 'FALSE' | 'EQ' | 'NE' | 'AND' | 'OR' | 'NOT' | 'LP' | 'RP' };

function lex(src: string): Tok[] {
    const out: Tok[] = [];
    let i = 0;
    while (i < src.length) {
        const c = src[i];
        if (/\s/.test(c)) { i++; continue; }
        if (c === '"' || c === "'") {
            let j = i + 1;
            let val = '';
            while (j < src.length && src[j] !== c) {
                if (src[j] === '\\' && j + 1 < src.length) {
                    const n = src[j + 1];
                    val += n === 'n' ? '\n' : n === 't' ? '\t' : n;
                    j += 2;
                } else {
                    val += src[j];
                    j++;
                }
            }
            if (j >= src.length) throw new Error('unterminated string');
            out.push({ k: 'STRING', v: val });
            i = j + 1;
            continue;
        }
        if (/[0-9]/.test(c) || (c === '-' && /[0-9]/.test(src[i + 1] ?? ''))) {
            let j = i + 1;
            while (j < src.length && /[0-9.]/.test(src[j])) j++;
            out.push({ k: 'NUMBER', v: src.slice(i, j) });
            i = j;
            continue;
        }
        if (/[A-Za-z_]/.test(c)) {
            let j = i + 1;
            while (j < src.length && /[A-Za-z0-9_]/.test(src[j])) j++;
            const word = src.slice(i, j);
            if (word === 'true') out.push({ k: 'TRUE' });
            else if (word === 'false') out.push({ k: 'FALSE' });
            else out.push({ k: 'IDENT', v: word });
            i = j;
            continue;
        }
        if (c === '=' && src[i + 1] === '=') { out.push({ k: 'EQ' }); i += 2; continue; }
        if (c === '!' && src[i + 1] === '=') { out.push({ k: 'NE' }); i += 2; continue; }
        if (c === '&' && src[i + 1] === '&') { out.push({ k: 'AND' }); i += 2; continue; }
        if (c === '|' && src[i + 1] === '|') { out.push({ k: 'OR' }); i += 2; continue; }
        if (c === '!') { out.push({ k: 'NOT' }); i++; continue; }
        if (c === '(') { out.push({ k: 'LP' }); i++; continue; }
        if (c === ')') { out.push({ k: 'RP' }); i++; continue; }
        throw new Error(`unexpected '${c}'`);
    }
    return out;
}

// ---------- parser（递归下降，优先级 ! > == > && > ||） ----------

function parseOr(t: Tok[], p: number): [Expr, number] {
    let [left, np] = parseAnd(t, p);
    while (t[np]?.k === 'OR') {
        const [right, n2] = parseAnd(t, np + 1);
        left = { kind: 'bin', op: 'OR', left, right };
        np = n2;
    }
    return [left, np];
}

function parseAnd(t: Tok[], p: number): [Expr, number] {
    let [left, np] = parseEq(t, p);
    while (t[np]?.k === 'AND') {
        const [right, n2] = parseEq(t, np + 1);
        left = { kind: 'bin', op: 'AND', left, right };
        np = n2;
    }
    return [left, np];
}

function parseEq(t: Tok[], p: number): [Expr, number] {
    let [left, np] = parseUnary(t, p);
    while (t[np]?.k === 'EQ' || t[np]?.k === 'NE') {
        const op = t[np].k === 'EQ' ? 'EQ' : 'NE';
        const [right, n2] = parseUnary(t, np + 1);
        left = { kind: 'bin', op, left, right };
        np = n2;
    }
    return [left, np];
}

function parseUnary(t: Tok[], p: number): [Expr, number] {
    if (t[p]?.k === 'NOT') {
        const [inner, np] = parseUnary(t, p + 1);
        return [{ kind: 'not', inner }, np];
    }
    return parsePrimary(t, p);
}

function parsePrimary(t: Tok[], p: number): [Expr, number] {
    const tk = t[p];
    if (!tk) throw new Error('unexpected end');
    if (tk.k === 'TRUE') return [{ kind: 'lit', value: true }, p + 1];
    if (tk.k === 'FALSE') return [{ kind: 'lit', value: false }, p + 1];
    if (tk.k === 'NUMBER') return [{ kind: 'lit', value: parseFloat(tk.v) }, p + 1];
    if (tk.k === 'STRING') return [{ kind: 'lit', value: tk.v }, p + 1];
    if (tk.k === 'IDENT') return [{ kind: 'id', name: tk.v }, p + 1];
    if (tk.k === 'LP') {
        const [inner, np] = parseOr(t, p + 1);
        if (t[np]?.k !== 'RP') throw new Error('expected )');
        return [inner, np + 1];
    }
    throw new Error('unexpected token');
}

// ---------- evaluator ----------

function evaluate(e: Expr, params: Record<string, unknown>): unknown {
    if (e.kind === 'lit') return e.value;
    if (e.kind === 'id') return params[e.name];
    if (e.kind === 'not') return !truthy(evaluate(e.inner, params));
    const l = evaluate(e.left, params);
    const r = evaluate(e.right, params);
    switch (e.op) {
        case 'AND': return truthy(l) && truthy(r);
        case 'OR':  return truthy(l) || truthy(r);
        case 'EQ':  return equals(l, r);
        case 'NE':  return !equals(l, r);
    }
}

function truthy(v: unknown): boolean {
    if (v == null) return false;
    if (typeof v === 'boolean') return v;
    if (typeof v === 'number') return v !== 0;
    if (typeof v === 'string') return v.length > 0;
    return true;
}

function equals(a: unknown, b: unknown): boolean {
    if (a == null || b == null) return a === b;
    if (typeof a === 'number' && typeof b === 'number') return a === b;
    if (typeof a === 'boolean' || typeof b === 'boolean') return truthy(a) === truthy(b);
    return String(a) === String(b);
}
