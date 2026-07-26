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

    // 8 个"元素动作"积木（移到 / 改大小 / 旋转到 …）不在 ACTION_DEFS 里，只在友好皮肤表里。
    // 浮层以前只查 ACTION_DEFS/TRIGGER_DEFS，查不到就直接把 kind 原文显示出来 —— 用户在积木库里
    // 看到的是"移到"，一拖起来变成灰底的 moveTo。
    it('拖元素动作积木：浮层显示的是积木库里那个中文标题 + 动作蓝', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1')]);
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        const w = mount(BlockCanvas, { attachTo: document.body });
        await nextTick();

        const ev = new Event('pointerdown') as unknown as PointerEvent;
        Object.defineProperty(ev, 'button', { value: 0 });
        Object.defineProperty(ev, 'pointerId', { value: 1 });
        Object.defineProperty(ev, 'clientX', { value: 100 });
        Object.defineProperty(ev, 'clientY', { value: 100 });
        Object.defineProperty(ev, 'currentTarget', { value: w.element });
        (w.vm as unknown as { startPaletteDrag: (k: string, e: PointerEvent) => void }).startPaletteDrag('moveTo', ev);
        await nextTick();

        const ghost = document.querySelector('.hc-drag-ghost') as HTMLElement;
        expect(ghost).toBeTruthy();
        expect(ghost.textContent?.trim()).toBe('移到');
        expect(ghost.getAttribute('style')).toContain('var(--ctp-blue)');

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

/**
 * 接线验证：ctrl+滚轮缩放发生在拖动中时，画布要等一帧再让拖拽层重测插槽（#97）。
 * 这里用可改的假矩形模拟"缩放后积木换了屏幕位置"，看吸附指示线有没有跟着到新位置。
 */
describe('BlockCanvas 拖动中缩放 → 重测吸附几何', () => {
    /** 给元素装一个可改的假矩形。 */
    function stubRect(el: Element, rect: { left: number; top: number; width: number; height: number }) {
        const cur = { ...rect };
        (el as HTMLElement).getBoundingClientRect = () => ({
            left: cur.left, top: cur.top, width: cur.width, height: cur.height,
            right: cur.left + cur.width, bottom: cur.top + cur.height,
            x: cur.left, y: cur.top, toJSON: () => ({}),
        }) as DOMRect;
        return (top: number) => { cur.top = top; };
    }

    it('拖动中 ctrl+滚轮 → 指示线挪到新几何上（不再停在旧位置）', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1')]);
        useScriptEditStore().selectRule('sr-1');
        const w = mount(BlockCanvas, { attachTo: document.body });
        await nextTick();

        stubRect(w.find('.hc-block-viewport').element, { left: 0, top: 0, width: 800, height: 600 });
        const moveA = stubRect(w.find('[data-block-path="actions/0"]').element, { left: 10, top: 100, width: 260, height: 30 });
        const moveB = stubRect(w.find('[data-block-path="actions/1"]').element, { left: 10, top: 140, width: 260, height: 30 });

        await w.find('[data-block-path="actions/0"]').trigger('pointerdown', { button: 0, clientX: 120, clientY: 110, pointerId: 1 });
        window.dispatchEvent(new PointerEvent('pointermove', { clientX: 9999, clientY: 9999 }));   // 越过拖动阈值
        // 指针停在"将来"才会有插槽的地方：现在什么都吸不上
        window.dispatchEvent(new PointerEvent('pointermove', { clientX: 140, clientY: 363 }));
        await nextTick();
        expect(document.querySelector('.hc-drop-indicator')).toBeNull();

        // 缩放：两块整体下移（尾插槽中心随之到 y≈363）
        moveA(300);
        moveB(340);
        await w.find('.hc-block-viewport').trigger('wheel', { ctrlKey: true, deltaY: -100, clientX: 140, clientY: 363 });
        await nextTick();
        await nextTick();
        expect(document.querySelector('.hc-drop-indicator')).toBeTruthy();

        window.dispatchEvent(new Event('pointerup'));
        await nextTick();
        w.unmount();
    });
});

/**
 * 右下角「本脚本变量实时预览」面板挂在画布 viewport 里，而画布把空白处按住左键一律当作平移。
 * 面板不拦 pointerdown 的话，在面板上按住一拖整个画布就跟着跑，变量值也没法选中复制（#137）。
 */
describe('BlockCanvas 变量预览面板不触发画布平移', () => {
    it('在面板上按住拖动 → 画布不平移', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1')]);
        const w = mount(BlockCanvas, { attachTo: document.body });
        await nextTick();

        const panel = w.find('.hc-var-watch');
        expect(panel.exists()).toBe(true);
        const before = w.find('.hc-block-world').attributes('style');

        panel.element.dispatchEvent(new PointerEvent('pointerdown', {
            bubbles: true, button: 0, clientX: 500, clientY: 500, pointerId: 1,
        }));
        await w.find('.hc-block-viewport').trigger('pointermove', { clientX: 300, clientY: 200 });
        await nextTick();

        expect(w.find('.hc-block-world').attributes('style')).toBe(before);

        // 对照：同样的操作落在画布空白处是会平移的（证明这个测试真的测得到平移）
        w.find('.hc-block-viewport').element.dispatchEvent(new PointerEvent('pointerdown', {
            bubbles: true, button: 0, clientX: 500, clientY: 500, pointerId: 1,
        }));
        await w.find('.hc-block-viewport').trigger('pointermove', { clientX: 300, clientY: 200 });
        await nextTick();
        expect(w.find('.hc-block-world').attributes('style')).not.toBe(before);

        window.dispatchEvent(new Event('pointerup'));
        w.unmount();
    });
});
