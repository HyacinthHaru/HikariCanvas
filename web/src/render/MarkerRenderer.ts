/**
 * 前端 marker 渲染（M9-C，镜像后端 {@code render/MarkerRenderer.java}）。
 *
 * 几何与后端逐行一致：arrow apex 在 path 端点上、base 朝外退 size；
 * dot 圆心在端点。任何修改需同步两端。
 */

export function arrowSize(strokeWidth: number): number {
    return Math.max(6, strokeWidth * 3);
}

export function dotRadius(strokeWidth: number): number {
    return Math.max(2, strokeWidth + 1);
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
    const len = Math.hypot(dirX, dirY);
    if (len < 1e-9) return;
    const dx = dirX / len;
    const dy = dirY / len;
    const bcx = apexX - dx * size;
    const bcy = apexY - dy * size;
    const px = -dy;
    const py = dx;
    const halfBase = size * 0.5;

    const prevFill = ctx.fillStyle;
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.moveTo(apexX, apexY);
    ctx.lineTo(bcx + px * halfBase, bcy + py * halfBase);
    ctx.lineTo(bcx - px * halfBase, bcy - py * halfBase);
    ctx.closePath();
    ctx.fill();
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
