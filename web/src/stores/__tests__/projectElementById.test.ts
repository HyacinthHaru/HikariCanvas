/**
 * project store {@code elementById} 单测。
 *
 * <p>它必须扫<b>全部图层</b>，不能只查 {@code state.elements}（那只是 activeLayer.elements
 * 的兼容视图）。切图层不会清空选中态、Konva 命中层也只铺活动层，于是「选中的元素在别的层」
 * 是常态；只查活动层会让属性面板突然变空、方向键微移静默 no-op、复制悄悄漏元素、
 * 时间轴加帧静默失败。后端 {@code elementExists} 本来就是扫全层的，两边对齐。</p>
 */
import { describe, expect, it, beforeEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useProjectStore } from '../project';
import type { ProjectState, Element, Layer } from '@/types/protocol';

beforeEach(() => {
    setActivePinia(createPinia());
});

function baseEl(id: string): Element {
    return {
        id,
        type: 'rect',
        x: 0,
        y: 0,
        w: 10,
        h: 10,
        rotation: 0,
        locked: false,
        visible: true,
    } as Element;
}

function layer(id: string, elements: Element[]): Layer {
    return {
        id,
        name: id,
        visible: true,
        locked: false,
        opacity: 1,
        blendMode: 'normal',
        colorTag: null,
        elements,
    };
}

function stateWith(layers: Layer[], activeLayerId?: string): ProjectState {
    return {
        version: 0,
        canvas: { widthMaps: 1, heightMaps: 1, background: '#FFFFFF' },
        layers,
        activeLayerId: activeLayerId ?? layers[0]?.id ?? '',
        history: { undoDepth: 0, redoDepth: 0 },
    };
}

describe('project.elementById', () => {
    it('state 未就绪 → null', () => {
        expect(useProjectStore().elementById('e-1')).toBeNull();
    });

    it('活动层里的元素照常找得到', () => {
        const store = useProjectStore();
        store.setSnapshot(stateWith([layer('l-0', [baseEl('e-1')]), layer('l-1', [baseEl('e-2')])]));
        expect(store.elementById('e-1')?.id).toBe('e-1');
    });

    it('非活动层里的元素同样找得到（跨层选中的关键场景）', () => {
        const store = useProjectStore();
        store.setSnapshot(stateWith([layer('l-0', [baseEl('e-1')]), layer('l-1', [baseEl('e-2')])]));
        // 活动层是 l-0，e-2 在 l-1
        expect(store.activeLayer.id).toBe('l-0');
        expect(store.elementById('e-2')?.id).toBe('e-2');
    });

    it('切图层之后，原来那层的元素仍然找得到', () => {
        const store = useProjectStore();
        const snapshot = stateWith([layer('l-0', [baseEl('e-1')]), layer('l-1', [baseEl('e-2')])]);
        store.setSnapshot(snapshot);
        // 模拟切到 l-1（选中态不会跟着清，e-1 可能还在 selectedIds 里）
        snapshot.activeLayerId = 'l-1';
        expect(store.elementById('e-1')?.id).toBe('e-1');
        expect(store.elementById('e-2')?.id).toBe('e-2');
    });

    it('哪一层都没有 → null', () => {
        const store = useProjectStore();
        store.setSnapshot(stateWith([layer('l-0', [baseEl('e-1')])]));
        expect(store.elementById('e-nope')).toBeNull();
    });

    it('空图层列表 / 图层没有 elements 字段都不抛', () => {
        const store = useProjectStore();
        store.setSnapshot(stateWith([]));
        expect(store.elementById('e-1')).toBeNull();
        const broken = stateWith([layer('l-0', [])]);
        (broken.layers[0] as { elements?: Element[] }).elements = undefined;
        store.setSnapshot(broken);
        expect(store.elementById('e-1')).toBeNull();
    });
});
