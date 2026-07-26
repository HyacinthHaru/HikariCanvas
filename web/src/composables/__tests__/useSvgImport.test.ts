// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';

const sendSpy = vi.fn(() => 'c-1');
const sendWithAckSpy = vi.fn().mockResolvedValue({});
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({ send: sendSpy, sendWithAck: sendWithAckSpy }),
}));

import { useSvgImport } from '../useSvgImport';
import { useProjectStore } from '@/stores/project';
import type { ProjectState } from '@/types/protocol';

/** 最小可用 ProjectState：一个非锁图层作为 activeLayer */
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

describe('useSvgImport', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        sendSpy.mockClear();
        sendWithAckSpy.mockClear().mockResolvedValue({});
        // seed 一个非锁 activeLayer 到 project store
        const project = useProjectStore();
        project.setSnapshot(makeSeedState());
    });

    it('sends one element.add per shape', async () => {
        const r = await useSvgImport().importSvg(
            `<svg xmlns="http://www.w3.org/2000/svg"><rect x="0" y="0" width="5" height="5" fill="#000000"/><path d="M0 0 L1 1" stroke="#fff" stroke-width="1"/></svg>`);
        expect(r.count).toBe(2);
        expect(sendWithAckSpy).toHaveBeenCalledTimes(2);
        expect(sendWithAckSpy.mock.calls[0][0]).toBe('element.add');
        expect(sendWithAckSpy.mock.calls[0][1]).toMatchObject({ type: 'path' });
    });

    it('returns count 0 when no writable layer exists', async () => {
        const project = useProjectStore();
        const lockedState = makeSeedState();
        lockedState.layers[0].locked = true;
        project.setSnapshot(lockedState);

        const r = await useSvgImport().importSvg(
            `<svg xmlns="http://www.w3.org/2000/svg"><rect x="0" y="0" width="5" height="5" fill="#000000"/></svg>`);
        expect(r.count).toBe(0);
        expect(sendWithAckSpy).not.toHaveBeenCalled();
    });
});
