<script setup lang="ts">
import { computed } from 'vue';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { Wifi, WifiOff, Loader2, ShieldAlert, MousePointer2, Move, Globe, FileText } from 'lucide-vue-next';
import { useI18n } from '@/i18n';
import Tooltip from '@/components/ui/Tooltip.vue';

const net = useNetworkStore();
const project = useProjectStore();
const ui = useUiStore();
const { t } = useI18n();

const statusColor = computed(() => {
    switch (net.status) {
        case 'ready': return 'text-emerald-500';
        case 'connecting':
        case 'authenticating': return 'text-amber-500';
        case 'error': return 'text-red-500';
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

const wallStateLabel = computed(() => {
    if (project.publishedAt != null) return t.value.status.published;
    return t.value.status.draft;
});
</script>

<template>
  <footer class="flex items-center justify-between h-6 px-3 text-[10px] bg-[color:var(--card)] border-t border-[color:var(--border)] text-[color:var(--muted-foreground)] select-none tabular-nums">
    <div class="flex items-center gap-3">
      <Tooltip :text="statusLabel">
        <span class="flex items-center gap-1" :class="statusColor">
          <Loader2 v-if="net.status === 'connecting' || net.status === 'authenticating'" class="size-3 animate-spin" />
          <Wifi v-else-if="net.status === 'ready'" class="size-3" />
          <ShieldAlert v-else-if="net.status === 'error'" class="size-3" />
          <WifiOff v-else class="size-3" />
          {{ statusLabel }}
        </span>
      </Tooltip>
      <Tooltip :text="ui.activeTool === 'select' ? t.tools.selectTool : t.tools.moveTool">
        <span class="flex items-center gap-1">
          <MousePointer2 v-if="ui.activeTool === 'select'" class="size-3" />
          <Move v-else class="size-3" />
          {{ ui.activeTool === 'select' ? 'Select' : 'Move' }}
        </span>
      </Tooltip>
      <span v-if="net.wallSize" class="flex items-center gap-1">
        {{ t.status.wall(net.wallSize.w, net.wallSize.h) }}
      </span>
      <Tooltip v-if="project.wallId" :text="t.status.wallStateTip">
        <span class="flex items-center gap-1" :class="project.publishedAt != null ? 'text-emerald-500' : ''">
          <Globe v-if="project.publishedAt != null" class="size-3" />
          <FileText v-else class="size-3" />
          {{ wallStateLabel }}
        </span>
      </Tooltip>
    </div>
    <div class="flex items-center gap-3">
      <span v-if="project.state">
        {{ t.status.elements(project.state.elements.length, project.state.version) }}
      </span>
      <Tooltip v-if="net.sessionId" :text="t.status.sessionFull(net.sessionId)">
        <span class="font-mono opacity-70">{{ t.status.session(net.sessionId) }}</span>
      </Tooltip>
    </div>
  </footer>
</template>
