import { describe, it, expect, vi, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { useProjectImport } from '../useProjectImport';
import { useNetworkStore } from '@/stores/network';

describe('useProjectImport', () => {
    beforeEach(() => { setActivePinia(createPinia()); useNetworkStore().sessionId = 'sess-1'; });

    it('POSTs the file with sessionId and returns warnings on success', async () => {
        const fetchSpy = vi.fn(async () => ({ ok: true, json: async () => ({ ok: true, warnings: [{ kind: 'missing-font', detail: 'arial' }] }) } as Response));
        vi.stubGlobal('fetch', fetchSpy);
        const file = new File([new Uint8Array([1, 2])], 'x.canvas');
        const r = await useProjectImport().importProject(file);
        expect(fetchSpy).toHaveBeenCalledWith('/api/project/import', expect.objectContaining({ method: 'POST' }));
        expect(r.ok).toBe(true);
        expect(r.warnings).toEqual([{ kind: 'missing-font', detail: 'arial' }]);
    });

    it('returns the backend error code on failure', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, json: async () => ({ error: 'IMPORT_SIZE_MISMATCH', message: 'too big' }) } as Response)));
        const r = await useProjectImport().importProject(new File([new Uint8Array([1])], 'x.canvas'));
        expect(r.ok).toBe(false);
        expect(r.errorCode).toBe('IMPORT_SIZE_MISMATCH');
    });
});
