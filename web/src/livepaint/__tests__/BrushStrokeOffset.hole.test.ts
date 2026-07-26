/**
 * 闭合笔画（画一个圈）会 union 出一个环形，圈内空白是内孔。
 *
 * 早先这里只取 poly[0] 外环、注释还写着"brush stroke 不可能产生 hole"，
 * 于是圈内被算成占用区：用户画个圈想给圈里填色，油漆桶找不到空隙，
 * 转去走"点元素内部改填充"，笔触又不支持填充，最后只得到一句"不支持"。
 */
import { describe, it, expect } from 'vitest';
import { brushStrokeToMultiPolygon, brushStrokeToPolygon } from '../BrushStrokeOffset';
import { buildGraph, findGapAt } from '../LivePaintCore';
import type { Element } from '@/types/protocol';

/** 圆心 (cx, cy) 半径 r 的闭合笔画采样点（首尾接上）。 */
function ringStroke(cx: number, cy: number, r: number, n = 48): Array<{ x: number; y: number }> {
    const pts: Array<{ x: number; y: number }> = [];
    for (let i = 0; i <= n; i++) {
        const t = (i / n) * Math.PI * 2;
        pts.push({ x: cx + Math.cos(t) * r, y: cy + Math.sin(t) * r });
    }
    return pts;
}

describe('闭合笔画保留内孔', () => {
    it('画一个圈 → 输出的 polygon 带内孔 ring', () => {
        const multi = brushStrokeToMultiPolygon(ringStroke(100, 100, 40), 6);
        expect(multi).not.toBeNull();
        const withHole = multi!.filter(poly => poly.length > 1);
        expect(withHole.length).toBeGreaterThan(0);
    });

    it('直线笔画不会凭空多出内孔（回归守卫）', () => {
        const multi = brushStrokeToMultiPolygon(
            [{ x: 0, y: 0 }, { x: 50, y: 0 }, { x: 100, y: 0 }], 10,
        );
        expect(multi).not.toBeNull();
        expect(multi!.every(poly => poly.length === 1)).toBe(true);
    });

    it('brushStrokeToPolygon 仍返回外轮廓单 ring（兼容旧调用方）', () => {
        const outer = brushStrokeToPolygon(ringStroke(100, 100, 40), 6);
        expect(outer).not.toBeNull();
        expect(outer!.length).toBeGreaterThanOrEqual(3);
    });

    it('圈心在 gap 图里是可填充空隙，圈上的笔画本身不是', async () => {
        const el = {
            id: 'e-1', type: 'brush', x: 0, y: 0, w: 256, h: 256, rotation: 0,
            visible: true, locked: false, size: 6,
            fill: { type: 'solid', color: '#000000' },
            // points 相对 element 原点；这里 element 原点就是 (0,0)
            points: ringStroke(100, 100, 40).map(p => ({ x: p.x, y: p.y, pressure: 1 })),
        } as unknown as Element;

        const graph = await buildGraph([el], 256, 256);
        expect(graph.degraded).toBeFalsy();
        // 圆心：以前被当成占用区 → null；修好后应命中一个空隙
        expect(findGapAt(graph, 100, 100)).not.toBeNull();
        // 笔画本体（圆周上）仍然是占用区
        expect(findGapAt(graph, 140, 100)).toBeNull();
    });
});
