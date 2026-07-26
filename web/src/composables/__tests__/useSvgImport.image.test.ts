// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';

const sendSpy = vi.fn(() => 'c-1');
const sendWithAckSpy = vi.fn().mockResolvedValue({});
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({ send: sendSpy, sendWithAck: sendWithAckSpy }),
}));

// Mock global fetch:
// - data: URL fetch → returns a blob
// - /api/upload → returns { source: 'aabbccddeeff0011' }
const mockFetch = vi.fn((url: string) => {
    if (typeof url === 'string' && url.startsWith('data:')) {
        return Promise.resolve({
            ok: true,
            blob: async () => new Blob(['fake'], { type: 'image/png' }),
        });
    }
    if (typeof url === 'string' && url.includes('/api/upload')) {
        return Promise.resolve({
            ok: true,
            json: async () => ({ source: 'aabbccddeeff0011', width: 100, height: 100, bytes: 1000 }),
        });
    }
    return Promise.resolve({ ok: false, json: async () => ({}) });
});

vi.stubGlobal('fetch', mockFetch);

import { useSvgImport } from '../useSvgImport';
import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import type { ProjectState } from '@/types/protocol';

function makeSeedState(): ProjectState {
    return {
        version: 1,
        canvas: { widthMaps: 1, heightMaps: 1, background: null },
        layers: [
            {
                id: 'layer-1',
                name: 'Layer 1',
                visible: true,
                locked: false,
                opacity: 1,
                blendMode: 'normal',
                colorTag: null,
                elements: [],
            },
        ],
        activeLayerId: 'layer-1',
        elements: [],
    } as unknown as ProjectState;
}

describe('useSvgImport image (Task 16)', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        sendSpy.mockClear();
        sendWithAckSpy.mockClear().mockResolvedValue({});
        mockFetch.mockClear();

        const project = useProjectStore();
        project.setSnapshot(makeSeedState());

        // Seed sessionId
        const net = useNetworkStore();
        net.sessionId = 'test-session-id';
    });

    it('<image href="data:..."> → one upload fetch + element.add with type=image and source', async () => {
        const { importSvg } = useSvgImport();
        const result = await importSvg(
            `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
                <image x="10" y="20" width="50" height="60" href="data:image/png;base64,iVBORw0KGgo="/>
            </svg>`,
        );

        expect(result.count).toBe(1);

        // fetch should have been called twice: once for data URL, once for /api/upload
        const calls = mockFetch.mock.calls.map((c) => String(c[0]));
        expect(calls.some((u) => u.startsWith('data:'))).toBe(true);
        expect(calls.some((u) => u.includes('/api/upload'))).toBe(true);

        // element.add should have been called with type='image' and correct source
        expect(sendWithAckSpy).toHaveBeenCalledTimes(1);
        expect(sendWithAckSpy.mock.calls[0][0]).toBe('element.add');
        const payload = sendWithAckSpy.mock.calls[0][1] as { type: string; props: Record<string, unknown> };
        expect(payload.type).toBe('image');
        expect(payload.props.source).toBe('aabbccddeeff0011');
    });

    it('<image> with external URL → skipped (console.warn, no upload)', async () => {
        const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
        const { importSvg } = useSvgImport();
        const result = await importSvg(
            `<svg xmlns="http://www.w3.org/2000/svg">
                <image x="0" y="0" width="10" height="10" href="https://example.com/img.png"/>
            </svg>`,
        );

        expect(result.count).toBe(0);
        expect(sendWithAckSpy).not.toHaveBeenCalled();
        expect(warnSpy).toHaveBeenCalled();
        warnSpy.mockRestore();
    });
});
