// @vitest-environment happy-dom
/**
 * 0.7.0-P5-H：BlockNode / BlockStack 试跑高亮 smoke。
 *
 * <p>注入 {@code BLOCK_HIGHLIGHT_KEY}（result + detail map ref），验证：
 * ① 命中本块 path 的 result → {@code data-hl-result} 属性 + 高亮 class；
 * ② detail → title；③ 未命中 / 未注入时不高亮、不崩（走空 map 兜底）。
 * happy-dom + pinia + 锁中文 locale。</p>
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick, ref } from 'vue';

import BlockNode from '../BlockNode.vue';
import BlockStack from '../BlockStack.vue';
import { BLOCK_HIGHLIGHT_KEY, type HighlightInject } from '../highlightInjection';
import type { StepResult } from '../traceHighlight';
import { useUiStore } from '@/stores/ui';
import type { ScriptAction, ScriptRule } from '@/types/protocol';

function makeHighlight(results: [string, StepResult][], details: [string, string][] = []): HighlightInject {
    return {
        results: ref(new Map(results)),
        details: ref(new Map(details)),
    };
}

describe('BlockNode 试跑高亮 smoke', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
    });

    it('命中本块 path → data-hl-result + 高亮 class', async () => {
        const action: ScriptAction = { type: 'log', message: 'hi' };
        const w = mount(BlockNode, {
            props: { action, path: 'actions/0' },
            global: { provide: { [BLOCK_HIGHLIGHT_KEY as symbol]: makeHighlight([['actions/0', 'ok']]) } },
        });
        await nextTick();
        const node = w.find('.hc-block-node');
        expect(node.attributes('data-hl-result')).toBe('ok');
        expect(node.classes()).toContain('hc-block-node-hl');
    });

    it('detail → title 提示', async () => {
        const action: ScriptAction = { type: 'log', message: 'hi' };
        const w = mount(BlockNode, {
            props: { action, path: 'actions/0' },
            global: {
                provide: {
                    [BLOCK_HIGHLIGHT_KEY as symbol]: makeHighlight([['actions/0', 'error']], [['actions/0', '执行抛异常']]),
                },
            },
        });
        await nextTick();
        expect(w.find('.hc-block-node').attributes('title')).toBe('执行抛异常');
    });

    it('未命中本块 path → 不高亮（无 data-hl-result）', async () => {
        const action: ScriptAction = { type: 'log', message: 'hi' };
        const w = mount(BlockNode, {
            props: { action, path: 'actions/1' },
            global: { provide: { [BLOCK_HIGHLIGHT_KEY as symbol]: makeHighlight([['actions/0', 'ok']]) } },
        });
        await nextTick();
        const node = w.find('.hc-block-node');
        // happy-dom 下 :data-hl-result="null" 不渲染该属性
        expect(node.attributes('data-hl-result')).toBeUndefined();
        expect(node.classes()).not.toContain('hc-block-node-hl');
    });

    it('未注入高亮（单独 mount）→ 走空 map 兜底，不崩', async () => {
        const action: ScriptAction = { type: 'log', message: 'hi' };
        const w = mount(BlockNode, { props: { action, path: 'actions/0' } });
        await nextTick();
        expect(w.find('.hc-block-node').exists()).toBe(true);
        expect(w.find('.hc-block-node').attributes('data-hl-result')).toBeUndefined();
    });
});

describe('BlockStack 帽子高亮 smoke（blockId=trigger）', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
    });

    function makeRule(): ScriptRule {
        return {
            id: 'sr-1', wallId: 'w-x', enabled: true, name: '我的规则',
            trigger: { type: 'wallReady' }, actions: [{ type: 'log', message: 'x' }], blockLayout: '{}',
        };
    }

    it("trigger step 命中 → 帽子 data-hl-result", async () => {
        const w = mount(BlockStack, {
            props: { rule: makeRule(), x: 0, y: 0 },
            global: { provide: { [BLOCK_HIGHLIGHT_KEY as symbol]: makeHighlight([['trigger', 'ok']]) } },
        });
        await nextTick();
        const hat = w.find('.hc-stack-hat');
        expect(hat.attributes('data-block-path')).toBe('trigger');
        expect(hat.attributes('data-hl-result')).toBe('ok');
    });

    it('帽子 detail → title', async () => {
        const w = mount(BlockStack, {
            props: { rule: makeRule(), x: 0, y: 0 },
            global: {
                provide: { [BLOCK_HIGHLIGHT_KEY as symbol]: makeHighlight([['trigger', 'skipped']], [['trigger', '条件不满足']]) },
            },
        });
        await nextTick();
        expect(w.find('.hc-stack-hat').attributes('title')).toBe('条件不满足');
    });

    it('未注入 → 帽子无高亮属性，不崩', async () => {
        const w = mount(BlockStack, { props: { rule: makeRule(), x: 0, y: 0 } });
        await nextTick();
        expect(w.find('.hc-stack-hat').attributes('data-hl-result')).toBeUndefined();
    });
});
