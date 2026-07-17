/**
 * Live Paint — Ramer–Douglas–Peucker 多边形顶点简化。
 *
 * 用途：buildGraph 输出的 gap polygon 顶点数可能因 polygon-clipping union 后膨胀
 * （多 element 边相交产生交点）。PathDValidator MAX_LEN=4096 char、每顶点 ~17 char →
 * 硬上限 ~240 顶点。{@link PolygonToPath.VERTEX_WARN_THRESHOLD}=180 起警告。
 *
 * 算法：保留首尾点，递归地用首尾连线做基准；找距连线最远的点 i：
 *   - 若距离 ≥ tolerance：保留 i，对 [0..i] / [i..n-1] 两段递归
 *   - 否则全部丢弃中间点（只留首尾）
 *
 * 闭合 polygon 处理：本模块约定首点不重复为末点。为了让 RDP 也能简化"首尾连接处"
 * 的冗余顶点，实现上把 polygon 当 open polyline 跑（首尾固定），不专门处理 wrap。
 * 实测对 union 输出已够用（瓶颈在长边采样产生的中间近共线点，不在 wrap 处）。
 *
 * 迭代而非递归：避免极端输入下栈深超过 ~1k 触发 RangeError。用显式 stack。
 */

import type { Polygon } from './types';

/**
 * 简化 polygon。返回新数组（不就地改）。tolerance ≤ 0 时直接 return poly 原样（无副本）。
 */
export function rdpSimplify(poly: Polygon, tolerance: number): Polygon {
    if (!Number.isFinite(tolerance) || tolerance <= 0) return poly;
    const n = poly.length;
    if (n <= 3) return poly;

    // keep[i] = true 表示该点保留
    const keep = new Array<boolean>(n).fill(false);
    keep[0] = true;
    keep[n - 1] = true;

    // 迭代式栈：[startIdx, endIdx)（闭区间）
    const stack: Array<[number, number]> = [[0, n - 1]];
    while (stack.length > 0) {
        const seg = stack.pop()!;
        const a = seg[0];
        const b = seg[1];
        if (b - a < 2) continue;

        const [ax, ay] = poly[a];
        const [bx, by] = poly[b];
        // 退化（首尾重合）：用首尾距离 0 → 全部点都"远"，简化为只留首尾
        // 跳过细分；不保留任何中间点
        const segLenSq = (bx - ax) * (bx - ax) + (by - ay) * (by - ay);
        if (segLenSq < 1e-12) continue;

        let maxDist = -1;
        let maxIdx = -1;
        for (let i = a + 1; i < b; i++) {
            const [px, py] = poly[i];
            // 点到线段距离²（数值稳定的投影 + 截断）
            const t = ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / segLenSq;
            let dx: number, dy: number;
            if (t < 0) {
                dx = px - ax; dy = py - ay;
            } else if (t > 1) {
                dx = px - bx; dy = py - by;
            } else {
                const projX = ax + t * (bx - ax);
                const projY = ay + t * (by - ay);
                dx = px - projX; dy = py - projY;
            }
            const dist = Math.sqrt(dx * dx + dy * dy);
            if (dist > maxDist) {
                maxDist = dist;
                maxIdx = i;
            }
        }

        if (maxDist >= tolerance && maxIdx > 0) {
            keep[maxIdx] = true;
            stack.push([a, maxIdx]);
            stack.push([maxIdx, b]);
        }
    }

    const out: Polygon = [];
    for (let i = 0; i < n; i++) {
        if (keep[i]) out.push(poly[i]);
    }
    return out;
}
