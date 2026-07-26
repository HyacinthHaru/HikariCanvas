// @vitest-environment happy-dom
/**
 * 笔触元素的宽高是采样点算出来的派生值，服务端 applyBrushPatch 没有 w / h 分支，
 * 收到就抛 "unknown brush field: w"，整条 patch 被拒。以前 Transformer 对笔触照挂锚点、
 * onTransformEnd 又无条件带上 w/h，于是"浏览器里缩放了、游戏里没动"。
 *
 * 本文件守两条：① 笔触不挂 Transformer；② 万一走到 onTransformEnd 也不发 w/h；
 * 外加一条通用的"几何没变就别发 op"。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ref } from 'vue';
import { setActivePinia, createPinia } from 'pinia';

const sendSpy = vi.fn(() => 'c-1');
vi.mock('@/network/wsClient', () => ({ getWsClient: () => ({ send: sendSpy }) }));

import { useTransformerManager } from '../useTransformerManager';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import type { Element, ProjectState } from '@/types/protocol';

function makeState(elements: Element[]): ProjectState {
    return {
        version: 1,
        canvas: { widthMaps: 1, heightMaps: 1, background: null },
        layers: [{
            id: 'layer-1', name: 'L1', visible: true, locked: false,
            opacity: 1, blendMode: 'normal', colorTag: null, elements,
        }],
        activeLayerId: 'layer-1',
        elements,
    } as unknown as ProjectState;
}

const brushEl = () => ({
    id: 'e-brush', type: 'brush', x: 10, y: 10, w: 40, h: 40, rotation: 0,
    locked: false, visible: true, points: [], size: 8,
    fill: { type: 'solid', color: '#000000' },
} as unknown as Element);

const rectEl = () => ({
    id: 'e-rect', type: 'rect', x: 0, y: 0, w: 20, h: 20, rotation: 0,
    locked: false, visible: true,
} as unknown as Element);

/** 假 Konva 节点：只实现 onTransformEnd 用到的 getter / setter 对。 */
function fakeNode(v: { x: number; y: number; w: number; h: number; sx: number; sy: number; rot: number }) {
    return {
        x: () => v.x,
        y: () => v.y,
        width: ((nw?: number) => (nw === undefined ? v.w : (v.w = nw))) as never,
        height: ((nh?: number) => (nh === undefined ? v.h : (v.h = nh))) as never,
        scaleX: ((s?: number) => (s === undefined ? v.sx : (v.sx = s))) as never,
        scaleY: ((s?: number) => (s === undefined ? v.sy : (v.sy = s))) as never,
        rotation: ((r?: number) => (r === undefined ? v.rot : (v.rot = r))) as never,
    };
}

function setup(elements: Element[]) {
    setActivePinia(createPinia());
    sendSpy.mockClear();
    const project = useProjectStore();
    project.setSnapshot(makeState(elements));
    const ui = useUiStore();
    ui.activeTool = 'select';

    const attached: unknown[][] = [];
    const transformerRef = ref({ getNode: () => ({ nodes: (ns: unknown[]) => { attached.push(ns); } }) });
    const layerRef = ref({ getNode: () => ({ findOne: (sel: string) => ({ id: sel }) }) });
    const mgr = useTransformerManager({
        transformerRef: transformerRef as never,
        layerRef: layerRef as never,
        elementsWatchSource: () => project.state?.elements ?? [],
    });
    return { project, ui, mgr, attached };
}

describe('useTransformerManager — 笔触不挂 Transformer', () => {
    it('只选中笔触时不挂任何节点', () => {
        const { ui, mgr, attached } = setup([brushEl()]);
        ui.selectElement('e-brush');
        mgr.attachTransformer();
        expect(attached.at(-1)).toEqual([]);
    });

    it('笔触 + 矩形混选时只挂矩形', () => {
        const { ui, mgr, attached } = setup([brushEl(), rectEl()]);
        ui.selectMany(['e-brush', 'e-rect']);
        mgr.attachTransformer();
        expect(attached.at(-1)).toEqual([{ id: '#e-rect' }]);
    });
});

describe('useTransformerManager — onTransformEnd', () => {
    it('笔触即使走到 onTransformEnd 也只发 x/y/rotation，不带 w/h', () => {
        const { mgr } = setup([brushEl()]);
        // 中心 (40,40)、缩放 2 倍：新 w/h = 80，新 x/y = 0
        const node = fakeNode({ x: 40, y: 40, w: 40, h: 40, sx: 2, sy: 2, rot: 0 });
        mgr.onTransformEnd({ target: node } as never, 'e-brush');
        expect(sendSpy).toHaveBeenCalledTimes(1);
        const [op, payload] = sendSpy.mock.calls[0] as unknown as [string, Record<string, unknown>];
        expect(op).toBe('element.transform');
        expect(payload).toEqual({ elementId: 'e-brush', x: 0, y: 0, rotation: 0 });
        expect(payload).not.toHaveProperty('w');
        expect(payload).not.toHaveProperty('h');
    });

    it('几何完全没变时一条 op 都不发', () => {
        const { mgr } = setup([rectEl()]);
        // rect 是 (0,0,20,20)：中心 (10,10)、scale 1 → 算出来与现状一致
        const node = fakeNode({ x: 10, y: 10, w: 20, h: 20, sx: 1, sy: 1, rot: 0 });
        mgr.onTransformEnd({ target: node } as never, 'e-rect');
        expect(sendSpy).not.toHaveBeenCalled();
    });

    it('普通元素正常发 element.transform（回归守卫）', () => {
        const { mgr, project } = setup([rectEl()]);
        const node = fakeNode({ x: 20, y: 20, w: 20, h: 20, sx: 2, sy: 2, rot: 0 });
        mgr.onTransformEnd({ target: node } as never, 'e-rect');
        expect(sendSpy).toHaveBeenCalledTimes(1);
        expect(sendSpy.mock.calls[0][1]).toEqual({
            elementId: 'e-rect', x: 0, y: 0, w: 40, h: 40, rotation: 0,
        });
        expect(project.elementById('e-rect')?.w).toBe(40);
    });
});
