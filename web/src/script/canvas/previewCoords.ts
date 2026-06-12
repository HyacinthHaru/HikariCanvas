/**
 * 0.7.1-P3：积木编辑器预览框坐标系（纯函数，可单测）。
 *
 * <p>预览框把整面墙（墙像素 = widthMaps*128 × heightMaps*128）等比缩放（fit）并居中显示进
 * 一块可变尺寸的预览区。本模块提供 fit-scale + 居中偏移的变换，及墙坐标 ↔ 预览像素坐标的
 * 双向映射。</p>
 *
 * <p><b>互逆约束</b>：{@link wallToPreview} 与 {@link previewToWall} 必须严格互逆——P4「幽灵
 * 拖动设目标坐标」用 previewToWall 把指针在预览里的位置反算成墙坐标填进积木，差一点目标就偏。
 * 单测做 round-trip 断言守这个约束。</p>
 */

/** fit-scale + 居中偏移。preview 像素坐标 = wall 坐标 × scale + offset。 */
export interface PreviewTransform {
    /** 墙 → 预览的等比缩放系数（min(paneW/wallW, paneH/wallH)）。 */
    scale: number;
    /** 居中后墙左边在预览区内的 x 像素偏移。 */
    offsetX: number;
    /** 居中后墙上边在预览区内的 y 像素偏移。 */
    offsetY: number;
}

/**
 * 算 fit-scale + 居中变换。任一维 ≤ 0（墙未就绪 / 预览区尚未测量）→ 恒等变换兜底，
 * 避免除零 / NaN 污染下游绘制。
 */
export function computePreviewTransform(
    wallW: number,
    wallH: number,
    paneW: number,
    paneH: number,
): PreviewTransform {
    if (wallW <= 0 || wallH <= 0 || paneW <= 0 || paneH <= 0) {
        return { scale: 1, offsetX: 0, offsetY: 0 };
    }
    const scale = Math.min(paneW / wallW, paneH / wallH);
    const offsetX = (paneW - wallW * scale) / 2;
    const offsetY = (paneH - wallH * scale) / 2;
    return { scale, offsetX, offsetY };
}

/** 墙坐标 → 预览像素坐标。 */
export function wallToPreview(t: PreviewTransform, x: number, y: number): { x: number; y: number } {
    return { x: t.offsetX + x * t.scale, y: t.offsetY + y * t.scale };
}

/** 预览像素坐标 → 墙坐标（{@link wallToPreview} 的逆）。 */
export function previewToWall(t: PreviewTransform, px: number, py: number): { x: number; y: number } {
    return { x: (px - t.offsetX) / t.scale, y: (py - t.offsetY) / t.scale };
}
