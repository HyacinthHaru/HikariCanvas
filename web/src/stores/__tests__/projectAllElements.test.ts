/**
 * 0.7.0-P5-F：project store {@code allElements} computed 单测（积木 elementId 下拉数据源）。
 *
 * <p>覆盖：跨层展平 + z-order 顺序保留 + label 规则（文本截 16 字 / 其余 类型 · 短码）+
 * state 未就绪返空。纯 Pinia store（vitest node）。</p>
 */
import { describe, expect, it, beforeEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useProjectStore } from '../project';
import type { ProjectState, Element, Layer } from '@/types/protocol';

beforeEach(() => {
    setActivePinia(createPinia());
});

function baseEl(id: string, type: Element['type']): Element {
    return {
        id,
        type,
        x: 0,
        y: 0,
        w: 10,
        h: 10,
        rotation: 0,
        locked: false,
        visible: true,
    } as Element;
}

function textEl(id: string, text: string): Element {
    return {
        ...baseEl(id, 'text'),
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

function stateWith(layers: Layer[]): ProjectState {
    return {
        version: 0,
        canvas: { widthMaps: 1, heightMaps: 1, background: '#FFFFFF' },
        layers,
        activeLayerId: layers[0]?.id ?? '',
        history: { undoDepth: 0, redoDepth: 0 },
    };
}

describe('project.allElements', () => {
    it('state 未就绪 → 空数组', () => {
        const store = useProjectStore();
        expect(store.allElements).toEqual([]);
    });

    it('跨层展平 + 保留顺序（图层 index 外、层内 index 内）', () => {
        const store = useProjectStore();
        store.setSnapshot(
            stateWith([
                layer('l-0', [baseEl('e-aaa111', 'rect'), baseEl('e-bbb222', 'circle')]),
                layer('l-1', [baseEl('e-ccc333', 'path')]),
            ]),
        );
        const ids = store.allElements.map((e) => e.id);
        expect(ids).toEqual(['e-aaa111', 'e-bbb222', 'e-ccc333']);
        // type 透传
        expect(store.allElements.map((e) => e.type)).toEqual(['rect', 'circle', 'path']);
    });

    it('文本元素 label = 正文（≤16 字原样）', () => {
        const store = useProjectStore();
        store.setSnapshot(stateWith([layer('l-0', [textEl('e-t1', '欢迎光临')])]));
        expect(store.allElements[0].label).toBe('欢迎光临');
    });

    it('文本元素 label：超 16 字截断 + 省略号', () => {
        const store = useProjectStore();
        const long = '这是一段非常非常非常长的招牌文字内容超过十六个字符';
        store.setSnapshot(stateWith([layer('l-0', [textEl('e-t2', long)])]));
        const label = store.allElements[0].label;
        expect(label.endsWith('…')).toBe(true);
        // 16 字正文 + 省略号 = 17 长
        expect(label.length).toBe(17);
        expect(label.slice(0, 16)).toBe(long.slice(0, 16));
    });

    it('空正文文本元素 → 退 类型 · 短码', () => {
        const store = useProjectStore();
        store.setSnapshot(stateWith([layer('l-0', [textEl('e-empty99', '   ')])]));
        // 空白正文 → 走兜底：text · empty9（短码 = id 去 e- 前缀头 6 位）
        expect(store.allElements[0].label).toBe('text · empty9');
    });

    it('非文本元素 label = 类型 · 短码', () => {
        const store = useProjectStore();
        store.setSnapshot(stateWith([layer('l-0', [baseEl('e-deadbeef', 'image')])]));
        expect(store.allElements[0].label).toBe('image · deadbe');
    });
});
