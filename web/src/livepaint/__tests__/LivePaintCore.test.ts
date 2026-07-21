/**
 * LivePaintCore 单元测试。
 *
 * 覆盖：
 * - pointInPolygon：矩形 / U 形凹多边形 / 边界 / 外部
 * - buildGraph：空 element / 单 rect / 退化输入
 *
 * polygonArea 是 LivePaintCore 内部函数（未 export）；本测试只通过 buildGraph
 * 的 MIN_GAP_AREA 过滤行为间接覆盖（面积小于 4 px² 的 gap 被丢）。
 */

import { describe, expect, it } from 'vitest';
import { buildGraph, pointInPolygon } from '../LivePaintCore';
import type { Polygon } from '../types';
import type { Element, RectElement } from '@/types/protocol';

function rect(over: Partial<RectElement> = {}): RectElement {
    return {
        id: 'r',
        type: 'rect',
        x: 0,
        y: 0,
        w: 10,
        h: 10,
        rotation: 0,
        locked: false,
        visible: true,
        ...over,
    };
}

describe('pointInPolygon', () => {
    const square: Polygon = [
        [0, 0],
        [10, 0],
        [10, 10],
        [0, 10],
    ];

    it('点在矩形内部 → true', () => {
        expect(pointInPolygon(5, 5, square)).toBe(true);
        expect(pointInPolygon(1, 1, square)).toBe(true);
        expect(pointInPolygon(9, 9, square)).toBe(true);
    });

    it('点在矩形外部 → false', () => {
        expect(pointInPolygon(-1, 5, square)).toBe(false);
        expect(pointInPolygon(11, 5, square)).toBe(false);
        expect(pointInPolygon(5, -1, square)).toBe(false);
        expect(pointInPolygon(5, 11, square)).toBe(false);
    });

    it('凹多边形（U 形）：中央凹槽内为 false；侧臂内为 true', () => {
        // U 形：开口朝上的字母 U
        // (0,0) → (10,0) → (10,10) → (7,10) → (7,3) → (3,3) → (3,10) → (0,10) → close
        const u: Polygon = [
            [0, 0],
            [10, 0],
            [10, 10],
            [7, 10],
            [7, 3],
            [3, 3],
            [3, 10],
            [0, 10],
        ];
        // 凹槽中央（U 开口里）：x=5, y=7 应在 polygon 外
        expect(pointInPolygon(5, 7, u)).toBe(false);
        // 底部：x=5, y=1 应在内
        expect(pointInPolygon(5, 1, u)).toBe(true);
        // 左臂中央：x=1, y=7
        expect(pointInPolygon(1, 7, u)).toBe(true);
        // 右臂中央：x=9, y=7
        expect(pointInPolygon(9, 7, u)).toBe(true);
    });
});

describe('buildGraph', () => {
    it('空 element 列表 → 整画布单 gap（outer = canvas bbox, holes 空）', async () => {
        const graph = await buildGraph([], 100, 50);
        expect(graph.gaps.length).toBe(1);
        expect(graph.gaps[0].outer).toEqual([
            [0, 0],
            [100, 0],
            [100, 50],
            [0, 50],
        ]);
        expect(graph.gaps[0].holes).toEqual([]);
        expect(graph.canvasWidth).toBe(100);
        expect(graph.canvasHeight).toBe(50);
        expect(graph.degraded).toBeUndefined();
    });

    it('一个 rect 元素居中 → polygon-clipping difference 产生外环 + 1 hole 或环绕结构', async () => {
        // 50×50 画布，中心 10×10 rect → gap = "外环 + 内孔 rect" 或 "环形单 ring"
        // 实测 polygon-clipping 输出 = 单 polygon，外环 = 画布边界 + 内 hole = rect
        const elements: Element[] = [rect({ x: 20, y: 20, w: 10, h: 10 })];
        const graph = await buildGraph(elements, 50, 50);
        expect(graph.degraded).toBeUndefined();
        expect(graph.gaps.length).toBeGreaterThanOrEqual(1);
        // 任一 gap 顶点都在 [0, 50]×[0, 50] 内
        for (const gap of graph.gaps) {
            for (const [x, y] of gap.outer) {
                expect(x).toBeGreaterThanOrEqual(0);
                expect(x).toBeLessThanOrEqual(50);
                expect(y).toBeGreaterThanOrEqual(0);
                expect(y).toBeLessThanOrEqual(50);
            }
        }
        // 验证：rect 中心点 (25, 25) 应不在任何 gap 里（被 element 占）
        // 用 pointInPolygon 自己复测
        let hit = false;
        for (const gap of graph.gaps) {
            if (pointInPolygon(25, 25, gap.outer)) {
                // 在外环内还得不在任何 hole 内
                let inHole = false;
                for (const hole of gap.holes) {
                    if (pointInPolygon(25, 25, hole)) {
                        inHole = true;
                        break;
                    }
                }
                if (!inHole) hit = true;
            }
        }
        expect(hit).toBe(false);
    });

    it('canvasWidth ≤ 0 / 非有限 → 空图（不抛）', async () => {
        const g1 = await buildGraph([], 0, 100);
        expect(g1.gaps).toEqual([]);
        expect(g1.canvasWidth).toBe(0);
        const g2 = await buildGraph([], NaN, 100);
        expect(g2.gaps).toEqual([]);
    });

    it('单 rect 完全覆盖画布 → gaps 数 = 0', async () => {
        const elements: Element[] = [rect({ x: 0, y: 0, w: 50, h: 50 })];
        const graph = await buildGraph(elements, 50, 50);
        // rect 完全覆盖；polygon-clipping difference 输出 0 个 polygon
        expect(graph.gaps.length).toBe(0);
        expect(graph.degraded).toBeUndefined();
    });

    it('两个不重叠 rect → 仍有 gap（剩余画布区）', async () => {
        // 100×100 画布，两个 10×10 rect 在角落
        const elements: Element[] = [
            rect({ id: 'r1', x: 0, y: 0, w: 10, h: 10 }),
            rect({ id: 'r2', x: 90, y: 90, w: 10, h: 10 }),
        ];
        const graph = await buildGraph(elements, 100, 100);
        expect(graph.degraded).toBeUndefined();
        expect(graph.gaps.length).toBeGreaterThanOrEqual(1);
        // 中心 (50, 50) 应该在 gap 内
        let foundCenter = false;
        for (const gap of graph.gaps) {
            if (!pointInPolygon(50, 50, gap.outer)) continue;
            let inHole = false;
            for (const hole of gap.holes) {
                if (pointInPolygon(50, 50, hole)) {
                    inHole = true;
                    break;
                }
            }
            if (!inHole) foundCenter = true;
        }
        expect(foundCenter).toBe(true);
    });
});
