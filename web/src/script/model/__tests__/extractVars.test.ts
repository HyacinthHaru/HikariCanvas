/**
 * 0.7.1：extractReferencedVariables 单测（积木画布「本脚本变量实时预览」的提取逻辑）。
 *
 * <p>纯函数（无 Vue / DOM），vitest node 跑。重点覆盖：触发器 fullName / 4 个变量名动作字段 /
 * 三处 {@code ${var:X}} 文本（setVariable.value / sendMessage.text / 友好改文字 patch.text）/
 * <b>if then-else + repeat body 嵌套</b>下钻 / 去重 + 首次出现顺序。</p>
 */
import { describe, expect, it } from 'vitest';
import type { ScriptAction, ScriptRule, ScriptTrigger } from '@/types/protocol';
import { extractReferencedVariables } from '../extractVars';

/** 包一条最小规则（默认 wallReady 触发 + 给定动作）。 */
function rule(actions: ScriptAction[], trigger: ScriptTrigger = { type: 'wallReady' }): ScriptRule {
    return {
        id: 'sr-1', wallId: 'w-1', enabled: true, name: 'r',
        trigger, actions, blockLayout: '{}',
    };
}

describe('extractReferencedVariables', () => {
    it('空规则（无变量）→ 空数组', () => {
        expect(extractReferencedVariables(rule([{ type: 'log', message: 'hi' }]))).toEqual([]);
    });

    it('触发器 variableChange.fullName', () => {
        const r = rule([{ type: 'log', message: 'x' }], { type: 'variableChange', fullName: 'user/红队比分' });
        expect(extractReferencedVariables(r)).toEqual(['user/红队比分']);
    });

    it('其它触发器不产出变量', () => {
        expect(extractReferencedVariables(rule([], { type: 'timer', intervalSeconds: 5 }))).toEqual([]);
        expect(extractReferencedVariables(rule([], { type: 'playerNear', rangeBlocks: 3 }))).toEqual([]);
    });

    it('4 个变量名动作字段（setVariable / incrementVariable / scaleVariable / setRandomVariable）', () => {
        const r = rule([
            { type: 'setVariable', fullName: 'user/a', value: '1' },
            { type: 'incrementVariable', fullName: 'user/b', delta: 1 },
            { type: 'scaleVariable', fullName: 'user/c', op: 'multiply', factor: 2 },
            { type: 'setRandomVariable', fullName: 'user/d', min: 0, max: 9 },
        ]);
        expect(extractReferencedVariables(r)).toEqual(['user/a', 'user/b', 'user/c', 'user/d']);
    });

    it('setVariable.value 里的 ${var:X}（结构字段 + 文本占位符都收）', () => {
        const r = rule([{ type: 'setVariable', fullName: 'user/total', value: '${var:user/a} + ${var:server.time}' }]);
        // 先 fullName=user/total，再 value 里两个占位符（首次出现顺序）。
        expect(extractReferencedVariables(r)).toEqual(['user/total', 'user/a', 'server.time']);
    });

    it('sendMessage.text 里的 ${var:X}（含 |fallback= 也只取 rawName）', () => {
        const r = rule([{ type: 'sendMessage', text: '欢迎 ${var:user/name|fallback=玩家}', channel: 'chat' }]);
        expect(extractReferencedVariables(r)).toEqual(['user/name']);
    });

    it('友好积木「改文字」(setElementProperties kind=setText) 的 patch.text 里的 ${var:X}', () => {
        const r = rule([
            { type: 'setElementProperties', elementId: 'el-1', kind: 'setText', patch: { text: '比分 ${var:user/score}' } },
        ]);
        expect(extractReferencedVariables(r)).toEqual(['user/score']);
    });

    it('友好积木非文本 kind（patch 是数值/颜色）→ 不产出占位符', () => {
        const r = rule([
            { type: 'setElementProperties', elementId: 'el-1', kind: 'moveTo', patch: { x: '10', y: '20' } },
        ]);
        expect(extractReferencedVariables(r)).toEqual([]);
    });

    it('if then/else 嵌套下钻（含条件分支内的变量名字段与文本占位符）', () => {
        const r = rule([
            {
                type: 'if',
                condition: 'var(user/hp) > 0',
                then: [
                    { type: 'setVariable', fullName: 'user/alive', value: '1' },
                    { type: 'sendMessage', text: '存活 ${var:user/hp}', channel: 'actionbar' },
                ],
                else: [
                    { type: 'incrementVariable', fullName: 'user/deaths', delta: 1 },
                ],
            },
        ]);
        // then 的 fullName + 文本占位符，再 else 的 fullName。注意 condition 串本身不抓（设计：只看占位符 / 字段）。
        expect(extractReferencedVariables(r)).toEqual(['user/alive', 'user/hp', 'user/deaths']);
    });

    it('repeat body 嵌套下钻', () => {
        const r = rule([
            {
                type: 'repeat',
                count: 3,
                body: [
                    { type: 'incrementVariable', fullName: 'user/counter', delta: 1 },
                    { type: 'sendMessage', text: '第 ${var:user/counter} 次', channel: 'chat' },
                ],
            },
        ]);
        expect(extractReferencedVariables(r)).toEqual(['user/counter']);
    });

    it('if 内嵌 repeat（多层容器）也能下钻到底', () => {
        const r = rule([
            {
                type: 'if',
                condition: 'var(user/on) == 1',
                then: [
                    {
                        type: 'repeat',
                        count: 2,
                        body: [{ type: 'setVariable', fullName: 'user/deep', value: '${var:user/seed}' }],
                    },
                ],
                else: [],
            },
        ]);
        expect(extractReferencedVariables(r)).toEqual(['user/deep', 'user/seed']);
    });

    it('去重：同一变量在多处引用只出现一次（保首次出现顺序）', () => {
        const r = rule([
            { type: 'setVariable', fullName: 'user/x', value: '${var:user/x}' },
            { type: 'incrementVariable', fullName: 'user/x', delta: 1 },
            { type: 'sendMessage', text: '${var:user/x} 和 ${var:user/y}', channel: 'chat' },
        ]);
        expect(extractReferencedVariables(r)).toEqual(['user/x', 'user/y']);
    });

    it('空白 fullName / 空文本被忽略', () => {
        const r = rule([
            { type: 'setVariable', fullName: '   ', value: '' },
            { type: 'incrementVariable', fullName: '', delta: 1 },
        ]);
        expect(extractReferencedVariables(r)).toEqual([]);
    });
});
