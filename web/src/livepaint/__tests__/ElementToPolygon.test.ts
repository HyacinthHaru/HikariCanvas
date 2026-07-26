/**
 * ElementToPolygon 单元测试。
 *
 * 覆盖：
 * - rect / circle / shape / star / path 各类型采样
 * - rotation 应用（绕 bbox 中心）
 * - path 解析失败 fallback
 *
 * 不 mock 任何业务代码；输入均为真实 Element record。
 */

import { describe, expect, it } from 'vitest';
import { elementToPolygon, CIRCLE_SAMPLE_POINTS } from '../ElementToPolygon';
import type {
    CircleElement,
    PathElement,
    RectElement,
    ShapeElement,
} from '@/types/protocol';

// ---------- 构造 helpers（按 BaseElement 字段全填） ----------

function makeRect(over: Partial<RectElement> = {}): RectElement {
    return {
        id: 'r1',
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

function makeCircle(over: Partial<CircleElement> = {}): CircleElement {
    return {
        id: 'c1',
        type: 'circle',
        x: 0,
        y: 0,
        w: 100,
        h: 100,
        rotation: 0,
        locked: false,
        visible: true,
        ...over,
    };
}

function makeShape(over: Partial<ShapeElement> = {}): ShapeElement {
    return {
        id: 's1',
        type: 'shape',
        kind: 'polygon',
        sides: 5,
        x: 0,
        y: 0,
        w: 100,
        h: 100,
        rotation: 0,
        locked: false,
        visible: true,
        ...over,
    };
}

function makePath(over: Partial<PathElement> = {}): PathElement {
    return {
        id: 'p1',
        type: 'path',
        d: 'M0 0 L10 0 L10 10 L0 10 Z',
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

describe('elementToPolygon — rect', () => {
    it('rect 4 顶点 + rotation=0：bbox 四角顺序 TL/TR/BR/BL', () => {
        const poly = elementToPolygon(makeRect({ x: 10, y: 20, w: 30, h: 40 }));
        expect(poly).not.toBeNull();
        expect(poly!).toEqual([
            [10, 20],
            [40, 20],
            [40, 60],
            [10, 60],
        ]);
    });

    it('rect rotation=90 顶点正确绕 bbox 中心旋转', () => {
        const poly = elementToPolygon(makeRect({ x: 0, y: 0, w: 10, h: 10, rotation: 90 }));
        expect(poly).not.toBeNull();
        expect(poly!.length).toBe(4);
        // 中心 (5,5)；TL(0,0) 绕 (5,5) 旋 90° 顺时针 → (10,0)（屏幕坐标 y 向下）
        const epsilon = 1e-6;
        const approx = (a: number, b: number): boolean => Math.abs(a - b) < epsilon;
        // 旋转 90°（按代码：cx + dx*cos - dy*sin, cy + dx*sin + dy*cos；cos=0, sin=1）
        // TL(0,0): dx=-5, dy=-5 → (5-0-(-5), 5+(-5)+0) = (10, 0)
        expect(approx(poly![0][0], 10)).toBe(true);
        expect(approx(poly![0][1], 0)).toBe(true);
        // 4 个顶点中心仍是 (5, 5)
        const cx = poly!.reduce((s, p) => s + p[0], 0) / 4;
        const cy = poly!.reduce((s, p) => s + p[1], 0) / 4;
        expect(approx(cx, 5)).toBe(true);
        expect(approx(cy, 5)).toBe(true);
    });
});

describe('elementToPolygon — circle', () => {
    it('circle 32 采样点 + 所有点在圆边界 ±0.5px 内', () => {
        const w = 100;
        const h = 100;
        const cx = 50;
        const cy = 50;
        const r = 50;
        const poly = elementToPolygon(makeCircle({ x: 0, y: 0, w, h }));
        expect(poly).not.toBeNull();
        expect(poly!.length).toBe(CIRCLE_SAMPLE_POINTS);
        for (const [x, y] of poly!) {
            const dx = x - cx;
            const dy = y - cy;
            const dist = Math.sqrt(dx * dx + dy * dy);
            // 采样点必须刚好在半径上（精度 ε）
            expect(Math.abs(dist - r)).toBeLessThan(0.5);
        }
    });

    it('circle w≠h 椭圆：x 极值 / y 极值符合 rx / ry', () => {
        const poly = elementToPolygon(makeCircle({ x: 0, y: 0, w: 200, h: 50 }));
        expect(poly).not.toBeNull();
        const xs = poly!.map(p => p[0]);
        const ys = poly!.map(p => p[1]);
        const cxCenter = 100;
        const cyCenter = 25;
        const rx = 100;
        const ry = 25;
        // 椭圆约束：(x-cx)²/rx² + (y-cy)²/ry² ≈ 1
        for (let i = 0; i < poly!.length; i++) {
            const nx = (xs[i] - cxCenter) / rx;
            const ny = (ys[i] - cyCenter) / ry;
            expect(Math.abs(nx * nx + ny * ny - 1)).toBeLessThan(1e-6);
        }
    });
});

describe('elementToPolygon — shape', () => {
    it('shape sides=5 五边形：5 顶点', () => {
        const poly = elementToPolygon(makeShape({ kind: 'polygon', sides: 5 }));
        expect(poly).not.toBeNull();
        expect(poly!.length).toBe(5);
    });

    it('star sides=5：输出 10 顶点（外 / 内交替）', () => {
        const poly = elementToPolygon(makeShape({
            kind: 'star',
            sides: 5,
            innerRatio: 0.4,
            x: 0,
            y: 0,
            w: 100,
            h: 100,
        }));
        expect(poly).not.toBeNull();
        expect(poly!.length).toBe(10);

        // 中心 (50, 50)；outer 半径 = 50；inner 半径 = 20
        const cx = 50;
        const cy = 50;
        const outerR = 50;
        const innerR = 20;
        // 偶数索引 = outer；奇数 = inner
        for (let i = 0; i < poly!.length; i++) {
            const [x, y] = poly![i];
            const dist = Math.sqrt((x - cx) ** 2 + (y - cy) ** 2);
            const expected = (i % 2 === 0) ? outerR : innerR;
            expect(Math.abs(dist - expected)).toBeLessThan(1e-6);
        }
    });
});

describe('elementToPolygon — path', () => {
    it('path "M0 0 L10 0 L10 10 L0 10 Z" → 4 顶点矩形（含 origin 偏移）', () => {
        const poly = elementToPolygon(makePath({
            x: 5,
            y: 7,
            w: 10,
            h: 10,
            d: 'M0 0 L10 0 L10 10 L0 10 Z',
        }));
        expect(poly).not.toBeNull();
        expect(poly!.length).toBe(4);
        // origin (5, 7) → 全局 (5,7)(15,7)(15,17)(5,17)
        expect(poly!).toEqual([
            [5, 7],
            [15, 7],
            [15, 17],
            [5, 17],
        ]);
    });

    it('path d="garbage" 解析失败 → fallback bbox 4 顶点', () => {
        const poly = elementToPolygon(makePath({
            x: 0,
            y: 0,
            w: 10,
            h: 10,
            d: 'garbage',
        }));
        // d 解析得 < 3 点，应回退到 bbox
        expect(poly).not.toBeNull();
        expect(poly!.length).toBe(4);
        expect(poly!).toEqual([
            [0, 0],
            [10, 0],
            [10, 10],
            [0, 10],
        ]);
    });

    it('元素不在原点时，重复顶点仍被去掉', () => {
        // 去重比较曾经拿"已加过偏移的上一点"去比"还没加偏移的新点"，元素 x/y 不为 0 时
        // 永远判不相等 → 重复点原样喂给 polygon-clipping。
        const poly = elementToPolygon(makePath({
            x: 5,
            y: 7,
            w: 10,
            h: 10,
            d: 'M0 0 L10 0 L10 0 L10 10 L0 10 Z',   // 第二个 L10 0 是重复点
        }));
        expect(poly).not.toBeNull();
        expect(poly!).toEqual([
            [5, 7],
            [15, 7],
            [15, 17],
            [5, 17],
        ]);
    });
});
