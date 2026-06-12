// @vitest-environment happy-dom
/**
 * 0.7.1-P3-T3：PreviewPane 渲染 smoke。
 *
 * <p>真实 mount：(1) 空态 project.state==null → 占位「未选择墙」+ 不调 renderProjectState；
 * (2) 有墙 state → canvas.getContext('2d') 被调 + renderProjectState(ctx, state) 被调。
 * happy-dom 的 canvas 不实装 2D context，故 stub getContext 返假 ctx；RAF 同步化让重绘当帧发生。</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

// renderProjectState mock —— 断言被调（以及调用入参）。
// vi.hoisted：mock 工厂会被提升到文件顶，普通顶层 const 在工厂里访问会 TDZ；用 hoisted 让
// fn 与工厂同被提升。PreviewRenderer 还被 LayerThumbnailRenderer 引用，必须整体 mock 掉。
const { renderProjectState } = vi.hoisted(() => ({ renderProjectState: vi.fn() }));
vi.mock('@/render/PreviewRenderer', () => ({
    renderProjectState,
    // project store reset() 会调 resetImageCaches；保留为 noop 避免 import 崩。
    resetImageCaches: () => {},
}));

import PreviewPane from '../PreviewPane.vue';
import { useUiStore } from '@/stores/ui';
import { useProjectStore } from '@/stores/project';
import type { ProjectState } from '@/types/protocol';

// RAF 同步化：让 requestPaint 当帧执行 paint。
const realRaf = globalThis.requestAnimationFrame;
const realCaf = globalThis.cancelAnimationFrame;
// 假 2D ctx：getContext 命中即记一次，断言用。
const fakeCtx = {} as CanvasRenderingContext2D;
let getContextSpy: ReturnType<typeof vi.spyOn>;

function fakeState(): ProjectState {
    return {
        version: 1,
        canvas: { widthMaps: 2, heightMaps: 1, background: '#FFFFFF' },
        layers: [{ id: 'l-1', name: 'L', visible: true, locked: false, opacity: 1, blendMode: 'normal', colorTag: null, elements: [] }],
        activeLayerId: 'l-1',
    } as unknown as ProjectState;
}

describe('PreviewPane 渲染 smoke（0.7.1-P3-T3）', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
        renderProjectState.mockClear();
        vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => { cb(0); return 1; });
        vi.stubGlobal('cancelAnimationFrame', () => {});
        getContextSpy = vi.spyOn(HTMLCanvasElement.prototype, 'getContext')
            .mockReturnValue(fakeCtx as unknown as RenderingContext);
    });
    afterEach(() => {
        getContextSpy.mockRestore();
        vi.stubGlobal('requestAnimationFrame', realRaf);
        vi.stubGlobal('cancelAnimationFrame', realCaf);
    });

    it('空态：占位「未选择墙」+ 不渲染', async () => {
        const wrapper = mount(PreviewPane);
        await nextTick();
        expect(wrapper.text()).toContain('未选择墙');
        expect(renderProjectState).not.toHaveBeenCalled();
        wrapper.unmount();
    });

    it('有墙 state：getContext(2d) + renderProjectState(ctx, state) 被调', async () => {
        const project = useProjectStore();
        const st = fakeState();
        project.setSnapshot(st);
        const wrapper = mount(PreviewPane);
        await nextTick();
        // canvas 拿到 2D 上下文
        expect(getContextSpy).toHaveBeenCalledWith('2d');
        // 渲染当前墙：第一个参数是 ctx，第二个是 state
        expect(renderProjectState).toHaveBeenCalled();
        const [ctxArg, stateArg] = renderProjectState.mock.calls[renderProjectState.mock.calls.length - 1];
        expect(ctxArg).toBe(fakeCtx);
        expect(stateArg).toBe(project.state);
        // 占位文字不再出现
        expect(wrapper.text()).not.toContain('未选择墙');
        wrapper.unmount();
    });

    it('标题渲染（t.value.script.preview.title 解包）', async () => {
        const wrapper = mount(PreviewPane);
        await nextTick();
        expect(wrapper.text()).toContain('预览');
        wrapper.unmount();
    });
});
