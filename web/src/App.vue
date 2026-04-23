<script setup lang="ts">
import { onMounted, watch } from 'vue';
import { useEventListener } from '@vueuse/core';
import TopBar from '@/components/layout/TopBar.vue';
import LeftTools from '@/components/layout/LeftTools.vue';
import CanvasView from '@/components/layout/CanvasView.vue';
import RightPanel from '@/components/layout/RightPanel.vue';
import StatusBar from '@/components/layout/StatusBar.vue';
import LogDrawer from '@/components/layout/LogDrawer.vue';
import { useUiStore } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { createWsClient, pickInitialToken } from '@/network/wsClient';

const ui = useUiStore();
const net = useNetworkStore();
const project = useProjectStore();

const wsClient = createWsClient();

onMounted(() => {
    const { token, source } = pickInitialToken();
    net.pushLog('meta', `token source: ${source}`);
    wsClient.connect(token);

    // 暴露调试入口到 console
    (window as unknown as Record<string, unknown>).__hk = {
        send: (op: string, payload?: unknown) => wsClient.send(op, payload),
        get ws() { return wsClient.raw; },
        get authenticated() { return net.authenticated; },
    };
});

// M5-D2 P1：新 element 被 server 加到 state 后自动选中，方便立刻进 Properties 编辑
watch(() => project.lastAddedElementId, (id) => {
    if (id) {
        ui.selectElement(id);
        project.lastAddedElementId = null;
    }
});

// M5-B7 全局快捷键。跳过 input/textarea/contenteditable 以免 typing 时误触
useEventListener(document, 'keydown', (e: KeyboardEvent) => {
    const t = e.target as HTMLElement | null;
    if (t && (t.matches?.('input, textarea, select') || t.isContentEditable)) return;

    const selectedId = ui.selectedElementId;
    const ctrl = e.ctrlKey || e.metaKey;

    if (e.key === 'Delete' || e.key === 'Backspace') {
        if (selectedId) {
            e.preventDefault();
            wsClient.send('element.delete', { elementId: selectedId });
            ui.selectElement(null);
        }
        return;
    }

    if (ctrl && !e.shiftKey && e.key.toLowerCase() === 'z') {
        e.preventDefault();
        wsClient.send('undo', {});
        return;
    }
    if (ctrl && ((e.shiftKey && e.key.toLowerCase() === 'z') || e.key.toLowerCase() === 'y')) {
        e.preventDefault();
        wsClient.send('redo', {});
        return;
    }

    if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key)) {
        if (!selectedId) return;
        const el = project.elementById(selectedId);
        if (!el) return;
        e.preventDefault();
        const step = e.shiftKey ? 10 : 1;
        let dx = 0;
        let dy = 0;
        if (e.key === 'ArrowLeft') dx = -step;
        else if (e.key === 'ArrowRight') dx = step;
        else if (e.key === 'ArrowUp') dy = -step;
        else if (e.key === 'ArrowDown') dy = step;
        const newX = el.x + dx;
        const newY = el.y + dy;
        el.x = newX;
        el.y = newY;
        wsClient.send('element.transform', { elementId: selectedId, x: newX, y: newY });
    }
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
