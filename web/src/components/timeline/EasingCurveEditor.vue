<script setup lang="ts">
/**
 * 缓动曲线编辑器（SVG cubic-bezier）。
 *
 * <p>选中关键帧后编辑其 {@code easing}（到下一帧的缓动）。4 个预设快捷（linear / easeIn / easeOut /
 * easeInOut）+ 拖两个控制点产生自定义 {@code cubicBezier}（写 {@code bezier:[x1,y1,x2,y2]}）。曲线用
 * {@code ease()}（双端权威，rendering.md §9.3）采样绘制，与游戏内播放逐位一致。</p>
 *
 * <p>本地 draft 跟手：拖动期只更新本地 {@code localEasing}（曲线/控制点实时动），松手才
 * {@code emit('update:modelValue')}（父 TimelineDock 发一条 sendKeyframeUpdate，照 onDragMove
 * 节流哲学）；预设按钮即时 emit。父发 WS 后 server patch 回流更新 props → watch 同步 draft。</p>
 */
import { computed, ref, watch } from 'vue';
import { useI18n } from '@/i18n';
import { ease } from '@/timeline/easing';
import type { Easing, EasingType } from '@/types/protocol';

const props = defineProps<{ modelValue: Easing }>();
const emit = defineEmits<{ (e: 'update:modelValue', v: Easing): void }>();
const { t } = useI18n();

const SIZE = 160;   // SVG 画布边长（px）
const PRESETS = ['linear', 'easeIn', 'easeOut', 'easeInOut'] as const;
const PRESET_POINTS: Record<string, [number, number, number, number]> = {
    linear: [0, 0, 1, 1],
    easeIn: [0.42, 0, 1, 1],
    easeOut: [0, 0, 0.58, 1],
    easeInOut: [0.42, 0, 0.58, 1],
};

// 本地 draft（拖动跟手；props 变化时同步）。
const localEasing = ref<Easing>({ ...props.modelValue });
// 拖拽期基线快照：onHandleDown 冻结当前 localEasing，onHandleMove 基于它算控制点，避免
// 拖动中收到 server patch（watch 改 localEasing）导致未拖的那个控制点跳变。
const dragStartEasing = ref<Easing | null>(null);
watch(() => props.modelValue, (v) => {
    if (dragging) return;   // 拖拽期跳过 props 同步，松手后下一条 patch 再回流
    localEasing.value = { ...v };
});

const points = computed<[number, number, number, number]>(() => {
    const e = localEasing.value;
    if (e.type === 'cubicBezier' && e.bezier) return e.bezier;
    return PRESET_POINTS[e.type] ?? [0, 0, 1, 1];
});
const isCustom = computed(() => localEasing.value.type === 'cubicBezier');

function toSvgX(x: number): number { return x * SIZE; }
function toSvgY(y: number): number { return SIZE - y * SIZE; }   // y 上增（bezier y=1 在顶）

const curvePath = computed(() => {
    const N = 40;
    let d = `M 0 ${SIZE}`;
    for (let i = 1; i <= N; i++) {
        const x = i / N;
        d += ` L ${toSvgX(x)} ${toSvgY(ease(localEasing.value, x))}`;
    }
    return d;
});
const p1 = computed(() => ({ x: toSvgX(points.value[0]), y: toSvgY(points.value[1]) }));
const p2 = computed(() => ({ x: toSvgX(points.value[2]), y: toSvgY(points.value[3]) }));

function selectPreset(type: EasingType): void {
    localEasing.value = { type };
    emit('update:modelValue', { type });
}

// ---------- 控制点拖拽（x 钳 [0,1] CSS 约束；y 不钳允许 overshoot 弹性） ----------
const svgRef = ref<SVGSVGElement | null>(null);
let dragging: 1 | 2 | null = null;
let dragPointerId = -1;

function clamp01(v: number): number { return Math.max(0, Math.min(1, v)); }

function onHandleDown(e: PointerEvent, which: 1 | 2): void {
    dragging = which;
    dragStartEasing.value = { ...localEasing.value, bezier: points.value };
    dragPointerId = e.pointerId;
    try { (e.target as Element).setPointerCapture(e.pointerId); } catch { /* ignore */ }
    e.preventDefault();
    e.stopPropagation();
}
function onHandleMove(e: PointerEvent): void {
    if (!dragging || !svgRef.value) return;
    const rect = svgRef.value.getBoundingClientRect();
    const x = clamp01((e.clientX - rect.left) / rect.width);
    const y = 1 - (e.clientY - rect.top) / rect.height;
    // 未拖的那个控制点取拖拽起始快照（非可能被 patch 改过的 localEasing），避免曲线跳变。
    const c = dragStartEasing.value?.bezier ?? points.value;
    const bezier: [number, number, number, number] = dragging === 1
        ? [x, y, c[2], c[3]]
        : [c[0], c[1], x, y];
    localEasing.value = { type: 'cubicBezier', bezier };   // 本地跟手，不 emit
}
function onHandleUp(e: PointerEvent): void {
    if (!dragging) return;
    try { (e.target as Element).releasePointerCapture(dragPointerId); } catch { /* ignore */ }
    dragging = null;
    dragStartEasing.value = null;
    dragPointerId = -1;
    emit('update:modelValue', localEasing.value);   // 松手发一条
}

function presetLabel(type: string): string {
    // t 是 ComputedRef（useI18n），script 内须 .value 解包——直接 t.timeline 是 undefined。
    const m = t.value.timeline as unknown as Record<string, string>;
    const map: Record<string, string> = {
        linear: m.easingLinear, easeIn: m.easingEaseIn, easeOut: m.easingEaseOut, easeInOut: m.easingEaseInOut,
    };
    return map[type] ?? type;
}
</script>

<template>
  <div class="flex flex-col gap-2 p-3" :style="{ width: SIZE + 24 + 'px' }">
    <div class="flex flex-wrap gap-1">
      <button
        v-for="preset in PRESETS"
        :key="preset"
        class="px-2 py-0.5 text-[11px] rounded border transition-colors"
        :class="localEasing.type === preset
          ? 'bg-[color:var(--primary)] text-[color:var(--primary-foreground)] border-transparent'
          : 'border-[color:var(--border)] hover:bg-[color:var(--accent)]'"
        @click="selectPreset(preset)"
      >
        {{ presetLabel(preset) }}
      </button>
    </div>

    <svg
      ref="svgRef"
      :width="SIZE"
      :height="SIZE"
      class="bg-[color:var(--muted)]/20 rounded touch-none mx-auto"
      style="overflow: visible"
      @pointermove="onHandleMove"
      @pointerup="onHandleUp"
      @pointercancel="onHandleUp"
    >
      <line x1="0" :y1="SIZE" :x2="SIZE" y2="0" stroke="var(--border)" stroke-dasharray="3 3" stroke-width="1" />
      <line :x1="toSvgX(0)" :y1="toSvgY(0)" :x2="p1.x" :y2="p1.y" stroke="var(--ctp-mauve)" stroke-width="1" opacity="0.5" />
      <line :x1="toSvgX(1)" :y1="toSvgY(1)" :x2="p2.x" :y2="p2.y" stroke="var(--ctp-mauve)" stroke-width="1" opacity="0.5" />
      <path :d="curvePath" fill="none" stroke="var(--ctp-mauve)" stroke-width="2" />
      <circle :cx="toSvgX(0)" :cy="toSvgY(0)" r="2.5" fill="var(--foreground)" />
      <circle :cx="toSvgX(1)" :cy="toSvgY(1)" r="2.5" fill="var(--foreground)" />
      <circle :cx="p1.x" :cy="p1.y" r="5" fill="var(--ctp-mauve)" stroke="var(--card)" stroke-width="1.5" class="cursor-grab" @pointerdown="onHandleDown($event, 1)" />
      <circle :cx="p2.x" :cy="p2.y" r="5" fill="var(--ctp-mauve)" stroke="var(--card)" stroke-width="1.5" class="cursor-grab" @pointerdown="onHandleDown($event, 2)" />
    </svg>

    <span class="text-[10px] text-center text-[color:var(--muted-foreground)]">
      {{ isCustom ? t.timeline.easingCustom : presetLabel(localEasing.type) }}
    </span>
  </div>
</template>
