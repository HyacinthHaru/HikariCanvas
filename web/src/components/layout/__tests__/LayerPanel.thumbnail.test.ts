// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { ProjectState } from '@/types/protocol';

const {
    thumbRenderSpy,
    clearThumbSpy,
    fontHooks,
    iconHooks,
    metricsHooks,
    imageHooks,
    paletteHooks,
    unsubCounts,
} = vi.hoisted(() => ({
    thumbRenderSpy: vi.fn<(_state: ProjectState | null, layer: { id: string }) => string | null>(),
    clearThumbSpy: vi.fn(),
    fontHooks: [] as Array<(fontId: string) => void>,
    iconHooks: [] as Array<(id: string) => void>,
    metricsHooks: [] as Array<() => void>,
    imageHooks: [] as Array<() => void>,
    paletteHooks: [] as Array<() => void>,
    unsubCounts: { font: 0, icon: 0, metrics: 0, image: 0, palette: 0 },
}));

vi.mock('@/render/LayerThumbnailRenderer', () => ({
    renderLayerThumbnail: (state: ProjectState | null, layer: { id: string }) => thumbRenderSpy(state, layer),
    clearLayerThumbnailCache: () => clearThumbSpy(),
}));

vi.mock('@/render/FontLoader', () => ({
    onFontLoaded: (fn: (fontId: string) => void) => {
        fontHooks.push(fn);
        return () => {
            unsubCounts.font++;
            const i = fontHooks.indexOf(fn);
            if (i >= 0) fontHooks.splice(i, 1);
        };
    },
}));

vi.mock('@/render/IconLoader', () => ({
    onIconLoaded: (fn: (id: string) => void) => {
        iconHooks.push(fn);
        return () => {
            unsubCounts.icon++;
            const i = iconHooks.indexOf(fn);
            if (i >= 0) iconHooks.splice(i, 1);
        };
    },
}));

vi.mock('@/render/GlyphMetricsLut', () => ({
    onMetricsReady: (fn: () => void) => {
        metricsHooks.push(fn);
        return () => {
            unsubCounts.metrics++;
            const i = metricsHooks.indexOf(fn);
            if (i >= 0) metricsHooks.splice(i, 1);
        };
    },
}));

vi.mock('@/render/PreviewRenderer', () => ({
    onImageReady: (fn: () => void) => {
        imageHooks.push(fn);
        return () => {
            unsubCounts.image++;
            const i = imageHooks.indexOf(fn);
            if (i >= 0) imageHooks.splice(i, 1);
        };
    },
    onPaletteReady: (fn: () => void) => {
        paletteHooks.push(fn);
        return () => {
            unsubCounts.palette++;
            const i = paletteHooks.indexOf(fn);
            if (i >= 0) paletteHooks.splice(i, 1);
        };
    },
    resetImageCaches: vi.fn(),
}));

vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({ send: vi.fn() }),
}));

import LayerPanel from '../LayerPanel.vue';
import { useProjectStore } from '@/stores/project';
import { useVariableStore } from '@/stores/variables';

function makeProjectState(): ProjectState {
    const l1 = {
        id: 'layer-1',
        name: 'Background',
        visible: true,
        locked: false,
        opacity: 1,
        blendMode: 'normal' as const,
        elements: [],
    };
    const l2 = {
        id: 'layer-2',
        name: 'Foreground',
        visible: true,
        locked: false,
        opacity: 1,
        blendMode: 'normal' as const,
        elements: [],
    };
    return {
        version: 1,
        protocolVersion: 1,
        canvas: {
            widthMaps: 2,
            heightMaps: 1,
            background: { type: 'solid', color: '#ffffff' },
            gridSize: 0,
        },
        layers: [l1, l2],
        elements: l2.elements,
        activeLayerId: 'layer-2',
        history: { undoDepth: 0, redoDepth: 0 },
    };
}

describe('LayerPanel — 缩略图单遍计算与异步资源失效刷新', () => {
    let rafQueue: FrameRequestCallback[] = [];
    let nextRafHandle = 1;

    beforeEach(() => {
        setActivePinia(createPinia());
        rafQueue = [];
        nextRafHandle = 1;
        thumbRenderSpy.mockReset();
        clearThumbSpy.mockReset();
        fontHooks.length = 0;
        iconHooks.length = 0;
        metricsHooks.length = 0;
        imageHooks.length = 0;
        paletteHooks.length = 0;
        unsubCounts.font = 0;
        unsubCounts.icon = 0;
        unsubCounts.metrics = 0;
        unsubCounts.image = 0;
        unsubCounts.palette = 0;

        vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
            rafQueue.push(cb);
            return nextRafHandle++;
        });
        vi.stubGlobal('cancelAnimationFrame', () => {
            rafQueue.length = 0;
        });
    });

    afterEach(() => {
        vi.unstubAllGlobals();
        vi.restoreAllMocks();
    });

    function flushRaf(): void {
        const callbacks = [...rafQueue];
        rafQueue.length = 0;
        for (const cb of callbacks) cb(16);
    }

    it('每轮渲染每层只计算一次缩略图；同帧多个异步资源回调合并为单次 rAF 刷新并清理缓存', async () => {
        let generation = 1;
        thumbRenderSpy.mockImplementation((_state, layer) => `data:image/png;base64,${layer.id}-v${generation}`);

        const project = useProjectStore();
        project.state = makeProjectState();

        const wrapper = mount(LayerPanel);
        // 2 个图层：首次挂载只各算一次（无 v-if / :src 重复调用）
        expect(thumbRenderSpy).toHaveBeenCalledTimes(2);
        const initialImgs = wrapper.findAll('.hc-layer-thumb img');
        expect(initialImgs).toHaveLength(2);
        expect(initialImgs[0].attributes('src')).toBe('data:image/png;base64,layer-2-v1');
        expect(initialImgs[1].attributes('src')).toBe('data:image/png;base64,layer-1-v1');

        // 同帧内连续触发字体、图片、调色板就绪信号 → 仅入队一个 rAF
        generation = 2;
        fontHooks[0]?.('inter');
        imageHooks[0]?.();
        paletteHooks[0]?.();
        expect(rafQueue).toHaveLength(1);

        flushRaf();
        await wrapper.vm.$nextTick();

        expect(clearThumbSpy).toHaveBeenCalledTimes(1);
        expect(thumbRenderSpy).toHaveBeenCalledTimes(4);
        const refreshedImgs = wrapper.findAll('.hc-layer-thumb img');
        expect(refreshedImgs[0].attributes('src')).toBe('data:image/png;base64,layer-2-v2');
        expect(refreshedImgs[1].attributes('src')).toBe('data:image/png;base64,layer-1-v2');

        // 变量更新同样触发下一帧缩略图刷新
        generation = 3;
        const variableStore = useVariableStore();
        variableStore.variables = new Map(variableStore.variables);
        await wrapper.vm.$nextTick();
        expect(rafQueue).toHaveLength(1);
        flushRaf();
        await wrapper.vm.$nextTick();
        expect(clearThumbSpy).toHaveBeenCalledTimes(2);
        expect(wrapper.findAll('.hc-layer-thumb img')[0].attributes('src'))
            .toBe('data:image/png;base64,layer-2-v3');

        // 卸载后所有订阅全部注销，再发信号不再刷新
        wrapper.unmount();
        expect(unsubCounts).toEqual({ font: 1, icon: 1, metrics: 1, image: 1, palette: 1 });
        expect(fontHooks).toHaveLength(0);
        expect(imageHooks).toHaveLength(0);
    });
});
