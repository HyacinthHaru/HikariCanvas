// @vitest-environment happy-dom
/**
 * 0.7.0-P4-C → P5-F：BlockNode 递归渲染 smoke。
 *
 * <p>核心验证：① 各类 action 渲染不崩 + 标题出现 + 参数槽换成真控件（P5-F：BlockParamInput）；
 * ② if 嵌套递归渲染；③ {@code data-block-path} <b>与后端 trace blockId 同构</b>（嵌套子块 path
 * 精确）；④ 改字段触发 {@code edit.updateActionField}（P5-F 接入）。
 * happy-dom + pinia + 锁中文 locale。</p>
 *
 * <p>P5-F 起 BlockNode 用 project / scriptEdit store，故 mount 前 setActivePinia。参数槽不再是
 * 「字段名: 原始值」纯文本占位，而是 input / select / 变量按钮——断言改为查控件与值。</p>
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import BlockNode from '../BlockNode.vue';
import { __resetCommandTemplatesCache } from '../../params/useCommandTemplates';
import { useUiStore } from '@/stores/ui';
import { useScriptEditStore } from '@/stores/scriptEdit';
import type { ScriptAction } from '@/types/protocol';

function mountNode(action: ScriptAction, path = 'actions/0') {
    return mount(BlockNode, { props: { action, path } });
}

describe('BlockNode 渲染 smoke', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
        __resetCommandTemplatesCache();
    });

    it('setVariable 渲染：标题 + 变量按钮（当前 fullName）+ value 文本框', async () => {
        const w = mountNode({ type: 'setVariable', fullName: 'user/score', value: '42' });
        await nextTick();
        expect(w.text()).toContain('设置变量');
        // 变量字段 → 变量按钮显示当前 fullName
        expect(w.text()).toContain('user/score');
        // value 字段 → text input，值 = 42
        const textInputs = w.findAll('input[type="text"]');
        const has42 = textInputs.some((i) => (i.element as HTMLInputElement).value === '42');
        expect(has42).toBe(true);
    });

    it('playTimeline 渲染：op select（值 play）+ seekMs number input', async () => {
        const w = mountNode({ type: 'playTimeline', timelineId: '', op: 'play' });
        await nextTick();
        expect(w.text()).toContain('播放时间轴');
        // op 字段 → select，值 play
        const selects = w.findAll('select');
        const opSelect = selects.find((s) => (s.element as HTMLSelectElement).value === 'play');
        expect(opSelect).toBeTruthy();
        // seekMs 字段 → number input 存在
        expect(w.find('input[type="number"]').exists()).toBe(true);
    });

    it('playSound 渲染：声音 input + 音量/音调 number + 范围 select', async () => {
        const w = mountNode({
            type: 'playSound',
            soundId: 'block.note_block.harp',
            volume: 1,
            pitch: 1,
            scope: 'near',
        });
        await nextTick();
        expect(w.text()).toContain('播放声音');
        // 声音 input（datalist）值
        const soundInput = w.find('input[list="hc-sound-suggest"]');
        expect(soundInput.exists()).toBe(true);
        expect((soundInput.element as HTMLInputElement).value).toBe('block.note_block.harp');
        // 两个 number（音量 / 音调）
        expect(w.findAll('input[type="number"]').length).toBe(2);
    });

    it('runCommand 渲染：复合 command 控件不崩（templateId 锚 + 区域存在）', async () => {
        // command 字段挂载会 fetch 模板——stub 返空（无 session 也走空路径）
        vi.stubGlobal('fetch', vi.fn(async () => ({
            ok: true, json: async () => ({ templates: [] }),
        } as unknown as Response)));
        const w = mountNode({
            type: 'runCommand',
            templateId: 'tpl-x',
            params: { who: 'red', n: '1' },
        });
        await flushPromises();
        expect(w.text()).toContain('执行命令');
        // 复合控件容器存在
        expect(w.find('.hc-block-command').exists()).toBe(true);
        vi.unstubAllGlobals();
    });

    it('未知 kind → 兜底未知积木文案不崩', async () => {
        const w = mountNode({ type: 'totallyUnknown' } as unknown as ScriptAction);
        await nextTick();
        expect(w.text()).toContain('未知积木');
        expect(w.text()).toContain('totallyUnknown');
    });

    it('data-block-path = props.path（顶层）', async () => {
        const w = mountNode({ type: 'log', message: 'hi' }, 'actions/3');
        await nextTick();
        expect(w.find('[data-block-path="actions/3"]').exists()).toBe(true);
    });
});

describe('BlockNode P5-F：字段改值回写 updateActionField', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
        __resetCommandTemplatesCache();
    });

    it('改 text 字段 → 调 edit.updateActionField(path, {字段: 新值})', async () => {
        const edit = useScriptEditStore();
        const spy = vi.spyOn(edit, 'updateActionField');
        const w = mountNode({ type: 'log', message: 'old' }, 'actions/2');
        await nextTick();
        await w.find('input[type="text"]').setValue('new');
        expect(spy).toHaveBeenCalledWith('actions/2', { message: 'new' });
    });

    it('改 number 字段 → emit number（非字符串）回写', async () => {
        const edit = useScriptEditStore();
        const spy = vi.spyOn(edit, 'updateActionField');
        const w = mountNode({ type: 'wait', ms: 100 }, 'actions/0');
        await nextTick();
        await w.find('input[type="number"]').setValue('250');
        expect(spy).toHaveBeenCalledWith('actions/0', { ms: 250 });
        // 值类型断言：第二参 ms 是 number
        const arg = spy.mock.calls[spy.mock.calls.length - 1][1] as { ms: unknown };
        expect(typeof arg.ms).toBe('number');
    });
});

describe('BlockNode if 递归 + path 同构', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
        __resetCommandTemplatesCache();
    });

    it('if 嵌套：then 子块 path = actions/0/then/0（与后端 trace blockId 同构）', async () => {
        const ifAction: ScriptAction = {
            type: 'if',
            condition: 'var("user/score") > 5',
            then: [{ type: 'log', message: 'in-then' }],
            else: [{ type: 'wait', ms: 100 }],
        };
        const w = mountNode(ifAction, 'actions/0');
        await nextTick();

        // G：condition 字段接 ConditionBuilder（可视模式解析 `var(...) > 5` 成行），
        // 不再是原始串占位 —— 验证构建器根元素已挂载而非裸串。
        expect(w.find('.hc-cond').exists()).toBe(true);
        // then 子块精确 path
        expect(w.find('[data-block-path="actions/0/then/0"]').exists()).toBe(true);
        // else 子块精确 path
        expect(w.find('[data-block-path="actions/0/else/0"]').exists()).toBe(true);
    });

    it('深层嵌套 if-in-if：path 逐层拼接 actions/0/then/0/then/0', async () => {
        const inner: ScriptAction = {
            type: 'if',
            condition: 'true',
            then: [{ type: 'log', message: 'deep' }],
            else: [],
        };
        const outer: ScriptAction = {
            type: 'if',
            condition: 'true',
            then: [inner],
            else: [],
        };
        const w = mountNode(outer, 'actions/0');
        await nextTick();
        // 内层 if 自身 path
        expect(w.find('[data-block-path="actions/0/then/0"]').exists()).toBe(true);
        // 内层 if 的 then 第 0 块
        expect(w.find('[data-block-path="actions/0/then/0/then/0"]').exists()).toBe(true);
    });

    it('if 空分支：显示空槽占位提示', async () => {
        const ifAction: ScriptAction = {
            type: 'if',
            condition: 'true',
            then: [],
            else: [],
        };
        const w = mountNode(ifAction, 'actions/1');
        await nextTick();
        // 两个空槽 → 至少出现占位文案
        expect(w.text()).toContain('把积木拖到这里');
    });
});

describe('BlockNode 0.7.1-P2 repeat C 形渲染', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
        __resetCommandTemplatesCache();
    });

    it('repeat 渲染：标题「重复」+ count number input（值 5）+ body 空槽占位 data-slot-path=actions/0/body', async () => {
        const w = mountNode({ type: 'repeat', count: 5, body: [] }, 'actions/0');
        await nextTick();
        expect(w.text()).toContain('重复');
        // count 字段 → number input，值 5
        const nums = w.findAll('input[type="number"]').map((i) => (i.element as HTMLInputElement).value);
        expect(nums).toContain('5');
        // 空 body 槽：data-slot-path = actions/0/body（collectSlots index=0 落点）
        expect(w.find('[data-slot-path="actions/0/body"]').exists()).toBe(true);
        // 空槽占位文案
        expect(w.text()).toContain('把积木拖到这里');
    });

    it('repeat body 子块 path = actions/0/body/0（与后端 ScriptRunner 展开 blockId 同构）', async () => {
        const w = mountNode(
            { type: 'repeat', count: 3, body: [{ type: 'log', message: 'in-body' }] },
            'actions/0',
        );
        await nextTick();
        // body 子块精确 path（不带 round——前后端同构）
        expect(w.find('[data-block-path="actions/0/body/0"]').exists()).toBe(true);
        // 有 body 子块时不再渲染空槽占位
        expect(w.find('[data-slot-path="actions/0/body"]').exists()).toBe(false);
    });

    it('改 count number → updateActionField(path, { count: 新值 number })', async () => {
        const edit = useScriptEditStore();
        const spy = vi.spyOn(edit, 'updateActionField');
        const w = mountNode({ type: 'repeat', count: 3, body: [] }, 'actions/2');
        await nextTick();
        await w.find('input[type="number"]').setValue('10');
        expect(spy).toHaveBeenCalledWith('actions/2', { count: 10 });
        const arg = spy.mock.calls[spy.mock.calls.length - 1][1] as { count: unknown };
        expect(typeof arg.count).toBe('number');
    });

    it('repeat 嵌套 if：body 内 if 的 then 子块 path = actions/0/body/0/then/0', async () => {
        const w = mountNode(
            {
                type: 'repeat',
                count: 2,
                body: [{ type: 'if', condition: 'true', then: [{ type: 'log', message: 'x' }], else: [] }],
            },
            'actions/0',
        );
        await nextTick();
        expect(w.find('[data-block-path="actions/0/body/0"]').exists()).toBe(true);
        expect(w.find('[data-block-path="actions/0/body/0/then/0"]').exists()).toBe(true);
    });
});

describe('BlockNode 待完善角标（次要问题 1：扩展到 variable / condition / sound）', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
        __resetCommandTemplatesCache();
    });

    /** 角标存在性 + hover 文案命中某字段名。 */
    function badge(w: ReturnType<typeof mountNode>) {
        return w.find('.hc-block-need-select');
    }

    it('setVariable：fullName 空 → 显示待完善角标', async () => {
        const w = mountNode({ type: 'setVariable', fullName: '', value: '' });
        await nextTick();
        expect(badge(w).exists()).toBe(true);
        // fullName 字段友好名 = "变量"
        expect(badge(w).attributes('title')).toContain('变量');
    });

    it('setVariable：fullName 有值 → 不显示角标', async () => {
        const w = mountNode({ type: 'setVariable', fullName: 'user/score', value: '' });
        await nextTick();
        expect(badge(w).exists()).toBe(false);
    });

    it('incrementVariable：fullName 空 → 显示角标', async () => {
        const w = mountNode({ type: 'incrementVariable', fullName: '   ', delta: 1 });
        await nextTick();
        expect(badge(w).exists()).toBe(true);
    });

    it('playSound：soundId 空 → 显示角标（命中"声音"）', async () => {
        const w = mountNode({ type: 'playSound', soundId: '', volume: 1, pitch: 1, scope: 'near' });
        await nextTick();
        expect(badge(w).exists()).toBe(true);
        expect(badge(w).attributes('title')).toContain('声音');
    });

    it('if：condition 空 → 显示角标（命中"条件"）', async () => {
        const w = mountNode({ type: 'if', condition: '', then: [], else: [] });
        await nextTick();
        expect(badge(w).exists()).toBe(true);
        expect(badge(w).attributes('title')).toContain('条件');
    });

    it('if：condition 有值 → 不显示角标', async () => {
        const w = mountNode({ type: 'if', condition: 'var("user/score") > 0', then: [], else: [] });
        await nextTick();
        expect(badge(w).exists()).toBe(false);
    });

    it('setElementProperty：elementId 空 → 显示角标（原 element 类仍覆盖）', async () => {
        const w = mountNode({ type: 'setElementProperty', elementId: '', property: 'x', value: 'v' });
        await nextTick();
        expect(badge(w).exists()).toBe(true);
    });

    it('log：message 空也不报"待完善"（log 无必填引用字段，空 message 合法）', async () => {
        const w = mountNode({ type: 'log', message: '' });
        await nextTick();
        expect(badge(w).exists()).toBe(false);
    });
});

describe('BlockNode 0.7.1 友好元素皮肤渲染（setElementProperties）', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
        __resetCommandTemplatesCache();
    });

    /** 友好积木：moveTo（patch x/y）。 */
    function friendlyMoveTo(elementId = 'e-1', x = '128', y = '64'): ScriptAction {
        return { type: 'setElementProperties', kind: 'moveTo', elementId, patch: { x, y } } as ScriptAction;
    }

    it('moveTo 渲染：标题「移到」+ 元素下拉 + 两个 number 值 128/64', async () => {
        const project = (await import('@/stores/project')).useProjectStore();
        // 给画布一个元素让 element 下拉非空（否则只渲染 noElements 提示）。
        project.setSnapshot({
            canvas: { width: 128, height: 128, background: '#000' },
            layers: [{ id: 'l1', name: 'L', visible: true, locked: false, opacity: 1, elements: [
                { id: 'e-1', type: 'rect', x: 0, y: 0, w: 10, h: 10, fill: '#fff' },
            ] }],
            activeLayerId: 'l1', gridSize: 8, guides: [], timelines: [], activeTimelineId: null,
        } as never);
        const w = mountNode(friendlyMoveTo(), 'actions/0');
        await nextTick();
        expect(w.text()).toContain('移到');
        // 两个 number input，值 128 / 64
        const nums = w.findAll('input[type="number"]').map((i) => (i.element as HTMLInputElement).value);
        expect(nums).toContain('128');
        expect(nums).toContain('64');
        // 元素下拉存在（element 字段 → select，选中 e-1）
        const sel = w.find('select');
        expect(sel.exists()).toBe(true);
        expect((sel.element as HTMLSelectElement).value).toBe('e-1');
        // data-block-path 不变（1 积木 1 path）
        expect(w.find('[data-block-path="actions/0"]').exists()).toBe(true);
    });

    it('改 x 字段 → updateActionField(path, { patch: 整体替换 })', async () => {
        const edit = useScriptEditStore();
        const spy = vi.spyOn(edit, 'updateActionField');
        const w = mountNode(friendlyMoveTo('e-1', '128', '64'), 'actions/2');
        await nextTick();
        // 找到值为 128 的 number input（x）改成 200
        const xInput = w.findAll('input[type="number"]').find(
            (i) => (i.element as HTMLInputElement).value === '128',
        )!;
        await xInput.setValue('200');
        // patch 整体替换：{ x:'200', y:'64' }（y 保留）
        expect(spy).toHaveBeenCalledWith('actions/2', { patch: { x: '200', y: '64' } });
    });

    it('show（fields 空）：只渲染标题「显示」+ 元素下拉，无额外参数输入', async () => {
        const w = mountNode(
            { type: 'setElementProperties', kind: 'show', elementId: 'e-1', patch: { opacity: '1' } } as ScriptAction,
            'actions/0',
        );
        await nextTick();
        expect(w.text()).toContain('显示');
        // show 无可编辑 patch 字段 → 没有 number / text 输入（仅元素 select）
        expect(w.findAll('input[type="number"]').length).toBe(0);
        expect(w.findAll('input[type="text"]').length).toBe(0);
    });

    it('未知 kind → fallback 通用名不崩（仍渲染元素下拉区，data-block-path 不变）', async () => {
        const w = mountNode(
            { type: 'setElementProperties', kind: 'totallyUnknownKind', elementId: 'e-1', patch: {} } as ScriptAction,
            'actions/1',
        );
        await nextTick();
        // 不崩：渲染出块根
        expect(w.find('[data-block-path="actions/1"]').exists()).toBe(true);
        // 通用名 fallback（设置元素属性）——不显示原始 i18n key 字符串
        expect(w.text()).not.toContain('script.blocks');
        expect(w.text()).toContain('设置元素属性');
    });

    it('elementId 空 → 显示待完善角标（命中"元素"）', async () => {
        const w = mountNode(friendlyMoveTo('', '0', '0'), 'actions/0');
        await nextTick();
        const badge = w.find('.hc-block-need-select');
        expect(badge.exists()).toBe(true);
        expect(badge.attributes('title')).toContain('元素');
    });

    it('elementId 有值 → 不显示待完善角标', async () => {
        const w = mountNode(friendlyMoveTo('e-1', '0', '0'), 'actions/0');
        await nextTick();
        expect(w.find('.hc-block-need-select').exists()).toBe(false);
    });

    it('0.7.1-P3 波2：friendly 元素下拉 focusin → setActiveElementBinding(path)', async () => {
        const project = (await import('@/stores/project')).useProjectStore();
        project.setSnapshot({
            canvas: { width: 128, height: 128, background: '#000' },
            layers: [{ id: 'l1', name: 'L', visible: true, locked: false, opacity: 1, elements: [
                { id: 'e-1', type: 'rect', x: 0, y: 0, w: 10, h: 10, fill: '#fff' },
            ] }],
            activeLayerId: 'l1', gridSize: 8, guides: [], timelines: [], activeTimelineId: null,
        } as never);
        const edit = useScriptEditStore();
        const spy = vi.spyOn(edit, 'setActiveElementBinding');
        const w = mountNode(friendlyMoveTo('e-1', '0', '0'), 'actions/3');
        await nextTick();
        // 元素下拉 focusin（focus 冒泡）→ 记本积木 path
        const sel = w.find('select');
        expect(sel.exists()).toBe(true);
        await sel.trigger('focusin');
        expect(spy).toHaveBeenCalledWith('actions/3');
    });
});

describe('BlockNode 0.7.1-P3 波2：常规块元素字段 focusin 绑定', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
        __resetCommandTemplatesCache();
    });

    async function seedOneElement() {
        const project = (await import('@/stores/project')).useProjectStore();
        project.setSnapshot({
            canvas: { width: 128, height: 128, background: '#000' },
            layers: [{ id: 'l1', name: 'L', visible: true, locked: false, opacity: 1, elements: [
                { id: 'e-1', type: 'rect', x: 0, y: 0, w: 10, h: 10, fill: '#fff' },
            ] }],
            activeLayerId: 'l1', gridSize: 8, guides: [], timelines: [], activeTimelineId: null,
        } as never);
    }

    it('setElementProperty（万能积木）元素下拉 focusin → setActiveElementBinding(path)', async () => {
        await seedOneElement();
        const edit = useScriptEditStore();
        const spy = vi.spyOn(edit, 'setActiveElementBinding');
        const w = mountNode({ type: 'setElementProperty', elementId: 'e-1', property: 'x', value: '0' }, 'actions/5');
        await nextTick();
        const sel = w.find('select');
        expect(sel.exists()).toBe(true);
        await sel.trigger('focusin');
        expect(spy).toHaveBeenCalledWith('actions/5');
    });

    it('非元素字段（setVariable 的 value 文本框）focusin 不记绑定', async () => {
        await seedOneElement();
        const edit = useScriptEditStore();
        const spy = vi.spyOn(edit, 'setActiveElementBinding');
        const w = mountNode({ type: 'setVariable', fullName: 'user/score', value: '0' }, 'actions/0');
        await nextTick();
        const textInput = w.find('input[type="text"]');
        expect(textInput.exists()).toBe(true);
        await textInput.trigger('focusin');
        expect(spy).not.toHaveBeenCalled();
    });
});
