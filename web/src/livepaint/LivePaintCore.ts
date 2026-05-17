/**
 * M18 Live Paint — union / difference / find gap 主流程。
 *
 * 流程：
 *   1. 收集 element polygon（ElementToPolygon）
 *   2. polygonClipping.union(...polys) = 占用区域（MultiPolygon）
 *   3. polygonClipping.difference(canvasRect, occupied) = 所有 gap（MultiPolygon）
 *   4. 把库的 MultiPolygon 拆成本模块 GapPolygon[]（每个含 outer + holes，去掉末点重复）
 *
 * 关键类型坑：
 *   - 库的 Polygon = Ring[]（多环含 holes，末点 = 首点）
 *   - 本模块 Polygon = Ring（单环，首点不复制）
 *   - 文件内用 PCRing / PCPolygon 区分
 */

import polygonClipping from 'polygon-clipping';
import type { Pair, Polygon as PCPolygon, Ring as PCRing } from 'polygon-clipping';
import type { Element } from '@/types/protocol';
import type { GapPolygon, LivePaintGraph, Polygon } from './types';
import { elementToPolygon } from './ElementToPolygon';

/** 构建当前画布的 gap graph。同步主线程；M18-P2 会移到 Worker。 */
export function buildGraph(
    elements: Element[],
    canvasWidth: number,
    canvasHeight: number,
): LivePaintGraph {
    if (!isFinite(canvasWidth) || !isFinite(canvasHeight) || canvasWidth <= 0 || canvasHeight <= 0) {
        return { gaps: [], canvasWidth: 0, canvasHeight: 0 };
    }

    /** 极小 gap 阈值（px²）。polygon-clipping 在数值精度边界偶尔输出近零面积 polygon；
     *  这些 polygon 用户根本点不中，留着只会让 findGapAt 多扫一次。4px² ≈ 2×2 像素，
     *  低于这个面积的 gap 实际不可达。 */
    const MIN_GAP_AREA = 4;

    // 收集 polygon
    const polys: Polygon[] = [];
    for (const el of elements) {
        const p = elementToPolygon(el);
        if (p !== null && p.length >= 3) polys.push(p);
    }

    // 整画布作为初始 gap
    const canvasRect: PCPolygon = [closeRing(toPCRing([
        [0, 0],
        [canvasWidth, 0],
        [canvasWidth, canvasHeight],
        [0, canvasHeight],
    ]))];

    if (polys.length === 0) {
        return {
            gaps: [{
                outer: [[0, 0], [canvasWidth, 0], [canvasWidth, canvasHeight], [0, canvasHeight]],
                holes: [],
            }],
            canvasWidth,
            canvasHeight,
        };
    }

    // 转 polygon-clipping 输入：每个本模块 Polygon → 单 ring PCPolygon
    const pcPolys: PCPolygon[] = polys.map(p => [closeRing(toPCRing(p))]);

    let gapsRaw: ReturnType<typeof polygonClipping.difference>;
    try {
        // union 多个 polygon → MultiPolygon
        const occupied = polygonClipping.union(pcPolys[0], ...pcPolys.slice(1));
        // canvas - occupied → 所有空隙
        gapsRaw = polygonClipping.difference(canvasRect, occupied);
    } catch (e) {
        // M18-P4：polygon-clipping 在退化输入（自交 / 重合边 / 共线）下偶尔抛
        // "Unable to complete output ring" 等。M18-P1 旧策略是降级为"整画布单 gap"，
        // 但这会让用户误以为油漆桶能用——实际点哪里都填充整画布，行为完全不符。
        // P4 改为 degraded=true + 空 gap 列表，让 UI 显式提示"不可用"。
        console.warn('[LivePaint] buildGraph union/difference failed:', e);
        return {
            gaps: [],
            canvasWidth,
            canvasHeight,
            degraded: true,
        };
    }

    // MultiPolygon → GapPolygon[]
    // M18-P4：极小 gap（面积 < MIN_GAP_AREA px²）整体丢弃；这些来自 polygon-clipping
    // 数值精度噪声，用户点不中也增加 findGapAt 扫描成本。
    const gaps: GapPolygon[] = [];
    for (const poly of gapsRaw) {
        if (poly.length === 0) continue;
        const outer = fromPCRing(poly[0]);
        if (outer.length < 3) continue;
        if (polygonArea(outer) < MIN_GAP_AREA) continue;
        const holes: Polygon[] = [];
        for (let i = 1; i < poly.length; i++) {
            const hole = fromPCRing(poly[i]);
            // hole 不做面积过滤——极小 hole 也是 gap 形状的一部分（虽然渲染上几乎不可见，
            // 但保留让 even-odd fill 行为与几何意图一致）。
            if (hole.length >= 3) holes.push(hole);
        }
        gaps.push({ outer, holes });
    }

    return { gaps, canvasWidth, canvasHeight };
}

/**
 * Shoelace formula 算 polygon 面积。约定首点不重复末点。
 * 输出绝对值（不区分 CW/CCW）。M18-P4 用于过滤数值精度噪声生成的极小 gap。
 */
function polygonArea(poly: Polygon): number {
    const n = poly.length;
    if (n < 3) return 0;
    let area = 0;
    for (let i = 0; i < n; i++) {
        const [x1, y1] = poly[i];
        const [x2, y2] = poly[(i + 1) % n];
        area += x1 * y2 - x2 * y1;
    }
    return Math.abs(area) / 2;
}

/** 在 graph 内找包含 (px, py) 的 gap。null = 命中 element 或画布外。 */
export function findGapAt(graph: LivePaintGraph, px: number, py: number): GapPolygon | null {
    if (!isFinite(px) || !isFinite(py)) return null;
    for (const gap of graph.gaps) {
        if (!pointInPolygon(px, py, gap.outer)) continue;
        let inHole = false;
        for (const hole of gap.holes) {
            if (pointInPolygon(px, py, hole)) { inHole = true; break; }
        }
        if (!inHole) return gap;
    }
    return null;
}

// ---------- 内部 helpers ----------

/**
 * 标准 ray casting point-in-polygon。O(n)。
 *
 * M18-P4：从 file-private 升级为 export，供 CanvasView 在"点击 element 内部 vector-fill"
 * 路径上做精确命中（仅 bbox hit 会让 circle / star 在角落区域误中）。
 *
 * 输入约定：poly 首点不重复末点（本模块 Polygon 约定）。
 */
export function pointInPolygon(px: number, py: number, poly: Polygon): boolean {
    let inside = false;
    const n = poly.length;
    for (let i = 0, j = n - 1; i < n; j = i++) {
        const xi = poly[i][0], yi = poly[i][1];
        const xj = poly[j][0], yj = poly[j][1];
        const intersect = ((yi > py) !== (yj > py))
            && (px < (xj - xi) * (py - yi) / (yj - yi + 1e-30) + xi);
        if (intersect) inside = !inside;
    }
    return inside;
}

/** 本模块 Polygon → 库 Ring（顶点序不变；不加末点）。 */
function toPCRing(poly: Polygon): PCRing {
    return poly.map(([x, y]) => [x, y] as Pair);
}

/** 库 Ring（含末点 = 首点）→ 本模块 Polygon（去掉末点重复）。 */
function fromPCRing(ring: PCRing): Polygon {
    if (ring.length === 0) return [];
    const last = ring.length - 1;
    if (ring.length >= 2) {
        const a = ring[0];
        const b = ring[last];
        if (Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9) {
            return ring.slice(0, last).map(p => [p[0], p[1]] as [number, number]);
        }
    }
    return ring.map(p => [p[0], p[1]] as [number, number]);
}

/** 给 ring 末尾补一个首点拷贝（polygon-clipping 输入需要 GeoJSON-style 闭环）。 */
function closeRing(ring: PCRing): PCRing {
    if (ring.length === 0) return ring;
    const first = ring[0];
    const last = ring[ring.length - 1];
    if (first[0] === last[0] && first[1] === last[1]) return ring;
    return [...ring, [first[0], first[1]] as Pair];
}
