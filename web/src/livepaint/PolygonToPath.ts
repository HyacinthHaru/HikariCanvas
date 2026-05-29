/**
 * M18 Live Paint — GapPolygon → SVG d 字符串 + PathElement payload。
 *
 * 输出约定：
 * - 一个 GapPolygon 转成多 subpath 的 d：外环一个 M..L..Z，每个 hole 一个 M..L..Z
 * - SVG 内多 M 配合 even-odd fill-rule（PathRenderer 默认即 even-odd）自动挖洞
 * - 坐标保留 2 位小数（toFixed(2)）
 * - {@link gapToPathElement} 输出相对 bbox (0,0)..(w,h) 的 d，与 PathElement.d 约定一致
 *
 * 顶点限制（M18-P1 仅警告，不简化）：
 * - PathDValidator.MAX_LEN = 4096 char；每顶点约 17 char → 约 240 顶点上限
 * - 安全阈值 180（留 holes / 模板字段余量）；超限 console.warn
 * - M18-P4 将做 Douglas-Peucker / RDP 简化
 */

import type { GapPolygon, Polygon } from './types';
import { rdpSimplify } from './RdpSimplifier';

/** 顶点数软警告阈值；超此值会触发 RDP 简化。 */
export const VERTEX_WARN_THRESHOLD = 180;

/**
 * 顶点硬上限。PathDValidator MAX_LEN = 4096，每顶点 fmt(...) 后约 17 char（"L1234.56 7890.12 "），
 * 留 holes / 模板字段余量后 ~240 为安全顶。超此值仍由 RDP 阶梯式抬 tolerance 尝试压缩；
 * 实在压不下来时只能 console.warn，由后端拒绝。
 */
export const VERTEX_HARD_LIMIT = 240;

/** 默认基础 tolerance（与 paintBucket store DEFAULT_TOLERANCE 一致，避免循环依赖故复制常量）。 */
export const DEFAULT_BASE_TOLERANCE = 0.5;

/**
 * M18-P4：阶梯式 RDP 简化。顶点 > VERTEX_WARN_THRESHOLD 时触发，初始 tolerance = baseTolerance，
 * 每轮翻倍直到 ≤ VERTEX_HARD_LIMIT 或 tolerance ≥ 16（再大就视觉退化太厉害，放弃）。
 * 每轮都用原 polygon 重算（而非上轮结果），避免误差累计偏移过大。
 *
 * v1.x 引入 baseTolerance 参数：用户可在 PaintBucketPanel 调（0.25..5 px）。
 * 低 → 更精细多顶点；高 → 平滑少顶点。fallback 阶梯（顶点仍超 LIMIT 时翻倍）逻辑保留。
 */
function maybeSimplify(poly: Polygon, baseTolerance: number): Polygon {
    if (poly.length <= VERTEX_WARN_THRESHOLD) {
        // 用户显式抬高 tolerance 时即便顶点不超阈值也尊重——给"我就想要更平滑边缘"的偏好。
        // baseTolerance ≤ DEFAULT_BASE_TOLERANCE 时不强加简化（保持视觉无差异）。
        if (baseTolerance > DEFAULT_BASE_TOLERANCE) {
            const simplified = rdpSimplify(poly, baseTolerance);
            return simplified.length >= 3 ? simplified : poly;
        }
        return poly;
    }
    let tol = baseTolerance > 0 ? baseTolerance : DEFAULT_BASE_TOLERANCE;
    let simplified = rdpSimplify(poly, tol);
    // 一轮就压到 LIMIT 以下当然好；不行就 tolerance 翻倍重来
    while (simplified.length > VERTEX_HARD_LIMIT && tol < 16) {
        tol *= 2;
        simplified = rdpSimplify(poly, tol);
    }
    if (simplified.length > VERTEX_HARD_LIMIT) {
        console.warn(
            `[LivePaint] RDP unable to reduce ${poly.length} verts below ${VERTEX_HARD_LIMIT};`
            + ` final = ${simplified.length} at tolerance ${tol}px.`
            + ' Server may reject the path; consider deleting overlapping elements.',
        );
    }
    // 与 ≤180 分支对齐：极端 tolerance 把 sliver 外环吸收到 2 点退化时，
    // 兜底返回未简化原 polygon（上游已保证 ≥3 顶点且面积 ≥ MIN_GAP_AREA），
    // 避免生成零面积退化路径 d（M..L..Z 仅两点）落库后渲染成不可见线段。
    return simplified.length >= 3 ? simplified : poly;
}

/** 一个 GapPolygon → 多 subpath SVG d 字符串。坐标 = 输入坐标系。 */
export function gapPolygonToPathD(gap: GapPolygon): string {
    const parts: string[] = [];
    const outerSub = polygonToSubpath(gap.outer);
    if (outerSub) parts.push(outerSub);
    for (const hole of gap.holes) {
        const sub = polygonToSubpath(hole);
        if (sub) parts.push(sub);
    }
    return parts.join(' ');
}

/**
 * GapPolygon → PathElement payload。把 gap 平移到本地坐标系 (0,0)..(w,h)。
 *
 * 调用方拿到后可直接：
 *   element.create { type: 'path', x, y, w, h, d, fill, rotation: 0, ... }
 */
export function gapToPathElement(gap: GapPolygon, baseTolerance: number = DEFAULT_BASE_TOLERANCE): {
    x: number;
    y: number;
    w: number;
    h: number;
    d: string;
    vertexCount: number;
} {
    // 收集全部点算 bbox
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    const accumulate = (poly: Array<[number, number]>): void => {
        for (const [x, y] of poly) {
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
        }
    };
    accumulate(gap.outer);
    for (const hole of gap.holes) accumulate(hole);

    if (!isFinite(minX) || !isFinite(minY) || !isFinite(maxX) || !isFinite(maxY)) {
        return { x: 0, y: 0, w: 0, h: 0, d: '', vertexCount: 0 };
    }

    const shift = ([x, y]: [number, number]): [number, number] => [x - minX, y - minY];
    // M18-P4：先对外环 / 每个 hole 做 RDP 简化（基于本地坐标，shift 后再算更稳定，
    // 因为 tolerance 是绝对像素阈值）。简化策略见 maybeSimplify。
    const shiftedOuter: Polygon = gap.outer.map(shift);
    const shiftedHoles: Polygon[] = gap.holes.map(h => h.map(shift));
    const simplifiedOuter = maybeSimplify(shiftedOuter, baseTolerance);
    const simplifiedHoles = shiftedHoles.map(h => maybeSimplify(h, baseTolerance));

    const localGap: GapPolygon = {
        outer: simplifiedOuter,
        holes: simplifiedHoles,
    };

    const vertexCount = simplifiedOuter.length + simplifiedHoles.reduce((s, h) => s + h.length, 0);

    return {
        x: minX,
        y: minY,
        w: maxX - minX,
        h: maxY - minY,
        d: gapPolygonToPathD(localGap),
        vertexCount,
    };
}

// ---------- helpers ----------

function polygonToSubpath(poly: Array<[number, number]>): string {
    if (poly.length === 0) return '';
    const [x0, y0] = poly[0];
    const parts: string[] = [`M${fmt(x0)} ${fmt(y0)}`];
    for (let i = 1; i < poly.length; i++) {
        const [x, y] = poly[i];
        parts.push(`L${fmt(x)} ${fmt(y)}`);
    }
    parts.push('Z');
    return parts.join(' ');
}

function fmt(v: number): string {
    // 2 位小数即可（128px × 128px wall 像素粒度，1/100 已远超视觉精度）
    return v.toFixed(2);
}
