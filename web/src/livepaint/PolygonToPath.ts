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

import type { GapPolygon } from './types';

/** 顶点数软警告阈值；超此值后端可能因 d 字符串过长拒绝。 */
export const VERTEX_WARN_THRESHOLD = 180;

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
export function gapToPathElement(gap: GapPolygon): {
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
    const localGap: GapPolygon = {
        outer: gap.outer.map(shift),
        holes: gap.holes.map(h => h.map(shift)),
    };

    const vertexCount = gap.outer.length + gap.holes.reduce((s, h) => s + h.length, 0);
    if (vertexCount > VERTEX_WARN_THRESHOLD) {
        console.warn(
            `[LivePaint] gap vertex count ${vertexCount} > ${VERTEX_WARN_THRESHOLD};`
            + ' may exceed PathDValidator MAX_LEN=4096. RDP simplification deferred to M18-P4.',
        );
    }

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
