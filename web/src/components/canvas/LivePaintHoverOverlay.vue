<script setup lang="ts">
/**
 * Live Paint — hover 高亮 overlay。
 *
 * 父组件传入当前 hovered gap polygon，本组件用 Konva v-path 画半透明蓝色填充 +
 * 描边轮廓，listening=false 让点击事件透传到下方 stage（再由 stage onClick 触发填充）。
 *
 * 设计取舍：
 *   - fillRule='evenodd' 配合 SVG d 内多 M..L..Z 子路径，自动挖出 holes
 *   - strokeWidth = 1.5/zoom 让线宽视觉上保持稳定不随 zoom 变化
 *   - 整体包装成 v-layer，与 SnapGuideOverlay 同级；layer listening=false
 */
import { computed } from 'vue';
import type { GapPolygon } from '@/livepaint';
import { gapPolygonToPathD } from '@/livepaint';
import { useUiStore } from '@/stores/ui';

const props = defineProps<{ hoveredGap: GapPolygon | null }>();
const ui = useUiStore();

const zoom = computed(() => Math.max(0.0001, ui.zoom));
const strokeW = computed(() => 1.5 / zoom.value);

/** GapPolygon → SVG d；用全局坐标系（gap 已是画布像素绝对坐标）。 */
const pathD = computed(() => {
    if (!props.hoveredGap) return '';
    return gapPolygonToPathD(props.hoveredGap);
});

const config = computed(() => ({
    data: pathD.value,
    fill: 'rgba(96, 165, 250, 0.22)',  // blue-400 / 22% alpha；与 SnapGuide 红/绿区分
    stroke: '#60a5fa',                  // blue-400
    strokeWidth: strokeW.value,
    fillRule: 'evenodd',                // 多 subpath 自动挖洞
    listening: false,
    perfectDrawEnabled: false,
}));
</script>

<template>
  <v-layer :listening="false">
    <v-path v-if="hoveredGap && pathD" :config="config" />
  </v-layer>
</template>
