import { describe, it, expect, vi, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { unzipSync, strFromU8 } from 'fflate';

let captured: Uint8Array | null = null;
vi.mock('@/lib/downloadBlob', () => ({ downloadBlob: (b: Uint8Array) => { captured = b; } }));
vi.mock('@/render/exportThumbnail', () => ({ renderExportThumbnail: vi.fn(async () => null) }));

import { useProjectExport } from '../useProjectExport';
import { useProjectStore } from '@/stores/project';
import { useScriptStore } from '@/stores/scripts';
import type { ProjectState, ScriptRule } from '@/types/protocol';

function emptyState(): ProjectState {
    return {
        version: 3, canvas: { widthMaps: 1, heightMaps: 1, background: '#fff' },
        layers: [{ id: 'l1', name: 'L', visible: true, locked: false, opacity: 1, blendMode: 'normal', elements: [] }],
        activeLayerId: 'l1',
    } as unknown as ProjectState;
}

function sampleRule(): ScriptRule {
    return {
        id: 'sr-1', wallId: 'w1', enabled: true, name: 'r',
        trigger: { type: 'wallReady' }, actions: [], blockLayout: '{}',
    } as unknown as ScriptRule;
}

describe('useProjectExport with scripts', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        captured = null;
        vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, arrayBuffer: async () => new ArrayBuffer(2) } as Response)));
    });

    it('includes scripts.json when the wall has scripts', async () => {
        useProjectStore().setSnapshot(emptyState());
        // 用 store 真实 setter initScripts 注入（listSorted 是只读 computed，依赖 rules+order）。
        useScriptStore().initScripts([sampleRule()]);
        await useProjectExport().exportProject();
        const entries = unzipSync(captured!);
        expect(Object.keys(entries)).toContain('scripts.json');
        expect(JSON.parse(strFromU8(entries['scripts.json']))).toHaveLength(1);
    });

    it('omits scripts.json when the wall has no scripts', async () => {
        useProjectStore().setSnapshot(emptyState());
        await useProjectExport().exportProject();
        const entries = unzipSync(captured!);
        expect(Object.keys(entries)).not.toContain('scripts.json');
    });
});
