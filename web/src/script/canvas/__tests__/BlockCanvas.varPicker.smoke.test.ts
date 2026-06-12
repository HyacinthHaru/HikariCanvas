// @vitest-environment happy-dom
/**
 * 0.7.0-P5 实测修复（次要根因 2：变量「选变量」按钮多点几次才出现选择器）。
 *
 * <p>根因：变量按钮 click 冒泡到 BlockStack.onStackClick → 若规则未选中则 selectRule 在同一次点击里
 * 替换 workingCopy 触发画布重渲，与刚弹出的 picker 抢时序。修法：① BlockNode/BlockStack 在 pointerdown
 * （早于 click）就选中本规则；② picker 打开的 click 截断冒泡。结果：完整 pointerdown→pointerup→click
 * 手势一次打开 picker，且规则已进入编辑态（变量选择落地需 workingCopy）。</p>
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import BlockCanvas from '../BlockCanvas.vue';
import { useUiStore } from '@/stores/ui';
import { useScriptStore } from '@/stores/scripts';
import { useScriptEditStore } from '@/stores/scriptEdit';
import { useNetworkStore } from '@/stores/network';
import { createWsClient } from '@/network/wsClient';
import type { ScriptRule } from '@/types/protocol';

function makeRule(id: string): ScriptRule {
    return {
        id, wallId: 'w-1', enabled: true, name: id, trigger: { type: 'wallReady' },
        actions: [{ type: 'setVariable', fullName: 'user/score', value: '' }], blockLayout: '{}',
    };
}

function fullGesture(el: HTMLElement): void {
    el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true, button: 0 }));
    el.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, composed: true, button: 0 }));
    el.dispatchEvent(new MouseEvent('click', { bubbles: true, composed: true, detail: 1 }));
}

beforeEach(() => {
    setActivePinia(createPinia());
    useUiStore().locale = 'zh';
    createWsClient(); // VariablePicker setup 调 getWsClient()
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false } as unknown as Response)));
});

describe('变量选择器打开可靠性（次要根因 2）', () => {
    it('pointerdown 落在变量按钮 → 先选中本规则（早于 click）', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1')]);
        const edit = useScriptEditStore();
        // 规则未选中
        expect(edit.selectedRuleId).toBeNull();
        const w = mount(BlockCanvas, { attachTo: document.body });
        useNetworkStore().sessionId = '';
        await nextTick();

        const btn = w.find('.hc-pf-var-btn');
        expect(btn.exists()).toBe(true);
        // 仅 pointerdown（还没 click）就应选中本规则
        (btn.element as HTMLElement).dispatchEvent(
            new PointerEvent('pointerdown', { bubbles: true, composed: true, button: 0 }),
        );
        await nextTick();
        expect(edit.selectedRuleId).toBe('sr-1');
        w.unmount();
    });

    it('完整手势：未选规则也一次打开 picker + 规则进入编辑态', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1')]);
        const edit = useScriptEditStore();
        const w = mount(BlockCanvas, { attachTo: document.body });
        useNetworkStore().sessionId = '';
        await nextTick();

        expect(document.querySelector('.hc-pf-picker-host')).toBeNull();
        fullGesture(w.find('.hc-pf-var-btn').element as HTMLElement);
        await nextTick();
        await nextTick();

        // picker 一次到位（Teleport 到 body）+ 规则已选中（变量落地依赖 workingCopy）
        expect(document.querySelector('.hc-pf-picker-host')).not.toBeNull();
        expect(edit.selectedRuleId).toBe('sr-1');
        w.unmount();
    });

    it('已选规则：打开 picker 的 click 不冒泡触发额外 selectRule（无重渲抖动）', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1')]);
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        const spy = vi.spyOn(edit, 'selectRule');
        const w = mount(BlockCanvas, { attachTo: document.body });
        useNetworkStore().sessionId = '';
        await nextTick();

        fullGesture(w.find('.hc-pf-var-btn').element as HTMLElement);
        await nextTick();
        await nextTick();

        // 已是当前规则：pointerdown 的 selectOwningRule 命中 guard 不调，click 又被 stopPropagation
        // 截断不到 onStackClick → selectRule 0 次额外调用 → 无重渲抖动
        expect(spy).not.toHaveBeenCalled();
        expect(document.querySelector('.hc-pf-picker-host')).not.toBeNull();
        w.unmount();
    });

    // ---- 0.7.1 触控板敏感度修复（修复 A：isFormTarget 用 closest）----
    // 变量按钮内有子 <span class="hc-pf-var-text">；pointerdown 实际落在子 span 上。
    // 旧 isFormTarget 用 el.matches('button') 看不到子节点 → 误判可拖；修复后用 closest 命中 button。

    it('pointerdown 落在变量按钮的子 span → 不启动拖块（无跟手浮层）+ 仍选中规则', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1')]);
        const edit = useScriptEditStore();
        expect(edit.selectedRuleId).toBeNull();
        const w = mount(BlockCanvas, { attachTo: document.body });
        useNetworkStore().sessionId = '';
        await nextTick();

        // 真实点击落点：按钮内的文本 span（不是 button 本身）。
        const span = w.find('.hc-pf-var-text');
        expect(span.exists()).toBe(true);
        const spanEl = span.element as HTMLElement;
        // 派发到 span：移动一大段后松手——若被误判可拖，会出现跟手浮层 .hc-drag-ghost。
        spanEl.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true, button: 0, clientX: 50, clientY: 50 }));
        await nextTick();
        window.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, clientX: 200, clientY: 200 }));
        await nextTick();

        // 修复后：落在表单目标（button 子节点）→ 不进拖动，无跟手浮层
        expect(document.querySelector('.hc-drag-ghost')).toBeNull();
        expect(edit.dragging).toBe(false);
        // 但仍要选中本规则（selectOwningRule，早于 click，让 picker 一次到位）
        expect(edit.selectedRuleId).toBe('sr-1');

        window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, clientX: 200, clientY: 200 }));
        await nextTick();
        w.unmount();
    });

    it('完整手势从子 span 起手也能一次打开 picker（不被误拖打断）', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1')]);
        const w = mount(BlockCanvas, { attachTo: document.body });
        useNetworkStore().sessionId = '';
        await nextTick();

        expect(document.querySelector('.hc-pf-picker-host')).toBeNull();
        // 起手点在子 span 上，完整 pointerdown→up→click 手势
        fullGesture(w.find('.hc-pf-var-text').element as HTMLElement);
        await nextTick();
        await nextTick();

        expect(document.querySelector('.hc-pf-picker-host')).not.toBeNull();
        expect(document.querySelector('.hc-drag-ghost')).toBeNull();
        w.unmount();
    });
});
