/**
 * 0.7.0-P4-D2：makeDefaultAction / makeDefaultTrigger 全 kind 合法性单测（纯逻辑）。
 *
 * <p>契约：每个 kind 产出的默认对象 type 正确、字段齐（与 protocol.ts wire 形态对齐）、
 * 数值落在后端 validator 范围内（间隔 / 范围 / 等待 / 音量 / 音调）、枚举取合法白名单值、
 * if 的 then/else 为空数组（非 null）。未知 kind 兜底返合法对象（不抛 / 不返 undefined）。</p>
 */
import { describe, expect, it } from 'vitest';
import { ACTION_DEFS, TRIGGER_DEFS, makeDefaultAction, makeDefaultTrigger } from '../blockDefs';

describe('makeDefaultAction — 全 kind 合法', () => {
    it('每个 ACTION_DEFS kind 都产出 type 一致的对象', () => {
        for (const kind of Object.keys(ACTION_DEFS)) {
            const a = makeDefaultAction(kind);
            expect(a.type).toBe(kind);
        }
    });

    it('setVariable：空 fullName + 空 value', () => {
        expect(makeDefaultAction('setVariable')).toEqual({ type: 'setVariable', fullName: '', value: '' });
    });

    it('incrementVariable：delta 默认 1', () => {
        expect(makeDefaultAction('incrementVariable')).toEqual({ type: 'incrementVariable', fullName: '', delta: 1 });
    });

    it('setElementProperty：property 默认 x（白名单首项）', () => {
        const a = makeDefaultAction('setElementProperty');
        expect(a).toEqual({ type: 'setElementProperty', elementId: '', property: 'x', value: '' });
    });

    it('playTimeline：op=play 且不带 seekMs（仅 seek 携带）', () => {
        const a = makeDefaultAction('playTimeline');
        expect(a).toEqual({ type: 'playTimeline', timelineId: '', op: 'play' });
        expect('seekMs' in a).toBe(false);
    });

    it('playSound：volume=1 / pitch=1（落 validator 范围）/ scope=near', () => {
        const a = makeDefaultAction('playSound');
        expect(a).toEqual({ type: 'playSound', soundId: '', volume: 1, pitch: 1, scope: 'near' });
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

    it('if：空 condition + then/else 为空数组（非 null）', () => {
        const a = makeDefaultAction('if');
        expect(a).toEqual({ type: 'if', condition: '', then: [], else: [] });
        if (a.type === 'if') {
            expect(Array.isArray(a.then)).toBe(true);
            expect(Array.isArray(a.else)).toBe(true);
            expect(a.then).toHaveLength(0);
            expect(a.else).toHaveLength(0);
        }
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

    it('variableChange：空 fullName', () => {
        expect(makeDefaultTrigger('variableChange')).toEqual({ type: 'variableChange', fullName: '' });
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
