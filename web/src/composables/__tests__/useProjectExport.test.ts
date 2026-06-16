import { describe, it, expect, vi, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';

const { downloadSpy } = vi.hoisted(() => ({ downloadSpy: vi.fn() }));
vi.mock('@/lib/downloadBlob', () => ({ downloadBlob: downloadSpy }));
vi.mock('@/render/exportThumbnail', () => ({ renderExportThumbnail: vi.fn(async () => null) }));

import { useProjectExport } from '../useProjectExport';
import { useProjectStore } from '@/stores/project';
import type { ProjectState } from '@/types/protocol';

function state(): ProjectState {
    return { version: 3, canvas: { widthMaps: 1, heightMaps: 1, background: '#fff' },
        layers: [{ id: 'l1', name: 'L', visible: true, locked: false, opacity: 1, blendMode: 'normal',
            elements: [{ id: 'e1', type: 'image', x: 0, y: 0, w: 4, h: 4, rotation: 0, opacity: 1, source: 'aabbccddeeff0011' }] }],
        activeLayerId: 'l1' } as unknown as ProjectState;
}

describe('useProjectExport', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        downloadSpy.mockClear();
        vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer } as Response)));
    });

    it('downloads a .canvas file built from current project state', async () => {
        useProjectStore().setSnapshot(state());
        await useProjectExport().exportProject();
        expect(downloadSpy).toHaveBeenCalledOnce();
        const [bytes, filename, mime] = downloadSpy.mock.calls[0];
        expect(bytes).toBeInstanceOf(Uint8Array);
        expect(filename.endsWith('.canvas')).toBe(true);
        expect(mime).toBe('application/octet-stream');
    });

    it('does nothing and reports when there is no active project', async () => {
        const { exportProject } = useProjectExport();
        const r = await exportProject();
        expect(r.ok).toBe(false);
        expect(downloadSpy).not.toHaveBeenCalled();
    });
});
