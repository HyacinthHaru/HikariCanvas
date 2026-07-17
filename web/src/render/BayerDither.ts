// Bayer 4×4 有序抖动（前端镜像）。
//
// 与 Java moe.hikari.canvas.render.BayerDither 逐行镜像：同矩阵、同 AMPLITUDE、同公式。
// 算法详见后端实现与 docs/rendering.md §6.6。
//
// 输入：ImageData（ARGB 字节排列 R,G,B,A 每像素 4 byte）
// 算法：每像素加 Bayer offset（±AMPLITUDE）→ PaletteLut.matchColor → getColor 反查 RGB 写回
// alpha < 128 像素跳过（透明，保持原状）

import type { PaletteLut } from './PaletteLut';

/** 4×4 Bayer 矩阵，索引 {@code [y%4][x%4]}，值域 0..15。双端镜像硬约束。 */
export const BAYER_MATRIX: ReadonlyArray<ReadonlyArray<number>> = Object.freeze([
    Object.freeze([ 0,  8,  2, 10]),
    Object.freeze([12,  4, 14,  6]),
    Object.freeze([ 3, 11,  1,  9]),
    Object.freeze([15,  7, 13,  5]),
]);

export const BAYER_SIZE = 4;

/** 单通道偏移幅度（±AMPLITUDE）。与后端 BayerDither.AMPLITUDE 一致；改一处必须改两处。 */
export const BAYER_AMPLITUDE = 16;

const ALPHA_THRESHOLD = 128;

/** 归一化阈值 [-0.5, 0.4375]。前后端必须用同一公式以保 4×4 相位对齐。 */
export function bayerThreshold(x: number, y: number): number {
    return BAYER_MATRIX[y & 3][x & 3] / 16 - 0.5;
}

/**
 * 对 ImageData 原地 dither。{@code alpha < 128} 像素跳过；其余像素加 Bayer offset、
 * 走 {@link PaletteLut.matchColor} 量化、反查 palette RGB 写回。
 *
 * <p>{@code phaseX/phaseY} 是该 ImageData 局部 (0,0) 对应的原画坐标偏移。
 * 当 offscreen buffer 只裁了 element bbox 子区域时，Bayer 矩阵必须以原画坐标取阈值
 * （{@code bayerThreshold(phaseX + x, phaseY + y)}），与后端 {@code BayerDither.apply(buf, lut, clipX, clipY)}
 * 相位对齐——否则 4×4 图案错位、双端像素漂移。默认 (0,0) 兼容全画布旧调用。</p>
 */
export function applyBayerDither(
    imgData: ImageData,
    palette: PaletteLut,
    phaseX = 0,
    phaseY = 0,
): void {
    if (!imgData || !palette) return;
    const d = imgData.data;
    const w = imgData.width;
    const h = imgData.height;
    for (let y = 0; y < h; y++) {
        for (let x = 0; x < w; x++) {
            const i = (y * w + x) * 4;
            const a = d[i + 3];
            if (a < ALPHA_THRESHOLD) continue;
            const t = bayerThreshold(phaseX + x, phaseY + y);
            const offset = Math.round(t * BAYER_AMPLITUDE * 2);
            const dr = clamp8(d[i] + offset);
            const dg = clamp8(d[i + 1] + offset);
            const db = clamp8(d[i + 2] + offset);
            const idx = palette.matchColor(dr, dg, db);
            const c = palette.getColor(idx);
            if (!c) continue;
            d[i] = c[0];
            d[i + 1] = c[1];
            d[i + 2] = c[2];
            // alpha 保留
        }
    }
}

function clamp8(v: number): number {
    return v < 0 ? 0 : v > 255 ? 255 : v;
}
