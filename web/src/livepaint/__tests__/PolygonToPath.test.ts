/**
 * PolygonToPath 单元测试。
 *
 * 覆盖：
 * - gapPolygonToPathD：简单矩形外环 / 含 hole 多 subpath
 * - gapToPathElement：bbox 算正确 + 顶点相对坐标
 * - 简化阈值：< 阈值不简化；超阈值简化到 ≤ 240
 */

import { describe, expect, it } from 'vitest';
import {
    gapPolygonToPathD,
    gapToPathElement,
    VERTEX_HARD_LIMIT,
    VERTEX_WARN_THRESHOLD,
} from '../PolygonToPath';
import type { GapPolygon, Polygon } from '../types';

describe('gapPolygonToPathD', () => {
    it('简单矩形外环 → "M0 0 L10 0 L10 10 L0 10 Z"（toFixed(2)）', () => {
        const gap: GapPolygon = {
            outer: [
                [0, 0],
                [10, 0],
                [10, 10],
                [0, 10],
            ],
            holes: [],
        };
        const d = gapPolygonToPathD(gap);
        expect(d).toBe('M0.00 0.00 L10.00 0.00 L10.00 10.00 L0.00 10.00 Z');
    });

    it('含 1 hole 的 polygon → 输出 2 个 subpath（2 个 M 命令）', () => {
        const gap: GapPolygon = {
            outer: [
                [0, 0],
                [100, 0],
                [100, 100],
                [0, 100],
            ],
            holes: [
                [
                    [40, 40],
                    [60, 40],
                    [60, 60],
                    [40, 60],
                ],
            ],
        };
        const d = gapPolygonToPathD(gap);
        // 2 个 M 命令
        const mCount = (d.match(/M/g) ?? []).length;
        expect(mCount).toBe(2);
        // 2 个 Z 命令
        const zCount = (d.match(/Z/g) ?? []).length;
        expect(zCount).toBe(2);
    });
});

describe('gapToPathElement', () => {
    it('bbox 正确：min/max 算自所有顶点，顶点相对坐标', () => {
        const gap: GapPolygon = {
            outer: [
                [10, 20],
                [40, 20],
                [40, 60],
                [10, 60],
            ],
            holes: [],
        };
        const out = gapToPathElement(gap);
        expect(out.x).toBe(10);
        expect(out.y).toBe(20);
        expect(out.w).toBe(30);
        expect(out.h).toBe(40);
        // d 应为相对坐标 (0,0)..(30,40)
        expect(out.d).toBe('M0.00 0.00 L30.00 0.00 L30.00 40.00 L0.00 40.00 Z');
        expect(out.vertexCount).toBe(4);
    });

    it('退化输入（空 polygon）→ 全 0 结果', () => {
        const gap: GapPolygon = { outer: [], holes: [] };
        const out = gapToPathElement(gap);
        expect(out.x).toBe(0);
        expect(out.y).toBe(0);
        expect(out.w).toBe(0);
        expect(out.h).toBe(0);
        expect(out.d).toBe('');
        expect(out.vertexCount).toBe(0);
    });

    it('零面积单点退化输入 → 全 0 结果', () => {
        const gap: GapPolygon = {
            outer: [[12.5, 8.25], [12.5, 8.25], [12.5, 8.25]],
            holes: [],
        };
        const out = gapToPathElement(gap);
        expect(out).toEqual({ x: 0, y: 0, w: 0, h: 0, d: '', vertexCount: 0, overBudget: false });
    });

    it('浮点顶点坐标：输出整数 x/y/w/h 且 x + localX 精确还原世界坐标（零平移漂移）', () => {
        const outer: Polygon = [
            [12.8, 7.3],
            [40.2, 7.3],
            [40.2, 25.9],
            [12.8, 25.9],
        ];
        const hole: Polygon = [
            [18.4, 10.6],
            [24.1, 10.6],
            [24.1, 16.2],
            [18.4, 16.2],
        ];
        const out = gapToPathElement({ outer, holes: [hole] });

        expect(Number.isInteger(out.x)).toBe(true);
        expect(Number.isInteger(out.y)).toBe(true);
        expect(Number.isInteger(out.w)).toBe(true);
        expect(Number.isInteger(out.h)).toBe(true);
        expect(out.x).toBe(12);
        expect(out.y).toBe(7);
        expect(out.w).toBe(29);
        expect(out.h).toBe(19);
        expect(out.overBudget).toBe(false);

        const coords: Array<[number, number]> = [];
        const re = /[ML](-?\d+\.\d+)\s+(-?\d+\.\d+)/g;
        let m: RegExpExecArray | null;
        while ((m = re.exec(out.d)) !== null) {
            coords.push([parseFloat(m[1]), parseFloat(m[2])]);
        }
        const expectedWorld = [...outer, ...hole];
        expect(coords).toHaveLength(expectedWorld.length);
        for (let i = 0; i < expectedWorld.length; i++) {
            const [localX, localY] = coords[i];
            expect(localX).toBeGreaterThanOrEqual(0);
            expect(localX).toBeLessThanOrEqual(out.w);
            expect(localY).toBeGreaterThanOrEqual(0);
            expect(localY).toBeLessThanOrEqual(out.h);
            expect(out.x + localX).toBeCloseTo(expectedWorld[i][0], 2);
            expect(out.y + localY).toBeCloseTo(expectedWorld[i][1], 2);
        }
    });

    it('亚像素窄条（0.6px × 20px）：整数 w 钳制 ≥ 1', () => {
        const sliver: GapPolygon = {
            outer: [
                [10.2, 5.0],
                [10.8, 5.0],
                [10.8, 25.0],
                [10.2, 25.0],
            ],
            holes: [],
        };
        const out = gapToPathElement(sliver);
        expect(out.x).toBe(10);
        expect(out.y).toBe(5);
        expect(out.w).toBeGreaterThanOrEqual(1);
        expect(out.w).toBe(1);
        expect(out.h).toBe(20);
        expect(out.overBudget).toBe(false);
    });

    it('超出服务端 10000 坐标或尺寸上限时标记 overBudget=true', () => {
        const hugeCoord: GapPolygon = {
            outer: [
                [10_005, 0],
                [10_015, 0],
                [10_015, 10],
                [10_005, 10],
            ],
            holes: [],
        };
        expect(gapToPathElement(hugeCoord).overBudget).toBe(true);

        const hugeDim: GapPolygon = {
            outer: [
                [0, 0],
                [10_005, 0],
                [10_005, 10],
                [0, 10],
            ],
            holes: [],
        };
        expect(gapToPathElement(hugeDim).overBudget).toBe(true);
    });
});

describe('maybeSimplify (via gapToPathElement)', () => {
    it('顶点数 < 阈值不简化（小 polygon 原样保留）', () => {
        // 10 顶点 polygon——远低于 VERTEX_WARN_THRESHOLD=180
        const verts: Polygon = [];
        for (let i = 0; i < 10; i++) {
            const t = (i / 10) * Math.PI * 2;
            verts.push([50 + Math.cos(t) * 40, 50 + Math.sin(t) * 40]);
        }
        const gap: GapPolygon = { outer: verts, holes: [] };
        const out = gapToPathElement(gap);
        expect(out.vertexCount).toBe(10);
    });

    it('顶点数 > VERTEX_WARN_THRESHOLD：触发 RDP，结果 ≤ VERTEX_HARD_LIMIT', () => {
        // 构造 500 个近共线点 + 在末尾收尾——RDP 该能压到很小
        // 用一个高密度小幅噪声圆，半径 100，500 点；tolerance 0.5 起步应足以减半
        const verts: Polygon = [];
        const N = 500;
        for (let i = 0; i < N; i++) {
            const t = (i / N) * Math.PI * 2;
            // 圆 + 微小（< 0.5）噪声，但 0.5 步起 RDP 也会丢绝大多数点
            verts.push([
                50 + Math.cos(t) * 40 + Math.sin(t * 7) * 0.1,
                50 + Math.sin(t) * 40 + Math.cos(t * 11) * 0.1,
            ]);
        }
        const gap: GapPolygon = { outer: verts, holes: [] };
        const out = gapToPathElement(gap);
        expect(verts.length).toBeGreaterThan(VERTEX_WARN_THRESHOLD);
        // 验证：简化后输出 ≤ VERTEX_HARD_LIMIT
        expect(out.vertexCount).toBeLessThanOrEqual(VERTEX_HARD_LIMIT);
        // 但应非空
        expect(out.vertexCount).toBeGreaterThanOrEqual(3);
    });
});
