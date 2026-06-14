// @vitest-environment happy-dom
/**
 * 0.7.0-P5-F：BlockParamInput 各类型渲染 smoke + emit 值正确性。
 *
 * <p>核心验证：① 每种 FieldType 渲染对应控件不崩；② 改值 emit 的<b>值类型与 wire 一致</b>
 * （number → number / 其余 → string / command → 复合对象）；③ number 范围钳位；④ element /
 * timeline 选项来自 project store；⑤ variable picker 按钮 click 开关（memory 约束）；
 * ⑥ command 复合字段：选模板 → params 子输入渲染 → 改值 emit 正确。</p>
 *
 * <p>happy-dom + pinia + 锁中文 locale（照 BlockNode.smoke 范式）。command 类型 stub 全局
 * fetch 返模板。</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import BlockParamInput from '../BlockParamInput.vue';
import { __resetCommandTemplatesCache } from '../useCommandTemplates';
import { useUiStore } from '@/stores/ui';
import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import type { FieldDef } from '../../model/blockDefs';
import type { ProjectState, Element, Layer } from '@/types/protocol';

function field(over: Partial<FieldDef> & Pick<FieldDef, 'type'>): FieldDef {
    return { name: over.name ?? 'f', labelKey: over.labelKey ?? 'script.fields.value', ...over };
}

function mountInput(f: FieldDef, value: unknown, actionKind = 'setVariable') {
    return mount(BlockParamInput, {
        props: { field: f, value, actionKind },
        // VariablePicker 在 setup 里调 getWsClient()（测试无 WsClient 单例），且本任务只验证
        // 「点按钮 → 浮层 host 出现」交互，不验证 picker 内部——故 stub 掉它。
        global: { stubs: { VariablePicker: { template: '<div class="hc-stub-picker" />' } } },
    });
}

/** 最近一次 update emit 的值。 */
function lastUpdate(w: ReturnType<typeof mountInput>): unknown {
    const ev = w.emitted('update');
    if (!ev || ev.length === 0) return undefined;
    return (ev[ev.length - 1] as unknown[])[0];
}

function baseEl(id: string, type: Element['type']): Element {
    return {
        id, type, x: 0, y: 0, w: 10, h: 10, rotation: 0, locked: false, visible: true,
    } as Element;
}

function layer(elements: Element[]): Layer {
    return {
        id: 'l-0', name: 'L', visible: true, locked: false, opacity: 1,
        blendMode: 'normal', colorTag: null, elements,
    };
}

function stateWith(over: Partial<ProjectState> = {}): ProjectState {
    return {
        version: 0,
        canvas: { widthMaps: 1, heightMaps: 1, background: '#FFFFFF' },
        layers: [layer([])],
        activeLayerId: 'l-0',
        history: { undoDepth: 0, redoDepth: 0 },
        ...over,
    };
}

beforeEach(() => {
    setActivePinia(createPinia());
    useUiStore().locale = 'zh';
    __resetCommandTemplatesCache();
});

afterEach(() => {
    vi.unstubAllGlobals();
});

describe('BlockParamInput — number', () => {
    it('渲染 number input + 改值 emit number（非字符串）', async () => {
        const f = field({ type: 'number', name: 'ms', min: 50, max: 5000, step: 50 });
        const w = mountInput(f, 500);
        const input = w.find('input[type="number"]');
        expect(input.exists()).toBe(true);
        expect((input.element as HTMLInputElement).value).toBe('500');
        await input.setValue('1234');
        expect(lastUpdate(w)).toBe(1234);
        expect(typeof lastUpdate(w)).toBe('number');
    });

    it('越上界 → 钳到 max', async () => {
        const f = field({ type: 'number', name: 'ms', min: 50, max: 5000 });
        const w = mountInput(f, 500);
        await w.find('input').setValue('999999');
        expect(lastUpdate(w)).toBe(5000);
    });

    it('越下界 → 钳到 min', async () => {
        const f = field({ type: 'number', name: 'ms', min: 50, max: 5000 });
        const w = mountInput(f, 500);
        await w.find('input').setValue('1');
        expect(lastUpdate(w)).toBe(50);
    });

    it('空输入 → 不 emit（保留旧值）', async () => {
        const f = field({ type: 'number', name: 'ms', min: 50, max: 5000 });
        const w = mountInput(f, 500);
        await w.find('input').setValue('');
        expect(w.emitted('update')).toBeUndefined();
    });
});

describe('BlockParamInput — text / select / scope / op', () => {
    it('text：改值 emit string', async () => {
        const w = mountInput(field({ type: 'text', name: 'value' }), 'hi');
        await w.find('input[type="text"]').setValue('world');
        expect(lastUpdate(w)).toBe('world');
    });

    it('select：选项来自 field.options，change emit string', async () => {
        const f = field({
            type: 'select',
            name: 'property',
            options: [
                { value: 'x', labelKey: 'script.fieldOptions.propX' },
                { value: 'y', labelKey: 'script.fieldOptions.propY' },
            ],
        });
        const w = mountInput(f, 'x');
        const opts = w.findAll('option');
        expect(opts.length).toBe(2);
        await w.find('select').setValue('y');
        expect(lastUpdate(w)).toBe('y');
    });

    it('scope toggle（near/all）emit string', async () => {
        const f = field({
            type: 'scope',
            name: 'scope',
            options: [
                { value: 'near', labelKey: 'script.fieldOptions.scopeNear' },
                { value: 'all', labelKey: 'script.fieldOptions.scopeAll' },
            ],
        });
        const w = mountInput(f, 'near', 'playSound');
        await w.find('select').setValue('all');
        expect(lastUpdate(w)).toBe('all');
    });
});

describe('BlockParamInput — variable', () => {
    it('显示当前 fullName；空值显占位文案', () => {
        const f = field({ type: 'variable', name: 'fullName' });
        const wEmpty = mountInput(f, '');
        expect(wEmpty.text()).toContain('选变量');
        const wVal = mountInput(f, 'user/score');
        expect(wVal.text()).toContain('user/score');
    });

    it('点按钮打开 picker（memory：button click 触发，非 select-change）', async () => {
        const f = field({ type: 'variable', name: 'fullName' });
        const w = mountInput(f, '');
        // 初始无 picker
        expect(document.querySelector('.hc-pf-picker-host')).toBeNull();
        await w.find('.hc-pf-var-btn').trigger('click');
        await nextTick();
        // picker host Teleport 到 body
        expect(document.querySelector('.hc-pf-picker-host')).not.toBeNull();
        w.unmount();
    });

    it('清空按钮 emit 空串', async () => {
        const f = field({ type: 'variable', name: 'fullName' });
        const w = mountInput(f, 'user/score');
        await w.find('.hc-pf-var-clear').trigger('click');
        expect(lastUpdate(w)).toBe('');
    });
});

describe('BlockParamInput — timeline / element（store 数据源）', () => {
    it('timeline：选项来自 project.state.timelines', async () => {
        const project = useProjectStore();
        project.setSnapshot(stateWith({
            timelines: [
                { id: 'tl-1', name: '开场', durationMs: 1000, fps: 20, loopMode: 'once', trigger: { type: 'manual', params: {} }, tracks: {} },
                { id: 'tl-2', name: '', durationMs: 1000, fps: 20, loopMode: 'once', trigger: { type: 'manual', params: {} }, tracks: {} },
            ],
        }));
        const w = mountInput(field({ type: 'timeline', name: 'timelineId' }), '', 'playTimeline');
        const opts = w.findAll('option');
        // 1 占位 + 2 时间轴
        expect(opts.length).toBe(3);
        expect(w.text()).toContain('开场');
        // 无 name 退 id
        expect(w.text()).toContain('tl-2');
        await w.find('select').setValue('tl-1');
        expect(lastUpdate(w)).toBe('tl-1');
    });

    it('timeline 空列表 → 提示文案，无 select', () => {
        useProjectStore().setSnapshot(stateWith({ timelines: [] }));
        const w = mountInput(field({ type: 'timeline', name: 'timelineId' }), '', 'playTimeline');
        expect(w.find('select').exists()).toBe(false);
        expect(w.text()).toContain('还没有时间轴');
    });

    it('element：选项来自 project.allElements，label 含文本/类型', async () => {
        const project = useProjectStore();
        project.setSnapshot(stateWith({
            layers: [layer([baseEl('e-rect11', 'rect'), baseEl('e-circ22', 'circle')])],
        }));
        const w = mountInput(field({ type: 'element', name: 'elementId' }), '', 'setElementProperty');
        const opts = w.findAll('option');
        expect(opts.length).toBe(3); // 占位 + 2 元素
        expect(w.text()).toContain('rect · rect11');
        await w.find('select').setValue('e-circ22');
        expect(lastUpdate(w)).toBe('e-circ22');
    });

    it('element 空列表 → 提示文案', () => {
        useProjectStore().setSnapshot(stateWith({ layers: [layer([])] }));
        const w = mountInput(field({ type: 'element', name: 'elementId' }), '', 'setElementProperty');
        expect(w.find('select').exists()).toBe(false);
        expect(w.text()).toContain('还没有元素');
    });
});

describe('BlockParamInput — sound（datalist 建议）', () => {
    it('渲染 input list + datalist 含常用建议', async () => {
        const w = mountInput(field({ type: 'sound', name: 'soundId' }), '', 'playSound');
        const input = w.find('input');
        expect(input.attributes('list')).toBe('hc-sound-suggest');
        const datalist = w.find('datalist#hc-sound-suggest');
        expect(datalist.exists()).toBe(true);
        const opts = w.findAll('datalist option');
        expect(opts.length).toBeGreaterThanOrEqual(20);
        // 含一个已知声音
        const values = opts.map((o) => o.attributes('value'));
        expect(values).toContain('block.note_block.harp');
        // 改值 emit string（任意 id 也接受）
        await input.setValue('minecraft:entity.cat.purr');
        expect(lastUpdate(w)).toBe('minecraft:entity.cat.purr');
    });
});

describe('BlockParamInput — command（复合字段）', () => {
    function stubTemplatesFetch() {
        vi.stubGlobal('fetch', vi.fn(async () => ({
            ok: true,
            json: async () => ({
                templates: [
                    { id: 'announce', params: [{ name: 'msg', type: 'text', maxLength: 64 }] },
                    { id: 'kick', params: [{ name: 'player', type: 'online-player', maxLength: 16 }, { name: 'reason', type: 'text', maxLength: 32 }] },
                ],
            }),
        } as unknown as Response)));
    }

    it('拉到模板后渲染模板下拉；选模板 emit 复合值（templateId + 空 params）', async () => {
        stubTemplatesFetch();
        useNetworkStore().sessionId = 'sid-1';
        const f = field({ type: 'command', name: 'templateId', labelKey: 'script.fields.templateId' });
        const w = mountInput(f, { templateId: '', params: {} }, 'runCommand');
        // 等 onMounted fetch 的 promise 链 + 渲染都 flush
        await flushPromises();
        const select = w.find('select');
        expect(select.exists()).toBe(true);
        const opts = w.findAll('option');
        // 占位 + 2 模板
        expect(opts.length).toBe(3);
        await select.setValue('announce');
        const v = lastUpdate(w) as { templateId: string; params: Record<string, string> };
        expect(v.templateId).toBe('announce');
        // 新模板的 param 名以空串起步
        expect(v.params).toEqual({ msg: '' });
    });

    it('选中模板后渲染 params 子输入；改 param emit 更新后的 params', async () => {
        stubTemplatesFetch();
        useNetworkStore().sessionId = 'sid-1';
        const f = field({ type: 'command', name: 'templateId', labelKey: 'script.fields.templateId' });
        const w = mountInput(f, { templateId: 'kick', params: { player: 'Steve', reason: '' } }, 'runCommand');
        await flushPromises();
        // kick 有 2 个 param 子输入
        const paramInputs = w.findAll('.hc-pf-cmd-params input');
        expect(paramInputs.length).toBe(2);
        // 改 reason
        await paramInputs[1].setValue('afk');
        const v = lastUpdate(w) as { templateId: string; params: Record<string, string> };
        expect(v.templateId).toBe('kick');
        expect(v.params.reason).toBe('afk');
        // 保留已填的 player
        expect(v.params.player).toBe('Steve');
    });

    it('无模板配置 → 提示文案，无 select', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => ({
            ok: true, json: async () => ({ templates: [] }),
        } as unknown as Response)));
        useNetworkStore().sessionId = 'sid-1';
        const f = field({ type: 'command', name: 'templateId', labelKey: 'script.fields.templateId' });
        const w = mountInput(f, { templateId: '', params: {} }, 'runCommand');
        await flushPromises();
        expect(w.find('select').exists()).toBe(false);
        expect(w.text()).toContain('服主还没配命令模板');
    });

    // 0.7.3-D1：孤儿模板检测
    it('templateId 非空但不在列表 → 显示孤儿警告', async () => {
        // 服主已删除 "old-cmd"，当前只剩 "announce"
        vi.stubGlobal('fetch', vi.fn(async () => ({
            ok: true,
            json: async () => ({
                templates: [
                    { id: 'announce', params: [{ name: 'msg', type: 'text', maxLength: 64 }] },
                ],
            }),
        } as unknown as Response)));
        useNetworkStore().sessionId = 'sid-1';
        const f = field({ type: 'command', name: 'templateId', labelKey: 'script.fields.templateId' });
        // 积木保存的 templateId 指向已删除的 "old-cmd"
        const w = mountInput(f, { templateId: 'old-cmd', params: {} }, 'runCommand');
        await flushPromises();
        // 孤儿警告出现，且包含失效 id
        expect(w.find('.hc-pf-cmd-orphan').exists()).toBe(true);
        expect(w.find('.hc-pf-cmd-orphan').text()).toContain('old-cmd');
        // params 子输入不渲染（selectedTemplate 为 null）
        expect(w.find('.hc-pf-cmd-params').exists()).toBe(false);
    });

    it('templateId 为空 → 不显示孤儿警告', async () => {
        stubTemplatesFetch();
        useNetworkStore().sessionId = 'sid-1';
        const f = field({ type: 'command', name: 'templateId', labelKey: 'script.fields.templateId' });
        const w = mountInput(f, { templateId: '', params: {} }, 'runCommand');
        await flushPromises();
        expect(w.find('.hc-pf-cmd-orphan').exists()).toBe(false);
    });

    it('templateId 在列表中存在 → 不显示孤儿警告，params 正常渲染', async () => {
        stubTemplatesFetch();
        useNetworkStore().sessionId = 'sid-1';
        const f = field({ type: 'command', name: 'templateId', labelKey: 'script.fields.templateId' });
        const w = mountInput(f, { templateId: 'announce', params: { msg: 'hello' } }, 'runCommand');
        await flushPromises();
        expect(w.find('.hc-pf-cmd-orphan').exists()).toBe(false);
        expect(w.find('.hc-pf-cmd-params').exists()).toBe(true);
    });
});

describe('BlockParamInput — disabled', () => {
    it('disabled 时各控件禁用', () => {
        const w = mount(BlockParamInput, {
            props: { field: field({ type: 'text', name: 'value' }), value: 'x', actionKind: 'log', disabled: true },
        });
        expect((w.find('input').element as HTMLInputElement).disabled).toBe(true);
    });

    it('disabled 时 variable 按钮不打开 picker', async () => {
        const f = field({ type: 'variable', name: 'fullName' });
        const w = mount(BlockParamInput, {
            props: { field: f, value: '', actionKind: 'setVariable', disabled: true },
            global: { stubs: { VariablePicker: { template: '<div class="hc-stub-picker" />' } } },
        });
        await w.find('.hc-pf-var-btn').trigger('click');
        await nextTick();
        expect(document.querySelector('.hc-pf-picker-host')).toBeNull();
    });
});
