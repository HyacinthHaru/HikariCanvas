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

    // closeEditing 在"有未保存改动 + 校验不过"时会拒绝退出（清空 workingCopy 就是丢数据）。
    // 以前不看它的返回值照关不误：提示一闪而过、编辑器关了，用户以为存上了。
    it('有未保存又校验不过的改动时：X 和 Esc 都关不掉，编辑会话原样留着', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([{
            id: 'sr-1', wallId: 'w-x', enabled: true, name: '规则',
            trigger: { type: 'wallReady' }, actions: [{ type: 'log', message: 'ok' }], blockLayout: '{}',
        }]);
        const ui = useUiStore();
        ui.scriptEditorOpen = true;
        const wrapper = mount(ScriptEditorOverlay, { attachTo: document.body });
        await nextTick();
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.setName('');                     // 规则名空 → 校验不过 + 脏
        await nextTick();
        expect(edit.validationErrors.length).toBeGreaterThan(0);

        await wrapper.find('button[title="关闭"]').trigger('click');
        expect(ui.scriptEditorOpen).toBe(true);
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
        await nextTick();
        expect(ui.scriptEditorOpen).toBe(true);
        // 改动还在，用户可以去修完再关
        expect(edit.workingCopy).not.toBeNull();
        wrapper.unmount();
    });

    it('把问题修好后就关得掉了', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([{
            id: 'sr-1', wallId: 'w-x', enabled: true, name: '规则',
            trigger: { type: 'wallReady' }, actions: [{ type: 'log', message: 'ok' }], blockLayout: '{}',
        }]);
        const ui = useUiStore();
        ui.scriptEditorOpen = true;
        const wrapper = mount(ScriptEditorOverlay, { attachTo: document.body });
        await nextTick();
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        edit.setName('');
        await nextTick();
        edit.setName('修好了');
        await nextTick();
        await wrapper.find('button[title="关闭"]').trigger('click');
        expect(ui.scriptEditorOpen).toBe(false);
        wrapper.unmount();
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

    it('合法规则：试跑按钮启用 + 无校验指示 + 无 banner 块（根因 1）', async () => {
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
        // 根因 1：红 banner 块已删除（任何情况下都不存在）
        expect(wrapper.find('.hc-script-errors').exists()).toBe(false);
        // 合法时头部温和指示也不显示
        expect(wrapper.find('.hc-validation-hint').exists()).toBe(false);
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

    it('非法规则（名称空）：头部温和指示显示（不是整行 banner）+ 试跑按钮禁用（根因 1）', async () => {
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
        // 根因 1：不再有整行红 banner（布局位移元凶已删）
        expect(wrapper.find('.hc-script-errors').exists()).toBe(false);
        // 改为头部 inline 温和指示，显示「N 处待完善」
        const hint = wrapper.find('.hc-validation-hint');
        expect(hint.exists()).toBe(true);
        expect(hint.text()).toContain('待完善');
        // 试跑按钮禁用
        expect((wrapper.find('button.hc-rule-test').element as HTMLButtonElement).disabled).toBe(true);
        // 点试跑不触发 send
        await wrapper.find('button.hc-rule-test').trigger('click');
        expect(sendScriptTest).not.toHaveBeenCalled();
    });

    /**
     * 给 viewport 与目标积木塞两个假矩形，让"挪到正中"算得出非零位移。
     * happy-dom 的 getBoundingClientRect 恒返 0，不塞就什么都测不出来。
     */
    function stubRects(vp: HTMLElement, target: HTMLElement): void {
        vp.getBoundingClientRect = () => ({
            left: 0, top: 0, width: 800, height: 600,
            right: 800, bottom: 600, x: 0, y: 0, toJSON: () => ({}),
        }) as DOMRect;
        target.getBoundingClientRect = () => ({
            left: 900, top: 700, width: 100, height: 40,
            right: 1000, bottom: 740, x: 900, y: 700, toJSON: () => ({}),
        }) as DOMRect;
    }

    /** 当前 world 层的 transform 串（pan 有没有动看它）。 */
    function worldTransform(w: ReturnType<typeof mount>): string {
        return w.find('.hc-block-world').element.getAttribute('style') ?? '';
    }

    it('点头部温和指示 → 把第一处错误积木挪到画布正中（改 pan，不滚容器）', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([{
            id: 'sr-1', wallId: 'w-x', enabled: true, name: '规则',
            // 一个非法动作（wait 越界）→ 该错误带 blockId=actions/0 可定位
            trigger: { type: 'wallReady' }, actions: [{ type: 'wait', ms: 1 }], blockLayout: '{}',
        }]);
        const wrapper = mount(ScriptEditorOverlay, { attachTo: document.body });
        await nextTick();
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        await nextTick();
        // 主根因联动：积木渲染自 workingCopy → 待完善积木的 data-block-path DOM 存在，能被 querySelector 找到。
        const blockEl = wrapper.find('[data-block-path="actions/0"]').element as HTMLElement;
        expect(blockEl).toBeTruthy();
        const vp = wrapper.find('.hc-block-viewport').element as HTMLElement;
        stubRects(vp, blockEl);
        // 绝不能去滚 viewport：那会让画布的屏幕↔world 换算整体错位（拖拽落点 / 吸附线全歪）。
        const scrollSpy = vi.spyOn(blockEl, 'scrollIntoView').mockImplementation(() => {});
        const hint = wrapper.find('.hc-validation-hint');
        expect(hint.exists()).toBe(true);
        await hint.trigger('click');
        await nextTick();
        // 积木中心 (950,720) → viewport 中心 (400,300)：pan 位移 (-550,-420)
        expect(worldTransform(wrapper)).toContain('translate(-550px, -420px)');
        expect(scrollSpy).not.toHaveBeenCalled();
        expect(vp.scrollTop).toBe(0);
        expect(vp.scrollLeft).toBe(0);
        wrapper.unmount();
    });

    it('trigger 字段错误：点指示把帽子（data-block-path="trigger"）挪到正中', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([{
            id: 'sr-1', wallId: 'w-x', enabled: true, name: '规则',
            // variableChange 触发器 fullName 空 → 错误 blockId='trigger'（帽子）
            trigger: { type: 'variableChange', fullName: '' },
            actions: [{ type: 'log', message: 'ok' }], blockLayout: '{}',
        }]);
        const wrapper = mount(ScriptEditorOverlay, { attachTo: document.body });
        await nextTick();
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        await nextTick();
        const hatEl = wrapper.find('[data-block-path="trigger"]').element as HTMLElement;
        expect(hatEl).toBeTruthy();
        stubRects(wrapper.find('.hc-block-viewport').element as HTMLElement, hatEl);
        const scrollSpy = vi.spyOn(hatEl, 'scrollIntoView').mockImplementation(() => {});
        await wrapper.find('.hc-validation-hint').trigger('click');
        await nextTick();
        expect(worldTransform(wrapper)).toContain('translate(-550px, -420px)');
        expect(scrollSpy).not.toHaveBeenCalled();
        wrapper.unmount();
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

describe('ScriptEditorOverlay 左右分栏 + 预览框（0.7.1-P3-T2）', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
    });

    it('未折叠：BlockCanvas + 分隔条 + PreviewPane 同时存在', async () => {
        const ui = useUiStore();
        ui.scriptPreviewCollapsed = false;
        const wrapper = mount(ScriptEditorOverlay);
        await nextTick();
        // 左积木画布 host 仍在
        expect(wrapper.find('.hc-script-canvas-host').exists()).toBe(true);
        // 分隔条（拖宽）存在
        expect(wrapper.find('.hc-script-splitter').exists()).toBe(true);
        // 右预览框存在（PreviewPane 根 class）
        expect(wrapper.find('.hc-preview-pane').exists()).toBe(true);
        // 预览标题渲染（证明 t.value.script.preview 解包）
        expect(wrapper.text()).toContain('预览');
    });

    it('折叠态：只剩 BlockCanvas，分隔条 / 预览框隐藏 + 展开按钮出现', async () => {
        const ui = useUiStore();
        ui.scriptPreviewCollapsed = true;
        const wrapper = mount(ScriptEditorOverlay);
        await nextTick();
        // 折叠时分隔条不渲染
        expect(wrapper.find('.hc-script-splitter').exists()).toBe(false);
        // PreviewPane 不渲染（折叠走 v-if 卸载，避免后台重绘）
        expect(wrapper.find('.hc-preview-pane').exists()).toBe(false);
        // 展开按钮存在（title=展开预览）
        const expandBtn = wrapper.find('button[title="展开预览"]');
        expect(expandBtn.exists()).toBe(true);
    });

    it('点折叠按钮 → 调 ui.toggleScriptPreview（collapsed 翻转）', async () => {
        const ui = useUiStore();
        ui.scriptPreviewCollapsed = false;
        const spy = vi.spyOn(ui, 'toggleScriptPreview');
        const wrapper = mount(ScriptEditorOverlay);
        await nextTick();
        const collapseBtn = wrapper.find('button[title="折叠预览"]');
        expect(collapseBtn.exists()).toBe(true);
        await collapseBtn.trigger('click');
        expect(spy).toHaveBeenCalled();
        expect(ui.scriptPreviewCollapsed).toBe(true);
    });
});

/**
 * 0.7.1-P3 实测 Bug 2：预览框分隔条拖过一次后 hover 持续触发（越拖越大）。
 *
 * <p>根因：分隔条 pointerup 绑在分隔条元素上、靠 setPointerCapture retarget。鼠标拖出分隔条再
 * 松手时 pointerup 不在分隔条触发 → splitterDragging 卡 true；之后 hover 分隔条 → pointermove
 * 触发 → 继续改宽。修复照 useBlockDrag 范式：拖动期间挂 window 监听，松手在 window 一定收到、
 * 一定清 dragging + 摘监听；模板分隔条只留 @pointerdown。</p>
 *
 * <p>可观测信号：{@code splitterDragging} 经 {@code hc-script-splitter-active} class 反映。
 * 断言"按下 → active；window pointerup → active 清除"，并验证 window pointerup 后 hover
 * （pointermove 落分隔条元素）不再改宽（越拖越大已治）。</p>
 */
describe('ScriptEditorOverlay 分隔条拖动 window 监听（Bug 2：松手必清 dragging）', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
    });

    /** 造一个 pointer 事件（happy-dom 默认不带 client/pointerId）。 */
    function ptr(type: string, clientX = 0, clientY = 0): PointerEvent {
        const ev = new Event(type, { bubbles: true }) as unknown as PointerEvent;
        Object.defineProperty(ev, 'clientX', { value: clientX });
        Object.defineProperty(ev, 'clientY', { value: clientY });
        Object.defineProperty(ev, 'pointerId', { value: 1 });
        return ev;
    }

    it('分隔条 pointerdown → 进入拖动态（hc-script-splitter-active）', async () => {
        const ui = useUiStore();
        ui.scriptPreviewCollapsed = false;
        const wrapper = mount(ScriptEditorOverlay, { attachTo: document.body });
        await nextTick();
        const splitter = wrapper.find('.hc-script-splitter');
        expect(splitter.exists()).toBe(true);
        await splitter.trigger('pointerdown');
        await nextTick();
        expect(wrapper.find('.hc-script-splitter').classes()).toContain('hc-script-splitter-active');
        wrapper.unmount();
    });

    it('window pointerup（指针已拖出分隔条）→ 退出拖动态（active 清除）', async () => {
        const ui = useUiStore();
        ui.scriptPreviewCollapsed = false;
        const wrapper = mount(ScriptEditorOverlay, { attachTo: document.body });
        await nextTick();
        const splitter = wrapper.find('.hc-script-splitter');
        await splitter.trigger('pointerdown');
        await nextTick();
        // 模拟"鼠标拖出分隔条再松手"——pointerup 落在 window，不在分隔条元素上。
        window.dispatchEvent(ptr('pointerup', 500, 300));
        await nextTick();
        // 修复后：window 监听一定收到 → splitterDragging 清 false → active class 消失。
        expect(wrapper.find('.hc-script-splitter').classes()).not.toContain('hc-script-splitter-active');
        wrapper.unmount();
    });

    it('window pointerup 后再 hover 分隔条（pointermove 落元素）不改宽（越拖越大已治）', async () => {
        const ui = useUiStore();
        ui.scriptPreviewCollapsed = false;
        const wrapper = mount(ScriptEditorOverlay, { attachTo: document.body });
        await nextTick();
        const splitter = wrapper.find('.hc-script-splitter');
        await splitter.trigger('pointerdown');
        // 松手在 window（指针已离开分隔条）。
        window.dispatchEvent(ptr('pointerup', 500, 300));
        await nextTick();
        const widthAfterRelease = ui.scriptPreviewWidthPct;
        // 之后纯 hover：pointermove 落在分隔条元素上——不应再改宽（dragging 已清 + 元素无 @pointermove）。
        await splitter.trigger('pointermove', { clientX: 50, clientY: 50 });
        await nextTick();
        expect(ui.scriptPreviewWidthPct).toBe(widthAfterRelease);
        wrapper.unmount();
    });

    it('卸载组件兜底：拖动中卸载后 window pointermove 不再改宽（监听已摘）', async () => {
        const ui = useUiStore();
        ui.scriptPreviewCollapsed = false;
        const wrapper = mount(ScriptEditorOverlay, { attachTo: document.body });
        await nextTick();
        const splitter = wrapper.find('.hc-script-splitter');
        await splitter.trigger('pointerdown');
        await nextTick();
        const widthBefore = ui.scriptPreviewWidthPct;
        wrapper.unmount();
        // 卸载后任何 window pointermove 都不应再驱动改宽（监听在卸载时摘掉）。
        window.dispatchEvent(ptr('pointermove', 9, 9));
        expect(ui.scriptPreviewWidthPct).toBe(widthBefore);
    });
});
