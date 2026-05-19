/**
 * 0.4.0-P2-G：NewVariableDialog 校验逻辑单元测试。
 *
 * <p>不引 @vue/test-utils，只测抽出的纯函数 {@link validateVariableName}（同时
 * 由 Dialog 组件 import 使用）。覆盖 4 个 case：</p>
 * <ul>
 *   <li>合法 name（提交按钮启用 → validation.valid = true）</li>
 *   <li>name 含非法字符（disabled）</li>
 *   <li>name > 64 char（disabled, tooLong）</li>
 *   <li>空 name（disabled, empty）</li>
 * </ul>
 *
 * <p>注：服务端调用（{@code wsClient.sendVariableCreate}）走 ack 通道，单测层不验证；
 * 留 e2e（M28 之后）覆盖。这里只保证客户端校验逻辑与服务端 NAME_REGEX 对齐。</p>
 */
import { describe, expect, it } from 'vitest';
import {
    validateVariableName,
    VARIABLE_NAME_MAX_LEN,
    VARIABLE_NAME_REGEX,
} from '@/components/variables/variableNameValidation';

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
            '红队比分',              // 中文：后端 P1-A NAME_REGEX 不允许
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
