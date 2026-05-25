/**
 * M18 Live Paint — Element → 单 ring Polygon 转换。
 *
 * 各元素类型采样策略：
 * - rect / icon / text / image / brush：bbox 4 顶点（text 字形 / image alpha / brush 像素级 v2）
 * - circle：椭圆 32 点采样（CircleElement 注释明确支持 w ≠ h 椭圆）
 * - shape：sides 个正多边形顶点（star 用 2×sides 交替外/内）
 * - path：自实装 M / L / Q / C / Z 子集解析 + de Casteljau 12 段采样；多 subpath v1 只取第一段
 *
 * 所有 element 应用 rotation（绕 bbox 中心，弧度 = rotation × π / 180）。
 *
 * 输出 polygon 约定：
 * - 至少 3 顶点（< 3 / 含 NaN / Infinity 返回 null）
 * - 首点不复制为末点
 * - 不强制方向（CCW/CW 都行，polygon-clipping 自处理）
 *
 * 关于自交：用户的 path 可能自交；本模块尽力而为，不主动检测——polygon-clipping
 * 容忍但行为可能略偏。M18-P4 hover preview 期间会观察 corner case。
 */

import type { BrushStrokeElement, Element, TextElement } from '@/types/protocol';
import type { Polygon } from './types';
import { brushStrokeToPolygon } from './BrushStrokeOffset';
import { textElementToPolygon } from './TextGlyphExtractor';

/** 椭圆采样点数。32 在 128 像素半径下视觉上几乎无棱角；更多会让 union 顶点爆炸。 */
export const CIRCLE_SAMPLE_POINTS = 32;

/** Q / C 贝塞尔每段采样段数（产生 N + 1 个点，但起点与上段终点合并）。 */
export const PATH_CURVE_SAMPLES = 12;

/** 主入口：Element → Polygon | null。null 表示不参与 Live Paint（visible=false / 退化）。 */
export function elementToPolygon(el: Element): Polygon | null {
    if (el.visible === false) return null;
    if (!isFinite(el.x) || !isFinite(el.y) || !isFinite(el.w) || !isFinite(el.h)) return null;
    if (el.w <= 0 || el.h <= 0) return null;

    let local: Polygon | null;
    switch (el.type) {
        case 'rect':
        case 'text':
        case 'icon':
        case 'image':
            local = bboxPolygon(el.x, el.y, el.w, el.h);
            break;
        case 'brush': {
            // M18-P5+：brush 升级到真实 stroke offset polygon（替代 bbox 兜底）。
            // points 是 element-local；这里加上 (el.x, el.y) 转全局。
            const brush = el as BrushStrokeElement;
            const globalPts = (brush.points ?? []).map(p => ({
                x: brush.x + p.x,
                y: brush.y + p.y,
            }));
            local = brushStrokeToPolygon(globalPts, brush.size);
            if (local === null) {
                // 退化几何（0 点 / size ≤ 0 / union 失败）→ fallback bbox 仍参与 union
                local = bboxPolygon(el.x, el.y, el.w, el.h);
            }
            break;
        }
        case 'circle':
            local = circlePolygon(el.x, el.y, el.w, el.h);
            break;
        case 'shape':
            local = shapePolygon(el.x, el.y, el.w, el.h, el.kind, el.sides, el.innerRatio);
            break;
        case 'path':
            local = pathPolygon(el.x, el.y, el.d);
            if (local === null) {
                // path 解析失败或为空 → fallback 到 bbox（仍参与 union，避免漏算）
                local = bboxPolygon(el.x, el.y, el.w, el.h);
            }
            break;
        default: {
            // 防御性 fallback：未来新元素类型先用 bbox 兜底
            const anyEl = el as { x: number; y: number; w: number; h: number };
            local = bboxPolygon(anyEl.x, anyEl.y, anyEl.w, anyEl.h);
            break;
        }
    }

    if (local === null || local.length < 3) return null;

    const rotation = (el.rotation ?? 0) % 360;
    const rotated = rotation === 0 ? local : rotatePolygon(local, el.x + el.w / 2, el.y + el.h / 2, rotation);

    // 出口校验
    for (const [x, y] of rotated) {
        if (!isFinite(x) || !isFinite(y)) return null;
    }
    return rotated;
}

/**
 * 0.4.9 Sub B：异步版本——text 元素走 fontkit 真实 glyph polygon；其他类型同步返回。
 *
 * 调用方（如 LivePaintCore.buildGraph）需 await；旧 sync `elementToPolygon` 保留以便
 * 不需要 glyph 精度的场景沿用（向下兼容）。Worker 内 buildGraph 升级为 async 后调用本版本。
 *
 * 失败 / null fallback 与同步版本一致：调用方决定是 fallback bbox 还是丢弃。
 */
export async function elementToPolygonAsync(el: Element): Promise<Polygon | null> {
    if (el.visible === false) return null;
    if (!isFinite(el.x) || !isFinite(el.y) || !isFinite(el.w) || !isFinite(el.h)) return null;
    if (el.w <= 0 || el.h <= 0) return null;

    if (el.type === 'text') {
        const textEl = el as TextElement;
        let local: Polygon | null = null;
        try {
            local = await textElementToPolygon(textEl);
        } catch {
            local = null;
        }
        // glyph 提取失败 → fallback bbox（与同步版本对 text 的行为一致）
        if (local === null || local.length < 3) {
            local = [
                [textEl.x, textEl.y],
                [textEl.x + textEl.w, textEl.y],
                [textEl.x + textEl.w, textEl.y + textEl.h],
                [textEl.x, textEl.y + textEl.h],
            ];
        }
        const rotation = (textEl.rotation ?? 0) % 360;
        const rotated = rotation === 0 ? local : rotatePolygon(local, textEl.x + textEl.w / 2, textEl.y + textEl.h / 2, rotation);
        for (const [x, y] of rotated) {
            if (!isFinite(x) || !isFinite(y)) return null;
        }
        return rotated;
    }
    // 非 text → 直接走同步路径
    return elementToPolygon(el);
}

// ---------- helpers ----------

function bboxPolygon(x: number, y: number, w: number, h: number): Polygon {
    return [
        [x, y],
        [x + w, y],
        [x + w, y + h],
        [x, y + h],
    ];
}

function circlePolygon(x: number, y: number, w: number, h: number): Polygon {
    const cx = x + w / 2;
    const cy = y + h / 2;
    const rx = w / 2;
    const ry = h / 2;
    const out: Polygon = [];
    for (let i = 0; i < CIRCLE_SAMPLE_POINTS; i++) {
        const t = (i / CIRCLE_SAMPLE_POINTS) * Math.PI * 2;
        out.push([cx + Math.cos(t) * rx, cy + Math.sin(t) * ry]);
    }
    return out;
}

function shapePolygon(
    x: number,
    y: number,
    w: number,
    h: number,
    kind: 'polygon' | 'star',
    sides: number,
    innerRatio?: number,
): Polygon {
    const cx = x + w / 2;
    const cy = y + h / 2;
    const r = Math.min(w, h) / 2;
    const inner = (innerRatio ?? 0.5) * r;
    // 起始角 −π/2 → 顶点朝上（与 ShapeElement 视觉约定对齐）
    const startAngle = -Math.PI / 2;
    const out: Polygon = [];
    if (kind === 'star') {
        const n = sides * 2;
        for (let i = 0; i < n; i++) {
            const t = startAngle + (i / n) * Math.PI * 2;
            const radius = (i % 2 === 0) ? r : inner;
            out.push([cx + Math.cos(t) * radius, cy + Math.sin(t) * radius]);
        }
    } else {
        for (let i = 0; i < sides; i++) {
            const t = startAngle + (i / sides) * Math.PI * 2;
            out.push([cx + Math.cos(t) * r, cy + Math.sin(t) * r]);
        }
    }
    return out;
}

function rotatePolygon(poly: Polygon, cx: number, cy: number, degrees: number): Polygon {
    const rad = (degrees * Math.PI) / 180;
    const cos = Math.cos(rad);
    const sin = Math.sin(rad);
    return poly.map(([px, py]) => {
        const dx = px - cx;
        const dy = py - cy;
        return [cx + dx * cos - dy * sin, cy + dx * sin + dy * cos] as [number, number];
    });
}

// ---------- path 子集解析 + 采样 ----------

/**
 * 解析 PathElement.d 子集 → 单 ring polygon（取第一个 subpath）。
 * 坐标在调用方角度是 element-local；这里加上 (originX, originY) 转全局。
 *
 * 返回 null：完全无 segment / 第一 subpath 不到 3 点。
 */
function pathPolygon(originX: number, originY: number, d: string | undefined | null): Polygon | null {
    if (!d) return null;
    const points: Array<[number, number]> = [];

    let curX = 0;
    let curY = 0;
    let subStartX = 0;
    let subStartY = 0;
    let subpathStartedIdx = -1;     // points 数组中第一个 subpath 起点的索引；-1 = 还没开始
    let subpathClosed = false;       // 第一个 subpath 是否已 Z

    let curCmd = '';
    let paramsPerCmd = 0;
    const paramBuf: number[] = [];
    let firstGroupForCmd = true;

    const pushPoint = (x: number, y: number): void => {
        // 避免重复连续点
        const last = points[points.length - 1];
        if (last && Math.abs(last[0] - x) < 1e-6 && Math.abs(last[1] - y) < 1e-6) return;
        points.push([x + originX, y + originY]);
    };

    let i = 0;
    const n = d.length;
    while (i < n && !subpathClosed) {
        const c = d[i];
        if (isSep(c)) { i++; continue; }
        if (isCmd(c)) {
            curCmd = c;
            paramsPerCmd = paramsForCommand(c);
            paramBuf.length = 0;
            firstGroupForCmd = true;
            i++;
            if (paramsPerCmd === 0) {
                // Z / z
                if (subpathStartedIdx >= 0) {
                    // 第一个 subpath 闭合 → 停止扫描
                    subpathClosed = true;
                }
                curX = subStartX;
                curY = subStartY;
                curCmd = '';
            }
            continue;
        }
        const scanned = scanNumber(d, i);
        if (scanned.endIdx === i) break;
        i = scanned.endIdx;
        if (!curCmd) continue;
        paramBuf.push(scanned.value);
        if (paramBuf.length < paramsPerCmd) continue;

        const relative = curCmd.toLowerCase() === curCmd;
        const op = curCmd.toUpperCase();
        switch (op) {
            case 'M': {
                const x = relative ? curX + paramBuf[0] : paramBuf[0];
                const y = relative ? curY + paramBuf[1] : paramBuf[1];
                if (firstGroupForCmd) {
                    if (subpathStartedIdx >= 0) {
                        // 已开始过第一个 subpath，遇到新 M → 第一 subpath 结束（不闭合也停）
                        subpathClosed = true;
                        break;
                    }
                    subpathStartedIdx = points.length;
                    pushPoint(x, y);
                    subStartX = x; subStartY = y;
                    firstGroupForCmd = false;
                } else {
                    // 隐式 lineto
                    pushPoint(x, y);
                }
                curX = x; curY = y;
                break;
            }
            case 'L': {
                const x = relative ? curX + paramBuf[0] : paramBuf[0];
                const y = relative ? curY + paramBuf[1] : paramBuf[1];
                pushPoint(x, y);
                curX = x; curY = y;
                firstGroupForCmd = false;
                break;
            }
            case 'Q': {
                const cx = relative ? curX + paramBuf[0] : paramBuf[0];
                const cy = relative ? curY + paramBuf[1] : paramBuf[1];
                const x = relative ? curX + paramBuf[2] : paramBuf[2];
                const y = relative ? curY + paramBuf[3] : paramBuf[3];
                sampleQuadratic(curX, curY, cx, cy, x, y, pushPoint);
                curX = x; curY = y;
                firstGroupForCmd = false;
                break;
            }
            case 'C': {
                const c1x = relative ? curX + paramBuf[0] : paramBuf[0];
                const c1y = relative ? curY + paramBuf[1] : paramBuf[1];
                const c2x = relative ? curX + paramBuf[2] : paramBuf[2];
                const c2y = relative ? curY + paramBuf[3] : paramBuf[3];
                const x = relative ? curX + paramBuf[4] : paramBuf[4];
                const y = relative ? curY + paramBuf[5] : paramBuf[5];
                sampleCubic(curX, curY, c1x, c1y, c2x, c2y, x, y, pushPoint);
                curX = x; curY = y;
                firstGroupForCmd = false;
                break;
            }
        }
        paramBuf.length = 0;
    }

    if (points.length < 3) return null;
    return points;
}

function sampleQuadratic(
    x0: number, y0: number,
    x1: number, y1: number,
    x2: number, y2: number,
    push: (x: number, y: number) => void,
): void {
    // 不 push 起点（与上段末点重合）；push 中间 + 终点共 PATH_CURVE_SAMPLES 个新点
    for (let i = 1; i <= PATH_CURVE_SAMPLES; i++) {
        const t = i / PATH_CURVE_SAMPLES;
        const mt = 1 - t;
        const x = mt * mt * x0 + 2 * mt * t * x1 + t * t * x2;
        const y = mt * mt * y0 + 2 * mt * t * y1 + t * t * y2;
        push(x, y);
    }
}

function sampleCubic(
    x0: number, y0: number,
    x1: number, y1: number,
    x2: number, y2: number,
    x3: number, y3: number,
    push: (x: number, y: number) => void,
): void {
    for (let i = 1; i <= PATH_CURVE_SAMPLES; i++) {
        const t = i / PATH_CURVE_SAMPLES;
        const mt = 1 - t;
        const mt2 = mt * mt;
        const t2 = t * t;
        const x = mt2 * mt * x0 + 3 * mt2 * t * x1 + 3 * mt * t2 * x2 + t2 * t * x3;
        const y = mt2 * mt * y0 + 3 * mt2 * t * y1 + 3 * mt * t2 * y2 + t2 * t * y3;
        push(x, y);
    }
}

function paramsForCommand(c: string): number {
    switch (c.toLowerCase()) {
        case 'm':
        case 'l': return 2;
        case 'q': return 4;
        case 'c': return 6;
        case 'z': return 0;
        default: return -1;
    }
}

function isCmd(c: string): boolean {
    const lc = c.toLowerCase();
    return lc === 'm' || lc === 'l' || lc === 'q' || lc === 'c' || lc === 'z';
}

function isSep(c: string): boolean {
    return c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === ',';
}

/** 与 PathParser.scanNumber 同语义。 */
function scanNumber(s: string, i: number): { value: number; endIdx: number } {
    const n = s.length;
    let j = i;
    if (j < n && (s[j] === '+' || s[j] === '-')) j++;
    let hasDigit = false;
    while (j < n && isDigit(s[j])) { j++; hasDigit = true; }
    if (j < n && s[j] === '.') {
        j++;
        while (j < n && isDigit(s[j])) { j++; hasDigit = true; }
    }
    if (!hasDigit) return { value: 0, endIdx: i };
    if (j < n && (s[j] === 'e' || s[j] === 'E')) {
        const eIdx = j;
        j++;
        if (j < n && (s[j] === '+' || s[j] === '-')) j++;
        let hasExpDigit = false;
        while (j < n && isDigit(s[j])) { j++; hasExpDigit = true; }
        if (!hasExpDigit) {
            const v = parseFloat(s.substring(i, eIdx));
            return { value: Number.isFinite(v) ? v : 0, endIdx: eIdx };
        }
    }
    const v = parseFloat(s.substring(i, j));
    return { value: Number.isFinite(v) ? v : 0, endIdx: Number.isFinite(v) ? j : i };
}

function isDigit(c: string): boolean {
    return c >= '0' && c <= '9';
}
