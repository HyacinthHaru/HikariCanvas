// @vitest-environment happy-dom
/**
 * 右栏的两道"锁"。
 *
 * <h3>画板锁定</h3>
 * <p>以前是整栏 {@code pointer-events: none}：滚轮事件直接穿过去，锁定状态下图层面板和属性
 * 面板都滚不动——想只读看看反而看不全；而且 Tab 依旧能聚焦到输入框，方向键改数值是<b>真发 op
 * 落库</b>的（按架构约定后端编辑 op 不看锁状态）。现在改用 inert 属性：点不动、Tab 也聚焦不到，
 * 滚动照旧；滚动容器本身不设 inert。</p>
 *
 * <h3>元素锁定</h3>
 * <p>{@code element.locked} 后端全仓一处都不看，删除按钮不拦就等于没锁。</p>
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import RightPanel from '../RightPanel.vue';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';
import type { ProjectState } from '@/types/protocol';

const sent: { op: string; payload: unknown }[] = [];
const send = vi.fn((op: string, payload?: unknown) => {
    sent.push({ op, payload });
    return `c-${sent.length}`;
});
vi.mock('@/network/wsClient', () => ({ getWsClient: () => ({ send }) }));

function makeState(elements: Record<string, unknown>[], layerOver: Record<string, unknown> = {}): ProjectState {
    return {
        canvas: { width: 128, height: 128, background: '#ffffff', gridSize: 8, guides: [] },
        elements,
        layers: [{ id: 'layer-1', name: 'Layer 1', locked: false, visible: true, elements, ...layerOver }],
        activeLayerId: 'layer-1',
        timelines: [],
        activeTimelineId: null,
    } as unknown as ProjectState;
}

function rect(id: string, over: Record<string, unknown> = {}) {
    return { id, type: 'rect', x: 0, y: 0, w: 10, h: 10, rotation: 0, visible: true, locked: false, ...over };
}

function mountPanel() {
    return mount(RightPanel, {
        global: {
            stubs: {
                LayerPanel: true,
                ElementListSection: true,
                BrushPanel: true,
                PaintBucketPanel: true,
                CanvasSettingsSection: true,
                TextElementSection: true,
                ImageElementSection: true,
                GeometricElementSection: true,
            },
        },
    });
}

beforeEach(() => {
    setActivePinia(createPinia());
    useUiStore().locale = 'zh';
    sent.length = 0;
    send.mockClear();
});

describe('RightPanel — 画板锁定', () => {
    it('锁定时编辑区带 inert，滚动容器不带（还能滚着看）', async () => {
        const project = useProjectStore();
        project.setSnapshot(makeState([rect('el-A')]));
        project.lockedAt = 1;
        useUiStore().selectElement('el-A');

        const w = mountPanel();
        await nextTick();

        // 属性内容块 inert
        const propsBlock = w.find('.p-3.space-y-3');
        expect(propsBlock.exists()).toBe(true);
        expect(propsBlock.attributes('inert')).toBeDefined();
        // 滚动容器本身不能 inert，否则锁定时连滚都滚不了
        const scroller = w.find('section.overflow-y-auto');
        expect(scroller.exists()).toBe(true);
        expect(scroller.attributes('inert')).toBeUndefined();
        // 不再用 pointer-events:none 屏蔽（那会连滚轮一起吃掉）
        expect(w.html()).not.toContain('pointer-events');
        w.unmount();
    });

    it('未锁定时不带 inert', async () => {
        const project = useProjectStore();
        project.setSnapshot(makeState([rect('el-A')]));
        useUiStore().selectElement('el-A');

        const w = mountPanel();
        await nextTick();

        expect(w.find('.p-3.space-y-3').attributes('inert')).toBeUndefined();
        w.unmount();
    });
});

describe('RightPanel — 元素锁定', () => {
    it('锁定元素：删除按钮禁用，点了也不发 op', async () => {
        const project = useProjectStore();
        project.setSnapshot(makeState([rect('el-A', { locked: true })]));
        useUiStore().selectElement('el-A');

        const w = mountPanel();
        await nextTick();

        const btn = w.find('header button');
        expect(btn.exists()).toBe(true);
        expect(btn.attributes('disabled')).toBeDefined();

        await btn.trigger('click');
        expect(sent.filter((s) => s.op === 'element.delete')).toHaveLength(0);
        w.unmount();
    });

    it('所在图层锁定：同样不删', async () => {
        const project = useProjectStore();
        project.setSnapshot(makeState([rect('el-A')], { locked: true }));
        useUiStore().selectElement('el-A');

        const w = mountPanel();
        await nextTick();

        await w.find('header button').trigger('click');
        expect(sent.filter((s) => s.op === 'element.delete')).toHaveLength(0);
        w.unmount();
    });

    it('没锁的元素照常删（回归守卫）', async () => {
        const project = useProjectStore();
        project.setSnapshot(makeState([rect('el-A')]));
        useUiStore().selectElement('el-A');

        const w = mountPanel();
        await nextTick();

        const btn = w.find('header button');
        expect(btn.attributes('disabled')).toBeUndefined();
        await btn.trigger('click');
        expect(sent.filter((s) => s.op === 'element.delete')).toHaveLength(1);
        w.unmount();
    });

    it('多选批量删：跳过锁定的，其余照删并说明跳了几个', async () => {
        const project = useProjectStore();
        project.setSnapshot(makeState([rect('el-A'), rect('el-B', { locked: true }), rect('el-C')]));
        useUiStore().selectMany(['el-A', 'el-B', 'el-C']);
        const net = useNetworkStore();

        const w = mountPanel();
        await nextTick();

        await w.find('header button').trigger('click');
        const deletes = sent.filter((s) => s.op === 'element.delete')
            .map((s) => (s.payload as { elementId: string }).elementId);
        expect(deletes).toEqual(['el-A', 'el-C']);
        expect(net.lastError).toBeTruthy();
        w.unmount();
    });
});
