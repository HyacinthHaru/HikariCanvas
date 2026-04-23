<script setup lang="ts">
import { computed } from 'vue';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { Layers, Sliders, Eye, EyeOff, Lock, Unlock } from 'lucide-vue-next';

const project = useProjectStore();
const ui = useUiStore();

const selected = computed(() => {
    if (!ui.selectedElementId) return null;
    return project.elementById(ui.selectedElementId);
});

const elementCount = computed(() => project.state?.elements.length ?? 0);
</script>

<template>
  <aside class="w-72 bg-[color:var(--card)] border-l border-[color:var(--border)] flex flex-col">
    <!-- Properties（选中元素参数）-->
    <section class="flex-1 overflow-y-auto">
      <header class="flex items-center gap-2 px-3 h-9 border-b border-[color:var(--border)] text-xs font-medium uppercase tracking-wider text-[color:var(--muted-foreground)]">
        <Sliders class="size-3.5" />
        <span>Properties</span>
      </header>

      <div v-if="!selected" class="p-3 text-xs text-[color:var(--muted-foreground)]">
        未选中元素
      </div>
      <div v-else class="p-3 space-y-2 text-xs">
        <div class="flex items-center justify-between">
          <span class="text-[color:var(--muted-foreground)]">ID</span>
          <span class="font-mono text-[10px] truncate max-w-[140px]" :title="selected.id">
            {{ selected.id }}
          </span>
        </div>
        <div class="flex items-center justify-between">
          <span class="text-[color:var(--muted-foreground)]">Type</span>
          <span>{{ selected.type }}</span>
        </div>
        <div class="grid grid-cols-2 gap-2">
          <div class="flex flex-col">
            <label class="text-[color:var(--muted-foreground)] text-[10px]">x</label>
            <span class="font-mono">{{ selected.x }}</span>
          </div>
          <div class="flex flex-col">
            <label class="text-[color:var(--muted-foreground)] text-[10px]">y</label>
            <span class="font-mono">{{ selected.y }}</span>
          </div>
          <div class="flex flex-col">
            <label class="text-[color:var(--muted-foreground)] text-[10px]">w</label>
            <span class="font-mono">{{ selected.w }}</span>
          </div>
          <div class="flex flex-col">
            <label class="text-[color:var(--muted-foreground)] text-[10px]">h</label>
            <span class="font-mono">{{ selected.h }}</span>
          </div>
        </div>
        <p class="pt-2 text-[color:var(--muted-foreground)] italic">
          M5-B 属性面板接入后可直接编辑。
        </p>
      </div>
    </section>

    <!-- Layers -->
    <section class="flex flex-col border-t border-[color:var(--border)] max-h-[40%]">
      <header class="flex items-center gap-2 px-3 h-9 border-b border-[color:var(--border)] text-xs font-medium uppercase tracking-wider text-[color:var(--muted-foreground)]">
        <Layers class="size-3.5" />
        <span>Layers</span>
        <span class="ml-auto text-[10px] font-normal normal-case">{{ elementCount }} 项</span>
      </header>
      <ul class="overflow-y-auto flex-1">
        <li v-if="elementCount === 0" class="p-3 text-xs text-[color:var(--muted-foreground)]">
          画布为空
        </li>
        <li
          v-for="(el, idx) in project.state?.elements ?? []"
          :key="el.id"
          class="px-3 py-1.5 flex items-center gap-2 text-xs cursor-pointer hover:bg-[color:var(--accent)]"
          :class="{ 'bg-[color:var(--accent)]': ui.selectedElementId === el.id }"
          @click="ui.selectElement(el.id)"
        >
          <span class="w-5 text-[10px] text-[color:var(--muted-foreground)] tabular-nums">{{ idx }}</span>
          <span class="flex-1 truncate">
            {{ el.type }}
            <span v-if="el.type === 'text'" class="opacity-60">· "{{ (el as any).text }}"</span>
          </span>
          <component :is="el.visible ? Eye : EyeOff" class="size-3 opacity-50" />
          <component :is="el.locked ? Lock : Unlock" class="size-3 opacity-50" />
        </li>
      </ul>
    </section>
  </aside>
</template>
