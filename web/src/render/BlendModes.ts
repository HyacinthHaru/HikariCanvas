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
 * 合成：source-over with effective srcAlpha = (srcA/255) * layerOpacity
 *   out = blend * srcA + dst * (1 - srcA)
 *
 * 主 buffer 视为不透明 RGB；layer buffer 为 ARGB。
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
 * 把 src（ARGB layer buffer ImageData）按 layerOpacity + blendMode 合到 dst（主 buffer ImageData）。
 * dst 假设是不透明 RGB（虽然 ImageData 仍是 RGBA，alpha 视为 255）。
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

        const br = blendChannel(sr, dr, mode);
        const bg = blendChannel(sg, dg, mode);
        const bb = blendChannel(sb, db, mode);

        const invA = 1 - srcA;
        dd[i]     = clamp255(Math.round(br * srcA + dr * invA));
        dd[i + 1] = clamp255(Math.round(bg * srcA + dg * invA));
        dd[i + 2] = clamp255(Math.round(bb * srcA + db * invA));
        dd[i + 3] = 255;
    }
}

function clamp255(v: number): number {
    if (v < 0) return 0;
    if (v > 255) return 255;
    return v;
}
