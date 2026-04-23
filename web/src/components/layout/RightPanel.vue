<script setup lang="ts">
import { computed, ref } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { getWsClient } from '@/network/wsClient';
import { Layers, Sliders, Eye, EyeOff, Lock, Unlock, Maximize2 } from 'lucide-vue-next';
import { layoutText, canonicalCharWidth, ASCENT_RATIO } from '@/render/TextLayout';
import { useI18n } from '@/i18n';
import type { Element, RectElement, TextElement, Effects, Stroke, Shadow, Glow } from '@/types/protocol';

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

const elementCount = computed(() => project.state?.elements.length ?? 0);

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

/** 200ms 防抖（用于文字 / 数值输入）。 */
const sendUpdateDebounced = useDebounceFn(sendUpdate, 200);

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
    sendUpdateDebounced({ [field]: v });
}

function onColorChange(field: string, ev: Event) {
    const v = (ev.target as HTMLInputElement).value.toUpperCase();
    sendUpdate({ [field]: v });
}

function onNumberChange(field: string, ev: Event) {
    const v = parseFloat((ev.target as HTMLInputElement).value);
    if (!Number.isFinite(v)) return;
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

// ---------- Rect stroke（独立于 text effects.stroke） ----------

function rectStroke(): Stroke | null {
    return (selected.value as RectElement | null)?.stroke ?? null;
}
function toggleRectStroke(ev: Event) {
    const on = (ev.target as HTMLInputElement).checked;
    sendUpdate({ stroke: on ? { width: 1, color: '#000000' } : null });
}
function patchRectStroke(partial: Partial<Stroke>) {
    const cur = rectStroke() ?? { width: 1, color: '#000000' };
    sendUpdate({ stroke: { ...cur, ...partial } });
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

// ---------- Layer reorder（HTML5 drag & drop） ----------

const dragIdx = ref(-1);
const dragOverIdx = ref(-1);

function onLayerDragStart(ev: DragEvent, idx: number) {
    dragIdx.value = idx;
    if (ev.dataTransfer) {
        ev.dataTransfer.effectAllowed = 'move';
        ev.dataTransfer.setData('text/plain', String(idx));
    }
}

function onLayerDragOver(ev: DragEvent, idx: number) {
    ev.preventDefault();
    dragOverIdx.value = idx;
    if (ev.dataTransfer) ev.dataTransfer.dropEffect = 'move';
}

function onLayerDragLeave() {
    dragOverIdx.value = -1;
}

function onLayerDrop(ev: DragEvent, idx: number) {
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

function onLayerDragEnd() {
    dragIdx.value = -1;
    dragOverIdx.value = -1;
}
</script>

<template>
  <aside class="w-72 bg-[color:var(--card)] border-l border-[color:var(--border)] flex flex-col">
    <!-- Properties -->
    <section class="flex-1 overflow-y-auto">
      <header class="flex items-center gap-2 px-3 h-9 border-b border-[color:var(--border)] text-xs font-medium uppercase tracking-wider text-[color:var(--muted-foreground)]">
        <Sliders class="size-3.5" />
        <span>{{ t.properties.header }}</span>
      </header>

      <div v-if="!selected" class="p-3 text-xs text-[color:var(--muted-foreground)]">
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
              <span class="text-[10px] text-[color:var(--muted-foreground)]">rotation</span>
              <select class="hc-input" :value="selected.rotation" @change="(e) => onSelectChange('rotation', e)">
                <option :value="0">0°</option>
                <option :value="90">90°</option>
                <option :value="180">180°</option>
                <option :value="270">270°</option>
              </select>
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
        </details>

        <!-- Rect 专属 -->
        <details v-if="isRect" class="group" open>
          <summary class="cursor-pointer select-none text-[color:var(--muted-foreground)] uppercase tracking-wider text-[10px] py-1 hover:text-[color:var(--foreground)]">
            {{ t.properties.rectHeader }}
          </summary>
          <div class="pt-1.5 space-y-2">
            <label class="flex items-center justify-between gap-2">
              <span class="text-[color:var(--muted-foreground)]">{{ t.properties.fill }}</span>
              <div class="flex items-center gap-1">
                <input type="checkbox" :checked="(selected as RectElement).fill !== undefined && (selected as RectElement).fill !== null"
                       @change="(e: Event) => sendUpdate({ fill: (e.target as HTMLInputElement).checked ? (selected as RectElement).fill ?? '#FF3366' : null })">
                <input type="color" class="hc-color" :value="(selected as RectElement).fill ?? '#FF3366'"
                       @input="(e) => onColorChange('fill', e)" :disabled="!((selected as RectElement).fill)">
              </div>
            </label>
            <label class="flex items-center justify-between gap-2">
              <span class="text-[color:var(--muted-foreground)]">{{ t.properties.stroke }}</span>
              <input type="checkbox" :checked="rectStroke() !== null" @change="toggleRectStroke">
            </label>
            <template v-if="rectStroke()">
              <div class="grid grid-cols-2 gap-2">
                <label class="flex flex-col gap-0.5">
                  <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.properties.strokeWidth }}</span>
                  <input type="number" min="0" class="hc-input" :value="rectStroke()!.width"
                         @input="(e) => patchRectStroke({ width: parseInt((e.target as HTMLInputElement).value, 10) || 0 })">
                </label>
                <label class="flex flex-col gap-0.5">
                  <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.properties.strokeColor }}</span>
                  <input type="color" class="hc-color h-7" :value="rectStroke()!.color"
                         @input="(e) => patchRectStroke({ color: (e.target as HTMLInputElement).value.toUpperCase() })">
                </label>
              </div>
            </template>
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
              <button
                type="button"
                class="flex-1 px-2 py-1 text-[11px] rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] flex items-center justify-center gap-1"
                :title="t.properties.fitHeight"
                @click="fitTextHeight"
              >
                <Maximize2 class="size-3 rotate-90" />
                <span>{{ t.properties.fitHeight }}</span>
              </button>
              <button
                type="button"
                class="flex-1 px-2 py-1 text-[11px] rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] flex items-center justify-center gap-1"
                :title="t.properties.fitWidth"
                @click="fitTextWidth"
              >
                <Maximize2 class="size-3" />
                <span>{{ t.properties.fitWidth }}</span>
              </button>
            </div>
            <div class="grid grid-cols-2 gap-2">
              <label class="flex flex-col gap-0.5">
                <span class="text-[10px] text-[color:var(--muted-foreground)]">fontId</span>
                <select class="hc-input" :value="(selected as TextElement).fontId" @change="(e) => onSelectChange('fontId', e)">
                  <option value="ark_pixel">ark_pixel</option>
                  <option value="source_han_sans">source_han_sans</option>
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
                <input type="color" class="hc-color h-7" :value="(selected as TextElement).color"
                       @input="(e) => onColorChange('color', e)">
              </label>
              <label class="flex flex-col gap-0.5">
                <span class="text-[10px] text-[color:var(--muted-foreground)]">letterSpacing</span>
                <input type="number" step="0.5" class="hc-input" :value="(selected as TextElement).letterSpacing"
                       @input="(e) => onNumberChange('letterSpacing', e)">
              </label>
              <label class="flex flex-col gap-0.5">
                <span class="text-[10px] text-[color:var(--muted-foreground)]">lineHeight</span>
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
                  <input type="color" class="hc-color h-7" :value="textEffects().stroke!.color"
                         @input="(e) => patchStroke({ color: (e.target as HTMLInputElement).value.toUpperCase() })">
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
                  <input type="color" class="hc-color h-7" :value="textEffects().shadow!.color"
                         @input="(e) => patchShadow({ color: (e.target as HTMLInputElement).value.toUpperCase() })">
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
                  <input type="color" class="hc-color h-7" :value="textEffects().glow!.color"
                         @input="(e) => patchGlow({ color: (e.target as HTMLInputElement).value.toUpperCase() })">
                </label>
              </div>
            </div>
          </div>
        </details>
      </div>
    </section>

    <!-- Layers -->
    <section class="flex flex-col border-t border-[color:var(--border)] max-h-[40%]">
      <header class="flex items-center gap-2 px-3 h-9 border-b border-[color:var(--border)] text-xs font-medium uppercase tracking-wider text-[color:var(--muted-foreground)]">
        <Layers class="size-3.5" />
        <span>{{ t.layers.header }}</span>
        <span class="ml-auto text-[10px] font-normal normal-case">{{ t.layers.count(elementCount) }}</span>
      </header>
      <ul class="overflow-y-auto flex-1">
        <li v-if="elementCount === 0" class="p-3 text-xs text-[color:var(--muted-foreground)]">
          {{ t.layers.empty }}
        </li>
        <li
          v-for="(el, idx) in project.state?.elements ?? []"
          :key="el.id"
          draggable="true"
          class="px-3 py-1.5 flex items-center gap-2 text-xs cursor-pointer hover:bg-[color:var(--accent)] transition-colors"
          :class="{
            'bg-[color:var(--accent)]': ui.selectedElementId === el.id,
            'opacity-50': dragIdx === idx,
            'ring-1 ring-[color:var(--ring)] ring-inset': dragOverIdx === idx && dragIdx !== idx,
          }"
          @click="ui.selectElement(el.id)"
          @dragstart="(e) => onLayerDragStart(e, idx)"
          @dragover="(e) => onLayerDragOver(e, idx)"
          @dragleave="onLayerDragLeave"
          @drop="(e) => onLayerDrop(e, idx)"
          @dragend="onLayerDragEnd"
        >
          <span class="w-5 text-[10px] text-[color:var(--muted-foreground)] tabular-nums">{{ idx }}</span>
          <span class="flex-1 truncate">
            {{ el.type }}
            <span v-if="el.type === 'text'" class="opacity-60">· "{{ (el as any).text }}"</span>
          </span>
          <button
            class="p-0.5 rounded hover:bg-[color:var(--background)]"
            :title="t.layers.toggleVisible(el.visible)"
            @click.stop="ws.send('element.update', { elementId: el.id, patch: { visible: !el.visible } }); (el as any).visible = !el.visible;"
          >
            <component :is="el.visible ? Eye : EyeOff" class="size-3" :class="el.visible ? '' : 'opacity-40'" />
          </button>
          <button
            class="p-0.5 rounded hover:bg-[color:var(--background)]"
            :title="t.layers.toggleLock(el.locked)"
            @click.stop="ws.send('element.update', { elementId: el.id, patch: { locked: !el.locked } }); (el as any).locked = !el.locked;"
          >
            <component :is="el.locked ? Lock : Unlock" class="size-3" :class="el.locked ? '' : 'opacity-40'" />
          </button>
        </li>
      </ul>
    </section>
  </aside>
</template>

<style scoped>
/* 手写样式（Tailwind 4 scoped style 不支持 @apply，改直接 CSS）。 */
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
textarea.hc-input {
    min-height: 2.5rem;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    resize: none;
}
</style>
