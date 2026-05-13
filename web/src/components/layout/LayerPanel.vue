<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';
import Tooltip from '@/components/ui/Tooltip.vue';
import {
    Layers,
    Eye,
    EyeOff,
    Lock,
    Unlock,
    Plus,
    Copy,
    Trash2,
    HelpCircle,
} from 'lucide-vue-next';
import type { BlendMode, Layer } from '@/types/protocol';

const project = useProjectStore();
const ui = useUiStore();
const ws = getWsClient();
const { t } = useI18n();

// ---------- 列表（UI 倒序：top = 最上层）----------

/**
 * Backend layers: index 0 = 底层（最先画），最大 index = 顶层（最后画）。
 * UI 习惯（PS/Figma）：顶层在视觉最上。
 * 因此 UI 渲染时反转一次。reorder 时换算 backendIdx = n-1-uiIdx。
 */
const uiLayers = computed<Layer[]>(() => {
    const layers = project.state?.layers ?? [];
    return [...layers].reverse();
});

const layerCount = computed(() => project.state?.layers.length ?? 0);
const activeLayerId = computed(() => project.state?.activeLayerId ?? '');

function isActive(layerId: string): boolean {
    return layerId === activeLayerId.value;
}

function uiToBackendIdx(uiIdx: number): number {
    return layerCount.value - 1 - uiIdx;
}

// ---------- 操作 ----------

function selectLayer(layerId: string): void {
    if (layerId === activeLayerId.value) return;
    // 切层前清空 element 选中，避免 Properties 显示不在新活动层的元素
    ui.selectElement(null);
    ws.send('layer.set-active', { layerId });
}

function toggleVisible(layer: Layer): void {
    ws.send('layer.update', {
        layerId: layer.id,
        patch: { visible: !layer.visible },
    });
}

function toggleLock(layer: Layer): void {
    ws.send('layer.update', {
        layerId: layer.id,
        patch: { locked: !layer.locked },
    });
}

function createNewLayer(): void {
    // afterLayerId = 当前活动层 → 新层叠在它之上
    ws.send('layer.create', { afterLayerId: activeLayerId.value || undefined });
}

function duplicateLayer(layer: Layer): void {
    ws.send('layer.duplicate', { layerId: layer.id });
}

function deleteLayer(layer: Layer): void {
    if (layerCount.value <= 1) return; // 最后一层禁删（按钮 disabled）
    ws.send('layer.delete', { layerId: layer.id });
    // 后端会自动把 activeLayerId 切到剩余的第一层（M8-C 行为）
    // selection 在 layer.delete patch 应用后由 selectElement 兜底（element 不在新 activeLayer 时清）
    if (layer.id === activeLayerId.value) {
        ui.selectElement(null);
    }
}

// ---------- inline rename ----------

// 注：v-for 里的模板 ref 在 Vue 3 中收集成数组，单元素 .focus() 不工作；
// 改用 querySelector + data-attr，保证只 focus 当前 editing 的 input。
const renameDraft = ref('');
const escPressed = ref(false);

async function startRename(layer: Layer): Promise<void> {
    ui.setEditingLayer(layer.id);
    renameDraft.value = layer.name;
    escPressed.value = false;
    await nextTick();
    const el = document.querySelector<HTMLInputElement>(
        `input[data-layer-rename="${layer.id}"]`);
    el?.focus();
    el?.select();
}

function commitRename(layer: Layer): void {
    const name = renameDraft.value.trim();
    if (escPressed.value) {
        ui.setEditingLayer(null);
        return;
    }
    if (name && name !== layer.name) {
        ws.send('layer.update', { layerId: layer.id, patch: { name } });
    }
    ui.setEditingLayer(null);
}

function cancelRename(): void {
    escPressed.value = true;
    ui.setEditingLayer(null);
}

// ---------- HTML5 drag reorder ----------

const dragUiIdx = ref(-1);
const dragOverUiIdx = ref(-1);

function onDragStart(ev: DragEvent, uiIdx: number): void {
    dragUiIdx.value = uiIdx;
    if (ev.dataTransfer) {
        ev.dataTransfer.effectAllowed = 'move';
        ev.dataTransfer.setData('text/plain', String(uiIdx));
    }
}

function onDragOver(ev: DragEvent, uiIdx: number): void {
    ev.preventDefault();
    dragOverUiIdx.value = uiIdx;
    if (ev.dataTransfer) ev.dataTransfer.dropEffect = 'move';
}

function onDragLeave(): void {
    dragOverUiIdx.value = -1;
}

function onDrop(ev: DragEvent, uiIdx: number): void {
    ev.preventDefault();
    const fromUi = dragUiIdx.value;
    dragUiIdx.value = -1;
    dragOverUiIdx.value = -1;
    if (fromUi < 0 || fromUi === uiIdx) return;
    const layer = uiLayers.value[fromUi];
    if (!layer) return;
    // UI 顺序 → backend 顺序换算
    const toBackend = uiToBackendIdx(uiIdx);
    ws.send('layer.reorder', { layerId: layer.id, index: toBackend });
}

function onDragEnd(): void {
    dragUiIdx.value = -1;
    dragOverUiIdx.value = -1;
}

// ---------- M8-E：active layer opacity + blendMode 控件 ----------

const BLEND_MODES: BlendMode[] = ['normal', 'multiply', 'screen', 'overlay'];

/**
 * slider 拖动期间的本地缓冲（百分比 0-100）；松开后归 null，由 state 接管显示。
 * 切换 active layer 时 watch 会清空，避免显示错位（参考 RightPanel 的 opacityDraftPct）。
 */
const opacityDraft = ref<number | null>(null);

watch(activeLayerId, () => { opacityDraft.value = null; });

function activeOpacityPct(layer: Layer): number {
    return opacityDraft.value !== null ? opacityDraft.value : Math.round(layer.opacity * 100);
}

// VueUse useDebounceFn 不提供 flush，所以 onChange（mouseup）走 immediate send，
// onInput 走 debounce。两条路最终值相同 → 最差冗余 1 次 ws，无副作用。
const sendOpacityDebounced = useDebounceFn((layerId: string, opacity: number) => {
    ws.send('layer.update', { layerId, patch: { opacity } });
}, 80);

function onOpacityInput(layer: Layer, ev: Event): void {
    const v = parseInt((ev.target as HTMLInputElement).value, 10);
    if (!Number.isFinite(v)) return;
    opacityDraft.value = v;
    const opacity = v / 100;
    // 乐观更新本地状态，让 PreviewRenderer 立即反映
    layer.opacity = opacity;
    sendOpacityDebounced(layer.id, opacity);
}

function onOpacityChange(layer: Layer): void {
    // slider 松开：清 draft + 立即 send 当前值，确保最终值落地
    opacityDraft.value = null;
    ws.send('layer.update', { layerId: layer.id, patch: { opacity: layer.opacity } });
}

function onBlendModeChange(layer: Layer, ev: Event): void {
    const mode = (ev.target as HTMLSelectElement).value as BlendMode;
    if (!BLEND_MODES.includes(mode)) return;
    layer.blendMode = mode;
    ws.send('layer.update', { layerId: layer.id, patch: { blendMode: mode } });
}
</script>

<template>
  <section class="flex flex-col border-b border-[color:var(--border)] max-h-[40%] min-h-[120px]">
    <header class="flex items-center gap-2 px-3 h-9 border-b border-[color:var(--border)] text-xs font-medium uppercase tracking-wider text-[color:var(--muted-foreground)]">
      <Layers class="size-3.5" />
      <span>{{ t.layerPanel.header }}</span>
      <span class="ml-auto text-[10px] font-normal normal-case">{{ t.layerPanel.count(layerCount) }}</span>
      <Tooltip :text="t.layerPanel.newLayerHint">
        <button
          class="p-1 rounded hover:bg-[color:var(--accent)] text-[color:var(--muted-foreground)] hover:text-[color:var(--foreground)]"
          :title="t.layerPanel.newLayer"
          @click="createNewLayer"
        >
          <Plus class="size-3.5" />
        </button>
      </Tooltip>
    </header>

    <ul class="overflow-y-auto flex-1">
      <!-- 单一 v-for：每个 layer 输出主行 + （仅 active）紧跟两行控件 -->
      <template v-for="(layer, uiIdx) in uiLayers" :key="layer.id">
        <li
          :draggable="ui.editingLayerId !== layer.id"
          class="group flex items-center gap-1 px-2 py-1.5 text-xs cursor-pointer transition-colors border-l-2 border-l-transparent"
          :class="{
            'bg-[color:var(--accent)] border-l-[color:var(--ring)]': isActive(layer.id),
            'hover:bg-[color:var(--accent)]': !isActive(layer.id),
            'opacity-50': dragUiIdx === uiIdx,
            'ring-1 ring-[color:var(--ring)] ring-inset': dragOverUiIdx === uiIdx && dragUiIdx !== uiIdx,
          }"
          @click="selectLayer(layer.id)"
          @dragstart="(e) => onDragStart(e, uiIdx)"
          @dragover="(e) => onDragOver(e, uiIdx)"
          @dragleave="onDragLeave"
          @drop="(e) => onDrop(e, uiIdx)"
          @dragend="onDragEnd"
        >
          <!-- visible toggle -->
          <button
            class="p-0.5 rounded hover:bg-[color:var(--background)] shrink-0"
            :title="t.layerPanel.toggleVisible(layer.visible)"
            @click.stop="toggleVisible(layer)"
          >
            <component
              :is="layer.visible ? Eye : EyeOff"
              class="size-3.5"
              :class="layer.visible ? '' : 'opacity-40'"
            />
          </button>

          <!-- lock toggle -->
          <button
            class="p-0.5 rounded hover:bg-[color:var(--background)] shrink-0"
            :title="t.layerPanel.toggleLock(layer.locked)"
            @click.stop="toggleLock(layer)"
          >
            <component
              :is="layer.locked ? Lock : Unlock"
              class="size-3.5"
              :class="layer.locked ? 'text-[color:var(--ring)]' : 'opacity-40'"
            />
          </button>

          <!-- name / inline rename -->
          <input
            v-if="ui.editingLayerId === layer.id"
            v-model="renameDraft"
            type="text"
            class="hc-input flex-1 px-1 py-0 text-xs"
            :data-layer-rename="layer.id"
            maxlength="64"
            :title="t.layerPanel.renameSave"
            @click.stop
            @keydown.enter.prevent="commitRename(layer)"
            @keydown.esc.prevent="cancelRename"
            @blur="commitRename(layer)"
          >
          <span
            v-else
            class="flex-1 truncate select-none"
            :title="t.layerPanel.renameTitle"
            @dblclick.stop="startRename(layer)"
          >
            {{ layer.name }}
          </span>

          <!-- element count badge -->
          <span class="text-[9px] text-[color:var(--muted-foreground)] tabular-nums shrink-0">
            {{ layer.elements.length }}
          </span>

          <!-- duplicate (hover) -->
          <Tooltip :text="t.layerPanel.duplicate">
            <button
              class="p-0.5 rounded hover:bg-[color:var(--background)] opacity-0 group-hover:opacity-100 shrink-0"
              @click.stop="duplicateLayer(layer)"
            >
              <Copy class="size-3" />
            </button>
          </Tooltip>

          <!-- delete (hover, disabled on last) -->
          <Tooltip :text="layerCount <= 1 ? t.layerPanel.deleteLast : t.layerPanel.deleteLayer">
            <button
              class="p-0.5 rounded shrink-0 opacity-0 group-hover:opacity-100"
              :class="layerCount <= 1
                ? 'cursor-not-allowed opacity-30 group-hover:opacity-30'
                : 'hover:bg-[color:var(--destructive)] hover:text-[color:var(--destructive-foreground)]'"
              :disabled="layerCount <= 1"
              @click.stop="deleteLayer(layer)"
            >
              <Trash2 class="size-3" />
            </button>
          </Tooltip>
        </li>
        <!-- M8-E：active layer 行紧跟两行 opacity / blendMode 控件 -->
        <li
          v-if="isActive(layer.id)"
          class="px-2 py-1.5 flex items-center gap-2 text-[10px] bg-[color:var(--accent)] border-l-2 border-l-[color:var(--ring)]"
        >
          <span class="text-[color:var(--muted-foreground)] shrink-0">{{ t.layerPanel.opacityLabel }}</span>
          <input
            type="range"
            min="0"
            max="100"
            step="1"
            class="flex-1 hc-layer-slider"
            :value="activeOpacityPct(layer)"
            @input="(e) => onOpacityInput(layer, e)"
            @change="onOpacityChange(layer)"
          >
          <span class="w-8 tabular-nums text-right">{{ activeOpacityPct(layer) }}%</span>
          <Tooltip :text="t.layerPanel.opacityHint">
            <HelpCircle class="size-2.5 opacity-50 hover:opacity-100 shrink-0" />
          </Tooltip>
        </li>
        <li
          v-if="isActive(layer.id)"
          class="px-2 pb-1.5 flex items-center gap-2 text-[10px] bg-[color:var(--accent)] border-l-2 border-l-[color:var(--ring)] border-b border-[color:var(--border)]"
        >
          <span class="text-[color:var(--muted-foreground)] shrink-0">{{ t.layerPanel.blendModeLabel }}</span>
          <select
            class="hc-layer-select flex-1"
            :value="layer.blendMode"
            @change="(e) => onBlendModeChange(layer, e)"
          >
            <option v-for="m in BLEND_MODES" :key="m" :value="m">
              {{ (t.layerPanel.blendModeOptions as Record<string, string>)[m] ?? m }}
            </option>
          </select>
        </li>
      </template>
    </ul>
  </section>
</template>

<style scoped>
.hc-input {
    background: var(--background);
    color: var(--foreground);
    border: 1px solid var(--border);
    border-radius: 4px;
}
.hc-input:focus {
    outline: none;
    border-color: var(--ring);
    box-shadow: 0 0 0 1px var(--ring);
}
.hc-layer-slider {
    height: 14px;
    background: transparent;
    accent-color: var(--ring);
}
.hc-layer-select {
    background: var(--background);
    color: var(--foreground);
    border: 1px solid var(--border);
    border-radius: 3px;
    font-size: 10px;
    padding: 1px 4px;
}
</style>
