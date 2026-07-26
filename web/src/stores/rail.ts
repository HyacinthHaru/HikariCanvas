import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import type {
    RailLine, RailStation, RailRun, RailTimetableEntry, WallRailBinding,
} from '@/types/rail';

/**
 * 铁路网络前端镜像。
 *
 * <p>不通过 state.patch 走（rail 不影响 ProjectState），全部 ack-driven —— 由
 * RailNetworkModal 自己调 wsClient send* 后把 ack payload 写入 store。store 维护跨
 * modal 一致性 + 跨组件复用（Schedule Manager 也要列线路 / 站点供 binding 下拉）。</p>
 */
export const useRailStore = defineStore('rail', () => {
    /** 线路 id → RailLine 主表。 */
    const lines = ref<Map<string, RailLine>>(new Map());
    /** 站点 id → RailStation。 */
    const stations = ref<Map<string, RailStation>>(new Map());
    /** 车次 id → RailRun。 */
    const runs = ref<Map<string, RailRun>>(new Map());
    /** 当前正在编辑的车次 id → timetable entries（站点 id → entry）。懒加载。 */
    const timetables = ref<Map<string, Map<string, RailTimetableEntry>>>(new Map());
    /** 当前 wall 的 binding（null = 未绑定 / 未加载）。 */
    const binding = ref<WallRailBinding | null>(null);

    // ── 写入 helpers（写穿 reactivity，赋新 Map） ──

    function setLine(line: RailLine) {
        const next = new Map(lines.value);
        next.set(line.id, line);
        lines.value = next;
    }

    function setLines(list: RailLine[]) {
        const next = new Map<string, RailLine>();
        for (const l of list) next.set(l.id, l);
        lines.value = next;
    }

    function removeLine(lineId: string) {
        if (!lines.value.has(lineId)) return;
        const next = new Map(lines.value);
        next.delete(lineId);
        lines.value = next;
        // 顺带清掉相关 stations / runs / timetables（FK CASCADE 已在 DB 层做，本地 mirror 自清）
        const sNext = new Map(stations.value);
        for (const [id, s] of sNext) if (s.lineId === lineId) sNext.delete(id);
        stations.value = sNext;
        const rNext = new Map(runs.value);
        for (const [id, r] of rNext) if (r.lineId === lineId) rNext.delete(id);
        runs.value = rNext;
    }

    function setStation(s: RailStation) {
        const next = new Map(stations.value);
        next.set(s.id, s);
        stations.value = next;
    }

    /**
     * 整批写入站点，并<b>整体替换</b>所涉线路的站点集合。
     *
     * <p>为什么不能只做合并：铁路数据是全服共享的，服务端改动不会广播，本端只能靠重新拉取
     * 对齐。只往旧 Map 上合并的话，别人删掉的站在这边永远删不掉——重拉多少次都还在列表里，
     * 点它做任何操作都被服务端拒绝。</p>
     *
     * <p>{@code lineId} 指明这批数据属于哪条线；不传时按 list 里出现过的线路推断（服务端
     * 返回空列表时就无从推断，所以调用方最好显式传）。其它线路的站点不受影响。</p>
     */
    function setStations(list: RailStation[], lineId?: string) {
        const next = new Map(stations.value);
        const touched = new Set<string>();
        if (lineId) touched.add(lineId);
        for (const s of list) touched.add(s.lineId);
        for (const [id, s] of next) if (touched.has(s.lineId)) next.delete(id);
        for (const s of list) next.set(s.id, s);
        stations.value = next;
    }

    function removeStation(id: string) {
        if (!stations.value.has(id)) return;
        const next = new Map(stations.value);
        next.delete(id);
        stations.value = next;
    }

    function setRun(r: RailRun) {
        const next = new Map(runs.value);
        next.set(r.id, r);
        runs.value = next;
    }

    /**
     * 整批写入车次，并<b>整体替换</b>所涉线路的车次集合（理由同 {@link setStations}）。
     * 被清掉的车次连带清掉它的时刻表缓存，不留孤儿。
     */
    function setRuns(list: RailRun[], lineId?: string) {
        const next = new Map(runs.value);
        const touched = new Set<string>();
        if (lineId) touched.add(lineId);
        for (const r of list) touched.add(r.lineId);
        const incoming = new Set(list.map((r) => r.id));
        const tNext = new Map(timetables.value);
        let timetableDirty = false;
        for (const [id, r] of next) {
            if (!touched.has(r.lineId) || incoming.has(id)) continue;
            // 这一趟车服务端已经没有了 → 连它的时刻表缓存一起清，不留孤儿
            next.delete(id);
            if (tNext.delete(id)) timetableDirty = true;
        }
        for (const r of list) next.set(r.id, r);
        runs.value = next;
        if (timetableDirty) timetables.value = tNext;
    }

    function removeRun(id: string) {
        if (!runs.value.has(id)) return;
        const next = new Map(runs.value);
        next.delete(id);
        runs.value = next;
        // 清 timetable 缓存
        const tNext = new Map(timetables.value);
        tNext.delete(id);
        timetables.value = tNext;
    }

    function setTimetable(runId: string, entries: RailTimetableEntry[]) {
        const map = new Map<string, RailTimetableEntry>();
        for (const e of entries) map.set(e.stationId, e);
        const next = new Map(timetables.value);
        next.set(runId, map);
        timetables.value = next;
    }

    function setBinding(b: WallRailBinding | null) {
        binding.value = b;
    }

    function reset() {
        lines.value = new Map();
        stations.value = new Map();
        runs.value = new Map();
        timetables.value = new Map();
        binding.value = null;
    }

    // ── 派生 ──

    const allLines = computed(() =>
        Array.from(lines.value.values()).sort((a, b) => a.name.localeCompare(b.name)));

    function stationsByLine(lineId: string): RailStation[] {
        const out: RailStation[] = [];
        for (const s of stations.value.values()) if (s.lineId === lineId) out.push(s);
        out.sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name));
        return out;
    }

    function runsByLine(lineId: string): RailRun[] {
        const out: RailRun[] = [];
        for (const r of runs.value.values()) if (r.lineId === lineId) out.push(r);
        out.sort((a, b) => a.direction.localeCompare(b.direction)
            || a.runNumber.localeCompare(b.runNumber));
        return out;
    }

    function timetableOf(runId: string): RailTimetableEntry[] {
        const m = timetables.value.get(runId);
        if (!m) return [];
        return Array.from(m.values());
    }

    return {
        lines, stations, runs, timetables, binding,
        setLine, setLines, removeLine,
        setStation, setStations, removeStation,
        setRun, setRuns, removeRun,
        setTimetable, setBinding,
        reset,
        allLines, stationsByLine, runsByLine, timetableOf,
    };
});
