/**
 * 0.7.0-P5-G：condition 模型 ↔ 字符串双向转换单测。
 *
 * <p>重点：① {@link buildConditionString} 各 operand 形态 / 转义 / 数字形态 / 括号 / joiner / 空；
 * ② {@link tryParseCondition} 往返幂等 + 各形态 + <b>稳健性</b>（复杂 / 非法串一律 null，绝不误解析）。</p>
 */
import { describe, it, expect } from 'vitest';
import {
    buildConditionString,
    tryParseCondition,
    defaultCondition,
    defaultOperand,
    formatNumber,
    escapeStringLiteral,
    type SimpleCondition,
    type Operand,
} from '../condition';

function row(lhs: Operand, op: SimpleCondition['rows'][number]['op'], rhs: Operand) {
    return { lhs, op, rhs };
}
const v = (name: string): Operand => ({ kind: 'var', name });
const num = (value: number): Operand => ({ kind: 'number', value });
const str = (value: string): Operand => ({ kind: 'string', value });
const bool = (value: boolean): Operand => ({ kind: 'bool', value });

// ============================================================================
// buildConditionString
// ============================================================================

describe('buildConditionString', () => {
    it('1. 单行 var == string', () => {
        const c: SimpleCondition = { rows: [row(v('user/x'), '==', str('red'))], joiner: '&&' };
        expect(buildConditionString(c)).toBe('var("user/x") == "red"');
    });

    it('2. 单行 var >= number', () => {
        const c: SimpleCondition = { rows: [row(v('user/score'), '>=', num(10))], joiner: '&&' };
        expect(buildConditionString(c)).toBe('var("user/score") >= 10');
    });

    it('3. 单行 var != bool', () => {
        const c: SimpleCondition = { rows: [row(v('user/flag'), '!=', bool(true))], joiner: '&&' };
        expect(buildConditionString(c)).toBe('var("user/flag") != true');
    });

    it('4. 单行 number 各比较符', () => {
        expect(buildConditionString({ rows: [row(num(1), '<', num(2))], joiner: '&&' })).toBe('1 < 2');
        expect(buildConditionString({ rows: [row(num(1), '<=', num(2))], joiner: '&&' })).toBe(
            '1 <= 2',
        );
        expect(buildConditionString({ rows: [row(num(3), '>', num(2))], joiner: '&&' })).toBe('3 > 2');
    });

    it('5. 多行 && 加括号', () => {
        const c: SimpleCondition = {
            rows: [row(v('user/a'), '>=', num(10)), row(v('user/b'), '==', str('red'))],
            joiner: '&&',
        };
        expect(buildConditionString(c)).toBe('(var("user/a") >= 10) && (var("user/b") == "red")');
    });

    it('6. 多行 || 加括号', () => {
        const c: SimpleCondition = {
            rows: [row(v('x'), '==', num(1)), row(v('y'), '==', num(2)), row(v('z'), '==', num(3))],
            joiner: '||',
        };
        expect(buildConditionString(c)).toBe(
            '(var("x") == 1) || (var("y") == 2) || (var("z") == 3)',
        );
    });

    it('7. 字符串转义：内部双引号与反斜杠', () => {
        const c: SimpleCondition = {
            rows: [row(v('user/path'), '==', str('a"b\\c'))],
            joiner: '&&',
        };
        // " → \"  ；\ → \\
        expect(buildConditionString(c)).toBe('var("user/path") == "a\\"b\\\\c"');
    });

    it('8. 变量名含特殊字符也转义', () => {
        const c: SimpleCondition = { rows: [row(v('ns/a"b'), '==', str('x'))], joiner: '&&' };
        expect(buildConditionString(c)).toBe('var("ns/a\\"b") == "x"');
    });

    it('9. 数字友好形态：整数无小数点 / 负数 / 小数', () => {
        expect(buildConditionString({ rows: [row(num(42), '==', num(42))], joiner: '&&' })).toBe(
            '42 == 42',
        );
        expect(buildConditionString({ rows: [row(num(-3), '<', num(0))], joiner: '&&' })).toBe(
            '-3 < 0',
        );
        expect(buildConditionString({ rows: [row(num(1.5), '>', num(1))], joiner: '&&' })).toBe(
            '1.5 > 1',
        );
    });

    it('10. 空 rows → 空串', () => {
        expect(buildConditionString({ rows: [], joiner: '&&' })).toBe('');
    });

    it('11. bool false / 字符串空值', () => {
        expect(buildConditionString({ rows: [row(v('f'), '==', bool(false))], joiner: '&&' })).toBe(
            'var("f") == false',
        );
        expect(buildConditionString({ rows: [row(v('s'), '==', str(''))], joiner: '&&' })).toBe(
            'var("s") == ""',
        );
    });
});

// ============================================================================
// formatNumber / escapeStringLiteral 单元
// ============================================================================

describe('formatNumber', () => {
    it('整数无小数点', () => {
        expect(formatNumber(42)).toBe('42');
        expect(formatNumber(0)).toBe('0');
        expect(formatNumber(-7)).toBe('-7');
    });
    it('小数原样最短表示', () => {
        expect(formatNumber(1.5)).toBe('1.5');
        expect(formatNumber(-0.25)).toBe('-0.25');
    });
    it('非有限值兜底 0', () => {
        expect(formatNumber(NaN)).toBe('0');
        expect(formatNumber(Infinity)).toBe('0');
        expect(formatNumber(-Infinity)).toBe('0');
    });

    // 后端表达式文法的 readNumber 只认「数字[.数字]」，没有指数写法。JS 的 String(n) 对极小 /
    // 极大的数会写成 1e-7 / 1e+21，原样写进条件里就是个语法错，整条规则保存不了。
    it('极小数不写成指数（1e-7 → 0.0000001）', () => {
        expect(formatNumber(1e-7)).toBe('0.0000001');
        expect(formatNumber(1.5e-7)).toBe('0.00000015');
        expect(formatNumber(-2.5e-8)).toBe('-0.000000025');
    });

    it('极大数不写成指数（1e21 → 一串 0）', () => {
        expect(formatNumber(1e21)).toBe('1000000000000000000000');
        expect(formatNumber(-1.25e22)).toBe('-12500000000000000000000');
    });

    it('产出的串一律能被「数字[.数字]」文法接住（不含 e / E / +）', () => {
        for (const n of [1e-7, 1e21, -3.5e-9, 2.5e30, 0.1, -42]) {
            expect(formatNumber(n)).toMatch(/^-?\d+(\.\d+)?$/);
        }
    });
});

describe('escapeStringLiteral', () => {
    it('反斜杠先于双引号转义', () => {
        expect(escapeStringLiteral('a\\b')).toBe('"a\\\\b"');
        expect(escapeStringLiteral('a"b')).toBe('"a\\"b"');
        // 同时含两者：\ 与 " 各自独立转义，不互相吞
        expect(escapeStringLiteral('\\"')).toBe('"\\\\\\""');
    });
    it('普通字符原样', () => {
        expect(escapeStringLiteral('hello')).toBe('"hello"');
        expect(escapeStringLiteral('')).toBe('""');
    });
});

// ============================================================================
// tryParseCondition
// ============================================================================

describe('tryParseCondition', () => {
    it('1. 往返幂等：单行 var == string', () => {
        const c: SimpleCondition = { rows: [row(v('user/x'), '==', str('red'))], joiner: '&&' };
        const s = buildConditionString(c);
        const parsed = tryParseCondition(s);
        expect(parsed).not.toBeNull();
        expect(buildConditionString(parsed!)).toBe(s);
    });

    it('2. var 各比较符往返', () => {
        for (const op of ['==', '!=', '<', '<=', '>', '>='] as const) {
            const c: SimpleCondition = { rows: [row(v('user/n'), op, num(5))], joiner: '&&' };
            const s = buildConditionString(c);
            const parsed = tryParseCondition(s);
            expect(parsed, `op=${op}`).not.toBeNull();
            expect(buildConditionString(parsed!)).toBe(s);
        }
    });

    it('3. 字符串含转义往返', () => {
        const c: SimpleCondition = {
            rows: [row(v('user/path'), '==', str('a"b\\c'))],
            joiner: '&&',
        };
        const s = buildConditionString(c);
        const parsed = tryParseCondition(s);
        expect(parsed).not.toBeNull();
        // 内容解开转义后等于原值
        expect(parsed!.rows[0].rhs).toEqual({ kind: 'string', value: 'a"b\\c' });
        expect(buildConditionString(parsed!)).toBe(s);
    });

    it('4. 多行同 joiner && 往返', () => {
        const c: SimpleCondition = {
            rows: [row(v('a'), '>=', num(10)), row(v('b'), '==', str('red'))],
            joiner: '&&',
        };
        const s = buildConditionString(c);
        const parsed = tryParseCondition(s);
        expect(parsed).not.toBeNull();
        expect(parsed!.joiner).toBe('&&');
        expect(parsed!.rows.length).toBe(2);
        expect(buildConditionString(parsed!)).toBe(s);
    });

    it('5. 多行同 joiner || 往返', () => {
        const c: SimpleCondition = {
            rows: [row(v('x'), '==', num(1)), row(v('y'), '==', num(2))],
            joiner: '||',
        };
        const s = buildConditionString(c);
        const parsed = tryParseCondition(s);
        expect(parsed).not.toBeNull();
        expect(parsed!.joiner).toBe('||');
        expect(buildConditionString(parsed!)).toBe(s);
    });

    it('6. bool / number / 负数 operand 往返', () => {
        const c: SimpleCondition = {
            rows: [row(v('f'), '!=', bool(false)), row(num(-3), '<', num(0))],
            joiner: '&&',
        };
        const s = buildConditionString(c);
        const parsed = tryParseCondition(s);
        expect(parsed).not.toBeNull();
        expect(buildConditionString(parsed!)).toBe(s);
    });

    it('7. 无括号单行也能解析（用户手打）', () => {
        const parsed = tryParseCondition('var("user/score") >= 10');
        expect(parsed).not.toBeNull();
        expect(parsed!.rows[0]).toEqual({
            lhs: { kind: 'var', name: 'user/score' },
            op: '>=',
            rhs: { kind: 'number', value: 10 },
        });
    });

    it('8. 混合 joiner → null', () => {
        expect(tryParseCondition('(a == 1) && (b == 2) || (c == 3)')).toBeNull();
        expect(tryParseCondition('var("a") == 1 && var("b") == 2 || var("c") == 3')).toBeNull();
    });

    it('9. 算术 → null', () => {
        expect(tryParseCondition('var("a") + 1 == 2')).toBeNull();
        expect(tryParseCondition('var("a") == var("b") * 2')).toBeNull();
        expect(tryParseCondition('1 - 2 < 3')).toBeNull();
    });

    it('10. 嵌套括号 / 双层括号 → null', () => {
        expect(tryParseCondition('((var("a") == 1))')).toBeNull();
        expect(tryParseCondition('(var("a") == (1))')).toBeNull();
        expect(tryParseCondition('(var("a") == 1) && ((var("b") == 2))')).toBeNull();
    });

    it('11. 取反 ! → null', () => {
        expect(tryParseCondition('!var("a")')).toBeNull();
        expect(tryParseCondition('!(var("a") == 1)')).toBeNull();
        expect(tryParseCondition('var("a") == !true')).toBeNull();
    });

    it('12. 连串比较 → null', () => {
        expect(tryParseCondition('1 < 2 < 3')).toBeNull();
        expect(tryParseCondition('var("a") == 1 == 2')).toBeNull();
    });

    it('13. 裸标识符（非 var/true/false）→ null', () => {
        expect(tryParseCondition('foo == 1')).toBeNull();
        expect(tryParseCondition('var("a") == bar')).toBeNull();
    });

    it('14. 垃圾串 / 语法错误 → null', () => {
        expect(tryParseCondition('@#$%')).toBeNull();
        expect(tryParseCondition('var("a")')).toBeNull(); // 缺比较符与右值
        expect(tryParseCondition('== 1')).toBeNull(); // 缺左值
        expect(tryParseCondition('var("a") ==')).toBeNull(); // 缺右值
        expect(tryParseCondition('var(a) == 1')).toBeNull(); // var 参数非字符串
        expect(tryParseCondition('var("a" == 1')).toBeNull(); // 括号不配对（未闭合 + 缺右括号）
        expect(tryParseCondition('var("unterminated == 1')).toBeNull(); // 字符串未闭合
    });

    it('15. 空 / 仅空白 → null', () => {
        expect(tryParseCondition('')).toBeNull();
        expect(tryParseCondition('   ')).toBeNull();
        expect(tryParseCondition('\t\n')).toBeNull();
    });

    it('16. 顶层悬空 joiner → null', () => {
        expect(tryParseCondition('var("a") == 1 &&')).toBeNull();
        expect(tryParseCondition('&& var("a") == 1')).toBeNull();
        expect(tryParseCondition('var("a") == 1 && && var("b") == 2')).toBeNull();
    });

    it('17. 右括号多 / 左括号多 → null', () => {
        expect(tryParseCondition('var("a") == 1)')).toBeNull();
        expect(tryParseCondition('(var("a") == 1')).toBeNull();
        expect(tryParseCondition('(a == 1)) && (b == 2)')).toBeNull();
    });

    it('18. build→parse→build 三段幂等（综合）', () => {
        const cases: SimpleCondition[] = [
            defaultCondition(),
            { rows: [row(v('user/x'), '==', str('red'))], joiner: '&&' },
            { rows: [row(num(1), '<', num(2)), row(v('a'), '!=', bool(true))], joiner: '||' },
            { rows: [row(str('hi'), '==', str('hi'))], joiner: '&&' },
        ];
        for (const c of cases) {
            const s1 = buildConditionString(c);
            const p = tryParseCondition(s1);
            expect(p, s1).not.toBeNull();
            const s2 = buildConditionString(p!);
            expect(s2).toBe(s1);
            // 再 parse 一次仍等价
            const p2 = tryParseCondition(s2);
            expect(buildConditionString(p2!)).toBe(s1);
        }
    });

    it('19. var 名为空串 / 右值空串可解析（default 形态）', () => {
        const s = buildConditionString(defaultCondition());
        expect(s).toBe('var("") == ""');
        const parsed = tryParseCondition(s);
        expect(parsed).not.toBeNull();
        expect(parsed!.rows[0].lhs).toEqual({ kind: 'var', name: '' });
        expect(parsed!.rows[0].rhs).toEqual({ kind: 'string', value: '' });
    });

    it('20. 单引号字符串也能解析（后端 lexer 认）', () => {
        const parsed = tryParseCondition("var('user/x') == 'red'");
        expect(parsed).not.toBeNull();
        expect(parsed!.rows[0].lhs).toEqual({ kind: 'var', name: 'user/x' });
        expect(parsed!.rows[0].rhs).toEqual({ kind: 'string', value: 'red' });
    });
});

// ============================================================================
// defaults
// ============================================================================

describe('defaults', () => {
    it('defaultCondition 一行 var("") == ""', () => {
        const c = defaultCondition();
        expect(c.rows.length).toBe(1);
        expect(c.joiner).toBe('&&');
        expect(buildConditionString(c)).toBe('var("") == ""');
    });
    it('defaultOperand 按 kind', () => {
        expect(defaultOperand('var')).toEqual({ kind: 'var', name: '' });
        expect(defaultOperand('number')).toEqual({ kind: 'number', value: 0 });
        expect(defaultOperand('string')).toEqual({ kind: 'string', value: '' });
        expect(defaultOperand('bool')).toEqual({ kind: 'bool', value: true });
    });
});
