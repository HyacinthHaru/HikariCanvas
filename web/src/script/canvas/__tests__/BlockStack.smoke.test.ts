// @vitest-environment happy-dom
/**
 * 0.7.0-P4-C / P5-H2：BlockStack（触发器帽子 + 动作序列）渲染 smoke。
 *
 * <p>验证：① 帽子读 TRIGGER_DEFS 渲染规则名 + <b>可编辑触发类型 select</b> + 当前类型参数控件
 * （H2）；② 动作序列用 BlockNode 渲染，顶层 path = {@code actions/i}；③ 帽子
 * data-block-path='trigger'；④ absolute 定位坐标应用（props.x/y）；⑤ H2 切触发类型调
 * setTrigger(makeDefaultTrigger)、改参数调 setTrigger 带新字段、lock 时控件 disabled。</p>
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import BlockStack from '../BlockStack.vue';
import { useUiStore } from '@/stores/ui';
import { useScriptEditStore } from '@/stores/scriptEdit';
import { useProjectStore } from '@/stores/project';
import { makeDefaultTrigger } from '../../model/blockDefs';
import type { ScriptRule } from '@/types/protocol';

// BlockParamInput（帽子参数控件）的 command 字段会拉模板；触发器无 command 字段，但
// onMounted 里仍可能触发——mock wsClient 让任何 send 走 resolve，避免 smoke 噪声。
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({
        sendScriptCreate: vi.fn(() => Promise.resolve()),
        sendScriptUpdate: vi.fn(() => Promise.resolve()),
        sendScriptDelete: vi.fn(() => Promise.resolve()),
        sendScriptEnable: vi.fn(() => Promise.resolve()),
    }),
}));

function makeRule(over: Partial<ScriptRule> = {}): ScriptRule {
    return {
        id: 'sr-aaaa',
        wallId: 'w-1',
        enabled: true,
        name: '我的规则',
        trigger: { type: 'timer', intervalSeconds: 30 },
        actions: [
            { type: 'setVariable', fullName: 'user/n', value: '1' },
            { type: 'log', message: 'done' },
        ],
        blockLayout: '{}',
        ...over,
    };
}

describe('BlockStack 渲染 smoke', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
    });

    it('帽子渲染：规则名 + 触发类型 select（当前选 timer）+ 触发参数控件', async () => {
        const w = mount(BlockStack, { props: { rule: makeRule(), x: 10, y: 20 } });
        await nextTick();
        expect(w.text()).toContain('我的规则');
        // H2：触发类型 select —— 6 个 option（label 走 script.blocks.<kind>），当前值 = timer
        const kindSelect = w.find('.hc-hat-kind-select');
        expect(kindSelect.exists()).toBe(true);
        expect((kindSelect.element as HTMLSelectElement).value).toBe('timer');
        expect(kindSelect.findAll('option')).toHaveLength(6);
        expect(w.text()).toContain('每隔一段时间'); // timer label（select option）
        // 触发参数控件（BlockParamInput number）：label '间隔（秒）' + input 受控值 = 30
        expect(w.text()).toContain('间隔（秒）');
        const numInput = w.find('.hc-hat-param input[type="number"]');
        expect(numInput.exists()).toBe(true);
        expect((numInput.element as HTMLInputElement).value).toBe('30');
    });

    it('帽子 data-block-path = trigger（trace 触发器步定位）', async () => {
        const w = mount(BlockStack, { props: { rule: makeRule(), x: 0, y: 0 } });
        await nextTick();
        expect(w.find('[data-block-path="trigger"]').exists()).toBe(true);
    });

    it('动作序列渲染：顶层 path = actions/0 / actions/1', async () => {
        const w = mount(BlockStack, { props: { rule: makeRule(), x: 0, y: 0 } });
        await nextTick();
        expect(w.find('[data-block-path="actions/0"]').exists()).toBe(true);
        expect(w.find('[data-block-path="actions/1"]').exists()).toBe(true);
        expect(w.text()).toContain('设置变量');
        expect(w.text()).toContain('记录日志');
    });

    it('absolute 坐标应用（left/top = props.x/y）', async () => {
        const w = mount(BlockStack, { props: { rule: makeRule(), x: 123, y: 456 } });
        await nextTick();
        const stack = w.find('.hc-block-stack').element as HTMLElement;
        expect(stack.style.left).toBe('123px');
        expect(stack.style.top).toBe('456px');
    });

    it('data-rule-id 挂在堆根', async () => {
        const w = mount(BlockStack, { props: { rule: makeRule({ id: 'sr-zzzz' }), x: 0, y: 0 } });
        await nextTick();
        expect(w.find('[data-rule-id="sr-zzzz"]').exists()).toBe(true);
    });

    it('无参数触发器（wallReady）渲染不崩 + 空动作序列显占位', async () => {
        const w = mount(BlockStack, {
            props: {
                rule: makeRule({ trigger: { type: 'wallReady' }, actions: [] }),
                x: 0,
                y: 0,
            },
        });
        await nextTick();
        expect(w.text()).toContain('当 画板就绪');
        expect(w.text()).toContain('把积木拖到这里'); // 空序列占位
    });

    it('禁用规则显示停用标记', async () => {
        const w = mount(BlockStack, { props: { rule: makeRule({ enabled: false }), x: 0, y: 0 } });
        await nextTick();
        expect(w.find('.hc-hat-disabled').exists()).toBe(true);
    });

    // ---- 0.7.0-P5-H2：可编辑触发器 ----

    it('切触发类型 → 调 setTrigger(makeDefaultTrigger(新类型))', async () => {
        const scripts = (await import('@/stores/scripts')).useScriptStore();
        const rule = makeRule({ trigger: { type: 'wallReady' } });
        scripts.upsert(rule); // selectRule 需 store 里有这条
        const edit = useScriptEditStore();
        edit.selectRule(rule.id);
        const spy = vi.spyOn(edit, 'setTrigger');

        const w = mount(BlockStack, { props: { rule, x: 0, y: 0 } });
        await nextTick();
        const select = w.find('.hc-hat-kind-select');
        await select.setValue('timer');
        expect(spy).toHaveBeenCalledTimes(1);
        // 切类型产出 makeDefaultTrigger('timer') —— 合法默认 trigger（带范围内间隔）
        expect(spy.mock.calls[0][0]).toEqual(makeDefaultTrigger('timer'));
        expect(spy.mock.calls[0][0]).toEqual({ type: 'timer', intervalSeconds: 10 });
    });

    it('改触发参数 → 调 setTrigger 带新字段值（保留 type）', async () => {
        const scripts = (await import('@/stores/scripts')).useScriptStore();
        const rule = makeRule({ trigger: { type: 'timer', intervalSeconds: 30 } });
        scripts.upsert(rule);
        const edit = useScriptEditStore();
        edit.selectRule(rule.id);
        const spy = vi.spyOn(edit, 'setTrigger');

        const w = mount(BlockStack, { props: { rule, x: 0, y: 0 } });
        await nextTick();
        const numInput = w.find('.hc-hat-param input[type="number"]');
        // 改 intervalSeconds 60：immutable 覆盖单字段后整体 setTrigger
        await numInput.setValue('60');
        await numInput.trigger('input');
        expect(spy).toHaveBeenCalled();
        const last = spy.mock.calls[spy.mock.calls.length - 1][0];
        expect(last).toEqual({ type: 'timer', intervalSeconds: 60 });
    });

    it('lock 时触发类型 select + 参数控件 disabled', async () => {
        const project = useProjectStore();
        // lockedAt 非 null → isLocked
        project.setWallMeta('w-1', null, Date.now(), 'owner', 'owner');
        const w = mount(BlockStack, {
            props: { rule: makeRule({ trigger: { type: 'timer', intervalSeconds: 30 } }), x: 0, y: 0 },
        });
        await nextTick();
        expect((w.find('.hc-hat-kind-select').element as HTMLSelectElement).disabled).toBe(true);
        expect((w.find('.hc-hat-param input[type="number"]').element as HTMLInputElement).disabled).toBe(true);
    });
});
