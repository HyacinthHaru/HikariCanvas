// @vitest-environment happy-dom
/**
 * 0.7.1「拖积木到删除区删单个积木」smoke + 几何单测。
 *
 * <p>三层验证：① 删除区几何纯函数（{@link computeDeleteZoneRect} / {@link pointInRect}）；
 * ② store 级 {@code scriptEdit.removeAction} 删对路径（含 if then/else 嵌套）；③ BlockCanvas
 * 端到端——拖动作积木亮删除区，松手在区内删该积木 / 区外正常重排。stub getBoundingClientRect 给真
 * viewport 矩形，让 pointerup 命中判定可走完整路径。</p>
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import BlockCanvas from '../BlockCanvas.vue';
import {
    computeDeleteZoneRect,
    pointInRect,
    DELETE_ZONE_W,
    DELETE_ZONE_H,
    DELETE_ZONE_BOTTOM,
} from '../useBlockDrag';
import { useUiStore } from '@/stores/ui';
import { useScriptStore } from '@/stores/scripts';
import { useScriptEditStore } from '@/stores/scriptEdit';
import type { ScriptRule, ScriptAction } from '@/types/protocol';

vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({
        sendScriptCreate: vi.fn(() => Promise.resolve()),
        sendScriptUpdate: vi.fn(() => Promise.resolve()),
        sendScriptDelete: vi.fn(() => Promise.resolve()),
        sendScriptEnable: vi.fn(() => Promise.resolve()),
    }),
}));

function makeRule(id: string, actions: ScriptAction[]): ScriptRule {
    return {
        id, wallId: 'w-1', enabled: true, name: id, trigger: { type: 'wallReady' },
        actions, blockLayout: '{}',
    };
}

beforeEach(() => {
    setActivePinia(createPinia());
    useUiStore().locale = 'zh';
    if (!(HTMLElement.prototype as unknown as { setPointerCapture?: unknown }).setPointerCapture) {
        (HTMLElement.prototype as unknown as { setPointerCapture: () => void }).setPointerCapture = () => { /* stub */ };
        (HTMLElement.prototype as unknown as { releasePointerCapture: () => void }).releasePointerCapture = () => { /* stub */ };
    }
});

describe('删除区几何（纯函数）', () => {
    const vp = { left: 100, top: 50, width: 800, height: 600 };

    it('删除区贴 viewport 底部居中，尺寸固定', () => {
        const r = computeDeleteZoneRect(vp);
        expect(r.width).toBe(DELETE_ZONE_W);
        expect(r.height).toBe(DELETE_ZONE_H);
        // 水平居中
        expect(r.left).toBe(vp.left + (vp.width - DELETE_ZONE_W) / 2);
        // 底部对齐：top = vpTop + vpHeight - bottomMargin - h
        expect(r.top).toBe(vp.top + vp.height - DELETE_ZONE_BOTTOM - DELETE_ZONE_H);
    });

    it('窄 viewport 时删除区宽度收缩不越界', () => {
        const r = computeDeleteZoneRect({ left: 0, top: 0, width: 120, height: 400 });
        expect(r.width).toBe(120 - 24);
        expect(r.left).toBe((120 - (120 - 24)) / 2);
    });

    it('pointInRect 命中判定', () => {
        const r = computeDeleteZoneRect(vp);
        const cx = r.left + r.width / 2;
        const cy = r.top + r.height / 2;
        expect(pointInRect(r, cx, cy)).toBe(true); // 区中心命中
        expect(pointInRect(r, r.left - 5, cy)).toBe(false); // 左侧区外
        expect(pointInRect(r, cx, r.top - 5)).toBe(false); // 上方区外
        expect(pointInRect(r, r.left, r.top)).toBe(true); // 左上角（闭区间）命中
    });
});

describe('scriptEdit.removeAction（store 级）', () => {
    it('删顶层动作积木', () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1', [
            { type: 'log', message: 'A' },
            { type: 'wait', ms: 500 },
            { type: 'log', message: 'C' },
        ])]);
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.removeAction('actions/1'); // 删中间 wait
        expect(edit.workingCopy!.actions.map((a) => a.type)).toEqual(['log', 'log']);
        // 进 undo（可撤销）
        expect(edit.undoStack.length).toBe(1);
    });

    it('删 if then 分支内的嵌套积木（不动外层）', () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1', [
            {
                type: 'if', condition: 'var(x) > 0',
                then: [{ type: 'log', message: 'T0' }, { type: 'wait', ms: 100 }],
                else: [],
            },
        ])]);
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.removeAction('actions/0/then/0'); // 删 then 的第一个
        const ifNode = edit.workingCopy!.actions[0] as Extract<ScriptAction, { type: 'if' }>;
        expect(ifNode.then.map((a) => a.type)).toEqual(['wait']);
    });

    it('非法 / 越界 path → no-op（不标脏、不 push undo）', () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1', [{ type: 'log', message: 'A' }])]);
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.removeAction('actions/9'); // 越界
        expect(edit.workingCopy!.actions.length).toBe(1);
        expect(edit.undoStack.length).toBe(0);
        expect(edit.dirty).toBe(false);
    });
});

describe('BlockCanvas 端到端：拖积木到删除区', () => {
    // 给 viewport 真矩形，让 computeDeleteZoneRect 算出可命中的区。
    function stubViewportRect(el: HTMLElement): void {
        el.getBoundingClientRect = () => ({
            left: 0, top: 0, width: 800, height: 600, right: 800, bottom: 600, x: 0, y: 0,
            toJSON: () => ({}),
        }) as DOMRect;
    }

    it('拖动作积木 → 删除区出现；松手在区内 → 删该积木', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1', [
            { type: 'log', message: 'A' },
            { type: 'wait', ms: 500 },
        ])]);
        const w = mount(BlockCanvas, { attachTo: document.body });
        await nextTick();
        const viewport = w.find('.hc-block-viewport').element as HTMLElement;
        stubViewportRect(viewport);

        // 删除区初始不显示
        expect(w.find('.hc-delete-zone').exists()).toBe(false);

        // 拖 actions/1（wait）
        const block = w.find('[data-block-path="actions/1"]');
        await block.trigger('pointerdown', { button: 0, clientX: 50, clientY: 50, pointerId: 1 });
        // 0.7.1 拖动阈值：需超阈值位移才真正启动拖动（删除区才 active）。
        window.dispatchEvent(new PointerEvent('pointermove', { clientX: 200, clientY: 200 }));
        await nextTick();
        // 删除区出现（拖动作积木时 active）
        expect(w.find('.hc-delete-zone').exists()).toBe(true);

        // 松手在删除区中心（底部居中 ~ x=400, y=600-24-32=544）
        const r = computeDeleteZoneRect({ left: 0, top: 0, width: 800, height: 600 });
        const up = new Event('pointerup') as unknown as PointerEvent;
        Object.defineProperty(up, 'clientX', { value: r.left + r.width / 2 });
        Object.defineProperty(up, 'clientY', { value: r.top + r.height / 2 });
        window.dispatchEvent(up);
        await nextTick();

        const edit = useScriptEditStore();
        // wait 被删，只剩 log A
        expect(edit.workingCopy!.actions.map((a) => a.type)).toEqual(['log']);
        // 删除区收起
        expect(w.find('.hc-delete-zone').exists()).toBe(false);
        w.unmount();
    });

    it('松手在删除区外 → 不删除（正常重排路径，积木保留）', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1', [
            { type: 'log', message: 'A' },
            { type: 'wait', ms: 500 },
        ])]);
        const w = mount(BlockCanvas, { attachTo: document.body });
        await nextTick();
        stubViewportRect(w.find('.hc-block-viewport').element as HTMLElement);

        const block = w.find('[data-block-path="actions/1"]');
        await block.trigger('pointerdown', { button: 0, clientX: 50, clientY: 50, pointerId: 1 });
        // 0.7.1 拖动阈值：需超阈值位移才真正启动拖动。
        window.dispatchEvent(new PointerEvent('pointermove', { clientX: 200, clientY: 200 }));
        await nextTick();

        // 松手在顶部（远离删除区 + 远离任何插槽）→ 既不删也不重排
        const up = new Event('pointerup') as unknown as PointerEvent;
        Object.defineProperty(up, 'clientX', { value: 50 });
        Object.defineProperty(up, 'clientY', { value: 5 });
        window.dispatchEvent(up);
        await nextTick();

        const edit = useScriptEditStore();
        expect(edit.workingCopy!.actions.length).toBe(2); // 两块都还在
        w.unmount();
    });

    it('移堆（拖帽子）不显示删除区', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1', [{ type: 'log', message: 'A' }])]);
        const w = mount(BlockCanvas, { attachTo: document.body });
        await nextTick();
        stubViewportRect(w.find('.hc-block-viewport').element as HTMLElement);

        const hat = w.find('[data-block-path="trigger"]');
        await hat.trigger('pointerdown', { button: 0, clientX: 30, clientY: 30, pointerId: 1 });
        await nextTick();
        // 移堆不亮删除区（规则删除有头部入口）
        expect(w.find('.hc-delete-zone').exists()).toBe(false);
        window.dispatchEvent(new Event('pointerup'));
        await nextTick();
        w.unmount();
    });
});
