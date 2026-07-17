// Fill 工具函数（前端镜像）。
//
// 后端 ac.haru.hikaricanvas.state.{Fill, SolidFill, LinearGradient, RadialGradient, Stop}
// 的 TS 侧消费者助手。

import type { Fill, FillCompat, LinearGradient, RadialGradient, SolidFill } from '../types/protocol';

/**
 * 把 string 形态 fill 归一化为 object 形态。其余按原样返回。
 * 工程文件 / 后端 v1 patch 兼容入口。
 */
export function normalizeFill(fill: FillCompat | null | undefined): Fill | undefined {
    if (fill === null || fill === undefined) return undefined;
    if (typeof fill === 'string') return { type: 'solid', color: fill };
    return fill;
}

/**
 * 临时桥接：把 Fill 拍平成一个 hex 颜色串供 ctx.fillStyle 用。
 * 渐变此路径取 stops[0].color 兜底显示（完整 CanvasGradient 走 fillToCanvasStyle）。
 */
export function fillToCss(fill: FillCompat | null | undefined): string | undefined {
    const f = normalizeFill(fill);
    if (!f) return undefined;
    if (f.type === 'solid') return f.color;
    // linear / radial
    return f.stops.length > 0 ? f.stops[0].color : '#FFFFFF';
}

/**
 * 抽取 fill 内全部颜色（用于 palette.projectColors 扫描去重）。
 */
export function fillColors(fill: FillCompat | null | undefined): string[] {
    const f = normalizeFill(fill);
    if (!f) return [];
    if (f.type === 'solid') return [f.color];
    return f.stops.map(s => s.color);
}

/**
 * 类型守卫：是否纯色填充。
 */
export function isSolidFill(fill: FillCompat | null | undefined): fill is SolidFill | string {
    if (fill === null || fill === undefined) return false;
    if (typeof fill === 'string') return true;
    return fill.type === 'solid';
}

/**
 * 把 {@link FillCompat} 转成 Canvas 2D `fillStyle` 接受的类型。
 *
 * <ul>
 *   <li>solid → hex string</li>
 *   <li>linear → {@link CanvasGradient}（{@code ctx.createLinearGradient}，端点由 bbox 4 角投影决定，
 *       与后端 {@code CanvasCompositor.buildLinearPaint} 公式逐行镜像）</li>
 *   <li>radial → {@link CanvasGradient}（{@code ctx.createRadialGradient}，内圆 r0=0，
 *       外圆 r1 = {@code g.r × min(bw,bh) / 2}；映射 Java {@code RadialGradientPaint} 单半径形态）</li>
 * </ul>
 *
 * @param bx 元素 bbox 左上 x（path 元素已 translate 时传 0）
 * @param by 元素 bbox 左上 y
 * @param bw 元素 bbox 宽
 * @param bh 元素 bbox 高
 */
export function fillToCanvasStyle(
    ctx: CanvasRenderingContext2D,
    fill: FillCompat | null | undefined,
    bx: number, by: number, bw: number, bh: number,
): string | CanvasGradient | undefined {
    const f = normalizeFill(fill);
    if (!f) return undefined;
    if (f.type === 'solid') return f.color;
    if (f.type === 'linear') return buildLinearGradient(ctx, f, bx, by, bw, bh);
    return buildRadialGradient(ctx, f, bx, by, bw, bh);
}

/** fallback 纯色（与后端 buildLinearPaint/buildRadialPaint 空 stops 时的 Color.WHITE 对齐）。 */
const FILL_FALLBACK_WHITE = '#FFFFFF';

/**
 * 剔除 position 非有限的 stops（镜像后端 FillPaintBuilder.filterFiniteStops）；
 * 协议入口 FillValidator 已挡，这里兜底覆盖模板 raw_state 等绕过路径。
 */
function filterFiniteStops(
    stops: ReadonlyArray<{ position: number; color: string }>,
): { position: number; color: string }[] {
    const out: { position: number; color: string }[] = [];
    for (const s of stops) {
        if (!s || !Number.isFinite(s.position)) continue;
        out.push(s);
    }
    return out;
}

function buildLinearGradient(
    ctx: CanvasRenderingContext2D,
    g: LinearGradient,
    bx: number, by: number, bw: number, bh: number,
): string | CanvasGradient {
    // 三档退化兜底，逐档对齐后端 buildLinearPaint。
    const stops = filterFiniteStops(g.stops);
    if (stops.length === 0) return FILL_FALLBACK_WHITE;          // 0 stops → 纯白
    if (stops.length < 2) return stops[0].color;                 // < 2 stops → 首 stop 色
    // 与 Java buildLinearPaint 同：方向向量 (cos θ, sin θ)；θ=0° 沿 +x，90° 沿 +y（画布坐标系 Y 朝下）
    // NaN 角度兜底为 0（对齐后端 Double.isFinite(g.angle())? : 0.0）
    const angle = Number.isFinite(g.angle) ? g.angle : 0;
    const rad = (angle * Math.PI) / 180;
    const dx = Math.cos(rad);
    const dy = Math.sin(rad);
    const cx = bx + bw / 2;
    const cy = by + bh / 2;
    const corners: [number, number][] = [
        [bx, by],
        [bx + bw, by],
        [bx, by + bh],
        [bx + bw, by + bh],
    ];
    let minP = Infinity, maxP = -Infinity;
    for (const [px, py] of corners) {
        const p = (px - cx) * dx + (py - cy) * dy;
        if (p < minP) minP = p;
        if (p > maxP) maxP = p;
    }
    const x1 = cx + dx * minP;
    const y1 = cy + dy * minP;
    const x2 = cx + dx * maxP;
    const y2 = cy + dy * maxP;
    // 端点重合（0 尺寸 bbox）→ 首 stop 纯色，对齐后端 `x1==x2 && y1==y2` 分支
    if (x1 === x2 && y1 === y2) return stops[0].color;
    const grad = ctx.createLinearGradient(x1, y1, x2, y2);
    addStops(grad, stops);
    return grad;
}

function buildRadialGradient(
    ctx: CanvasRenderingContext2D,
    g: RadialGradient,
    bx: number, by: number, bw: number, bh: number,
): string | CanvasGradient {
    // 退化兜底对齐后端 buildRadialPaint。
    const stops = filterFiniteStops(g.stops);
    if (stops.length === 0) return FILL_FALLBACK_WHITE;
    if (stops.length < 2) return stops[0].color;
    // cx/cy/r 非有限兜底（对齐后端 Double.isFinite? : 0.5/0.5/1.0）
    const gcx = Number.isFinite(g.cx) ? g.cx : 0.5;
    const gcy = Number.isFinite(g.cy) ? g.cy : 0.5;
    const gr = Number.isFinite(g.r) ? g.r : 1;
    const cx = bx + gcx * bw;
    const cy = by + gcy * bh;
    const radius = gr * Math.min(bw, bh) / 2;
    // r<=0 / 非有限 → 首 stop 纯色（对齐后端 `radius <= 0 || !finite` 分支）。
    // 不再强制 Math.max(0.0001, ...)——那会让退化半径显示末 stop 色，与后端纯色行为分叉。
    if (radius <= 0 || !Number.isFinite(radius)) return stops[0].color;
    // Canvas createRadialGradient(x0,y0,r0,x1,y1,r1)：内圆退化为中心点（r0=0）映射 Java RadialGradientPaint
    const grad = ctx.createRadialGradient(cx, cy, 0, cx, cy, radius);
    addStops(grad, stops);
    return grad;
}

function addStops(grad: CanvasGradient, stops: ReadonlyArray<{ position: number; color: string }>): void {
    // Canvas 允许相等 position（硬切），无需 monotonize；只 clamp 到 [0,1]
    for (const s of stops) {
        const p = s.position < 0 ? 0 : s.position > 1 ? 1 : s.position;
        grad.addColorStop(p, s.color);
    }
}
