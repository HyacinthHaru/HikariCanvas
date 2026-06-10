/**
 * 0.7.0 P1 scriptStore 单测。
 *
 * <p>纯 Pinia store 逻辑（无 Vue 组件 / DOM），用 vitest node 跑。覆盖 initScripts /
 * upsert（新增 + 覆盖保位置）/ removeRule / reset / get / listSorted 顺序 + size。</p>
 */
import { describe, expect, it, beforeEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useScriptStore } from '../scripts';
import type { ScriptRule, ScriptTracePayload } from '@/types/protocol';

beforeEach(() => {
    setActivePinia(createPinia());
});

function makeRule(id: string, name = `rule-${id}`): ScriptRule {
    return {
        id,
        wallId: 'w-abc12345',
        enabled: true,
        name,
        trigger: { type: 'timer', intervalSeconds: 30 },
        actions: [{ type: 'log', message: 'hi' }],
        blockLayout: '{}',
    };
}

describe('scriptStore', () => {
    it('初始为空', () => {
        const s = useScriptStore();
        expect(s.size).toBe(0);
        expect(s.listSorted).toEqual([]);
        expect(s.get('any')).toBe(null);
    });

    it('initScripts 列表初始化（保持服务端顺序 + 覆盖旧内容）', () => {
        const s = useScriptStore();
        s.upsert(makeRule('sr-stale'));
        s.initScripts([makeRule('sr-b'), makeRule('sr-a'), makeRule('sr-c')]);
        expect(s.size).toBe(3);
        expect(s.get('sr-stale')).toBe(null);
        expect(s.listSorted.map((r) => r.id)).toEqual(['sr-b', 'sr-a', 'sr-c']);
    });

    it('initScripts(null) / initScripts(undefined) 清空', () => {
        const s = useScriptStore();
        s.upsert(makeRule('sr-x'));
        s.initScripts(null);
        expect(s.size).toBe(0);
        s.upsert(makeRule('sr-x'));
        s.initScripts(undefined);
        expect(s.size).toBe(0);
        expect(s.listSorted).toEqual([]);
    });

    it('upsert 新增 → 追加到 order 末尾', () => {
        const s = useScriptStore();
        s.upsert(makeRule('sr-1'));
        s.upsert(makeRule('sr-2'));
        expect(s.listSorted.map((r) => r.id)).toEqual(['sr-1', 'sr-2']);
        expect(s.size).toBe(2);
    });

    it('upsert 已存在 → 原位替换不挪顺序', () => {
        const s = useScriptStore();
        s.initScripts([makeRule('sr-1'), makeRule('sr-2'), makeRule('sr-3')]);
        s.upsert(makeRule('sr-2', '改名后'));
        expect(s.listSorted.map((r) => r.id)).toEqual(['sr-1', 'sr-2', 'sr-3']);
        expect(s.get('sr-2')?.name).toBe('改名后');
        expect(s.size).toBe(3);
    });

    it('upsert 重建 Map 引用以触发 Vue 响应', () => {
        const s = useScriptStore();
        const initial = s.rules;
        s.upsert(makeRule('sr-1'));
        // 锁的是响应性契约（Map 重建触发 watch）而非实现细节
        expect(s.rules).not.toBe(initial);
    });

    it('removeRule 已存在 → 删除（含 order）', () => {
        const s = useScriptStore();
        s.initScripts([makeRule('sr-1'), makeRule('sr-2')]);
        s.removeRule('sr-1');
        expect(s.get('sr-1')).toBe(null);
        expect(s.size).toBe(1);
        expect(s.listSorted.map((r) => r.id)).toEqual(['sr-2']);
    });

    it('removeRule 不存在 → noop（保持 ref 引用）', () => {
        const s = useScriptStore();
        s.upsert(makeRule('sr-1'));
        const before = s.rules;
        s.removeRule('sr-nonexistent');
        // 锁的是响应性契约（noop 不重建 Map → 不触发 watch）而非实现细节
        expect(s.rules).toBe(before);
        expect(s.size).toBe(1);
    });

    it('reset 清空全部', () => {
        const s = useScriptStore();
        s.initScripts([makeRule('sr-1'), makeRule('sr-2')]);
        s.reset();
        expect(s.size).toBe(0);
        expect(s.listSorted).toEqual([]);
        expect(s.get('sr-1')).toBe(null);
    });

    it('get 返回完整 rule（trigger / actions / blockLayout 字段透传）', () => {
        const s = useScriptStore();
        const rule: ScriptRule = {
            id: 'sr-full',
            wallId: 'w-abc12345',
            enabled: false,
            name: '完整',
            trigger: { type: 'playerNear', rangeBlocks: 8 },
            actions: [
                { type: 'setVariable', fullName: 'user/score', value: '1' },
                {
                    type: 'if', condition: '${var:user/score} > 0',
                    then: [{ type: 'wait', ms: 500 }],
                    else: [],
                },
            ],
            blockLayout: '{"x":1}',
        };
        s.upsert(rule);
        expect(s.get('sr-full')).toEqual(rule);
    });

    // ---------- 0.7.0-P3 A2（K11）：lastTrace ----------

    it('lastTrace 初始 null；setLastTrace 落表（detail 缺省 step 兼容）', () => {
        const s = useScriptStore();
        expect(s.lastTrace).toBe(null);
        const payload: ScriptTracePayload = {
            ruleId: 'sr-1',
            steps: [
                { blockId: 'trigger', kind: 'trigger', result: 'ok', detail: 'TEST test' },
                { blockId: 'actions/0', kind: 'action', result: 'ok' }, // detail 省略不上 wire
                { blockId: 'actions/1', kind: 'action', result: 'error', detail: 'boom' },
            ],
        };
        s.setLastTrace(payload);
        expect(s.lastTrace).toEqual(payload);
        expect(s.lastTrace!.steps[1].detail).toBeUndefined();
    });

    it('setLastTrace 覆盖式——只留最近一次试跑', () => {
        const s = useScriptStore();
        s.setLastTrace({ ruleId: 'sr-old', steps: [] });
        s.setLastTrace({
            ruleId: 'sr-new',
            steps: [{ blockId: 'trigger', kind: 'trigger', result: 'blocked', detail: '频率超限' }],
        });
        expect(s.lastTrace!.ruleId).toBe('sr-new');
        expect(s.lastTrace!.steps).toHaveLength(1);
    });

    it('reset 清 lastTrace（wall 切换不残留上一面墙的轨迹）', () => {
        const s = useScriptStore();
        s.setLastTrace({ ruleId: 'sr-1', steps: [] });
        s.reset();
        expect(s.lastTrace).toBe(null);
        // 即便 rules 已空，reset 也要清 lastTrace（reset 早退分支回归）
        s.setLastTrace({ ruleId: 'sr-2', steps: [] });
        s.reset();
        expect(s.lastTrace).toBe(null);
    });

    it('setLastTrace(null) 显式清空', () => {
        const s = useScriptStore();
        s.setLastTrace({ ruleId: 'sr-1', steps: [] });
        s.setLastTrace(null);
        expect(s.lastTrace).toBe(null);
    });
});
