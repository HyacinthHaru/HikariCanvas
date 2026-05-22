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
    X, Plus, Trash2, Pencil, Train, Building2, MapPin, Check, X as XIcon,
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
    // 拉该线路的 stations + runs；服务端目前无单 line 拉接口，先用 list 全部然后过滤
    // P5 / 后续可以加 rail.line.detail op 拉单线全数据；当前用 list 总取代
    // 简化：直接 set 空，下次 modal 再开拉一次
    // TODO future：加 rail.line.detail
    // 暂用 ws stations.add 时回填到 store，line.list 不返 stations
    // 这里先空，让 user 手动添加 stations / runs
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

async function deleteLine(id: string) {
    if (!confirm(t.value.rail.deleteLineConfirm)) return;
    try {
        await ws.sendRailLineDelete(id);
        rail.removeLine(id);
        if (selectedLineId.value === id) selectedLineId.value = null;
    } catch (e) {
        errorMsg.value = (e as Error).message;
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

async function deleteStation(id: string) {
    if (!confirm(t.value.rail.deleteStationConfirm)) return;
    try {
        await ws.sendRailStationDelete(id);
        rail.removeStation(id);
    } catch (e) {
        errorMsg.value = (e as Error).message;
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

async function createRun() {
    if (!selectedLineId.value) return;
    const runNumber = prompt(t.value.rail.runNumberPrompt) || '';
    if (!runNumber.trim()) return;
    try {
        const { run } = await ws.sendRailRunCreate({
            lineId: selectedLineId.value,
            runNumber: runNumber.trim(),
            direction: 'up',
            serviceType: 'local',
        });
        rail.setRun(run);
        runDialogOpenForRunId.value = run.id;
    } catch (e) {
        errorMsg.value = (e as Error).message;
    }
}

async function deleteRun(id: string) {
    if (!confirm(t.value.rail.deleteRunConfirm)) return;
    try {
        await ws.sendRailRunDelete(id);
        rail.removeRun(id);
    } catch (e) {
        errorMsg.value = (e as Error).message;
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
            <div v-for="line in rail.allLines"
                 :key="line.id"
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
                      @click.stop="deleteLine(line.id)">
                <Trash2 class="size-3" />
              </button>
            </div>
            <div v-if="rail.allLines.length === 0"
                 class="p-3 text-center text-[color:var(--muted-foreground)]">
              {{ t.rail.emptyLines }}
            </div>
          </div>
        </aside>

        <!-- 右：选中线路详情（站点 + 车次） -->
        <section class="flex-1 overflow-y-auto p-3 text-xs space-y-4">
          <div v-if="!selectedLine"
               class="p-6 text-center text-[color:var(--muted-foreground)]">
            {{ t.rail.pickLine }}
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
                <li v-for="s in selectedStations"
                    :key="s.id"
                    class="flex items-center gap-2 px-2 py-1.5 rounded border border-[color:var(--border)] bg-[color:var(--background)]">
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
                          @click="deleteStation(s.id)">
                    <Trash2 class="size-3" />
                  </button>
                </li>
              </ul>
            </section>

            <!-- 车次 -->
            <section>
              <div class="flex items-center gap-1.5 mb-2">
                <Train class="size-3.5 text-[color:var(--ctp-mauve)]" />
                <span class="font-medium">{{ t.rail.runs }}</span>
                <span class="text-[color:var(--muted-foreground)]">{{ selectedRuns.length }}</span>
                <button class="ml-auto hc-btn flex items-center gap-1 px-2 py-1 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                        @click="createRun">
                  <Plus class="size-3" />
                  <span>{{ t.rail.newRun }}</span>
                </button>
              </div>
              <ul class="space-y-1">
                <li v-for="r in selectedRuns"
                    :key="r.id"
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
                          @click="deleteRun(r.id)">
                    <Trash2 class="size-3" />
                  </button>
                </li>
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
