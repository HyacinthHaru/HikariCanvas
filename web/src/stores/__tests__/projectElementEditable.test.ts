/**
 * project store：「这个元素现在能不能改」。
 *
 * <p>元素锁（{@code element.locked}）后端全仓一处都不看，只有前端拦得住——删除 / 方向键微移 /
 * 变换锚点漏一个，锁定就是个摆设。图层锁后端会拒（LAYER_LOCKED），但前端已经乐观改过本地值，
 * 拒了也只进日志，两边就此对不上；而且必须按元素<b>自己所在</b>的图层判，不是"当前激活层"
 * ——切图层不清选中，选中的元素在别的层是常态。</p>
 */
import { describe, expect, it, beforeEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useProjectStore } from '../project';
import type { ProjectState, Element, Layer } from '@/types/protocol';

beforeEach(() => {
    setActivePinia(createPinia());
});

function el(id: string, over: Partial<Element> = {}): Element {
    return {
        id, type: 'rect', x: 0, y: 0, w: 10, h: 10, rotation: 0, locked: false, visible: true,
        ...over,
    } as Element;
}

function layer(id: string, elements: Element[], over: Partial<Layer> = {}): Layer {
    return {
        id, name: id, visible: true, locked: false, opacity: 1,
        blendMode: 'normal', colorTag: null, elements, ...over,
    };
}

function stateWith(layers: Layer[]): ProjectState {
    return {
        version: 1,
        canvas: { widthMaps: 1, heightMaps: 1, background: null },
        layers,
        activeLayerId: layers[0]?.id,
        elements: layers[0]?.elements ?? [],
    } as unknown as ProjectState;
}

describe('layerOfElement', () => {
    it('返回元素自己所在的图层，而不是激活层', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWith([
            layer('L1', [el('e-a')]),
            layer('L2', [el('e-b')]),
        ]));

        expect(project.layerOfElement('e-b')?.id).toBe('L2');
        expect(project.activeLayer.id).toBe('L1');
    });

    it('找不到返回 null', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWith([layer('L1', [el('e-a')])]));
        expect(project.layerOfElement('e-nope')).toBeNull();
    });
});

describe('isElementEditable', () => {
    it('元素自己上锁 → 不能改', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWith([layer('L1', [el('e-a', { locked: true })])]));
        expect(project.isElementEditable('e-a')).toBe(false);
    });

    it('所在图层上锁 → 不能改（哪怕它不是激活层）', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWith([
            layer('L1', [el('e-a')]),
            layer('L2', [el('e-b')], { locked: true }),
        ]));
        expect(project.isElementEditable('e-a')).toBe(true);
        expect(project.isElementEditable('e-b')).toBe(false);
    });

    it('元素不存在 → 不能改', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWith([layer('L1', [el('e-a')])]));
        expect(project.isElementEditable('e-nope')).toBe(false);
    });
});

describe('editableIds', () => {
    it('挑出能改的那些，顺序不变', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWith([
            layer('L1', [el('e-a'), el('e-b', { locked: true }), el('e-c')]),
            layer('L2', [el('e-d')], { locked: true }),
        ]));

        expect(project.editableIds(['e-a', 'e-b', 'e-c', 'e-d'])).toEqual(['e-a', 'e-c']);
    });

    it('接受 Set（多选态直接传 selectedIds）', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWith([layer('L1', [el('e-a'), el('e-b', { locked: true })])]));
        expect(project.editableIds(new Set(['e-a', 'e-b']))).toEqual(['e-a']);
    });
});
