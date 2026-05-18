<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { useEventListener } from '@vueuse/core';
import TopBar from '@/components/layout/TopBar.vue';
import LeftTools from '@/components/layout/LeftTools.vue';
import CanvasView from '@/components/layout/CanvasView.vue';
import IconLibrary from '@/components/layout/IconLibrary.vue';
import RightPanel from '@/components/layout/RightPanel.vue';
import StatusBar from '@/components/layout/StatusBar.vue';
import LogDrawer from '@/components/layout/LogDrawer.vue';
import HomePage from '@/components/HomePage.vue';
import TemplateGallery from '@/components/template/TemplateGallery.vue';
import HelpModal from '@/components/HelpModal.vue';
import { useUiStore } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { createWsClient, pickInitialToken } from '@/network/wsClient';
import { setUploadAuthProvider } from '@/render/PreviewRenderer';

const ui = useUiStore();
const net = useNetworkStore();
const project = useProjectStore();

// M5.5：URL 没 token → 显示首页（HomePage 列出 walls）；有 token → 走编辑器
const showHomePage = ref(false);
const wsClient = createWsClient();
// M16 P1.1：把 sessionId 注入 PreviewRenderer 的 /api/upload/{hash} URL 构造器（lazy 读 store）
setUploadAuthProvider(() => net.sessionId ?? null);

onMounted(() => {
    const { token, source } = pickInitialToken();
    if (!token) {
        showHomePage.value = true;
        net.pushLog('meta', 'no token; showing homepage');
        // 仍暴露调试入口（仅 DEV；生产构建走 Vite 死代码消除，window.__hk 完全不存在）
        if (import.meta.env.DEV) {
            (window as unknown as Record<string, unknown>).__hk = {
                send: () => null,
                get ws() { return null; },
                get authenticated() { return false; },
            };
        }
        return;
    }
    net.pushLog('meta', `token source: ${source}`);
    wsClient.connect(token);

    // 暴露调试入口到 console（仅 DEV；生产构建走 Vite 死代码消除，window.__hk 完全不存在）
    if (import.meta.env.DEV) {
        (window as unknown as Record<string, unknown>).__hk = {
            send: (op: string, payload?: unknown) => wsClient.send(op, payload),
            get ws() { return wsClient.raw; },
            get authenticated() { return net.authenticated; },
        };
    }
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

    // 2026-05-14 lock-state 守卫：locked wall 时拒所有编辑型快捷键（删除 / undo / redo / 微移）；
    // zoom / select / theme / locale / Cmd+A 等非编辑快捷键不受影响。
    // 这是 lock-state 的核心：readonly overlay 挡住 stage 鼠标，但快捷键直接走 window 必须独立守卫。
    if (project.isLocked) {
        const isEditKey =
            e.key === 'Delete' ||
            e.key === 'Backspace' ||
            (ctrl && (e.key.toLowerCase() === 'z' || e.key.toLowerCase() === 'y')) ||
            ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key);
        if (isEditKey) {
            e.preventDefault();
            return;
        }
    }

    if (e.key === 'Delete' || e.key === 'Backspace') {
        // M8-F：多选批量删
        if (ui.selectedCount > 1) {
            e.preventDefault();
            const ids = Array.from(ui.selectedIds);
            for (const id of ids) wsClient.send('element.delete', { elementId: id });
            ui.clearSelection();
            return;
        }
        if (selectedId) {
            e.preventDefault();
            wsClient.send('element.delete', { elementId: selectedId });
            ui.selectElement(null);
        }
        return;
    }

    // M8-F：Cmd+A 全选当前活动层所有元素
    if (ctrl && e.key.toLowerCase() === 'a') {
        e.preventDefault();
        const ids = project.activeLayer.elements
            .filter((el) => el.visible)
            .map((el) => el.id);
        ui.selectMany(ids);
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
        // M8-F：多选时所有选中 element 同步微移
        if (ui.selectedCount > 1) {
            e.preventDefault();
            const step = e.shiftKey ? 10 : 1;
            let dx = 0;
            let dy = 0;
            if (e.key === 'ArrowLeft') dx = -step;
            else if (e.key === 'ArrowRight') dx = step;
            else if (e.key === 'ArrowUp') dy = -step;
            else if (e.key === 'ArrowDown') dy = step;
            for (const id of ui.selectedIds) {
                const el = project.elementById(id);
                if (!el) continue;
                const newX = el.x + dx;
                const newY = el.y + dy;
                el.x = newX; el.y = newY;
                wsClient.send('element.transform', { elementId: id, x: newX, y: newY });
            }
            return;
        }
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
  <HomePage v-if="showHomePage" />
  <div v-else class="h-screen w-screen flex flex-col">
    <TopBar />
    <div class="flex-1 flex min-h-0 relative">
      <LeftTools v-if="!ui.leftCollapsed" />
      <!-- M26.3：图标库 panel。挂在 LeftTools 右侧、CanvasView 上方（absolute）。 -->
      <IconLibrary />
      <CanvasView />
      <RightPanel v-if="!ui.rightCollapsed" />
      <LogDrawer />
    </div>
    <StatusBar />
    <TemplateGallery />
    <HelpModal />
  </div>
</template>
