<script setup lang="ts">
/**
 * 0.4.0-P3-L：列车 / 公交时刻表管理 modal。
 *
 * <p>挂在 App.vue 末尾（同 VariablePanel / TemplateGallery），由 {@code ui.scheduleManagerOpen}
 * 控制显示；TopBar Calendar 按钮触发开启。打开时自动调 {@code wsClient.sendScheduleList}
 * 加载当前 wall 的 schedule（loading state）。后端 op 走 ack payload，本地 mirror 由
 * ScheduleStore 维护。</p>
 *
 * <p>UI：站名输入 + 精度切换（0.4.0 bugfix Bug 4）+ entries 列表（inline 删除）+ 添加按钮
 * + 当前状态预览（schedule.* 变量）。子 modal {@link ScheduleEntryDialog} 用于添加 / 编辑
 * 单条 entry，按 wall.precision 决定时间输入是 HH:mm 还是 HH:mm:ss。</p>
 */
import { computed, ref, watch } from 'vue';
import { X, Train, Plus, Trash2, Pencil, Clock, TrainTrack, ChevronDown, ChevronRight } from 'lucide-vue-next';
import { useUiStore } from '@/stores/ui';
import { useProjectStore } from '@/stores/project';
import { useScheduleStore } from '@/stores/schedule';
import { useVariableStore } from '@/stores/variables';
import { useRailStore } from '@/stores/rail';
import { useNetworkStore } from '@/stores/network';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';
import ScheduleEntryDialog from './ScheduleEntryDialog.vue';
import type { ScheduleEntry, SchedulePrecision } from '@/types/schedule';

const ui = useUiStore();
const project = useProjectStore();
const schedule = useScheduleStore();
const variables = useVariableStore();
const rail = useRailStore();
const net = useNetworkStore();
const ws = getWsClient();
const { t } = useI18n();

// 0.4.5 P3：铁路绑定段状态
const railSectionExpanded = ref(false);
const railLinesLoaded = ref(false);
const railEnabled = computed<boolean>(() => !!rail.binding?.lineId);
const railBindSubmitting = ref(false);
const railLineDraft = ref<string>('');
const railStationDraft = ref<string>('');
const railDirectionDraft = ref<'up' | 'down' | 'both'>('both');

const open = computed(() => ui.scheduleManagerOpen);

const stationDraft = ref('');
const stationSubmitting = ref(false);
const precisionSubmitting = ref(false);
const submittingEntryId = ref<number | null>(null);
const entryDialogOpen = ref(false);
const editingEntry = ref<ScheduleEntry | null>(null);
const entryDialogError = ref<string | null>(null);
const entrySubmitting = ref(false);

watch(open, async (v) => {
    if (!v) return;
    if (!project.wallId) return;
    schedule.setLoading(true);
    try {
        const ack = await ws.sendScheduleList();
        schedule.setLoaded(ack.schedule);
        stationDraft.value = ack.schedule?.stationName ?? '';
    } catch (e) {
        schedule.setError((e as Error).message);
        schedule.setLoading(false);
    }
    // 0.4.5 P3：同步 rail binding 草稿到 store（ready payload 已带 binding）
    railLineDraft.value = rail.binding?.lineId ?? '';
    railStationDraft.value = rail.binding?.stationId ?? '';
    railDirectionDraft.value = (rail.binding?.direction as 'up' | 'down' | 'both' | null) ?? 'both';
});

const railStationsForDraftLine = computed(() =>
    railLineDraft.value ? rail.stationsByLine(railLineDraft.value) : []);

async function expandRailSection() {
    railSectionExpanded.value = !railSectionExpanded.value;
    if (railSectionExpanded.value && !railLinesLoaded.value) {
        try {
            const { lines } = await ws.sendRailLineList();
            rail.setLines(lines);
            railLinesLoaded.value = true;
            // 若已绑定，自动拉线路详情（站点列表）
            if (rail.binding?.lineId) {
                const detail = await ws.sendRailLineDetail(rail.binding.lineId);
                rail.setStations(detail.stations);
            }
        } catch (e) {
            net.pushLog('err', `rail.line.list rejected: ${(e as Error).message}`);
        }
    }
}

async function onRailLineChange() {
    // 切换线路时拉该线路的站点列表
    const lineId = railLineDraft.value;
    railStationDraft.value = '';  // 重置站点选择
    if (!lineId) return;
    try {
        const detail = await ws.sendRailLineDetail(lineId);
        rail.setStations(detail.stations);
    } catch (e) {
        net.pushLog('err', `rail.line.detail rejected: ${(e as Error).message}`);
    }
}

async function applyRailBinding() {
    if (railBindSubmitting.value) return;
    if (!railLineDraft.value || !railStationDraft.value) return;
    railBindSubmitting.value = true;
    try {
        const { binding } = await ws.sendRailWallBind({
            lineId: railLineDraft.value,
            stationId: railStationDraft.value,
            direction: railDirectionDraft.value,
        });
        rail.setBinding(binding);
    } catch (e) {
        net.pushLog('err', `rail.wall.bind rejected: ${(e as Error).message}`);
    } finally {
        railBindSubmitting.value = false;
    }
}

async function clearRailBinding() {
    if (railBindSubmitting.value) return;
    railBindSubmitting.value = true;
    try {
        const { binding } = await ws.sendRailWallBind({
            lineId: null,
            stationId: null,
            direction: 'both',
        });
        rail.setBinding(binding);
        railLineDraft.value = '';
        railStationDraft.value = '';
    } catch (e) {
        net.pushLog('err', `rail.wall.bind clear rejected: ${(e as Error).message}`);
    } finally {
        railBindSubmitting.value = false;
    }
}

async function saveStationName(): Promise<void> {
    if (stationSubmitting.value) return;
    const trimmed = stationDraft.value.trim();
    const next = trimmed.length === 0 ? null : trimmed;
    if ((schedule.current?.stationName ?? null) === next) return;
    stationSubmitting.value = true;
    try {
        const ack = await ws.sendScheduleUpsert(next, schedule.precision);
        schedule.setStationName(ack.stationName);
        if (ack.precision) schedule.setPrecision(ack.precision);
    } catch (e) {
        schedule.setError((e as Error).message);
    } finally {
        stationSubmitting.value = false;
    }
}

/** 0.4.0 bugfix（Bug 4）：切换精度。stationName 保留当前值。 */
async function switchPrecision(precision: SchedulePrecision): Promise<void> {
    if (precisionSubmitting.value) return;
    if (schedule.precision === precision) return;
    precisionSubmitting.value = true;
    try {
        const ack = await ws.sendScheduleUpsert(
            schedule.current?.stationName ?? null, precision);
        if (ack.precision) schedule.setPrecision(ack.precision);
        else schedule.setPrecision(precision);
    } catch (e) {
        schedule.setError((e as Error).message);
    } finally {
        precisionSubmitting.value = false;
    }
}

function openAddEntry(): void {
    editingEntry.value = null;
    entryDialogError.value = null;
    entryDialogOpen.value = true;
}

function openEditEntry(entry: ScheduleEntry): void {
    editingEntry.value = entry;
    entryDialogError.value = null;
    entryDialogOpen.value = true;
}

async function onEntryDialogSubmit(payload: {
    id: number | null;
    departureTime: string;
    destination: string | null;
    sortOrder: number;
}): Promise<void> {
    if (entrySubmitting.value) return;
    entrySubmitting.value = true;
    entryDialogError.value = null;
    try {
        let ack;
        if (payload.id == null) {
            ack = await ws.sendScheduleEntryAdd(
                payload.departureTime, payload.destination, payload.sortOrder);
        } else {
            ack = await ws.sendScheduleEntryUpdate(
                payload.id, payload.departureTime, payload.destination, payload.sortOrder);
        }
        schedule.upsertEntry({
            id: ack.id,
            departureTime: ack.departureTime,
            destination: ack.destination,
            sortOrder: ack.sortOrder,
        });
        entryDialogOpen.value = false;
    } catch (e) {
        entryDialogError.value = (e as Error).message;
    } finally {
        entrySubmitting.value = false;
    }
}

async function deleteEntry(entry: ScheduleEntry): Promise<void> {
    if (submittingEntryId.value !== null) return;
    submittingEntryId.value = entry.id;
    try {
        await ws.sendScheduleEntryDelete(entry.id);
        schedule.removeEntry(entry.id);
    } catch (e) {
        schedule.setError((e as Error).message);
    } finally {
        submittingEntryId.value = null;
    }
}

function close(): void {
    if (stationSubmitting.value || precisionSubmitting.value
            || entrySubmitting.value || submittingEntryId.value !== null) {
        return;
    }
    ui.closeScheduleManager();
}

// schedule:* 变量 live preview。M28-enhance：next2_* 第二班次 + eta_mmss MM:SS 格式
const previewVars = computed(() => {
    const wid = project.wallId;
    if (!wid) return null;
    const get = (k: string) => variables.get(`schedule:${wid}/${k}`);
    return {
        nextDeparture: get('next_departure')?.currentValue ?? '',
        nextDestination: get('next_destination')?.currentValue ?? '',
        etaMinutes: get('eta_minutes')?.currentValue ?? '',
        etaSeconds: get('eta_seconds')?.currentValue ?? '',
        etaMmss: get('eta_mmss')?.currentValue ?? '',
        isArriving: get('is_arriving')?.currentValue ?? 'false',
        arrivalStatus: get('arrival_status')?.currentValue ?? '',
        precision: get('precision')?.currentValue ?? schedule.precision,
        // M28-enhance：第二班次
        next2Departure: get('next2_departure')?.currentValue ?? '',
        next2Destination: get('next2_destination')?.currentValue ?? '',
        next2EtaMinutes: get('next2_eta_minutes')?.currentValue ?? '',
        next2EtaSeconds: get('next2_eta_seconds')?.currentValue ?? '',
        next2EtaMmss: get('next2_eta_mmss')?.currentValue ?? '',
        next2IsArriving: get('next2_is_arriving')?.currentValue ?? 'false',
        next2ArrivalStatus: get('next2_arrival_status')?.currentValue ?? '',
    };
});
</script>

<template>
  <div v-if="open"
       class="fixed inset-0 z-50 bg-[color:var(--ctp-crust)]/50 flex items-center justify-center p-4"
       @click.self="close">
    <div class="bg-[color:var(--card)] text-[color:var(--foreground)] rounded-[var(--radius)] shadow-md max-w-2xl w-full max-h-[90vh] flex flex-col border border-[color:var(--border)]">
      <header class="flex items-center gap-2 px-4 h-11 border-b border-[color:var(--border)]">
        <Train class="size-4 text-[color:var(--ctp-blue)]" />
        <h2 class="text-sm font-semibold">{{ t.schedule.modalTitle }}</h2>
        <button class="ml-auto p-1 rounded hover:bg-[color:var(--accent)]"
                @click="close" :disabled="entrySubmitting || stationSubmitting || precisionSubmitting">
          <X class="size-4" />
        </button>
      </header>

      <div class="flex-1 overflow-y-auto p-4 space-y-4 text-xs">
        <!-- 站名 -->
        <label class="block">
          <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.schedule.stationName }}</span>
          <div class="flex items-center gap-2 mt-0.5">
            <input type="text"
                   class="hc-input flex-1"
                   v-model="stationDraft"
                   :placeholder="t.schedule.stationPlaceholder"
                   maxlength="64"
                   @blur="saveStationName"
                   @keydown.enter.prevent="saveStationName" />
            <span v-if="stationSubmitting" class="text-xs text-[color:var(--muted-foreground)]">…</span>
          </div>
        </label>

        <!-- 0.4.5 P3：铁路网络绑定段（可折叠） -->
        <section class="border border-[color:var(--border)] rounded-[var(--radius-sm)]">
          <button class="w-full flex items-center gap-1.5 px-3 py-2 hover:bg-[color:var(--accent)]/30 rounded-[var(--radius-sm)] text-left"
                  @click="expandRailSection">
            <ChevronDown v-if="railSectionExpanded" class="size-3.5 text-[color:var(--muted-foreground)]" />
            <ChevronRight v-else class="size-3.5 text-[color:var(--muted-foreground)]" />
            <TrainTrack class="size-3.5 text-[color:var(--ctp-mauve)]" />
            <span class="font-medium">{{ t.schedule.railBindingTitle }}</span>
            <span v-if="railEnabled"
                  class="ml-auto px-2 py-0.5 rounded bg-[color:var(--ctp-mauve)]/15 text-[color:var(--ctp-mauve)] text-[10px]">
              {{ t.schedule.railBindingEnabled }}
            </span>
            <span v-else
                  class="ml-auto px-2 py-0.5 rounded bg-[color:var(--muted)] text-[color:var(--muted-foreground)] text-[10px]">
              {{ t.schedule.railBindingDisabled }}
            </span>
          </button>
          <div v-if="railSectionExpanded" class="p-3 pt-1 space-y-2 border-t border-[color:var(--border)]">
            <p class="text-[10px] text-[color:var(--muted-foreground)]">
              {{ t.schedule.railBindingHint }}
            </p>
            <div class="grid grid-cols-3 gap-2">
              <label class="block">
                <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.schedule.railBindingLine }}</span>
                <select class="hc-input mt-0.5"
                        v-model="railLineDraft"
                        @change="onRailLineChange"
                        :disabled="railBindSubmitting">
                  <option value="">{{ t.schedule.railBindingNoLine }}</option>
                  <option v-for="line in rail.allLines" :key="line.id" :value="line.id">
                    {{ line.name }}{{ line.code ? ' (' + line.code + ')' : '' }}
                  </option>
                </select>
              </label>
              <label class="block">
                <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.schedule.railBindingStation }}</span>
                <select class="hc-input mt-0.5"
                        v-model="railStationDraft"
                        :disabled="!railLineDraft || railBindSubmitting">
                  <option value="">{{ t.schedule.railBindingPickStation }}</option>
                  <option v-for="s in railStationsForDraftLine" :key="s.id" :value="s.id">
                    {{ s.name }}
                  </option>
                </select>
              </label>
              <label class="block">
                <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.schedule.railBindingDirection }}</span>
                <select class="hc-input mt-0.5"
                        v-model="railDirectionDraft"
                        :disabled="railBindSubmitting">
                  <option value="both">{{ t.schedule.railBindingDirBoth }}</option>
                  <option value="up">{{ t.rail.directionUp }}</option>
                  <option value="down">{{ t.rail.directionDown }}</option>
                </select>
              </label>
            </div>
            <div class="flex gap-2">
              <button class="hc-btn px-3 py-1 text-xs rounded-[var(--radius-sm)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)] hover:opacity-90 disabled:opacity-40"
                      :disabled="!railLineDraft || !railStationDraft || railBindSubmitting"
                      @click="applyRailBinding">
                {{ railEnabled ? t.schedule.railBindingUpdate : t.schedule.railBindingApply }}
              </button>
              <button v-if="railEnabled"
                      class="hc-btn px-3 py-1 text-xs rounded-[var(--radius-sm)] border border-[color:var(--border)] hover:bg-[color:var(--accent)] disabled:opacity-40"
                      :disabled="railBindSubmitting"
                      @click="clearRailBinding">
                {{ t.schedule.railBindingClear }}
              </button>
              <span v-if="railBindSubmitting" class="text-xs text-[color:var(--muted-foreground)] self-center">…</span>
            </div>
            <p v-if="railEnabled"
               class="text-[10px] text-[color:var(--ctp-mauve)] bg-[color:var(--ctp-mauve)]/10 px-2 py-1 rounded">
              {{ t.schedule.railBindingActiveHint }}
            </p>
          </div>
        </section>

        <!-- 0.4.0 bugfix（Bug 4）：精度切换 -->
        <div class="block">
          <div class="flex items-center gap-2 mb-1">
            <Clock class="size-3.5 text-[color:var(--ctp-blue)]" />
            <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.schedule.precisionLabel }}</span>
            <span v-if="precisionSubmitting" class="text-xs text-[color:var(--muted-foreground)]">…</span>
          </div>
          <div class="inline-flex border border-[color:var(--border)] rounded-[var(--radius-sm)] overflow-hidden">
            <button type="button"
                    class="px-3 py-1 text-xs"
                    :class="schedule.precision === 'minute'
                        ? 'bg-[color:var(--primary)] text-[color:var(--primary-foreground)]'
                        : 'bg-[color:var(--background)] hover:bg-[color:var(--accent)]'"
                    :disabled="precisionSubmitting"
                    @click="switchPrecision('minute')">
              {{ t.schedule.precisionMinute }}
            </button>
            <button type="button"
                    class="px-3 py-1 text-xs border-l border-[color:var(--border)]"
                    :class="schedule.precision === 'second'
                        ? 'bg-[color:var(--primary)] text-[color:var(--primary-foreground)]'
                        : 'bg-[color:var(--background)] hover:bg-[color:var(--accent)]'"
                    :disabled="precisionSubmitting"
                    @click="switchPrecision('second')">
              {{ t.schedule.precisionSecond }}
            </button>
          </div>
          <p class="mt-1 text-[10px] text-[color:var(--muted-foreground)]">
            {{ t.schedule.precisionHint }}
          </p>
        </div>

        <!-- entries 列表 -->
        <section class="space-y-2" :class="railEnabled ? 'opacity-50 pointer-events-none' : ''">
          <div class="flex items-center gap-2">
            <span class="font-medium">{{ t.schedule.entriesHeader }}</span>
            <span class="text-xs text-[color:var(--muted-foreground)]">
              ({{ schedule.sortedEntries.length }})
            </span>
            <span v-if="railEnabled"
                  class="text-[10px] text-[color:var(--ctp-mauve)] italic">
              {{ t.schedule.entriesDisabledHint }}
            </span>
            <button class="ml-auto hc-btn px-2 py-1 text-xs rounded-[var(--radius-sm)]
                          bg-[color:var(--primary)] text-[color:var(--primary-foreground)]
                          hover:opacity-90 flex items-center gap-1 disabled:opacity-40"
                    :disabled="railEnabled"
                    @click="openAddEntry">
              <Plus class="size-3" />
              {{ t.schedule.addEntry }}
            </button>
          </div>

          <div v-if="schedule.loading"
               class="p-3 rounded bg-[color:var(--muted)] text-[color:var(--muted-foreground)]">
            ...
          </div>
          <div v-else-if="schedule.sortedEntries.length === 0"
               class="p-3 rounded bg-[color:var(--muted)] text-[color:var(--muted-foreground)]">
            {{ t.schedule.emptyEntries }}
          </div>

          <ul v-else class="space-y-1.5">
            <li v-for="entry in schedule.sortedEntries" :key="entry.id"
                class="flex items-center gap-2 px-2 py-1.5 border border-[color:var(--border)] rounded">
              <span class="font-mono font-semibold text-[color:var(--ctp-blue)]"
                    :class="schedule.precision === 'second' ? 'w-20' : 'w-12'">
                {{ entry.departureTime }}
              </span>
              <span class="flex-1 truncate"
                    :class="!entry.destination ? 'text-[color:var(--muted-foreground)] italic' : ''">
                {{ entry.destination || '—' }}
              </span>
              <span class="text-xs text-[color:var(--muted-foreground)] font-mono">
                #{{ entry.sortOrder }}
              </span>
              <button class="p-1 rounded hover:bg-[color:var(--accent)]"
                      @click="openEditEntry(entry)"
                      :disabled="submittingEntryId === entry.id"
                      :aria-label="t.schedule.entryEditAria">
                <Pencil class="size-3.5 text-[color:var(--muted-foreground)]" />
              </button>
              <button class="p-1 rounded hover:bg-[color:var(--destructive)]/20"
                      @click="deleteEntry(entry)"
                      :disabled="submittingEntryId === entry.id"
                      :aria-label="t.schedule.entryDeleteAria">
                <Trash2 class="size-3.5 text-[color:var(--destructive)]" />
              </button>
            </li>
          </ul>
        </section>

        <!-- 状态预览 -->
        <section v-if="previewVars" class="space-y-1.5 border-t border-[color:var(--border)] pt-3">
          <div class="flex items-center gap-2">
            <span class="font-medium">{{ t.schedule.previewHeader }}</span>
          </div>
          <dl class="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 font-mono text-xs">
            <dt class="text-[color:var(--muted-foreground)]">{{ t.schedule.previewNextDeparture }}</dt>
            <dd>{{ previewVars.nextDeparture || '—' }}</dd>
            <dt class="text-[color:var(--muted-foreground)]">{{ t.schedule.previewNextDestination }}</dt>
            <dd>{{ previewVars.nextDestination || '—' }}</dd>
            <dt class="text-[color:var(--muted-foreground)]">{{ t.schedule.previewEtaSeconds }}</dt>
            <dd>{{ previewVars.etaSeconds || '—' }}</dd>
            <dt class="text-[color:var(--muted-foreground)]">{{ t.schedule.previewEtaMinutes }}</dt>
            <dd>{{ previewVars.etaMinutes || '—' }}</dd>
            <dt class="text-[color:var(--muted-foreground)]">{{ t.schedule.previewEtaMmss }}</dt>
            <dd>{{ previewVars.etaMmss || '—' }}</dd>
            <dt class="text-[color:var(--muted-foreground)]">{{ t.schedule.previewIsArriving }}</dt>
            <dd>{{ previewVars.isArriving }}</dd>
            <dt class="text-[color:var(--muted-foreground)]">{{ t.schedule.previewArrivalStatus }}</dt>
            <dd>{{ previewVars.arrivalStatus || '—' }}</dd>
            <dt class="text-[color:var(--muted-foreground)]">{{ t.schedule.previewPrecision }}</dt>
            <dd>{{ previewVars.precision }}</dd>
          </dl>

          <!-- M28-enhance：第二班次 -->
          <div class="mt-2 pt-2 border-t border-[color:var(--border)]">
            <div class="font-medium mb-1 text-[color:var(--ctp-mauve)]">
              {{ t.schedule.previewNext2Header }}
            </div>
            <dl class="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 font-mono text-xs">
              <dt class="text-[color:var(--muted-foreground)]">{{ t.schedule.previewNextDeparture }}</dt>
              <dd>{{ previewVars.next2Departure || '—' }}</dd>
              <dt class="text-[color:var(--muted-foreground)]">{{ t.schedule.previewNextDestination }}</dt>
              <dd>{{ previewVars.next2Destination || '—' }}</dd>
              <dt class="text-[color:var(--muted-foreground)]">{{ t.schedule.previewEtaSeconds }}</dt>
              <dd>{{ previewVars.next2EtaSeconds || '—' }}</dd>
              <dt class="text-[color:var(--muted-foreground)]">{{ t.schedule.previewEtaMmss }}</dt>
              <dd>{{ previewVars.next2EtaMmss || '—' }}</dd>
              <dt class="text-[color:var(--muted-foreground)]">{{ t.schedule.previewIsArriving }}</dt>
              <dd>{{ previewVars.next2IsArriving }}</dd>
              <dt class="text-[color:var(--muted-foreground)]">{{ t.schedule.previewArrivalStatus }}</dt>
              <dd>{{ previewVars.next2ArrivalStatus || '—' }}</dd>
            </dl>
          </div>
        </section>

        <div v-if="schedule.lastError"
             class="px-2 py-1 rounded bg-[color:var(--destructive)]/10 text-[color:var(--destructive)] text-xs">
          {{ schedule.lastError }}
        </div>
      </div>

      <footer class="px-4 py-3 border-t border-[color:var(--border)] flex items-center justify-end">
        <button class="hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                @click="close">
          {{ t.schedule.close }}
        </button>
      </footer>
    </div>

    <!-- 子对话框：添加 / 编辑 entry -->
    <ScheduleEntryDialog v-if="entryDialogOpen"
                        :entry="editingEntry"
                        :precision="schedule.precision"
                        :submitting="entrySubmitting"
                        :error-message="entryDialogError"
                        @close="entryDialogOpen = false"
                        @submit="onEntryDialogSubmit" />
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
