<script setup lang="ts">
import { Brush } from 'lucide-vue-next';
import { useBrushStore } from '@/stores/brush';
import { useI18n } from '@/i18n';
import Tooltip from '@/components/ui/Tooltip.vue';
import FillInput from '@/components/ui/FillInput.vue';
import type { Fill } from '@/types/protocol';

const brush = useBrushStore();
const { t } = useI18n();

function onSizeInput(ev: Event) {
    const v = parseInt((ev.target as HTMLInputElement).value, 10);
    if (Number.isFinite(v)) brush.size = Math.max(1, Math.min(64, v));
}

function onOpacityInput(ev: Event) {
    const pct = parseInt((ev.target as HTMLInputElement).value, 10);
    if (Number.isFinite(pct)) brush.opacity = Math.max(0, Math.min(1, pct / 100));
}

function onSmoothingInput(ev: Event) {
    const pct = parseInt((ev.target as HTMLInputElement).value, 10);
    if (Number.isFinite(pct)) brush.smoothing = Math.max(0, Math.min(1, pct / 100));
}

function onFillChange(v: Fill) {
    brush.fill = v;
}
</script>

<template>
  <section class="flex-1 overflow-y-auto min-h-0">
    <header class="flex items-center gap-2 px-3 h-9 border-b border-[color:var(--border)] text-xs font-medium uppercase tracking-wider text-[color:var(--muted-foreground)]">
      <Brush class="size-3.5" />
      <span>{{ t.brush.header }}</span>
    </header>
    <div class="px-3 py-3 space-y-3 text-xs">
      <!-- size 滑块 -->
      <label class="flex flex-col gap-1">
        <div class="flex items-center justify-between">
          <span class="text-[color:var(--muted-foreground)]">{{ t.brush.size }}</span>
          <span class="font-mono tabular-nums">{{ brush.size }}px</span>
        </div>
        <input type="range" min="1" max="64" :value="brush.size" @input="onSizeInput" />
      </label>

      <!-- fill（FillInput 支持 solid / linear / radial） -->
      <div class="flex flex-col gap-1">
        <span class="text-[color:var(--muted-foreground)]">{{ t.brush.fill }}</span>
        <FillInput :model-value="brush.fill" @update:model-value="onFillChange" />
      </div>

      <!-- opacity 滑块 -->
      <label class="flex flex-col gap-1">
        <div class="flex items-center justify-between">
          <span class="text-[color:var(--muted-foreground)]">{{ t.brush.opacity }}</span>
          <span class="font-mono tabular-nums">{{ Math.round(brush.opacity * 100) }}%</span>
        </div>
        <input type="range" min="0" max="100"
               :value="Math.round(brush.opacity * 100)" @input="onOpacityInput" />
      </label>

      <!-- pressureSize toggle -->
      <Tooltip :text="t.brush.pressureSizeTip">
        <label class="flex items-center justify-between cursor-help">
          <span class="text-[color:var(--muted-foreground)]">{{ t.brush.pressureSize }}</span>
          <input type="checkbox" v-model="brush.pressureSize">
        </label>
      </Tooltip>

      <!-- pressureOpacity toggle -->
      <Tooltip :text="t.brush.pressureOpacityTip">
        <label class="flex items-center justify-between cursor-help">
          <span class="text-[color:var(--muted-foreground)]">{{ t.brush.pressureOpacity }}</span>
          <input type="checkbox" v-model="brush.pressureOpacity">
        </label>
      </Tooltip>

      <!-- smoothing 滑块 -->
      <label class="flex flex-col gap-1">
        <Tooltip :text="t.brush.smoothingTip">
          <div class="flex items-center justify-between cursor-help">
            <span class="text-[color:var(--muted-foreground)]">{{ t.brush.smoothing }}</span>
            <span class="font-mono tabular-nums">{{ Math.round(brush.smoothing * 100) }}%</span>
          </div>
        </Tooltip>
        <input type="range" min="0" max="100"
               :value="Math.round(brush.smoothing * 100)" @input="onSmoothingInput" />
      </label>

      <!-- dither toggle（Q7：复用 M11 renderMode） -->
      <Tooltip :text="t.brush.ditherTip">
        <label class="flex items-center justify-between cursor-help">
          <span class="text-[color:var(--muted-foreground)]">{{ t.brush.dither }}</span>
          <input type="checkbox" v-model="brush.dither">
        </label>
      </Tooltip>

      <p class="text-xs text-[color:var(--muted-foreground)] pt-2 leading-relaxed">
        {{ t.brush.hint }}
      </p>
    </div>
  </section>
</template>
