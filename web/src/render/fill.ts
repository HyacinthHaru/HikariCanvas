// M11-A：Fill 工具函数（前端镜像）。
//
// 后端 moe.hikari.canvas.state.{Fill, SolidFill, LinearGradient, RadialGradient, Stop}
// 的 TS 侧消费者助手。
//
// 当前阶段（M11-A）只承担三件事：
//   1. 把后端 / 老工程文件吐回的 string 形态 fill 归一化为 SolidFill object
//   2. 给 ctx.fillStyle 提供一个纯色 fallback（渐变取第一 stop 颜色；M11-B 替换为 CanvasGradient）
//   3. 给 palette projectColors 扫描提供一次性"提取所有颜色"接口
//
// M11-B 实施时本文件加 fillToCanvasStyle(ctx, fill, bbox) → string | CanvasGradient。

import type { Fill, FillCompat, SolidFill } from '../types/protocol';

/**
 * 把 string 形态 fill 归一化为 object 形态。其余按原样返回。
 * M0-M10 工程文件 / 后端 v1 patch 兼容入口。
 */
export function normalizeFill(fill: FillCompat | null | undefined): Fill | undefined {
    if (fill === null || fill === undefined) return undefined;
    if (typeof fill === 'string') return { type: 'solid', color: fill };
    return fill;
}

/**
 * M11-A 临时桥接：把 Fill 拍平成一个 hex 颜色串供 ctx.fillStyle 用。
 * 渐变此阶段取 stops[0].color 显示，M11-B 替换为 createLinearGradient / createRadialGradient。
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
 * M11-C：把 {@link FillCompat} 转成 Canvas 2D `fillStyle` 接受的类型。
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

function buildLinearGradient(
    ctx: CanvasRenderingContext2D,
    g: { angle: number; stops: { position: number; color: string }[] },
    bx: number, by: number, bw: number, bh: number,
): CanvasGradient {
    // 与 Java buildLinearPaint 同：方向向量 (cos θ, sin θ)；θ=0° 沿 +x，90° 沿 +y（画布坐标系 Y 朝下）
    const rad = (g.angle * Math.PI) / 180;
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
    const grad = ctx.createLinearGradient(x1, y1, x2, y2);
    addStops(grad, g.stops);
    return grad;
}

function buildRadialGradient(
    ctx: CanvasRenderingContext2D,
    g: { cx: number; cy: number; r: number; stops: { position: number; color: string }[] },
    bx: number, by: number, bw: number, bh: number,
): CanvasGradient {
    const cx = bx + g.cx * bw;
    const cy = by + g.cy * bh;
    const radius = Math.max(0.0001, g.r * Math.min(bw, bh) / 2);
    // Canvas createRadialGradient(x0,y0,r0,x1,y1,r1)：内圆退化为中心点（r0=0）映射 Java RadialGradientPaint
    const grad = ctx.createRadialGradient(cx, cy, 0, cx, cy, radius);
    addStops(grad, g.stops);
    return grad;
}

function addStops(grad: CanvasGradient, stops: { position: number; color: string }[]): void {
    // Canvas 允许相等 position（硬切），无需 monotonize；只 clamp 到 [0,1]
    for (const s of stops) {
        const p = s.position < 0 ? 0 : s.position > 1 ? 1 : s.position;
        grad.addColorStop(p, s.color);
    }
}
