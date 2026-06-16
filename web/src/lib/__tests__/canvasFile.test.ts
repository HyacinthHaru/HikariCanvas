import { describe, it, expect } from 'vitest';
import { collectImageHashes, buildManifest, assembleCanvasZip } from '../canvasFile';
import type { ProjectState } from '@/types/protocol';
import { unzipSync, strFromU8 } from 'fflate';

function stateWithImages(): ProjectState {
    return {
        version: 3, canvas: { widthMaps: 2, heightMaps: 1, background: '#FFFFFF' },
        layers: [{ id: 'l1', name: 'L', visible: true, locked: false, opacity: 1, blendMode: 'normal',
            elements: [
                { id: 'e1', type: 'image', x: 0, y: 0, w: 10, h: 10, rotation: 0, opacity: 1, source: 'aabbccddeeff0011' },
                { id: 'e2', type: 'image', x: 0, y: 0, w: 10, h: 10, rotation: 0, opacity: 1, source: 'aabbccddeeff0011' },
                { id: 'e3', type: 'text', x: 0, y: 0, w: 10, h: 10, rotation: 0, opacity: 1, text: 'hi' },
            ] }],
        activeLayerId: 'l1',
    } as unknown as ProjectState;
}

describe('collectImageHashes', () => {
    it('returns deduped image source hashes across all layers', () => {
        expect(collectImageHashes(stateWithImages())).toEqual(['aabbccddeeff0011']);
    });
    it('returns empty for a state with no image elements', () => {
        const s = { version: 3, canvas: { widthMaps: 1, heightMaps: 1, background: '#fff' },
            layers: [{ id: 'l1', name: 'L', visible: true, locked: false, opacity: 1, blendMode: 'normal', elements: [] }],
            activeLayerId: 'l1' } as unknown as ProjectState;
        expect(collectImageHashes(s)).toEqual([]);
    });
});

describe('buildManifest', () => {
    it('fills spec/kind/wall from state', () => {
        const m = buildManifest(stateWithImages(), { createdAt: 123, createdBy: 'Steve', pluginVersion: '0.8.0', name: 'X' });
        expect(m.spec).toBe(1);
        expect(m.kind).toBe('project');
        expect(m.wall).toEqual({ width: 2, height: 1 });
        expect(m.created_at).toBe(123);
        expect(m.created_by).toBe('Steve');
    });
});

describe('assembleCanvasZip', () => {
    it('packs manifest/project.json (+optional scripts/thumbnail/assets) into a readable zip', () => {
        const bytes = assembleCanvasZip({
            manifest: buildManifest(stateWithImages(), { createdAt: 1, pluginVersion: '0.8.0' }),
            projectJson: '{"version":3}',
            scriptsJson: '[]',
            thumbnailPng: new Uint8Array([137, 80]),
            assets: { 'aabbccddeeff0011': new Uint8Array([1, 2]) },
        });
        const entries = unzipSync(bytes);
        expect(Object.keys(entries).sort()).toEqual(
            ['assets/aabbccddeeff0011.png', 'manifest.json', 'project.json', 'scripts.json', 'thumbnail.png']);
        expect(JSON.parse(strFromU8(entries['project.json']))).toEqual({ version: 3 });
    });
    it('omits scripts.json/thumbnail/assets when not provided', () => {
        const bytes = assembleCanvasZip({
            manifest: buildManifest(stateWithImages(), { createdAt: 1, pluginVersion: '0.8.0' }),
            projectJson: '{}',
        });
        expect(Object.keys(unzipSync(bytes)).sort()).toEqual(['manifest.json', 'project.json']);
    });
});
