// @vitest-environment happy-dom
/**
 * 改完站名紧接着切精度。
 *
 * <p>输入框失焦会先发一次「存新站名」，那一发还在路上时 store 里仍是旧站名。切精度这一发
 * 如果按 store 的值填站名，服务端按顺序处理，最后存下的就是旧站名，而先前那发的回执又把
 * 界面写成新站名——界面和数据库对不上，一直骗到下次刷新。</p>
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import ScheduleManagerModal from '../ScheduleManagerModal.vue';
import { useUiStore } from '@/stores/ui';
import { useProjectStore } from '@/stores/project';
import { useScheduleStore } from '@/stores/schedule';

/** 服务端那边的持久化状态。 */
let stored = { stationName: '旧站名' as string | null, precision: 'minute' as string };
/** 挂起中的 upsert 回执（测试自己决定何时落地，模拟"还在路上"）。 */
let pendingAcks: Array<() => void> = [];

const upsertCalls: Array<{ stationName: string | null; precision?: string }> = [];

const sendScheduleList = vi.fn(async () => ({
    schedule: {
        wallId: 'w-abc',
        stationName: stored.stationName,
        precision: stored.precision,
        entries: [],
        updatedAt: 0,
    },
}));

const sendScheduleUpsert = vi.fn((stationName: string | null, precision?: string) => {
    upsertCalls.push({ stationName, precision });
    return new Promise<{ stationName: string | null; precision?: string }>((res) => {
        pendingAcks.push(() => {
            // 服务端按到达顺序落库
            stored = { stationName, precision: precision ?? stored.precision };
            res({ stationName, precision: precision ?? stored.precision });
        });
    });
});

vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({
        sendScheduleList,
        sendScheduleUpsert,
        sendScheduleEntryAdd: vi.fn(),
        sendScheduleEntryUpdate: vi.fn(),
        sendScheduleEntryDelete: vi.fn(),
        sendRailLineList: vi.fn(async () => ({ lines: [] })),
        sendRailLineDetail: vi.fn(),
        sendRailWallBind: vi.fn(),
    }),
}));

beforeEach(() => {
    setActivePinia(createPinia());
    useUiStore().locale = 'zh';
    stored = { stationName: '旧站名', precision: 'minute' };
    pendingAcks = [];
    upsertCalls.length = 0;
    sendScheduleUpsert.mockClear();
});

async function mountModal() {
    useProjectStore().setWallMeta('w-abc', null, null, null, null);
    const w = mount(ScheduleManagerModal, { attachTo: document.body });
    // watch(open) 是异步的，先挂载再打开
    useUiStore().scheduleManagerOpen = true;
    await nextTick();
    await Promise.resolve();
    await Promise.resolve();
    await nextTick();
    return w;
}

function stationInput(w: Awaited<ReturnType<typeof mountModal>>) {
    return w.findAll('input[type="text"]')[0];
}

function precisionButton(w: Awaited<ReturnType<typeof mountModal>>, label: string) {
    return w.findAll('button').find((b) => b.text().startsWith(label));
}

describe('ScheduleManagerModal — 改站名后立刻切精度', () => {
    it('切精度那一发带的是输入框里的新站名，不是 store 里的旧站名', async () => {
        const w = await mountModal();
        expect(useScheduleStore().current?.stationName).toBe('旧站名');

        // 改站名 → 失焦（先发出去，回执还没回来）
        await stationInput(w).setValue('新站名');
        await stationInput(w).trigger('blur');
        await nextTick();
        expect(upsertCalls[0]).toEqual({ stationName: '新站名', precision: 'minute' });
        // 回执故意不落地：store 里还是旧站名
        expect(useScheduleStore().current?.stationName).toBe('旧站名');

        // 紧接着切精度
        await precisionButton(w, '秒（')!.trigger('click');
        await nextTick();
        expect(upsertCalls[1]).toEqual({ stationName: '新站名', precision: 'second' });

        // 两发都落地后，服务端存的是新站名 + 新精度
        pendingAcks.forEach((r) => r());
        await nextTick();
        await Promise.resolve();
        await nextTick();
        expect(stored).toEqual({ stationName: '新站名', precision: 'second' });
        expect(useScheduleStore().current?.stationName).toBe('新站名');
        w.unmount();
    });

    it('没动过站名时切精度照常带当前站名', async () => {
        const w = await mountModal();
        await precisionButton(w, '秒（')!.trigger('click');
        await nextTick();
        expect(upsertCalls[0]).toEqual({ stationName: '旧站名', precision: 'second' });
        w.unmount();
    });
});
