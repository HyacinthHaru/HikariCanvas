// @vitest-environment happy-dom
/**
 * 车次详情对话框：时刻表草稿不被「保存基本信息」冲掉。
 *
 * <p>基本信息和时刻表是两个独立的保存按钮。保存基本信息后 store 会换上服务端返回的新 run
 * 对象，{@code watch(run, …)} 随即触发整体重建草稿——修复前会拿 store 里的<b>旧</b>时刻表
 * 把用户刚填、还没保存的一屏时间盖掉，全程没有任何提示。</p>
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import RailRunDialog from '../RailRunDialog.vue';
import { useRailStore } from '@/stores/rail';
import { useUiStore } from '@/stores/ui';
import type { RailRun, RailStation } from '@/types/rail';

// 服务端返回一个「新对象」的 run（真实 ack 路径就是这样，身份必变）
const sendRailRunUpdate = vi.fn(async (runId: string, patch: Record<string, unknown>) => ({
    run: { ...makeRun(), ...patch, id: runId, updatedAt: Date.now() + 1 } as RailRun,
}));
const sendRailTimetableSet = vi.fn(async () => ({}));
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({ sendRailRunUpdate, sendRailTimetableSet }),
}));

function makeRun(): RailRun {
    return {
        id: 'run-1', lineId: 'line-1', runNumber: '1001', direction: 'up',
        serviceType: 'local', cars: 4, startStationId: null, endStationId: null,
        notes: null, createdAt: 0, updatedAt: 0,
    };
}

function makeStations(): RailStation[] {
    return [
        { id: 'st-1', lineId: 'line-1', name: '起点', sortOrder: 0, isTerminus: true, createdAt: 0 },
        { id: 'st-2', lineId: 'line-1', name: '中途', sortOrder: 1, isTerminus: false, createdAt: 0 },
    ];
}

beforeEach(() => {
    setActivePinia(createPinia());
    useUiStore().locale = 'zh';
    sendRailRunUpdate.mockClear();
    sendRailTimetableSet.mockClear();
});

function mountDialog() {
    const rail = useRailStore();
    rail.setRun(makeRun());
    rail.setTimetable('run-1', [
        { runId: 'run-1', stationId: 'st-1', arrival: null, departure: '06:00:00', stopsHere: true },
        { runId: 'run-1', stationId: 'st-2', arrival: '06:05:00', departure: null, stopsHere: true },
    ]);
    const w = mount(RailRunDialog, {
        props: { runId: 'run-1', stations: makeStations() },
        attachTo: document.body,
    });
    return { w, rail };
}

/** 取时刻表区里的时间输入框（顺序 = 站点顺序 × [到达, 出发]）。 */
function timeInputs(w: ReturnType<typeof mountDialog>['w']) {
    return w.findAll('input[type="time"]');
}

describe('RailRunDialog — 时刻表草稿保护', () => {
    it('保存基本信息后，未保存的时刻表编辑仍在（不被 store 旧值覆盖）', async () => {
        const { w } = mountDialog();
        await nextTick();

        // 改「中途」站的到达时间（第 3 个 time 输入框 = 站 2 的 arrival）
        const arrivalOfSt2 = timeInputs(w)[2];
        await arrivalOfSt2.setValue('07:30:00');
        // happy-dom 的 time 控件会把值规范化（掉秒），拿它当基准比对即可
        const edited = (timeInputs(w)[2].element as HTMLInputElement).value;
        expect(edited).not.toBe('06:05:00');

        // 点「保存基本信息」→ store.setRun 换新对象 → watch(run) 触发
        const saveBasic = w.findAll('button').find((b) => b.text().includes('保存基本信息'));
        expect(saveBasic).toBeTruthy();
        await saveBasic!.trigger('click');
        await nextTick();
        await nextTick();

        // 时刻表编辑必须还在
        expect((timeInputs(w)[2].element as HTMLInputElement).value).toBe(edited);
        w.unmount();
    });

    it('有未保存改动时显示提示；保存时刻表后提示消失', async () => {
        const { w } = mountDialog();
        await nextTick();
        expect(w.text()).not.toContain('时刻表有改动还没保存');

        await timeInputs(w)[2].setValue('07:30:00');
        await nextTick();
        expect(w.text()).toContain('时刻表有改动还没保存');

        const saveTimetable = w.findAll('button').find((b) => b.text().includes('保存时刻表'));
        await saveTimetable!.trigger('click');
        await nextTick();
        await nextTick();
        expect(sendRailTimetableSet).toHaveBeenCalled();
        expect(w.text()).not.toContain('时刻表有改动还没保存');
        w.unmount();
    });

    it('没动过时刻表时，run 变化仍会照常用 store 值重建草稿', async () => {
        const { w, rail } = mountDialog();
        await nextTick();

        // 外部（比如主 modal 拉详情）更新了时刻表 + run
        rail.setTimetable('run-1', [
            { runId: 'run-1', stationId: 'st-1', arrival: null, departure: '08:00:00', stopsHere: true },
            { runId: 'run-1', stationId: 'st-2', arrival: '08:05:00', departure: null, stopsHere: true },
        ]);
        rail.setRun({ ...makeRun(), runNumber: '2002', updatedAt: 99 });
        await nextTick();

        expect((timeInputs(w)[1].element as HTMLInputElement).value).toBe('08:00');
        expect((timeInputs(w)[2].element as HTMLInputElement).value).toBe('08:05');
        w.unmount();
    });
});
