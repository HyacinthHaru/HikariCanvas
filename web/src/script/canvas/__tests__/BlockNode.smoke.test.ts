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
