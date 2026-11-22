/**
 * project store 的 state.patch 分支单测：
 * - /canvas/<field>（含 NON_NULL 缺省 value 置空）
 * - /timelines 与 /activeTimelineId 全路径增删改与容错
 */
import { describe, expect, it, beforeEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useProjectStore } from '../project';
import type { ProjectState, Timeline, Keyframe, PatchOp } from '@/types/protocol';

beforeEach(() => {
    setActivePinia(createPinia());
});

function stateWithGrid(gridSize?: number): ProjectState {
    return {
        version: 0,
        protocolVersion: 3,
        canvas: { widthMaps: 1, heightMaps: 1, background: '#FFFFFF', gridSize },
        layers: [{
            id: 'l-0', name: 'Layer 0', visible: true, locked: false,
            opacity: 1, blendMode: 'normal', colorTag: null, elements: [],
        }],
        activeLayerId: 'l-0',
        history: { undoDepth: 0, redoDepth: 0 },
    };
}

function minimalState(): ProjectState {
    return stateWithGrid(undefined);
}

function makeTimeline(overrides: Partial<Timeline> = {}): Timeline {
    return {
        id: 'tl-1',
        name: 'Intro',
        durationMs: 5000,
        fps: 20,
        loopMode: 'loop',
        trigger: { type: 'manual', params: {} },
        tracks: {},
        ...overrides,
    };
}

function makeKeyframe(overrides: Partial<Keyframe> = {}): Keyframe {
    return {
        id: 'kf-1',
        property: 'x',
        timeMs: 0,
        value: 0,
        easing: { type: 'linear' },
        ...overrides,
    };
}

function apply(store: ReturnType<typeof useProjectStore>, ops: PatchOp[], version = 1): ProjectState {
    store.applyPatch(version, ops);
    return store.state!;
}

// ---------- /canvas/<field> patch ----------

describe('/canvas/<field> patch', () => {
    it('replace 带值 → 正常写入', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithGrid(8));
        project.applyPatch(1, [
            { op: 'replace', path: '/canvas/gridSize', value: 16 } as PatchOp,
        ]);
        expect(project.state?.canvas.gridSize).toBe(16);
    });

    it('replace 的 value 缺席（NON_NULL 省掉）→ 字段清空，网格真的关得掉', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithGrid(8));
        project.applyPatch(1, [
            { op: 'replace', path: '/canvas/gridSize' } as unknown as PatchOp,
        ]);
        expect(project.state?.canvas.gridSize).toBeUndefined();
    });

    it('remove → 字段清空', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithGrid(8));
        project.applyPatch(1, [
            { op: 'remove', path: '/canvas/gridSize' } as PatchOp,
        ]);
        expect(project.state?.canvas.gridSize).toBeUndefined();
    });

    it('background 等非空字段不受影响', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithGrid(8));
        project.applyPatch(1, [
            { op: 'replace', path: '/canvas/background', value: '#101010' } as PatchOp,
        ]);
        expect(project.state?.canvas.background).toBe('#101010');
        expect(project.state?.canvas.gridSize).toBe(8);
    });
});

// ---------- 时间轴 patch ----------

describe('project store 时间轴 patch', () => {
    it('首条 timeline add：timelines 数组懒初始化', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        expect(store.state!.timelines).toBeUndefined();

        const tl = makeTimeline();
        const s = apply(store, [
            { op: 'add', path: '/timelines/0', value: tl },
            { op: 'replace', path: '/activeTimelineId', value: 'tl-1' },
        ]);

        expect(s.timelines).toHaveLength(1);
        expect(s.timelines![0].id).toBe('tl-1');
        expect(s.activeTimelineId).toBe('tl-1');
        expect(s.version).toBe(1);
    });

    it('timeline add 追加到 length（idx === length 允许）', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        apply(store, [{ op: 'add', path: '/timelines/0', value: makeTimeline({ id: 'tl-1' }) }]);
        apply(store, [{ op: 'add', path: '/timelines/1', value: makeTimeline({ id: 'tl-2' }) }], 2);
        expect(store.state!.timelines!.map((t) => t.id)).toEqual(['tl-1', 'tl-2']);
    });

    it('timeline add 越界 idx（> length）不插入、不 throw', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        expect(() =>
            apply(store, [{ op: 'add', path: '/timelines/5', value: makeTimeline() }]),
        ).not.toThrow();
        expect(store.state!.timelines).toBeUndefined();
    });

    it('timeline remove + activeTimelineId replace null', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        apply(store, [
            { op: 'add', path: '/timelines/0', value: makeTimeline() },
            { op: 'replace', path: '/activeTimelineId', value: 'tl-1' },
        ]);
        const s = apply(store, [
            { op: 'remove', path: '/timelines/0' },
            { op: 'replace', path: '/activeTimelineId', value: null },
        ], 2);
        expect(s.timelines).toHaveLength(0);
        expect(s.activeTimelineId).toBeUndefined();
    });

    it('timeline replace 整项', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        apply(store, [{ op: 'add', path: '/timelines/0', value: makeTimeline({ name: 'Old' }) }]);
        const s = apply(store, [
            { op: 'replace', path: '/timelines/0', value: makeTimeline({ name: 'New' }) },
        ], 2);
        expect(s.timelines![0].name).toBe('New');
    });

    it('timeline remove 越界不 throw', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        apply(store, [{ op: 'add', path: '/timelines/0', value: makeTimeline() }]);
        expect(() => apply(store, [{ op: 'remove', path: '/timelines/9' }], 2)).not.toThrow();
        expect(store.state!.timelines).toHaveLength(1);
    });

    it('activeTimelineId replace 字符串与 remove', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        apply(store, [{ op: 'replace', path: '/activeTimelineId', value: 'tl-x' }]);
        expect(store.state!.activeTimelineId).toBe('tl-x');
        apply(store, [{ op: 'remove', path: '/activeTimelineId' }], 2);
        expect(store.state!.activeTimelineId).toBeUndefined();
    });

    it('timeline field replace（durationMs / loopMode / trigger）', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        apply(store, [{ op: 'add', path: '/timelines/0', value: makeTimeline() }]);
        const s = apply(store, [
            { op: 'replace', path: '/timelines/0/durationMs', value: 8000 },
            { op: 'replace', path: '/timelines/0/loopMode', value: 'pingPong' },
            { op: 'replace', path: '/timelines/0/trigger', value: { type: 'variableChange', params: { fullName: 'user/x' } } },
        ], 2);
        expect(s.timelines![0].durationMs).toBe(8000);
        expect(s.timelines![0].loopMode).toBe('pingPong');
        expect(s.timelines![0].trigger).toEqual({ type: 'variableChange', params: { fullName: 'user/x' } });
    });

    it('timeline field replace 指向不存在 timeline 不 throw', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        expect(() =>
            apply(store, [{ op: 'replace', path: '/timelines/0/durationMs', value: 9000 }]),
        ).not.toThrow();
    });

    it('整轨 add / replace / remove（len4）', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        apply(store, [{ op: 'add', path: '/timelines/0', value: makeTimeline() }]);

        const track = [makeKeyframe({ id: 'kf-1', timeMs: 0 }), makeKeyframe({ id: 'kf-2', timeMs: 500 })];
        apply(store, [{ op: 'add', path: '/timelines/0/tracks/e-abc', value: track }], 2);
        expect(store.state!.timelines![0].tracks['e-abc']).toHaveLength(2);

        const replaced = [makeKeyframe({ id: 'kf-3', timeMs: 100 })];
        apply(store, [{ op: 'replace', path: '/timelines/0/tracks/e-abc', value: replaced }], 3);
        expect(store.state!.timelines![0].tracks['e-abc'].map((k) => k.id)).toEqual(['kf-3']);

        apply(store, [{ op: 'remove', path: '/timelines/0/tracks/e-abc' }], 4);
        expect(store.state!.timelines![0].tracks['e-abc']).toBeUndefined();
    });

    it('关键帧项 add 到边界 idx + remove + replace（len5）', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        apply(store, [{ op: 'add', path: '/timelines/0', value: makeTimeline() }]);
        apply(store, [{ op: 'add', path: '/timelines/0/tracks/e-1', value: [makeKeyframe({ id: 'kf-a', timeMs: 0 })] }], 2);

        apply(store, [{ op: 'add', path: '/timelines/0/tracks/e-1/1', value: makeKeyframe({ id: 'kf-b', timeMs: 200 }) }], 3);
        expect(store.state!.timelines![0].tracks['e-1'].map((k) => k.id)).toEqual(['kf-a', 'kf-b']);

        apply(store, [{ op: 'replace', path: '/timelines/0/tracks/e-1/0', value: makeKeyframe({ id: 'kf-a2', timeMs: 50 }) }], 4);
        expect(store.state!.timelines![0].tracks['e-1'][0].id).toBe('kf-a2');

        apply(store, [{ op: 'remove', path: '/timelines/0/tracks/e-1/1' }], 5);
        expect(store.state!.timelines![0].tracks['e-1'].map((k) => k.id)).toEqual(['kf-a2']);
    });

    it('关键帧项 add 到不存在的轨（track ??= [] 防御）', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        apply(store, [{ op: 'add', path: '/timelines/0', value: makeTimeline() }]);
        apply(store, [{ op: 'add', path: '/timelines/0/tracks/e-new/0', value: makeKeyframe({ id: 'kf-z' }) }], 2);
        expect(store.state!.timelines![0].tracks['e-new'].map((k) => k.id)).toEqual(['kf-z']);
    });

    it('关键帧项越界 idx 不 throw（add > length / remove >= length）', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        apply(store, [{ op: 'add', path: '/timelines/0', value: makeTimeline() }]);
        apply(store, [{ op: 'add', path: '/timelines/0/tracks/e-1', value: [makeKeyframe({ id: 'kf-a' })] }], 2);

        expect(() =>
            apply(store, [{ op: 'add', path: '/timelines/0/tracks/e-1/9', value: makeKeyframe() }], 3),
        ).not.toThrow();
        expect(() =>
            apply(store, [{ op: 'remove', path: '/timelines/0/tracks/e-1/9' }], 4),
        ).not.toThrow();
        expect(store.state!.timelines![0].tracks['e-1']).toHaveLength(1);
    });

    it('关键帧字段 replace（timeMs / value / easing，len6）', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        apply(store, [{ op: 'add', path: '/timelines/0', value: makeTimeline() }]);
        apply(store, [{ op: 'add', path: '/timelines/0/tracks/e-1', value: [makeKeyframe({ id: 'kf-a', timeMs: 0, value: 0 })] }], 2);

        apply(store, [
            { op: 'replace', path: '/timelines/0/tracks/e-1/0/timeMs', value: 300 },
            { op: 'replace', path: '/timelines/0/tracks/e-1/0/value', value: 42 },
            { op: 'replace', path: '/timelines/0/tracks/e-1/0/easing', value: { type: 'cubicBezier', bezier: [0.25, 0.1, 0.25, 1] } },
        ], 3);
        const kf = store.state!.timelines![0].tracks['e-1'][0];
        expect(kf.timeMs).toBe(300);
        expect(kf.value).toBe(42);
        expect(kf.easing).toEqual({ type: 'cubicBezier', bezier: [0.25, 0.1, 0.25, 1] });
    });

    it('关键帧字段 replace 指向不存在 kf 不 throw', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        apply(store, [{ op: 'add', path: '/timelines/0', value: makeTimeline() }]);
        apply(store, [{ op: 'add', path: '/timelines/0/tracks/e-1', value: [makeKeyframe()] }], 2);
        expect(() =>
            apply(store, [{ op: 'replace', path: '/timelines/0/tracks/e-1/9/timeMs', value: 1 }], 3),
        ).not.toThrow();
    });

    it('eid 含 JSON Pointer 转义段（~1 → /、~0 → ~）解码', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        apply(store, [{ op: 'add', path: '/timelines/0', value: makeTimeline() }]);
        apply(store, [{ op: 'add', path: '/timelines/0/tracks/a~1b~0c', value: [makeKeyframe({ id: 'kf-esc' })] }], 2);
        const tracks = store.state!.timelines![0].tracks;
        expect(tracks['a/b~c']).toBeDefined();
        expect(tracks['a/b~c'].map((k) => k.id)).toEqual(['kf-esc']);
    });

    it('timeline 缺失时 tracks / keyframe patch 静默 return 不 throw', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        expect(() =>
            apply(store, [
                { op: 'add', path: '/timelines/0/tracks/e-1', value: [makeKeyframe()] },
                { op: 'replace', path: '/timelines/0/tracks/e-1/0/timeMs', value: 1 },
            ]),
        ).not.toThrow();
        expect(store.state!.timelines).toBeUndefined();
    });

    it('track 缺失时关键帧项 remove / replace 静默 return', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        apply(store, [{ op: 'add', path: '/timelines/0', value: makeTimeline() }]);
        expect(() =>
            apply(store, [
                { op: 'remove', path: '/timelines/0/tracks/e-missing/0' },
                { op: 'replace', path: '/timelines/0/tracks/e-missing/0', value: makeKeyframe() },
            ], 2),
        ).not.toThrow();
        expect(store.state!.timelines![0].tracks['e-missing']).toBeUndefined();
    });

    it('负数 timeline idx 不 throw、不改 state', () => {
        const store = useProjectStore();
        store.setSnapshot(minimalState());
        apply(store, [{ op: 'add', path: '/timelines/0', value: makeTimeline() }]);
        expect(() =>
            apply(store, [{ op: 'replace', path: '/timelines/-1/durationMs', value: 1 }], 2),
        ).not.toThrow();
        expect(store.state!.timelines![0].durationMs).toBe(5000);
    });
});
