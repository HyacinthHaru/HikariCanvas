import { describe, expect, it } from 'vitest';
import {
    evalBoolean,
    evaluateBooleanStrict,
    evaluateExpression,
    parseExpression,
    ParseError,
    UndeclaredParamError,
    type VarResolver,
} from '../templateExpr';

function runStrict(
    src: string,
    params: Record<string, unknown> = {},
    varResolver?: VarResolver,
): boolean {
    return evaluateBooleanStrict(parseExpression(src), params, varResolver);
}

function makeVarResolver(vars: Record<string, string>): VarResolver {
    return (fullName: string) => (Object.prototype.hasOwnProperty.call(vars, fullName) ? vars[fullName] : null);
}

describe('parseExpression — 词法与语法与后端 ExpressionParser 一致', () => {
    it('解析标识符、布尔、数字与转义字符串字面量', () => {
        expect(parseExpression('show_english')).toEqual({ kind: 'identifier', name: 'show_english' });
        expect(parseExpression('true')).toEqual({ kind: 'literal', value: true });
        expect(parseExpression('false')).toEqual({ kind: 'literal', value: false });
        expect(parseExpression('3')).toEqual({ kind: 'literal', value: 3 });
        expect(parseExpression('-1.5')).toEqual({ kind: 'literal', value: -1.5 });
        expect(parseExpression('.5')).toEqual({ kind: 'literal', value: 0.5 });
        expect(parseExpression('-.5')).toEqual({ kind: 'literal', value: -0.5 });
        expect(parseExpression('--3')).toEqual({ kind: 'literal', value: 3 });
        expect(parseExpression('-0')).toEqual({ kind: 'literal', value: 0 });
        expect(parseExpression('"Bond, James"')).toEqual({ kind: 'literal', value: 'Bond, James' });
        expect(parseExpression('"a\\nb\\tc\\rd\\\\e\\"f\\\'g"')).toEqual({
            kind: 'literal',
            value: 'a\nb\tc\rd\\e"f\'g',
        });
        expect(parseExpression("'apostrophe\\'s'")).toEqual({
            kind: 'literal',
            value: "apostrophe's",
        });
    });

    it('独立 MINUS token 支持无空格二元减法与非字面量一元负号', () => {
        expect(parseExpression('1-2')).toEqual({
            kind: 'binary',
            op: 'SUB',
            left: { kind: 'literal', value: 1 },
            right: { kind: 'literal', value: 2 },
        });
        expect(parseExpression('-x')).toEqual({
            kind: 'neg',
            inner: { kind: 'identifier', name: 'x' },
        });
        expect(parseExpression('-(a+b)')).toEqual({
            kind: 'neg',
            inner: {
                kind: 'binary',
                op: 'ADD',
                left: { kind: 'identifier', name: 'a' },
                right: { kind: 'identifier', name: 'b' },
            },
        });
        expect(parseExpression('-var("x")')).toEqual({
            kind: 'neg',
            inner: { kind: 'var', fullName: 'x' },
        });
    });

    it('运算符优先级：!/- > * / > + - > 比较 > == != > && > ||', () => {
        const orExpr = parseExpression('a || b && c');
        expect(orExpr.kind).toBe('binary');
        if (orExpr.kind === 'binary') {
            expect(orExpr.op).toBe('OR');
            expect(orExpr.right.kind === 'binary' && orExpr.right.op).toBe('AND');
        }

        const arithEq = parseExpression('1 + 2 * 3 == 7');
        expect(arithEq).toEqual({
            kind: 'binary',
            op: 'EQ',
            left: {
                kind: 'binary',
                op: 'ADD',
                left: { kind: 'literal', value: 1 },
                right: {
                    kind: 'binary',
                    op: 'MUL',
                    left: { kind: 'literal', value: 2 },
                    right: { kind: 'literal', value: 3 },
                },
            },
            right: { kind: 'literal', value: 7 },
        });

        const cmpEq = parseExpression('a == 1 < 2');
        expect(cmpEq.kind === 'binary' && cmpEq.op).toBe('EQ');
        if (cmpEq.kind === 'binary') {
            expect(cmpEq.right.kind === 'binary' && cmpEq.right.op).toBe('LT');
        }
    });

    it('禁止连串比较、多点数字、源串科学计数法与非法尾随 token', () => {
        expect(() => parseExpression('1 < 2 < 3')).toThrow(ParseError);
        expect(() => parseExpression('a >= b > c')).toThrow(ParseError);
        expect(() => parseExpression('1.2.3')).toThrow(ParseError);
        expect(() => parseExpression('1e3')).toThrow(ParseError);
        expect(() => parseExpression('.')).toThrow(ParseError);
        expect(() => parseExpression('(a == 1')).toThrow(ParseError);
        expect(() => parseExpression('a & b')).toThrow(ParseError);
        expect(() => parseExpression('1 +')).toThrow(ParseError);
        expect(() => parseExpression('* 3')).toThrow(ParseError);
        expect(() => parseExpression('1 ** 2')).toThrow(ParseError);
        expect(() => parseExpression('"unterminated')).toThrow(ParseError);

        try {
            parseExpression('a b');
            expect.unreachable('should have thrown ParseError');
        } catch (err) {
            expect(err).toBeInstanceOf(ParseError);
            expect((err as ParseError).position).toBe(2);
        }
    });

    it('var 保留关键字：var("fullName") 解析为 VarRef，裸 var 报错，前缀同源标识符仍合法', () => {
        expect(parseExpression('var("user/score")')).toEqual({ kind: 'var', fullName: 'user/score' });
        expect(parseExpression("var('schedule/eta_seconds')")).toEqual({
            kind: 'var',
            fullName: 'schedule/eta_seconds',
        });
        expect(() => parseExpression('var')).toThrow(ParseError);
        expect(() => parseExpression('var == 1')).toThrow(ParseError);
        expect(() => parseExpression('var(x)')).toThrow(ParseError);
        expect(() => parseExpression('var(1)')).toThrow(ParseError);
        expect(() => parseExpression('var("x"')).toThrow(ParseError);
        expect(parseExpression('variable')).toEqual({ kind: 'identifier', name: 'variable' });
        expect(parseExpression('var_x')).toEqual({ kind: 'identifier', name: 'var_x' });
    });

    it('MAX_DEPTH = 64 递归深度守卫', () => {
        expect(() => parseExpression('!'.repeat(60) + 'true')).not.toThrow();
        expect(() => parseExpression('!'.repeat(65) + 'true')).toThrow(ParseError);
        expect(() => parseExpression('('.repeat(65) + '1' + ')'.repeat(65))).toThrow(ParseError);
    });
});

describe('evaluateExpression / evaluateBooleanStrict — 求值语义与后端 ExpressionEvaluator 一致', () => {
    it('truthy 判定覆盖布尔、数字、字符串与 null/undefined', () => {
        expect(runStrict('flag', { flag: true })).toBe(true);
        expect(runStrict('flag', { flag: false })).toBe(false);
        expect(runStrict('flag', { flag: null })).toBe(false);
        expect(runStrict('flag', { flag: undefined })).toBe(false);
        expect(runStrict('name', { name: 'Hikari' })).toBe(true);
        expect(runStrict('name', { name: '' })).toBe(false);
        expect(runStrict('n', { n: 3 })).toBe(true);
        expect(runStrict('n', { n: 0 })).toBe(false);
    });

    it('&& 与 || 严格惰性短路：右侧含未声明参数时不抛错', () => {
        expect(runStrict('false && undeclared == 1', {})).toBe(false);
        expect(runStrict('flag && undeclared == 1', { flag: false })).toBe(false);
        expect(runStrict('true || undeclared == 1', {})).toBe(true);
        expect(runStrict('flag || undeclared == 1', { flag: true })).toBe(true);
        expect(() => runStrict('true && undeclared == 1', {})).toThrow(UndeclaredParamError);
        expect(() => runStrict('false || undeclared == 1', {})).toThrow(UndeclaredParamError);
    });

    it('params 仅认自有属性：未声明标识符及 Object.prototype 属性均抛 UndeclaredParamError', () => {
        expect(() => runStrict('missing', {})).toThrow(UndeclaredParamError);
        expect(() => runStrict('toString', {})).toThrow(UndeclaredParamError);
        expect(() => runStrict('constructor', {})).toThrow(UndeclaredParamError);
    });

    it('== / != 双侧数值等值、null/undefined 等值与 StrictNumber ASCII 空白规则', () => {
        expect(runStrict('s == 3.5', { s: '3.50' })).toBe(true);
        expect(runStrict('s == 3.5', { s: ' \t3.50\n ' })).toBe(true);
        // NBSP (U+00A0) 不属于 ASCII 空白，与 Java String.trim() 对齐不识别为数值
        expect(runStrict('s == 5', { s: '\u00a05' })).toBe(false);
        // 参数值字符串自身支持科学计数法（StrictNumber 文法）
        expect(runStrict('s == 100', { s: '1e2' })).toBe(true);
        expect(runStrict('s == 0', { s: 'abc' })).toBe(false);
        expect(runStrict('s == 0', { s: '' })).toBe(false);
        expect(runStrict('a == b', { a: null, b: undefined })).toBe(true);
        expect(runStrict('a == b', { a: null, b: '' })).toBe(false);
        expect(runStrict('flag == true', { flag: true })).toBe(true);
        expect(runStrict('s != "yes"', { s: 'no' })).toBe(true);
    });

    it('< <= > >= 单侧数值触发数值比较，双侧非数值退 UTF-16 字典序（null 当空串）', () => {
        expect(runStrict('s > 9', { s: '10' })).toBe(true);
        expect(runStrict('a < b', { a: 'apple', b: 'banana' })).toBe(true);
        expect(runStrict('a > b', { a: 'apple', b: 'banana' })).toBe(false);
        expect(runStrict('a <= b', { a: 'same', b: 'same' })).toBe(true);
        expect(runStrict('a < "a"', { a: null })).toBe(true);
    });

    it('四则运算、除零返 0 与 norm() 统一消除 -0 及溢出 Infinity', () => {
        expect(evaluateExpression(parseExpression('7'), {})).toBe(7);
        expect(evaluateExpression(parseExpression('"hi"'), {})).toBe('hi');
        expect(runStrict('1 + 2 * 3 == 7')).toBe(true);
        expect(runStrict('(1 + 2) * 3 == 9')).toBe(true);
        expect(runStrict('1 / 0')).toBe(false);
        expect(runStrict('1 / 0 == 0')).toBe(true);
        expect(runStrict('10 / n == 0', { n: 0 })).toBe(true);
        expect(runStrict('s + 5 == 5', { s: 'abc' })).toBe(true);
        expect(runStrict('-1 * 0 == 0')).toBe(true);
        expect(runStrict('-1 * 0 < 0')).toBe(false);
        expect(Object.is(evaluateExpression(parseExpression('-1 * 0'), {}), 0)).toBe(true);

        const huge = '9'.repeat(400);
        expect(runStrict(`${huge} + 1 == 1`)).toBe(true);
        expect(runStrict(`${huge} * 2`)).toBe(false);
    });

    it('var("fullName") 支持注入 resolver、缺失返 null 且无 resolver 时抛错', () => {
        const resolver = makeVarResolver({ 'user/score': '10', zero: '0', phase: 'RUNNING' });
        expect(runStrict('var("user/score") >= 10', {}, resolver)).toBe(true);
        expect(runStrict('var("phase") == "RUNNING"', {}, resolver)).toBe(true);
        expect(runStrict('var("user/missing")', {}, resolver)).toBe(false);
        expect(runStrict('var("user/missing") < 10', {}, resolver)).toBe(true);
        expect(runStrict('-var("zero") < 0', {}, resolver)).toBe(false);
        expect(() => runStrict('var("user/score")', {})).toThrow(/variable resolver/);
    });
});

describe('evalBoolean — 模板 visible_when 容错入口', () => {
    it('合法表达式返回真实布尔结果，空串或解析/求值错误保守返回 true', () => {
        expect(evalBoolean(null, {})).toBe(true);
        expect(evalBoolean(undefined, {})).toBe(true);
        expect(evalBoolean('   ', {})).toBe(true);
        expect(evalBoolean('show_english == true && name != ""', { show_english: true, name: 'Hikari' })).toBe(true);
        expect(evalBoolean('show_english == true && name != ""', { show_english: false, name: 'Hikari' })).toBe(false);
        expect(evalBoolean('count >= 2 && (total - 1) > 0', { count: 2, total: 5 })).toBe(true);
        expect(evalBoolean('count >= 2 && (total - 1) > 0', { count: 2, total: 1 })).toBe(false);
        // 语法错误或未声明参数 → 保守显示字段（返 true）
        expect(evalBoolean('1 < 2 < 3', {})).toBe(true);
        expect(evalBoolean('missing_param == 1', {})).toBe(true);
        expect(evalBoolean('false && missing_param == 1', {})).toBe(false);
    });
});
