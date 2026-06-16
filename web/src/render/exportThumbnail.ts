import { renderProjectState } from '@/render/PreviewRenderer';
import type { ProjectState } from '@/types/protocol';

export const EXPORT_THUMB_W = 256;
export const EXPORT_THUMB_H = 128;

/** 把整个工程渲成 256×128 PNG 字节（用于 .canvas thumbnail.png）。canvas 不可用时返回 null。 */
export async function renderExportThumbnail(state: ProjectState): Promise<Uint8Array | null> {
    const fullW = Math.max(1, state.canvas.widthMaps * 128);
    const fullH = Math.max(1, state.canvas.heightMaps * 128);

    const full = document.createElement('canvas');
    full.width = fullW; full.height = fullH;
    const fctx = full.getContext('2d');
    if (!fctx) return null;
    renderProjectState(fctx, state);

    const thumb = document.createElement('canvas');
    thumb.width = EXPORT_THUMB_W; thumb.height = EXPORT_THUMB_H;
    const tctx = thumb.getContext('2d');
    if (!tctx) return null;
    // contain 居中
    const scale = Math.min(EXPORT_THUMB_W / fullW, EXPORT_THUMB_H / fullH);
    const dw = fullW * scale, dh = fullH * scale;
    tctx.imageSmoothingEnabled = true;
    tctx.drawImage(full, (EXPORT_THUMB_W - dw) / 2, (EXPORT_THUMB_H - dh) / 2, dw, dh);

    const blob = await new Promise<Blob | null>((res) => thumb.toBlob((b) => res(b), 'image/png'));
    if (!blob) return null;
    return new Uint8Array(await blob.arrayBuffer());
}
