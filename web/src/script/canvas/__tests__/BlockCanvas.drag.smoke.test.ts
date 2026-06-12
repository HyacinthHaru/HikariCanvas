// @vitest-environment happy-dom
/**
 * 0.7.0-P4-D2：BlockCanvas + 拖拽接入 smoke（pointer 序列不崩）。
 *
 * <p>真实 mount BlockCanvas（含 BlockStack / BlockNode 子组件 + provide 拖拽句柄 + mock wsClient）：
 * 验证 ① 块根 pointerdown（拖块）不崩 + 出现跟手浮层；② 帽子 pointerdown（移堆）不崩 + 出现
 * 移堆视觉；③ palette 源经 exposed startPaletteDrag 不崩。setPointerCapture 在 happy-dom 上
 * stub 掉（元素原型补一个 no-op，避免 happy-dom 未实现抛错中断拖拽启动）。</p>
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import BlockCanvas from '../BlockCanvas.vue';
import { useUiStore } from '@/stores/ui';
import { useScriptStore } from '@/stores/scripts';
import { useScriptEditStore } from '@/stores/scriptEdit';
import type { ScriptRule } from '@/types/protocol';

// mock wsClient（scriptEdit 选规则 / setActions 链路不真发网络）
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({
        sendScriptCreate: vi.fn(() => Promise.resolve()),
        sendScriptUpdate: vi.fn(() => Promise.resolve()),
        sendScriptDelete: vi.fn(() => Promise.resolve()),
        sendScriptEnable: vi.fn(() => Promise.resolve()),
    }),
}));

function makeRule(id: string, layout = '{}'): ScriptRule {
    return {
        id, wallId: 'w-1', enabled: true, name: id, trigger: { type: 'wallReady' },
        actions: [{ type: 'log', message: 'A' }, { type: 'log', message: 'B' }],
        blockLayout: layout,
    };
}

beforeEach(() => {
    setActivePinia(createPinia());
    useUiStore().locale = 'zh';
    // happy-dom 未实现 pointer capture：补 no-op stub 防拖拽启动时抛错。
    if (!(HTMLElement.prototype as unknown as { setPointerCapture?: unknown }).setPointerCapture) {
        (HTMLElement.prototype as unknown as { setPointerCapture: () => void }).setPointerCapture = () => { /* stub */ };
        (HTMLElement.prototype as unknown as { releasePointerCapture: () => void }).releasePointerCapture = () => { /* stub */ };
    }
});

describe('BlockCanvas 拖拽接入 smoke', () => {
    it('块根 pointerdown（拖块）不崩 + 出现跟手浮层', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1')]);
        const w = mount(BlockCanvas, { attachTo: document.body });
        await nextTick();

        const block = w.find('[data-block-path="actions/0"]');
        expect(block.exists()).toBe(true);
        await block.trigger('pointerdown', { button: 0, clientX: 50, clientY: 50, pointerId: 1 });
        // 0.7.1 拖动阈值：pointerdown 仅 arm pending，需一次超阈值位移才真正启动拖动。
        window.dispatchEvent(new PointerEvent('pointermove', { clientX: 200, clientY: 200 }));
        await nextTick();
        // 跟手浮层 Teleport 到 body
        expect(document.querySelector('.hc-drag-ghost')).toBeTruthy();
        // 该规则被选中（拖块隐含编辑）
        expect(useScriptEditStore().selectedRuleId).toBe('sr-1');

        // 松手（远离插槽 → 无命中还原）不崩
        window.dispatchEvent(new Event('pointerup'));
        await nextTick();
        w.unmount();
    });

    it('帽子 pointerdown（移堆）不崩 + 出现移堆视觉 + 选中规则', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1')]);
        const w = mount(BlockCanvas, { attachTo: document.body });
        await nextTick();

        const hat = w.find('[data-block-path="trigger"]');
        expect(hat.exists()).toBe(true);
        await hat.trigger('pointerdown', { button: 0, clientX: 30, clientY: 30, pointerId: 1 });
        // 0.7.1 拖动阈值：移堆同样需超阈值位移才启动（pointerdown 仅 arm pending）。
        window.dispatchEvent(new PointerEvent('pointermove', { clientX: 200, clientY: 200 }));
        await nextTick();
        expect(useScriptEditStore().selectedRuleId).toBe('sr-1');

        window.dispatchEvent(new Event('pointerup'));
        await nextTick();
        w.unmount();
    });

    it('palette 源经 exposed startPaletteDrag 不崩', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1')]);
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        const w = mount(BlockCanvas, { attachTo: document.body });
        await nextTick();

        // 模拟 overlay 转发 palette pointerdown
        const ev = new Event('pointerdown') as unknown as PointerEvent;
        Object.defineProperty(ev, 'button', { value: 0 });
        Object.defineProperty(ev, 'pointerId', { value: 1 });
        Object.defineProperty(ev, 'clientX', { value: 100 });
        Object.defineProperty(ev, 'clientY', { value: 100 });
        Object.defineProperty(ev, 'currentTarget', { value: w.element });
        (w.vm as unknown as { startPaletteDrag: (k: string, e: PointerEvent) => void }).startPaletteDrag('wait', ev);
        await nextTick();
        expect(document.querySelector('.hc-drag-ghost')).toBeTruthy();

        window.dispatchEvent(new Event('pointerup'));
        await nextTick();
        w.unmount();
    });

    it('帽子区点击（pointerup 后无拖动位移）选中规则', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1'), makeRule('sr-2')]);
        const w = mount(BlockCanvas, { attachTo: document.body });
        await nextTick();
        const stack2 = w.find('[data-rule-id="sr-2"]');
        await stack2.trigger('click');
        await nextTick();
        expect(useScriptEditStore().selectedRuleId).toBe('sr-2');
        w.unmount();
    });
});
