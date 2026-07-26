// @vitest-environment happy-dom
/**
 * 变换锚点该给谁挂。
 *
 * <p>三类元素不该有锚点：</p>
 * <ul>
 *   <li><b>上锁的元素</b>——{@code element.locked} 后端全仓一处都不看，锚点一挂就能拉，
 *       拉完真落地，"锁定"等于摆设；</li>
 *   <li><b>上锁图层里的元素</b>——后端会拒 LAYER_LOCKED，但前端已经乐观改过本地几何，
 *       浏览器显示新的、游戏里还是旧的，一直分叉到重新拉快照。且必须按元素<b>自己所在</b>的
 *       图层判，不是"当前激活层"（切图层不清选中，选中的元素在别的层是常态）；</li>
 *   <li><b>看不见的元素</b>（自己隐藏或所在图层隐藏）——画面上没有的东西挂一圈锚点既莫名其妙，
 *       又能被拖着改（后端不看 visible）。</li>
 * </ul>
 */
import { describe, it, expect, vi } from 'vitest';
import { ref } from 'vue';
import { setActivePinia, createPinia } from 'pinia';

const sendSpy = vi.fn(() => 'c-1');
vi.mock('@/network/wsClient', () => ({ getWsClient: () => ({ send: sendSpy }) }));

import { useTransformerManager } from '../useTransformerManager';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import type { Element, Layer, ProjectState } from '@/types/protocol';

function el(id: string, over: Partial<Element> = {}): Element {
    return {
        id, type: 'rect', x: 0, y: 0, w: 20, h: 20, rotation: 0, locked: false, visible: true,
        ...over,
    } as Element;
}

function layer(id: string, elements: Element[], over: Partial<Layer> = {}): Layer {
    return {
        id, name: id, visible: true, locked: false, opacity: 1,
        blendMode: 'normal', colorTag: null, elements, ...over,
    };
}

function setup(layers: Layer[]) {
    setActivePinia(createPinia());
    sendSpy.mockClear();
    const project = useProjectStore();
    project.setSnapshot({
        version: 1,
        canvas: { widthMaps: 1, heightMaps: 1, background: null },
        layers,
        activeLayerId: layers[0].id,
        elements: layers[0].elements,
    } as unknown as ProjectState);
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

describe('useTransformerManager — 锁定 / 隐藏的元素不挂锚点', () => {
    it('上锁的元素不挂', () => {
        const { ui, mgr, attached } = setup([layer('L1', [el('e-locked', { locked: true })])]);
        ui.selectElement('e-locked');
        mgr.attachTransformer();
        expect(attached.at(-1)).toEqual([]);
    });

    it('上锁图层里的元素不挂 —— 哪怕它不是当前激活层', () => {
        const { ui, mgr, attached } = setup([
            layer('L1', [el('e-a')]),
            layer('L2', [el('e-b')], { locked: true }),
        ]);
        ui.selectMany(['e-a', 'e-b']);
        mgr.attachTransformer();
        expect(attached.at(-1)).toEqual([{ id: '#e-a' }]);
    });

    it('隐藏的元素不挂', () => {
        const { ui, mgr, attached } = setup([layer('L1', [el('e-hidden', { visible: false }), el('e-ok')])]);
        ui.selectMany(['e-hidden', 'e-ok']);
        mgr.attachTransformer();
        expect(attached.at(-1)).toEqual([{ id: '#e-ok' }]);
    });

    it('隐藏图层里的元素不挂', () => {
        const { ui, mgr, attached } = setup([
            layer('L1', [el('e-a')]),
            layer('L2', [el('e-b')], { visible: false }),
        ]);
        ui.selectMany(['e-a', 'e-b']);
        mgr.attachTransformer();
        expect(attached.at(-1)).toEqual([{ id: '#e-a' }]);
    });

    it('正常元素照挂（回归守卫）', () => {
        const { ui, mgr, attached } = setup([layer('L1', [el('e-a')])]);
        ui.selectElement('e-a');
        mgr.attachTransformer();
        expect(attached.at(-1)).toEqual([{ id: '#e-a' }]);
    });
});

describe('useTransformerManager — 预览态下的"没动过就别发"', () => {
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

    it('时间轴预览态下碰一下锚点没挪动 → 一条 op 都不发（按画面上显示的几何比，不是元素基础值）', () => {
        setActivePinia(createPinia());
        sendSpy.mockClear();
        const project = useProjectStore();
        project.setSnapshot({
            version: 1,
            canvas: { widthMaps: 1, heightMaps: 1, background: null },
            layers: [layer('L1', [el('e-a')])],
            activeLayerId: 'L1',
            elements: [el('e-a')],
        } as unknown as ProjectState);
        const transformerRef = ref({ getNode: () => ({ nodes: () => { /* noop */ } }) });
        const layerRef = ref({ getNode: () => ({ findOne: (sel: string) => ({ id: sel }) }) });
        const mgr = useTransformerManager({
            transformerRef: transformerRef as never,
            layerRef: layerRef as never,
            elementsWatchSource: () => project.state?.elements ?? [],
            // 元素基础值在 (0,0)，但这一刻画面上被关键帧插到 (100,100)
            displayGeometryOf: () => ({ x: 100, y: 100, w: 20, h: 20, rotation: 0 }),
        });

        // 节点就停在画面位置（中心 110,110），用户只是点了一下锚点没拖
        mgr.onTransformEnd({ target: fakeNode({ x: 110, y: 110, w: 20, h: 20, sx: 1, sy: 1, rot: 0 }) } as never, 'e-a');

        expect(sendSpy).not.toHaveBeenCalled();
    });
});
