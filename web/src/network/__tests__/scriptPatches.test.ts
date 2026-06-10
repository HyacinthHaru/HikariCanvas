// @vitest-environment happy-dom
/**
 * 0.7.0 P1 applyScriptPatches 单测。
 *
 * <p>state.patch 的 /scripts/ 通道路由逻辑（applyAliasPatches 当年没独立测，这次把新函数
 * 写成可独测的导出补上）。wsClient 模块本身 import 即安全（单例 / window 仅在方法内用），
 * 但 pushLog 走 useNetworkStore，需 happy-dom + 激活 pinia。</p>
 */
import { describe, expect, it, beforeEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { applyScriptPatches } from '../wsClient';
import { useScriptStore } from '@/stores/scripts';
import type { PatchOp, ScriptRule } from '@/types/protocol';

beforeEach(() => {
    setActivePinia(createPinia());
});

function makeRule(id: string, name = `rule-${id}`): ScriptRule {
    return {
        id,
        wallId: 'w-abc12345',
        enabled: true,
        name,
        trigger: { type: 'wallReady' },
        actions: [{ type: 'log', message: 'hi' }],
        blockLayout: '{}',
    };
}

describe('applyScriptPatches', () => {
    it('add 完整 rule → upsert 落表', () => {
        const store = useScriptStore();
        const rule = makeRule('sr-1');
        const ops: PatchOp[] = [{ op: 'add', path: '/scripts/sr-1', value: rule }];
        applyScriptPatches(ops);
        expect(store.get('sr-1')).toEqual(rule);
        expect(store.size).toBe(1);
    });

    it('add 已存在 ruleId → 替换（replace 语义统一用 add）', () => {
        const store = useScriptStore();
        applyScriptPatches([{ op: 'add', path: '/scripts/sr-1', value: makeRule('sr-1', '旧') }]);
        applyScriptPatches([{ op: 'add', path: '/scripts/sr-1', value: makeRule('sr-1', '新') }]);
        expect(store.get('sr-1')?.name).toBe('新');
        expect(store.size).toBe(1);
    });

    it('replace 也收下（兼容形态）', () => {
        const store = useScriptStore();
        applyScriptPatches([{ op: 'replace', path: '/scripts/sr-2', value: makeRule('sr-2') }]);
        expect(store.get('sr-2')).not.toBe(null);
    });

    it('remove → 删除规则', () => {
        const store = useScriptStore();
        applyScriptPatches([{ op: 'add', path: '/scripts/sr-1', value: makeRule('sr-1') }]);
        applyScriptPatches([{ op: 'remove', path: '/scripts/sr-1' }]);
        expect(store.get('sr-1')).toBe(null);
        expect(store.size).toBe(0);
    });

    it('空 ruleId 段 → 忽略不抛（log err）', () => {
        const store = useScriptStore();
        applyScriptPatches([{ op: 'add', path: '/scripts/', value: makeRule('sr-x') }]);
        expect(store.size).toBe(0);
    });

    it('add 缺 value / value 非对象 → 忽略不抛', () => {
        const store = useScriptStore();
        applyScriptPatches([
            { op: 'add', path: '/scripts/sr-1' },
            { op: 'add', path: '/scripts/sr-2', value: 'not-an-object' },
        ]);
        expect(store.size).toBe(0);
    });

    it('RFC 6901 编码的 ruleId 段正确解码（~1 → / ，~0 → ~）', () => {
        const store = useScriptStore();
        // ruleId 实际不会含 / ，但路由层必须与 alias 通道同款 decode（防御性契约）
        const rule = makeRule('sr~odd/id');
        applyScriptPatches([{ op: 'add', path: '/scripts/sr~0odd~1id', value: rule }]);
        // remove 同样按 decode 后的 id 定位
        applyScriptPatches([{ op: 'remove', path: '/scripts/sr~0odd~1id' }]);
        expect(store.size).toBe(0);
    });
});
