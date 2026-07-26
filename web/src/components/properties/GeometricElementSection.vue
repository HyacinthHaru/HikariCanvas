<script setup lang="ts">
/**
 * 几何元素族（rect / circle / shape / path）共用段：
 * - fill：toggle + FillInput（solid / linear / radial）
 * - stroke：toggle + width + color
 * - Shape 专属：kind / sides / innerRatio
 * - dither toggle
 *
 * isRect / isCircle / isShape / isPath 由父组件根据 element.type 推；
 * 这里仅按 element.type 自己再判一次决定 header 文案与 Shape 专属字段显隐。
 */
import { computed } from 'vue';
import Tooltip from '@/components/ui/Tooltip.vue';
import ColorInput from '@/components/ui/ColorInput.vue';
import FillInput from '@/components/ui/FillInput.vue';
import { useI18n } from '@/i18n';
import { MAX_STROKE_WIDTH, clampNumber } from '@/constants/elementLimits';
import type { RectElement, CircleElement, ShapeElement, PathElement, Stroke, Fill } from '@/types/protocol';

type GeometricElement = RectElement | CircleElement | ShapeElement | PathElement;

interface Props {
    element: GeometricElement;
    locked: boolean;
}
const props = defineProps<Props>();
const emit = defineEmits<{
    update: [patch: Record<string, unknown>];
    updateDebounced: [patch: Record<string, unknown>];
}>();

const { t } = useI18n();

const isRect = computed(() => props.element.type === 'rect');
const isCircle = computed(() => props.element.type === 'circle');
const isShape = computed(() => props.element.type === 'shape');
const isDither = computed(() => props.element.renderMode === 'dither');

function geomStroke(): Stroke | null {
    return props.element.stroke ?? null;
}
function toggleGeomStroke(ev: Event) {
    const on = (ev.target as HTMLInputElement).checked;
    emit('update', { stroke: on ? { width: 1, color: '#000000' } : null });
}
function patchGeomStroke(partial: Partial<Stroke>) {
    const cur = geomStroke() ?? { width: 1, color: '#000000' };
    const next = { ...cur, ...partial };
    // 宽度夹到后端范围内。输入框的 min="0" 挡不住手输负数，`parseInt(...) || 0` 也照样放行
    // 负值；超界这一帧会被后端整条拒收，而本地已经乐观改过，编辑器和游戏里就此对不上。
    next.width = Math.round(clampNumber(next.width, 0, MAX_STROKE_WIDTH));
    emit('update', { stroke: next });
}
function toggleGeomFill(ev: Event) {
    const on = (ev.target as HTMLInputElement).checked;
    emit('update', { fill: on ? { type: 'solid', color: '#FF3366' } : null });
}
function geomFill(): Fill | undefined {
    const raw = props.element.fill;
    if (raw === undefined || raw === null) return undefined;
    if (typeof raw === 'string') return { type: 'solid', color: raw };
    return raw;
}
function onDitherChange(ev: Event) {
    const on = (ev.target as HTMLInputElement).checked;
    emit('update', { renderMode: on ? 'dither' : 'clean' });
}

function onSelectChange(field: string, ev: Event) {
    const v = (ev.target as HTMLSelectElement).value;
    emit('update', { [field]: v });
}
function onNumberChange(field: string, ev: Event) {
    let v = parseFloat((ev.target as HTMLInputElement).value);
    if (!Number.isFinite(v)) return;
    // sides 必须是 [3,32] 的整数——避免退化多边形（< 3 边）/ 超采样（> 32）。
    if (field === 'sides') v = Math.max(3, Math.min(32, Math.round(v)));
    emit('updateDebounced', { [field]: v });
}
</script>

<template>
  <details class="group" open>
    <summary class="cursor-pointer select-none text-[color:var(--muted-foreground)] uppercase tracking-wider text-xs py-1 hover:text-[color:var(--foreground)]">
      {{ isRect ? t.properties.rectHeader
          : isCircle ? t.properties.circleHeader
          : isShape ? t.properties.shapeHeader
          : t.properties.pathHeader }}
    </summary>
    <div class="pt-1.5 space-y-2">
      <!-- fill：toggle + FillInput（支持 solid / linear / radial） -->
      <div class="flex items-start justify-between gap-2">
        <span class="text-[color:var(--muted-foreground)] mt-0.5">{{ t.properties.fill }}</span>
        <div class="flex flex-col gap-1 flex-1 max-w-[180px]">
          <input type="checkbox"
                 :checked="geomFill() !== undefined"
                 @change="toggleGeomFill"
                 class="self-end">
          <FillInput v-if="geomFill()"
                     :model-value="geomFill()"
                     @update:model-value="(v) => emit('update', { fill: v })" />
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
            <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.properties.strokeWidth }}</span>
            <input type="number" min="0" :max="MAX_STROKE_WIDTH" class="hc-input" :value="geomStroke()!.width"
                   @input="(e) => patchGeomStroke({ width: parseInt((e.target as HTMLInputElement).value, 10) || 0 })">
          </label>
          <label class="flex flex-col gap-0.5">
            <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.properties.strokeColor }}</span>
            <ColorInput :model-value="geomStroke()!.color"
                        @update:model-value="(v) => patchGeomStroke({ color: v })" />
          </label>
        </div>
      </template>
      <!-- Shape 专属：kind / sides / innerRatio -->
      <template v-if="isShape">
        <div class="grid grid-cols-2 gap-2">
          <label class="flex flex-col gap-0.5">
            <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.properties.shapeKind }}</span>
            <select class="hc-input" :value="(element as ShapeElement).kind"
                    @change="(e) => onSelectChange('kind', e)">
              <option value="polygon">{{ t.properties.shapeKindPolygon }}</option>
              <option value="star">{{ t.properties.shapeKindStar }}</option>
            </select>
          </label>
          <label class="flex flex-col gap-0.5">
            <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.properties.shapeSides }}</span>
            <input type="number" min="3" max="32" class="hc-input"
                   :value="(element as ShapeElement).sides"
                   @input="(e) => onNumberChange('sides', e)">
          </label>
        </div>
        <label v-if="(element as ShapeElement).kind === 'star'" class="flex flex-col gap-0.5">
          <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.properties.shapeInnerRatio }}</span>
          <input type="range" min="0.1" max="0.95" step="0.05"
                 :value="(element as ShapeElement).innerRatio ?? 0.5"
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
