<script setup lang="ts">
import { useNetworkStore } from '@/stores/network';
import { useUiStore } from '@/stores/ui';
import { X, Trash2 } from 'lucide-vue-next';
import { computed, nextTick, ref, watch } from 'vue';

const net = useNetworkStore();
const ui = useUiStore();

const logList = ref<HTMLElement | null>(null);

const displayLogs = computed(() => net.logs);

// 新增时自动滚到底
watch(displayLogs, async () => {
    await nextTick();
    if (logList.value) {
        logList.value.scrollTop = logList.value.scrollHeight;
    }
}, { deep: true });

function levelClass(level: string): string {
    switch (level) {
        case 'sent': return 'text-sky-400';
        case 'recv': return 'text-emerald-400';
        case 'err': return 'text-red-400';
        default: return 'text-[color:var(--muted-foreground)]';
    }
}
</script>

<template>
  <div
    v-if="ui.logDrawerOpen"
    class="absolute bottom-6 left-0 right-0 h-56 bg-[color:var(--card)] border-t border-[color:var(--border)] flex flex-col shadow-lg"
  >
    <header class="flex items-center justify-between h-8 px-3 border-b border-[color:var(--border)] text-xs">
      <span class="font-medium">WS Log ({{ net.logs.length }})</span>
      <div class="flex items-center gap-1">
        <button
          class="p-1 rounded hover:bg-[color:var(--accent)]"
          title="清空"
          @click="net.clearLogs()"
        >
          <Trash2 class="size-3.5" />
        </button>
        <button
          class="p-1 rounded hover:bg-[color:var(--accent)]"
          title="关闭"
          @click="ui.toggleLogDrawer()"
        >
          <X class="size-3.5" />
        </button>
      </div>
    </header>
    <div
      ref="logList"
      class="flex-1 overflow-y-auto px-3 py-2 font-mono text-[11px] leading-5 bg-[color:var(--background)]"
    >
      <div v-for="line in displayLogs" :key="line.ts + line.text" :class="levelClass(line.level)">
        <span class="text-[color:var(--muted-foreground)] mr-2">
          {{ new Date(line.ts).toISOString().slice(11, 23) }}
        </span>
        <span class="whitespace-pre-wrap">{{ line.text }}</span>
      </div>
    </div>
  </div>
</template>
