<script setup lang="ts">
/**
 * 0.4.4：车次详情子 modal。
 * - 编辑 runNumber / direction / serviceType / cars / startStation / endStation / notes
 * - 时刻表 inline 编辑（每站 arrival / departure / stopsHere）
 * - 「自动生成」按钮 → 弹自动生成对话框（首站时间 + 站间秒 + 跳站集合）
 */
import { computed, ref, watch, onMounted } from 'vue';
import { X, Wand2, Save, Train } from 'lucide-vue-next';
import { getWsClient } from '@/network/wsClient';
import { useRailStore } from '@/stores/rail';
import { useNetworkStore } from '@/stores/network';
import { useI18n } from '@/i18n';
import { SERVICE_TYPE_ENUMS } from '@/types/rail';
import type { RailStation, RailTimetableEntry } from '@/types/rail';

const props = defineProps<{ runId: string; stations: RailStation[] }>();
const emit = defineEmits<{ (e: 'close'): void }>();

const ws = getWsClient();
const rail = useRailStore();
const net = useNetworkStore();
const { t } = useI18n();

const run = computed(() => rail.runs.get(props.runId));

const draftRunNumber = ref('');
const draftDirection = ref<'up' | 'down'>('up');
const draftServiceType = ref('local');
const draftCars = ref<number | ''>('');
const draftStartId = ref<string>('');
const draftEndId = ref<string>('');
const draftNotes = ref('');

/** 时刻表 inline 编辑草稿 — 站点 id → { arrival, departure, stopsHere }。 */
const timetableDraft = ref<Map<string, { arrival: string; departure: string; stopsHere: boolean }>>(new Map());

const showAutoGenerate = ref(false);
const autoFirst = ref('06:00:00');
const autoTravel = ref(90);
const autoDwell = ref(30);
const autoSkips = ref<Set<string>>(new Set());

const error = ref<string | null>(null);
const submitting = ref(false);

onMounted(() => {
    syncDraftFromStore();
});

watch(run, syncDraftFromStore);

function syncDraftFromStore() {
    const r = run.value;
    if (!r) return;
    draftRunNumber.value = r.runNumber;
    draftDirection.value = r.direction;
    draftServiceType.value = r.serviceType;
    draftCars.value = r.cars ?? '';
    draftStartId.value = r.startStationId ?? '';
    draftEndId.value = r.endStationId ?? '';
    draftNotes.value = r.notes ?? '';

    // 同步时刻表 draft（按当前 stations 顺序，缺失补空）
    const existing = rail.timetableOf(props.runId);
    const existingMap = new Map<string, RailTimetableEntry>();
    for (const e of existing) existingMap.set(e.stationId, e);
    const next = new Map<string, { arrival: string; departure: string; stopsHere: boolean }>();
    for (const s of props.stations) {
        const ex = existingMap.get(s.id);
        next.set(s.id, {
            arrival: ex?.arrival ?? '',
            departure: ex?.departure ?? '',
            stopsHere: ex?.stopsHere ?? true,
        });
    }
    timetableDraft.value = next;
}

async function saveBasic() {
    if (!run.value) return;
    submitting.value = true;
    error.value = null;
    try {
        const cars = draftCars.value === '' ? null : Number(draftCars.value);
        const { run: updated } = await ws.sendRailRunUpdate(props.runId, {
            runNumber: draftRunNumber.value.trim(),
            direction: draftDirection.value,
            serviceType: draftServiceType.value.trim(),
            cars,
            startStationId: draftStartId.value || null,
            endStationId: draftEndId.value || null,
            notes: draftNotes.value.trim() || null,
        });
        rail.setRun(updated);
    } catch (e) {
        error.value = (e as Error).message;
        net.pushLog('err', `rail.run.update rejected: ${(e as Error).message}`);
    } finally {
        submitting.value = false;
    }
}

async function saveTimetable() {
    submitting.value = true;
    error.value = null;
    try {
        const entries = Array.from(timetableDraft.value.entries()).map(([stationId, row]) => ({
            stationId,
            arrival: row.arrival.trim() || null,
            departure: row.departure.trim() || null,
            stopsHere: row.stopsHere,
        }));
        await ws.sendRailTimetableSet(props.runId, entries);
        rail.setTimetable(props.runId, entries.map((e) => ({
            runId: props.runId,
            stationId: e.stationId,
            arrival: e.arrival,
            departure: e.departure,
            stopsHere: e.stopsHere,
        })));
    } catch (e) {
        error.value = (e as Error).message;
        net.pushLog('err', `rail.run.timetable.set rejected: ${(e as Error).message}`);
    } finally {
        submitting.value = false;
    }
}

async function runAutoGenerate() {
    if (!run.value) return;
    submitting.value = true;
    error.value = null;
    try {
        // 复用 rail.run.create 路径不行（已建）；改走"先 update（更新区间起止）→ 重置 timetable"
        // 简化方案：调 timetable.set 前在前端用 generator 算出 rows 也行
        // 这里走后端：rerun create with generateOptions 不行（已存在），所以前端跑算法 → set
        const stations = props.stations.slice();
        if (draftDirection.value === 'down') stations.reverse();
        // 截取区间段
        let startIdx = 0;
        let endIdx = stations.length - 1;
        if (draftStartId.value) {
            const i = stations.findIndex((s) => s.id === draftStartId.value);
            if (i >= 0) startIdx = i;
        }
        if (draftEndId.value) {
            const i = stations.findIndex((s) => s.id === draftEndId.value);
            if (i >= 0) endIdx = i;
        }
        if (startIdx > endIdx) throw new Error('start station after end station');

        const segment = stations.slice(startIdx, endIdx + 1);
        const rows: Array<{ stationId: string; arrival: string | null;
                            departure: string | null; stopsHere: boolean }> = [];
        let cursor = parseTime(autoFirst.value);
        for (let i = 0; i < segment.length; i++) {
            const s = segment[i];
            const isFirst = i === 0;
            const isLast = i === segment.length - 1;
            const skip = autoSkips.value.has(s.id);
            if (skip) {
                rows.push({
                    stationId: s.id,
                    arrival: isFirst ? null : formatTime(cursor),
                    departure: isLast ? null : formatTime(cursor),
                    stopsHere: false,
                });
                cursor += autoTravel.value;
            } else if (isFirst) {
                rows.push({
                    stationId: s.id,
                    arrival: null,
                    departure: formatTime(cursor),
                    stopsHere: true,
                });
                cursor += autoTravel.value;
            } else if (isLast) {
                rows.push({
                    stationId: s.id,
                    arrival: formatTime(cursor),
                    departure: null,
                    stopsHere: true,
                });
            } else {
                rows.push({
                    stationId: s.id,
                    arrival: formatTime(cursor),
                    departure: formatTime(cursor + autoDwell.value),
                    stopsHere: true,
                });
                cursor += autoDwell.value + autoTravel.value;
            }
        }
        await ws.sendRailTimetableSet(props.runId, rows);
        rail.setTimetable(props.runId, rows.map((r) => ({
            runId: props.runId,
            stationId: r.stationId,
            arrival: r.arrival,
            departure: r.departure,
            stopsHere: r.stopsHere,
        })));
        // 把 draft 同步到 UI（让 inline 编辑显示生成的值）
        const draft = new Map(timetableDraft.value);
        for (const r of rows) {
            draft.set(r.stationId, {
                arrival: r.arrival ?? '',
                departure: r.departure ?? '',
                stopsHere: r.stopsHere,
            });
        }
        // 未在区间内的站点保持原样
        timetableDraft.value = draft;
        showAutoGenerate.value = false;
    } catch (e) {
        error.value = (e as Error).message;
    } finally {
        submitting.value = false;
    }
}

function toggleSkip(id: string) {
    const next = new Set(autoSkips.value);
    if (next.has(id)) next.delete(id); else next.add(id);
    autoSkips.value = next;
}

function parseTime(s: string): number {
    const parts = s.split(':');
    const h = Number(parts[0] ?? '0');
    const m = Number(parts[1] ?? '0');
    const sec = Number(parts[2] ?? '0');
    return h * 3600 + m * 60 + sec;
}

function formatTime(totalSec: number): string {
    const t = ((totalSec % 86400) + 86400) % 86400;
    const h = Math.floor(t / 3600);
    const m = Math.floor((t % 3600) / 60);
    const s = t % 60;
    return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
}

function getRow(stationId: string) {
    let row = timetableDraft.value.get(stationId);
    if (!row) {
        row = { arrival: '', departure: '', stopsHere: true };
        timetableDraft.value.set(stationId, row);
    }
    return row;
}
</script>

<template>
  <div class="fixed inset-0 z-[60] bg-[color:var(--ctp-crust)]/50 flex items-center justify-center p-4"
       @click.self="emit('close')"
       @keydown.escape.prevent="emit('close')">
    <div class="bg-[color:var(--card)] text-[color:var(--foreground)] rounded-[var(--radius)] shadow-md w-full max-w-4xl max-h-[90vh] flex flex-col border border-[color:var(--border)]">
      <header class="flex items-center gap-2 px-4 h-11 border-b border-[color:var(--border)]">
        <Train class="size-4 text-[color:var(--ctp-mauve)]" />
        <h2 class="text-sm font-semibold">{{ t.rail.runDialogTitle }} {{ run?.runNumber ?? '' }}</h2>
        <button class="ml-auto p-1 rounded hover:bg-[color:var(--accent)]" @click="emit('close')">
          <X class="size-4" />
        </button>
      </header>

      <div v-if="error"
           class="px-3 py-2 text-xs bg-[color:var(--destructive)]/10 text-[color:var(--destructive)] border-b border-[color:var(--border)]">
        {{ error }}
      </div>

      <div class="flex-1 overflow-y-auto p-3 text-xs space-y-4">
        <!-- 基本字段 -->
        <section class="grid grid-cols-2 gap-2">
          <label class="block">
            <span class="text-[color:var(--muted-foreground)]">{{ t.rail.runNumber }}</span>
            <input type="text" class="hc-input mt-0.5 font-mono" v-model="draftRunNumber" maxlength="64" />
          </label>
          <label class="block">
            <span class="text-[color:var(--muted-foreground)]">{{ t.rail.direction }}</span>
            <select class="hc-input mt-0.5" v-model="draftDirection">
              <option value="up">{{ t.rail.directionUp }}</option>
              <option value="down">{{ t.rail.directionDown }}</option>
            </select>
          </label>
          <label class="block">
            <span class="text-[color:var(--muted-foreground)]">{{ t.rail.serviceType }}</span>
            <input type="text" class="hc-input mt-0.5" v-model="draftServiceType"
                   :list="`svc-list-${runId}`" maxlength="32" />
            <datalist :id="`svc-list-${runId}`">
              <option v-for="t in SERVICE_TYPE_ENUMS" :key="t" :value="t" />
            </datalist>
          </label>
          <label class="block">
            <span class="text-[color:var(--muted-foreground)]">{{ t.rail.cars }}</span>
            <input type="number" class="hc-input mt-0.5 font-mono" v-model.number="draftCars" min="1" max="32" />
          </label>
          <label class="block">
            <span class="text-[color:var(--muted-foreground)]">{{ t.rail.startStation }}</span>
            <select class="hc-input mt-0.5" v-model="draftStartId">
              <option value="">{{ t.rail.lineStart }}</option>
              <option v-for="s in stations" :key="s.id" :value="s.id">{{ s.name }}</option>
            </select>
          </label>
          <label class="block">
            <span class="text-[color:var(--muted-foreground)]">{{ t.rail.endStation }}</span>
            <select class="hc-input mt-0.5" v-model="draftEndId">
              <option value="">{{ t.rail.lineEnd }}</option>
              <option v-for="s in stations" :key="s.id" :value="s.id">{{ s.name }}</option>
            </select>
          </label>
          <label class="block col-span-2">
            <span class="text-[color:var(--muted-foreground)]">{{ t.rail.notes }}</span>
            <input type="text" class="hc-input mt-0.5" v-model="draftNotes" maxlength="256"
                   :placeholder="t.rail.notesPlaceholder" />
          </label>
        </section>

        <div class="flex items-center gap-2">
          <button class="hc-btn flex items-center gap-1 px-3 py-1 text-xs rounded-[var(--radius-sm)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)] hover:opacity-90 disabled:opacity-40"
                  :disabled="submitting"
                  @click="saveBasic">
            <Save class="size-3" />
            <span>{{ t.rail.saveBasic }}</span>
          </button>
          <button class="hc-btn flex items-center gap-1 px-3 py-1 text-xs rounded-[var(--radius-sm)] border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                  @click="showAutoGenerate = true">
            <Wand2 class="size-3" />
            <span>{{ t.rail.autoGenerate }}</span>
          </button>
        </div>

        <!-- 时刻表 inline -->
        <section>
          <div class="flex items-center gap-1.5 mb-2">
            <span class="font-medium">{{ t.rail.timetable }}</span>
            <button class="ml-auto hc-btn flex items-center gap-1 px-3 py-1 text-xs rounded-[var(--radius-sm)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)] hover:opacity-90 disabled:opacity-40"
                    :disabled="submitting"
                    @click="saveTimetable">
              <Save class="size-3" />
              <span>{{ t.rail.saveTimetable }}</span>
            </button>
          </div>
          <table class="w-full text-left">
            <thead>
              <tr class="text-[color:var(--muted-foreground)]">
                <th class="font-normal py-1">{{ t.rail.station }}</th>
                <th class="font-normal py-1 w-28">{{ t.rail.arrival }}</th>
                <th class="font-normal py-1 w-28">{{ t.rail.departure }}</th>
                <th class="font-normal py-1 w-16">{{ t.rail.stopsHere }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in stations" :key="s.id"
                  class="border-t border-[color:var(--border)]">
                <td class="py-1">{{ s.name }}</td>
                <td class="py-1">
                  <input type="text" class="hc-input font-mono"
                         :value="getRow(s.id).arrival"
                         @input="(e: Event) => getRow(s.id).arrival = (e.target as HTMLInputElement).value"
                         placeholder="HH:mm:ss" />
                </td>
                <td class="py-1">
                  <input type="text" class="hc-input font-mono"
                         :value="getRow(s.id).departure"
                         @input="(e: Event) => getRow(s.id).departure = (e.target as HTMLInputElement).value"
                         placeholder="HH:mm:ss" />
                </td>
                <td class="py-1 text-center">
                  <input type="checkbox"
                         :checked="getRow(s.id).stopsHere"
                         @change="(e: Event) => getRow(s.id).stopsHere = (e.target as HTMLInputElement).checked" />
                </td>
              </tr>
            </tbody>
          </table>
        </section>
      </div>

      <!-- 自动生成对话框（嵌套子 modal） -->
      <div v-if="showAutoGenerate"
           class="absolute inset-0 z-[70] bg-[color:var(--ctp-crust)]/60 flex items-center justify-center p-4"
           @click.self="showAutoGenerate = false">
        <div class="bg-[color:var(--card)] rounded-[var(--radius)] shadow-md w-full max-w-md p-4 border border-[color:var(--border)] space-y-3">
          <header class="flex items-center gap-2">
            <Wand2 class="size-4 text-[color:var(--ctp-blue)]" />
            <h3 class="font-semibold text-sm">{{ t.rail.autoGenerateTitle }}</h3>
          </header>
          <label class="block text-xs">
            <span class="text-[color:var(--muted-foreground)]">{{ t.rail.firstDeparture }}</span>
            <input type="text" class="hc-input mt-0.5 font-mono"
                   v-model="autoFirst" placeholder="HH:mm:ss" />
          </label>
          <div class="grid grid-cols-2 gap-2 text-xs">
            <label class="block">
              <span class="text-[color:var(--muted-foreground)]">{{ t.rail.travelSeconds }}</span>
              <input type="number" class="hc-input mt-0.5 font-mono" v-model.number="autoTravel" min="0" />
            </label>
            <label class="block">
              <span class="text-[color:var(--muted-foreground)]">{{ t.rail.dwellSeconds }}</span>
              <input type="number" class="hc-input mt-0.5 font-mono" v-model.number="autoDwell" min="0" />
            </label>
          </div>
          <div class="text-xs">
            <span class="text-[color:var(--muted-foreground)]">{{ t.rail.skipStations }}</span>
            <div class="mt-1 max-h-40 overflow-y-auto space-y-1">
              <label v-for="s in stations" :key="s.id"
                     class="flex items-center gap-1.5 cursor-pointer">
                <input type="checkbox"
                       :checked="autoSkips.has(s.id)"
                       @change="toggleSkip(s.id)" />
                <span>{{ s.name }}</span>
              </label>
            </div>
          </div>
          <div class="flex justify-end gap-2 pt-2">
            <button class="hc-btn px-3 py-1 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                    @click="showAutoGenerate = false">
              {{ t.rail.cancel }}
            </button>
            <button class="hc-btn px-3 py-1 text-xs rounded bg-[color:var(--primary)] text-[color:var(--primary-foreground)] hover:opacity-90"
                    @click="runAutoGenerate">
              {{ t.rail.generate }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hc-input {
    width: 100%;
    padding: 0.25rem 0.375rem;
    font-size: 0.75rem;
    line-height: 1rem;
    border-radius: 4px;
    background: var(--background);
    color: var(--foreground);
    border: 1px solid var(--border);
}
.hc-input:focus {
    outline: none;
    border-color: var(--ring);
    box-shadow: 0 0 0 1px var(--ring);
}
</style>
