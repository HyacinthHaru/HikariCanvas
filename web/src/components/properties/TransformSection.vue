<script setup lang="ts">
/**
 * 通用 transform 段：x / y / w / h / rotation / visible / locked / opacity /
 * blendMode / renderMode。所有 Element 类型都用。
 *
 * 设计：
 * - props.element：当前元素（任意子类型）
 * - props.locked：wall 锁定时为 true（顶层 .hc-readonly-panel 已经禁用 pointer-events，
 *   这里不再额外处理，但保留参数以便未来子组件按需细化交互）
 * - emits update / updateDebounced：分别用于"定型"变更 vs 防抖输入；父组件转 ws.send
 *
 * opacity slider 的本地 draft 缓冲在自己内部维护，切换元素时 watch reset。
 */
import { computed, ref, watch } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { HelpCircle } from 'lucide-vue-next';
import Tooltip from '@/components/ui/Tooltip.vue';
import { useI18n } from '@/i18n';
import type { Element } from '@/types/protocol';

interface Props {
    element: Element;
    locked: boolean;
}
const props = defineProps<Props>();
const emit = defineEmits<{
    update: [patch: Record<string, unknown>];
    updateDebounced: [patch: Record<string, unknown>];
}>();

const { t } = useI18n();

function onBoolChange(field: 'visible' | 'locked', ev: Event) {
    const v = (ev.target as HTMLInputElement).checked;
    emit('update', { [field]: v });
}

function onNumberChange(field: string, ev: Event) {
    let v = parseFloat((ev.target as HTMLInputElement).value);
    if (!Number.isFinite(v)) return;
    if (field === 'rotation') v = ((Math.round(v) % 360) + 360) % 360;
    emit('updateDebounced', { [field]: v });
}

// ---------- M8-E：element opacity slider ----------

const opacityDraftPct = ref<number | null>(null);
watch(() => props.element.id, () => { opacityDraftPct.value = null; });

const opacityPct = computed(() => {
    if (opacityDraftPct.value !== null) return opacityDraftPct.value;
    const op = props.element.opacity;
    return op === undefined || op === null ? 100 : Math.round(op * 100);
});

const sendOpacityDebounced = useDebounceFn((opacity: number) => {
    emit('update', { opacity });
}, 80);

function onOpacityInput(ev: Event): void {
    const v = parseInt((ev.target as HTMLInputElement).value, 10);
    if (!Number.isFinite(v)) return;
    opacityDraftPct.value = v;
    const opacity = v / 100;
    (props.element as unknown as Record<string, unknown>).opacity = opacity;
    sendOpacityDebounced(opacity);
}

function onOpacityChange(): void {
    opacityDraftPct.value = null;
    const op = (props.element as { opacity?: number }).opacity ?? 1;
    emit('update', { opacity: op });
}
</script>

<template>
  <details class="group" open>
    <summary class="cursor-pointer select-none text-[color:var(--muted-foreground)] uppercase tracking-wider text-[10px] py-1 hover:text-[color:var(--foreground)]">
      {{ t.properties.transformHeader }}
    </summary>
    <div class="grid grid-cols-2 gap-2 pt-1.5">
      <label class="flex flex-col gap-0.5">
        <span class="text-[10px] text-[color:var(--muted-foreground)]">x</span>
        <input type="number" class="hc-input" :value="element.x" @input="(e) => onNumberChange('x', e)">
      </label>
      <label class="flex flex-col gap-0.5">
        <span class="text-[10px] text-[color:var(--muted-foreground)]">y</span>
        <input type="number" class="hc-input" :value="element.y" @input="(e) => onNumberChange('y', e)">
      </label>
      <label class="flex flex-col gap-0.5">
        <span class="text-[10px] text-[color:var(--muted-foreground)]">w</span>
        <input type="number" min="1" class="hc-input" :value="element.w" @input="(e) => onNumberChange('w', e)">
      </label>
      <label class="flex flex-col gap-0.5">
        <span class="text-[10px] text-[color:var(--muted-foreground)]">h</span>
        <input type="number" min="1" class="hc-input" :value="element.h" @input="(e) => onNumberChange('h', e)">
      </label>
      <label class="flex flex-col gap-0.5 col-span-2">
        <span class="hc-field-label">
          {{ t.properties.rotation }}
          <Tooltip :text="t.properties.rotationTip">
            <HelpCircle class="size-2.5 opacity-50 hover:opacity-100 inline" />
          </Tooltip>
        </span>
        <input type="number" min="0" max="359" class="hc-input" :value="element.rotation"
               @input="(e) => onNumberChange('rotation', e)">
      </label>
    </div>
    <div class="flex gap-3 pt-2">
      <label class="flex items-center gap-1.5">
        <input type="checkbox" :checked="element.visible" @change="(e) => onBoolChange('visible', e)">
        <span>{{ t.properties.visible }}</span>
      </label>
      <label class="flex items-center gap-1.5">
        <input type="checkbox" :checked="element.locked" @change="(e) => onBoolChange('locked', e)">
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
        <select class="hc-input opacity-60 cursor-not-allowed" :value="element.blendMode ?? 'normal'" disabled>
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
        <select class="hc-input opacity-60 cursor-not-allowed" :value="element.renderMode ?? 'clean'" disabled>
          <option value="clean">{{ t.properties.renderModeClean }}</option>
          <option value="dither">{{ t.properties.renderModeDither }}</option>
        </select>
      </label>
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
.hc-elem-slider {
    height: 14px;
    background: transparent;
    accent-color: var(--ring);
}
</style>
