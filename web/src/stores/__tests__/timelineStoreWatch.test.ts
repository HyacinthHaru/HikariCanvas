/**
 * 时间轴 store 的两条自动清理，此前都没真的跑起来过。
 *
 * ① 切 wall 清编辑态：原先盯的是 `project.state === null`，而切 wall 的唯一路径里
 *    project.reset() 和 setSnapshot() 是同步挨着的两句，Vue 下一轮重算 getter 时看到的
 *    还是 false，回调一次都没进过 —— dock 开关 / 播放头 / 选中全部带到新墙上。
 * ② 时长缩短钳回播放头：时长改动的回声 patch 是原地改 activeTimeline 上的字段，
 *    computed 没读过 durationMs 就不重算，watch 也就不触发。
 */
import { describe, expect, it, beforeEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';
import { useProjectStore } from '../project';
import { useTimelineStore } from '../timeline';
import type { ProjectState, Timeline } from '@/types/protocol';

beforeEach(() => {
    setActivePinia(createPinia());
});

function makeTimeline(over: Partial<Timeline> = {}): Timeline {
    return {
        id: 'tl-1',
        name: 'Intro',
        durationMs: 5000,
        fps: 20,
        loopMode: 'loop',
        trigger: { type: 'manual', params: {} },
        tracks: {},
        ...over,
    };
}

function stateWithTimeline(tl: Timeline): ProjectState {
    return {
        version: 0,
        protocolVersion: 3,
        canvas: { widthMaps: 1, heightMaps: 1, background: '#FFFFFF' },
        layers: [{
            id: 'l-0', name: 'L', visible: true, locked: false, opacity: 1,
            blendMode: 'normal', colorTag: null, elements: [],
        }],
        activeLayerId: 'l-0',
        history: { undoDepth: 0, redoDepth: 0 },
        timelines: [tl],
        activeTimelineId: tl.id,
    } as unknown as ProjectState;
}

describe('切 wall 清时间轴编辑态', () => {
    it('换一面墙 → dock 关闭、播放头归零、选中清空', async () => {
        const project = useProjectStore();
        const timeline = useTimelineStore();
        project.setSnapshot(stateWithTimeline(makeTimeline()));
        project.setWallMeta('w-old', null, null, null, null);
        await nextTick();

        timeline.openDock();
        timeline.setPlayhead(1234);
        timeline.selectGroup('e-1:1234');
        timeline.toggleExpanded('e-1');
        await nextTick();

        // 切 wall：与 wsClient.handleReady 同样的顺序（先 reset 再灌新快照）
        project.reset();
        project.setSnapshot(stateWithTimeline(makeTimeline({ id: 'tl-2' })));
        project.setWallMeta('w-new', null, null, null, null);
        await nextTick();

        expect(timeline.dockOpen).toBe(false);
        expect(timeline.playheadMs).toBe(0);
        expect(timeline.selectedGroups.size).toBe(0);
        expect(timeline.expandedElements.size).toBe(0);
    });

    it('同一面墙重连不清编辑态（重连不该让 dock 闪一下）', async () => {
        const project = useProjectStore();
        const timeline = useTimelineStore();
        project.setSnapshot(stateWithTimeline(makeTimeline()));
        project.setWallMeta('w-same', null, null, null, null);
        await nextTick();

        timeline.openDock();
        timeline.setPlayhead(800);
        await nextTick();

        // 同 wall 重连：wsClient 不调 project.reset()，只重灌快照 + 同一个 wallId
        project.setSnapshot(stateWithTimeline(makeTimeline()));
        project.setWallMeta('w-same', null, null, null, null);
        await nextTick();

        expect(timeline.dockOpen).toBe(true);
        expect(timeline.playheadMs).toBe(800);
    });
});

describe('时长缩短把播放头钳回来', () => {
    it('原地改 durationMs 也能触发钳位', async () => {
        const project = useProjectStore();
        const timeline = useTimelineStore();
        const tl = makeTimeline({ durationMs: 5000 });
        project.setSnapshot(stateWithTimeline(tl));
        project.setWallMeta('w-1', null, null, null, null);
        await nextTick();

        timeline.setPlayhead(4800);
        expect(timeline.playheadMs).toBe(4800);

        // 后端 timeline.update 的回声 patch 就是这么落地的：原地改字段，不换对象
        project.applyPatch(1, [
            { op: 'replace', path: '/timelines/0/durationMs', value: 2000 },
        ]);
        await nextTick();

        expect(timeline.playheadMs).toBe(2000);
    });

    it('时长变长不动播放头', async () => {
        const project = useProjectStore();
        const timeline = useTimelineStore();
        project.setSnapshot(stateWithTimeline(makeTimeline({ durationMs: 5000 })));
        project.setWallMeta('w-1', null, null, null, null);
        await nextTick();

        timeline.setPlayhead(1000);
        project.applyPatch(1, [
            { op: 'replace', path: '/timelines/0/durationMs', value: 9000 },
        ]);
        await nextTick();

        expect(timeline.playheadMs).toBe(1000);
    });
});
