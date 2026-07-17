import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import type { ScheduleEntry, SchedulePrecision, WallSchedule } from '@/types/schedule';
import { useProjectStore } from '@/stores/project';

/**
 * 列车 / 公交时刻表前端镜像 store。
 *
 * <p>不是全局变量 store——schedule 是 per-wall 元数据 + entries，仅 ScheduleManagerModal
 * / TopBar 按钮 / Provider live-preview 使用。打开 modal 时调
 * {@code wsClient.sendScheduleList(wallId)} 一次性拉数据；后续 op 走 ack payload 自己更新本地
 * mirror（不像 variables 走 state.patch 广播，schedule 用户量小不值得多路推送）。</p>
 *
 * <p>wall 切换时 {@link reset} 清空，统一由 {@code project.reset()} 调用（不再在
 * wsClient.handleReady 单独并列调），同 wall 重连保留。</p>
 *
 * <p>增加 precision 字段（minute / second），UI 切换控制 entry 时间
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

    /**
     * 占位 WallSchedule 的 wallId 取 project store 当前真值，避免硬编码空串魔法值。
     * project.wallId 在 ready handshake 设定，schedule modal 仅在已连接 wall 才可打开，故此处必有真值；
     * 极端边角（未连接）退回 '' 与旧行为一致。
     */
    function currentWallId(): string {
        return useProjectStore().wallId ?? '';
    }

    function setStationName(stationName: string | null): void {
        if (!current.value) {
            current.value = {
                wallId: currentWallId(),
                stationName,
                updatedAt: Date.now(),
                precision: 'minute',
                entries: [],
            };
        } else {
            current.value = { ...current.value, stationName, updatedAt: Date.now() };
        }
    }

    /** 本地切换精度（upsert ack 回灌时调）。 */
    function setPrecision(precision: SchedulePrecision): void {
        if (!current.value) {
            current.value = {
                wallId: currentWallId(),
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
                wallId: currentWallId(),
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
