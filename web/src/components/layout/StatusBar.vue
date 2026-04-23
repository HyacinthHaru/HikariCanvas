<script setup lang="ts">
import { computed } from 'vue';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { Wifi, WifiOff, Loader2, ShieldAlert } from 'lucide-vue-next';
import { useI18n } from '@/i18n';

const net = useNetworkStore();
const project = useProjectStore();
const { t } = useI18n();

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
        case 'ready': return t.value.status.ready;
        case 'authenticating': return t.value.status.authenticating;
        case 'connecting': return t.value.status.connecting;
        case 'error': return net.lastError ?? t.value.status.error;
        default: return t.value.status.disconnected;
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
        {{ t.status.session(net.sessionId) }}
      </span>
      <span v-if="net.wallSize">
        {{ t.status.wall(net.wallSize.w, net.wallSize.h) }}
      </span>
    </div>
    <div class="flex items-center gap-3">
      <span v-if="project.state">
        {{ t.status.elements(project.state.elements.length, project.state.version) }}
      </span>
    </div>
  </footer>
</template>
