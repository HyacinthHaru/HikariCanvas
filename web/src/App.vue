<script setup lang="ts">
import { defineAsyncComponent, onMounted, ref, watch } from 'vue';
import { useDebounceFn, useEventListener } from '@vueuse/core';
import TopBar from '@/components/layout/TopBar.vue';
import LeftTools from '@/components/layout/LeftTools.vue';
import CanvasView from '@/components/layout/CanvasView.vue';
import IconLibrary from '@/components/layout/IconLibrary.vue';
import RightPanel from '@/components/layout/RightPanel.vue';
import StatusBar from '@/components/layout/StatusBar.vue';
import LogDrawer from '@/components/layout/LogDrawer.vue';
import VariablePanel from '@/components/variables/VariablePanel.vue';
import ScheduleManagerModal from '@/components/schedule/ScheduleManagerModal.vue';
import RailNetworkModal from '@/components/rail/RailNetworkModal.vue';
import HomePage from '@/components/HomePage.vue';
import TemplateGallery from '@/components/template/TemplateGallery.vue';
import HelpModal from '@/components/HelpModal.vue';
import { useUiStore } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { useVariableStore } from '@/stores/variables';
import { useTimelineStore } from '@/stores/timeline';
import { createWsClient, pickInitialToken } from '@/network/wsClient';
import { createNudgeQueue } from '@/composables/nudgeQueue';
import { setUploadAuthProvider, setVariableContextProvider } from '@/render/PreviewRenderer';

const ui = useUiStore();
const net = useNetworkStore();
const project = useProjectStore();
const variables = useVariableStore();
const timeline = useTimelineStore();
// 时间轴 dock 懒加载拆独立 chunk（仿 lexical；首次开 dock 才下载，守住 700KB 线）。
const TimelineDock = defineAsyncComponent(() => import('@/components/timeline/TimelineDock.vue'));
// 积木脚本编辑器全屏 overlay 懒加载拆 script-engine chunk（首次开才下载）。
const ScriptEditorOverlay = defineAsyncComponent(() => import('@/script/canvas/ScriptEditorOverlay.vue'));

// URL 没 token → 显示首页（HomePage 列出 walls）；有 token → 走编辑器
const showHomePage = ref(false);
const wsClient = createWsClient();
// 把 sessionId 注入 PreviewRenderer 的 /api/upload/{hash} URL 构造器（lazy 读 store）
setUploadAuthProvider(() => net.sessionId ?? null);
// 把 wallId + variable store 注入 PreviewRenderer 让 drawText 可解析 ${var:X}
// + 画 placeholder hint chip（编辑器内长占位符撑爆 layout 的修复）
setVariableContextProvider(() => ({ wallId: project.wallId, store: variables }));

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

// 新 element 被 server 加到 state 后自动选中，方便立刻进 Properties 编辑
watch(() => project.lastAddedElementId, (id) => {
    if (id) {
        ui.selectElement(id);
        project.lastAddedElementId = null;
    }
});

// ---------- 方向键微移：本地累积 + 松手后一次提交 ----------

/**
 * 方向键微移走"本地累积 + 松手提交"，不再每次 keydown 发一帧（见 {@link createNudgeQueue}
 * 头注：按住方向键会被后端限流丢帧、甚至被 1008 断连踢下线）。与画布拖动同一纪律。
 */
const nudgeQueue = createNudgeQueue({
    getElement: (id) => project.elementById(id) ?? null,
    send: (id, x, y) => { wsClient.send('element.transform', { elementId: id, x, y }); },
});

/** 兜底：一直按着不松手（键盘自动重复不产生 keyup）时也要把落点发出去。 */
const flushNudgeDebounced = useDebounceFn(() => nudgeQueue.flush(), 200);

// 松开方向键 = 一次微移结束，立刻提交落点（不用等防抖）。
useEventListener(document, 'keyup', (e: KeyboardEvent) => {
    if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key)) nudgeQueue.flush();
});
// 切走窗口时 keyup 可能永远收不到，兜一手。
useEventListener(window, 'blur', () => nudgeQueue.flush());

// 全局快捷键。跳过 input/textarea/contenteditable 以免 typing 时误触
useEventListener(document, 'keydown', (e: KeyboardEvent) => {
    const t = e.target as HTMLElement | null;
    if (t && (t.matches?.('input, textarea, select') || t.isContentEditable)) return;

    const selectedId = ui.selectedElementId;
    const ctrl = e.ctrlKey || e.metaKey;

    // lock-state 守卫：locked wall 时拒所有编辑型快捷键（删除 / undo / redo / 微移）；
    // zoom / select / theme / locale / Cmd+A 等非编辑快捷键不受影响。
    // readonly overlay 挡住 stage 鼠标，但快捷键直接走 window 必须独立守卫。
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
        // 时间轴 dock 选中关键帧时，Delete 归 dock 删帧（不误删画布元素，两套选中独立）
        if (timeline.dockOpen && (timeline.selectedGroups.size > 0 || timeline.selectedKeyframeId)) {
            e.preventDefault();
            return;
        }
        // 多选批量删
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

    // Cmd+A 全选当前活动层所有元素
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
        const step = e.shiftKey ? 10 : 1;
        let dx = 0;
        let dy = 0;
        if (e.key === 'ArrowLeft') dx = -step;
        else if (e.key === 'ArrowRight') dx = step;
        else if (e.key === 'ArrowUp') dy = -step;
        else if (e.key === 'ArrowDown') dy = step;

        // 多选时所有选中 element 同步微移
        if (ui.selectedCount > 1) {
            e.preventDefault();
            for (const id of ui.selectedIds) nudgeQueue.nudge(id, dx, dy);
            flushNudgeDebounced();
            return;
        }
        if (!selectedId) return;
        if (!project.elementById(selectedId)) return;
        e.preventDefault();
        nudgeQueue.nudge(selectedId, dx, dy);
        flushNudgeDebounced();
    }
});
</script>

<template>
  <HomePage v-if="showHomePage" />
  <div v-else class="h-screen w-screen flex flex-col">
    <TopBar />
    <div class="flex-1 flex min-h-0 relative">
      <LeftTools v-if="!ui.leftCollapsed" />
      <!-- 图标库 panel。挂在 LeftTools 右侧、CanvasView 上方（absolute）。 -->
      <IconLibrary />
      <CanvasView />
      <RightPanel v-if="!ui.rightCollapsed" />
      <LogDrawer />
    </div>
    <!-- 时间轴 AE 风底部 dock（布局流兄弟，压缩画布可视区；懒加载拆 chunk） -->
    <TimelineDock v-if="timeline.dockOpen" />
    <StatusBar />
    <TemplateGallery />
    <HelpModal />
    <!-- 变量管理面板 fixed drawer，z-50；与 LogDrawer / TemplateGallery 同层 modal -->
    <VariablePanel />
    <!-- 列车时刻表管理 modal -->
    <ScheduleManagerModal />
    <!-- 铁路网络（线路 + 站点 + 车次 + 时刻表）管理 modal -->
    <RailNetworkModal v-if="ui.railNetworkOpen" @close="ui.closeRailNetwork()" />
    <!-- 积木脚本编辑器全屏 overlay（懒加载拆 script-engine chunk） -->
    <ScriptEditorOverlay v-if="ui.scriptEditorOpen" />
  </div>
</template>
