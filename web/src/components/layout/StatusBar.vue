<script setup lang="ts">
import { computed } from 'vue';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import {
    Wifi, WifiOff, Loader2, ShieldAlert,
    MousePointer2, Move, Hand, Minus, MoveRight, Circle, Star, Brush, PaintBucket,
    Lock, Unlock,
} from 'lucide-vue-next';
import { useI18n } from '@/i18n';
import Tooltip from '@/components/ui/Tooltip.vue';

const net = useNetworkStore();
const project = useProjectStore();
const ui = useUiStore();
const { t } = useI18n();

const statusColor = computed(() => {
    switch (net.status) {
        case 'ready': return 'text-[color:var(--ctp-green)]';
        case 'connecting':
        case 'authenticating': return 'text-[color:var(--ctp-peach)]';
        case 'error': return 'text-[color:var(--destructive)]';
        default: return 'text-[color:var(--muted-foreground)]';
    }
});

const statusLabel = computed(() => {
    switch (net.status) {
        case 'ready': return t.value.status.ready;
        case 'authenticating': return t.value.status.authenticating;
        case 'connecting': return t.value.status.connecting;
        case 'error': return net.connectionError ?? t.value.status.error;
        default: return t.value.status.disconnected;
    }
});

/**
 * 业务错误（改变量失败 / 规则校验不过 / 锁定失败等）单独显示，不染红连接指示。
 * 连接本身出错时 statusLabel 已在讲同一件事，此处不重复。
 */
const noticeText = computed(() =>
    net.status === 'error' ? null : net.lastError);

const wallStateLabel = computed(() => {
    if (project.isLocked) return t.value.status.locked;
    return t.value.status.unlocked;
});

const activeToolMeta = computed(() => {
    switch (ui.activeTool) {
        case 'select':
            return { icon: MousePointer2, tip: t.value.tools.selectTool, label: t.value.status.toolSelect };
        case 'move':
            return { icon: Move, tip: t.value.tools.moveTool, label: t.value.status.toolMove };
        case 'hand':
            return { icon: Hand, tip: t.value.tools.handTool, label: t.value.status.toolHand };
        case 'line':
            return { icon: Minus, tip: t.value.tools.lineTool, label: t.value.status.toolLine };
        case 'arrow':
            return { icon: MoveRight, tip: t.value.tools.arrowTool, label: t.value.status.toolArrow };
        case 'circle':
            return { icon: Circle, tip: t.value.tools.circleTool, label: t.value.status.toolCircle };
        case 'star':
            return { icon: Star, tip: t.value.tools.starTool, label: t.value.status.toolStar };
        case 'brush':
            return { icon: Brush, tip: t.value.tools.brushTool, label: t.value.status.toolBrush };
        case 'paint-bucket':
            return { icon: PaintBucket, tip: t.value.tools.paintBucketTool, label: t.value.status.toolPaintBucket };
    }
});
</script>

<template>
  <footer class="flex items-center justify-between h-6 px-3 text-xs bg-[color:var(--card)] border-t border-[color:var(--border)] text-[color:var(--muted-foreground)] select-none tabular-nums">
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
      <Tooltip :text="activeToolMeta.tip">
        <span class="flex items-center gap-1">
          <component :is="activeToolMeta.icon" class="size-3" />
          {{ activeToolMeta.label }}
        </span>
      </Tooltip>
      <span v-if="net.wallSize" class="flex items-center gap-1">
        {{ t.status.wall(net.wallSize.w, net.wallSize.h) }}
      </span>
      <Tooltip v-if="project.wallId" :text="t.status.wallStateTip">
        <span class="flex items-center gap-1" :class="project.isLocked ? 'text-[color:var(--ctp-peach)]' : ''">
          <Lock v-if="project.isLocked" class="size-3" />
          <Unlock v-else class="size-3" />
          {{ wallStateLabel }}
        </span>
      </Tooltip>
      <Tooltip v-if="noticeText" :text="noticeText">
        <span class="flex items-center gap-1 text-[color:var(--ctp-peach)] max-w-[32ch] truncate">
          <ShieldAlert class="size-3 shrink-0" />
          {{ noticeText }}
        </span>
      </Tooltip>
    </div>
    <div class="flex items-center gap-3">
      <span v-if="project.state">
        {{ t.status.elements(project.allElements.length, project.state.version) }}
      </span>
      <Tooltip v-if="net.sessionId" :text="t.status.sessionFull(net.sessionId)">
        <span class="font-mono opacity-70">{{ t.status.session(net.sessionId) }}</span>
      </Tooltip>
    </div>
  </footer>
</template>
