/**
 * M18-P5+ Live Paint — Brush stroke offset polygon。
 *
 * 输入 brush 元素的采样点数组 + 笔触宽度，输出真实笔画形状的单 ring polygon。
 * 升级前：ElementToPolygon 对 brush 走 bbox 兜底，导致 Live Paint 把整个 bbox 当占用区域，
 * 用户点 brush 周围空白会误判为内部（bbox 比真实笔画大很多，尤其细长 stroke）。
 * 升级后：每 segment 矩形 + 接头 round join + 端点 round cap，用 polygon-clipping union
 * 合并为单一外环 polygon。
 *
 * 算法：
 *   1. 预处理：points > 80 时 rdpSimplify(tolerance=0.5) 简化
 *   2. 每 segment p[i] → p[i+1]：法向偏移 r → 4 顶点矩形
 *   3. 端点（首尾）：圆形 cap（半圆 16 段采样）
 *   4. 接头（内部点）：round join（圆弧 8 段采样，外侧凸角）—— 简化为完整圆盘
 *      让 union 自动处理凸/凹两侧（圆盘是凸的，与两边矩形 union 自然贴合）
 *   5. polygon-clipping union(...) 合并 → 单外环 polygon（不含 hole；brush stroke
 *      不可能产生 hole，self-intersection 也是 stroke 局部交叠）
 *
 * 性能：
 *   - 长 brush 经 RDP 简化到 ≤ 80 顶点 → ~80 个 segment + 81 个圆盘 join + 2 caps
 *     ≈ 163 个小 polygon，union 5-20ms（Worker 已隔离）
 *   - 单顶点 brush 退化为单一圆盘 polygon
 *
 * 退化处理：
 *   - 0 点 / size ≤ 0 → null（caller fallback bbox）
 *   - polygon-clipping union 抛错 → null
 *   - union 输出 multipoly（多外环）→ 只取面积最大的（不可能但防御）
 */

import polygonClipping from 'polygon-clipping';
import type { Pair, Polygon as PCPolygon, Ring as PCRing } from 'polygon-clipping';
import type { Polygon } from './types';
import { rdpSimplify } from './RdpSimplifier';

/** RDP 简化触发阈值（点数）。≤ 此阈值不做简化，> 此阈值降到 ≤ MAX_POINTS_AFTER_RDP。 */
export const RDP_THRESHOLD = 80;

/** RDP 默认 tolerance（px）；brush 笔触采样点本身已有精度，0.5 比较保守。 */
export const RDP_TOLERANCE = 0.5;

/** 端点 cap 采样段数（半圆）。16 在 size ≤ 64 下视觉无棱角；与 LivePaintCore 32 整圆采样保持比例。 */
export const CAP_SAMPLES = 16;

/** 接头 join 采样段数（整圆）。与端点 cap 数量级一致；接头处用整圆盘简化几何，让 union 自然贴合。 */
export const JOIN_SAMPLES = 16;

/**
 * 主入口：brush 采样点 + 基础宽度 → 真实笔画 polygon。
 *
 * @param points BrushPoint[]，坐标须已加上 (element.x, element.y) 偏移转全局
 * @param size BrushStrokeElement.size（基础宽度 px，半径 = size / 2）
 * @returns 单 ring polygon（首点不复制）；null = 退化输入 / union 失败
 */
export function brushStrokeToPolygon(
    points: Array<{ x: number; y: number }>,
    size: number,
): Polygon | null {
    if (!Number.isFinite(size) || size <= 0) return null;
    if (!points || points.length === 0) return null;

    // 过滤 NaN/Infinity 点
    const cleanPoints: Array<[number, number]> = [];
    for (const p of points) {
        if (Number.isFinite(p.x) && Number.isFinite(p.y)) {
            cleanPoints.push([p.x, p.y]);
        }
    }
    if (cleanPoints.length === 0) return null;

    const r = size / 2;
    if (r <= 0) return null;

    // 单点：直接返回圆盘
    if (cleanPoints.length === 1) {
        return diskPolygon(cleanPoints[0][0], cleanPoints[0][1], r, JOIN_SAMPLES);
    }

    // 预处理：去重连续重合点（防止 segment 长度 0 导致 NaN 法向）
    const dedup: Polygon = [cleanPoints[0]];
    for (let i = 1; i < cleanPoints.length; i++) {
        const prev = dedup[dedup.length - 1];
        const cur = cleanPoints[i];
        if (Math.abs(cur[0] - prev[0]) > 1e-9 || Math.abs(cur[1] - prev[1]) > 1e-9) {
            dedup.push(cur);
        }
    }
    if (dedup.length === 1) {
        return diskPolygon(dedup[0][0], dedup[0][1], r, JOIN_SAMPLES);
    }

    // RDP 简化（长 brush 性能优化）
    const simplified = dedup.length > RDP_THRESHOLD
        ? rdpSimplify(dedup, RDP_TOLERANCE)
        : dedup;
    // 简化后再去重一次（极端 tolerance 下可能仍有近重合点）
    const pts: Polygon = [simplified[0]];
    for (let i = 1; i < simplified.length; i++) {
        const prev = pts[pts.length - 1];
        const cur = simplified[i];
        if (Math.abs(cur[0] - prev[0]) > 1e-9 || Math.abs(cur[1] - prev[1]) > 1e-9) {
            pts.push(cur);
        }
    }
    if (pts.length === 1) {
        return diskPolygon(pts[0][0], pts[0][1], r, JOIN_SAMPLES);
    }

    // 构造小 polygon 集合：每 segment 矩形 + 每内部接头圆盘 + 首尾端点圆盘（圆盘同时充当 cap + join）
    // 用圆盘替代半圆 cap + 圆弧 join 让算法显著简化：圆盘是凸的，与矩形 union 后
    // 自然形成 round cap + round join 效果；多余的"半圆背面"部分被 segment 矩形覆盖时
    // 不影响最终轮廓（外部观察者看到的还是 round cap / round join）。
    const subPolygons: PCPolygon[] = [];

    // 每个点放一个圆盘（首尾 = cap，中间 = join）
    for (let i = 0; i < pts.length; i++) {
        const disk = diskPolygon(pts[i][0], pts[i][1], r, JOIN_SAMPLES);
        subPolygons.push([closeRing(toPCRing(disk))]);
    }

    // 每 segment 4 顶点矩形（法向偏移 r）
    for (let i = 0; i < pts.length - 1; i++) {
        const [x1, y1] = pts[i];
        const [x2, y2] = pts[i + 1];
        const dx = x2 - x1;
        const dy = y2 - y1;
        const len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-9) continue;
        const nx = -dy / len;     // perp = (-d.y, d.x) / len
        const ny = dx / len;
        const ox = nx * r;
        const oy = ny * r;
        const rect: Polygon = [
            [x1 + ox, y1 + oy],
            [x2 + ox, y2 + oy],
            [x2 - ox, y2 - oy],
            [x1 - ox, y1 - oy],
        ];
        subPolygons.push([closeRing(toPCRing(rect))]);
    }

    if (subPolygons.length === 0) return null;

    // polygon-clipping union 合并所有
    let merged: ReturnType<typeof polygonClipping.union>;
    try {
        merged = polygonClipping.union(subPolygons[0], ...subPolygons.slice(1));
    } catch (e) {
        if (import.meta.env && import.meta.env.DEV) {
            console.warn('[BrushStrokeOffset] union failed:', e);
        }
        return null;
    }

    // 输出为 MultiPolygon = Array<Polygon = Array<Ring>>
    // brush stroke union 理论上只产生 1 个 polygon（连续笔触）
    // 但 RDP 简化或浮点精度可能让 union 分裂成多个 — 选面积最大的外环
    if (!merged || merged.length === 0) return null;

    let bestRing: PCRing | null = null;
    let bestArea = -1;
    for (const poly of merged) {
        if (poly.length === 0) continue;
        const ring = poly[0]; // outer ring（库约定第一个 ring = outer）
        const polyOut = fromPCRing(ring);
        if (polyOut.length < 3) continue;
        const area = polygonArea(polyOut);
        if (area > bestArea) {
            bestArea = area;
            bestRing = ring;
        }
    }
    if (bestRing === null) return null;
    const outer = fromPCRing(bestRing);
    if (outer.length < 3) return null;

    // 出口校验
    for (const [x, y] of outer) {
        if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
    }
    return outer;
}

// ---------- helpers ----------

/**
 * 生成圆形 polygon（绕 (cx, cy) 半径 r，samples 段采样）。首点不复制末点。
 *
 * 关键：相位偏移 π/samples 让所有顶点错开半个采样段，避免与相邻 segment 矩形顶点
 * 完全重合（重合会触发 polygon-clipping "Unable to complete output ring" 浮点退化错误，
 * 实测在垂直 / 水平 segment 上 100% 命中）。
 */
function diskPolygon(cx: number, cy: number, r: number, samples: number): Polygon {
    const phase = Math.PI / samples;
    const out: Polygon = [];
    for (let i = 0; i < samples; i++) {
        const t = phase + (i / samples) * Math.PI * 2;
        out.push([cx + Math.cos(t) * r, cy + Math.sin(t) * r]);
    }
    return out;
}

/** Shoelace formula 算 polygon 面积（绝对值）。约定首点不重复末点。 */
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

/** 本模块 Polygon → 库 Ring。 */
function toPCRing(poly: Polygon): PCRing {
    return poly.map(([x, y]) => [x, y] as Pair);
}

/** 库 Ring → 本模块 Polygon（去掉末点重复）。 */
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
