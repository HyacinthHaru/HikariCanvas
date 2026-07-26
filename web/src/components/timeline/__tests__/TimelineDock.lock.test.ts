// @vitest-environment happy-dom
/**
 * 两条守卫：
 *
 * ① 画板锁定时时间轴不能改。CLAUDE.md 定的是"前端是锁的唯一执行者"，后端按纪律不看 lock，
 *    而整个 timeline 模块以前一处 isLocked 都没引用——锁定的作品照样能删帧、改时长。
 * ② 退格键的输入焦点判断要认 contenteditable。画布内联文本编辑器和右栏文本框都是
 *    Lexical 的 contenteditable（根节点是 div），只判 tagName 会漏：用户在文本框里删字，
 *    字没删掉，选中的关键帧反倒全没了。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

const sendKeyframeDelete = vi.fn().mockResolvedValue(undefined);
const sendKeyframeUpdate = vi.fn().mockResolvedValue(undefined);
const sendTimelineUpdate = vi.fn().mockResolvedValue(undefined);
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({
        sendTimelineCreate: vi.fn().mockResolvedValue(undefined),
        sendTimelineUpdate,
        sendTimelineDelete: vi.fn().mockResolvedValue(undefined),
        sendKeyframeAdd: vi.fn().mockResolvedValue(undefined),
        sendKeyframeUpdate,
        sendKeyframeDelete,
        sendKeyframeMove: vi.fn().mockResolvedValue(undefined),
    }),
}));

import TimelineDock from '../TimelineDock.vue';
import { transformKeyframeKey } from '../timelineLogic';
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
            trigger: { type: 'manual', params: {} },
            tracks: {
                'e-1': [
                    { id: 'kx', property: 'x', timeMs: 0, value: 0, easing: { type: 'linear' } },
                    { id: 'ky', property: 'y', timeMs: 0, value: 0, easing: { type: 'linear' } },
                ],
            },
        }],
        activeTimelineId: 'tl-1',
    } as unknown as ProjectState;
}

/** 挂 dock + 选中 e-1 在 t=0 的整体关键帧。 */
async function mountWithSelectedGroup() {
    useProjectStore().setSnapshot(makeState());
    useUiStore().selectMany(['e-1']);
    const store = useTimelineStore();
    store.openDock();
    const wrapper = mount(TimelineDock, { attachTo: document.body });
    await nextTick();
    store.selectGroup(transformKeyframeKey('e-1', 0));
    await nextTick();
    return wrapper;
}

function pressDelete(target: EventTarget): void {
    target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete', bubbles: true, cancelable: true }));
}

describe('TimelineDock 锁定守卫', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
        sendKeyframeDelete.mockClear();
        sendKeyframeUpdate.mockClear();
        sendTimelineUpdate.mockClear();
        document.body.innerHTML = '';
    });

    it('未锁定时按 Delete 正常删关键帧（回归守卫）', async () => {
        await mountWithSelectedGroup();
        pressDelete(document.body);
        expect(sendKeyframeDelete).toHaveBeenCalled();
    });

    it('画板锁定时按 Delete 一条删除都不发', async () => {
        await mountWithSelectedGroup();
        useProjectStore().lockedAt = Date.now();
        await nextTick();
        pressDelete(document.body);
        expect(sendKeyframeDelete).not.toHaveBeenCalled();
    });

    it('画板锁定时设置里的时长 / 帧率输入被禁用', async () => {
        const wrapper = await mountWithSelectedGroup();
        useProjectStore().lockedAt = Date.now();
        await nextTick();
        await wrapper.find('[title="时间轴设置"]').trigger('click');
        await nextTick();
        const numberInputs = wrapper.findAll('input[type="number"]');
        expect(numberInputs.length).toBeGreaterThan(0);
        expect(numberInputs.every(i => (i.element as HTMLInputElement).disabled)).toBe(true);
    });
});

describe('TimelineDock 退格键的输入焦点判断', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
        sendKeyframeDelete.mockClear();
        document.body.innerHTML = '';
    });

    it('焦点在 contenteditable（Lexical 文本编辑器）里时不删关键帧', async () => {
        await mountWithSelectedGroup();
        const editor = document.createElement('div');
        // happy-dom 不一定实现 isContentEditable，直接定义成真实浏览器里的形态
        Object.defineProperty(editor, 'isContentEditable', { value: true });
        document.body.appendChild(editor);
        pressDelete(editor);
        expect(sendKeyframeDelete).not.toHaveBeenCalled();
    });

    it('焦点在普通 input 里时同样不删（原有行为不回退）', async () => {
        await mountWithSelectedGroup();
        const input = document.createElement('input');
        document.body.appendChild(input);
        pressDelete(input);
        expect(sendKeyframeDelete).not.toHaveBeenCalled();
    });
});
