/**
 * 前端 marker 渲染（M9-C，镜像后端 {@code render/MarkerRenderer.java}）。
 *
 * 几何与后端逐行一致：arrow apex 在 path 端点上、base 朝外退 size；
 * dot 圆心在端点。任何修改需同步两端。
 */

/** 0.4.7：marker 大小上限与 element-length cap 比例。与后端 MarkerRenderer.java 一致。 */
export const MAX_ARROW_SIZE = 40;
export const MAX_DOT_RADIUS = 16;
export const MARKER_MAX_RATIO_OF_LENGTH = 0.4;

/**
 * arrow size 公式（0.4.7 修）。
 *
 * 原 max(6, stroke × 3) 在 stroke 大时无上限增长（stroke=20 → 60，吞没 50px 短箭头）。
 * 新公式 clamp(6, stroke × 2.5 + 4, 40) 平滑增长 + 40px 上限。
 *
 * 可选 elementDiagonal 参数（hypot(w, h)）= element bbox 对角线，传入后做 element-aware
 * cap：marker ≤ diagonal × MARKER_MAX_RATIO_OF_LENGTH (0.4)，保证至少留 60% 给直线段。
 */
export function arrowSize(strokeWidth: number, elementDiagonal?: number): number {
    const raw = Math.round(strokeWidth * 2.5 + 4);
    const base = Math.max(6, Math.min(MAX_ARROW_SIZE, raw));
    if (elementDiagonal === undefined || elementDiagonal <= 0) return base;
    const byLength = Math.round(elementDiagonal * MARKER_MAX_RATIO_OF_LENGTH);
    return Math.max(6, Math.min(base, byLength));
}

export function dotRadius(strokeWidth: number, elementDiagonal?: number): number {
    const base = Math.max(2, Math.min(MAX_DOT_RADIUS, strokeWidth + 1));
    if (elementDiagonal === undefined || elementDiagonal <= 0) return base;
    // dot 直径 = 半径 × 2，所以 cap 是 ratio/2
    const byLength = Math.round(elementDiagonal * MARKER_MAX_RATIO_OF_LENGTH * 0.5);
    return Math.max(2, Math.min(base, byLength));
}

/**
 * 构造 arrow 三角形几何（不绘制）。drawArrow 内部用，也供 PreviewRenderer 在描边前
 * 从 stroke clip 中扣除（2026-05-15 修 Bug：粗 stroke 突破 arrow 锥尖）。
 */
export function arrowShape(
    apexX: number, apexY: number,
    dirX: number, dirY: number,
    size: number,
): Path2D | null {
    const len = Math.hypot(dirX, dirY);
    if (len < 1e-9) return null;
    const dx = dirX / len;
    const dy = dirY / len;
    const bcx = apexX - dx * size;
    const bcy = apexY - dy * size;
    const px = -dy;
    const py = dx;
    const halfBase = size * 0.5;
    const path = new Path2D();
    path.moveTo(apexX, apexY);
    path.lineTo(bcx + px * halfBase, bcy + py * halfBase);
    path.lineTo(bcx - px * halfBase, bcy - py * halfBase);
    path.closePath();
    return path;
}

/**
 * 在 (apexX, apexY) 画实心三角形 arrow，朝 (dirX, dirY) 方向。
 * dirX/dirY 不必预先归一化（内部归一化）。
 */
export function drawArrow(
    ctx: CanvasRenderingContext2D,
    apexX: number, apexY: number,
    dirX: number, dirY: number,
    size: number, color: string,
): void {
    const path = arrowShape(apexX, apexY, dirX, dirY, size);
    if (!path) return;
    const prevFill = ctx.fillStyle;
    ctx.fillStyle = color;
    ctx.fill(path);
    ctx.fillStyle = prevFill;
}

export function drawDot(
    ctx: CanvasRenderingContext2D,
    cx: number, cy: number,
    radius: number, color: string,
): void {
    if (radius <= 0) return;
    const prevFill = ctx.fillStyle;
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(cx, cy, radius, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = prevFill;
}
