/**
 * 变量名校验纯函数 {@link validateVariableName} 单元测试。
 *
 * <p>保证客户端校验逻辑与服务端 {@code Variable.NAME_REGEX} 对齐：合法名、非法字符、
 * 超长（&gt; 64 字符）与空串四类边界。</p>
 */
import { describe, expect, it } from 'vitest';
import {
    validateVariableName,
    VARIABLE_NAME_MAX_LEN,
    VARIABLE_NAME_REGEX,
} from '../variableNameValidation';

describe('validateVariableName', () => {
    it('合法 name：返 valid = true（提交按钮启用）', () => {
        const cases = ['red_score', 'foo.bar', 'A-B-C', 'x', 'with_123.dots-and-dashes'];
        for (const name of cases) {
            const r = validateVariableName(name);
            expect(r.valid, `case ${name}`).toBe(true);
            expect(r.error).toBe(null);
        }
    });

    it('name 含非法字符 → disabled (invalid)', () => {
        const cases = [
            '红队比分',              // 中文：后端 NAME_REGEX 不允许
            'foo bar',               // 空格
            'foo/bar',               // 斜杠
            'foo!bar',               // 特殊符号
            'foo:bar',               // 冒号
            '#redteam',              // hash
        ];
        for (const name of cases) {
            const r = validateVariableName(name);
            expect(r.valid, `case ${name}`).toBe(false);
            expect(r.error, `case ${name}`).toBe('invalid');
        }
    });

    it('name > 64 char → disabled (tooLong)', () => {
        const longName = 'a'.repeat(VARIABLE_NAME_MAX_LEN + 1);
        const r = validateVariableName(longName);
        expect(r.valid).toBe(false);
        expect(r.error).toBe('tooLong');
        // 边界：恰好 64 字符应通过
        const exact = 'a'.repeat(VARIABLE_NAME_MAX_LEN);
        const r2 = validateVariableName(exact);
        expect(r2.valid).toBe(true);
    });

    it('空 name → disabled (empty)', () => {
        const r = validateVariableName('');
        expect(r.valid).toBe(false);
        expect(r.error).toBe('empty');
    });

    it('正则与后端 NAME_REGEX 文本对齐（防漂移）', () => {
        // 后端 Variable.NAME_REGEX = "^[a-zA-Z0-9_.-]+$"。前端常量同款。
        expect(VARIABLE_NAME_REGEX.source).toBe('^[a-zA-Z0-9_.-]+$');
    });
});
