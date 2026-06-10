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
import { useScriptStore } from '@/stores/scripts';
import { useScriptEditStore } from '@/stores/scriptEdit';

// H：试跑按钮点击会调 getWsClient().sendScriptTest——mock 掉（含 scriptEdit 用到的 send*）。
const sendScriptTest = vi.fn(() => Promise.resolve({ accepted: true, ruleId: 'sr-1' }));
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({
        sendScriptTest,
        sendScriptCreate: vi.fn(() => Promise.resolve()),
        sendScriptUpdate: vi.fn(() => Promise.resolve()),
        sendScriptDelete: vi.fn(() => Promise.resolve()),
        sendScriptEnable: vi.fn(() => Promise.resolve()),
    }),
}));

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

    it('"新建规则"按钮 D1 起启用（未锁定墙）', async () => {
        const wrapper = mount(ScriptEditorOverlay);
        await nextTick();
        const newBtn = wrapper.findAll('button').find(b => b.text().includes('新建规则'));
        expect(newBtn).toBeTruthy();
        // D1 接通后按钮启用（lock 时才禁用，此 smoke 默认未锁定）
        expect((newBtn!.element as HTMLButtonElement).disabled).toBe(false);
    });

    it('D1：选规则后头部出现名称输入 + 列表高亮当前', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([{
            id: 'sr-1', wallId: 'w-x', enabled: true, name: '我的规则',
            trigger: { type: 'wallReady' }, actions: [], blockLayout: '{}',
        }]);
        const wrapper = mount(ScriptEditorOverlay);
        await nextTick();
        // 列表项渲染规则名
        expect(wrapper.text()).toContain('我的规则');
        // 点列表项进入编辑
        const item = wrapper.findAll('.hc-rule-item').find(li => li.text().includes('我的规则'));
        expect(item).toBeTruthy();
        await item!.trigger('click');
        await nextTick();
        // 头部出现名称输入框，值 = 规则名
        const nameInput = wrapper.find('input.hc-rule-name');
        expect(nameInput.exists()).toBe(true);
        expect((nameInput.element as HTMLInputElement).value).toBe('我的规则');
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

describe('ScriptEditorOverlay 试跑 + 校验 smoke（H）', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
        sendScriptTest.mockClear();
    });

    function selectValidRule() {
        const scripts = useScriptStore();
        scripts.initScripts([{
            id: 'sr-1', wallId: 'w-x', enabled: true, name: '合法规则',
            trigger: { type: 'wallReady' }, actions: [{ type: 'log', message: 'hi' }], blockLayout: '{}',
        }]);
    }

    it('合法规则：试跑按钮启用 + 无错误 banner', async () => {
        selectValidRule();
        const wrapper = mount(ScriptEditorOverlay);
        await nextTick();
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        await nextTick();
        // 试跑按钮（title=试跑）存在且未禁用
        const testBtn = wrapper.find('button.hc-rule-test');
        expect(testBtn.exists()).toBe(true);
        expect((testBtn.element as HTMLButtonElement).disabled).toBe(false);
        // 无校验错误 banner
        expect(wrapper.find('.hc-script-errors').exists()).toBe(false);
    });

    it('点试跑 → 调 sendScriptTest(ruleId)', async () => {
        selectValidRule();
        const wrapper = mount(ScriptEditorOverlay);
        await nextTick();
        useScriptEditStore().selectRule('sr-1');
        await nextTick();
        await wrapper.find('button.hc-rule-test').trigger('click');
        expect(sendScriptTest).toHaveBeenCalledWith('sr-1');
    });

    it('非法规则（名称空）：错误 banner 显示 + 试跑按钮禁用', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([{
            id: 'sr-1', wallId: 'w-x', enabled: true, name: '占位',
            trigger: { type: 'wallReady' }, actions: [{ type: 'log', message: 'x' }], blockLayout: '{}',
        }]);
        const wrapper = mount(ScriptEditorOverlay);
        await nextTick();
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.setName('   '); // 改成空名 = 非法
        await nextTick();
        // 错误 banner 出现 + 含校验标题
        const banner = wrapper.find('.hc-script-errors');
        expect(banner.exists()).toBe(true);
        expect(banner.text()).toContain('规则名称不能为空');
        // 试跑按钮禁用
        expect((wrapper.find('button.hc-rule-test').element as HTMLButtonElement).disabled).toBe(true);
        // 点试跑不触发 send
        await wrapper.find('button.hc-rule-test').trigger('click');
        expect(sendScriptTest).not.toHaveBeenCalled();
    });

    it('trace 推送（ruleId 匹配）→ 高亮不崩 + result map 更新到画布', async () => {
        selectValidRule();
        const wrapper = mount(ScriptEditorOverlay);
        await nextTick();
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        await nextTick();
        const scripts = useScriptStore();
        // 推一条匹配当前规则的 trace
        scripts.setLastTrace({
            ruleId: 'sr-1',
            steps: [
                { blockId: 'trigger', kind: 'trigger', result: 'ok' },
                { blockId: 'actions/0', kind: 'action', result: 'ok' },
            ],
        });
        await nextTick();
        // 不崩；图例出现（高亮中）
        expect(wrapper.find('.hc-script-legend').exists()).toBe(true);
    });
});
