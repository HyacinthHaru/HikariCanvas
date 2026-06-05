<script setup lang="ts">
/**
 * 0.6 P4 / P4.5：AE 风时间轴底部 dock。
 *
 * <p>P4：dock 布局 + scrubber 60fps 本地预览 + 本地播放 + 缓动曲线编辑器 + timeline 设置。
 * P4.5（AE/PR 风整体关键帧）：元素行直接显示/选/删/缓动一个"整体关键帧"——该时刻所有 transform
 * 几何属性（x/y/w/h/rotation/opacity）的关键帧聚合成一个块；缓动统一应用到所有 transform → x/y
 * 进度同步 → 运动轨迹保持直线（只速度按缓动变，修"调缓动把轨迹掰弯"）。展开元素看 per-property
 * 子轨（P4.5a 只读灰显，单属性微调留 P4.5b）。</p>
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useEventListener } from '@vueuse/core';
import {
    Play, Pause, SkipBack, Plus, ChevronRight, ChevronDown, Film, X, Trash2, Spline, Settings,
} from 'lucide-vue-next';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { useTimelineStore } from '@/stores/timeline';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';
import { useTimelinePlayback } from '@/composables/useTimelinePlayback';
import {
    keyframeablePropertiesFor,
    defaultValueFor,
    computeRulerTicks,
    msToPx,
    pxToMs,
    formatTimeLabel,
    clampTimeMs,
    LOOP_MODES,
    KEYFRAMEABLE_PROPERTIES,
    aggregateTransformKeyframes,
    transformKeyframeKey,
    type TransformKeyframe,
} from './timelineLogic';
import EasingCurveEditor from './EasingCurveEditor.vue';
import type { Easing, LoopMode, TriggerConfig } from '@/types/protocol';

const project = useProjectStore();
const ui = useUiStore();
const store = useTimelineStore();
const ws = getWsClient();
const { t } = useI18n();
const playback = useTimelinePlayback();

const MANUAL_TRIGGER: TriggerConfig = { type: 'manual', params: {} };
const LINEAR_EASING = { type: 'linear' as const };
const ROW_H = 28;
const RULER_H = 24;
const LABEL_W = 220;

const tl = computed(() => store.activeTimeline);
const durationMs = computed(() => tl.value?.durationMs ?? 0);
const playing = computed(() => store.playing);

// ---------- 轨道展平：element 主行（整体帧）+ 展开后 per-property 子行（只读，P4.5b 微调） ----------
interface FlatRow {
    kind: 'element' | 'property';
    elementId: string;
    elementType: string | null;
    groups?: TransformKeyframe[];
    property?: string;
    keyframes?: { id: string; timeMs: number }[];
}
const flatRows = computed<FlatRow[]>(() => {
    const t0 = tl.value;
    if (!t0) return [];
    // 选中元素 ∪ 已有轨道元素——空 timeline 也能从选中元素加首帧（AE 工作流）
    const ids = new Set<string>([...ui.selectedIds, ...Object.keys(t0.tracks ?? {})]);
    const rows: FlatRow[] = [];
    for (const elementId of [...ids].sort()) {
        const el = project.elementById(elementId);
        const elementType = el?.type ?? null;
        rows.push({ kind: 'element', elementId, elementType, groups: aggregateTransformKeyframes(t0, elementId) });
        if (store.isExpanded(elementId)) {
            const trackKfs = t0.tracks?.[elementId] ?? [];
            const props = el ? keyframeablePropertiesFor(el) : [...new Set(trackKfs.map(k => k.property))];
            for (const property of props) {
                const keyframes = trackKfs.filter(k => k.property === property)
                    .map(k => ({ id: k.id, timeMs: k.timeMs })).sort((a, b) => a.timeMs - b.timeMs);
                rows.push({ kind: 'property', elementId, elementType, property, keyframes });
            }
        }
    }
    return rows;
});

// ---------- 时间↔像素 ----------
const timeAreaRef = ref<HTMLElement | null>(null);
const timeAreaWidth = ref(0);
let resizeObs: ResizeObserver | null = null;
watch([timeAreaWidth, durationMs], () => {
    const w = timeAreaWidth.value, dur = durationMs.value;
    store.setPxPerMs(dur > 0 && w > 0 ? w / dur : 0);
}, { immediate: true });
const ticks = computed(() => computeRulerTicks(durationMs.value, store.pxPerMs, timeAreaWidth.value, store.scrollMs));
const playheadX = computed(() => msToPx(store.playheadMs, store.pxPerMs, store.scrollMs));
function kfX(timeMs: number): number { return msToPx(timeMs, store.pxPerMs, store.scrollMs); }
function timeFromClientX(clientX: number): number {
    const el = timeAreaRef.value;
    if (!el) return store.playheadMs;
    return pxToMs(clientX - el.getBoundingClientRect().left, store.pxPerMs, store.scrollMs);
}

// ---------- scrubber ----------
const scrubbing = ref(false);
let scrubPointerId = -1;
function onScrubDown(e: PointerEvent): void {
    if (!tl.value || store.pxPerMs <= 0) return;
    if (store.playing) playback.pause();
    scrubbing.value = true; scrubPointerId = e.pointerId;
    try { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); } catch { /* ignore */ }
    store.setPreviewActive(true);
    store.setPlayhead(clampTimeMs(timeFromClientX(e.clientX), durationMs.value));
    e.preventDefault();
}
function onScrubMove(e: PointerEvent): void {
    if (!scrubbing.value) return;
    store.setPlayhead(clampTimeMs(timeFromClientX(e.clientX), durationMs.value));
}
function onScrubUp(e: PointerEvent): void {
    if (!scrubbing.value) return;
    scrubbing.value = false;
    try { (e.currentTarget as HTMLElement).releasePointerCapture(scrubPointerId); } catch { /* ignore */ }
    scrubPointerId = -1;
}

// ---------- 整体关键帧：选中 / 缓动同步 / 删除 / 加帧 ----------
function onGroupClick(e: MouseEvent, elementId: string, timeMs: number): void {
    store.selectGroup(transformKeyframeKey(elementId, timeMs), e.shiftKey);
}
function isGroupSel(elementId: string, timeMs: number): boolean {
    return store.isGroupSelected(transformKeyframeKey(elementId, timeMs));
}
const hasSelectedGroup = computed(() => store.selectedGroups.size > 0);

/** 恰好选中一个整体帧时返回它（缓动编辑用；多选时 null）。 */
const selectedGroupKf = computed<TransformKeyframe | null>(() => {
    if (store.selectedGroups.size !== 1 || !tl.value) return null;
    const key = [...store.selectedGroups][0];
    const sep = key.lastIndexOf(':');
    const elementId = key.slice(0, sep);
    const timeMs = Number(key.slice(sep + 1));
    return aggregateTransformKeyframes(tl.value, elementId).find(g => g.timeMs === timeMs) ?? null;
});
const selectedEasing = computed<Easing>(() => selectedGroupKf.value?.easing ?? { type: 'linear' });

function onEasingUpdate(easing: Easing): void {
    const g = selectedGroupKf.value;
    if (!g || !tl.value) return;
    // 同步整体帧所有 transform 关键帧的缓动 → x/y 进度一致 → 轨迹直线
    for (const kfId of g.keyframeIds) {
        ws.sendKeyframeUpdate(tl.value.id, kfId, { easing }).catch(() => { /* wsClient 日志 */ });
    }
}
function deleteSelectedGroups(): void {
    if (!tl.value) return;
    for (const key of store.selectedGroups) {
        const sep = key.lastIndexOf(':');
        const elementId = key.slice(0, sep);
        const timeMs = Number(key.slice(sep + 1));
        const g = aggregateTransformKeyframes(tl.value, elementId).find(x => x.timeMs === timeMs);
        if (g) for (const kfId of g.keyframeIds) {
            ws.sendKeyframeDelete(tl.value.id, kfId).catch(() => { /* wsClient 日志 */ });
        }
    }
    store.clearGroups();
}
/**
 * 在播放头给元素所有 transform 属性各加/更新一帧（值=元素当前值），组成一个整体关键帧。
 * 同 timeMs 已有该属性帧 → 更新值（不重复加）；否则新增。
 */
function addTransformKeyframe(elementId: string): void {
    if (!tl.value) return;
    const el = project.elementById(elementId);
    if (!el) return;
    const timeMs = store.playheadMs;
    const existing = tl.value.tracks?.[elementId] ?? [];
    for (const property of KEYFRAMEABLE_PROPERTIES) {
        const value = defaultValueFor(el, property);
        const ex = existing.find(k => k.property === property && k.timeMs === timeMs);
        if (ex) ws.sendKeyframeUpdate(tl.value.id, ex.id, { value }).catch(() => { /* wsClient 日志 */ });
        else ws.sendKeyframeAdd(tl.value.id, elementId, property, timeMs, value, LINEAR_EASING).catch(() => { /* wsClient 日志 */ });
    }
    store.selectGroup(transformKeyframeKey(elementId, timeMs));
}

// ---------- 缓动编辑器开关 ----------
const easingEditorOpen = ref(false);
watch(() => store.selectedGroups.size, (n) => { if (n === 0) easingEditorOpen.value = false; });

// ---------- Delete 删选中整体帧（dock 打开 + 有选中 + 焦点不在输入框）----------
useEventListener(window, 'keydown', (e: KeyboardEvent) => {
    if (e.key !== 'Delete' && e.key !== 'Backspace') return;
    if (!store.dockOpen || store.selectedGroups.size === 0) return;
    const tag = (e.target as HTMLElement | null)?.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
    deleteSelectedGroups();
    e.preventDefault();
});

// ---------- dock 高度 resize ----------
const resizing = ref(false);
let resizeStartY = 0, resizeStartH = 0, resizePointerId = -1;
function onResizeDown(e: PointerEvent): void {
    resizing.value = true; resizeStartY = e.clientY; resizeStartH = store.dockHeight; resizePointerId = e.pointerId;
    try { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); } catch { /* ignore */ }
    e.preventDefault();
}
function onResizeMove(e: PointerEvent): void {
    if (!resizing.value) return;
    store.setDockHeight(resizeStartH + (resizeStartY - e.clientY));
}
function onResizeUp(e: PointerEvent): void {
    if (!resizing.value) return;
    resizing.value = false;
    try { (e.currentTarget as HTMLElement).releasePointerCapture(resizePointerId); } catch { /* ignore */ }
    resizePointerId = -1;
}

// ---------- 左标签 ↔ 右轨道 滚动同步 ----------
const labelsScrollRef = ref<HTMLElement | null>(null);
const tracksScrollRef = ref<HTMLElement | null>(null);
function onTracksScroll(): void {
    if (labelsScrollRef.value && tracksScrollRef.value) labelsScrollRef.value.scrollTop = tracksScrollRef.value.scrollTop;
}

// ---------- 播放控制 ----------
function togglePlay(): void { if (store.playing) playback.pause(); else playback.play(); }
function rewind(): void { playback.pause(); store.setPreviewActive(true); store.setPlayhead(0); }

// ---------- 新建 timeline ----------
const creating = ref(false);
async function createTimeline(): Promise<void> {
    if (creating.value) return;
    creating.value = true;
    try { await ws.sendTimelineCreate('', 5000, 20, 'loop', MANUAL_TRIGGER); }
    catch { /* wsClient 日志 */ }
    finally { creating.value = false; }
}

// ---------- timeline 设置 ----------
const settingsOpen = ref(false);
const confirmDeleteTimeline = ref(false);
function updateTimeline(patch: Partial<{ name: string; durationMs: number; fps: number; loopMode: LoopMode }>): void {
    if (tl.value) ws.sendTimelineUpdate(tl.value.id, patch).catch(() => { /* wsClient 日志 */ });
}
function onNameChange(e: Event): void { updateTimeline({ name: (e.target as HTMLInputElement).value }); }
function onDurationChange(e: Event): void {
    const v = parseInt((e.target as HTMLInputElement).value, 10);
    if (Number.isInteger(v) && v >= 100 && v <= 3_600_000) updateTimeline({ durationMs: v });
}
function onFpsChange(e: Event): void {
    const v = parseInt((e.target as HTMLInputElement).value, 10);
    if (Number.isInteger(v) && v >= 1 && v <= 240) updateTimeline({ fps: v });
}
function onLoopChange(e: Event): void { updateTimeline({ loopMode: (e.target as HTMLSelectElement).value as LoopMode }); }
function deleteTimeline(): void {
    if (!tl.value) return;
    ws.sendTimelineDelete(tl.value.id).catch(() => { /* wsClient 日志 */ });
    confirmDeleteTimeline.value = false; settingsOpen.value = false;
}
function loopModeLabel(m: string): string {
    // t 是 ComputedRef，script 内须 .value 解包（hotfix-2）
    const x = t.value.timeline as unknown as Record<string, string>;
    const map: Record<string, string> = { once: x.loopOnce, loop: x.loopLoop, pingPong: x.loopPingPong };
    return map[m] ?? m;
}
watch(settingsOpen, (open) => { if (!open) confirmDeleteTimeline.value = false; });

// ---------- 标签 ----------
function propertyLabel(p: string): string {
    const m = t.value.timeline as unknown as Record<string, string>;
    return m['prop' + p.charAt(0).toUpperCase() + p.slice(1)] ?? p;
}
function elementLabel(row: FlatRow): string {
    const short = row.elementId.startsWith('e-') ? row.elementId.slice(2) : row.elementId;
    return row.elementType ? `${short} · ${row.elementType}` : short;
}

// ---------- 宽度测量 + 生命周期 ----------
function measureWidth(): void {
    timeAreaWidth.value = tracksScrollRef.value?.clientWidth ?? timeAreaRef.value?.clientWidth ?? 0;
}
onMounted(() => {
    const el = timeAreaRef.value;
    if (el && typeof ResizeObserver !== 'undefined') {
        resizeObs = new ResizeObserver(() => measureWidth());
        resizeObs.observe(el);
        measureWidth();
    }
});
watch([tl, () => flatRows.value.length], () => { nextTick(measureWidth); });
onBeforeUnmount(() => {
    if (resizeObs) { resizeObs.disconnect(); resizeObs = null; }
    playback.exitPreview();
});
watch(() => store.dockOpen, (open) => { if (!open) playback.exitPreview(); });
</script>

<template>
  <div
    class="relative flex flex-col border-t border-[color:var(--border)] bg-[color:var(--card)] text-[color:var(--card-foreground)] select-none"
    :style="{ height: store.dockHeight + 'px' }"
  >
    <!-- resize handle -->
    <div
      class="absolute top-0 left-0 right-0 h-1 cursor-ns-resize hover:bg-[color:var(--accent)] z-20"
      :title="t.timeline.dockResize"
      @pointerdown="onResizeDown" @pointermove="onResizeMove" @pointerup="onResizeUp" @pointercancel="onResizeUp"
    />

    <!-- header -->
    <div class="flex items-center gap-2 px-3 shrink-0 border-b border-[color:var(--border)]" :style="{ height: '36px' }">
      <Film class="size-4 text-[color:var(--muted-foreground)]" />
      <span class="text-sm font-medium">{{ t.timeline.dockTitle }}</span>
      <template v-if="tl">
        <div class="mx-2 h-4 w-px bg-[color:var(--border)]" />
        <button class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)]" :title="t.timeline.rewind" @click="rewind"><SkipBack class="size-4" /></button>
        <button class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)]" :title="playing ? t.timeline.pause : t.timeline.play" @click="togglePlay">
          <Pause v-if="playing" class="size-4" /><Play v-else class="size-4" />
        </button>
        <span class="ml-1 text-xs tabular-nums text-[color:var(--muted-foreground)]">{{ formatTimeLabel(store.playheadMs) }} / {{ formatTimeLabel(durationMs) }}</span>
        <div class="mx-2 h-4 w-px bg-[color:var(--border)]" />
        <span class="text-xs truncate max-w-[180px]">{{ tl.name }}</span>
        <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ tl.fps }}fps</span>
        <button
          class="hc-btn p-1 rounded-[var(--radius-sm)] transition-colors"
          :class="settingsOpen ? 'bg-[color:var(--accent)] text-[color:var(--foreground)]' : 'hover:bg-[color:var(--accent)]'"
          :title="t.timeline.dockSettings" @click="settingsOpen = !settingsOpen"
        ><Settings class="size-4" /></button>
        <button
          v-if="store.selectedGroups.size === 1"
          class="hc-btn ml-1 p-1 rounded-[var(--radius-sm)] transition-colors"
          :class="easingEditorOpen ? 'bg-[color:var(--accent)] text-[color:var(--foreground)]' : 'hover:bg-[color:var(--accent)]'"
          :title="t.timeline.addEasing" @click="easingEditorOpen = !easingEditorOpen"
        ><Spline class="size-4" /></button>
        <button
          v-if="hasSelectedGroup"
          class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--destructive)]/15 text-[color:var(--destructive)]"
          :title="t.timeline.kfDeleteAria" @click="deleteSelectedGroups"
        ><Trash2 class="size-4" /></button>
      </template>
      <div class="flex-1" />
      <button class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)]" :title="t.timeline.dockClose" @click="store.closeDock()"><X class="size-4" /></button>
    </div>

    <!-- 空状态 -->
    <div v-if="!tl" class="flex-1 flex flex-col items-center justify-center gap-3 text-[color:var(--muted-foreground)]">
      <span class="text-sm">{{ t.timeline.dockEmpty }}</span>
      <span class="text-xs">{{ t.timeline.dockEmptyHint }}</span>
      <button class="hc-btn inline-flex items-center gap-1 px-3 py-1.5 rounded-[var(--radius-sm)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)] disabled:opacity-50" :disabled="creating" @click="createTimeline"><Plus class="size-4" />{{ t.timeline.dockNew }}</button>
    </div>

    <!-- 主体 -->
    <div v-else class="flex-1 flex min-h-0">
      <!-- 左树 -->
      <div class="shrink-0 flex flex-col border-r border-[color:var(--border)]" :style="{ width: LABEL_W + 'px' }">
        <div class="shrink-0 border-b border-[color:var(--border)]" :style="{ height: RULER_H + 'px' }" />
        <div ref="labelsScrollRef" class="flex-1 overflow-hidden">
          <div v-for="(row, i) in flatRows" :key="i" class="group flex items-center px-2 text-xs border-b border-[color:var(--border)]/40" :style="{ height: ROW_H + 'px' }">
            <template v-if="row.kind === 'element'">
              <button class="mr-1 text-[color:var(--muted-foreground)] hover:text-[color:var(--foreground)]" @click="store.toggleExpanded(row.elementId)">
                <ChevronDown v-if="store.isExpanded(row.elementId)" class="size-3.5" /><ChevronRight v-else class="size-3.5" />
              </button>
              <span class="font-medium truncate flex-1">{{ elementLabel(row) }}</span>
              <button class="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-[color:var(--accent)] text-[color:var(--muted-foreground)]" :title="t.timeline.dockAddKeyframe" @click="addTransformKeyframe(row.elementId)"><Plus class="size-3.5" /></button>
            </template>
            <template v-else>
              <span class="pl-6 text-[color:var(--muted-foreground)] truncate">{{ propertyLabel(row.property!) }}</span>
            </template>
          </div>
        </div>
      </div>

      <!-- 右时间区 -->
      <div ref="timeAreaRef" class="flex-1 flex flex-col min-w-0 relative">
        <!-- 标尺（可拖 scrub） -->
        <div
          class="shrink-0 relative border-b border-[color:var(--border)] cursor-pointer overflow-hidden"
          :style="{ height: RULER_H + 'px' }"
          @pointerdown="onScrubDown" @pointermove="onScrubMove" @pointerup="onScrubUp" @pointercancel="onScrubUp"
        >
          <div v-for="tick in ticks" :key="tick.timeMs" class="absolute top-0 bottom-0 border-l border-[color:var(--border)]/60" :style="{ left: tick.x + 'px' }">
            <span class="absolute top-0.5 left-1 text-[10px] text-[color:var(--muted-foreground)] whitespace-nowrap">{{ tick.label }}</span>
          </div>
        </div>

        <!-- 轨道 -->
        <div ref="tracksScrollRef" class="flex-1 overflow-y-auto overflow-x-hidden relative" @scroll="onTracksScroll">
          <div v-if="flatRows.length === 0" class="absolute inset-0 flex items-center justify-center px-4 text-center text-xs text-[color:var(--muted-foreground)] pointer-events-none">{{ t.timeline.dockSelectHint }}</div>
          <div
            v-for="(row, i) in flatRows"
            :key="i"
            class="relative border-b border-[color:var(--border)]/40"
            :class="row.kind === 'element' ? 'bg-[color:var(--muted)]/20' : ''"
            :style="{ height: ROW_H + 'px' }"
          >
            <!-- 元素行：整体关键帧块（可选 / 删 / 缓动） -->
            <template v-if="row.kind === 'element'">
              <div
                v-for="g in row.groups"
                :key="g.timeMs"
                class="absolute top-1/2 size-3 -translate-x-1/2 -translate-y-1/2 rotate-45 cursor-pointer border"
                :class="isGroupSel(row.elementId, g.timeMs)
                  ? 'bg-[color:var(--ctp-yellow)] border-[color:var(--foreground)] z-10'
                  : 'bg-[color:var(--ctp-mauve)] border-[color:var(--card)]'"
                :style="{ left: kfX(g.timeMs) + 'px' }"
                :title="formatTimeLabel(g.timeMs)"
                @click="onGroupClick($event, row.elementId, g.timeMs)"
                @dblclick.stop
              />
            </template>
            <!-- 属性子行：per-property 关键帧块（P4.5a 只读灰显，单属性微调留 P4.5b） -->
            <template v-else>
              <div
                v-for="kf in row.keyframes"
                :key="kf.id"
                class="absolute top-1/2 size-2 -translate-x-1/2 -translate-y-1/2 rotate-45 bg-[color:var(--muted-foreground)]/50 border border-[color:var(--card)]"
                :style="{ left: kfX(kf.timeMs) + 'px' }"
              />
            </template>
          </div>
        </div>

        <!-- 播放头竖线（贯穿标尺 + 轨道） -->
        <div class="absolute top-0 bottom-0 w-px bg-[color:var(--ctp-red)] pointer-events-none z-10" :style="{ left: playheadX + 'px' }">
          <div class="absolute -left-[4px] top-0 size-0 border-l-[5px] border-r-[5px] border-t-[6px] border-l-transparent border-r-transparent" style="border-top-color: var(--ctp-red)" />
        </div>
      </div>
    </div>

    <!-- 缓动曲线编辑器 popover（选中单个整体帧 + header 曲线按钮打开） -->
    <div
      v-if="easingEditorOpen && selectedGroupKf"
      class="absolute left-2 bottom-full mb-1 z-50 rounded-[var(--radius)] border border-[color:var(--border)] bg-[color:var(--card)] shadow-lg"
    >
      <EasingCurveEditor :model-value="selectedEasing" @update:model-value="onEasingUpdate" />
    </div>

    <!-- timeline 设置 popover -->
    <div
      v-if="settingsOpen && tl"
      class="absolute right-2 top-11 z-50 w-64 max-h-[calc(100%-3.5rem)] overflow-y-auto p-3 flex flex-col gap-2 rounded-[var(--radius)] border border-[color:var(--border)] bg-[color:var(--card)] shadow-lg text-xs"
    >
      <label class="flex flex-col gap-1">
        <span class="text-[color:var(--muted-foreground)]">{{ t.timeline.newName }}</span>
        <input :value="tl.name" class="px-2 py-1 rounded border border-[color:var(--border)] bg-transparent" @change="onNameChange" />
      </label>
      <div class="flex gap-2">
        <label class="flex flex-col gap-1 flex-1">
          <span class="text-[color:var(--muted-foreground)]">{{ t.timeline.newDuration }}</span>
          <input :value="tl.durationMs" type="number" min="100" max="3600000" class="px-2 py-1 rounded border border-[color:var(--border)] bg-transparent" @change="onDurationChange" />
        </label>
        <label class="flex flex-col gap-1 w-16">
          <span class="text-[color:var(--muted-foreground)]">{{ t.timeline.newFps }}</span>
          <input :value="tl.fps" type="number" min="1" max="240" class="px-2 py-1 rounded border border-[color:var(--border)] bg-transparent" @change="onFpsChange" />
        </label>
      </div>
      <label class="flex flex-col gap-1">
        <span class="text-[color:var(--muted-foreground)]">{{ t.timeline.newLoop }}</span>
        <select :value="tl.loopMode" class="px-2 py-1 rounded border border-[color:var(--border)] bg-transparent" @change="onLoopChange">
          <option v-for="m in LOOP_MODES" :key="m" :value="m">{{ loopModeLabel(m) }}</option>
        </select>
      </label>
      <button
        class="mt-1 inline-flex items-center justify-center gap-1 px-2 py-1 rounded border-t border-[color:var(--border)] text-[color:var(--destructive)] hover:bg-[color:var(--destructive)]/15"
        @click="confirmDeleteTimeline ? deleteTimeline() : (confirmDeleteTimeline = true)"
      >
        <Trash2 class="size-3.5" />{{ confirmDeleteTimeline ? t.timeline.dockDeleteConfirm : t.timeline.dockDelete }}
      </button>
    </div>
  </div>
</template>
