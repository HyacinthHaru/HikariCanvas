/**
 * M18-P5：RdpSimplifier 单元测试。
 *
 * 覆盖：
 * - 三顶点不变
 * - 共线点删除
 * - tolerance ≤ 0 时保留全部点（return 原数组）
 * - 大数据不爆栈（迭代式 stack）
 */

import { describe, expect, it } from 'vitest';
import { rdpSimplify } from '../RdpSimplifier';
import type { Polygon } from '../types';

describe('rdpSimplify', () => {
    it('三顶点不变（n ≤ 3 直接 return）', () => {
        const tri: Polygon = [
            [0, 0],
            [10, 0],
            [5, 10],
        ];
        const out = rdpSimplify(tri, 1.0);
        expect(out).toBe(tri); // 同引用
        expect(out.length).toBe(3);
    });

    it('共线点删除：5 个共线点，tolerance=0.1 → 仅保留 2 端点', () => {
        // [0,0] [1,0] [2,0] [3,0] [4,0]：所有中间点都在首尾连线上
        const line: Polygon = [
            [0, 0],
            [1, 0],
            [2, 0],
            [3, 0],
            [4, 0],
        ];
        const out = rdpSimplify(line, 0.1);
        expect(out.length).toBe(2);
        expect(out[0]).toEqual([0, 0]);
        expect(out[1]).toEqual([4, 0]);
    });

    it('tolerance=0 → 直接 return 原数组（保留全部点）', () => {
        const poly: Polygon = [
            [0, 0],
            [1, 1],
            [2, 0],
            [3, 1],
            [4, 0],
        ];
        const out = rdpSimplify(poly, 0);
        expect(out).toBe(poly); // 同引用
        expect(out.length).toBe(5);
    });

    it('tolerance 非有限（NaN）→ return 原数组', () => {
        const poly: Polygon = [
            [0, 0],
            [1, 1],
            [2, 0],
            [3, 1],
            [4, 0],
        ];
        const out = rdpSimplify(poly, NaN);
        expect(out).toBe(poly);
    });

    it('大数据（1000 点曲线）不爆栈 + 输出合理', () => {
        const N = 1000;
        const poly: Polygon = [];
        for (let i = 0; i < N; i++) {
            const t = (i / N) * Math.PI * 2;
            poly.push([Math.cos(t) * 100, Math.sin(t) * 100]);
        }
        // 不抛 RangeError
        let out: Polygon = [];
        expect(() => {
            out = rdpSimplify(poly, 1.0);
        }).not.toThrow();
        // tolerance 1px 简化 1000 点圆 → 应远小于 1000
        expect(out.length).toBeLessThan(N);
        // 端点保留
        expect(out[0]).toEqual(poly[0]);
        expect(out[out.length - 1]).toEqual(poly[poly.length - 1]);
    });

    it('显著拐点不被丢：方波 [0,0] [1,0] [1,10] [2,10] tolerance=0.5 保留 4 点', () => {
        const poly: Polygon = [
            [0, 0],
            [1, 0],
            [1, 10],
            [2, 10],
        ];
        const out = rdpSimplify(poly, 0.5);
        // 拐点 [1,0] / [1,10] 距离首尾连线远，应保留
        expect(out.length).toBe(4);
    });
});
