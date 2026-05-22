<script setup lang="ts">
/**
 * 0.4.4：铁路网络管理 modal。
 *
 * 三层：线路 → 站点 + 车次 → 车次详情（独立子 modal）。
 *
 * - 顶部：线路列表（左侧）+ 选中线路的详情（右侧 站点 / 车次 两个 section）
 * - 车次行点 ✏ 弹 RailRunDialog（含时刻表 inline 编辑 + 自动生成对话框）
 * - 创建线路 → 弹简化输入框（name + code + color）
 */
import { computed, onMounted, ref } from 'vue';
import {
    X, Plus, Trash2, Pencil, Train, Building2, MapPin, GripVertical,
} from 'lucide-vue-next';
import { getWsClient } from '@/network/wsClient';
import { useRailStore } from '@/stores/rail';
import { useNetworkStore } from '@/stores/network';
import { useI18n } from '@/i18n';
import type { RailLine, RailRun, RailStation } from '@/types/rail';
import RailRunDialog from './RailRunDialog.vue';

const emit = defineEmits<{ (e: 'close'): void }>();

const ws = getWsClient();
const rail = useRailStore();
const net = useNetworkStore();
const { t } = useI18n();

const loading = ref(false);
const selectedLineId = ref<string | null>(null);
const lineName = ref('');
const lineCode = ref('');
const lineColor = ref('#3B82F6');
const stationDraftName = ref('');
const runDialogOpenForRunId = ref<string | null>(null);
const errorMsg = ref<string | null>(null);

// 0.4.5 P2：inline 删除确认（替换 confirm()）。type + id 共同标识哪一行
const confirmingDelete = ref<{ type: 'line' | 'station' | 'run'; id: string } | null>(null);

// 0.4.5 P2：新建车次对话框（替换 prompt()）
const showNewRunDialog = ref(false);
const newRunNumber = ref('');
const newRunDirection = ref<'up' | 'down'>('up');
const newRunServiceType = ref('local');

const selectedLine = computed<RailLine | null>(() =>
    selectedLineId.value ? rail.lines.get(selectedLineId.value) ?? null : null);
const selectedStations = computed<RailStation[]>(() =>
    selectedLineId.value ? rail.stationsByLine(selectedLineId.value) : []);
const selectedRuns = computed<RailRun[]>(() =>
    selectedLineId.value ? rail.runsByLine(selectedLineId.value) : []);

onMounted(() => {
    void loadAll();
});

async function loadAll() {
    loading.value = true;
    errorMsg.value = null;
    try {
        const { lines } = await ws.sendRailLineList();
        rail.setLines(lines);
        if (lines.length > 0 && !selectedLineId.value) {
            await selectLine(lines[0].id);
        }
    } catch (e) {
        errorMsg.value = (e as Error).message;
        net.pushLog('err', `rail.line.list rejected: ${(e as Error).message}`);
    } finally {
        loading.value = false;
    }
}

async function selectLine(id: string) {
    selectedLineId.value = id;
    // 0.4.5 P1：调 rail.line.detail 一次性拉 stations + runs + 各 run 的 timetable
    try {
        const detail = await ws.sendRailLineDetail(id);
        rail.setLine(detail.line);
        rail.setStations(detail.stations);
        rail.setRuns(detail.runs);
        // 填充 timetable cache（车次详情对话框开时直接命中，无需再拉）
        for (const [runId, rows] of Object.entries(detail.timetableByRun)) {
            rail.setTimetable(runId, rows.map((r) => ({
                runId,
                stationId: r.stationId,
                arrival: r.arrival ?? null,
                departure: r.departure ?? null,
                stopsHere: r.stopsHere,
            })));
        }
    } catch (e) {
        errorMsg.value = (e as Error).message;
        net.pushLog('err', `rail.line.detail rejected: ${(e as Error).message}`);
    }
}

async function createLine() {
    if (!lineName.value.trim()) return;
    try {
        const { line } = await ws.sendRailLineCreate(
            lineName.value.trim(),
            lineCode.value.trim() || null,
            lineColor.value || null,
        );
        rail.setLine(line);
        selectedLineId.value = line.id;
        lineName.value = '';
        lineCode.value = '';
    } catch (e) {
        errorMsg.value = (e as Error).message;
    }
}

function askDelete(type: 'line' | 'station' | 'run', id: string) {
    confirmingDelete.value = { type, id };
}

function cancelDelete() {
    confirmingDelete.value = null;
}

function isConfirmingDelete(type: 'line' | 'station' | 'run', id: string): boolean {
    const c = confirmingDelete.value;
    return c !== null && c.type === type && c.id === id;
}

async function confirmDeleteLine(id: string) {
    try {
        await ws.sendRailLineDelete(id);
        rail.removeLine(id);
        if (selectedLineId.value === id) selectedLineId.value = null;
    } catch (e) {
        errorMsg.value = (e as Error).message;
    } finally {
        confirmingDelete.value = null;
    }
}

async function addStation() {
    if (!selectedLineId.value || !stationDraftName.value.trim()) return;
    const sortOrder = selectedStations.value.length;
    try {
        const { station } = await ws.sendRailStationAdd(
            selectedLineId.value, stationDraftName.value.trim(), null, sortOrder, false,
        );
        rail.setStation(station);
        stationDraftName.value = '';
    } catch (e) {
        errorMsg.value = (e as Error).message;
    }
}

async function confirmDeleteStation(id: string) {
    try {
        await ws.sendRailStationDelete(id);
        rail.removeStation(id);
    } catch (e) {
        errorMsg.value = (e as Error).message;
    } finally {
        confirmingDelete.value = null;
    }
}

async function updateStationSort(s: RailStation, delta: number) {
    const newSort = Math.max(0, s.sortOrder + delta);
    try {
        const { station } = await ws.sendRailStationUpdate(s.id, { sortOrder: newSort });
        rail.setStation(station);
    } catch (e) {
        errorMsg.value = (e as Error).message;
    }
}

// 0.4.5 P4：HTML5 native drag-drop 拖动排序
const draggingStationId = ref<string | null>(null);
const dragOverStationId = ref<string | null>(null);

function onStationDragStart(e: DragEvent, sId: string) {
    draggingStationId.value = sId;
    if (e.dataTransfer) {
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', sId);
    }
}

function onStationDragOver(e: DragEvent, sId: string) {
    e.preventDefault();
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
    dragOverStationId.value = sId;
}

function onStationDragLeave() {
    dragOverStationId.value = null;
}

async function onStationDrop(e: DragEvent, targetId: string) {
    e.preventDefault();
    const draggedId = draggingStationId.value;
    draggingStationId.value = null;
    dragOverStationId.value = null;
    if (!draggedId || draggedId === targetId) return;

    const current = selectedStations.value.slice();
    const draggedIdx = current.findIndex((s) => s.id === draggedId);
    const targetIdx = current.findIndex((s) => s.id === targetId);
    if (draggedIdx < 0 || targetIdx < 0) return;
    const [dragged] = current.splice(draggedIdx, 1);
    current.splice(targetIdx, 0, dragged);

    // 批量更新 sortOrder = 0..N（仅在 order 实际变化的项发请求）
    for (let i = 0; i < current.length; i++) {
        const s = current[i];
        if (s.sortOrder === i) continue;
        try {
            const { station } = await ws.sendRailStationUpdate(s.id, { sortOrder: i });
            rail.setStation(station);
        } catch (ex) {
            errorMsg.value = (ex as Error).message;
            return;
        }
    }
}

function isDragOver(sId: string): boolean {
    return dragOverStationId.value === sId && draggingStationId.value !== null
        && draggingStationId.value !== sId;
}

function openNewRunDialog() {
    if (!selectedLineId.value) return;
    newRunNumber.value = '';
    newRunDirection.value = 'up';
    newRunServiceType.value = 'local';
    showNewRunDialog.value = true;
}

async function submitNewRun() {
    if (!selectedLineId.value || !newRunNumber.value.trim()) return;
    try {
        const { run } = await ws.sendRailRunCreate({
            lineId: selectedLineId.value,
            runNumber: newRunNumber.value.trim(),
            direction: newRunDirection.value,
            serviceType: newRunServiceType.value,
        });
        rail.setRun(run);
        showNewRunDialog.value = false;
        runDialogOpenForRunId.value = run.id;
    } catch (e) {
        errorMsg.value = (e as Error).message;
    }
}

async function confirmDeleteRun(id: string) {
    try {
        await ws.sendRailRunDelete(id);
        rail.removeRun(id);
    } catch (e) {
        errorMsg.value = (e as Error).message;
    } finally {
        confirmingDelete.value = null;
    }
}

function openRunDialog(id: string) {
    runDialogOpenForRunId.value = id;
}

function closeRunDialog() {
    runDialogOpenForRunId.value = null;
}
</script>

<template>
  <div class="fixed inset-0 z-50 bg-[color:var(--ctp-crust)]/50 flex items-center justify-center p-4"
       @click.self="emit('close')"
       @keydown.escape.prevent="emit('close')">
    <div class="bg-[color:var(--card)] text-[color:var(--foreground)] rounded-[var(--radius)] shadow-md w-full max-w-5xl max-h-[90vh] flex flex-col border border-[color:var(--border)]">
      <header class="flex items-center gap-2 px-4 h-11 border-b border-[color:var(--border)]">
        <Train class="size-4 text-[color:var(--ctp-mauve)]" />
        <h2 class="text-sm font-semibold">{{ t.rail.modalTitle }}</h2>
        <button class="ml-auto p-1 rounded hover:bg-[color:var(--accent)]" @click="emit('close')">
          <X class="size-4" />
        </button>
      </header>

      <div v-if="errorMsg"
           class="px-3 py-2 text-xs bg-[color:var(--destructive)]/10 text-[color:var(--destructive)] border-b border-[color:var(--border)]">
        {{ errorMsg }}
      </div>

      <div class="flex-1 overflow-hidden flex">
        <!-- 左：线路列表 + 创建 -->
        <aside class="w-72 border-r border-[color:var(--border)] flex flex-col overflow-hidden">
          <div class="p-2 border-b border-[color:var(--border)] space-y-1.5">
            <input type="text"
                   class="hc-input"
                   v-model="lineName"
                   :placeholder="t.rail.linePlaceholder"
                   @keydown.enter.prevent="createLine" />
            <div class="flex gap-1.5">
              <input type="text"
                     class="hc-input flex-1"
                     v-model="lineCode"
                     :placeholder="t.rail.lineCodePlaceholder" maxlength="8" />
              <input type="color"
                     class="hc-input w-12 p-0.5"
                     v-model="lineColor" />
            </div>
            <button class="w-full hc-btn flex items-center justify-center gap-1 px-2 py-1 text-xs rounded-[var(--radius-sm)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)] hover:opacity-90 disabled:opacity-40"
                    :disabled="!lineName.trim()"
                    @click="createLine">
              <Plus class="size-3" />
              <span>{{ t.rail.newLine }}</span>
            </button>
          </div>
          <div class="flex-1 overflow-y-auto p-1 text-xs">
            <template v-for="line in rail.allLines" :key="line.id">
              <div v-if="isConfirmingDelete('line', line.id)"
                   class="flex items-center gap-1 px-2 py-1.5 rounded bg-[color:var(--destructive)]/10 border border-[color:var(--destructive)]/30">
                <span class="flex-1 text-[10px] text-[color:var(--destructive)] truncate">
                  {{ t.rail.deleteLineConfirm }}
                </span>
                <button class="hc-btn px-1.5 py-0.5 rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] text-[10px]"
                        @click.stop="cancelDelete">{{ t.rail.cancel }}</button>
                <button class="hc-btn px-1.5 py-0.5 rounded bg-[color:var(--destructive)] text-[color:var(--destructive-foreground)] hover:opacity-90 text-[10px]"
                        @click.stop="confirmDeleteLine(line.id)">{{ t.rail.deleteConfirmYes }}</button>
              </div>
              <div v-else
                   class="flex items-center gap-1 px-2 py-1.5 rounded cursor-pointer"
                   :class="selectedLineId === line.id
                       ? 'bg-[color:var(--accent)]/40'
                       : 'hover:bg-[color:var(--accent)]/20'"
                   @click="selectLine(line.id)">
                <span class="inline-block size-3 rounded shrink-0"
                      :style="{ backgroundColor: line.color ?? '#9CA3AF' }"></span>
                <span class="flex-1 truncate">{{ line.name }}</span>
                <span v-if="line.code"
                      class="px-1 py-0.5 rounded bg-[color:var(--muted)] text-[10px] text-[color:var(--muted-foreground)] font-mono">
                  {{ line.code }}
                </span>
                <button class="ml-1 p-0.5 rounded text-[color:var(--destructive)] hover:bg-[color:var(--destructive)]/10"
                        @click.stop="askDelete('line', line.id)">
                  <Trash2 class="size-3" />
                </button>
              </div>
            </template>
            <div v-if="rail.allLines.length === 0"
                 class="p-3 text-center text-[color:var(--muted-foreground)] space-y-2">
              <div>{{ t.rail.emptyLines }}</div>
              <!-- 0.4.5 P7：首次打开空状态加示例引导 -->
              <div class="text-[10px] bg-[color:var(--ctp-blue)]/10 text-[color:var(--ctp-blue)] p-2 rounded text-left">
                <div class="font-medium mb-1">💡 {{ t.rail.tipFirstLine }}</div>
                <div class="opacity-80">{{ t.rail.tipExample }}</div>
              </div>
            </div>
          </div>
        </aside>

        <!-- 右：选中线路详情（站点 + 车次） -->
        <section class="flex-1 overflow-y-auto p-3 text-xs space-y-4">
          <div v-if="!selectedLine"
               class="p-6 text-center text-[color:var(--muted-foreground)] space-y-3">
            <div>{{ t.rail.pickLine }}</div>
            <!-- 0.4.5 P7：未选线路时显示典型工作流引导 -->
            <ol class="text-[11px] text-left max-w-md mx-auto space-y-1 bg-[color:var(--muted)] p-3 rounded">
              <li class="opacity-80">1️⃣ {{ t.rail.tipStep1 }}</li>
              <li class="opacity-80">2️⃣ {{ t.rail.tipStep2 }}</li>
              <li class="opacity-80">3️⃣ {{ t.rail.tipStep3 }}</li>
              <li class="opacity-80">4️⃣ {{ t.rail.tipStep4 }}</li>
            </ol>
          </div>
          <template v-else>
            <!-- 站点 -->
            <section>
              <div class="flex items-center gap-1.5 mb-2">
                <Building2 class="size-3.5 text-[color:var(--ctp-blue)]" />
                <span class="font-medium">{{ t.rail.stations }}</span>
                <span class="text-[color:var(--muted-foreground)]">{{ selectedStations.length }}</span>
              </div>
              <div class="flex gap-1.5 mb-2">
                <input type="text"
                       class="hc-input flex-1"
                       v-model="stationDraftName"
                       :placeholder="t.rail.stationPlaceholder"
                       @keydown.enter.prevent="addStation" />
                <button class="hc-btn flex items-center gap-1 px-2 py-1 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] disabled:opacity-40"
                        :disabled="!stationDraftName.trim()"
                        @click="addStation">
                  <Plus class="size-3" />
                  <span>{{ t.rail.addStation }}</span>
                </button>
              </div>
              <ul class="space-y-1">
                <template v-for="s in selectedStations" :key="s.id">
                  <li v-if="isConfirmingDelete('station', s.id)"
                      class="flex items-center gap-2 px-2 py-1.5 rounded bg-[color:var(--destructive)]/10 border border-[color:var(--destructive)]/30">
                    <span class="flex-1 text-[color:var(--destructive)] truncate">
                      {{ t.rail.deleteStationConfirm }}
                    </span>
                    <button class="hc-btn px-2 py-0.5 rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] text-[10px]"
                            @click="cancelDelete">{{ t.rail.cancel }}</button>
                    <button class="hc-btn px-2 py-0.5 rounded bg-[color:var(--destructive)] text-[color:var(--destructive-foreground)] hover:opacity-90 text-[10px]"
                            @click="confirmDeleteStation(s.id)">{{ t.rail.deleteConfirmYes }}</button>
                  </li>
                  <li v-else
                      :draggable="true"
                      class="flex items-center gap-2 px-2 py-1.5 rounded border bg-[color:var(--background)] transition-colors"
                      :class="isDragOver(s.id)
                          ? 'border-[color:var(--ctp-mauve)] bg-[color:var(--ctp-mauve)]/10'
                          : 'border-[color:var(--border)]'"
                      @dragstart="(e) => onStationDragStart(e, s.id)"
                      @dragover="(e) => onStationDragOver(e, s.id)"
                      @dragleave="onStationDragLeave"
                      @drop="(e) => onStationDrop(e, s.id)">
                    <GripVertical class="size-3 shrink-0 text-[color:var(--muted-foreground)] cursor-grab" />
                    <MapPin class="size-3 shrink-0 text-[color:var(--ctp-blue)]" />
                    <span class="font-mono text-[color:var(--muted-foreground)]">{{ s.sortOrder }}</span>
                    <span class="flex-1 truncate">{{ s.name }}</span>
                    <span v-if="s.isTerminus"
                          class="px-1.5 py-0.5 rounded bg-[color:var(--ctp-peach)]/15 text-[color:var(--ctp-peach)] text-[10px]">
                      {{ t.rail.terminus }}
                    </span>
                    <button class="hc-btn p-0.5 rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                            :title="t.rail.sortUp"
                            @click="updateStationSort(s, -1)">↑</button>
                    <button class="hc-btn p-0.5 rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                            :title="t.rail.sortDown"
                            @click="updateStationSort(s, 1)">↓</button>
                    <button class="p-0.5 rounded text-[color:var(--destructive)] hover:bg-[color:var(--destructive)]/10"
                            @click="askDelete('station', s.id)">
                      <Trash2 class="size-3" />
                    </button>
                  </li>
                </template>
              </ul>
            </section>

            <!-- 车次 -->
            <section>
              <div class="flex items-center gap-1.5 mb-2">
                <Train class="size-3.5 text-[color:var(--ctp-mauve)]" />
                <span class="font-medium">{{ t.rail.runs }}</span>
                <span class="text-[color:var(--muted-foreground)]">{{ selectedRuns.length }}</span>
                <button class="ml-auto hc-btn flex items-center gap-1 px-2 py-1 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                        @click="openNewRunDialog">
                  <Plus class="size-3" />
                  <span>{{ t.rail.newRun }}</span>
                </button>
              </div>
              <ul class="space-y-1">
                <template v-for="r in selectedRuns" :key="r.id">
                  <li v-if="isConfirmingDelete('run', r.id)"
                      class="flex items-center gap-2 px-2 py-1.5 rounded bg-[color:var(--destructive)]/10 border border-[color:var(--destructive)]/30">
                    <span class="flex-1 text-[color:var(--destructive)] truncate">
                      {{ t.rail.deleteRunConfirm }}
                    </span>
                    <button class="hc-btn px-2 py-0.5 rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] text-[10px]"
                            @click="cancelDelete">{{ t.rail.cancel }}</button>
                    <button class="hc-btn px-2 py-0.5 rounded bg-[color:var(--destructive)] text-[color:var(--destructive-foreground)] hover:opacity-90 text-[10px]"
                            @click="confirmDeleteRun(r.id)">{{ t.rail.deleteConfirmYes }}</button>
                  </li>
                  <li v-else
                      class="flex items-center gap-2 px-2 py-1.5 rounded border border-[color:var(--border)] bg-[color:var(--background)]">
                    <span class="font-mono">{{ r.runNumber }}</span>
                    <span class="px-1.5 py-0.5 rounded bg-[color:var(--ctp-blue)]/15 text-[color:var(--ctp-blue)] text-[10px]">
                      {{ r.direction === 'up' ? t.rail.directionUp : t.rail.directionDown }}
                    </span>
                    <span class="px-1.5 py-0.5 rounded bg-[color:var(--ctp-mauve)]/15 text-[color:var(--ctp-mauve)] text-[10px]">
                      {{ r.serviceType }}
                    </span>
                    <span v-if="r.cars != null"
                          class="text-[color:var(--muted-foreground)]">{{ r.cars }} {{ t.rail.cars }}</span>
                    <span v-if="r.notes"
                          class="flex-1 truncate text-[color:var(--muted-foreground)] italic">{{ r.notes }}</span>
                    <span v-else class="flex-1"></span>
                    <button class="hc-btn flex items-center gap-1 px-1.5 py-0.5 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                            @click="openRunDialog(r.id)">
                      <Pencil class="size-3" />
                      <span>{{ t.rail.editRun }}</span>
                    </button>
                    <button class="p-0.5 rounded text-[color:var(--destructive)] hover:bg-[color:var(--destructive)]/10"
                            @click="askDelete('run', r.id)">
                      <Trash2 class="size-3" />
                    </button>
                  </li>
                </template>
              </ul>
            </section>
          </template>
        </section>
      </div>

      <RailRunDialog
        v-if="runDialogOpenForRunId"
        :run-id="runDialogOpenForRunId"
        :stations="selectedStations"
        @close="closeRunDialog" />

      <!-- 0.4.5 P2：新车次创建对话框（替换原生 prompt） -->
      <div v-if="showNewRunDialog"
           class="absolute inset-0 z-[55] bg-[color:var(--ctp-crust)]/50 flex items-center justify-center p-4"
           @click.self="showNewRunDialog = false"
           @keydown.escape.prevent="showNewRunDialog = false">
        <div class="bg-[color:var(--card)] rounded-[var(--radius)] shadow-md w-full max-w-md p-4 border border-[color:var(--border)] space-y-3">
          <header class="flex items-center gap-2">
            <Train class="size-4 text-[color:var(--ctp-mauve)]" />
            <h3 class="font-semibold text-sm">{{ t.rail.newRunDialogTitle }}</h3>
          </header>
          <label class="block text-xs">
            <span class="text-[color:var(--muted-foreground)]">{{ t.rail.runNumber }}</span>
            <input type="text"
                   class="hc-input mt-0.5 font-mono"
                   v-model="newRunNumber"
                   :placeholder="t.rail.runNumberPlaceholder"
                   maxlength="64"
                   ref="newRunNumberInput"
                   @keydown.enter.prevent="submitNewRun" />
          </label>
          <div class="grid grid-cols-2 gap-2 text-xs">
            <label class="block">
              <span class="text-[color:var(--muted-foreground)]">{{ t.rail.direction }}</span>
              <select class="hc-input mt-0.5" v-model="newRunDirection">
                <option value="up">{{ t.rail.directionUp }}</option>
                <option value="down">{{ t.rail.directionDown }}</option>
              </select>
            </label>
            <label class="block">
              <span class="text-[color:var(--muted-foreground)]">{{ t.rail.serviceType }}</span>
              <select class="hc-input mt-0.5" v-model="newRunServiceType">
                <option value="local">{{ t.rail.serviceTypeLocal }}</option>
                <option value="express">{{ t.rail.serviceTypeExpress }}</option>
                <option value="section">{{ t.rail.serviceTypeSection }}</option>
                <option value="limited">{{ t.rail.serviceTypeLimited }}</option>
              </select>
            </label>
          </div>
          <div class="flex justify-end gap-2 pt-2">
            <button class="hc-btn px-3 py-1 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                    @click="showNewRunDialog = false">
              {{ t.rail.cancel }}
            </button>
            <button class="hc-btn px-3 py-1 text-xs rounded bg-[color:var(--primary)] text-[color:var(--primary-foreground)] hover:opacity-90 disabled:opacity-40"
                    :disabled="!newRunNumber.trim()"
                    @click="submitNewRun">
              {{ t.rail.create }}
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
