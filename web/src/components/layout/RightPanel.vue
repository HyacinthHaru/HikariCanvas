<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { getWsClient } from '@/network/wsClient';
import { Layers, Sliders, Eye, EyeOff, Lock, Unlock, Maximize2, Trash2, HelpCircle } from 'lucide-vue-next';
import { layoutText, canonicalCharWidth, ASCENT_RATIO } from '@/render/TextLayout';
import { FONT_META } from '@/render/PreviewRenderer';
import { useI18n } from '@/i18n';
import Tooltip from '@/components/ui/Tooltip.vue';
import LayerPanel from '@/components/layout/LayerPanel.vue';
import ColorInput from '@/components/ui/ColorInput.vue';
import FillInput from '@/components/ui/FillInput.vue';
import BrushPanel from '@/components/layout/BrushPanel.vue';
import type { Element, RectElement, TextElement, CircleElement, ShapeElement, PathElement, Effects, Stroke, Shadow, Glow, Fill } from '@/types/protocol';

const project = useProjectStore();
const ui = useUiStore();
const ws = getWsClient();
const { t } = useI18n();

const selected = computed<Element | null>(() => {
    if (!ui.selectedElementId) return null;
    return project.elementById(ui.selectedElementId);
});
const isText = computed(() => selected.value?.type === 'text');
const isRect = computed(() => selected.value?.type === 'rect');
const isCircle = computed(() => selected.value?.type === 'circle');
const isShape = computed(() => selected.value?.type === 'shape');
const isPath = computed(() => selected.value?.type === 'path');
/** M11-D：几何元素族（支持 fill / dither）。text / icon 不在内。 */
const isGeometric = computed(() => isRect.value || isCircle.value || isShape.value || isPath.value);
/** M11-D：当前元素 renderMode 是否 'dither'（缺省 'clean'）。 */
const isDither = computed(() => selected.value?.renderMode === 'dither');

/** M11-D：fill 通用切换 + 透传给 FillInput。fill 关闭时发 null（rect/path/circle/shape 必须 stroke 兜底）。 */
function geomStroke(): Stroke | null {
    const e = selected.value as (RectElement | CircleElement | ShapeElement | PathElement | null);
    return e?.stroke ?? null;
}
function toggleGeomStroke(ev: Event) {
    const on = (ev.target as HTMLInputElement).checked;
    sendUpdate({ stroke: on ? { width: 1, color: '#000000' } : null });
}
function patchGeomStroke(partial: Partial<Stroke>) {
    const cur = geomStroke() ?? { width: 1, color: '#000000' };
    sendUpdate({ stroke: { ...cur, ...partial } });
}
function toggleGeomFill(ev: Event) {
    const on = (ev.target as HTMLInputElement).checked;
    sendUpdate({ fill: on ? { type: 'solid', color: '#FF3366' } : null });
}
function geomFill(): Fill | undefined {
    const e = selected.value as (RectElement | CircleElement | ShapeElement | PathElement | null);
    const raw = e?.fill;
    if (raw === undefined || raw === null) return undefined;
    if (typeof raw === 'string') return { type: 'solid', color: raw };
    return raw;
}
function onDitherChange(ev: Event) {
    const on = (ev.target as HTMLInputElement).checked;
    sendUpdate({ renderMode: on ? 'dither' : 'clean' });
}

/** M8-F：是否多选（>= 2）。multi 时隐藏单选 UI，显示批量操作提示。 */
const isMulti = computed(() => ui.selectedCount >= 2);

const elementCount = computed(() => project.state?.elements?.length ?? 0);
const activeLayerLocked = computed(() => project.activeLayerLocked);

// ---------- M8-E：element opacity slider ----------

/**
 * slider 拖动中本地缓冲百分比；松开后归 null 由 computed 回到 state 真值。
 * 切换选中元素时必须清空，否则下一个元素 slider 显示前一个的值。
 */
const opacityDraftPct = ref<number | null>(null);

watch(() => selected.value?.id, () => { opacityDraftPct.value = null; });

const opacityPct = computed(() => {
    if (opacityDraftPct.value !== null) return opacityDraftPct.value;
    const op = selected.value?.opacity;
    return op === undefined || op === null ? 100 : Math.round(op * 100);
});

// VueUse useDebounceFn 不提供 flush；input 走 debounce，change（mouseup）immediate send，
// 两条路最终值相同；最差冗余 1 次 ws 发送同值 patch，无副作用。
const sendOpacityDebounced = useDebounceFn((elementId: string, value: number) => {
    ws.send('element.update', { elementId, patch: { opacity: value } });
}, 80);

function onOpacityInput(ev: Event): void {
    const v = parseInt((ev.target as HTMLInputElement).value, 10);
    if (!Number.isFinite(v)) return;
    opacityDraftPct.value = v;
    const el = selected.value;
    if (!el) return;
    const opacity = v / 100;
    (el as unknown as Record<string, unknown>).opacity = opacity;
    sendOpacityDebounced(el.id, opacity);
}

function onOpacityChange(): void {
    const el = selected.value;
    opacityDraftPct.value = null;
    if (!el) return;
    // 立即 send 当前 element.opacity（来自最后一次 input 乐观更新），确保最终值落地
    const op = (el as { opacity?: number }).opacity ?? 1;
    ws.send('element.update', { elementId: el.id, patch: { opacity: op } });
}

/** 立即发送（用于 boolean / color / select 之类"定型"变更）。 */
function sendUpdate(patch: Record<string, unknown>) {
    const el = selected.value;
    if (!el) return;
    // optimistic
    for (const [k, v] of Object.entries(patch)) {
        (el as unknown as Record<string, unknown>)[k] = v;
    }
    ws.send('element.update', { elementId: el.id, patch });
}

/** 80ms 防抖（用于文字 / 数值输入）。2026-05-12 实测后从 200ms 降下来，
 *  在 ~12 wpm 输入下不会塞 WS，但视觉滞后感明显改善。color/select 路径不防抖。 */
const sendUpdateDebounced = useDebounceFn(sendUpdate, 80);

// ---------- 控件绑定 helpers ----------

function numberModel(field: keyof Element) {
    return {
        get: () => (selected.value as unknown as Record<string, number>)[field as string] ?? 0,
        set: (v: number) => sendUpdateDebounced({ [field]: Number.isFinite(v) ? v : 0 }),
    };
}

function onBoolChange(field: 'visible' | 'locked', ev: Event) {
    const v = (ev.target as HTMLInputElement).checked;
    sendUpdate({ [field]: v });
}

function onTextChange(field: string, ev: Event) {
    const v = (ev.target as HTMLInputElement | HTMLTextAreaElement).value;
    // eager optimistic：文本类输入立即更新 local element.text，这样下面的 fitTextHeight
    // 能读到最新内容。sendUpdateDebounced 自己也会再 optimistic 一次，无害。
    const el = selected.value;
    if (el) (el as unknown as Record<string, unknown>)[field] = v;
    sendUpdateDebounced({ [field]: v });
    if (field === 'text' && el?.type === 'text') {
        // M5-D5 Bug 8：输入文字后自动按 canonicalCharWidth 布局撑高。不 fit 宽度——
        // 宽度是用户设定的软换行边界，保留设计意图。
        autoFitHeightDebounced();
    }
}

const autoFitHeightDebounced = useDebounceFn(() => {
    fitTextHeight();
}, 250);

function onColorChange(field: string, ev: Event) {
    const v = (ev.target as HTMLInputElement).value.toUpperCase();
    sendUpdate({ [field]: v });
}

function onNumberChange(field: string, ev: Event) {
    let v = parseFloat((ev.target as HTMLInputElement).value);
    if (!Number.isFinite(v)) return;
    if (field === 'rotation') v = ((Math.round(v) % 360) + 360) % 360;
    sendUpdateDebounced({ [field]: v });
}

function onSelectChange(field: string, ev: Event) {
    const v = (ev.target as HTMLSelectElement).value;
    sendUpdate({ [field]: field === 'rotation' ? parseInt(v, 10) : v });
}

// ---------- Effects 专用 ----------

function textEffects(): Effects {
    const t = selected.value as TextElement | null;
    return t?.effects ?? {};
}

function updateEffects(patch: Partial<Effects>) {
    const e = selected.value as TextElement | null;
    if (!e) return;
    const merged: Effects = { ...(e.effects ?? {}), ...patch };
    // 所有子键为 null/undefined 时干脆传 undefined 让后端去 effects
    const clean: Effects | null = (merged.stroke || merged.shadow || merged.glow) ? merged : null;
    sendUpdate({ effects: clean });
}

function toggleStroke(ev: Event) {
    const on = (ev.target as HTMLInputElement).checked;
    updateEffects({ stroke: on ? { width: 2, color: '#000000' } : undefined });
}
function toggleShadow(ev: Event) {
    const on = (ev.target as HTMLInputElement).checked;
    updateEffects({ shadow: on ? { dx: 2, dy: 2, color: '#000000' } : undefined });
}
function toggleGlow(ev: Event) {
    const on = (ev.target as HTMLInputElement).checked;
    updateEffects({ glow: on ? { radius: 3, color: '#33CCFF' } : undefined });
}

function patchStroke(partial: Partial<Stroke>) {
    const cur = textEffects().stroke ?? { width: 2, color: '#000000' };
    updateEffects({ stroke: { ...cur, ...partial } });
}
function patchShadow(partial: Partial<Shadow>) {
    const cur = textEffects().shadow ?? { dx: 2, dy: 2, color: '#000000' };
    updateEffects({ shadow: { ...cur, ...partial } });
}
function patchGlow(partial: Partial<Glow>) {
    const cur = textEffects().glow ?? { radius: 3, color: '#33CCFF' };
    updateEffects({ glow: { ...cur, ...partial } });
}

function deleteSelected() {
    const el = selected.value;
    if (!el) return;
    ws.send('element.delete', { elementId: el.id });
    ui.selectElement(null);
}

/** M8-F：批量删除多选元素，逐个发 element.delete op。 */
function deleteMultiSelected(): void {
    if (ui.selectedCount === 0) return;
    const ids = Array.from(ui.selectedIds);
    for (const id of ids) {
        ws.send('element.delete', { elementId: id });
    }
    ui.clearSelection();
}

// ---------- M5-D3 P4：文本 fit-content ----------

function fitTextHeight() {
    const el = selected.value;
    if (!el || el.type !== 'text') return;
    const te = el as TextElement;
    const glyphs = layoutText(te);
    if (glyphs.length === 0) return;
    let maxBaselineY = te.y;
    for (const g of glyphs) {
        if (g.baselineY > maxBaselineY) maxBaselineY = g.baselineY;
    }
    // baseline 到 top = fontSize * ASCENT_RATIO；descent = fontSize - ascent
    const ascent = Math.round(te.fontSize * ASCENT_RATIO);
    const descent = Math.max(1, te.fontSize - ascent);
    const newH = Math.max(1, (maxBaselineY + descent) - te.y);
    if (newH !== te.h) sendUpdate({ h: newH });
}

function fitTextWidth() {
    const el = selected.value;
    if (!el || el.type !== 'text') return;
    const te = el as TextElement;
    const glyphs = layoutText(te);
    if (glyphs.length === 0) return;
    let maxRight = te.x;
    for (const g of glyphs) {
        // rotated glyph 占方格 fontSize；非旋转占 canonicalCharWidth
        const w = g.rotated ? te.fontSize : canonicalCharWidth(g.ch, te.fontSize);
        const right = g.x + w;
        if (right > maxRight) maxRight = right;
    }
    const newW = Math.max(1, maxRight - te.x);
    if (newW !== te.w) sendUpdate({ w: newW });
}

// ---------- Element 在活动层内的 z-order 重排（HTML5 drag & drop） ----------
//
// 注：M8-D 起这里只处理"层内元素"的重排（element.reorder op），层级的重排由 LayerPanel.vue
// 单独负责（layer.reorder op）。命名 onElement* 与 LayerPanel 的 onDrag* 区分。

const dragIdx = ref(-1);
const dragOverIdx = ref(-1);

function onElementDragStart(ev: DragEvent, idx: number) {
    dragIdx.value = idx;
    if (ev.dataTransfer) {
        ev.dataTransfer.effectAllowed = 'move';
        ev.dataTransfer.setData('text/plain', String(idx));
    }
}

function onElementDragOver(ev: DragEvent, idx: number) {
    ev.preventDefault();
    dragOverIdx.value = idx;
    if (ev.dataTransfer) ev.dataTransfer.dropEffect = 'move';
}

function onElementDragLeave() {
    dragOverIdx.value = -1;
}

function onElementDrop(ev: DragEvent, idx: number) {
    ev.preventDefault();
    const from = dragIdx.value;
    dragIdx.value = -1;
    dragOverIdx.value = -1;
    if (from < 0 || from === idx) return;
    if (!project.state) return;
    const el = project.state.elements[from];
    if (!el) return;
    // optimistic reorder
    const arr = project.state.elements;
    const [moved] = arr.splice(from, 1);
    arr.splice(idx, 0, moved);
    ws.send('element.reorder', { elementId: el.id, index: idx });
}

function onElementDragEnd() {
    dragIdx.value = -1;
    dragOverIdx.value = -1;
}
</script>

<template>
  <aside class="w-72 bg-[color:var(--card)] border-l border-[color:var(--border)] flex flex-col"
         :class="{ 'hc-readonly-panel': project.isLocked }">
    <!-- 2026-05-14 lock-state：locked 时整个右栏 pointer-events: none + opacity 60%，
         禁止任何编辑控件交互；解锁路径只能走 TopBar Lock 按钮（owner 才可见）。 -->
    <!-- M8-D：图层面板（顶端，自身控制 max-h 40%）。 -->
    <LayerPanel />

    <!-- M12-D：笔刷工具激活时，下半 BrushPanel 替代 Properties；其他工具走 Properties 原路径 -->
    <BrushPanel v-if="ui.activeTool === 'brush'" />

    <!-- Properties -->
    <template v-else>
    <section class="flex-1 overflow-y-auto min-h-0">
      <header class="flex items-center gap-2 px-3 h-9 border-b border-[color:var(--border)] text-xs font-medium uppercase tracking-wider text-[color:var(--muted-foreground)]">
        <Sliders class="size-3.5" />
        <span>{{ t.properties.header }}</span>
        <Tooltip v-if="selected" :text="t.properties.deleteTitle" shortcut="Del">
          <button
            class="ml-auto p-1 rounded hover:bg-[color:var(--destructive)] hover:text-[color:var(--destructive-foreground)] text-[color:var(--muted-foreground)]"
            @click="deleteSelected"
          >
            <Trash2 class="size-3.5" />
          </button>
        </Tooltip>
        <Tooltip v-else-if="isMulti" :text="t.properties.deleteMultiTip(ui.selectedCount)" shortcut="Del">
          <button
            class="ml-auto p-1 rounded hover:bg-[color:var(--destructive)] hover:text-[color:var(--destructive-foreground)] text-[color:var(--muted-foreground)]"
            @click="deleteMultiSelected"
          >
            <Trash2 class="size-3.5" />
          </button>
        </Tooltip>
      </header>

      <div v-if="isMulti" class="p-3 space-y-2 text-xs">
        <div class="font-medium">{{ t.properties.multiSelected(ui.selectedCount) }}</div>
        <div class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.properties.multiHint }}</div>
      </div>

      <div v-else-if="!selected" class="p-3 text-xs text-[color:var(--muted-foreground)]">
        {{ t.properties.empty }}
      </div>

      <div v-else class="p-3 space-y-3 text-xs">
        <!-- 基本信息 -->
        <div class="flex items-center justify-between">
          <span class="text-[color:var(--muted-foreground)]">{{ t.properties.type }}</span>
          <span class="font-mono">{{ selected.type }}</span>
        </div>
        <div class="flex items-center justify-between">
          <span class="text-[color:var(--muted-foreground)]">{{ t.properties.id }}</span>
          <span class="font-mono text-[10px] truncate max-w-[140px]" :title="selected.id">
            {{ selected.id }}
          </span>
        </div>

        <!-- 位置 & 尺寸 -->
        <details class="group" open>
          <summary class="cursor-pointer select-none text-[color:var(--muted-foreground)] uppercase tracking-wider text-[10px] py-1 hover:text-[color:var(--foreground)]">
            {{ t.properties.transformHeader }}
          </summary>
          <div class="grid grid-cols-2 gap-2 pt-1.5">
            <label class="flex flex-col gap-0.5">
              <span class="text-[10px] text-[color:var(--muted-foreground)]">x</span>
              <input type="number" class="hc-input" :value="selected.x" @input="(e) => onNumberChange('x', e)">
            </label>
            <label class="flex flex-col gap-0.5">
              <span class="text-[10px] text-[color:var(--muted-foreground)]">y</span>
              <input type="number" class="hc-input" :value="selected.y" @input="(e) => onNumberChange('y', e)">
            </label>
            <label class="flex flex-col gap-0.5">
              <span class="text-[10px] text-[color:var(--muted-foreground)]">w</span>
              <input type="number" min="1" class="hc-input" :value="selected.w" @input="(e) => onNumberChange('w', e)">
            </label>
            <label class="flex flex-col gap-0.5">
              <span class="text-[10px] text-[color:var(--muted-foreground)]">h</span>
              <input type="number" min="1" class="hc-input" :value="selected.h" @input="(e) => onNumberChange('h', e)">
            </label>
            <label class="flex flex-col gap-0.5 col-span-2">
              <span class="hc-field-label">
                {{ t.properties.rotation }}
                <Tooltip :text="t.properties.rotationTip">
                  <HelpCircle class="size-2.5 opacity-50 hover:opacity-100 inline" />
                </Tooltip>
              </span>
              <input type="number" min="0" max="359" class="hc-input" :value="selected.rotation"
                     @input="(e) => onNumberChange('rotation', e)">
            </label>
          </div>
          <div class="flex gap-3 pt-2">
            <label class="flex items-center gap-1.5">
              <input type="checkbox" :checked="selected.visible" @change="(e) => onBoolChange('visible', e)">
              <span>{{ t.properties.visible }}</span>
            </label>
            <label class="flex items-center gap-1.5">
              <input type="checkbox" :checked="selected.locked" @change="(e) => onBoolChange('locked', e)">
              <span>{{ t.properties.locked }}</span>
            </label>
          </div>
          <!-- M8-E：element opacity slider -->
          <label class="flex items-center gap-2 pt-1">
            <span class="hc-field-label">
              {{ t.properties.opacity }}
              <Tooltip :text="t.properties.opacityTip">
                <HelpCircle class="size-2.5 opacity-50 hover:opacity-100 inline" />
              </Tooltip>
            </span>
            <input
              type="range"
              min="0"
              max="100"
              step="1"
              class="flex-1 hc-elem-slider"
              :value="opacityPct"
              @input="onOpacityInput"
              @change="onOpacityChange"
            >
            <span class="w-8 text-[10px] text-right tabular-nums">{{ opacityPct }}%</span>
          </label>
          <!-- M8-E：blendMode + renderMode UI 保留但 disabled（M11 dither 一并实装合成） -->
          <div class="grid grid-cols-2 gap-2 pt-1">
            <label class="flex flex-col gap-0.5">
              <span class="hc-field-label">
                {{ t.properties.blendMode }}
                <Tooltip :text="t.properties.blendModeTip">
                  <HelpCircle class="size-2.5 opacity-50 hover:opacity-100 inline" />
                </Tooltip>
              </span>
              <select class="hc-input opacity-60 cursor-not-allowed" :value="selected.blendMode ?? 'normal'" disabled>
                <option value="normal">normal</option>
                <option value="multiply">multiply</option>
                <option value="screen">screen</option>
                <option value="overlay">overlay</option>
              </select>
            </label>
            <label class="flex flex-col gap-0.5">
              <span class="hc-field-label">
                {{ t.properties.renderMode }}
                <Tooltip :text="t.properties.renderModeTip">
                  <HelpCircle class="size-2.5 opacity-50 hover:opacity-100 inline" />
                </Tooltip>
              </span>
              <select class="hc-input opacity-60 cursor-not-allowed" :value="selected.renderMode ?? 'clean'" disabled>
                <option value="clean">{{ t.properties.renderModeClean }}</option>
                <option value="dither">{{ t.properties.renderModeDither }}</option>
              </select>
            </label>
          </div>
        </details>

        <!-- 几何元素（rect / circle / shape / path）公用 fill + stroke + dither -->
        <details v-if="isGeometric" class="group" open>
          <summary class="cursor-pointer select-none text-[color:var(--muted-foreground)] uppercase tracking-wider text-[10px] py-1 hover:text-[color:var(--foreground)]">
            {{ isRect ? t.properties.rectHeader
                : isCircle ? t.properties.circleHeader
                : isShape ? t.properties.shapeHeader
                : t.properties.pathHeader }}
          </summary>
          <div class="pt-1.5 space-y-2">
            <!-- fill：toggle + FillInput（M11-D 支持 solid / linear / radial） -->
            <div class="flex items-start justify-between gap-2">
              <span class="text-[color:var(--muted-foreground)] mt-0.5">{{ t.properties.fill }}</span>
              <div class="flex flex-col gap-1 flex-1 max-w-[180px]">
                <input type="checkbox"
                       :checked="geomFill() !== undefined"
                       @change="toggleGeomFill"
                       class="self-end">
                <FillInput v-if="geomFill()"
                           :model-value="geomFill()"
                           @update:model-value="(v) => sendUpdate({ fill: v })" />
              </div>
            </div>
            <!-- stroke：toggle + 宽度 + 颜色 -->
            <label class="flex items-center justify-between gap-2">
              <span class="text-[color:var(--muted-foreground)]">{{ t.properties.stroke }}</span>
              <input type="checkbox" :checked="geomStroke() !== null" @change="toggleGeomStroke">
            </label>
            <template v-if="geomStroke()">
              <div class="grid grid-cols-2 gap-2">
                <label class="flex flex-col gap-0.5">
                  <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.properties.strokeWidth }}</span>
                  <input type="number" min="0" class="hc-input" :value="geomStroke()!.width"
                         @input="(e) => patchGeomStroke({ width: parseInt((e.target as HTMLInputElement).value, 10) || 0 })">
                </label>
                <label class="flex flex-col gap-0.5">
                  <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.properties.strokeColor }}</span>
                  <ColorInput :model-value="geomStroke()!.color"
                              @update:model-value="(v) => patchGeomStroke({ color: v })" />
                </label>
              </div>
            </template>
            <!-- Shape 专属：kind / sides / innerRatio -->
            <template v-if="isShape">
              <div class="grid grid-cols-2 gap-2">
                <label class="flex flex-col gap-0.5">
                  <span class="text-[10px] text-[color:var(--muted-foreground)]">kind</span>
                  <select class="hc-input" :value="(selected as ShapeElement).kind"
                          @change="(e) => onSelectChange('kind', e)">
                    <option value="polygon">polygon</option>
                    <option value="star">star</option>
                  </select>
                </label>
                <label class="flex flex-col gap-0.5">
                  <span class="text-[10px] text-[color:var(--muted-foreground)]">sides</span>
                  <input type="number" min="3" max="32" class="hc-input"
                         :value="(selected as ShapeElement).sides"
                         @input="(e) => onNumberChange('sides', e)">
                </label>
              </div>
              <label v-if="(selected as ShapeElement).kind === 'star'" class="flex flex-col gap-0.5">
                <span class="text-[10px] text-[color:var(--muted-foreground)]">innerRatio</span>
                <input type="range" min="0.1" max="0.95" step="0.05"
                       :value="(selected as ShapeElement).innerRatio ?? 0.5"
                       @input="(e) => onNumberChange('innerRatio', e)">
              </label>
            </template>
            <!-- dither toggle -->
            <Tooltip :text="t.fill.ditherTip">
              <label class="flex items-center justify-between gap-2 cursor-help">
                <span class="text-[color:var(--muted-foreground)]">{{ t.fill.ditherLabel }}</span>
                <input type="checkbox" :checked="isDither" @change="onDitherChange">
              </label>
            </Tooltip>
          </div>
        </details>

        <!-- Text 专属 -->
        <details v-if="isText" class="group" open>
          <summary class="cursor-pointer select-none text-[color:var(--muted-foreground)] uppercase tracking-wider text-[10px] py-1 hover:text-[color:var(--foreground)]">
            {{ t.properties.textHeader }}
          </summary>
          <div class="pt-1.5 space-y-2">
            <label class="flex flex-col gap-0.5">
              <span class="text-[10px] text-[color:var(--muted-foreground)]">text</span>
              <textarea rows="2" class="hc-input font-mono resize-none" :value="(selected as TextElement).text"
                        @input="(e) => onTextChange('text', e)"></textarea>
            </label>
            <!-- Fit content：按当前 text + 字号 + letterSpacing + lineHeight 计算 bbox -->
            <div class="flex gap-2 pt-1">
              <Tooltip :text="t.properties.fitHeightTip">
                <button
                  type="button"
                  class="flex-1 px-2 py-1 text-[11px] rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] flex items-center justify-center gap-1"
                  @click="fitTextHeight"
                >
                  <Maximize2 class="size-3 rotate-90" />
                  <span>{{ t.properties.fitHeight }}</span>
                </button>
              </Tooltip>
              <Tooltip :text="t.properties.fitWidthTip">
                <button
                  type="button"
                  class="flex-1 px-2 py-1 text-[11px] rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] flex items-center justify-center gap-1"
                  @click="fitTextWidth"
                >
                  <Maximize2 class="size-3" />
                  <span>{{ t.properties.fitWidth }}</span>
                </button>
              </Tooltip>
            </div>
            <div class="grid grid-cols-2 gap-2">
              <label class="flex flex-col gap-0.5">
                <span class="text-[10px] text-[color:var(--muted-foreground)]">fontId</span>
                <select class="hc-input" :value="(selected as TextElement).fontId" @change="(e) => onSelectChange('fontId', e)">
                  <option v-for="(meta, id) in FONT_META" :key="id" :value="id">{{ meta.displayName }}</option>
                </select>
              </label>
              <label class="flex flex-col gap-0.5">
                <span class="text-[10px] text-[color:var(--muted-foreground)]">fontSize</span>
                <input type="number" min="1" class="hc-input" :value="(selected as TextElement).fontSize"
                       @input="(e) => onNumberChange('fontSize', e)">
              </label>
              <label class="flex flex-col gap-0.5">
                <span class="text-[10px] text-[color:var(--muted-foreground)]">align</span>
                <select class="hc-input" :value="(selected as TextElement).align" @change="(e) => onSelectChange('align', e)">
                  <option value="left">left</option>
                  <option value="center">center</option>
                  <option value="right">right</option>
                </select>
              </label>
              <label class="flex flex-col gap-0.5">
                <span class="text-[10px] text-[color:var(--muted-foreground)]">color</span>
                <ColorInput :model-value="(selected as TextElement).color"
                            @update:model-value="(v) => sendUpdate({ color: v })" />
              </label>
              <label class="flex flex-col gap-0.5">
                <span class="hc-field-label">
                  {{ t.properties.letterSpacing }}
                  <Tooltip :text="t.properties.letterSpacingTip">
                    <HelpCircle class="size-2.5 opacity-50 hover:opacity-100 inline" />
                  </Tooltip>
                </span>
                <input type="number" step="0.5" class="hc-input" :value="(selected as TextElement).letterSpacing"
                       @input="(e) => onNumberChange('letterSpacing', e)">
              </label>
              <label class="flex flex-col gap-0.5">
                <span class="hc-field-label">
                  {{ t.properties.lineHeight }}
                  <Tooltip :text="t.properties.lineHeightTip">
                    <HelpCircle class="size-2.5 opacity-50 hover:opacity-100 inline" />
                  </Tooltip>
                </span>
                <input type="number" step="0.1" class="hc-input" :value="(selected as TextElement).lineHeight"
                       @input="(e) => onNumberChange('lineHeight', e)">
              </label>
            </div>
            <label class="flex items-center gap-1.5 pt-1">
              <input type="checkbox" :checked="(selected as TextElement).vertical"
                     @change="(e) => sendUpdate({ vertical: (e.target as HTMLInputElement).checked })">
              <span>{{ t.properties.verticalHelp }}</span>
            </label>
          </div>
        </details>

        <!-- Text effects -->
        <details v-if="isText" class="group">
          <summary class="cursor-pointer select-none text-[color:var(--muted-foreground)] uppercase tracking-wider text-[10px] py-1 hover:text-[color:var(--foreground)]">
            {{ t.properties.effectsHeader }}
          </summary>
          <div class="pt-1.5 space-y-3">
            <!-- stroke -->
            <div>
              <label class="flex items-center justify-between">
                <span>stroke</span>
                <input type="checkbox" :checked="!!textEffects().stroke" @change="toggleStroke">
              </label>
              <div v-if="textEffects().stroke" class="grid grid-cols-2 gap-2 pt-1.5">
                <label class="flex flex-col gap-0.5">
                  <span class="text-[10px] text-[color:var(--muted-foreground)]">width</span>
                  <input type="number" min="0" class="hc-input" :value="textEffects().stroke!.width"
                         @input="(e) => patchStroke({ width: parseInt((e.target as HTMLInputElement).value, 10) || 0 })">
                </label>
                <label class="flex flex-col gap-0.5">
                  <span class="text-[10px] text-[color:var(--muted-foreground)]">color</span>
                  <ColorInput :model-value="textEffects().stroke!.color"
                              @update:model-value="(v) => patchStroke({ color: v })" />
                </label>
              </div>
            </div>
            <!-- shadow -->
            <div>
              <label class="flex items-center justify-between">
                <span>shadow</span>
                <input type="checkbox" :checked="!!textEffects().shadow" @change="toggleShadow">
              </label>
              <div v-if="textEffects().shadow" class="grid grid-cols-3 gap-2 pt-1.5">
                <label class="flex flex-col gap-0.5">
                  <span class="text-[10px] text-[color:var(--muted-foreground)]">dx</span>
                  <input type="number" class="hc-input" :value="textEffects().shadow!.dx"
                         @input="(e) => patchShadow({ dx: parseInt((e.target as HTMLInputElement).value, 10) || 0 })">
                </label>
                <label class="flex flex-col gap-0.5">
                  <span class="text-[10px] text-[color:var(--muted-foreground)]">dy</span>
                  <input type="number" class="hc-input" :value="textEffects().shadow!.dy"
                         @input="(e) => patchShadow({ dy: parseInt((e.target as HTMLInputElement).value, 10) || 0 })">
                </label>
                <label class="flex flex-col gap-0.5">
                  <span class="text-[10px] text-[color:var(--muted-foreground)]">color</span>
                  <ColorInput :model-value="textEffects().shadow!.color"
                              @update:model-value="(v) => patchShadow({ color: v })" />
                </label>
              </div>
            </div>
            <!-- glow -->
            <div>
              <label class="flex items-center justify-between">
                <span>glow</span>
                <input type="checkbox" :checked="!!textEffects().glow" @change="toggleGlow">
              </label>
              <div v-if="textEffects().glow" class="grid grid-cols-2 gap-2 pt-1.5">
                <label class="flex flex-col gap-0.5">
                  <span class="text-[10px] text-[color:var(--muted-foreground)]">radius</span>
                  <input type="number" min="0" max="64" class="hc-input" :value="textEffects().glow!.radius"
                         @input="(e) => patchGlow({ radius: parseInt((e.target as HTMLInputElement).value, 10) || 0 })">
                </label>
                <label class="flex flex-col gap-0.5">
                  <span class="text-[10px] text-[color:var(--muted-foreground)]">color</span>
                  <ColorInput :model-value="textEffects().glow!.color"
                              @update:model-value="(v) => patchGlow({ color: v })" />
                </label>
              </div>
            </div>
          </div>
        </details>
      </div>
    </section>

    <!-- Elements（当前活动层内的元素列表）-->
    <section class="flex flex-col border-t border-[color:var(--border)] max-h-[35%] min-h-[100px]">
      <header class="flex items-center gap-2 px-3 h-9 border-b border-[color:var(--border)] text-xs font-medium uppercase tracking-wider text-[color:var(--muted-foreground)]">
        <Layers class="size-3.5" />
        <span>{{ t.elements.header }}</span>
        <span class="ml-auto text-[10px] font-normal normal-case">{{ t.elements.count(elementCount) }}</span>
      </header>
      <div v-if="activeLayerLocked" class="px-3 py-1.5 text-[10px] text-[color:var(--muted-foreground)] bg-[color:var(--muted)] border-b border-[color:var(--border)]">
        {{ t.elements.lockedHint }}
      </div>
      <ul class="overflow-y-auto flex-1">
        <li v-if="elementCount === 0" class="p-3 text-xs text-[color:var(--muted-foreground)]">
          {{ activeLayerLocked ? t.elements.emptyLocked : t.elements.empty }}
        </li>
        <li
          v-for="(el, idx) in project.state?.elements ?? []"
          :key="el.id"
          :draggable="!activeLayerLocked"
          class="px-3 py-1.5 flex items-center gap-2 text-xs cursor-pointer hover:bg-[color:var(--accent)] transition-colors"
          :class="{
            'bg-[color:var(--accent)]': ui.isSelected(el.id),
            'opacity-50': dragIdx === idx,
            'ring-1 ring-[color:var(--ring)] ring-inset': dragOverIdx === idx && dragIdx !== idx,
            'cursor-not-allowed': activeLayerLocked,
          }"
          @click="(e) => (e.shiftKey || e.metaKey || e.ctrlKey) ? ui.toggleSelection(el.id) : ui.selectElement(el.id)"
          @dragstart="(e) => onElementDragStart(e, idx)"
          @dragover="(e) => onElementDragOver(e, idx)"
          @dragleave="onElementDragLeave"
          @drop="(e) => onElementDrop(e, idx)"
          @dragend="onElementDragEnd"
        >
          <span class="w-5 text-[10px] text-[color:var(--muted-foreground)] tabular-nums">{{ idx }}</span>
          <span class="flex-1 truncate">
            {{ el.type }}
            <span v-if="el.type === 'text'" class="opacity-60">· "{{ (el as any).text }}"</span>
          </span>
          <button
            class="p-0.5 rounded hover:bg-[color:var(--background)] disabled:opacity-30 disabled:cursor-not-allowed"
            :title="activeLayerLocked ? t.elements.lockedHint : t.elements.toggleVisible(el.visible)"
            :disabled="activeLayerLocked"
            @click.stop="ws.send('element.update', { elementId: el.id, patch: { visible: !el.visible } }); (el as any).visible = !el.visible;"
          >
            <component :is="el.visible ? Eye : EyeOff" class="size-3" :class="el.visible ? '' : 'opacity-40'" />
          </button>
          <button
            class="p-0.5 rounded hover:bg-[color:var(--background)] disabled:opacity-30 disabled:cursor-not-allowed"
            :title="activeLayerLocked ? t.elements.lockedHint : t.elements.toggleLock(el.locked)"
            :disabled="activeLayerLocked"
            @click.stop="ws.send('element.update', { elementId: el.id, patch: { locked: !el.locked } }); (el as any).locked = !el.locked;"
          >
            <component :is="el.locked ? Lock : Unlock" class="size-3" :class="el.locked ? '' : 'opacity-40'" />
          </button>
        </li>
      </ul>
    </section>
    </template> <!-- M12-D：v-else 结束（Properties 块只在非 brush 工具显示） -->
  </aside>
</template>

<style scoped>
/* 手写样式（Tailwind 4 scoped style 不支持 @apply，改直接 CSS）。 */
.hc-field-label {
    font-size: 10px;
    color: var(--muted-foreground);
    display: inline-flex;
    align-items: center;
    gap: 3px;
}
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
.hc-color {
    width: 100%;
    height: 1.5rem;
    border-radius: 4px;
    border: 1px solid var(--border);
    cursor: pointer;
    padding: 0;
    background: transparent;
}
.hc-elem-slider {
    height: 14px;
    background: transparent;
    accent-color: var(--ring);
}
textarea.hc-input {
    min-height: 2.5rem;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    resize: none;
}
/* 2026-05-14 lock-state：locked 时整栏禁用编辑。pointer-events: none 完全屏蔽点击 / 输入 / 拖拽；
   opacity 60% 提供视觉反馈让用户知道控件不可用。 */
.hc-readonly-panel section,
.hc-readonly-panel :deep(.layer-panel) {
    pointer-events: none;
    opacity: 0.6;
}
</style>
