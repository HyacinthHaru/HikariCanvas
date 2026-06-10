// @vitest-environment happy-dom
/**
 * 0.7.0-P4-C：BlockStack（触发器帽子 + 动作序列）渲染 smoke。
 *
 * <p>验证：① 帽子读 TRIGGER_DEFS 渲染规则名 + 触发器 label + 触发器参数占位；② 动作序列
 * 用 BlockNode 渲染，顶层 path = {@code actions/i}；③ 帽子 data-block-path='trigger'；
 * ④ absolute 定位坐标应用（props.x/y）。</p>
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import BlockStack from '../BlockStack.vue';
import { useUiStore } from '@/stores/ui';
import type { ScriptRule } from '@/types/protocol';

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

    it('帽子渲染：规则名 + 触发器 label + 触发器参数占位', async () => {
        const w = mount(BlockStack, { props: { rule: makeRule(), x: 10, y: 20 } });
        await nextTick();
        expect(w.text()).toContain('我的规则');
        expect(w.text()).toContain('每隔一段时间'); // timer label
        expect(w.text()).toContain('间隔（秒）:'); // 触发器字段 label
        expect(w.text()).toContain('30'); // intervalSeconds 原始值
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
});
