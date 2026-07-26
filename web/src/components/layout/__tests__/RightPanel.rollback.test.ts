// @vitest-environment happy-dom
/**
 * 属性面板：服务端拒收某一帧后回滚那次乐观更新。
 *
 * <p>面板一直是"先改本地再发帧"。此前服务端回 {@code INVALID_PAYLOAD} 时前端既不回滚也不重拉，
 * 于是浏览器显示新值、游戏里还是旧值，一直分叉到下次全量快照；而错误只写进日志，用户全程无感。
 * 现在每帧记一份原值，同 id 的 INVALID_PAYLOAD 回来就还原，并把说明写进状态栏提示位。</p>
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

let sendSeq = 0;
const sent: { op: string; payload: unknown; id: string }[] = [];
const send = vi.fn((op: string, payload?: unknown) => {
    const id = `c-${sendSeq++}`;
    sent.push({ op, payload, id });
    return id;
});
vi.mock('@/network/wsClient', () => ({ getWsClient: () => ({ send }) }));

function makeState(elements: Record<string, unknown>[]): ProjectState {
    return {
        canvas: { width: 128, height: 128, background: '#ffffff', gridSize: 8, guides: [] },
        elements,
        layers: [{ id: 'layer-1', name: 'Layer 1', locked: false, visible: true, elements }],
        activeLayerId: 'layer-1',
        timelines: [],
        activeTimelineId: null,
    } as unknown as ProjectState;
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
    sendSeq = 0;
    send.mockClear();
});

describe('RightPanel — 被拒的乐观更新会回滚', () => {
    it('INVALID_PAYLOAD 回来 → 还原那一帧改过的字段 + 给用户可见提示', async () => {
        const project = useProjectStore();
        project.setSnapshot(makeState([
            { id: 'el-A', type: 'rect', x: 0, y: 0, w: 10, h: 10, rotation: 0, visible: true, locked: false },
        ]));
        const ui = useUiStore();
        ui.selectElement('el-A');
        const net = useNetworkStore();

        const w = mountPanel();
        await nextTick();

        // 取消勾选「可见」→ 立即发帧 + 乐观改本地
        const visibleCheckbox = w.findAll('input[type="checkbox"]')[0];
        await visibleCheckbox.setValue(false);
        const frame = sent.find((s) => s.op === 'element.update');
        expect(frame).toBeTruthy();
        expect((project.elementById('el-A') as { visible: boolean }).visible).toBe(false);

        // 服务端拒了这一帧
        net.lastOpError = {
            code: 'INVALID_PAYLOAD', message: '值不合法', ts: Date.now(), opId: frame!.id,
        };
        await nextTick();

        expect((project.elementById('el-A') as { visible: boolean }).visible).toBe(true);
        expect(net.lastError).toBe('值不合法');
        w.unmount();
    });

    it('别人的帧 id 出错 → 不动我的乐观更新', async () => {
        const project = useProjectStore();
        project.setSnapshot(makeState([
            { id: 'el-A', type: 'rect', x: 0, y: 0, w: 10, h: 10, rotation: 0, visible: true, locked: false },
        ]));
        useUiStore().selectElement('el-A');
        const net = useNetworkStore();

        const w = mountPanel();
        await nextTick();
        await w.findAll('input[type="checkbox"]')[0].setValue(false);

        net.lastOpError = {
            code: 'INVALID_PAYLOAD', message: '别处的错误', ts: Date.now(), opId: 'c-999',
        };
        await nextTick();
        expect((project.elementById('el-A') as { visible: boolean }).visible).toBe(false);
        w.unmount();
    });

    it('限流 / 会话类错误不回滚（本地值本身没问题，等服务端快照对齐）', async () => {
        const project = useProjectStore();
        project.setSnapshot(makeState([
            { id: 'el-A', type: 'rect', x: 0, y: 0, w: 10, h: 10, rotation: 0, visible: true, locked: false },
        ]));
        useUiStore().selectElement('el-A');
        const net = useNetworkStore();

        const w = mountPanel();
        await nextTick();
        await w.findAll('input[type="checkbox"]')[0].setValue(false);
        const frame = sent.find((s) => s.op === 'element.update')!;

        net.lastOpError = {
            code: 'RATE_LIMITED', message: '太快了', ts: Date.now(), opId: frame.id,
        };
        await nextTick();
        expect((project.elementById('el-A') as { visible: boolean }).visible).toBe(false);
        w.unmount();
    });
});
