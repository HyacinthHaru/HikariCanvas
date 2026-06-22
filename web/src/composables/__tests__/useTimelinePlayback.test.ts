import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { effectScope } from 'vue';
import { useTimelinePlayback } from '../useTimelinePlayback';
import { useProjectStore } from '@/stores/project';
import { useTimelineStore } from '@/stores/timeline';
import type { ProjectState, Timeline } from '@/types/protocol';

function makeTimeline(): Timeline {
    return {
        id: 'tl-deadbeef',
        name: 'tl',
        durationMs: 1000,
        fps: 20,
        loopMode: 'loop',
        trigger: { type: 'manual', params: {} },
        tracks: {},
    };
}

function seedActiveTimeline() {
    const project = useProjectStore();
    project.state = {
        timelines: [makeTimeline()],
        activeTimelineId: 'tl-deadbeef',
    } as unknown as ProjectState;
}

describe('useTimelinePlayback', () => {
    let rafCount = 0;

    beforeEach(() => {
        setActivePinia(createPinia());
        vi.useFakeTimers();
        rafCount = 0;
        // 桩 rAF：只计数，不回调（不让 tick 跑），用来观测调度次数。
        vi.stubGlobal('requestAnimationFrame', () => ++rafCount);
        vi.stubGlobal('cancelAnimationFrame', () => {});
    });

    afterEach(() => {
        vi.unstubAllGlobals();
        vi.useRealTimers();
    });

    it('does not double-schedule rAF when play() is called twice', () => {
        const scope = effectScope();
        scope.run(() => {
            seedActiveTimeline();
            const pb = useTimelinePlayback();
            pb.play();
            pb.play();
            expect(rafCount).toBe(1);
        });
        scope.stop();
    });

    it('second play() while already playing short-circuits before touching playback state', () => {
        const scope = effectScope();
        scope.run(() => {
            seedActiveTimeline();
            const tlStore = useTimelineStore();
            const pb = useTimelinePlayback();
            pb.play();
            expect(rafCount).toBe(1);
            expect(tlStore.playing).toBe(true);
            // 前置幂等闸：playing 且 rafId 非 null 时，再 play() 不重置播放态（不再调 setPlaying/setPreviewActive）。
            const setPlayingSpy = vi.spyOn(tlStore, 'setPlaying');
            const setPreviewSpy = vi.spyOn(tlStore, 'setPreviewActive');
            pb.play();
            pb.play();
            expect(rafCount).toBe(1);
            expect(setPlayingSpy).not.toHaveBeenCalled();
            expect(setPreviewSpy).not.toHaveBeenCalled();
        });
        scope.stop();
    });

    it('play() reschedules after stop()', () => {
        const scope = effectScope();
        scope.run(() => {
            seedActiveTimeline();
            const pb = useTimelinePlayback();
            pb.play();
            expect(rafCount).toBe(1);
            pb.stop();
            pb.play();
            expect(rafCount).toBe(2);
        });
        scope.stop();
    });
});
