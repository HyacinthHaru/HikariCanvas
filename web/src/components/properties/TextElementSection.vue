<script setup lang="ts">
/**
 * Text 元素专属段：content / fontId / fontSize / align / color / letterSpacing /
 * lineHeight / vertical + Effects（stroke / shadow / glow）+ fit-content 按钮。
 *
 * 父组件传入 element + locked，子组件直接 mutate element 做 optimistic（与原 RightPanel
 * 行为一致），通过 emit('update' | 'updateDebounced') 让父去发 ws op。
 */
import { useDebounceFn } from '@vueuse/core';
import { HelpCircle, Maximize2 } from 'lucide-vue-next';
import Tooltip from '@/components/ui/Tooltip.vue';
import ColorInput from '@/components/ui/ColorInput.vue';
import { layoutText, charAdvance, ASCENT_RATIO } from '@/render/TextLayout';
import { FONT_META } from '@/render/PreviewRenderer';
import { useI18n } from '@/i18n';
import type { TextElement, Effects, Stroke, Shadow, Glow } from '@/types/protocol';

interface Props {
    element: TextElement;
    locked: boolean;
}
const props = defineProps<Props>();
const emit = defineEmits<{
    update: [patch: Record<string, unknown>];
    updateDebounced: [patch: Record<string, unknown>];
}>();

const { t } = useI18n();

function onTextChange(field: string, ev: Event) {
    const v = (ev.target as HTMLInputElement | HTMLTextAreaElement).value;
    // eager optimistic：文本类输入立即更新 local element，下面的 fitTextHeight 能读到最新内容
    (props.element as unknown as Record<string, unknown>)[field] = v;
    emit('updateDebounced', { [field]: v });
    if (field === 'text') autoFitHeightDebounced();
}

const autoFitHeightDebounced = useDebounceFn(() => {
    fitTextHeight();
}, 250);

function onNumberChange(field: string, ev: Event) {
    const v = parseFloat((ev.target as HTMLInputElement).value);
    if (!Number.isFinite(v)) return;
    emit('updateDebounced', { [field]: v });
}

function onSelectChange(field: string, ev: Event) {
    const v = (ev.target as HTMLSelectElement).value;
    emit('update', { [field]: v });
}

// ---------- M5-D3 P4：文本 fit-content ----------

function fitTextHeight() {
    const te = props.element;
    const glyphs = layoutText(te);
    if (glyphs.length === 0) return;
    let maxBaselineY = te.y;
    for (const g of glyphs) {
        if (g.baselineY > maxBaselineY) maxBaselineY = g.baselineY;
    }
    const ascent = Math.round(te.fontSize * ASCENT_RATIO);
    const descent = Math.max(1, te.fontSize - ascent);
    const newH = Math.max(1, (maxBaselineY + descent) - te.y);
    if (newH !== te.h) emit('update', { h: newH });
}

function fitTextWidth() {
    const te = props.element;
    const glyphs = layoutText(te);
    if (glyphs.length === 0) return;
    let maxRight = te.x;
    for (const g of glyphs) {
        const w = g.rotated ? te.fontSize : charAdvance(te.fontId, g.ch, te.fontSize);
        const right = g.x + w;
        if (right > maxRight) maxRight = right;
    }
    const newW = Math.max(1, maxRight - te.x);
    if (newW !== te.w) emit('update', { w: newW });
}

// ---------- Effects ----------

function textEffects(): Effects {
    return props.element.effects ?? {};
}

function updateEffects(patch: Partial<Effects>) {
    const merged: Effects = { ...(props.element.effects ?? {}), ...patch };
    const clean: Effects | null = (merged.stroke || merged.shadow || merged.glow) ? merged : null;
    emit('update', { effects: clean });
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
</script>

<template>
  <!-- Text 主段 -->
  <details class="group" open>
    <summary class="cursor-pointer select-none text-[color:var(--muted-foreground)] uppercase tracking-wider text-[10px] py-1 hover:text-[color:var(--foreground)]">
      {{ t.properties.textHeader }}
    </summary>
    <div class="pt-1.5 space-y-2">
      <label class="flex flex-col gap-0.5">
        <span class="text-[10px] text-[color:var(--muted-foreground)]">text</span>
        <textarea rows="2" class="hc-input font-mono resize-none" :value="element.text"
                  @input="(e) => onTextChange('text', e)"></textarea>
      </label>
      <!-- Fit content -->
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
          <select class="hc-input" :value="element.fontId" @change="(e) => onSelectChange('fontId', e)">
            <option v-for="(meta, id) in FONT_META" :key="id" :value="id">{{ meta.displayName }}</option>
          </select>
        </label>
        <label class="flex flex-col gap-0.5">
          <span class="text-[10px] text-[color:var(--muted-foreground)]">fontSize</span>
          <input type="number" min="1" class="hc-input" :value="element.fontSize"
                 @input="(e) => onNumberChange('fontSize', e)">
        </label>
        <label class="flex flex-col gap-0.5">
          <span class="text-[10px] text-[color:var(--muted-foreground)]">align</span>
          <select class="hc-input" :value="element.align" @change="(e) => onSelectChange('align', e)">
            <option value="left">left</option>
            <option value="center">center</option>
            <option value="right">right</option>
          </select>
        </label>
        <label class="flex flex-col gap-0.5">
          <span class="text-[10px] text-[color:var(--muted-foreground)]">color</span>
          <ColorInput :model-value="element.color"
                      @update:model-value="(v) => emit('update', { color: v })" />
        </label>
        <label class="flex flex-col gap-0.5">
          <span class="hc-field-label">
            {{ t.properties.letterSpacing }}
            <Tooltip :text="t.properties.letterSpacingTip">
              <HelpCircle class="size-2.5 opacity-50 hover:opacity-100 inline" />
            </Tooltip>
          </span>
          <input type="number" step="0.5" class="hc-input" :value="element.letterSpacing"
                 @input="(e) => onNumberChange('letterSpacing', e)">
        </label>
        <label class="flex flex-col gap-0.5">
          <span class="hc-field-label">
            {{ t.properties.lineHeight }}
            <Tooltip :text="t.properties.lineHeightTip">
              <HelpCircle class="size-2.5 opacity-50 hover:opacity-100 inline" />
            </Tooltip>
          </span>
          <input type="number" step="0.1" class="hc-input" :value="element.lineHeight"
                 @input="(e) => onNumberChange('lineHeight', e)">
        </label>
      </div>
      <label class="flex items-center gap-1.5 pt-1">
        <input type="checkbox" :checked="element.vertical"
               @change="(e) => emit('update', { vertical: (e.target as HTMLInputElement).checked })">
        <span>{{ t.properties.verticalHelp }}</span>
      </label>
    </div>
  </details>

  <!-- Effects 段（stroke / shadow / glow） -->
  <details class="group">
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
</template>

<style scoped>
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
textarea.hc-input {
    min-height: 2.5rem;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    resize: none;
}
</style>
