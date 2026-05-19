import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import type { ScheduleEntry, SchedulePrecision, WallSchedule } from '@/types/schedule';

/**
 * 0.4.0-P3-L：列车 / 公交时刻表前端镜像 store。
 *
 * <p>不是全局变量 store——schedule 是 per-wall 元数据 + entries，仅 ScheduleManagerModal
 * / TopBar 按钮 / Provider live-preview 使用。打开 modal 时调
 * {@code wsClient.sendScheduleList(wallId)} 一次性拉数据；后续 op 走 ack payload 自己更新本地
 * mirror（不像 variables 走 state.patch 广播，schedule 用户量小不值得多路推送）。</p>
 *
 * <p>wall 切换时 {@link reset} 清空（由 {@code project.reset()} 触发）；同 wall 重连保留。</p>
 *
 * <p>0.4.0 bugfix（Bug 4）：增加 precision 字段（minute / second），UI 切换控制 entry 时间
 * 输入精度 + Provider 刷新频率。</p>
 */
export const useScheduleStore = defineStore('schedule', () => {
    /** 当前 wall 的时刻表；null = 未加载 / 元数据行不存在。 */
    const current = ref<WallSchedule | null>(null);
    /** 当前 wall 的 schedule.list 加载中。 */
    const loading = ref(false);
    const lastError = ref<string | null>(null);

    function setLoaded(schedule: WallSchedule | null): void {
        current.value = schedule;
        loading.value = false;
        lastError.value = null;
    }

    function setStationName(stationName: string | null): void {
        if (!current.value) {
            current.value = {
                wallId: '',
                stationName,
                updatedAt: Date.now(),
                precision: 'minute',
                entries: [],
            };
        } else {
            current.value = { ...current.value, stationName, updatedAt: Date.now() };
        }
    }

    /** 0.4.0 bugfix（Bug 4）：本地切换精度（upsert ack 回灌时调）。 */
    function setPrecision(precision: SchedulePrecision): void {
        if (!current.value) {
            current.value = {
                wallId: '',
                stationName: null,
                updatedAt: Date.now(),
                precision,
                entries: [],
            };
        } else {
            current.value = { ...current.value, precision, updatedAt: Date.now() };
        }
    }

    function upsertEntry(entry: ScheduleEntry): void {
        if (!current.value) {
            current.value = {
                wallId: '',
                stationName: null,
                updatedAt: Date.now(),
                precision: 'minute',
                entries: [entry],
            };
            return;
        }
        const idx = current.value.entries.findIndex((e) => e.id === entry.id);
        const next = current.value.entries.slice();
        if (idx >= 0) next[idx] = entry;
        else next.push(entry);
        // 按 sortOrder ASC, departureTime ASC 排序
        next.sort((a, b) => {
            if (a.sortOrder !== b.sortOrder) return a.sortOrder - b.sortOrder;
            return a.departureTime.localeCompare(b.departureTime);
        });
        current.value = { ...current.value, entries: next, updatedAt: Date.now() };
    }

    function removeEntry(id: number): void {
        if (!current.value) return;
        const next = current.value.entries.filter((e) => e.id !== id);
        current.value = { ...current.value, entries: next, updatedAt: Date.now() };
    }

    function setLoading(v: boolean): void {
        loading.value = v;
    }
    function setError(msg: string | null): void {
        lastError.value = msg;
    }

    /** wall 切换 / 网络断开复位（与 ui.reset / project.reset 联动）。 */
    function reset(): void {
        current.value = null;
        loading.value = false;
        lastError.value = null;
    }

    /** entries 按已排序顺序返回（modal 渲染用）。 */
    const sortedEntries = computed<ScheduleEntry[]>(() => current.value?.entries ?? []);

    /** 当前精度（缺省 minute）。 */
    const precision = computed<SchedulePrecision>(() => current.value?.precision ?? 'minute');

    return {
        current, loading, lastError,
        sortedEntries, precision,
        setLoaded, setStationName, setPrecision, upsertEntry, removeEntry,
        setLoading, setError,
        reset,
    };
});
