// @vitest-environment happy-dom
/**
 * 整体关键帧执行器的两条守卫：
 *
 * ① 乐观改了本地值、服务端却没收下时要撤回来并提示。以前 catch 是空的，断线 / 被拒 /
 *    等不到回执时界面显示新值、服务端还是旧值，用户毫不知情。
 * ② 同一 (元素, 属性, 时刻) 的 add 还在路上时不许再发一条。后端不做去重，重复帧一旦
 *    产生，取值取最后一帧、更新打在第一帧上 —— 这个时刻就再也改不动了。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';

const sendKeyframeUpdate = vi.fn();
const sendKeyframeAdd = vi.fn();
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({ sendKeyframeUpdate, sendKeyframeAdd }),
}));

import { useTimelineAuthoring, __resetPendingAddsForTest } from '../useTimelineAuthoring';
import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import type { Keyframe, ProjectState, Timeline } from '@/types/protocol';

function mkState(): ProjectState {
    return {
        version: 3,
        protocolVersion: 3,
        canvas: { widthMaps: 1, heightMaps: 1, background: '#FFFFFF' },
        layers: [{
            id: 'l-0', name: 'L', visible: true, locked: false, opacity: 1,
            blendMode: 'normal', colorTag: null,
            elements: [{
                id: 'e-1', type: 'rect', x: 10, y: 20, w: 30, h: 40, rotation: 0,
                locked: false, visible: true, opacity: 1,
                fill: { type: 'solid', color: '#ffffff' },
            }],
        }],
        activeLayerId: 'l-0',
        history: { undoDepth: 0, redoDepth: 0 },
    } as unknown as ProjectState;
}

function mkTimeline(tracks: Record<string, Keyframe[]> = {}): Timeline {
    return {
        id: 'tl-1', name: 'T', durationMs: 5000, fps: 20, loopMode: 'loop',
        trigger: { type: 'manual', params: {} }, tracks,
    };
}

const ALL_PROPS: Keyframe[] = [
    { id: 'kf-x', property: 'x', timeMs: 0, value: 999, easing: { type: 'linear' } },
    { id: 'kf-y', property: 'y', timeMs: 0, value: 999, easing: { type: 'linear' } },
    { id: 'kf-w', property: 'w', timeMs: 0, value: 999, easing: { type: 'linear' } },
    { id: 'kf-h', property: 'h', timeMs: 0, value: 999, easing: { type: 'linear' } },
    { id: 'kf-r', property: 'rotation', timeMs: 0, value: 999, easing: { type: 'linear' } },
    { id: 'kf-o', property: 'opacity', timeMs: 0, value: 999, easing: { type: 'linear' } },
];

beforeEach(() => {
    setActivePinia(createPinia());
    sendKeyframeUpdate.mockReset();
    sendKeyframeAdd.mockReset();
    __resetPendingAddsForTest();
    useProjectStore().setSnapshot(mkState());
});

describe('乐观改值失败要撤回', () => {
    it('服务端拒绝 → 本地值放回原样 + 给用户提示', async () => {
        sendKeyframeUpdate.mockRejectedValue(new Error('INVALID_KEYFRAME_TIME'));
        const kfs = ALL_PROPS.map(k => ({ ...k }));
        const tl = mkTimeline({ 'e-1': kfs });

        useTimelineAuthoring().upsertTransformKeyframe(tl, 'e-1', 0);
        // 乐观阶段：值已被改成元素当前几何
        expect(kfs.find(k => k.property === 'x')!.value).toBe(10);

        await new Promise(r => setTimeout(r, 0));

        expect(kfs.find(k => k.property === 'x')!.value).toBe(999);
        expect(kfs.find(k => k.property === 'y')!.value).toBe(999);
        expect(useNetworkStore().lastError).toBeTruthy();
    });

    it('成功时不动本地值、不报错', async () => {
        sendKeyframeUpdate.mockResolvedValue(undefined);
        const kfs = ALL_PROPS.map(k => ({ ...k }));
        useTimelineAuthoring().upsertTransformKeyframe(mkTimeline({ 'e-1': kfs }), 'e-1', 0);
        await new Promise(r => setTimeout(r, 0));

        expect(kfs.find(k => k.property === 'x')!.value).toBe(10);
        expect(useNetworkStore().lastError).toBeNull();
    });

    it('期间落了新 patch 就不再撤回（别覆盖更新的真相）', async () => {
        let reject: (e: Error) => void = () => {};
        sendKeyframeUpdate.mockImplementation(() => new Promise((_, rj) => { reject = rj; }));
        const kfs = ALL_PROPS.map(k => ({ ...k }));
        useTimelineAuthoring().upsertTransformKeyframe(mkTimeline({ 'e-1': kfs }), 'e-1', 0);

        // 服务端推来的新值先落地
        kfs.find(k => k.property === 'x')!.value = 555;
        reject(new Error('ack_timeout'));
        await new Promise(r => setTimeout(r, 0));

        expect(kfs.find(k => k.property === 'x')!.value).toBe(555);
    });
});

describe('同一时刻不重复打帧', () => {
    it('第一条 add 还没回执时，第二次调用不再发 add', async () => {
        let resolveAdd: () => void = () => {};
        sendKeyframeAdd.mockImplementation(() => new Promise<void>(r => { resolveAdd = r; }));
        const authoring = useTimelineAuthoring();
        const tl = mkTimeline({});   // 一帧都没有 → 6 条 add

        authoring.upsertTransformKeyframe(tl, 'e-1', 0);
        expect(sendKeyframeAdd).toHaveBeenCalledTimes(6);

        // 服务端还没回执、patch 也没回来（tracks 依旧为空）——照旧逻辑会再发 6 条
        authoring.upsertTransformKeyframe(tl, 'e-1', 0);
        expect(sendKeyframeAdd).toHaveBeenCalledTimes(6);

        resolveAdd();
        await new Promise(r => setTimeout(r, 0));
    });

    it('回执到了以后同一时刻还能再打（不是永久拉黑）', async () => {
        sendKeyframeAdd.mockResolvedValue(undefined);
        const authoring = useTimelineAuthoring();
        const tl = mkTimeline({});

        authoring.upsertTransformKeyframe(tl, 'e-1', 0);
        await new Promise(r => setTimeout(r, 0));
        authoring.upsertTransformKeyframe(tl, 'e-1', 0);

        expect(sendKeyframeAdd).toHaveBeenCalledTimes(12);
    });

    it('不同时刻互不影响', () => {
        sendKeyframeAdd.mockImplementation(() => new Promise<void>(() => {}));
        const authoring = useTimelineAuthoring();
        const tl = mkTimeline({});
        authoring.upsertTransformKeyframe(tl, 'e-1', 0);
        authoring.upsertTransformKeyframe(tl, 'e-1', 500);
        expect(sendKeyframeAdd).toHaveBeenCalledTimes(12);
    });
});
