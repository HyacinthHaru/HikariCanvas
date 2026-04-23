<script setup lang="ts">
import { computed } from 'vue';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { Wifi, WifiOff, Loader2, ShieldAlert } from 'lucide-vue-next';

const net = useNetworkStore();
const project = useProjectStore();

const statusColor = computed(() => {
    switch (net.status) {
        case 'ready': return 'text-emerald-400';
        case 'connecting':
        case 'authenticating': return 'text-yellow-400';
        case 'error': return 'text-red-400';
        default: return 'text-[color:var(--muted-foreground)]';
    }
});

const statusLabel = computed(() => {
    switch (net.status) {
        case 'ready': return 'ready';
        case 'authenticating': return 'authenticating';
        case 'connecting': return 'connecting';
        case 'error': return net.lastError ?? 'error';
        default: return 'disconnected';
    }
});
</script>

<template>
  <footer class="flex items-center justify-between h-6 px-3 text-[10px] bg-[color:var(--card)] border-t border-[color:var(--border)] text-[color:var(--muted-foreground)] select-none">
    <div class="flex items-center gap-3">
      <span class="flex items-center gap-1" :class="statusColor">
        <Loader2 v-if="net.status === 'connecting' || net.status === 'authenticating'" class="size-3 animate-spin" />
        <Wifi v-else-if="net.status === 'ready'" class="size-3" />
        <ShieldAlert v-else-if="net.status === 'error'" class="size-3" />
        <WifiOff v-else class="size-3" />
        {{ statusLabel }}
      </span>
      <span v-if="net.sessionId">
        session {{ net.sessionId.slice(0, 8) }}…
      </span>
      <span v-if="net.wallSize">
        wall {{ net.wallSize.w }}×{{ net.wallSize.h }}
      </span>
    </div>
    <div class="flex items-center gap-3">
      <span v-if="project.state">
        v{{ project.state.version }} · {{ project.state.elements.length }} elements
      </span>
    </div>
  </footer>
</template>
