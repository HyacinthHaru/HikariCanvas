<script setup lang="ts">
/**
 * 右栏总装配器。本身只管：
 * - 顶部 LayerPanel（不动）
 * - 笔刷工具激活时切到 BrushPanel（替代 Properties，不动）
 * - Properties 头部 + 多选 / 空选提示
 * - 按 element.type dispatch 到对应 properties/*Section 子组件
 * - 子组件 emit('update' | 'updateDebounced') → 这里统一走 sendUpdate / sendUpdateDebounced
 *   做 optimistic mutation + ws.send；保证对外 op 流与拆分前一致
 * - 底部 ElementListSection（不动）
 *
 * 拆分前 1076 行 god component。M13 后续段 + M14 协作各类还会继续往里塞东西，
 * 留薄壳让子组件迭代不影响其他段。
 */
import { computed } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { getWsClient } from '@/network/wsClient';
import { Sliders, Trash2 } from 'lucide-vue-next';
import { useI18n } from '@/i18n';
import Tooltip from '@/components/ui/Tooltip.vue';
import LayerPanel from '@/components/layout/LayerPanel.vue';
import BrushPanel from '@/components/layout/BrushPanel.vue';
import TransformSection from '@/components/properties/TransformSection.vue';
import TextElementSection from '@/components/properties/TextElementSection.vue';
import GeometricElementSection from '@/components/properties/GeometricElementSection.vue';
import ImageElementSection from '@/components/properties/ImageElementSection.vue';
import CanvasSettingsSection from '@/components/properties/CanvasSettingsSection.vue';
import ElementListSection from '@/components/properties/ElementListSection.vue';
import type { Element, TextElement, RectElement, CircleElement, ShapeElement, PathElement, ImageElement } from '@/types/protocol';

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
const isImage = computed(() => selected.value?.type === 'image');
/** M11-D：几何元素族（支持 fill / dither）。text / icon / image 不在内。 */
const isGeometric = computed(() => isRect.value || isCircle.value || isShape.value || isPath.value);

/** M8-F：是否多选（>= 2）。multi 时隐藏单选 UI，显示批量操作提示。 */
const isMulti = computed(() => ui.selectedCount >= 2);

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

      <div v-else-if="!selected" class="text-xs">
        <!-- 未选中元素时空 hint + M17 F5 画板设置段 -->
        <div class="p-3 text-[color:var(--muted-foreground)]">
          {{ t.properties.empty }}
        </div>
        <CanvasSettingsSection />
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

        <!-- 位置 & 尺寸 + opacity + blendMode / renderMode（共通段） -->
        <TransformSection
          :element="selected"
          :locked="project.isLocked"
          @update="sendUpdate"
          @update-debounced="sendUpdateDebounced"
        />

        <!-- 几何元素（rect / circle / shape / path）公用 fill + stroke + dither -->
        <GeometricElementSection
          v-if="isGeometric"
          :element="selected as RectElement | CircleElement | ShapeElement | PathElement"
          :locked="project.isLocked"
          @update="sendUpdate"
          @update-debounced="sendUpdateDebounced"
        />

        <!-- Text 主段 + Effects 段 -->
        <TextElementSection
          v-if="isText"
          :element="selected as TextElement"
          :locked="project.isLocked"
          @update="sendUpdate"
          @update-debounced="sendUpdateDebounced"
        />

        <!-- M13-D：ImageElement 段 -->
        <ImageElementSection
          v-if="isImage"
          :element="selected as ImageElement"
          :locked="project.isLocked"
          @update="sendUpdate"
        />
      </div>
    </section>

    <!-- Elements（当前活动层内的元素列表）-->
    <ElementListSection />
    </template> <!-- M12-D：v-else 结束（Properties 块只在非 brush 工具显示） -->
  </aside>
</template>

<style scoped>
/* 2026-05-14 lock-state：locked 时整栏禁用编辑。pointer-events: none 完全屏蔽点击 / 输入 / 拖拽；
   opacity 60% 提供视觉反馈让用户知道控件不可用。 */
.hc-readonly-panel section,
.hc-readonly-panel :deep(.layer-panel) {
    pointer-events: none;
    opacity: 0.6;
}
</style>
