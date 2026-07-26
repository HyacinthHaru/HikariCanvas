// @vitest-environment happy-dom
/**
 * dock 上两条与"改动没落到服务端"有关的守卫：
 *
 * ① 整体帧缓动同步的 6 条 op 必须共享同一个 coalesceKey。不传的话后端按每帧一个 key 回退，
 *    一次调整占 6 步撤销，而且撤一步只回滚一个属性的缓动 —— 正好把运动轨迹掰弯。
 * ② 帧拖动是乐观改本地时刻再发 op；服务端没收下时要把本地挪回原位并提示，
 *    否则画面上帧在新位置、服务端在老位置，之后所有操作都打在错的时刻上。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

const sendKeyframeUpdate = vi.fn().mockResolvedValue(undefined);
const sendKeyframeMove = vi.fn().mockResolvedValue(undefined);
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({
        sendTimelineCreate: vi.fn().mockResolvedValue(undefined),
        sendTimelineUpdate: vi.fn().mockResolvedValue(undefined),
        sendTimelineDelete: vi.fn().mockResolvedValue(undefined),
        sendKeyframeAdd: vi.fn().mockResolvedValue(undefined),
        sendKeyframeUpdate,
        sendKeyframeDelete: vi.fn().mockResolvedValue(undefined),
        sendKeyframeMove,
    }),
}));

import TimelineDock from '../TimelineDock.vue';
import { transformKeyframeKey } from '../timelineLogic';
import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import { useUiStore } from '@/stores/ui';
import { useTimelineStore } from '@/stores/timeline';
import type { ProjectState } from '@/types/protocol';

function makeState(): ProjectState {
    return {
        version: 3,
        canvas: { w: 128, h: 128, background: { type: 'solid', color: '#FFFFFF' } },
        layers: [{
            id: 'layer-1', name: 'L', visible: true, locked: false, opacity: 1,
            blendMode: 'normal', colorTag: null,
            elements: [{
                id: 'e-1', type: 'rect', x: 0, y: 0, w: 100, h: 50, rotation: 0,
                locked: false, visible: true, fill: { type: 'solid', color: '#ffffff' },
                stroke: null, opacity: 1, blendMode: 'normal', renderMode: 'clean',
            }],
        }],
        activeLayerId: 'layer-1',
        history: { undoDepth: 0, redoDepth: 0 },
        timelines: [{
            id: 'tl-1', name: 'Test', durationMs: 5000, fps: 20, loopMode: 'loop',
            trigger: { type: 'manual', params: {} },
            tracks: {
                'e-1': [
                    { id: 'kx', property: 'x', timeMs: 1000, value: 0, easing: { type: 'linear' } },
                    { id: 'ky', property: 'y', timeMs: 1000, value: 0, easing: { type: 'linear' } },
                ],
            },
        }],
        activeTimelineId: 'tl-1',
    } as unknown as ProjectState;
}

async function mountDock() {
    useProjectStore().setSnapshot(makeState());
    useProjectStore().setWallMeta('w-1', null, null, null, null);
    useUiStore().selectMany(['e-1']);
    const store = useTimelineStore();
    store.openDock();
    const wrapper = mount(TimelineDock, { attachTo: document.body });
    await nextTick();
    return wrapper;
}

beforeEach(() => {
    setActivePinia(createPinia());
    useUiStore().locale = 'zh';
    sendKeyframeUpdate.mockClear().mockResolvedValue(undefined);
    sendKeyframeMove.mockClear().mockResolvedValue(undefined);
    document.body.innerHTML = '';
});

describe('整体帧缓动同步', () => {
    it('同一组的每条 op 共享同一个 coalesceKey（一次调整 = 一步撤销）', async () => {
        const wrapper = await mountDock();
        const store = useTimelineStore();
        store.selectGroup(transformKeyframeKey('e-1', 1000));
        await nextTick();

        (wrapper.vm as unknown as { onEasingUpdate: (e: unknown) => void })
            .onEasingUpdate({ type: 'easeInOut' });

        expect(sendKeyframeUpdate).toHaveBeenCalledTimes(2);
        const keys = sendKeyframeUpdate.mock.calls.map(c => c[3]);
        expect(keys[0]).toBeTruthy();
        expect(new Set(keys).size).toBe(1);
    });
});

describe('拖帧失败要挪回原位', () => {
    it('服务端拒绝 → 本地 timeMs 回到拖动前 + 给用户提示', async () => {
        sendKeyframeMove.mockRejectedValue(new Error('send_failed'));
        const wrapper = await mountDock();
        const vm = wrapper.vm as unknown as {
            onBlockPointerDown: (e: unknown, id: string, t: number) => void;
            onBlockPointerMove: (e: unknown) => void;
            onBlockPointerUp: (e: unknown) => void;
        };
        const store = useTimelineStore();
        store.setPxPerMs(0.1);   // 10ms/px，拖 50px = 500ms

        vm.onBlockPointerDown({ clientX: 0, pointerId: 1, stopPropagation() {}, currentTarget: null }, 'e-1', 1000);
        vm.onBlockPointerMove({ clientX: 50, pointerId: 1 });
        vm.onBlockPointerUp({ pointerId: 1, shiftKey: false, currentTarget: null });

        const track = useProjectStore().state!.timelines![0].tracks!['e-1'];
        expect(track[0].timeMs).not.toBe(1000);   // 乐观阶段已挪走

        await new Promise(r => setTimeout(r, 0));

        expect(track[0].timeMs).toBe(1000);
        expect(track[1].timeMs).toBe(1000);
        expect(useNetworkStore().lastError).toBeTruthy();
    });

    it('成功时保持在新位置', async () => {
        const wrapper = await mountDock();
        const vm = wrapper.vm as unknown as {
            onBlockPointerDown: (e: unknown, id: string, t: number) => void;
            onBlockPointerMove: (e: unknown) => void;
            onBlockPointerUp: (e: unknown) => void;
        };
        useTimelineStore().setPxPerMs(0.1);

        vm.onBlockPointerDown({ clientX: 0, pointerId: 1, stopPropagation() {}, currentTarget: null }, 'e-1', 1000);
        vm.onBlockPointerMove({ clientX: 50, pointerId: 1 });
        vm.onBlockPointerUp({ pointerId: 1, shiftKey: false, currentTarget: null });
        await new Promise(r => setTimeout(r, 0));

        const track = useProjectStore().state!.timelines![0].tracks!['e-1'];
        expect(track[0].timeMs).not.toBe(1000);
        expect(useNetworkStore().lastError).toBeNull();
    });
});
