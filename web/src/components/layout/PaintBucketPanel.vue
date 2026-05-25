<script setup lang="ts">
/**
 * M18 Live Paint — 油漆桶面板（替代 BrushPanel 在 paint-bucket 工具激活时显示）。
 *
 * 结构模仿 BrushPanel：
 *   - 头部 icon + 标题
 *   - FillInput（绑定 paintBucket.currentFill）
 *   - v1.x 新增：RDP tolerance slider（决定生成 PathElement 的顶点密度）
 *   - 操作提示（点击空白 / 点击元素 / hover 高亮）
 *
 * 不暴露 isBuilding loading 状态 —— useLivePaint 实例由 CanvasView 持有，
 * 由 CanvasView 自行在右下角浮窗显示。多实例会创建多 worker（昂贵），不可走 provide/inject 之外的路径。
 */
import { PaintBucket } from 'lucide-vue-next';
import {
    usePaintBucketStore,
    TOLERANCE_MIN,
    TOLERANCE_MAX,
    TOLERANCE_STEP,
} from '@/stores/paintBucket';
import { useI18n } from '@/i18n';
import FillInput from '@/components/ui/FillInput.vue';
import Tooltip from '@/components/ui/Tooltip.vue';
import type { Fill } from '@/types/protocol';

const paintBucket = usePaintBucketStore();
const { t } = useI18n();

function onFillChange(v: Fill) {
    paintBucket.currentFill = v;
}

function onToleranceInput(ev: Event) {
    const v = parseFloat((ev.target as HTMLInputElement).value);
    if (!Number.isFinite(v)) return;
    const clamped = Math.max(TOLERANCE_MIN, Math.min(TOLERANCE_MAX, v));
    paintBucket.tolerance = clamped;
}
</script>

<template>
  <section class="flex-1 overflow-y-auto min-h-0">
    <header class="flex items-center gap-2 px-3 h-9 border-b border-[color:var(--border)] text-xs font-medium uppercase tracking-wider text-[color:var(--muted-foreground)]">
      <PaintBucket class="size-3.5" />
      <span>{{ t.livePaint.title }}</span>
    </header>
    <div class="px-3 py-3 space-y-3 text-xs">
      <!-- fill 输入（复用 FillInput，支持 solid / linear / radial） -->
      <div class="flex flex-col gap-1">
        <span class="text-[color:var(--muted-foreground)]">{{ t.livePaint.fillLabel }}</span>
        <FillInput :model-value="paintBucket.currentFill" @update:model-value="onFillChange" />
      </div>

      <!-- RDP tolerance slider -->
      <label class="flex flex-col gap-1">
        <Tooltip :text="t.livePaint.toleranceTip">
          <div class="flex items-center justify-between cursor-help">
            <span class="text-[color:var(--muted-foreground)]">{{ t.livePaint.toleranceLabel }}</span>
            <span class="font-mono tabular-nums">{{ paintBucket.tolerance.toFixed(2) }}px</span>
          </div>
        </Tooltip>
        <input type="range"
               :min="TOLERANCE_MIN" :max="TOLERANCE_MAX" :step="TOLERANCE_STEP"
               :value="paintBucket.tolerance" @input="onToleranceInput" />
      </label>

      <!-- 操作提示 -->
      <p class="text-xs text-[color:var(--muted-foreground)] pt-2 leading-relaxed">
        {{ t.livePaint.hint }}
      </p>
      <p class="text-xs text-[color:var(--muted-foreground)] leading-relaxed">
        {{ t.livePaint.hintHoverPreview }}
      </p>
    </div>
  </section>
</template>
