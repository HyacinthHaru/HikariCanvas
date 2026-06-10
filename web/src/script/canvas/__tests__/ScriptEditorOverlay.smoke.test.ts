// @vitest-environment happy-dom
/**
 * 0.7.0-P4-B：ScriptEditorOverlay 渲染 smoke（防 ComputedRef 解包崩溃 + 关闭路径）。
 *
 * <p>真实 mount overlay（含 BlockCanvas 子组件）：验证挂载不崩、标题渲染（证明 t.value.script
 * 正确解包，漏 `.value` 则 `t.script.editorTitle` undefined → 崩）、X / Esc 关闭调
 * ui.closeScriptEditor、ui.scriptEditorOpen toggle 状态机。照 TimelineDock.smoke.test.ts 范式：
 * happy-dom + pinia + 锁 locale 断言中文文案。</p>
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import ScriptEditorOverlay from '../ScriptEditorOverlay.vue';
import { useUiStore } from '@/stores/ui';

describe('ScriptEditorOverlay 渲染 smoke', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';   // 锁 locale，断言中文文案（验证 t.value.script 解包）
    });

    it('挂载不崩 + 标题渲染（t.value.script.editorTitle 解包）', async () => {
        const wrapper = mount(ScriptEditorOverlay);
        await nextTick();
        expect(wrapper.text()).toContain('积木脚本');
        // 空画布提示文案出现 → t.value.script.empty 正确解包
        expect(wrapper.text()).toContain('还没有脚本规则');
    });

    it('"新建规则"按钮 B 阶段占位禁用', async () => {
        const wrapper = mount(ScriptEditorOverlay);
        await nextTick();
        const newBtn = wrapper.findAll('button').find(b => b.text().includes('新建规则'));
        expect(newBtn).toBeTruthy();
        expect((newBtn!.element as HTMLButtonElement).disabled).toBe(true);
    });

    it('点 X 调 ui.closeScriptEditor（scriptEditorOpen → false）', async () => {
        const ui = useUiStore();
        ui.scriptEditorOpen = true;
        const spy = vi.spyOn(ui, 'closeScriptEditor');
        const wrapper = mount(ScriptEditorOverlay);
        await nextTick();
        const closeBtn = wrapper.find('button[title="关闭"]');
        expect(closeBtn.exists()).toBe(true);
        await closeBtn.trigger('click');
        expect(spy).toHaveBeenCalled();
        expect(ui.scriptEditorOpen).toBe(false);
    });

    it('按 Esc 调 ui.closeScriptEditor', async () => {
        const ui = useUiStore();
        ui.scriptEditorOpen = true;
        const spy = vi.spyOn(ui, 'closeScriptEditor');
        mount(ScriptEditorOverlay, { attachTo: document.body });
        await nextTick();
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
        await nextTick();
        expect(spy).toHaveBeenCalled();
        expect(ui.scriptEditorOpen).toBe(false);
    });

    it('zoom% 显示 + reset 按钮存在', async () => {
        const wrapper = mount(ScriptEditorOverlay);
        await nextTick();
        expect(wrapper.text()).toContain('100%');
        const resetBtn = wrapper.find('button[title="回到原始视图"]');
        expect(resetBtn.exists()).toBe(true);
    });

    it('ui store toggle / close 开关状态机', () => {
        const ui = useUiStore();
        expect(ui.scriptEditorOpen).toBe(false);
        ui.toggleScriptEditor();
        expect(ui.scriptEditorOpen).toBe(true);
        ui.toggleScriptEditor();
        expect(ui.scriptEditorOpen).toBe(false);
        ui.scriptEditorOpen = true;
        ui.closeScriptEditor();
        expect(ui.scriptEditorOpen).toBe(false);
    });
});
