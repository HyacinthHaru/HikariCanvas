/**
 * 0.7.0-P5-G（K-UI-6）：if 积木 condition 的「简化条件模型 ↔ 字符串」双向转换。
 *
 * <p>普通用户用「操作数 比较符 操作数」逐行拼条件（同一 {@code joiner} 连所有行），本模块负责
 * 把这个 {@link SimpleCondition} <b>序列化</b>成后端 {@code ExpressionParser} 能解析的表达式串
 * （{@link buildConditionString}），以及把已有 condition 串<b>尽力反解析</b>回模型
 * （{@link tryParseCondition}）；复杂 / 超出简化文法的串反解析失败返回 {@code null}，UI 据此
 * 切「高级文本框」模式直接编辑原串。</p>
 *
 * <h2>后端文法权威（务必一致）</h2>
 * 见 {@code plugin/src/main/java/moe/hikari/canvas/template/expr/ExpressionParser.java}：
 * <ul>
 *   <li>操作数（这里只支持其中 4 种「叶子」形态）：{@code var("fullName")} / 数字 / 带引号字符串
 *       / {@code true} | {@code false}；</li>
 *   <li>比较符：{@code == != < <= > >=}（cmp 层<b>禁连串</b>，{@code 1 < 2 < 3} 后端 parse error）；</li>
 *   <li>字符串字面量：双引号包裹，内部 {@code "} 与 {@code \} 转义（{@code \" \\}）；后端 lexer
 *       的 {@code readString} 还认 {@code \n \t \r \'}，但我们 build 只产出 {@code \" \\} 两种转义，
 *       parse 也只解开这两种 + 保留未知转义的「去反斜杠」行为与后端一致（{@code default -> n}）。</li>
 * </ul>
 *
 * <h2>简化文法（{@link tryParseCondition} 只认这一形态，其余一律 null）</h2>
 * <pre>
 *   condition := row ( JOINER row )*          —— JOINER 顶层只能<b>全 &&</b> 或<b>全 ||</b>，混用 → null
 *   row       := operand CMP operand          —— 恰好一个比较，连串 / 算术 / ! / 嵌套括号残留 → null
 *   operand   := var("..") | number | "str" | true | false
 * </pre>
 *
 * <p><b>稳健性优先于覆盖率</b>：宁可对一个其实合法的复杂串返 {@code null}（用户落到高级文本框，
 * 仍能编辑），也<b>绝不</b>把一个含算术 / 取反 / 嵌套的串误解析成结构错误的简化模型再 build
 * 回去把语义改坏。所以反解析器是「严格白名单」式——只要遇到任何不在简化文法内的 token / 结构
 * 立刻 null。</p>
 */

/** 比较符（与后端 cmp 层一致）。 */
export type CmpOp = '==' | '!=' | '<' | '<=' | '>' | '>=';

/** 所有比较符（UI 下拉用）。 */
export const CMP_OPS: readonly CmpOp[] = ['==', '!=', '<', '<=', '>', '>='];

/**
 * 单个操作数（叶子）。{@code kind} 决定序列化形态：
 * - {@code var}  → {@code var("name")}（name 内部按字符串规则转义）
 * - {@code number} → 友好数字串（整数无小数点，见 {@link formatNumber}）
 * - {@code string} → 双引号字符串（内部 {@code " \} 转义）
 * - {@code bool} → {@code true} / {@code false}
 */
export type Operand =
    | { kind: 'var'; name: string }
    | { kind: 'number'; value: number }
    | { kind: 'string'; value: string }
    | { kind: 'bool'; value: boolean };

/** 操作数类型标签（UI 类型下拉用）。 */
export type OperandKind = Operand['kind'];

/** 一行比较：{@code lhs op rhs}。 */
export interface ConditionRow {
    lhs: Operand;
    op: CmpOp;
    rhs: Operand;
}

/** joiner（v1 同一 joiner 连所有行）。 */
export type Joiner = '&&' | '||';

/** 简化条件模型：多行 + 单一 joiner。 */
export interface SimpleCondition {
    rows: ConditionRow[];
    joiner: Joiner;
}

// ============================================================================
// 序列化：模型 → 字符串
// ============================================================================

/**
 * 数字 → 友好串（与后端 StrictNumber 友好形态对齐：整数不带小数点）。
 *
 * <p>整数（{@code Number.isInteger}）用 {@code String(n)}（{@code 42} 而非 {@code 42.0}）；
 * 小数原样 {@code String(n)}（JS 默认最短往返表示，如 {@code 1.5}）。非有限值（{@code NaN} /
 * {@code Infinity}）兜底成 {@code 0}——后端文法不接受这些，且条件里出现它们无意义。
 * 负数前缀 {@code -} 由 {@code String} 自然给出（后端 unary MINUS 常量折叠成 {@code Literal(-n)}，
 * 往返语义一致）。</p>
 */
export function formatNumber(n: number): string {
    if (!Number.isFinite(n)) return '0';
    if (Number.isInteger(n)) return String(n);
    return String(n);
}

/**
 * 字符串字面量转义（与后端 {@code readString} 反向）：包一层双引号，内部 {@code \} → {@code \\}、
 * {@code "} → {@code \"}。<b>只转这两种</b>——其它字符（含换行 / tab）原样写进引号内。
 *
 * <p>注意 {@code \} 必须先于 {@code "} 替换（否则会把刚插入的转义反斜杠再次转义）。</p>
 */
export function escapeStringLiteral(s: string): string {
    const escaped = s.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
    return `"${escaped}"`;
}

/** 单个操作数 → 串。 */
export function operandToString(op: Operand): string {
    switch (op.kind) {
        case 'var':
            return `var(${escapeStringLiteral(op.name)})`;
        case 'number':
            return formatNumber(op.value);
        case 'string':
            return escapeStringLiteral(op.value);
        case 'bool':
            return op.value ? 'true' : 'false';
    }
}

/** 一行 → 串：{@code operand op operand}（操作数与比较符间各一空格）。 */
export function rowToString(row: ConditionRow): string {
    return `${operandToString(row.lhs)} ${row.op} ${operandToString(row.rhs)}`;
}

/**
 * 模型 → condition 字符串。
 *
 * <ul>
 *   <li>空 rows → {@code ''}（空条件；后端 validator 会拒——UI 应在保存前阻止空条件）；</li>
 *   <li>单行 → 直接 {@link rowToString}（不加括号）；</li>
 *   <li>多行 → 每行用 {@code ( ... )} 包裹再用 {@code  ${joiner} } 连——加括号防 {@code &&} / {@code ||}
 *       与行内比较的优先级歧义（虽然比较优先级高于 &&/||，加括号让产物更直观且对未来扩展安全）。</li>
 * </ul>
 */
export function buildConditionString(c: SimpleCondition): string {
    if (c.rows.length === 0) return '';
    if (c.rows.length === 1) return rowToString(c.rows[0]);
    const sep = ` ${c.joiner} `;
    return c.rows.map((r) => `(${rowToString(r)})`).join(sep);
}

// ============================================================================
// 反解析：字符串 → 模型（尽力，失败 null）
// ============================================================================

/** 反解析内部 token 类型（最小化，仅覆盖简化文法所需）。 */
type Tok =
    | { t: 'var'; name: string }
    | { t: 'num'; value: number }
    | { t: 'str'; value: string }
    | { t: 'bool'; value: boolean }
    | { t: 'cmp'; op: CmpOp }
    | { t: 'and' }
    | { t: 'or' }
    | { t: 'lparen' }
    | { t: 'rparen' };

/** 数字字面量文法（镜像后端 StrictNumber.PATTERN：可选符号 + 整数/小数 + 可选指数）。 */
const NUM_RE = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?/;

/**
 * 最小化 tokenizer：把简化条件串切成 token 序列；<b>遇到任何不属于简化文法的字符立刻抛</b>
 * （由 {@link tryParseCondition} catch 成 null）。
 *
 * <p>故意<b>不</b>支持后端完整文法的算术符 {@code + - * /}（除作为数字字面量前缀符号）、{@code !}、
 * 标识符（裸 ident）等——这些一旦出现说明是复杂表达式，应落高级文本框。{@code +}/{@code -} 仅在
 * 「紧跟数字」时作为数字符号被 {@link NUM_RE} 吃掉；独立出现（如 {@code a + b}）会走到「未知字符」
 * 分支抛错。</p>
 */
function tokenize(src: string): Tok[] {
    const out: Tok[] = [];
    let i = 0;
    const n = src.length;
    while (i < n) {
        const c = src[i];
        // 空白
        if (c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === '\f' || c === '\v') {
            i++;
            continue;
        }
        // 字符串字面量（单 / 双引号；与后端 readString 一致解开 \\ \" \' \n \t \r + 未知转义去斜杠）
        if (c === '"' || c === "'") {
            const [value, next] = readString(src, i, c);
            out.push({ t: 'str', value });
            i = next;
            continue;
        }
        // 比较符（双字符优先）
        if (c === '=' && src[i + 1] === '=') {
            out.push({ t: 'cmp', op: '==' });
            i += 2;
            continue;
        }
        if (c === '!' && src[i + 1] === '=') {
            out.push({ t: 'cmp', op: '!=' });
            i += 2;
            continue;
        }
        if (c === '<' && src[i + 1] === '=') {
            out.push({ t: 'cmp', op: '<=' });
            i += 2;
            continue;
        }
        if (c === '>' && src[i + 1] === '=') {
            out.push({ t: 'cmp', op: '>=' });
            i += 2;
            continue;
        }
        if (c === '<') {
            out.push({ t: 'cmp', op: '<' });
            i++;
            continue;
        }
        if (c === '>') {
            out.push({ t: 'cmp', op: '>' });
            i++;
            continue;
        }
        // 逻辑连接符
        if (c === '&' && src[i + 1] === '&') {
            out.push({ t: 'and' });
            i += 2;
            continue;
        }
        if (c === '|' && src[i + 1] === '|') {
            out.push({ t: 'or' });
            i += 2;
            continue;
        }
        // 括号
        if (c === '(') {
            out.push({ t: 'lparen' });
            i++;
            continue;
        }
        if (c === ')') {
            out.push({ t: 'rparen' });
            i++;
            continue;
        }
        // 数字（含前缀符号；必须在标识符 / var 之前——但 var 以字母开头不会与数字冲突）
        const numMatch = NUM_RE.exec(src.slice(i));
        if (numMatch && numMatch.index === 0) {
            // 仅当 + / - / digit / . 起头才认作数字（避免误吃；NUM_RE 已保证）
            const text = numMatch[0];
            const value = Number(text);
            if (!Number.isFinite(value)) throw new ParseFail();
            out.push({ t: 'num', value });
            i += text.length;
            continue;
        }
        // 标识符起头：只接受关键字 var / true / false；其它裸标识符 → 复杂表达式，抛错
        if (isIdentStart(c)) {
            let j = i + 1;
            while (j < n && isIdentCont(src[j])) j++;
            const word = src.slice(i, j);
            if (word === 'true') {
                out.push({ t: 'bool', value: true });
                i = j;
                continue;
            }
            if (word === 'false') {
                out.push({ t: 'bool', value: false });
                i = j;
                continue;
            }
            if (word === 'var') {
                // var 必须紧跟 ("fullName")，在 tokenize 阶段<b>整体</b>消费成单个 var token——
                // 否则把括号产成独立 lparen/rparen token，会被 parseRow 的「行内不许括号」检查误杀
                // （var 的括号是操作数语法的一部分，不是分组括号）。失败（缺括号 / 参数非字符串 /
                // 未闭合）→ 抛错落高级文本框。
                i = j;
                i = skipSpaces(src, i);
                if (src[i] !== '(') throw new ParseFail();
                i++; // consume '('
                i = skipSpaces(src, i);
                const q = src[i];
                if (q !== '"' && q !== "'") throw new ParseFail(); // var 参数必须是字符串字面量
                const [name, afterStr] = readString(src, i, q);
                i = afterStr;
                i = skipSpaces(src, i);
                if (src[i] !== ')') throw new ParseFail();
                i++; // consume ')'
                out.push({ t: 'var', name });
                continue;
            }
            // 其它标识符（裸 ident，如模板参数名）→ 简化文法不支持 → 落高级文本框
            throw new ParseFail();
        }
        // 任何其它字符（+ - * / ! 单独出现、@ # 等）→ 复杂 / 非法 → 抛错落高级
        throw new ParseFail();
    }
    return out;
}

/** 解析失败信号（内部用；{@link tryParseCondition} 统一 catch 成 null）。 */
class ParseFail extends Error {}

function isIdentStart(c: string): boolean {
    return c === '_' || /[A-Za-z]/.test(c);
}
function isIdentCont(c: string): boolean {
    return c === '_' || /[A-Za-z0-9]/.test(c);
}

/** 从 {@code i} 跳过 ASCII 空白（与 tokenize 主循环的空白判定一致），返回首个非空白下标。 */
function skipSpaces(src: string, i: number): number {
    let j = i;
    while (j < src.length) {
        const c = src[j];
        if (c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === '\f' || c === '\v') j++;
        else break;
    }
    return j;
}

/**
 * 读字符串字面量（与后端 {@code readString} 同语义）：从 {@code start}（引号位置）读到匹配的
 * 闭合引号，返 [解开转义后的内容, 闭合引号之后的下标]。转义认 {@code \n \t \r \\ \" \'}，
 * 其它 {@code \x} → {@code x}（去反斜杠，与后端 {@code default -> n} 一致）。未闭合 → 抛错。
 */
function readString(src: string, start: number, quote: string): [string, number] {
    let i = start + 1;
    const n = src.length;
    let sb = '';
    while (i < n) {
        const c = src[i];
        if (c === '\\' && i + 1 < n) {
            const nx = src[i + 1];
            switch (nx) {
                case 'n':
                    sb += '\n';
                    break;
                case 't':
                    sb += '\t';
                    break;
                case 'r':
                    sb += '\r';
                    break;
                case '\\':
                    sb += '\\';
                    break;
                case '"':
                    sb += '"';
                    break;
                case "'":
                    sb += "'";
                    break;
                default:
                    sb += nx;
                    break;
            }
            i += 2;
            continue;
        }
        if (c === quote) {
            return [sb, i + 1];
        }
        sb += c;
        i++;
    }
    throw new ParseFail(); // 未闭合
}

/**
 * 反解析 condition 串 → {@link SimpleCondition}；任何不符简化文法 → {@code null}。
 *
 * <p>流程：tokenize → 按顶层 joiner（全 {@code &&} 或全 {@code ||}，混用 null）切行 →
 * 每行严格匹配 {@code operand cmp operand} → 组装。每一步遇到意外结构都返 null。</p>
 *
 * <p><b>空串 → null</b>（空条件不是有效简化模型；UI 对空 condition 走可视模式默认一行，
 * 不依赖本函数反解析空串）。</p>
 */
export function tryParseCondition(str: string): SimpleCondition | null {
    if (str == null) return null;
    if (str.trim() === '') return null;
    let toks: Tok[];
    try {
        toks = tokenize(str);
    } catch {
        return null;
    }
    if (toks.length === 0) return null;

    // 顶层按 && / || 切片。先确定顶层 joiner 一致性（混用 → null）。
    // 切片时遇到括号要按深度跳过——但简化文法里行内不允许裸括号（除非整行被一对括号包住，
    // 见下行级解析）。为稳健：先扫顶层（depth==0）的连接符，确认全同一种。
    const segments = splitTopLevel(toks);
    if (segments === null) return null;
    const { joiner, parts } = segments;
    if (parts.length === 0) return null;

    const rows: ConditionRow[] = [];
    for (const part of parts) {
        const row = parseRow(part);
        if (row === null) return null;
        rows.push(row);
    }
    return { rows, joiner };
}

/**
 * 按顶层（括号深度 0）的 {@code &&} / {@code ||} 切分 token 序列。要求顶层连接符<b>全是同一种</b>
 * （混用 → null）。无任何顶层连接符 → 单段（joiner 默认 {@code &&}，单行时 joiner 不影响输出）。
 *
 * <p>括号深度跟踪：遇 {@code lparen} depth+1、{@code rparen} depth-1；depth<0（右括号多）→ null。
 * 结束时 depth≠0（不配对）→ null。<b>顶层</b>连接符才作为切点；括号内的连接符属于子表达式，
 * 但简化文法 parseRow 不接受括号内含连接符（多比较）——交给 parseRow 去拒。</p>
 */
function splitTopLevel(toks: Tok[]): { joiner: Joiner; parts: Tok[][] } | null {
    const parts: Tok[][] = [];
    let cur: Tok[] = [];
    let depth = 0;
    let seenJoiner: Joiner | null = null;
    for (const tk of toks) {
        if (tk.t === 'lparen') {
            depth++;
            cur.push(tk);
            continue;
        }
        if (tk.t === 'rparen') {
            depth--;
            if (depth < 0) return null; // 右括号多
            cur.push(tk);
            continue;
        }
        if (depth === 0 && (tk.t === 'and' || tk.t === 'or')) {
            const j: Joiner = tk.t === 'and' ? '&&' : '||';
            if (seenJoiner === null) seenJoiner = j;
            else if (seenJoiner !== j) return null; // 混用 && 与 ||
            parts.push(cur);
            cur = [];
            continue;
        }
        cur.push(tk);
    }
    if (depth !== 0) return null; // 括号不配对
    parts.push(cur);
    // 任何一段为空（如 `a && && b` / 以连接符起头结尾）→ null
    if (parts.some((p) => p.length === 0)) return null;
    return { joiner: seenJoiner ?? '&&', parts };
}

/**
 * 解析单行 token 序列为 {@link ConditionRow}：恰好 {@code operand cmp operand}。
 *
 * <p>容许整行被一对<b>最外层</b>括号包住（多行 build 会给每行加括号；反解析须能脱壳）——
 * 但仅脱<b>正好包裹整行</b>的一层；若脱壳后内部仍有不配对 / 多余结构则 parseOperand 阶段会拒。
 * 不做无限脱壳（{@code ((a==b))} 这种双层括号 → 脱一层后内部仍以括号起止，parseOperand 不接受
 * 括号操作数 → null，落高级文本框，符合「稳健优先」）。</p>
 */
function parseRow(toks: Tok[]): ConditionRow | null {
    let body = toks;
    // 脱最外层包裹括号（正好首 lparen 与末 rparen 配对且包住整段）
    if (body.length >= 2 && body[0].t === 'lparen' && body[body.length - 1].t === 'rparen') {
        if (isWrappingPair(body)) {
            body = body.slice(1, body.length - 1);
        }
    }
    // 行内不允许再出现括号 / 连接符（简化文法：operand cmp operand）
    if (body.some((tk) => tk.t === 'lparen' || tk.t === 'rparen' || tk.t === 'and' || tk.t === 'or')) {
        return null;
    }
    // 用游标解析 operand cmp operand，且必须正好消费完。
    const cur = { toks: body, i: 0 };
    const lhs = parseOperand(cur);
    if (lhs === null) return null;
    const opTok = cur.toks[cur.i];
    if (!opTok || opTok.t !== 'cmp') return null;
    cur.i++;
    const rhs = parseOperand(cur);
    if (rhs === null) return null;
    // 必须正好用完（多余 token = 连串比较 / 算术残留 → null）
    if (cur.i !== cur.toks.length) return null;
    return { lhs, op: opTok.op, rhs };
}

/**
 * 判断 token 序列的首 lparen 是否与<b>末</b> rparen 配对（即外层括号正好包住整段）。
 * 用深度扫描：首 lparen 计为 depth=1，若在到达末尾之前 depth 归 0，则外层括号不是包整段的
 * （如 {@code (a) && (b)}），返 false。
 */
function isWrappingPair(toks: Tok[]): boolean {
    let depth = 0;
    for (let k = 0; k < toks.length; k++) {
        const tk = toks[k];
        if (tk.t === 'lparen') depth++;
        else if (tk.t === 'rparen') {
            depth--;
            if (depth === 0 && k !== toks.length - 1) return false; // 提前闭合
        }
    }
    return depth === 0;
}

/**
 * 从游标解析一个操作数（var / num / str / bool）。
 *
 * <p>{@code var} token 在 tokenize 阶段已把 {@code var("fullName")} 整体消费、{@code name} 解开
 * 转义存入 token，故这里直接取用，不再处理括号。</p>
 *
 * <p>识别成功推进游标并返 Operand；不符 → null（不推进）。</p>
 */
function parseOperand(cur: { toks: Tok[]; i: number }): Operand | null {
    const tk = cur.toks[cur.i];
    if (!tk) return null;
    switch (tk.t) {
        case 'num':
            cur.i++;
            return { kind: 'number', value: tk.value };
        case 'str':
            cur.i++;
            return { kind: 'string', value: tk.value };
        case 'bool':
            cur.i++;
            return { kind: 'bool', value: tk.value };
        case 'var':
            cur.i++;
            return { kind: 'var', name: tk.name };
        default:
            return null;
    }
}

// ============================================================================
// 默认值
// ============================================================================

/** 默认一行变量比较：{@code var("") == ""}（合理空起步，用户填变量名 + 右值）。 */
export function defaultRow(): ConditionRow {
    return {
        lhs: { kind: 'var', name: '' },
        op: '==',
        rhs: { kind: 'string', value: '' },
    };
}

/** 默认简化条件（一行 + && joiner）。 */
export function defaultCondition(): SimpleCondition {
    return { rows: [defaultRow()], joiner: '&&' };
}

/** 默认操作数（按 kind 给合理空值；UI 切操作数类型时用）。 */
export function defaultOperand(kind: OperandKind): Operand {
    switch (kind) {
        case 'var':
            return { kind: 'var', name: '' };
        case 'number':
            return { kind: 'number', value: 0 };
        case 'string':
            return { kind: 'string', value: '' };
        case 'bool':
            return { kind: 'bool', value: true };
    }
}
