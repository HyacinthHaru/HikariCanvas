// @vitest-environment happy-dom
/**
 * 0.6 P4 hotfix-2 回归：TimelineDock / EasingCurveEditor 渲染 smoke。
 *
 * 真实 mount 组件并触发"展开属性行 / 打开设置 / 渲染缓动预设"——这些路径调 propertyLabel /
 * loopModeLabel / presetLabel，内部访问 `useI18n()` 的 t（ComputedRef）。若 script 里漏写 `.value`
 * （`t.timeline` 而非 `t.value.timeline`），渲染时 `m['propX']` / `x.loopOnce` 抛 undefined → 整个
 * 组件崩溃消失（hotfix-2 的真 bug）。vite build 只转译不查类型、vitest 纯函数测试都抓不到，只有
 * 这种组件渲染 smoke 能在 CI 拦住。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({
        sendTimelineCreate: vi.fn().mockResolvedValue(undefined),
        sendTimelineUpdate: vi.fn().mockResolvedValue(undefined),
        sendTimelineDelete: vi.fn().mockResolvedValue(undefined),
        sendKeyframeAdd: vi.fn().mockResolvedValue(undefined),
        sendKeyframeUpdate: vi.fn().mockResolvedValue(undefined),
        sendKeyframeDelete: vi.fn().mockResolvedValue(undefined),
        sendKeyframeMove: vi.fn().mockResolvedValue(undefined),
    }),
}));

import TimelineDock from '../TimelineDock.vue';
import EasingCurveEditor from '../EasingCurveEditor.vue';
import { useProjectStore } from '@/stores/project';
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
            trigger: { type: 'manual', params: {} }, tracks: {},
        }],
        activeTimelineId: 'tl-1',
    } as unknown as ProjectState;
}

/** 带一个整体关键帧（e-1 的 x/y/opacity @ t=0）的 state，用于测整体帧渲染/选中。 */
function makeStateWithKf(): ProjectState {
    const s = makeState() as unknown as { timelines: { tracks: Record<string, unknown> }[] };
    s.timelines[0].tracks = {
        'e-1': [
            { id: 'kx', property: 'x', timeMs: 0, value: 0, easing: { type: 'linear' } },
            { id: 'ky', property: 'y', timeMs: 0, value: 0, easing: { type: 'linear' } },
            { id: 'ko', property: 'opacity', timeMs: 0, value: 1, easing: { type: 'linear' } },
        ],
    };
    return s as unknown as ProjectState;
}

describe('TimelineDock 渲染 smoke（防 ComputedRef 解包崩溃）', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';   // 锁定 locale，下面断言中文文案（验证 t.value.timeline 解包）
    });

    it('选中元素 → 主体显示元素行（flatRows 接 ui.selectedIds）', async () => {
        useProjectStore().setSnapshot(makeState());
        useUiStore().selectMany(['e-1']);
        useTimelineStore().openDock();

        const wrapper = mount(TimelineDock);
        await nextTick();
        // elementLabel 是短形态 "1 · rect"（去 e- 前缀）——出现即证明 flatRows 接上了 ui.selectedIds
        expect(wrapper.text()).toContain('· rect');
    });

    it('展开元素 → 属性行渲染不崩，propertyLabel 正确解包 t.value.timeline', async () => {
        useProjectStore().setSnapshot(makeState());
        useUiStore().selectMany(['e-1']);
        const timeline = useTimelineStore();
        timeline.openDock();

        const wrapper = mount(TimelineDock);
        await nextTick();
        timeline.toggleExpanded('e-1');
        await nextTick();
        // 这两个属性名只有 propertyLabel 正确读到 t.value.timeline 才会出现；漏 .value 则此处崩
        expect(wrapper.text()).toContain('横坐标 X');     // propX
        expect(wrapper.text()).toContain('不透明度');     // propOpacity
    });

    it('打开设置 → loopModeLabel 正确解包，settings 不崩', async () => {
        useProjectStore().setSnapshot(makeState());
        useTimelineStore().openDock();

        const wrapper = mount(TimelineDock);
        await nextTick();
        const settingsBtn = wrapper.find('[title="时间轴设置"]');
        expect(settingsBtn.exists()).toBe(true);
        await settingsBtn.trigger('click');
        await nextTick();
        // settings popover 的 loop select 渲染了 option（loopModeLabel 不崩）
        expect(wrapper.findAll('option').length).toBeGreaterThanOrEqual(3);
    });

    it('整体关键帧块渲染 + pointer 点选不崩（P4.5 / P4.5b 块改 pointer 拖动）', async () => {
        useProjectStore().setSnapshot(makeStateWithKf());
        useUiStore().selectMany(['e-1']);
        const timeline = useTimelineStore();
        timeline.openDock();
        const wrapper = mount(TimelineDock);
        await nextTick();
        timeline.setPxPerMs(0.1);   // happy-dom 无布局 → 手动给缩放，让块 pointer 选中路径可走
        // element row 把 x/y/opacity @ t=0 聚合成一个整体关键帧块（菱形 rotate-45）；P4.5b 选中走 pointerup
        const blocks = wrapper.findAll('.rotate-45');
        expect(blocks.length).toBeGreaterThan(0);
        await blocks[0].trigger('pointerdown', { clientX: 50, button: 0 });
        await blocks[0].trigger('pointerup', { clientX: 50, button: 0 });   // 同 X → moved=false → 当点击
        await nextTick();
        expect(timeline.selectedGroups.has('e-1:0')).toBe(true);
    });

    it('展开后点击 per-property 关键帧 → selectKeyframe（P4.5b）', async () => {
        useProjectStore().setSnapshot(makeStateWithKf());
        useUiStore().selectMany(['e-1']);
        const timeline = useTimelineStore();
        timeline.openDock();
        const wrapper = mount(TimelineDock);
        await nextTick();
        timeline.toggleExpanded('e-1');
        await nextTick();
        // per-property 块用 cursor-pointer（整体帧块是 cursor-ew-resize），点其一 → 选中单帧
        const propBlocks = wrapper.findAll('.cursor-pointer.rotate-45');
        expect(propBlocks.length).toBeGreaterThan(0);
        await propBlocks[0].trigger('click');
        expect(timeline.selectedKeyframeId).toBeTruthy();
        expect(timeline.selectedGroups.size).toBe(0);   // 与整体帧选中互斥
    });

    it('加帧按钮选中新整体帧，不崩（P4.5）', async () => {
        useProjectStore().setSnapshot(makeStateWithKf());
        useUiStore().selectMany(['e-1']);
        const timeline = useTimelineStore();
        timeline.openDock();
        const wrapper = mount(TimelineDock);
        await nextTick();
        const addBtn = wrapper.find('[title="在播放头给这个元素打一个关键帧"]');
        expect(addBtn.exists()).toBe(true);
        await addBtn.trigger('click');
        await nextTick();
        expect(timeline.selectedGroups.has('e-1:0')).toBe(true);
    });

    it('自动加帧 toggle 切 store.autoKeyframe（P4.5b）', async () => {
        useProjectStore().setSnapshot(makeState());
        const timeline = useTimelineStore();
        timeline.openDock();
        const wrapper = mount(TimelineDock);
        await nextTick();
        expect(timeline.autoKeyframe).toBe(true);   // 默认开
        const btn = wrapper.find('[title^="自动加帧"]');
        expect(btn.exists()).toBe(true);
        await btn.trigger('click');
        expect(timeline.autoKeyframe).toBe(false);
    });
});

describe('EasingCurveEditor 渲染 smoke', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';   // 锁定 locale，下面断言中文文案（验证 t.value.timeline 解包）
    });

    it('预设按钮渲染不崩，presetLabel 正确解包 t.value.timeline', () => {
        const wrapper = mount(EasingCurveEditor, {
            props: { modelValue: { type: 'linear' } },
        });
        expect(wrapper.findAll('button').length).toBeGreaterThanOrEqual(4);
        expect(wrapper.text()).toContain('匀速');   // easingLinear
    });
});
