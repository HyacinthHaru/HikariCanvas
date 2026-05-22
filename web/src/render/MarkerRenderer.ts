/**
 * 前端 marker 渲染（M9-C，镜像后端 {@code render/MarkerRenderer.java}）。
 *
 * 几何与后端逐行一致：arrow apex 在 path 端点上、base 朝外退 size；
 * dot 圆心在端点。任何修改需同步两端。
 */

/** 0.4.7：marker 大小约束。与后端 MarkerRenderer.java 一致。 */
export const MARKER_MAX_RATIO_OF_LENGTH = 0.5;
export const MIN_ARROW_SIZE = 8;
export const MIN_DOT_RADIUS = 3;

/**
 * arrow size 公式（0.4.7 第二次修）。
 *
 * 关键比例：base width / stroke width ≥ 3，否则箭头 V 头跟直线粗矩形粘连难辨。
 * stroke × 3 自然增长，无硬上限——element-aware cap 由调用方传 element 对角线限制。
 *
 * 视觉清晰底线：base ≥ stroke × 2，优先级 > element cap（宁可略溢出 bbox 也别糊）。
 */
export function arrowSize(strokeWidth: number, elementDiagonal?: number): number {
    const base = Math.max(MIN_ARROW_SIZE, strokeWidth * 3);
    if (elementDiagonal === undefined || elementDiagonal <= 0) return base;
    const byLength = Math.round(elementDiagonal * MARKER_MAX_RATIO_OF_LENGTH);
    const minByStroke = Math.max(MIN_ARROW_SIZE, strokeWidth * 2);
    return Math.max(minByStroke, Math.min(base, byLength));
}

export function dotRadius(strokeWidth: number, elementDiagonal?: number): number {
    const base = Math.max(MIN_DOT_RADIUS, strokeWidth + 1);
    if (elementDiagonal === undefined || elementDiagonal <= 0) return base;
    const byLength = Math.round(elementDiagonal * MARKER_MAX_RATIO_OF_LENGTH * 0.5);
    const minByStroke = Math.max(MIN_DOT_RADIUS, strokeWidth);
    return Math.max(minByStroke, Math.min(base, byLength));
}

/**
 * 构造 arrow 三角形几何（不绘制）。
 *
 * 0.4.7 几何修正（与后端 MarkerRenderer.java arrowShape 同款）：
 * - 原实现 path end 当 apex，base 朝 path 内部退 size → stroke 矩形带在三角形 apex
 *   附近超出边缘（V 头被直线横穿）
 * - 新实现 path end 当 **base center**，apex 朝 dir 方向延伸 size → stroke 干净截止
 *   于 base center（配合 BUTT lineCap），arrow 在 path end 之外突出（SVG marker-end 标准）
 *
 * 参数 apexX/Y 仍命名 apex（向下兼容 caller）— 内部当 base center 用。
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
    // apexX/Y 是 path end = base center；真 apex 在 path end + dir × size
    const bcx = apexX;
    const bcy = apexY;
    const realApexX = apexX + dx * size;
    const realApexY = apexY + dy * size;
    const px = -dy;
    const py = dx;
    const halfBase = size * 0.5;
    const path = new Path2D();
    path.moveTo(realApexX, realApexY);
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
