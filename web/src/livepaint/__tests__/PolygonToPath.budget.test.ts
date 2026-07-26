/**
 * 服务端 PathDValidator 唯一的规模限制是 d 字符串<b>总长</b> 4096，
 * 而简化逻辑是逐个 ring 各限 240 顶点、没有总量预算。
 * 十来个小岛（每个 ring 都远没到 240 顶点）加起来就能超 4096 字符：
 * element.add 被拒、填充失败，可鼠标悬停的高亮还在说"这里能填"。
 */
import { describe, it, expect } from 'vitest';
import { gapToPathElement, PATH_D_BUDGET, MAX_PATH_D_LEN } from '../PolygonToPath';
import type { GapPolygon, Polygon } from '../types';

/** 圆心 (cx, cy) 半径 r 的采样多边形。 */
function circle(cx: number, cy: number, r: number, n = 32): Polygon {
    const out: Polygon = [];
    for (let i = 0; i < n; i++) {
        const t = (i / n) * Math.PI * 2;
        out.push([cx + Math.cos(t) * r, cy + Math.sin(t) * r]);
    }
    return out;
}

/**
 * 锯齿状小岛：半径在 30 / 6 之间交替，起伏远超最大简化容差（16px），
 * RDP 怎么抬容差都删不掉这些顶点 —— 用来构造"简化救不回来"的场景。
 */
function jaggedIsland(cx: number, cy: number, n = 12): Polygon {
    const out: Polygon = [];
    for (let i = 0; i < n; i++) {
        const t = (i / n) * Math.PI * 2;
        const r = i % 2 === 0 ? 30 : 6;
        out.push([cx + Math.cos(t) * r, cy + Math.sin(t) * r]);
    }
    return out;
}

/** 一个大外环 + n 个圆形小岛。 */
function gapWithIslands(n: number): GapPolygon {
    const holes: Polygon[] = [];
    for (let i = 0; i < n; i++) {
        holes.push(circle(30 + (i % 6) * 40, 30 + Math.floor(i / 6) * 40, 12));
    }
    return {
        outer: [[0, 0], [256, 0], [256, 256], [0, 256]],
        holes,
    };
}

/** 一个大外环 + n 个锯齿小岛（简化压不下去）。 */
function gapWithJaggedIslands(n: number): GapPolygon {
    const holes: Polygon[] = [];
    for (let i = 0; i < n; i++) {
        holes.push(jaggedIsland(60 + (i % 8) * 80, 60 + Math.floor(i / 8) * 80));
    }
    return {
        outer: [[0, 0], [640, 0], [640, 640], [0, 640]],
        holes,
    };
}

describe('gapToPathElement 的 d 总长预算', () => {
    it('预算不超过服务端 PathDValidator.MAX_LEN', () => {
        expect(PATH_D_BUDGET).toBeLessThan(MAX_PATH_D_LEN);
    });

    it('单个简单空隙不受影响', () => {
        const out = gapToPathElement({ outer: circle(64, 64, 40), holes: [] });
        expect(out.overBudget).toBe(false);
        expect(out.d.length).toBeLessThanOrEqual(PATH_D_BUDGET);
    });

    it('十几个圆形小岛：抬容差后被压进预算，正常提交', () => {
        const out = gapToPathElement(gapWithIslands(14));
        expect(out.overBudget).toBe(false);
        expect(out.d.length).toBeLessThanOrEqual(PATH_D_BUDGET);
    });

    it('抬到容差上限也压不进预算时标记 overBudget（调用方据此拦下这次提交）', () => {
        const out = gapToPathElement(gapWithJaggedIslands(60));
        expect(out.overBudget).toBe(true);
        expect(out.d.length).toBeGreaterThan(PATH_D_BUDGET);
    });

    it('中等复杂度：抬容差能救回来，不误报 overBudget', () => {
        const out = gapToPathElement(gapWithJaggedIslands(40));
        expect(out.overBudget).toBe(false);
        expect(out.d.length).toBeLessThanOrEqual(PATH_D_BUDGET);
    });
});
