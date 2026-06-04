<script setup lang="ts">
/**
 * 0.6 P2（B3）：时间轴最简管理 modal（关键帧动画）。
 *
 * <p>挂在 App.vue 末尾（同 ScheduleManagerModal / VariablePanel），由 {@code ui.timelineManagerOpen}
 * 控制显示；TopBar Film 按钮触发。数据源是 {@code useProjectStore().state.timelines} /
 * {@code activeTimelineId}——P1 已落 applyTimelineMutation，timeline.* / keyframe.* op 的
 * state.patch 会自动更新这份 store mirror，本 modal 不持独立 store，只读 + 发 op。</p>
 *
 * <p>本面板是 MVP 关键帧列表（非 AE 风 panel —— 那是 P4）：5 区块 = 时间轴列表 / 新建表单 /
 * 选中 timeline 的关键帧列表 / 给选中元素加关键帧 / 播放控制。所有写操作走 wsClient.sendXxx
 * 的 ack 通道 + catch 错误 inline 提示（照 ScheduleManagerModal 的错误展示方式）。
 * 纯计算（分组 / 默认值 / 校验）抽到 {@link timelineLogic}，组件只做绑定 + 发包。</p>
 *
 * <p>固化决策（CLAUDE.md 0.6 路线）：play/pause/seek 与编辑 op 同权（session 活跃即可，
 * 不查 owner）；trigger MVP 固定 manual；easing MVP 固定 linear。</p>
 */
import { computed, reactive, ref, watch } from 'vue';
import { Film, X, Plus, Trash2, Play, Pause, SkipBack } from 'lucide-vue-next';
import { useUiStore } from '@/stores/ui';
import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';
import type { Element, Timeline, TriggerConfig } from '@/types/protocol';
import {
    CREATE_FORM_DEFAULTS,
    KEYFRAMEABLE_PROPERTIES,
    LOOP_MODES,
    type CreateFormInput,
    type KeyframeProperty,
    defaultValueFor,
    formatKeyframeValue,
    groupKeyframesByElement,
    isValidKeyframeTime,
    shortElementId,
    validateCreateForm,
} from './timelineLogic';

const ui = useUiStore();
const project = useProjectStore();
const net = useNetworkStore();
const ws = getWsClient();
const { t } = useI18n();

const open = computed(() => ui.timelineManagerOpen);

/** MVP 固定 trigger（触发器 UI 留 P5）。 */
const MANUAL_TRIGGER: TriggerConfig = { type: 'manual', params: {} };

// ---------- 数据源（project store mirror，P1 已经 patch-driven） ----------

const timelines = computed<Timeline[]>(() => project.state?.timelines ?? []);
/** 选中 = 当前 activeTimelineId 指向的那条（与"当前 timeline"等价，task 区块 1 高亮规则）。 */
const activeTimelineId = computed<string | null>(() => project.state?.activeTimelineId ?? null);
const selectedTimeline = computed<Timeline | null>(() => {
    const id = activeTimelineId.value;
    if (!id) return null;
    return timelines.value.find((tl) => tl.id === id) ?? null;
});

// ---------- 区块 1：删除确认状态机（照 VariablePanel confirmingDelete 风格） ----------

const confirmingDeleteId = ref<string | null>(null);
const deleteSubmitting = ref(false);

function requestDelete(timelineId: string): void {
    confirmingDeleteId.value = timelineId;
}
function cancelDelete(): void {
    confirmingDeleteId.value = null;
}
async function confirmDelete(timelineId: string): Promise<void> {
    if (deleteSubmitting.value) return;
    deleteSubmitting.value = true;
    try {
        await ws.sendTimelineDelete(timelineId);
        confirmingDeleteId.value = null;
    } catch (e) {
        showError((e as Error).message);
    } finally {
        deleteSubmitting.value = false;
    }
}

// ---------- 区块 2：新建表单 ----------

const createForm = reactive<CreateFormInput>({ ...CREATE_FORM_DEFAULTS });
const createSubmitting = ref(false);
const createError = ref<string | null>(null);

async function submitCreate(): Promise<void> {
    if (createSubmitting.value) return;
    const v = validateCreateForm(createForm);
    if (!v.ok) {
        createError.value = (t.value.timeline as Record<string, string>)[v.reason] ?? v.reason;
        return;
    }
    createError.value = null;
    createSubmitting.value = true;
    try {
        await ws.sendTimelineCreate(
            createForm.name.trim(),
            createForm.durationMs,
            createForm.fps,
            createForm.loopMode,
            MANUAL_TRIGGER,
        );
        // 重置表单回默认（新 timeline 经 state.patch 回流，无需本地预测）
        Object.assign(createForm, CREATE_FORM_DEFAULTS);
    } catch (e) {
        createError.value = (e as Error).message;
    } finally {
        createSubmitting.value = false;
    }
}

// ---------- 区块 3：选中 timeline 的关键帧列表（按 elementId 分组） ----------

const keyframeGroups = computed(() =>
    groupKeyframesByElement(selectedTimeline.value, (eid) => {
        const el = project.elementById(eid);
        return el ? el.type : null;
    }));

const kfDeleteSubmittingId = ref<string | null>(null);

async function deleteKeyframe(keyframeId: string): Promise<void> {
    const tl = selectedTimeline.value;
    if (!tl || kfDeleteSubmittingId.value !== null) return;
    kfDeleteSubmittingId.value = keyframeId;
    try {
        await ws.sendKeyframeDelete(tl.id, keyframeId);
    } catch (e) {
        showError((e as Error).message);
    } finally {
        kfDeleteSubmittingId.value = null;
    }
}

// ---------- 区块 4：给选中元素加关键帧 ----------

/** task：单选时启用；多选 / 空选禁用 + tooltip 说明。 */
const selectedElementId = computed<string | null>(() =>
    ui.selectedCount === 1 ? ui.selectedElementId : null);
const selectedElement = computed<Element | null>(() =>
    selectedElementId.value ? project.elementById(selectedElementId.value) : null);

const addProperty = ref<KeyframeProperty>('opacity');
const addTimeMs = ref<number>(0);
const addSubmitting = ref(false);
const addError = ref<string | null>(null);

/** 默认值随 property / 选中元素变化自动取元素当前属性值（opacity null → 1）。 */
const addDefaultValue = computed<number>(() =>
    defaultValueFor(selectedElement.value, addProperty.value));

/** 禁用条件：无 timeline / 无单选元素。tooltip 文案由模板按情形选。 */
const addDisabled = computed<boolean>(() =>
    !selectedTimeline.value || ui.selectedCount !== 1);

const addDisabledReason = computed<string>(() => {
    if (ui.selectedCount === 0) return t.value.timeline.addNoSelection;
    if (ui.selectedCount > 1) return t.value.timeline.addMultiSelection;
    if (!selectedTimeline.value) return t.value.timeline.emptyList;
    return '';
});

async function submitAddKeyframe(): Promise<void> {
    const tl = selectedTimeline.value;
    const eid = selectedElementId.value;
    if (!tl || !eid || addSubmitting.value) return;
    if (!isValidKeyframeTime(addTimeMs.value, tl.durationMs)) {
        addError.value = t.value.timeline.errKeyframeTime(tl.durationMs);
        return;
    }
    addError.value = null;
    addSubmitting.value = true;
    try {
        // MVP：value 取元素当前属性值（数值类）；easing 固定 linear。
        await ws.sendKeyframeAdd(
            tl.id, eid, addProperty.value, addTimeMs.value,
            addDefaultValue.value, { type: 'linear' },
        );
    } catch (e) {
        addError.value = (e as Error).message;
    } finally {
        addSubmitting.value = false;
    }
}

// ---------- 区块 5：播放控制 ----------

const playbackSubmitting = ref(false);

async function play(): Promise<void> {
    const tl = selectedTimeline.value;
    if (!tl || playbackSubmitting.value) return;
    playbackSubmitting.value = true;
    try {
        await ws.sendTimelinePlay(tl.id);
    } catch (e) {
        showError((e as Error).message);
    } finally {
        playbackSubmitting.value = false;
    }
}

async function pause(): Promise<void> {
    if (!selectedTimeline.value || playbackSubmitting.value) return;
    playbackSubmitting.value = true;
    try {
        await ws.sendTimelinePause();
    } catch (e) {
        showError((e as Error).message);
    } finally {
        playbackSubmitting.value = false;
    }
}

async function rewind(): Promise<void> {
    const tl = selectedTimeline.value;
    if (!tl || playbackSubmitting.value) return;
    playbackSubmitting.value = true;
    try {
        await ws.sendTimelineSeek(0, tl.id);
    } catch (e) {
        showError((e as Error).message);
    } finally {
        playbackSubmitting.value = false;
    }
}

// ---------- 通用 ----------

const lastError = ref<string | null>(null);
function showError(msg: string): void {
    lastError.value = msg;
    net.pushLog('err', `timeline op rejected: ${msg}`);
}

/** loopMode 裸值 → 大白话文案。 */
function loopModeLabel(mode: string): string {
    if (mode === 'once') return t.value.timeline.loopOnce;
    if (mode === 'pingPong') return t.value.timeline.loopPingPong;
    return t.value.timeline.loopLoop;
}

/** property 裸值 → 大白话文案。 */
function propertyLabel(property: string): string {
    switch (property) {
        case 'x': return t.value.timeline.propX;
        case 'y': return t.value.timeline.propY;
        case 'w': return t.value.timeline.propW;
        case 'h': return t.value.timeline.propH;
        case 'rotation': return t.value.timeline.propRotation;
        case 'opacity': return t.value.timeline.propOpacity;
        default: return property;
    }
}

function close(): void {
    if (createSubmitting.value || deleteSubmitting.value || addSubmitting.value
            || kfDeleteSubmittingId.value !== null || playbackSubmitting.value) {
        return;
    }
    ui.closeTimelineManager();
}

// 关闭 modal 或切 timeline 时清掉删除确认 / 错误残留，避免下次打开闪一下旧态。
watch(open, (v) => {
    if (!v) {
        confirmingDeleteId.value = null;
        lastError.value = null;
        createError.value = null;
        addError.value = null;
    }
});
watch(activeTimelineId, () => {
    confirmingDeleteId.value = null;
    addError.value = null;
});
</script>

<template>
  <div v-if="open"
       class="fixed inset-0 z-50 bg-[color:var(--ctp-crust)]/50 flex items-center justify-center p-4"
       @click.self="close">
    <div class="bg-[color:var(--card)] text-[color:var(--foreground)] rounded-[var(--radius)] shadow-md max-w-2xl w-full max-h-[90vh] flex flex-col border border-[color:var(--border)]">
      <header class="flex items-center gap-2 px-4 h-11 border-b border-[color:var(--border)]">
        <Film class="size-4 text-[color:var(--ctp-mauve)]" />
        <h2 class="text-sm font-semibold">{{ t.timeline.modalTitle }}</h2>
        <button class="ml-auto p-1 rounded hover:bg-[color:var(--accent)]"
                @click="close"
                :disabled="createSubmitting || deleteSubmitting || addSubmitting || playbackSubmitting">
          <X class="size-4" />
        </button>
      </header>

      <div class="flex-1 overflow-y-auto p-4 space-y-4 text-xs">
        <!-- 区块 1：时间轴列表 -->
        <section class="space-y-2">
          <div class="flex items-center gap-2">
            <span class="font-medium">{{ t.timeline.listHeader }}</span>
            <span class="text-xs text-[color:var(--muted-foreground)]">({{ timelines.length }})</span>
          </div>

          <div v-if="timelines.length === 0"
               class="p-3 rounded bg-[color:var(--muted)] text-[color:var(--muted-foreground)]">
            {{ t.timeline.emptyList }}
          </div>

          <ul v-else class="space-y-1.5">
            <li v-for="tl in timelines" :key="tl.id"
                class="rounded border"
                :class="tl.id === activeTimelineId
                    ? 'border-[color:var(--ctp-mauve)] bg-[color:var(--ctp-mauve)]/10'
                    : 'border-[color:var(--border)]'">
              <!-- 正常行 -->
              <div v-if="confirmingDeleteId !== tl.id"
                   class="flex items-center gap-2 px-2 py-1.5">
                <span class="flex-1 truncate font-medium" :title="tl.id">{{ tl.name }}</span>
                <span class="text-[color:var(--muted-foreground)] font-mono">
                  {{ t.timeline.durationLabel }} {{ tl.durationMs }}ms
                </span>
                <span class="text-[color:var(--muted-foreground)] font-mono">
                  {{ tl.fps }}{{ ' ' }}{{ t.timeline.fpsLabel }}
                </span>
                <span class="px-1.5 py-0.5 rounded bg-[color:var(--secondary)] text-[10px]">
                  {{ loopModeLabel(tl.loopMode) }}
                </span>
                <button class="p-1 rounded hover:bg-[color:var(--destructive)]/20"
                        @click="requestDelete(tl.id)"
                        :aria-label="t.timeline.deleteAria">
                  <Trash2 class="size-3.5 text-[color:var(--destructive)]" />
                </button>
              </div>
              <!-- 删除确认 popover（inline，照 VariablePanel 风格） -->
              <div v-else
                   class="flex items-center gap-2 p-2 rounded bg-[color:var(--destructive)]/10 border border-[color:var(--destructive)]/30">
                <span class="flex-1 text-[color:var(--destructive)] truncate" :title="tl.name">
                  {{ t.timeline.deleteConfirm(tl.name) }}
                </span>
                <button class="hc-btn px-2 py-0.5 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                        @click="cancelDelete">
                  {{ t.timeline.deleteConfirmNo }}
                </button>
                <button class="hc-btn px-2 py-0.5 text-xs rounded bg-[color:var(--destructive)] text-[color:var(--destructive-foreground)] hover:opacity-90 disabled:opacity-40"
                        :disabled="deleteSubmitting"
                        @click="confirmDelete(tl.id)">
                  {{ t.timeline.deleteConfirmYes }}
                </button>
              </div>
            </li>
          </ul>
        </section>

        <!-- 区块 2：新建表单 -->
        <section class="space-y-2 border-t border-[color:var(--border)] pt-3">
          <span class="font-medium">{{ t.timeline.newHeader }}</span>
          <div class="grid grid-cols-2 gap-2">
            <label class="block col-span-2">
              <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.timeline.newName }}</span>
              <input type="text" class="hc-input mt-0.5"
                     v-model="createForm.name"
                     :placeholder="t.timeline.newNamePlaceholder"
                     maxlength="64" />
            </label>
            <label class="block">
              <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.timeline.newDuration }}</span>
              <input type="number" class="hc-input mt-0.5"
                     v-model.number="createForm.durationMs"
                     min="1" step="100" />
            </label>
            <label class="block">
              <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.timeline.newFps }}</span>
              <input type="number" class="hc-input mt-0.5"
                     v-model.number="createForm.fps"
                     min="1" max="240" step="1" />
            </label>
            <label class="block col-span-2">
              <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.timeline.newLoop }}</span>
              <select class="hc-input mt-0.5" v-model="createForm.loopMode">
                <option v-for="m in LOOP_MODES" :key="m" :value="m">{{ loopModeLabel(m) }}</option>
              </select>
            </label>
          </div>
          <div class="flex items-center gap-2">
            <button class="hc-btn px-3 py-1 text-xs rounded-[var(--radius-sm)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)] hover:opacity-90 flex items-center gap-1 disabled:opacity-40"
                    :disabled="createSubmitting"
                    @click="submitCreate">
              <Plus class="size-3" />
              {{ t.timeline.newSubmit }}
            </button>
            <span v-if="createError" class="text-[color:var(--destructive)] text-[10px]">{{ createError }}</span>
          </div>
        </section>

        <!-- 区块 3：选中 timeline 的关键帧列表 -->
        <section v-if="selectedTimeline" class="space-y-2 border-t border-[color:var(--border)] pt-3">
          <div class="flex items-center gap-2">
            <span class="font-medium">{{ t.timeline.keyframesHeader }}</span>
            <span class="text-xs text-[color:var(--muted-foreground)]">— {{ selectedTimeline.name }}</span>
          </div>

          <div v-if="keyframeGroups.length === 0"
               class="p-3 rounded bg-[color:var(--muted)] text-[color:var(--muted-foreground)]">
            {{ t.timeline.emptyKeyframes }}
          </div>

          <div v-else class="space-y-2">
            <div v-for="grp in keyframeGroups" :key="grp.elementId"
                 class="border border-[color:var(--border)] rounded">
              <div class="px-2 py-1 bg-[color:var(--secondary)] flex items-center gap-2 rounded-t">
                <span class="font-mono text-[color:var(--ctp-mauve)]">{{ shortElementId(grp.elementId) }}</span>
                <span class="text-[10px] text-[color:var(--muted-foreground)]">
                  {{ grp.elementType ?? t.timeline.elementMissing }}
                </span>
              </div>
              <ul class="divide-y divide-[color:var(--border)]">
                <li v-for="kf in grp.keyframes" :key="kf.id"
                    class="flex items-center gap-2 px-2 py-1">
                  <span class="w-24 truncate">{{ propertyLabel(kf.property) }}</span>
                  <span class="w-20 font-mono text-[color:var(--muted-foreground)]">{{ kf.timeMs }}ms</span>
                  <span class="flex-1 truncate font-mono">{{ formatKeyframeValue(kf.value) }}</span>
                  <button class="p-1 rounded hover:bg-[color:var(--destructive)]/20"
                          @click="deleteKeyframe(kf.id)"
                          :disabled="kfDeleteSubmittingId === kf.id"
                          :aria-label="t.timeline.kfDeleteAria">
                    <Trash2 class="size-3.5 text-[color:var(--destructive)]" />
                  </button>
                </li>
              </ul>
            </div>
          </div>
        </section>

        <!-- 区块 4：给选中元素加关键帧 -->
        <section v-if="selectedTimeline" class="space-y-2 border-t border-[color:var(--border)] pt-3">
          <span class="font-medium">{{ t.timeline.addHeader }}</span>
          <p v-if="addDisabled" class="text-[10px] text-[color:var(--muted-foreground)] italic">
            {{ addDisabledReason }}
          </p>
          <div class="grid grid-cols-[1fr_1fr_auto] gap-2 items-end">
            <label class="block">
              <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.timeline.addProperty }}</span>
              <select class="hc-input mt-0.5" v-model="addProperty" :disabled="addDisabled">
                <option v-for="p in KEYFRAMEABLE_PROPERTIES" :key="p" :value="p">{{ propertyLabel(p) }}</option>
              </select>
            </label>
            <label class="block">
              <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.timeline.addTime }}</span>
              <input type="number" class="hc-input mt-0.5"
                     v-model.number="addTimeMs"
                     :disabled="addDisabled"
                     min="0" :max="selectedTimeline.durationMs" step="1" />
            </label>
            <button class="hc-btn px-3 py-1 text-xs rounded-[var(--radius-sm)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)] hover:opacity-90 flex items-center gap-1 disabled:opacity-40"
                    :disabled="addDisabled || addSubmitting"
                    :title="addDisabled ? addDisabledReason : ''"
                    @click="submitAddKeyframe">
              <Plus class="size-3" />
              {{ t.timeline.addSubmit }}
            </button>
          </div>
          <p v-if="!addDisabled" class="text-[10px] text-[color:var(--muted-foreground)]">
            {{ t.timeline.addValue }}: <span class="font-mono">{{ addDefaultValue }}</span>
          </p>
          <span v-if="addError" class="text-[color:var(--destructive)] text-[10px]">{{ addError }}</span>
        </section>

        <!-- 区块 5：播放控制 -->
        <section v-if="selectedTimeline" class="space-y-2 border-t border-[color:var(--border)] pt-3">
          <span class="font-medium">{{ t.timeline.playbackHeader }}</span>
          <div class="flex items-center gap-2">
            <button class="hc-btn px-3 py-1 text-xs rounded-[var(--radius-sm)] bg-[color:var(--secondary)] hover:bg-[color:var(--accent)] flex items-center gap-1 disabled:opacity-40"
                    :disabled="playbackSubmitting"
                    @click="play">
              <Play class="size-3" />
              {{ t.timeline.play }}
            </button>
            <button class="hc-btn px-3 py-1 text-xs rounded-[var(--radius-sm)] bg-[color:var(--secondary)] hover:bg-[color:var(--accent)] flex items-center gap-1 disabled:opacity-40"
                    :disabled="playbackSubmitting"
                    @click="pause">
              <Pause class="size-3" />
              {{ t.timeline.pause }}
            </button>
            <button class="hc-btn px-3 py-1 text-xs rounded-[var(--radius-sm)] bg-[color:var(--secondary)] hover:bg-[color:var(--accent)] flex items-center gap-1 disabled:opacity-40"
                    :disabled="playbackSubmitting"
                    @click="rewind">
              <SkipBack class="size-3" />
              {{ t.timeline.rewind }}
            </button>
          </div>
          <p class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.timeline.playbackHint }}</p>
        </section>

        <div v-if="lastError"
             class="px-2 py-1 rounded bg-[color:var(--destructive)]/10 text-[color:var(--destructive)] text-xs">
          {{ lastError }}
        </div>
      </div>

      <footer class="px-4 py-3 border-t border-[color:var(--border)] flex items-center justify-end">
        <button class="hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                @click="close">
          {{ t.timeline.close }}
        </button>
      </footer>
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
