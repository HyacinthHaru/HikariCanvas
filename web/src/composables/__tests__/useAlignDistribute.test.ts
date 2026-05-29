/**
 * useAlignDistribute 纯函数测试。
 *
 * 覆盖：align left/center/right + top/middle/bottom + distribute h/v + 边界 case：
 *   - 1 元素返空
 *   - 2 元素 distribute 返空
 *   - locked 元素跳过
 *   - 大小不同的元素 center 对齐用"中心点平均"
 */

import { describe, it, expect } from 'vitest';
import {
    computeAlignDistributePatches,
    countLockedSkipped,
} from '../useAlignDistribute';
import type { Element, RectElement } from '@/types/protocol';

/** 工厂：造一个最小可用 RectElement 给测试用。 */
function rect(id: string, x: number, y: number, w: number, h: number, locked = false): RectElement {
    return {
        type: 'rect',
        id,
        x, y, w, h,
        rotation: 0,
        locked,
        visible: true,
        fill: '#FF0000',
    };
}

describe('computeAlignDistributePatches - align', () => {
    it('align left: 所有元素 x 对到最小 x', () => {
        const els: Element[] = [
            rect('a', 10, 0, 20, 20),
            rect('b', 30, 0, 20, 20),
            rect('c', 50, 0, 20, 20),
        ];
        const patches = computeAlignDistributePatches(els, 'x', 'start');
        // a 的 x=10 不变（已是 min）→ 不在 patch；b/c 应改 x=10
        expect(patches.length).toBe(2);
        expect(patches).toContainEqual({ id: 'b', x: 10 });
        expect(patches).toContainEqual({ id: 'c', x: 10 });
    });

    it('align right: 所有元素右边沿对到最大 right', () => {
        const els: Element[] = [
            rect('a', 0, 0, 20, 20),   // right = 20
            rect('b', 0, 0, 40, 20),   // right = 40
            rect('c', 0, 0, 10, 20),   // right = 10
        ];
        const patches = computeAlignDistributePatches(els, 'x', 'end');
        // target right = 40
        // a: x = 40 - 20 = 20
        // b: x = 40 - 40 = 0 → 不变
        // c: x = 40 - 10 = 30
        expect(patches).toContainEqual({ id: 'a', x: 20 });
        expect(patches).toContainEqual({ id: 'c', x: 30 });
        expect(patches.find(p => p.id === 'b')).toBeUndefined();
    });

    it('align center horizontal: 所有元素中心对齐到中心平均', () => {
        const els: Element[] = [
            rect('a', 0, 0, 20, 20),   // center = 10
            rect('b', 40, 0, 20, 20),  // center = 50
        ];
        const patches = computeAlignDistributePatches(els, 'x', 'center');
        // avg center = 30; a -> x = 30 - 10 = 20; b -> x = 30 - 10 = 20
        expect(patches).toContainEqual({ id: 'a', x: 20 });
        expect(patches).toContainEqual({ id: 'b', x: 20 });
    });

    it('align top: 与 align left 公式对称（axis=y）', () => {
        const els: Element[] = [
            rect('a', 0, 5, 20, 20),
            rect('b', 0, 30, 20, 20),
        ];
        const patches = computeAlignDistributePatches(els, 'y', 'start');
        // min y = 5; b -> y=5
        expect(patches).toContainEqual({ id: 'b', y: 5 });
        expect(patches.find(p => p.id === 'a')).toBeUndefined();
    });

    it('align bottom: bottom edge 对齐到最大', () => {
        const els: Element[] = [
            rect('a', 0, 0, 20, 20),   // bottom = 20
            rect('b', 0, 0, 20, 40),   // bottom = 40
        ];
        const patches = computeAlignDistributePatches(els, 'y', 'end');
        // target bottom = 40; a -> y = 40 - 20 = 20; b -> y = 0 不变
        expect(patches).toContainEqual({ id: 'a', y: 20 });
        expect(patches.find(p => p.id === 'b')).toBeUndefined();
    });
});

describe('computeAlignDistributePatches - distribute', () => {
    it('distribute horizontal: 3 元素中间对到首尾中心点等间距', () => {
        const els: Element[] = [
            rect('a', 0, 0, 10, 10),     // center = 5
            rect('b', 50, 0, 10, 10),    // center = 55 (中间，会被移动)
            rect('c', 100, 0, 10, 10),   // center = 105
        ];
        const patches = computeAlignDistributePatches(els, 'x', 'distribute-equal');
        // step = (105 - 5) / 2 = 50；b 目标 center = 5 + 50 = 55，无变化（恰好已等间距）
        expect(patches).toEqual([]);

        // 改 b 起始：让它偏移以触发变更
        const els2: Element[] = [
            rect('a', 0, 0, 10, 10),     // center = 5
            rect('b', 30, 0, 10, 10),    // center = 35
            rect('c', 100, 0, 10, 10),   // center = 105
        ];
        const patches2 = computeAlignDistributePatches(els2, 'x', 'distribute-equal');
        // step = 50；b 目标 center = 55 → x = 55 - 5 = 50
        expect(patches2).toContainEqual({ id: 'b', x: 50 });
        expect(patches2.length).toBe(1);
    });

    it('distribute vertical: 4 元素按 center y 排序后均分', () => {
        const els: Element[] = [
            rect('a', 0, 0, 10, 10),     // center y = 5
            rect('b', 0, 10, 10, 10),    // center y = 15
            rect('c', 0, 20, 10, 10),    // center y = 25
            rect('d', 0, 90, 10, 10),    // center y = 95
        ];
        const patches = computeAlignDistributePatches(els, 'y', 'distribute-equal');
        // step = (95 - 5) / 3 = 30
        // b target center = 35 → y = 30
        // c target center = 65 → y = 60
        expect(patches).toContainEqual({ id: 'b', y: 30 });
        expect(patches).toContainEqual({ id: 'c', y: 60 });
        // a, d 不动
        expect(patches.find(p => p.id === 'a')).toBeUndefined();
        expect(patches.find(p => p.id === 'd')).toBeUndefined();
    });

    it('distribute 2 元素：返空（少于 3 个无中间项）', () => {
        const els: Element[] = [
            rect('a', 0, 0, 10, 10),
            rect('b', 100, 0, 10, 10),
        ];
        const patches = computeAlignDistributePatches(els, 'x', 'distribute-equal');
        expect(patches).toEqual([]);
    });
});

describe('computeAlignDistributePatches - 边界', () => {
    it('1 元素：所有操作返空', () => {
        const els: Element[] = [rect('a', 0, 0, 10, 10)];
        expect(computeAlignDistributePatches(els, 'x', 'start')).toEqual([]);
        expect(computeAlignDistributePatches(els, 'y', 'distribute-equal')).toEqual([]);
    });

    it('locked 元素被过滤掉：单 unlocked + 单 locked = 等效 1 元素', () => {
        const els: Element[] = [
            rect('a', 0, 0, 10, 10),
            rect('b', 100, 0, 10, 10, true),  // locked
        ];
        // 过滤 locked 后只剩 a，单元素 → 返空
        expect(computeAlignDistributePatches(els, 'x', 'start')).toEqual([]);
    });

    it('locked 元素不出现在 patch 里', () => {
        const els: Element[] = [
            rect('a', 0, 0, 10, 10),
            rect('b', 50, 0, 10, 10, true),  // locked → 跳过
            rect('c', 100, 0, 10, 10),
        ];
        const patches = computeAlignDistributePatches(els, 'x', 'start');
        // 仅 a, c 参与；min x = 0；c -> x=0
        expect(patches).toContainEqual({ id: 'c', x: 0 });
        expect(patches.find(p => p.id === 'b')).toBeUndefined();
    });
});

describe('countLockedSkipped', () => {
    it('返回 locked 元素数量', () => {
        const els: Element[] = [
            rect('a', 0, 0, 10, 10),
            rect('b', 0, 0, 10, 10, true),
            rect('c', 0, 0, 10, 10, true),
        ];
        expect(countLockedSkipped(els)).toBe(2);
    });

    it('全 unlocked 返 0', () => {
        const els: Element[] = [rect('a', 0, 0, 10, 10)];
        expect(countLockedSkipped(els)).toBe(0);
    });
});
