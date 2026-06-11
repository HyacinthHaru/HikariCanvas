// @vitest-environment happy-dom
/**
 * 0.7.0-P5 实测修复（渲染数据源主根因）：BlockCanvas 渲染<b>当前编辑规则</b>用 workingCopy。
 *
 * <p>这是最关键的回归测试——0.7.0 之前画布只读 {@code scripts.listSorted}（server 镜像），
 * 但所有编辑改的是 {@code scriptEdit.workingCopy}（本地副本）。本套验证：选中规则后对 workingCopy
 * 的改动（{@code setActions} 加积木 / {@code setTrigger} 改触发器 / {@code updateActionField} 改字段）
 * <b>立即反映到画布 DOM</b>，无需等 800ms debounce save 回 server。</p>
 *
 * <p>mock wsClient：本地编辑链路（selectRule / setActions / setTrigger）不真发网络；断言只看画布
 * DOM 是否随 workingCopy 即时变化。</p>
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import BlockCanvas from '../BlockCanvas.vue';
import { useUiStore } from '@/stores/ui';
import { useScriptStore } from '@/stores/scripts';
import { useScriptEditStore } from '@/stores/scriptEdit';
import type { ScriptRule, ScriptAction } from '@/types/protocol';

// mock wsClient：编辑链路不真发网络（setActions/setTrigger 走 debounce save，会调 sendScriptUpdate）。
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({
        sendScriptCreate: vi.fn(() => Promise.resolve()),
        sendScriptUpdate: vi.fn(() => Promise.resolve()),
        sendScriptDelete: vi.fn(() => Promise.resolve()),
        sendScriptEnable: vi.fn(() => Promise.resolve()),
    }),
}));

function makeRule(id: string, actions: ScriptAction[] = [{ type: 'log', message: 'A' }]): ScriptRule {
    return {
        id, wallId: 'w-1', enabled: true, name: id, trigger: { type: 'wallReady' },
        actions, blockLayout: '{}',
    };
}

beforeEach(() => {
    setActivePinia(createPinia());
    useUiStore().locale = 'zh';
});

describe('BlockCanvas 渲染当前编辑规则用 workingCopy（主根因回归）', () => {
    it('setActions 加一个积木 → 画布立即出现新积木 DOM（不等 save）', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1')]); // server 镜像只有 1 个动作（actions/0）
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        const w = mount(BlockCanvas);
        await nextTick();

        // 初始：1 个动作块
        expect(w.find('[data-block-path="actions/0"]').exists()).toBe(true);
        expect(w.find('[data-block-path="actions/1"]').exists()).toBe(false);

        // 改 workingCopy（加第二个动作）——模拟拖积木 setActions
        edit.setActions([
            { type: 'log', message: 'A' },
            { type: 'wait', ms: 500 },
        ]);
        await nextTick();

        // 画布立即出现第二个积木（actions/1），server 镜像并未变（仍 1 个动作）
        expect(w.find('[data-block-path="actions/1"]').exists()).toBe(true);
        expect(scripts.get('sr-1')!.actions.length).toBe(1); // server 镜像没动
        w.unmount();
    });

    it('拖入"设置元素属性"（空 elementId 会被 validator 拦保存）也立即显示', async () => {
        // 这正是 bug 报告里"拖设置元素属性/执行命令永远不显示但出现待完善"的场景：
        // 空必填字段被 validator 拦 → server 永不更新 → 旧逻辑画布永远不显示。
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1')]);
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        const w = mount(BlockCanvas);
        await nextTick();

        edit.setActions([
            { type: 'log', message: 'A' },
            { type: 'setElementProperty', elementId: '', property: 'x', value: '' },
        ]);
        await nextTick();

        // 该积木立即渲染（哪怕 elementId 空、validator 不放行保存）
        const block = w.find('[data-block-path="actions/1"]');
        expect(block.exists()).toBe(true);
        // validator 应报错（说明 server 不会收到这条 save）——但画布已显示
        expect(edit.validationErrors.length).toBeGreaterThan(0);
        // 待完善角标渲染（空 elementId）
        expect(block.find('.hc-block-need-select').exists()).toBe(true);
        w.unmount();
    });

    it('setTrigger 改触发类型 → 帽子 select 立即反映新 type', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1')]); // 初始 wallReady
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        const w = mount(BlockCanvas);
        await nextTick();

        const select = () => w.find('.hc-hat-kind-select').element as HTMLSelectElement;
        expect(select().value).toBe('wallReady');

        edit.setTrigger({ type: 'timer', intervalSeconds: 10 });
        await nextTick();

        // 帽子下拉立即反映 timer + 出现 intervalSeconds 数值控件
        expect(select().value).toBe('timer');
        expect(w.find('.hc-hat-param input[type="number"]').exists()).toBe(true);
        w.unmount();
    });

    it('updateActionField 改字段值 → 画布控件立即反映', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1', [{ type: 'wait', ms: 500 }])]);
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        const w = mount(BlockCanvas);
        await nextTick();

        const numInput = () => w.find('[data-block-path="actions/0"] input[type="number"]').element as HTMLInputElement;
        expect(numInput().value).toBe('500');

        edit.updateActionField('actions/0', { ms: 1200 } as Partial<ScriptAction>);
        await nextTick();
        expect(numInput().value).toBe('1200');
        w.unmount();
    });

    it('未选规则（workingCopy=null）→ 全部规则渲染 server 镜像', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1'), makeRule('sr-2')]);
        // 不选任何规则
        const w = mount(BlockCanvas);
        await nextTick();
        expect(w.findAll('.hc-block-stack').length).toBe(2);
        // server 镜像的动作渲染
        expect(w.find('[data-rule-id="sr-1"] [data-block-path="actions/0"]').exists()).toBe(true);
        w.unmount();
    });

    it('只有被编辑规则用 workingCopy；其它规则仍渲染 server 镜像', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule('sr-1'), makeRule('sr-2')]);
        const edit = useScriptEditStore();
        edit.selectRule('sr-1');
        const w = mount(BlockCanvas);
        await nextTick();

        // 给 sr-1 的 workingCopy 加积木
        edit.setActions([{ type: 'log', message: 'A' }, { type: 'wait', ms: 500 }]);
        await nextTick();

        // sr-1（被编辑）有 actions/1；sr-2（未编辑）只有 server 镜像的 actions/0
        expect(w.find('[data-rule-id="sr-1"] [data-block-path="actions/1"]').exists()).toBe(true);
        expect(w.find('[data-rule-id="sr-2"] [data-block-path="actions/1"]').exists()).toBe(false);
        expect(w.find('[data-rule-id="sr-2"] [data-block-path="actions/0"]').exists()).toBe(true);
        w.unmount();
    });
});
