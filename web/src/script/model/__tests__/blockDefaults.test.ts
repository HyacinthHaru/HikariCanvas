/**
 * 0.7.0-P4-D2 / P5 实测修复：makeDefaultAction / makeDefaultTrigger 全 kind 合法性单测（纯逻辑）。
 *
 * <p>契约：每个 kind 产出的默认对象 type 正确、字段齐（与 protocol.ts wire 形态对齐）、
 * 数值落在后端 validator 范围内（间隔 / 范围 / 等待 / 音量 / 音调）、枚举取合法白名单值、
 * if 的 then/else 为空数组（非 null）。未知 kind 兜底返合法对象（不抛 / 不返 undefined）。</p>
 *
 * <p><b>P5 实测修复（根因 2）</b>：不依赖墙状态的引用字段（变量名 / 声音 / if 条件）改为非空
 * 合理默认，让"拖出新块即合法"——避免空引用被前端 validator + 后端立刻判非法、红字轰炸 +
 * 卡自动保存。本测新增「拖出即过 validateRule」与「if 默认条件能被 tryParseCondition 解析回
 * 可视模式」两组断言守这个行为。</p>
 */
import { describe, expect, it } from 'vitest';
import { ACTION_DEFS, TRIGGER_DEFS, makeDefaultAction, makeDefaultTrigger } from '../blockDefs';
import { validateRule } from '../validator';
import { tryParseCondition } from '../condition';
import type { ScriptRule } from '@/types/protocol';

describe('makeDefaultAction — 全 kind 合法', () => {
    it('每个 ACTION_DEFS kind 都产出 type 一致的对象', () => {
        for (const kind of Object.keys(ACTION_DEFS)) {
            const a = makeDefaultAction(kind);
            expect(a.type).toBe(kind);
        }
    });

    it('setVariable：非空 fullName（拖出即合法）+ 空 value（后端合法）', () => {
        expect(makeDefaultAction('setVariable')).toEqual({ type: 'setVariable', fullName: 'user/score', value: '' });
    });

    it('incrementVariable：非空 fullName + delta 默认 1', () => {
        expect(makeDefaultAction('incrementVariable')).toEqual({ type: 'incrementVariable', fullName: 'user/score', delta: 1 });
    });

    it('setElementProperty：elementId 留空（依赖墙上元素）+ property 默认 x（白名单首项）', () => {
        const a = makeDefaultAction('setElementProperty');
        expect(a).toEqual({ type: 'setElementProperty', elementId: '', property: 'x', value: '' });
    });

    it('playTimeline：op=play 且不带 seekMs（仅 seek 携带）', () => {
        const a = makeDefaultAction('playTimeline');
        expect(a).toEqual({ type: 'playTimeline', timelineId: '', op: 'play' });
        expect('seekMs' in a).toBe(false);
    });

    it('playSound：非空 soundId（拖出即合法）/ volume=1 / pitch=1（落 validator 范围）/ scope=near', () => {
        const a = makeDefaultAction('playSound');
        expect(a).toEqual({ type: 'playSound', soundId: 'entity.player.levelup', volume: 1, pitch: 1, scope: 'near' });
        if (a.type === 'playSound') {
            expect(a.volume).toBeGreaterThanOrEqual(0);
            expect(a.volume).toBeLessThanOrEqual(2);
            expect(a.pitch).toBeGreaterThanOrEqual(0.5);
            expect(a.pitch).toBeLessThanOrEqual(2);
        }
    });

    it('wait：ms=500（落 50..5000 区间）', () => {
        const a = makeDefaultAction('wait');
        expect(a).toEqual({ type: 'wait', ms: 500 });
        if (a.type === 'wait') {
            expect(a.ms).toBeGreaterThanOrEqual(50);
            expect(a.ms).toBeLessThanOrEqual(5000);
        }
    });

    it('runCommand：空 templateId + 空 params 对象', () => {
        const a = makeDefaultAction('runCommand');
        expect(a).toEqual({ type: 'runCommand', templateId: '', params: {} });
    });

    it('log：空 message', () => {
        expect(makeDefaultAction('log')).toEqual({ type: 'log', message: '' });
    });

    it('if：非空合法 condition（拖出即合法）+ then/else 为空数组（非 null）', () => {
        const a = makeDefaultAction('if');
        expect(a).toEqual({ type: 'if', condition: 'var("user/score") > 0', then: [], else: [] });
        if (a.type === 'if') {
            expect(Array.isArray(a.then)).toBe(true);
            expect(Array.isArray(a.else)).toBe(true);
            expect(a.then).toHaveLength(0);
            expect(a.else).toHaveLength(0);
        }
    });

    it('if 默认 condition 能被 tryParseCondition 解析回可视模式（不掉到高级文本框）', () => {
        const a = makeDefaultAction('if');
        if (a.type !== 'if') throw new Error('expected if');
        const parsed = tryParseCondition(a.condition);
        expect(parsed).not.toBeNull();
        expect(parsed).toEqual({
            rows: [{ lhs: { kind: 'var', name: 'user/score' }, op: '>', rhs: { kind: 'number', value: 0 } }],
            joiner: '&&',
        });
    });

    it('未知 kind → 兜底合法 log（不抛 / 不 undefined）', () => {
        const a = makeDefaultAction('totallyBogusKind');
        expect(a).toBeDefined();
        expect(a.type).toBe('log');
    });
});

describe('makeDefaultTrigger — 全 kind 合法', () => {
    it('每个 TRIGGER_DEFS kind 都产出 type 一致的对象', () => {
        for (const kind of Object.keys(TRIGGER_DEFS)) {
            const tr = makeDefaultTrigger(kind);
            expect(tr.type).toBe(kind);
        }
    });

    it('variableChange：非空 fullName（切到此触发器即合法）', () => {
        expect(makeDefaultTrigger('variableChange')).toEqual({ type: 'variableChange', fullName: 'user/score' });
    });

    it('timer：intervalSeconds=10（落 1..86400）', () => {
        const tr = makeDefaultTrigger('timer');
        expect(tr).toEqual({ type: 'timer', intervalSeconds: 10 });
        if (tr.type === 'timer') {
            expect(tr.intervalSeconds).toBeGreaterThanOrEqual(1);
            expect(tr.intervalSeconds).toBeLessThanOrEqual(86400);
        }
    });

    it('playerNear：rangeBlocks=8（落 1..32）', () => {
        const tr = makeDefaultTrigger('playerNear');
        expect(tr).toEqual({ type: 'playerNear', rangeBlocks: 8 });
        if (tr.type === 'playerNear') {
            expect(tr.rangeBlocks).toBeGreaterThanOrEqual(1);
            expect(tr.rangeBlocks).toBeLessThanOrEqual(32);
        }
    });

    it('无字段触发器：playerJoin / playerKill / wallReady 只带 type', () => {
        expect(makeDefaultTrigger('playerJoin')).toEqual({ type: 'playerJoin' });
        expect(makeDefaultTrigger('playerKill')).toEqual({ type: 'playerKill' });
        expect(makeDefaultTrigger('wallReady')).toEqual({ type: 'wallReady' });
    });

    it('未知 kind → 兜底 wallReady', () => {
        expect(makeDefaultTrigger('nope')).toEqual({ type: 'wallReady' });
    });
});

// ---------- P5 实测修复（根因 2）：拖出新块即合法（过前端 validateRule，无错）----------

/** 造一条最小合法规则壳，把待测动作塞进 actions[0]（除该动作外其余字段都合法）。 */
function ruleWithAction(action: ScriptRule['actions'][number]): ScriptRule {
    return {
        id: 'sr-x', wallId: 'w-x', enabled: true, name: '规则',
        trigger: { type: 'wallReady' }, actions: [action], blockLayout: '{}',
    };
}

/** 造一条最小合法规则壳，把待测触发器塞进 trigger（actions 给一个合法 log）。 */
function ruleWithTrigger(trigger: ScriptRule['trigger']): ScriptRule {
    return {
        id: 'sr-x', wallId: 'w-x', enabled: true, name: '规则',
        trigger, actions: [{ type: 'log', message: '' }], blockLayout: '{}',
    };
}

describe('拖出新块即合法 — 不依赖墙状态的 kind 过 validateRule（根因 2）', () => {
    // 这些 kind 的引用字段都有非空合法默认（或本就无引用字段），拖出/切换瞬间不该报错。
    const SELF_VALID_ACTIONS = ['setVariable', 'incrementVariable', 'playSound', 'wait', 'log', 'if'] as const;
    for (const kind of SELF_VALID_ACTIONS) {
        it(`makeDefaultAction('${kind}') 拖出即合法（validateRule 无错）`, () => {
            const errors = validateRule(ruleWithAction(makeDefaultAction(kind)));
            expect(errors).toEqual([]);
        });
    }

    // variableChange 触发器（带非空默认变量名）切换瞬间也应合法。
    const SELF_VALID_TRIGGERS = ['variableChange', 'timer', 'playerNear', 'playerJoin', 'playerKill', 'wallReady'] as const;
    for (const kind of SELF_VALID_TRIGGERS) {
        it(`makeDefaultTrigger('${kind}') 切换即合法（validateRule 无错）`, () => {
            const errors = validateRule(ruleWithTrigger(makeDefaultTrigger(kind)));
            expect(errors).toEqual([]);
        });
    }

    // 依赖墙状态的 kind（elementId / timelineId / templateId 留空）拖出后会报"缺 XX ID"——
    // 这是预期的（视觉层标"请选择"，用户必须选）。这里固化这个边界：恰好报对应的一条引用缺失错。
    it('依赖墙状态的 kind 拖出后报引用缺失（预期，用户须选）', () => {
        expect(validateRule(ruleWithAction(makeDefaultAction('setElementProperty')))
            .some((e) => e.message.includes('元素 ID'))).toBe(true);
        expect(validateRule(ruleWithAction(makeDefaultAction('playTimeline')))
            .some((e) => e.message.includes('时间轴 ID'))).toBe(true);
        expect(validateRule(ruleWithAction(makeDefaultAction('runCommand')))
            .some((e) => e.message.includes('模板 ID'))).toBe(true);
    });
});
