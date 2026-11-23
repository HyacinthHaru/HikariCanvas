/**
 * project store 核心查询与选中认领单测：
 * - 新加即选中（lastAddedElementIds 认领范围与连发合并）
 * - allElements 跨层展平与标签截断
 * - elementById 跨全部图层查找
 * - layerOfElement / isElementEditable / editableIds 可编辑性判定
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

function makeEl(id: string, over: Partial<Element> = {}): Element {
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
        ...over,
    } as Element;
}

function makeTextEl(id: string, text: string): Element {
    return {
        ...makeEl(id, { type: 'text' }),
        type: 'text',
        text,
        fontId: 'inter',
        fontSize: 16,
        color: '#000000',
        align: 'left',
        letterSpacing: 0,
        lineHeight: 1,
        vertical: false,
    } as Element;
}

function makeLayer(id: string, elements: Element[], over: Partial<Layer> = {}): Layer {
    return {
        id,
        name: id,
        visible: true,
        locked: false,
        opacity: 1,
        blendMode: 'normal',
        colorTag: null,
        elements,
        ...over,
    };
}

function stateWithLayers(layers: Layer[], activeLayerId?: string): ProjectState {
    return {
        version: 1,
        canvas: { widthMaps: 1, heightMaps: 1, background: '#FFFFFF' },
        layers,
        activeLayerId: activeLayerId ?? layers[0]?.id ?? '',
        elements: layers[0]?.elements ?? [],
        history: { undoDepth: 0, redoDepth: 0 },
    } as unknown as ProjectState;
}

function addOp(idx: number, e: Element): PatchOp {
    return { op: 'add', path: `/layers/0/elements/${idx}`, value: e } as unknown as PatchOp;
}

// ---------- 1. 新加即选中 ----------

describe('新加即选中 — 认领范围', () => {
    it('真·新建元素被认领', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithLayers([makeLayer('layer-1', [makeEl('e-a')])]));

        project.applyPatch(2, [addOp(1, makeEl('e-b'))]);

        expect(project.lastAddedElementIds).toEqual(['e-b']);
    });

    it('调整叠放顺序（remove + add 同一元素）不认领', () => {
        const project = useProjectStore();
        const moved = makeEl('e-b');
        project.setSnapshot(stateWithLayers([makeLayer('layer-1', [makeEl('e-a'), moved, makeEl('e-c')])]));

        project.applyPatch(2, [
            { op: 'remove', path: '/layers/0/elements/1' } as unknown as PatchOp,
            addOp(0, moved),
        ]);

        expect(project.lastAddedElementIds).toEqual([]);
        expect(project.state!.layers[0].elements.map((e) => e.id)).toEqual(['e-b', 'e-a', 'e-c']);
    });

    it('连着到达的多条新建算同一批，最终整批都在里面（粘贴多个元素）', () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date('2026-01-01T00:00:00Z'));
        const project = useProjectStore();
        project.setSnapshot(stateWithLayers([makeLayer('layer-1', [])]));

        project.applyPatch(2, [addOp(0, makeEl('e-1'))]);
        vi.advanceTimersByTime(5);
        project.applyPatch(3, [addOp(1, makeEl('e-2'))]);
        vi.advanceTimersByTime(5);
        project.applyPatch(4, [addOp(2, makeEl('e-3'))]);

        expect(project.lastAddedElementIds).toEqual(['e-1', 'e-2', 'e-3']);
    });

    it('隔得久的新建另起一批（手画第二个图形不会连上第一个）', () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date('2026-01-01T00:00:00Z'));
        const project = useProjectStore();
        project.setSnapshot(stateWithLayers([makeLayer('layer-1', [])]));

        project.applyPatch(2, [addOp(0, makeEl('e-1'))]);
        vi.advanceTimersByTime(ADD_BURST_MS + 1);
        project.applyPatch(3, [addOp(1, makeEl('e-2'))]);

        expect(project.lastAddedElementIds).toEqual(['e-2']);
    });

    it('切 wall / 断线重置后不残留上一批', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithLayers([makeLayer('layer-1', [])]));
        project.applyPatch(2, [addOp(0, makeEl('e-1'))]);

        project.reset();

        expect(project.lastAddedElementIds).toEqual([]);
    });
});

// ---------- 2. allElements ----------

describe('project.allElements', () => {
    it('state 未就绪 → 空数组', () => {
        const store = useProjectStore();
        expect(store.allElements).toEqual([]);
    });

    it('跨层展平 + 保留顺序（图层 index 外、层内 index 内）', () => {
        const store = useProjectStore();
        store.setSnapshot(
            stateWithLayers([
                makeLayer('l-0', [makeEl('e-aaa111', { type: 'rect' }), makeEl('e-bbb222', { type: 'circle' })]),
                makeLayer('l-1', [makeEl('e-ccc333', { type: 'path' })]),
            ]),
        );
        expect(store.allElements.map((e) => e.id)).toEqual(['e-aaa111', 'e-bbb222', 'e-ccc333']);
        expect(store.allElements.map((e) => e.type)).toEqual(['rect', 'circle', 'path']);
    });

    it('文本元素 label = 正文（≤16 字原样）', () => {
        const store = useProjectStore();
        store.setSnapshot(stateWithLayers([makeLayer('l-0', [makeTextEl('e-t1', '欢迎光临')])]));
        expect(store.allElements[0].label).toBe('欢迎光临');
    });

    it('文本元素 label：超 16 字截断 + 省略号', () => {
        const store = useProjectStore();
        const long = '这是一段非常非常非常长的招牌文字内容超过十六个字符';
        store.setSnapshot(stateWithLayers([makeLayer('l-0', [makeTextEl('e-t2', long)])]));
        const label = store.allElements[0].label;
        expect(label.endsWith('…')).toBe(true);
        expect(label.length).toBe(17);
        expect(label.slice(0, 16)).toBe(long.slice(0, 16));
    });

    it('空正文文本元素 → 退 类型 · 短码', () => {
        const store = useProjectStore();
        store.setSnapshot(stateWithLayers([makeLayer('l-0', [makeTextEl('e-empty99', '   ')])]));
        expect(store.allElements[0].label).toBe('Text · empty9');
    });

    it('非文本元素 label = 类型 · 短码', () => {
        const store = useProjectStore();
        store.setSnapshot(stateWithLayers([makeLayer('l-0', [makeEl('e-deadbeef', { type: 'image' })])]));
        expect(store.allElements[0].label).toBe('Image · deadbe');
    });
});

// ---------- 3. elementById ----------

describe('project.elementById', () => {
    it('state 未就绪 → null', () => {
        expect(useProjectStore().elementById('e-1')).toBeNull();
    });

    it('活动层里的元素照常找得到', () => {
        const store = useProjectStore();
        store.setSnapshot(stateWithLayers([makeLayer('l-0', [makeEl('e-1')]), makeLayer('l-1', [makeEl('e-2')])]));
        expect(store.elementById('e-1')?.id).toBe('e-1');
    });

    it('非活动层里的元素同样找得到（跨层选中的关键场景）', () => {
        const store = useProjectStore();
        store.setSnapshot(stateWithLayers([makeLayer('l-0', [makeEl('e-1')]), makeLayer('l-1', [makeEl('e-2')])]));
        expect(store.activeLayer.id).toBe('l-0');
        expect(store.elementById('e-2')?.id).toBe('e-2');
    });

    it('切图层之后，原来那层的元素仍然找得到', () => {
        const store = useProjectStore();
        const snapshot = stateWithLayers([makeLayer('l-0', [makeEl('e-1')]), makeLayer('l-1', [makeEl('e-2')])]);
        store.setSnapshot(snapshot);
        snapshot.activeLayerId = 'l-1';
        expect(store.elementById('e-1')?.id).toBe('e-1');
        expect(store.elementById('e-2')?.id).toBe('e-2');
    });

    it('哪一层都没有 → null', () => {
        const store = useProjectStore();
        store.setSnapshot(stateWithLayers([makeLayer('l-0', [makeEl('e-1')])]));
        expect(store.elementById('e-nope')).toBeNull();
    });

    it('空图层列表 / 图层没有 elements 字段都不抛', () => {
        const store = useProjectStore();
        store.setSnapshot(stateWithLayers([]));
        expect(store.elementById('e-1')).toBeNull();
        const broken = stateWithLayers([makeLayer('l-0', [])]);
        (broken.layers[0] as { elements?: Element[] }).elements = undefined;
        store.setSnapshot(broken);
        expect(store.elementById('e-1')).toBeNull();
    });
});

// ---------- 4. layerOfElement / isElementEditable / editableIds ----------

describe('layerOfElement', () => {
    it('返回元素自己所在的图层，而不是激活层', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithLayers([
            makeLayer('L1', [makeEl('e-a')]),
            makeLayer('L2', [makeEl('e-b')]),
        ]));

        expect(project.layerOfElement('e-b')?.id).toBe('L2');
        expect(project.activeLayer.id).toBe('L1');
    });

    it('找不到返回 null', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithLayers([makeLayer('L1', [makeEl('e-a')])]));
        expect(project.layerOfElement('e-nope')).toBeNull();
    });
});

describe('isElementEditable', () => {
    it('元素自己上锁 → 不能改', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithLayers([makeLayer('L1', [makeEl('e-a', { locked: true })])]));
        expect(project.isElementEditable('e-a')).toBe(false);
    });

    it('所在图层上锁 → 不能改（哪怕它不是激活层）', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithLayers([
            makeLayer('L1', [makeEl('e-a')]),
            makeLayer('L2', [makeEl('e-b')], { locked: true }),
        ]));
        expect(project.isElementEditable('e-a')).toBe(true);
        expect(project.isElementEditable('e-b')).toBe(false);
    });

    it('元素不存在 → 不能改', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithLayers([makeLayer('L1', [makeEl('e-a')])]));
        expect(project.isElementEditable('e-nope')).toBe(false);
    });
});

describe('editableIds', () => {
    it('挑出能改的那些，顺序不变', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithLayers([
            makeLayer('L1', [makeEl('e-a'), makeEl('e-b', { locked: true }), makeEl('e-c')]),
            makeLayer('L2', [makeEl('e-d')], { locked: true }),
        ]));

        expect(project.editableIds(['e-a', 'e-b', 'e-c', 'e-d'])).toEqual(['e-a', 'e-c']);
    });

    it('接受 Set（多选态直接传 selectedIds）', () => {
        const project = useProjectStore();
        project.setSnapshot(stateWithLayers([makeLayer('L1', [makeEl('e-a'), makeEl('e-b', { locked: true })])]));
        expect(project.editableIds(new Set(['e-a', 'e-b']))).toEqual(['e-a']);
    });
});
