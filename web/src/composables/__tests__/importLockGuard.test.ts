// @vitest-environment happy-dom
/**
 * 锁定守卫：SVG 导入 / 工程导入。
 *
 * 后端按纪律不看 lock、编辑 op 一律放行，前端是锁的唯一执行者。这两个入口以前一处守卫
 * 都没有：锁定的作品可以被塞进任意矢量图形，或者被一个 .canvas 文件整块盖掉。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';

const sendSpy = vi.fn(() => 'c-1');
vi.mock('@/network/wsClient', () => ({ getWsClient: () => ({ send: sendSpy }) }));

import { useSvgImport } from '../useSvgImport';
import { useProjectImport } from '../useProjectImport';
import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import { SvgImportError } from '@/lib/svg/svgSecurity';
import type { ProjectState } from '@/types/protocol';

function seedState(): ProjectState {
    return {
        version: 1,
        canvas: { widthMaps: 1, heightMaps: 1, background: null },
        layers: [{
            id: 'layer-1', name: 'L1', visible: true, locked: false,
            opacity: 1, blendMode: 'normal', colorTag: null, elements: [],
        }],
        activeLayerId: 'layer-1',
        elements: [],
    } as unknown as ProjectState;
}

const SVG = '<svg xmlns="http://www.w3.org/2000/svg"><rect x="0" y="0" width="5" height="5" fill="#000000"/></svg>';

describe('导入入口的锁定守卫', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        sendSpy.mockClear();
        useProjectStore().setSnapshot(seedState());
        useNetworkStore().sessionId = 'sess-1';
    });

    it('画板锁定时 SVG 导入抛 WALL_LOCKED，一条 element.add 都不发', async () => {
        useProjectStore().lockedAt = Date.now();
        await expect(useSvgImport().importSvg(SVG)).rejects.toBeInstanceOf(SvgImportError);
        expect(sendSpy).not.toHaveBeenCalled();
    });

    it('未锁定时 SVG 导入照常发 element.add（回归守卫）', async () => {
        const r = await useSvgImport().importSvg(SVG);
        expect(r.count).toBe(1);
        expect(sendSpy).toHaveBeenCalledTimes(1);
    });

    it('画板锁定时工程导入直接返回 WALL_LOCKED，不发请求', async () => {
        useProjectStore().lockedAt = Date.now();
        const fetchSpy = vi.fn();
        vi.stubGlobal('fetch', fetchSpy);
        const r = await useProjectImport().importProject(new File([new Uint8Array([1])], 'x.canvas'));
        expect(r.ok).toBe(false);
        expect(r.errorCode).toBe('WALL_LOCKED');
        expect(fetchSpy).not.toHaveBeenCalled();
    });
});
