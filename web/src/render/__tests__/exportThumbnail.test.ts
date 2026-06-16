// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest';

const { renderSpy } = vi.hoisted(() => ({ renderSpy: vi.fn() }));
vi.mock('@/render/PreviewRenderer', () => ({ renderProjectState: renderSpy }));

import { renderExportThumbnail } from '../exportThumbnail';
import type { ProjectState } from '@/types/protocol';

function tinyState(): ProjectState {
    return { version: 3, canvas: { widthMaps: 1, heightMaps: 1, background: '#fff' },
        layers: [{ id: 'l1', name: 'L', visible: true, locked: false, opacity: 1, blendMode: 'normal', elements: [] }],
        activeLayerId: 'l1' } as unknown as ProjectState;
}

// happy-dom 不实现 canvas 2D context（getContext('2d') 返回 null），打桩一个最小 ctx，
// 让被测函数能走到 renderProjectState + toBlob（实现里 imageSmoothingEnabled / drawImage 会被调）。
function stubCtx(): Partial<CanvasRenderingContext2D> {
    return { imageSmoothingEnabled: false, drawImage: vi.fn() } as Partial<CanvasRenderingContext2D>;
}

describe('renderExportThumbnail', () => {
    beforeEach(() => {
        renderSpy.mockClear();
        vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(
            stubCtx() as unknown as CanvasRenderingContext2D);
    });

    it('renders the full project then returns PNG bytes (or null if canvas unavailable)', async () => {
        const png = new Uint8Array([137, 80, 78, 71]);
        vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(function (this: HTMLCanvasElement, cb) {
            cb(new Blob([png], { type: 'image/png' }));
        });
        const out = await renderExportThumbnail(tinyState());
        expect(renderSpy).toHaveBeenCalled();
        // toBlob 被打桩 → 应拿到字节
        expect(out).toBeInstanceOf(Uint8Array);
    });

    it('returns null when toBlob yields null', async () => {
        vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(function (this: HTMLCanvasElement, cb) { cb(null); });
        expect(await renderExportThumbnail(tinyState())).toBeNull();
    });
});
