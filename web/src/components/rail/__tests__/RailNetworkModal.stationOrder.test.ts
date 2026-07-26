// @vitest-environment happy-dom
/**
 * 站点上移 / 下移 + 整线重拉。
 *
 * <ul>
 *   <li><b>上移 / 下移必须和相邻站换位</b>：只把自己的号 ±1 会和相邻站撞成同一个号，撞号后
 *       列表按名字排，按钮看着就"点了没反应"；重复的号还会存进数据库，影响时刻表里的站序。</li>
 *   <li><b>整线重拉要能删掉本地残留</b>：铁路数据全服共享、服务端不广播，别人删掉的站只能
 *       靠重拉对齐；只往旧表里合并的话，那些站永远消不掉。</li>
 * </ul>
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import RailNetworkModal from '../RailNetworkModal.vue';
import { useRailStore } from '@/stores/rail';
import { useUiStore } from '@/stores/ui';
import type { RailLine, RailStation } from '@/types/rail';

const line: RailLine = {
    id: 'line-1', name: '一号线', code: 'L1', color: '#3B82F6',
    ownerUuid: null, createdAt: 0, updatedAt: 0,
} as unknown as RailLine;

function mkStation(id: string, name: string, sortOrder: number): RailStation {
    return { id, lineId: 'line-1', name, sortOrder, isTerminus: false, createdAt: 0 };
}

/** 服务端持有的站点表（update 直接改这里，模拟真实持久化）。 */
let serverStations: RailStation[] = [];

const sendRailLineList = vi.fn(async () => ({ lines: [line] }));
const sendRailLineDetail = vi.fn(async () => ({
    line,
    stations: serverStations.map((s) => ({ ...s })),
    runs: [],
    timetableByRun: {},
}));
const sendRailStationUpdate = vi.fn(async (id: string, patch: { sortOrder?: number }) => {
    const s = serverStations.find((x) => x.id === id)!;
    if (patch.sortOrder !== undefined) s.sortOrder = patch.sortOrder;
    return { station: { ...s } };
});

vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({
        sendRailLineList,
        sendRailLineDetail,
        sendRailStationUpdate,
        sendRailLineCreate: vi.fn(),
        sendRailLineDelete: vi.fn(),
        sendRailStationAdd: vi.fn(),
        sendRailStationDelete: vi.fn(),
        sendRailRunCreate: vi.fn(),
        sendRailRunDelete: vi.fn(),
    }),
}));

beforeEach(() => {
    setActivePinia(createPinia());
    useUiStore().locale = 'zh';
    serverStations = [
        mkStation('st-1', 'A 站', 0),
        mkStation('st-2', 'B 站', 1),
        mkStation('st-3', 'C 站', 2),
    ];
    sendRailStationUpdate.mockClear();
    sendRailLineDetail.mockClear();
});

async function mountModal() {
    const w = mount(RailNetworkModal, { attachTo: document.body });
    // onMounted → loadAll → selectLine（两层 await）
    await nextTick();
    await Promise.resolve();
    await Promise.resolve();
    await nextTick();
    return w;
}

/** 当前列表里的站名顺序。 */
function stationOrder(): string[] {
    return useRailStore().stationsByLine('line-1').map((s) => s.name);
}

function sortButtons(w: Awaited<ReturnType<typeof mountModal>>, dir: '↑' | '↓') {
    return w.findAll('button').filter((b) => b.text() === dir);
}

describe('RailNetworkModal — 站点排序', () => {
    it('下移一站 = 与下一站换位，号不重复', async () => {
        const w = await mountModal();
        expect(stationOrder()).toEqual(['A 站', 'B 站', 'C 站']);

        // 第 1 站的「↓」
        await sortButtons(w, '↓')[0].trigger('click');
        await nextTick();
        await Promise.resolve();
        await Promise.resolve();
        await nextTick();

        expect(stationOrder()).toEqual(['B 站', 'A 站', 'C 站']);
        // 号必须两两不同
        const orders = serverStations.map((s) => s.sortOrder).sort();
        expect(new Set(orders).size).toBe(orders.length);
        w.unmount();
    });

    it('上移一站 = 与上一站换位', async () => {
        const w = await mountModal();
        // 第 3 站的「↑」
        await sortButtons(w, '↑')[2].trigger('click');
        await nextTick();
        await Promise.resolve();
        await Promise.resolve();
        await nextTick();

        expect(stationOrder()).toEqual(['A 站', 'C 站', 'B 站']);
        w.unmount();
    });

    it('号本来就重复时也能把顺序推动（并顺手抹平重复号）', async () => {
        serverStations = [
            mkStation('st-1', 'A 站', 0),
            mkStation('st-2', 'B 站', 0),
            mkStation('st-3', 'C 站', 0),
        ];
        const w = await mountModal();
        // 重复号 + 名字兜底排序 → A / B / C
        expect(stationOrder()).toEqual(['A 站', 'B 站', 'C 站']);

        await sortButtons(w, '↓')[0].trigger('click');
        await nextTick();
        await Promise.resolve();
        await Promise.resolve();
        await nextTick();

        expect(stationOrder()).toEqual(['B 站', 'A 站', 'C 站']);
        expect(serverStations.map((s) => s.sortOrder).sort()).toEqual([0, 1, 2]);
        w.unmount();
    });

    it('第一站的「↑」和最后一站的「↓」是禁用的，点不动也不发包', async () => {
        const w = await mountModal();
        const ups = sortButtons(w, '↑');
        const downs = sortButtons(w, '↓');
        expect((ups[0].element as HTMLButtonElement).disabled).toBe(true);
        expect((downs[downs.length - 1].element as HTMLButtonElement).disabled).toBe(true);
        expect((ups[1].element as HTMLButtonElement).disabled).toBe(false);
        w.unmount();
    });

    it('别人删了一站，重新选中线路后本地列表里也没了', async () => {
        const w = await mountModal();
        expect(stationOrder()).toEqual(['A 站', 'B 站', 'C 站']);

        // 服务端那边把 B 站删了
        serverStations = serverStations.filter((s) => s.id !== 'st-2');
        // 重新点这条线（整线重拉）
        const lineRow = w.findAll('div.cursor-pointer').find((d) => d.text().includes('一号线'));
        await lineRow!.trigger('click');
        await nextTick();
        await Promise.resolve();
        await Promise.resolve();
        await nextTick();

        expect(stationOrder()).toEqual(['A 站', 'C 站']);
        w.unmount();
    });
});
