import type { BlendMode } from '@/types/protocol';

/**
 * M8-E v1 混合模式实现（镜像后端 {@code BlendModes.java}）。
 * 契约见 {@code docs/rendering.md §6.6}。
 *
 * 公式 per-channel 0-1：
 * - normal:   out = src
 * - multiply: out = src * dst
 * - screen:   out = 1 - (1 - src) * (1 - dst)
 * - overlay:  if dst < 0.5: out = 2 * src * dst
 *             else:         out = 1 - 2 * (1 - src) * (1 - dst)
 *
 * 合成（W3C source-over 标准）：
 *   srcA = (srcAlpha/255) * layerOpacity
 *   dstA = dstAlpha/255
 *   outA = srcA + dstA * (1 - srcA)
 *   outRGB = (blend * srcA + dst * dstA * (1 - srcA)) / outA
 *
 * Ultrareview 2026-05-25 #7：主 buffer 已支持透明（0.4.6 P2 ARGB），
 * 必须按真 source-over 合成 alpha，不能再强写 255。
 *
 * 任何修改要与后端 BlendModes.java 同步，否则双端预览像素漂移。
 */
export function blendChannel(srcByte: number, dstByte: number, mode: BlendMode): number {
    const s = srcByte / 255;
    const d = dstByte / 255;
    let b: number;
    switch (mode) {
        case 'multiply':
            b = s * d;
            break;
        case 'screen':
            b = 1 - (1 - s) * (1 - d);
            break;
        case 'overlay':
            b = d < 0.5 ? 2 * s * d : 1 - 2 * (1 - s) * (1 - d);
            break;
        case 'normal':
        default:
            b = s;
            break;
    }
    return clamp255(Math.round(b * 255));
}

/**
 * 把 src（ARGB layer buffer ImageData）按 layerOpacity + blendMode 合到 dst（ARGB 主 buffer ImageData）。
 * P3-92：dst 是真 ARGB 主 buffer（0.4.6 P2 起支持透明背景），dst alpha（da = dd[i+3]）
 * 真实参与 W3C source-over 合成——不再假设 255。与模块级 JSDoc 及实现一致。
 */
export function applyBlendModeOver(
    dst: ImageData,
    src: ImageData,
    layerOpacity: number,
    mode: BlendMode,
): void {
    if (layerOpacity <= 0) return;
    const dd = dst.data;
    const sd = src.data;
    const n = Math.min(dd.length, sd.length);
    for (let i = 0; i < n; i += 4) {
        const sa = sd[i + 3];
        if (sa === 0) continue;
        const srcA = (sa / 255) * layerOpacity;
        if (srcA <= 0) continue;

        const sr = sd[i];
        const sg = sd[i + 1];
        const sb = sd[i + 2];
        const dr = dd[i];
        const dg = dd[i + 1];
        const db = dd[i + 2];
        const da = dd[i + 3];

        const br = blendChannel(sr, dr, mode);
        const bg = blendChannel(sg, dg, mode);
        const bb = blendChannel(sb, db, mode);

        // Ultrareview 2026-05-25 #7：W3C source-over with alpha pass-through。
        const dstAf = da / 255;
        const invA = 1 - srcA;
        const outAf = srcA + dstAf * invA;
        if (outAf <= 0) {
            dd[i] = dd[i + 1] = dd[i + 2] = dd[i + 3] = 0;
        } else {
            const weight = dstAf * invA;
            dd[i]     = clamp255(Math.round((br * srcA + dr * weight) / outAf));
            dd[i + 1] = clamp255(Math.round((bg * srcA + dg * weight) / outAf));
            dd[i + 2] = clamp255(Math.round((bb * srcA + db * weight) / outAf));
            dd[i + 3] = clamp255(Math.round(outAf * 255));
        }
    }
}

function clamp255(v: number): number {
    if (v < 0) return 0;
    if (v > 255) return 255;
    return v;
}
