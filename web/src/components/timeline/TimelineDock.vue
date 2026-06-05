<script setup lang="ts">
/**
 * 0.6 P4：AE 风时间轴底部 dock。
 *
 * <p>替代 P2 的 TimelineManagerModal（居中最简列表）为带时间标尺 + 每元素每属性子轨 + 关键帧块 +
 * 播放头 scrubber 的横向时间轴。数据读 {@code project.state} mirror（patch 驱动），编辑态在
 * {@code useTimelineStore}；scrubber / 本地播放走 interpolate 预览（CanvasView 接管喂
 * renderProjectState），绕开 project.state 的 {@code {deep:true}} watch（docs/timeline.md §8.2）。</p>
 *
 * <p>P4a：dock 布局 + resize + 标尺 + 轨道只读渲染 + scrubber 预览 + 本地播放。
 * P4b：左树列全可动画属性（stopwatch 建轨）+ 关键帧块拖拽改 timeMs（本地拖动 override / dragend
 * 发一条 sendKeyframeMove，照 onDragMove 节流哲学）+ 选中 + 删除 + 双击 / + 按钮加帧。
 * 缓动曲线编辑器是 P4c。无 active timeline 时一键新建。</p>
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
    defaultValueForExtended,
    computeRulerTicks,
    msToPx,
    pxToMs,
    formatTimeLabel,
    clampTimeMs,
    snapToFrame,
    LOOP_MODES,
} from './timelineLogic';
import EasingCurveEditor from './EasingCurveEditor.vue';
import type { Easing, LoopMode, TriggerConfig } from '@/types/protocol';

const project = useProjectStore();
const ui = useUiStore();
const store = useTimelineStore();
const ws = getWsClient();
const { t } = useI18n();
const playback = useTimelinePlayback();

/** MVP 固定 trigger / 缓动（曲线编辑器是 P4c，触发器 UI 是 P5）。 */
const MANUAL_TRIGGER: TriggerConfig = { type: 'manual', params: {} };
const LINEAR_EASING = { type: 'linear' as const };
const ROW_H = 28;       // 轨道行高（左标签 / 右轨道一致，保证横向对齐）
const RULER_H = 24;
const LABEL_W = 220;

const tl = computed(() => store.activeTimeline);
const durationMs = computed(() => tl.value?.durationMs ?? 0);
const playing = computed(() => store.playing);
const hasSelectedKf = computed(() => store.selectedKeyframeId !== null);

// ---------- P4c：缓动曲线编辑器（选中关键帧 → 编辑其 easing） ----------
const easingEditorOpen = ref(false);
const selectedKeyframe = computed(() => {
    const id = store.selectedKeyframeId;
    const t0 = tl.value;
    if (!id || !t0 || !t0.tracks) return null;
    for (const eid of Object.keys(t0.tracks)) {
        const kf = (t0.tracks[eid] ?? []).find(k => k.id === id);
        if (kf) return kf;
    }
    return null;
});
const selectedEasing = computed<Easing>(() => selectedKeyframe.value?.easing ?? { type: 'linear' });
function onEasingUpdate(easing: Easing): void {
    const id = store.selectedKeyframeId;
    if (id && tl.value) {
        ws.sendKeyframeUpdate(tl.value.id, id, { easing }).catch(() => { /* wsClient 日志 */ });
    }
}
// 选中清空（删除帧 / 切轨）时收起编辑器。
watch(() => store.selectedKeyframeId, (id) => { if (!id) easingEditorOpen.value = false; });

// ---------- 轨道展平：element 主行 + 展开后的「全可动画属性」子行（含无轨属性，可加首帧建轨） ----------
interface FlatRow {
    kind: 'element' | 'property';
    elementId: string;
    elementType: string | null;
    property?: string;
    keyframes?: { id: string; timeMs: number }[];
}
const flatRows = computed<FlatRow[]>(() => {
    const t0 = tl.value;
    if (!t0) return [];
    // 选中的画布元素 ∪ 已有轨道的元素——空 timeline 也能从选中元素加首帧建轨（AE 工作流：选图层 →
    // timeline 显示其可动画属性 → 加关键帧）。否则 tracks 空时 dock 永远空、无属性行、无从下手。
    const ids = new Set<string>([...ui.selectedIds, ...Object.keys(t0.tracks ?? {})]);
    const rows: FlatRow[] = [];
    for (const elementId of [...ids].sort()) {
        const el = project.elementById(elementId);
        const elementType = el?.type ?? null;
        rows.push({ kind: 'element', elementId, elementType });
        if (store.isExpanded(elementId)) {
            const trackKfs = t0.tracks?.[elementId] ?? [];
            // 元素存在 → 列全可动画属性（含无轨，可点 + 建轨）；元素已删（孤儿轨）→ 仅列已有轨属性
            const props = el
                ? keyframeablePropertiesFor(el)
                : [...new Set(trackKfs.map(k => k.property))];
            for (const property of props) {
                const keyframes = trackKfs
                    .filter(k => k.property === property)
                    .map(k => ({ id: k.id, timeMs: k.timeMs }))
                    .sort((a, b) => a.timeMs - b.timeMs);
                rows.push({ kind: 'property', elementId, elementType, property, keyframes });
            }
        }
    }
    return rows;
});

// ---------- 时间↔像素：pxPerMs 由时间区可见宽度 fit（P4a 铺满，缩放/横滚留后续） ----------
const timeAreaRef = ref<HTMLElement | null>(null);
const timeAreaWidth = ref(0);
let resizeObs: ResizeObserver | null = null;

watch([timeAreaWidth, durationMs], () => {
    const w = timeAreaWidth.value;
    const dur = durationMs.value;
    store.setPxPerMs(dur > 0 && w > 0 ? w / dur : 0);
}, { immediate: true });

const ticks = computed(() =>
    computeRulerTicks(durationMs.value, store.pxPerMs, timeAreaWidth.value, store.scrollMs));
const playheadX = computed(() => msToPx(store.playheadMs, store.pxPerMs, store.scrollMs));
function kfX(timeMs: number): number {
    return msToPx(timeMs, store.pxPerMs, store.scrollMs);
}
function timeFromClientX(clientX: number): number {
    const el = timeAreaRef.value;
    if (!el) return store.playheadMs;
    const rect = el.getBoundingClientRect();
    return pxToMs(clientX - rect.left, store.pxPerMs, store.scrollMs);
}

// ---------- scrubber（拖标尺改播放头；本地预览，不发 WS） ----------
const scrubbing = ref(false);
let scrubPointerId = -1;
function onScrubDown(e: PointerEvent): void {
    if (!tl.value || store.pxPerMs <= 0) return;
    if (store.playing) playback.pause();   // scrub 抢占自动播放，避免 rAF 与 scrub 每帧互覆 playhead
    scrubbing.value = true;
    scrubPointerId = e.pointerId;
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

// ---------- 关键帧拖拽改 timeMs（本地 override；dragend 发一条 sendKeyframeMove） ----------
const dragKfId = ref<string | null>(null);
const dragTimeMs = ref(0);
let dragPointerId = -1;
let dragStartTimeMs = 0;
let dragMoved = false;

function onKfDown(e: PointerEvent, kf: { id: string; timeMs: number }): void {
    if (!tl.value) return;
    e.stopPropagation();   // 防触发轨道双击加帧 / scrub
    store.selectKeyframe(kf.id);
    dragKfId.value = kf.id;
    dragStartTimeMs = kf.timeMs;
    dragTimeMs.value = kf.timeMs;
    dragMoved = false;
    dragPointerId = e.pointerId;
    try { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); } catch { /* ignore */ }
    e.preventDefault();
}
function onKfMove(e: PointerEvent): void {
    if (dragKfId.value === null || !tl.value) return;
    const snapped = snapToFrame(timeFromClientX(e.clientX), tl.value.fps, durationMs.value);
    if (snapped !== dragStartTimeMs) dragMoved = true;
    dragTimeMs.value = snapped;
}
function onKfUp(e: PointerEvent): void {
    if (dragKfId.value === null) return;
    const id = dragKfId.value;
    try { (e.currentTarget as HTMLElement).releasePointerCapture(dragPointerId); } catch { /* ignore */ }
    dragPointerId = -1;
    // 照 CanvasView.onDragMove 节流哲学：拖动期只更新本地 override，松手才发一条 WS。
    if (dragMoved && tl.value && dragTimeMs.value !== dragStartTimeMs) {
        ws.sendKeyframeMove(tl.value.id, id, dragTimeMs.value).catch(() => { /* wsClient 日志 */ });
    }
    dragKfId.value = null;
    dragMoved = false;
}
function blockX(kf: { id: string; timeMs: number }): number {
    return dragKfId.value === kf.id ? kfX(dragTimeMs.value) : kfX(kf.timeMs);
}

// ---------- 加 / 删关键帧 ----------
function onTrackDblClick(e: MouseEvent, row: FlatRow): void {
    if (row.kind !== 'property' || !tl.value) return;
    const rect = timeAreaRef.value?.getBoundingClientRect();
    if (!rect) return;
    const timeMs = snapToFrame(
        pxToMs(e.clientX - rect.left, store.pxPerMs, store.scrollMs), tl.value.fps, durationMs.value);
    addKeyframe(row, timeMs);
}
function addKeyframeAtPlayhead(row: FlatRow): void {
    if (!tl.value) return;
    addKeyframe(row, store.playheadMs);
}
function addKeyframe(row: FlatRow, timeMs: number): void {
    if (!tl.value || !row.property) return;
    const el = project.elementById(row.elementId);
    const value = defaultValueForExtended(el, row.property);
    ws.sendKeyframeAdd(tl.value.id, row.elementId, row.property, timeMs, value, LINEAR_EASING)
        .catch(() => { /* wsClient 日志 */ });
}
function deleteSelectedKeyframe(): void {
    const id = store.selectedKeyframeId;
    if (id && tl.value) {
        ws.sendKeyframeDelete(tl.value.id, id).catch(() => { /* wsClient 日志 */ });
        store.selectKeyframe(null);
    }
}
// Delete/Backspace 删选中关键帧（dock 打开 + 有选中 + 焦点不在输入框）。App.vue 的全局
// Delete handler 对同条件 return（不误删画布元素——dock 选帧与画布选元素是两套独立 store）。
useEventListener(window, 'keydown', (e: KeyboardEvent) => {
    if (e.key !== 'Delete' && e.key !== 'Backspace') return;
    if (!store.dockOpen || !store.selectedKeyframeId) return;
    const tag = (e.target as HTMLElement | null)?.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
    deleteSelectedKeyframe();
    e.preventDefault();
});

// ---------- dock 高度 resize（顶边拖拽） ----------
const resizing = ref(false);
let resizeStartY = 0;
let resizeStartH = 0;
let resizePointerId = -1;
function onResizeDown(e: PointerEvent): void {
    resizing.value = true;
    resizeStartY = e.clientY;
    resizeStartH = store.dockHeight;
    resizePointerId = e.pointerId;
    try { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); } catch { /* ignore */ }
    e.preventDefault();
}
function onResizeMove(e: PointerEvent): void {
    if (!resizing.value) return;
    store.setDockHeight(resizeStartH + (resizeStartY - e.clientY));   // 往上拖增高
}
function onResizeUp(e: PointerEvent): void {
    if (!resizing.value) return;
    resizing.value = false;
    try { (e.currentTarget as HTMLElement).releasePointerCapture(resizePointerId); } catch { /* ignore */ }
    resizePointerId = -1;
}

// ---------- 左标签 ↔ 右轨道 垂直滚动同步 ----------
const labelsScrollRef = ref<HTMLElement | null>(null);
const tracksScrollRef = ref<HTMLElement | null>(null);
function onTracksScroll(): void {
    if (labelsScrollRef.value && tracksScrollRef.value) {
        labelsScrollRef.value.scrollTop = tracksScrollRef.value.scrollTop;
    }
}

// ---------- 播放控制 ----------
function togglePlay(): void {
    if (store.playing) playback.pause();
    else playback.play();
}
function rewind(): void {
    playback.pause();
    store.setPreviewActive(true);
    store.setPlayhead(0);
}

// ---------- 无 active timeline → 一键新建 ----------
const creating = ref(false);
async function createTimeline(): Promise<void> {
    if (creating.value) return;
    creating.value = true;
    try {
        await ws.sendTimelineCreate('', 5000, 20, 'loop', MANUAL_TRIGGER);
    } catch { /* 错误经 wsClient 日志通道；dock MVP 不做 inline 错误条 */ }
    finally { creating.value = false; }
}

// ---------- P4d：timeline 设置（name / duration / fps / loop 编辑 + 删除） ----------
const settingsOpen = ref(false);
const confirmDeleteTimeline = ref(false);
function updateTimeline(patch: Partial<{ name: string; durationMs: number; fps: number; loopMode: LoopMode }>): void {
    if (tl.value) ws.sendTimelineUpdate(tl.value.id, patch).catch(() => { /* wsClient 日志 */ });
}
function onNameChange(e: Event): void {
    updateTimeline({ name: (e.target as HTMLInputElement).value });
}
function onDurationChange(e: Event): void {
    const v = parseInt((e.target as HTMLInputElement).value, 10);
    // 与后端 TimelineOperations [DURATION_MIN_MS=100, DURATION_MAX_MS=3_600_000] 对齐——
    // 否则合法输入（如 700000）被前端静默吞、或 <100 发出后被后端拒而无反馈。
    if (Number.isInteger(v) && v >= 100 && v <= 3_600_000) updateTimeline({ durationMs: v });
}
function onFpsChange(e: Event): void {
    const v = parseInt((e.target as HTMLInputElement).value, 10);
    if (Number.isInteger(v) && v >= 1 && v <= 240) updateTimeline({ fps: v });
}
function onLoopChange(e: Event): void {
    updateTimeline({ loopMode: (e.target as HTMLSelectElement).value as LoopMode });
}
function deleteTimeline(): void {
    if (!tl.value) return;
    ws.sendTimelineDelete(tl.value.id).catch(() => { /* wsClient 日志 */ });
    confirmDeleteTimeline.value = false;
    settingsOpen.value = false;
}
function loopModeLabel(m: string): string {
    // t 是 ComputedRef（useI18n），script 内必须 .value 解包——直接 t.timeline 是 undefined，
    // 渲染时 x.loopOnce 抛 "reading 'loopOnce' of undefined" 致整个 dock 崩溃消失。
    const x = t.value.timeline as unknown as Record<string, string>;
    const map: Record<string, string> = { once: x.loopOnce, loop: x.loopLoop, pingPong: x.loopPingPong };
    return map[m] ?? m;
}
watch(settingsOpen, (open) => { if (!open) confirmDeleteTimeline.value = false; });

// ---------- 标签 ----------
function propertyLabel(p: string): string {
    // t 是 ComputedRef，script 内须 .value 解包（同 loopModeLabel；否则展开属性行即崩）。
    const m = t.value.timeline as unknown as Record<string, string>;
    return m['prop' + p.charAt(0).toUpperCase() + p.slice(1)] ?? p;
}
function elementLabel(row: FlatRow): string {
    const short = row.elementId.startsWith('e-') ? row.elementId.slice(2) : row.elementId;
    return row.elementType ? `${short} · ${row.elementType}` : short;
}

function measureWidth(): void {
    // 用 tracksScroll 内容盒宽（已扣竖滚动条）作 pxPerMs 基准，使标尺刻度 / 关键帧 / 播放头
    // 同宽对齐——否则竖滚动条占位（占位式滚动条平台）会让关键帧块与标尺系统性错位。
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
// 轨道数 / active timeline 变化（竖滚动条可能出现/消失 → 内容盒宽变）后重测。
watch([tl, () => flatRows.value.length], () => { nextTick(measureWidth); });
onBeforeUnmount(() => {
    if (resizeObs) { resizeObs.disconnect(); resizeObs = null; }
    playback.exitPreview();   // dock 卸载回到基值 state，避免画布卡在插值帧
});

watch(() => store.dockOpen, (open) => { if (!open) playback.exitPreview(); });
</script>

<template>
  <div
    class="relative flex flex-col border-t border-[color:var(--border)] bg-[color:var(--card)] text-[color:var(--card-foreground)] select-none"
    :style="{ height: store.dockHeight + 'px' }"
  >
    <!-- resize handle（顶边） -->
    <div
      class="absolute top-0 left-0 right-0 h-1 cursor-ns-resize hover:bg-[color:var(--accent)] z-20"
      :title="t.timeline.dockResize"
      @pointerdown="onResizeDown"
      @pointermove="onResizeMove"
      @pointerup="onResizeUp"
      @pointercancel="onResizeUp"
    />

    <!-- header：播放控制 + 时间 + 名称 + 删除选中帧 + 关闭 -->
    <div class="flex items-center gap-2 px-3 shrink-0 border-b border-[color:var(--border)]" :style="{ height: '36px' }">
      <Film class="size-4 text-[color:var(--muted-foreground)]" />
      <span class="text-sm font-medium">{{ t.timeline.dockTitle }}</span>
      <template v-if="tl">
        <div class="mx-2 h-4 w-px bg-[color:var(--border)]" />
        <button class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)]" :title="t.timeline.rewind" @click="rewind">
          <SkipBack class="size-4" />
        </button>
        <button class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)]" :title="playing ? t.timeline.pause : t.timeline.play" @click="togglePlay">
          <Pause v-if="playing" class="size-4" />
          <Play v-else class="size-4" />
        </button>
        <span class="ml-1 text-xs tabular-nums text-[color:var(--muted-foreground)]">
          {{ formatTimeLabel(store.playheadMs) }} / {{ formatTimeLabel(durationMs) }}
        </span>
        <div class="mx-2 h-4 w-px bg-[color:var(--border)]" />
        <span class="text-xs truncate max-w-[180px]">{{ tl.name }}</span>
        <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ tl.fps }}fps</span>
        <button
          class="hc-btn p-1 rounded-[var(--radius-sm)] transition-colors"
          :class="settingsOpen ? 'bg-[color:var(--accent)] text-[color:var(--foreground)]' : 'hover:bg-[color:var(--accent)]'"
          :title="t.timeline.dockSettings"
          @click="settingsOpen = !settingsOpen"
        >
          <Settings class="size-4" />
        </button>
        <button
          v-if="hasSelectedKf"
          class="hc-btn ml-1 p-1 rounded-[var(--radius-sm)] transition-colors"
          :class="easingEditorOpen ? 'bg-[color:var(--accent)] text-[color:var(--foreground)]' : 'hover:bg-[color:var(--accent)]'"
          :title="t.timeline.addEasing"
          @click="easingEditorOpen = !easingEditorOpen"
        >
          <Spline class="size-4" />
        </button>
        <button
          v-if="hasSelectedKf"
          class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--destructive)]/15 text-[color:var(--destructive)]"
          :title="t.timeline.kfDeleteAria"
          @click="deleteSelectedKeyframe"
        >
          <Trash2 class="size-4" />
        </button>
      </template>
      <div class="flex-1" />
      <button class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)]" :title="t.timeline.dockClose" @click="store.closeDock()">
        <X class="size-4" />
      </button>
    </div>

    <!-- 空状态：无 active timeline -->
    <div v-if="!tl" class="flex-1 flex flex-col items-center justify-center gap-3 text-[color:var(--muted-foreground)]">
      <span class="text-sm">{{ t.timeline.dockEmpty }}</span>
      <span class="text-xs">{{ t.timeline.dockEmptyHint }}</span>
      <button
        class="hc-btn inline-flex items-center gap-1 px-3 py-1.5 rounded-[var(--radius-sm)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)] disabled:opacity-50"
        :disabled="creating"
        @click="createTimeline"
      >
        <Plus class="size-4" />
        {{ t.timeline.dockNew }}
      </button>
    </div>

    <!-- 主体：左属性树 + 右时间区 -->
    <div v-else class="flex-1 flex min-h-0">
      <!-- 左：属性树 -->
      <div class="shrink-0 flex flex-col border-r border-[color:var(--border)]" :style="{ width: LABEL_W + 'px' }">
        <div class="shrink-0 border-b border-[color:var(--border)]" :style="{ height: RULER_H + 'px' }" />
        <div ref="labelsScrollRef" class="flex-1 overflow-hidden">
          <div
            v-for="(row, i) in flatRows"
            :key="i"
            class="group flex items-center px-2 text-xs border-b border-[color:var(--border)]/40"
            :style="{ height: ROW_H + 'px' }"
          >
            <template v-if="row.kind === 'element'">
              <button class="mr-1 text-[color:var(--muted-foreground)] hover:text-[color:var(--foreground)]" @click="store.toggleExpanded(row.elementId)">
                <ChevronDown v-if="store.isExpanded(row.elementId)" class="size-3.5" />
                <ChevronRight v-else class="size-3.5" />
              </button>
              <span class="font-medium truncate">{{ elementLabel(row) }}</span>
            </template>
            <template v-else>
              <span class="pl-5 text-[color:var(--muted-foreground)] truncate flex-1">{{ propertyLabel(row.property!) }}</span>
              <button
                class="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-[color:var(--accent)] text-[color:var(--muted-foreground)]"
                :title="t.timeline.addSubmit"
                @click="addKeyframeAtPlayhead(row)"
              >
                <Plus class="size-3" />
              </button>
            </template>
          </div>
        </div>
      </div>

      <!-- 右：时间区（标尺 + 轨道行 + 播放头） -->
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

        <!-- 轨道行（关键帧块可选中 / 拖拽；双击空白加帧） -->
        <div ref="tracksScrollRef" class="flex-1 overflow-y-auto overflow-x-hidden relative" @scroll="onTracksScroll">
          <div
            v-if="flatRows.length === 0"
            class="absolute inset-0 flex items-center justify-center px-4 text-center text-xs text-[color:var(--muted-foreground)] pointer-events-none"
          >
            {{ t.timeline.dockSelectHint }}
          </div>
          <div
            v-for="(row, i) in flatRows"
            :key="i"
            class="relative border-b border-[color:var(--border)]/40"
            :class="row.kind === 'element' ? 'bg-[color:var(--muted)]/20' : 'cursor-copy'"
            :style="{ height: ROW_H + 'px' }"
            @dblclick="onTrackDblClick($event, row)"
          >
            <template v-if="row.kind === 'property'">
              <div
                v-for="kf in row.keyframes"
                :key="kf.id"
                class="absolute top-1/2 size-2.5 -translate-x-1/2 -translate-y-1/2 rotate-45 cursor-grab active:cursor-grabbing border"
                :class="store.selectedKeyframeId === kf.id
                  ? 'bg-[color:var(--ctp-yellow)] border-[color:var(--foreground)] z-10'
                  : 'bg-[color:var(--ctp-mauve)] border-[color:var(--card)]'"
                :style="{ left: blockX(kf) + 'px' }"
                @pointerdown="onKfDown($event, kf)"
                @pointermove="onKfMove"
                @pointerup="onKfUp"
                @pointercancel="onKfUp"
                @dblclick.stop
              />
            </template>
          </div>
        </div>

        <!-- 播放头竖线（贯穿标尺 + 轨道，覆盖在时间区上） -->
        <div class="absolute top-0 bottom-0 w-px bg-[color:var(--ctp-red)] pointer-events-none z-10" :style="{ left: playheadX + 'px' }">
          <div class="absolute -left-[4px] top-0 size-0 border-l-[5px] border-r-[5px] border-t-[6px] border-l-transparent border-r-transparent" style="border-top-color: var(--ctp-red)" />
        </div>
      </div>
    </div>

    <!-- P4c：缓动曲线编辑器 popover（选中关键帧 + header 曲线按钮打开；向上弹出避屏幕底裁切） -->
    <div
      v-if="easingEditorOpen && hasSelectedKf"
      class="absolute left-2 bottom-full mb-1 z-50 rounded-[var(--radius)] border border-[color:var(--border)] bg-[color:var(--card)] shadow-lg"
    >
      <EasingCurveEditor :model-value="selectedEasing" @update:model-value="onEasingUpdate" />
    </div>

    <!-- P4d：timeline 设置 popover（向上弹出） -->
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
        <Trash2 class="size-3.5" />
        {{ confirmDeleteTimeline ? t.timeline.dockDeleteConfirm : t.timeline.dockDelete }}
      </button>
    </div>
  </div>
</template>
