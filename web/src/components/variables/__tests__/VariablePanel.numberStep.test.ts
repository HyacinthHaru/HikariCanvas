// @vitest-environment happy-dom
/**
 * 变量面板的 +1 / −1 连点。
 *
 * <p>加减是「读当前值 → 算新值 → 把新值发过去」。基数如果每次都取自 store，而 store 要等
 * 服务端回执再推回来才更新，那么连点几下就都拿同一个旧基数算，发出去的是同一个数——加了
 * 半天只加了一次。长按（50ms 一发）在稍慢一点的网络下必然踩中。</p>
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import VariablePanel from '../VariablePanel.vue';
import { useUiStore } from '@/stores/ui';
import { useProjectStore } from '@/stores/project';
import { useVariableStore } from '@/stores/variables';
import type { Variable } from '@/types/variable';

/** 服务端回执故意不落地（模拟"回执还在路上"），由测试自己决定何时推回 store。 */
let resolveSet: Array<() => void> = [];
const sendVariableSet = vi.fn((_fullName: string, _value: string) => new Promise<void>((res) => {
    resolveSet.push(() => res());
}));

vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({
        sendVariableSet,
        sendVariableDelete: vi.fn(),
        sendVariableAliasSet: vi.fn(),
        sendVariableAliasClear: vi.fn(),
    }),
}));

const FULL_NAME = 'user:w-abc/count';

function mkVar(current: string): Variable {
    return {
        namespace: 'user:w-abc', key: 'count', type: 'NUMBER',
        defaultValue: '0', currentValue: current, updatedAt: 0, ttl: 0, source: 'manual',
    };
}

beforeEach(() => {
    setActivePinia(createPinia());
    useUiStore().locale = 'zh';
    useUiStore().variablePanelOpen = true;
    resolveSet = [];
    sendVariableSet.mockClear();
});

function mountPanel() {
    const project = useProjectStore();
    project.setWallMeta('w-abc', null, null, null, null);
    useVariableStore().set(FULL_NAME, mkVar('5'));
    return mount(VariablePanel, { attachTo: document.body });
}

/** 「+1」/「-1」按钮（NumberStepButton 用 title 区分）。 */
function plusButton(w: ReturnType<typeof mountPanel>) {
    return w.findAll('button').find((b) => (b.attributes('title') ?? '').startsWith('+1'));
}

function minusButton(w: ReturnType<typeof mountPanel>) {
    return w.findAll('button').find((b) => (b.attributes('title') ?? '').startsWith('-1'));
}

/** 发出去的那些值。 */
function sentValues(): string[] {
    return sendVariableSet.mock.calls.map((c) => c[1] as string);
}

describe('VariablePanel — 数值加减', () => {
    it('回执没回来之前连点三下，发出去的是 6 / 7 / 8 而不是三个 6', async () => {
        const w = mountPanel();
        await nextTick();
        const plus = plusButton(w)!;

        await plus.trigger('pointerdown');
        await plus.trigger('pointerup');
        await plus.trigger('pointerdown');
        await plus.trigger('pointerup');
        await plus.trigger('pointerdown');
        await plus.trigger('pointerup');
        await nextTick();

        expect(sentValues()).toEqual(['6', '7', '8']);
        w.unmount();
    });

    it('累加期间面板显示本地值，服务端追上后交回权威值', async () => {
        const w = mountPanel();
        await nextTick();
        const plus = plusButton(w)!;

        await plus.trigger('pointerdown');
        await plus.trigger('pointerup');
        await plus.trigger('pointerdown');
        await plus.trigger('pointerup');
        await nextTick();
        expect(w.text()).toContain('7');

        // 回执落地 + 服务端把新值推回来
        resolveSet.forEach((r) => r());
        await nextTick();
        await nextTick();
        useVariableStore().set(FULL_NAME, mkVar('7'));
        await nextTick();
        await nextTick();

        expect(w.text()).toContain('7');

        // 交回权威值之后，再点一下要从服务端的值接着加
        await plus.trigger('pointerdown');
        await plus.trigger('pointerup');
        await nextTick();
        expect(sentValues()[sentValues().length - 1]).toBe('8');
        w.unmount();
    });

    it('别处把值改成 20 之后，再点 +1 是 21（不接着本地的旧账算）', async () => {
        const w = mountPanel();
        await nextTick();
        const plus = plusButton(w)!;

        await plus.trigger('pointerdown');
        await plus.trigger('pointerup');
        await nextTick();
        resolveSet.forEach((r) => r());
        await nextTick();
        await nextTick();

        // 别的人 / 插件把它改成了 20
        useVariableStore().set(FULL_NAME, mkVar('20'));
        await nextTick();
        await nextTick();

        await plus.trigger('pointerdown');
        await plus.trigger('pointerup');
        await nextTick();
        expect(sentValues()[sentValues().length - 1]).toBe('21');
        w.unmount();
    });

    it('减号同样按本地值累减', async () => {
        const w = mountPanel();
        await nextTick();
        const minus = minusButton(w)!;

        await minus.trigger('pointerdown');
        await minus.trigger('pointerup');
        await minus.trigger('pointerdown');
        await minus.trigger('pointerup');
        await nextTick();

        expect(sentValues()).toEqual(['4', '3']);
        w.unmount();
    });
});
