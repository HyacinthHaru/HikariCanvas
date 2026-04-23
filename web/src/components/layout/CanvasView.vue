<script setup lang="ts">
import { computed } from 'vue';
import { ZoomIn, ZoomOut, RotateCcw } from 'lucide-vue-next';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { onKeyStroke } from '@vueuse/core';

const project = useProjectStore();
const ui = useUiStore();

// 画布尺寸占位 display
const sizeLabel = computed(() => {
    if (!project.state) return '—';
    const c = project.state.canvas;
    return `${c.widthMaps}×${c.heightMaps} maps · ${c.widthMaps * 128}×${c.heightMaps * 128} px`;
});

// 快捷键：Ctrl+= / Ctrl+- / Ctrl+0
onKeyStroke(['=', '+'], (e) => {
    if (!e.ctrlKey && !e.metaKey) return;
    e.preventDefault();
    ui.zoomIn();
});
onKeyStroke('-', (e) => {
    if (!e.ctrlKey && !e.metaKey) return;
    e.preventDefault();
    ui.zoomOut();
});
onKeyStroke('0', (e) => {
    if (!e.ctrlKey && !e.metaKey) return;
    e.preventDefault();
    ui.zoomReset();
});
</script>

<template>
  <section class="flex-1 relative overflow-hidden bg-[color:var(--background)]">
    <!-- 画布占位：M5-B 接入 Canvas 2D + Konva overlay -->
    <div class="absolute inset-0 flex items-center justify-center">
      <div class="flex flex-col items-center gap-3 text-[color:var(--muted-foreground)]">
        <div
          class="border border-dashed border-[color:var(--border)] rounded-lg grid place-items-center"
          :style="{
            width: `${(project.canvasPixelWidth || 256) * ui.zoom}px`,
            height: `${(project.canvasPixelHeight || 256) * ui.zoom}px`,
          }"
        >
          <div class="text-xs opacity-60">
            Canvas 渲染区（M5-B 实装）
          </div>
        </div>
        <div class="text-xs">{{ sizeLabel }} · zoom {{ (ui.zoom * 100).toFixed(0) }}%</div>
      </div>
    </div>

    <!-- 右下角 zoom 控件 -->
    <div class="absolute bottom-3 right-3 flex items-center gap-1 bg-[color:var(--card)] border border-[color:var(--border)] rounded-lg p-1 shadow-sm">
      <button class="p-1.5 rounded hover:bg-[color:var(--accent)]" title="Zoom out (Ctrl+-)" @click="ui.zoomOut()">
        <ZoomOut class="size-4" />
      </button>
      <span class="w-12 text-center text-xs tabular-nums">
        {{ (ui.zoom * 100).toFixed(0) }}%
      </span>
      <button class="p-1.5 rounded hover:bg-[color:var(--accent)]" title="Zoom in (Ctrl+=)" @click="ui.zoomIn()">
        <ZoomIn class="size-4" />
      </button>
      <button class="p-1.5 rounded hover:bg-[color:var(--accent)]" title="Reset zoom (Ctrl+0)" @click="ui.zoomReset()">
        <RotateCcw class="size-4" />
      </button>
    </div>
  </section>
</template>
