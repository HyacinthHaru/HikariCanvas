<script setup lang="ts">
import { ref } from 'vue';
import { ZoomIn, ZoomOut, RotateCcw, Maximize, ImagePlus } from 'lucide-vue-next';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { useI18n } from '@/i18n';
import Tooltip from '@/components/ui/Tooltip.vue';

const props = defineProps<{
    sizeLabel: string;
    gridSize: number;
    uploading: boolean;
}>();

const emit = defineEmits<{
    fit: [];
    triggerUpload: [];
    gridChange: [ev: Event];
}>();

const project = useProjectStore();
const ui = useUiStore();
const { t } = useI18n();

const ZOOM_PRESETS = [0.5, 0.75, 1, 1.5, 2, 4];

const zoomEditOpen = ref(false);
const zoomDraft = ref('');
function openZoomEdit() {
    zoomDraft.value = String(Math.round(ui.zoom * 100));
    zoomEditOpen.value = true;
}
function commitZoomEdit() {
    const n = parseFloat(zoomDraft.value);
    if (Number.isFinite(n) && n > 0) {
        ui.setZoom(Math.max(0.25, Math.min(4, n / 100)));
    }
    zoomEditOpen.value = false;
}
function cancelZoomEdit() {
    zoomEditOpen.value = false;
}
</script>

<template>
  <div class="sticky bottom-3 float-right mr-3 flex items-center gap-1 bg-[color:var(--card)] border border-[color:var(--border)] rounded-lg p-1 shadow-sm text-[color:var(--foreground)]">
    <Tooltip :text="t.image.uploadTip">
      <button
        class="p-1.5 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
        :disabled="project.isLocked || props.uploading"
        @click="emit('triggerUpload')"
      >
        <ImagePlus class="size-4" />
      </button>
    </Tooltip>
    <div class="w-px h-5 bg-[color:var(--border)] mx-0.5"></div>
    <Tooltip :text="t.canvas.zoomOut" shortcut="Ctrl+-">
      <button class="p-1.5 rounded hover:bg-[color:var(--accent)]" @click="ui.zoomOut()">
        <ZoomOut class="size-4" />
      </button>
    </Tooltip>
    <span class="relative">
      <input
        v-if="zoomEditOpen"
        type="number"
        class="hc-zoom-input"
        :value="zoomDraft"
        autofocus
        step="10"
        min="25"
        max="400"
        @input="(e) => zoomDraft = (e.target as HTMLInputElement).value"
        @keydown.enter="commitZoomEdit"
        @keydown.escape="cancelZoomEdit"
        @blur="commitZoomEdit"
      />
      <Tooltip v-else :text="t.canvas.zoomInputTip">
        <button
          class="w-14 px-1 py-0.5 text-center text-xs tabular-nums rounded hover:bg-[color:var(--accent)]"
          @click="openZoomEdit"
        >{{ (ui.zoom * 100).toFixed(0) }}%</button>
      </Tooltip>
    </span>
    <Tooltip :text="t.canvas.zoomIn" shortcut="Ctrl+=">
      <button class="p-1.5 rounded hover:bg-[color:var(--accent)]" @click="ui.zoomIn()">
        <ZoomIn class="size-4" />
      </button>
    </Tooltip>
    <div class="w-px h-5 bg-[color:var(--border)] mx-0.5"></div>
    <div class="flex items-center gap-0.5">
      <button
        v-for="p in ZOOM_PRESETS"
        :key="p"
        class="px-1.5 py-0.5 text-[10px] tabular-nums rounded transition-colors"
        :class="Math.abs(ui.zoom - p) < 0.01
          ? 'bg-[color:var(--accent)] text-[color:var(--accent-foreground)]'
          : 'hover:bg-[color:var(--accent)] text-[color:var(--muted-foreground)]'"
        @click="ui.setZoom(p)"
      >{{ (p * 100).toFixed(0) }}</button>
    </div>
    <div class="w-px h-5 bg-[color:var(--border)] mx-0.5"></div>
    <Tooltip :text="t.canvas.fit">
      <button class="p-1.5 rounded hover:bg-[color:var(--accent)]" @click="emit('fit')">
        <Maximize class="size-4" />
      </button>
    </Tooltip>
    <Tooltip :text="t.canvas.zoomReset" shortcut="Ctrl+0">
      <button class="p-1.5 rounded hover:bg-[color:var(--accent)]" @click="ui.zoomReset()">
        <RotateCcw class="size-4" />
      </button>
    </Tooltip>
    <span class="pl-2 pr-1 border-l border-[color:var(--border)] ml-1 text-[10px] text-[color:var(--muted-foreground)]">
      {{ props.sizeLabel }}
    </span>
    <Tooltip :text="t.canvas.gridTip">
      <label class="flex items-center gap-1 pl-2 border-l border-[color:var(--border)] ml-1">
        <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.canvas.grid }}</span>
        <input
          type="number"
          min="0"
          max="256"
          step="1"
          class="hc-grid-input"
          :value="props.gridSize"
          @change="(ev) => emit('gridChange', ev)"
        >
      </label>
    </Tooltip>
  </div>
</template>

<style scoped>
.hc-zoom-input {
    width: 3.5rem;
    padding: 0.125rem 0.25rem;
    font-size: 0.75rem;
    text-align: center;
    border-radius: 4px;
    background: var(--background);
    color: var(--foreground);
    border: 1px solid var(--ring);
    outline: none;
    font-variant-numeric: tabular-nums;
}
.hc-zoom-input::-webkit-outer-spin-button,
.hc-zoom-input::-webkit-inner-spin-button { -webkit-appearance: none; margin: 0; }
.hc-grid-input {
    width: 2.5rem;
    padding: 0.125rem 0.25rem;
    font-size: 0.7rem;
    text-align: center;
    border-radius: 3px;
    background: var(--background);
    color: var(--foreground);
    border: 1px solid var(--border);
    outline: none;
    font-variant-numeric: tabular-nums;
}
.hc-grid-input:focus {
    border-color: var(--ring);
    box-shadow: 0 0 0 1px var(--ring);
}
</style>
