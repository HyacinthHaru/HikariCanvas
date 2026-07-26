/**
 * project store：「新加即选中」认领谁。
 *
 * <p>两个坑都在这儿守：</p>
 * <ol>
 *   <li>调整叠放顺序 / 换图层发的也是「先 remove 再 add 同一个元素」，光看 add 会把
 *       "挪了个位置"认成"新建"，于是拖一下图层列表就把用户的选中抢走了；</li>
 *   <li>粘贴多个元素是一条条加进来的，逐条替换选中的话，5 条走完只剩最后一个被选中，
 *       用户接着的整体拖动 / 删除只作用于 1 个。</li>
 * </ol>
 */
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useProjectStore, ADD_BURST_MS } from '../project';
import type { ProjectState, Element, Layer, PatchOp } from '@/types/protocol';

beforeEach(() => {
    setActivePinia(createPinia());
});

afterEach(() => {
    vi.useRealTimers();
});

function el(id: string): Element {
    return {
        id, type: 'rect', x: 0, y: 0, w: 10, h: 10, rotation: 0, locked: false, visible: true,
    } as Element;
}

function layer(id: string, elements: Element[]): Layer {
    return {
        id, name: id, visible: true, locked: false, opacity: 1,
        blendMode: 'normal', colorTag: null, elements,
    };
}

function stateWith(elements: Element[]): ProjectState {
    return {
        version: 1,
        canvas: { widthMaps: 1, heightMaps: 1, background: null },
        layers: [layer('layer-1', elements)],
        activeLayerId: 'layer-1',
        elements,
    } as unknown as ProjectState;
}

function addOp(idx: number, e: Element): PatchOp {
    return { op: 'add', path: `/layers/0/elements/${idx}`, value: e } as unknown as PatchOp;
}

describe('新加即选中 — 认领范围', () => {
    it('真·新建元素被认领', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWith([el('e-a')]));

        project.applyPatch(2, [addOp(1, el('e-b'))]);

        expect(project.lastAddedElementIds).toEqual(['e-b']);
    });

    it('调整叠放顺序（remove + add 同一元素）不认领', () => {
        const project = useProjectStore();
        const moved = el('e-b');
        project.setSnapshot(stateWith([el('e-a'), moved, el('e-c')]));

        // 后端 reorderElement 的 patch 形态：先 remove 原位置，再 add 到新位置
        project.applyPatch(2, [
            { op: 'remove', path: '/layers/0/elements/1' } as unknown as PatchOp,
            addOp(0, moved),
        ]);

        expect(project.lastAddedElementIds).toEqual([]);
        // 位置确实换了（patch 本身照常应用）
        expect(project.state!.layers[0].elements.map((e) => e.id)).toEqual(['e-b', 'e-a', 'e-c']);
    });

    it('连着到达的多条新建算同一批，最终整批都在里面（粘贴多个元素）', () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date('2026-01-01T00:00:00Z'));
        const project = useProjectStore();
        project.setSnapshot(stateWith([]));

        project.applyPatch(2, [addOp(0, el('e-1'))]);
        vi.advanceTimersByTime(5);
        project.applyPatch(3, [addOp(1, el('e-2'))]);
        vi.advanceTimersByTime(5);
        project.applyPatch(4, [addOp(2, el('e-3'))]);

        expect(project.lastAddedElementIds).toEqual(['e-1', 'e-2', 'e-3']);
    });

    it('隔得久的新建另起一批（手画第二个图形不会连上第一个）', () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date('2026-01-01T00:00:00Z'));
        const project = useProjectStore();
        project.setSnapshot(stateWith([]));

        project.applyPatch(2, [addOp(0, el('e-1'))]);
        vi.advanceTimersByTime(ADD_BURST_MS + 1);
        project.applyPatch(3, [addOp(1, el('e-2'))]);

        expect(project.lastAddedElementIds).toEqual(['e-2']);
    });

    it('切 wall / 断线重置后不残留上一批', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWith([]));
        project.applyPatch(2, [addOp(0, el('e-1'))]);

        project.reset();

        expect(project.lastAddedElementIds).toEqual([]);
    });
});
