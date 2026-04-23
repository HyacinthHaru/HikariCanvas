<script setup lang="ts">
import { onMounted } from 'vue';
import TopBar from '@/components/layout/TopBar.vue';
import LeftTools from '@/components/layout/LeftTools.vue';
import CanvasView from '@/components/layout/CanvasView.vue';
import RightPanel from '@/components/layout/RightPanel.vue';
import StatusBar from '@/components/layout/StatusBar.vue';
import LogDrawer from '@/components/layout/LogDrawer.vue';
import { useUiStore } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';
import { createWsClient, pickInitialToken } from '@/network/wsClient';

const ui = useUiStore();
const net = useNetworkStore();

onMounted(() => {
    const ws = createWsClient();
    const { token, source } = pickInitialToken();
    net.pushLog('meta', `token source: ${source}`);
    ws.connect(token);

    // 暴露调试入口到 console（替代原 __hk）
    (window as unknown as Record<string, unknown>).__hk = {
        send: (op: string, payload?: unknown) => ws.send(op, payload),
        get ws() { return ws.raw; },
        get authenticated() { return net.authenticated; },
    };
});
</script>

<template>
  <div class="h-screen w-screen flex flex-col">
    <TopBar />
    <div class="flex-1 flex min-h-0 relative">
      <LeftTools v-if="!ui.leftCollapsed" />
      <CanvasView />
      <RightPanel v-if="!ui.rightCollapsed" />
      <LogDrawer />
    </div>
    <StatusBar />
  </div>
</template>
