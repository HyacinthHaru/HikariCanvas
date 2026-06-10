// @vitest-environment happy-dom
/**
 * 0.7.0-P4-C：BlockCanvas 真规则上画布 smoke。
 *
 * <p>验证：scripts.listSorted 有 N 规则 → 渲染 N 个 BlockStack；坐标来自各规则
 * blockLayout（命中显式坐标）或 autoLayout 兜底（缺坐标纵向排布）。</p>
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import BlockCanvas from '../BlockCanvas.vue';
import { useUiStore } from '@/stores/ui';
import { useScriptStore } from '@/stores/scripts';
import type { ScriptRule } from '@/types/protocol';

function makeRule(id: string, blockLayout: string): ScriptRule {
    return {
        id,
        wallId: 'w-1',
        enabled: true,
        name: id,
        trigger: { type: 'wallReady' },
        actions: [{ type: 'log', message: 'x' }],
        blockLayout,
    };
}

describe('BlockCanvas 真渲染 smoke', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
    });

    it('2 规则 → 2 个 BlockStack 渲染', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([
            makeRule('sr-1', '{}'),
            makeRule('sr-2', '{}'),
        ]);
        const w = mount(BlockCanvas);
        await nextTick();
        const stacks = w.findAll('.hc-block-stack');
        expect(stacks.length).toBe(2);
        expect(w.find('[data-rule-id="sr-1"]').exists()).toBe(true);
        expect(w.find('[data-rule-id="sr-2"]').exists()).toBe(true);
    });

    it('显式 blockLayout 坐标命中（stacks[rule.id]）', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([
            makeRule('sr-1', '{"stacks":{"sr-1":{"x":300,"y":150}}}'),
        ]);
        const w = mount(BlockCanvas);
        await nextTick();
        const stack = w.find('[data-rule-id="sr-1"]').element as HTMLElement;
        expect(stack.style.left).toBe('300px');
        expect(stack.style.top).toBe('150px');
    });

    it('缺坐标走 autoLayout 纵向排布（首条 40,40 / 次条 40,360）', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([
            makeRule('sr-a', '{}'),
            makeRule('sr-b', '{}'),
        ]);
        const w = mount(BlockCanvas);
        await nextTick();
        const a = w.find('[data-rule-id="sr-a"]').element as HTMLElement;
        const b = w.find('[data-rule-id="sr-b"]').element as HTMLElement;
        expect([a.style.left, a.style.top]).toEqual(['40px', '40px']);
        expect([b.style.left, b.style.top]).toEqual(['40px', '360px']);
    });

    it('混合：一条显式 + 一条缺坐标（缺的走 autoLayout 序号 0）', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([
            makeRule('sr-explicit', '{"stacks":{"sr-explicit":{"x":500,"y":500}}}'),
            makeRule('sr-missing', '{}'),
        ]);
        const w = mount(BlockCanvas);
        await nextTick();
        const exp = w.find('[data-rule-id="sr-explicit"]').element as HTMLElement;
        const miss = w.find('[data-rule-id="sr-missing"]').element as HTMLElement;
        expect([exp.style.left, exp.style.top]).toEqual(['500px', '500px']);
        // sr-missing 是唯一缺坐标的 → autoLayout 第 0 个 → (40, 40)
        expect([miss.style.left, miss.style.top]).toEqual(['40px', '40px']);
    });

    it('空 store → 0 个 BlockStack（不崩）', async () => {
        useScriptStore(); // 默认空
        const w = mount(BlockCanvas);
        await nextTick();
        expect(w.findAll('.hc-block-stack').length).toBe(0);
    });
});
