<script setup lang="ts">
/**
 * 底部 Elements 区：列出当前 active layer 内的元素 + 拖拽重排（element.reorder op）
 * + visible / lock 行内 toggle。
 *
 * 与 LayerPanel.vue（顶部图层面板）职责分离：这里只管"层内元素"，层级重排由 LayerPanel 处理。
 *
 * 直接用 stores + ws client；保持与原 RightPanel 行为完全一致。
 */
import { computed, ref } from 'vue';
import { Layers, Eye, EyeOff, Lock, Unlock } from 'lucide-vue-next';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';

const project = useProjectStore();
const ui = useUiStore();
const ws = getWsClient();
const { t } = useI18n();

const elementCount = computed(() => project.state?.elements?.length ?? 0);
const activeLayerLocked = computed(() => project.activeLayerLocked);

// ---------- Element 在活动层内的 z-order 重排（HTML5 drag & drop） ----------

const dragIdx = ref(-1);
const dragOverIdx = ref(-1);

function onElementDragStart(ev: DragEvent, idx: number) {
    dragIdx.value = idx;
    if (ev.dataTransfer) {
        ev.dataTransfer.effectAllowed = 'move';
        ev.dataTransfer.setData('text/plain', String(idx));
    }
}

function onElementDragOver(ev: DragEvent, idx: number) {
    ev.preventDefault();
    dragOverIdx.value = idx;
    if (ev.dataTransfer) ev.dataTransfer.dropEffect = 'move';
}

function onElementDragLeave() {
    dragOverIdx.value = -1;
}

function onElementDrop(ev: DragEvent, idx: number) {
    ev.preventDefault();
    const from = dragIdx.value;
    dragIdx.value = -1;
    dragOverIdx.value = -1;
    if (from < 0 || from === idx) return;
    if (!project.state) return;
    const el = project.state.elements[from];
    if (!el) return;
    // optimistic reorder
    const arr = project.state.elements;
    const [moved] = arr.splice(from, 1);
    arr.splice(idx, 0, moved);
    ws.send('element.reorder', { elementId: el.id, index: idx });
}

function onElementDragEnd() {
    dragIdx.value = -1;
    dragOverIdx.value = -1;
}
</script>

<template>
  <section class="flex flex-col border-t border-[color:var(--border)] max-h-[35%] min-h-[100px]">
    <header class="flex items-center gap-2 px-3 h-9 border-b border-[color:var(--border)] text-xs font-medium uppercase tracking-wider text-[color:var(--muted-foreground)]">
      <Layers class="size-3.5" />
      <span>{{ t.elements.header }}</span>
      <span class="ml-auto text-xs font-normal normal-case">{{ t.elements.count(elementCount) }}</span>
    </header>
    <div v-if="activeLayerLocked" class="px-3 py-1.5 text-xs text-[color:var(--muted-foreground)] bg-[color:var(--muted)] border-b border-[color:var(--border)]">
      {{ t.elements.lockedHint }}
    </div>
    <ul class="overflow-y-auto flex-1">
      <li v-if="elementCount === 0" class="p-3 text-xs text-[color:var(--muted-foreground)]">
        {{ activeLayerLocked ? t.elements.emptyLocked : t.elements.empty }}
      </li>
      <li
        v-for="(el, idx) in project.state?.elements ?? []"
        :key="el.id"
        :draggable="!activeLayerLocked"
        class="px-3 py-1.5 flex items-center gap-2 text-xs cursor-pointer hover:bg-[color:var(--accent)] transition-colors"
        :class="{
          'bg-[color:var(--accent)]': ui.isSelected(el.id),
          'opacity-50': dragIdx === idx,
          'ring-1 ring-[color:var(--ring)] ring-inset': dragOverIdx === idx && dragIdx !== idx,
          'cursor-not-allowed': activeLayerLocked,
        }"
        @click="(e) => (e.shiftKey || e.metaKey || e.ctrlKey) ? ui.toggleSelection(el.id) : ui.selectElement(el.id)"
        @dragstart="(e) => onElementDragStart(e, idx)"
        @dragover="(e) => onElementDragOver(e, idx)"
        @dragleave="onElementDragLeave"
        @drop="(e) => onElementDrop(e, idx)"
        @dragend="onElementDragEnd"
      >
        <span class="w-5 text-xs text-[color:var(--muted-foreground)] tabular-nums">{{ idx }}</span>
        <span class="flex-1 truncate">
          {{ el.type }}
          <span v-if="el.type === 'text'" class="opacity-60">· "{{ (el as any).text }}"</span>
        </span>
        <button
          class="p-0.5 rounded hover:bg-[color:var(--background)] disabled:opacity-30 disabled:cursor-not-allowed"
          :title="activeLayerLocked ? t.elements.lockedHint : t.elements.toggleVisible(el.visible)"
          :disabled="activeLayerLocked"
          @click.stop="ws.send('element.update', { elementId: el.id, patch: { visible: !el.visible } }); (el as any).visible = !el.visible;"
        >
          <component :is="el.visible ? Eye : EyeOff" class="size-3" :class="el.visible ? '' : 'opacity-40'" />
        </button>
        <button
          class="p-0.5 rounded hover:bg-[color:var(--background)] disabled:opacity-30 disabled:cursor-not-allowed"
          :title="activeLayerLocked ? t.elements.lockedHint : t.elements.toggleLock(el.locked)"
          :disabled="activeLayerLocked"
          @click.stop="ws.send('element.update', { elementId: el.id, patch: { locked: !el.locked } }); (el as any).locked = !el.locked;"
        >
          <component :is="el.locked ? Lock : Unlock" class="size-3" :class="el.locked ? '' : 'opacity-40'" />
        </button>
      </li>
    </ul>
  </section>
</template>
