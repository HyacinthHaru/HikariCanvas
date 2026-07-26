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
import { BLOCK_HIGHLIGHT_KEY, BLOCK_STACK_RULE_KEY, type HighlightInject } from '../highlightInjection';
import { highlightKey, type StepResult } from '../traceHighlight';
import { useUiStore } from '@/stores/ui';
import type { ScriptAction, ScriptRule } from '@/types/protocol';

function makeHighlight(results: [string, StepResult][], details: [string, string][] = []): HighlightInject {
    return {
        results: ref(new Map(results)),
        details: ref(new Map(details)),
    };
}

/** 高亮 map 的 key 是「规则 id + blockId」复合键，测试统一经 highlightKey 拼。 */
function k(blockId: string, ruleId = 'sr-1'): string {
    return highlightKey(ruleId, blockId);
}

/** BlockNode 单独挂载时要自带规则上下文（画布上由 BlockStack provide）。 */
function nodeProvide(highlight: HighlightInject, ruleId = 'sr-1') {
    return {
        [BLOCK_HIGHLIGHT_KEY as symbol]: highlight,
        [BLOCK_STACK_RULE_KEY as symbol]: ref(ruleId),
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
            global: { provide: nodeProvide(makeHighlight([[k('actions/0'), 'ok']])) },
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
                provide: nodeProvide(
                    makeHighlight([[k('actions/0'), 'error']], [[k('actions/0'), '执行抛异常']]),
                ),
            },
        });
        await nextTick();
        expect(w.find('.hc-block-node').attributes('title')).toBe('执行抛异常');
    });

    it('未命中本块 path → 不高亮（无 data-hl-result）', async () => {
        const action: ScriptAction = { type: 'log', message: 'hi' };
        const w = mount(BlockNode, {
            props: { action, path: 'actions/1' },
            global: { provide: nodeProvide(makeHighlight([[k('actions/0'), 'ok']])) },
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
            global: { provide: { [BLOCK_HIGHLIGHT_KEY as symbol]: makeHighlight([[k('trigger'), 'ok']]) } },
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
                provide: {
                    [BLOCK_HIGHLIGHT_KEY as symbol]:
                        makeHighlight([[k('trigger'), 'skipped']], [[k('trigger'), '条件不满足']]),
                },
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

    it('试跑的是别的规则 → 本堆帽子和积木都不亮（不跨堆误亮）', async () => {
        // 画布上所有堆共用同一份高亮 map：试跑 sr-9 时 sr-1 这一堆必须一片安静。
        const highlight = makeHighlight(
            [[k('trigger', 'sr-9'), 'ok'], [k('actions/0', 'sr-9'), 'ok']],
            [[k('trigger', 'sr-9'), '别的规则的说明']],
        );
        const w = mount(BlockStack, {
            props: { rule: makeRule(), x: 0, y: 0 },
            global: { provide: { [BLOCK_HIGHLIGHT_KEY as symbol]: highlight } },
        });
        await nextTick();
        expect(w.find('.hc-stack-hat').attributes('data-hl-result')).toBeUndefined();
        expect(w.find('.hc-stack-hat').attributes('title')).toBeUndefined();
        expect(w.find('.hc-block-node').attributes('data-hl-result')).toBeUndefined();
    });

    it('试跑的就是本规则 → 帽子与堆内积木都点亮', async () => {
        const highlight = makeHighlight([[k('trigger'), 'ok'], [k('actions/0'), 'blocked']]);
        const w = mount(BlockStack, {
            props: { rule: makeRule(), x: 0, y: 0 },
            global: { provide: { [BLOCK_HIGHLIGHT_KEY as symbol]: highlight } },
        });
        await nextTick();
        expect(w.find('.hc-stack-hat').attributes('data-hl-result')).toBe('ok');
        // 堆内 BlockNode 自己从 BlockStack provide 的规则 id 拼 key，无需外部再注入
        expect(w.find('.hc-block-node').attributes('data-hl-result')).toBe('blocked');
    });
});
