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
import { useI18n } from '@/i18n';

const { t } = useI18n();
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

// 新 element 被 server 加到 state 后自动选中，方便立刻进 Properties 编辑。
// 整批选中：粘贴 / 批量导入是一条条加进来的，逐条替换选中的话最后只剩一个被选中
// （见 project store 的 lastAddedElementIds）。
watch(() => project.lastAddedElementIds, (ids) => {
    if (ids.length > 0) ui.selectMany(ids);
});

// 拖放兜底：只有画布那一块接收投放，文件落在右栏 / 顶栏 / 时间轴上时浏览器会执行默认动作
// ——直接把编辑器页面导航成那张图片，会话当场断掉，得退回去重连。这里在 window 层把
// 带文件（和图标拖拽）的 dragover / drop 一律拦下，落在非投放区就静默丢弃。
// 只拦这两类，纯文本拖拽仍走浏览器默认行为（输入框里拖字不受影响）。
function isSwallowableDrag(e: DragEvent): boolean {
    const types = e.dataTransfer?.types;
    if (!types) return false;
    const list = Array.from(types);
    return list.includes('Files') || list.includes('application/x-hikari-icon');
}
useEventListener(window, 'dragover', (e: DragEvent) => {
    if (isSwallowableDrag(e)) e.preventDefault();
});
useEventListener(window, 'drop', (e: DragEvent) => {
    if (isSwallowableDrag(e)) e.preventDefault();
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

/**
 * 有元素因为上锁被跳过时说一声，否则用户按了 Delete / 方向键什么都没发生，只会以为卡了。
 * 走状态栏的提示位（业务提示通道），不染红连接指示。
 */
function notifyLockedSkipped(count: number): void {
    if (count <= 0) return;
    net.lastError = t.value.elements.lockedSkipped(count);
}

// 全局快捷键。跳过 input/textarea/contenteditable 以免 typing 时误触
useEventListener(document, 'keydown', (e: KeyboardEvent) => {
    // 变量名别再叫 t —— 外层的 t 是 i18n 文案（notifyLockedSkipped 要用），重名会看晕人
    const target = e.target as HTMLElement | null;
    if (target && (target.matches?.('input, textarea, select') || target.isContentEditable)) return;

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
            // 锁定的元素 / 锁定图层里的元素不删（元素锁后端根本不看，前端不拦就等于没锁）
            const deletable = project.editableIds(ids);
            for (const id of deletable) wsClient.send('element.delete', { elementId: id });
            notifyLockedSkipped(ids.length - deletable.length);
            ui.clearSelection();
            return;
        }
        if (selectedId) {
            e.preventDefault();
            if (!project.isElementEditable(selectedId)) {
                notifyLockedSkipped(1);
                return;
            }
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

        // 多选时所有选中 element 同步微移。锁定的跳过——微移是先改本地再提交，
        // 对锁定图层里的元素发出去会被后端拒，本地却已经动了，两边就此对不上。
        if (ui.selectedCount > 1) {
            e.preventDefault();
            const movable = project.editableIds(ui.selectedIds);
            for (const id of movable) nudgeQueue.nudge(id, dx, dy);
            notifyLockedSkipped(ui.selectedCount - movable.length);
            flushNudgeDebounced();
            return;
        }
        if (!selectedId) return;
        if (!project.elementById(selectedId)) return;
        e.preventDefault();
        if (!project.isElementEditable(selectedId)) {
            notifyLockedSkipped(1);
            return;
        }
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
