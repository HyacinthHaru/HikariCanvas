<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useEventListener } from '@vueuse/core';
import { useProjectStore } from '@/stores/project';
import { isDrawTool, useUiStore } from '@/stores/ui';
import { getWsClient } from '@/network/wsClient';
import { renderProjectState, onIconReady, onPaletteReady } from '@/render/PreviewRenderer';
import { useI18n } from '@/i18n';
import type { Element } from '@/types/protocol';

import CanvasGridOverlay from '@/components/canvas/CanvasGridOverlay.vue';
import CanvasZoomBar from '@/components/canvas/CanvasZoomBar.vue';
import TextInlineEditor from '@/components/canvas/TextInlineEditor.vue';

import { useMarqueeSelection } from '@/composables/useMarqueeSelection';
import { useDrawToCreate } from '@/composables/useDrawToCreate';
import { useBrushHost } from '@/composables/useBrushHost';
import { useTransformerManager } from '@/composables/useTransformerManager';
import { useCanvasShortcuts } from '@/composables/useCanvasShortcuts';
import { usePanScroll } from '@/composables/usePanScroll';
import { useCanvasUpload } from '@/composables/useCanvasUpload';

const project = useProjectStore();
const ui = useUiStore();
const ws = getWsClient();
const { t } = useI18n();

// ---------- 核心 ref ----------
const canvasEl = ref<HTMLCanvasElement | null>(null);
const stageRef = ref<{ getNode(): unknown } | null>(null);
const layerRef = ref<{ getNode(): unknown } | null>(null);
const transformerRef = ref<{ getNode(): unknown } | null>(null);
const outerRef = ref<HTMLElement | null>(null);
const brushHostRef = ref<HTMLElement | null>(null);
const editingId = ref<string | null>(null);
const inlineEditorRef = ref<{ focus: () => void } | null>(null);
const fileInputRef = ref<HTMLInputElement | null>(null);

const editingElement = computed(() => {
    if (!editingId.value) return null;
    const el = project.elementById(editingId.value);
    return el && el.type === 'text' ? el : null;
});

const widthPx = computed(() => project.canvasPixelWidth || 256);
const heightPx = computed(() => project.canvasPixelHeight || 256);

const sizeLabel = computed(() => {
    if (!project.state) return t.value.canvas.empty;
    const c = project.state.canvas;
    return t.value.canvas.sizeLabel(c.widthMaps, c.heightMaps, widthPx.value, heightPx.value);
});

const stageConfig = computed(() => ({
    width: widthPx.value,
    height: heightPx.value,
}));

const transformerConfig = {
    enabledAnchors: [
        'top-left', 'top-right', 'bottom-left', 'bottom-right',
        'middle-left', 'middle-right', 'top-center', 'bottom-center',
    ],
    rotationSnaps: [] as number[],
    rotationSnapTolerance: 5,
    borderStroke: '#60a5fa',
    borderStrokeWidth: 1.5,
    anchorStroke: '#60a5fa',
    anchorFill: '#0b1120',
    // 2026-05-12 polish：anchor 由 8 升 12，方便拖拽；rotate 锚点也拉远 8px
    anchorSize: 12,
    rotateAnchorOffset: 32,
};

/** 悬停的 element id；用于画 hover 描边提示"可拖拽"。 */
const hoverId = ref<string | null>(null);

const elements = computed(() => project.state?.elements ?? []);

// ---------- M8-E：grid overlay ----------
const gridSize = computed(() => project.state?.canvas.gridSize ?? 0);

function onGridChange(ev: Event): void {
    const v = parseInt((ev.target as HTMLInputElement).value, 10);
    const size = Number.isFinite(v) ? Math.max(0, Math.min(256, v)) : 0;
    ws.send('canvas.grid', { size });
}

// ---------- composables ----------
const {
    marqueeConfig,
    start: marqueeStart,
    move: marqueeMove,
    end: marqueeEnd,
    cancel: marqueeCancel,
} = useMarqueeSelection();
const {
    drawPreview,
    start: drawStart,
    move: drawMove,
    end: drawEnd,
    cancel: drawCancel,
} = useDrawToCreate();
const {
    onBrushPointerDown,
    onBrushPointerMove,
    onBrushPointerUp,
    onBrushPointerCancel,
} = useBrushHost({ brushHostRef, widthPx: () => widthPx.value, heightPx: () => heightPx.value });
const { onTransformEnd } = useTransformerManager({
    transformerRef,
    layerRef,
    elementsWatchSource: () => elements.value,
});
useCanvasShortcuts();
const {
    onWheel,
    onMouseDown,
    onMouseMove,
    onMouseUpOrLeave,
    fitToViewport,
    isPanning,
} = usePanScroll({ outerRef, widthPx: () => widthPx.value, heightPx: () => heightPx.value });
const {
    uploadError,
    uploading,
    onCanvasDragOver,
    onCanvasDrop,
    onFileInputChange,
    triggerFileInput,
} = useCanvasUpload({ brushHostRef, fileInputRef });

// ---------- element 命中 ----------
function hitConfig(e: Element) {
    // Konva 用 offsetX/Y 把「bbox 左上」坐标转为「绕中心旋转」
    const hovered = hoverId.value === e.id;
    const selected = ui.isSelected(e.id);
    const canDrag = !e.locked && e.visible && !project.activeLayerLocked;
    // M9-E：绘制工具激活时，element-hit 整层 listening=false，让 mousedown 穿透到 stage
    // 启动 drag-to-create（PS/Figma 行为：drawTool 下点已有元素也是开始画新元素）
    // M17 F4：hand 工具同样关闭 element-hit listening，使左键直接被 outer 的 pan 处理。
    const drawing = isDrawTool(ui.activeTool) || ui.activeTool === 'hand';
    return {
        id: e.id,
        name: 'element-hit',
        x: e.x + e.w / 2,
        y: e.y + e.h / 2,
        width: e.w,
        height: e.h,
        offsetX: e.w / 2,
        offsetY: e.h / 2,
        rotation: e.rotation,
        // hover/选中时画轻微描边，让 PS 式拖拽提示可见；rgba 完全透明但 hit 可点
        fill: 'rgba(0,0,0,0.001)',
        stroke: hovered && !selected ? '#60a5fa' : undefined,
        strokeWidth: hovered && !selected ? 1 : 0,
        dash: hovered && !selected ? [4, 3] : undefined,
        draggable: canDrag,
        listening: !drawing,
    };
}

function onHitMouseEnter(ev: { target?: { getStage?: () => { container?: () => HTMLElement | undefined } | undefined } }, id: string) {
    hoverId.value = id;
    const stage = ev?.target?.getStage?.();
    const container = stage?.container?.();
    if (container) container.style.cursor = 'move';
}

function onHitMouseLeave(ev: { target?: { getStage?: () => { container?: () => HTMLElement | undefined } | undefined } }) {
    hoverId.value = null;
    const stage = ev?.target?.getStage?.();
    const container = stage?.container?.();
    if (container) container.style.cursor = 'default';
}

function onHitClick(ev: { cancelBubble?: boolean; evt?: MouseEvent | TouchEvent }, id: string): void {
    // 切到别的元素或者在编辑中点同一元素的非 textarea 区域 → 先收编辑态
    if (editingId.value && editingId.value !== id) finishEditing();
    // M8-F：Shift / Cmd / Ctrl click = 加选 / 切换；普通 click = 单选替换
    const me = ev.evt as MouseEvent | undefined;
    if (me && (me.shiftKey || me.metaKey || me.ctrlKey)) {
        ui.toggleSelection(id);
    } else {
        ui.selectElement(id);
    }
    if (ev) ev.cancelBubble = true;
}

function onHitDblClick(ev: { cancelBubble?: boolean }, id: string): void {
    const el = project.elementById(id);
    if (!el || el.type !== 'text') return;
    ui.selectElement(id);
    editingId.value = id;
    if (ev) ev.cancelBubble = true;
    nextTick(() => inlineEditorRef.value?.focus());
}

function onEditInput(ev: Event) {
    const el = editingElement.value;
    if (!el) return;
    const v = (ev.target as HTMLTextAreaElement).value;
    // optimistic
    (el as unknown as Record<string, unknown>).text = v;
    ws.send('element.update', { elementId: el.id, patch: { text: v } });
}

function finishEditing() {
    editingId.value = null;
}

function onEditKeydown(ev: KeyboardEvent) {
    if (ev.key === 'Escape') {
        ev.preventDefault();
        finishEditing();
        return;
    }
    // Enter = 完成；Shift+Enter = 换行（走默认行为）
    if (ev.key === 'Enter' && !ev.shiftKey && !ev.isComposing) {
        ev.preventDefault();
        finishEditing();
    }
}

// ---------- stage mouse 路由 ----------
//
// drawTool 优先：activeTool 是绘制工具时启动 useDrawToCreate；
// brush 工具走独立 PointerEvent 通道（useBrushHost），stage 事件直接 return；
// 否则启动 useMarqueeSelection。
// 拖出窗口兜底 mouseup 见下方 window listener。

interface KonvaStageNode { getStage?: () => { getPointerPosition?: () => { x: number; y: number } | null } | null }
type MouseEvt = MouseEvent | TouchEvent;
interface StageEvt {
    target: KonvaStageNode & { getType?: () => string; hasName?: (n: string) => boolean };
    evt?: MouseEvt;
}

function onStageMouseDown(ev: StageEvt): void {
    const node = ev.target;
    if (!node) return;
    const isElementHit = node.hasName?.('element-hit') ?? false;
    // drawTool 下 element-hit listening=false，target 实际是 stage 根，所以下面 isElementHit 必为 false
    if (isElementHit) return;  // element 点击由 onHitClick 处理（仅 select/move 工具）

    // alt / middle 让 outer onMouseDown 接管 pan
    const evt = ev.evt as MouseEvt | undefined;
    if (evt && ((evt as MouseEvent).button === 1 || (evt as MouseEvent).altKey)) return;

    // M17 F4：hand 工具 / 按住 Space 临时切的 hand —— 左键交给 outer pan，不启动 marquee / draw。
    if (ui.activeTool === 'hand') return;

    const stage = node.getStage?.();
    const pos = stage?.getPointerPosition?.();
    if (!pos) return;

    // M12-C：brush 工具走 PointerEvent + BrushController（独立路径），stage Konva 事件不动
    if (ui.activeTool === 'brush') return;
    // M9-E：绘制工具激活时启动 drag-to-create；其他工具启动 marquee
    if (isDrawTool(ui.activeTool)) {
        if (editingId.value) finishEditing();
        drawStart(pos);
        return;
    }

    marqueeStart(pos, (evt as MouseEvent | undefined)?.shiftKey ?? false);
}

function onStageMouseMove(ev: StageEvt): void {
    const stage = ev.target?.getStage?.();
    const pos = stage?.getPointerPosition?.();
    if (!pos) return;
    if (drawMove(pos)) return;
    marqueeMove(pos);
}

function onStageMouseUp(): void {
    // M9-E：绘制工具的 drag-to-create 完成
    if (drawEnd()) return;

    const res = marqueeEnd();
    if (res.outcome === 'idle') return;
    if (res.outcome === 'click-empty') {
        // click 空白：清编辑态 + 清选中（shift 时保留现有选中）
        if (editingId.value) finishEditing();
        if (!res.additive) ui.clearSelection();
        return;
    }
    // committed：marqueeSel 内已处理 selectMany / addToSelection；这里仅在非 additive 时收编辑态
    if (!res.additive && editingId.value) finishEditing();
}

// window 兜底：拖出窗口后松手时清掉 marquee / drawDrag
useEventListener(window, 'mouseup', () => {
    marqueeCancel();
    drawCancel();  // 拖出窗口 = 取消本次创建
});

/** 双击 stage 空白处：取消所有选中 + 退出编辑（用户实测后明确要求的 escape 路径）。 */
function onStageDblClick(ev: { target: { getType?: () => string; hasName?: (n: string) => boolean } }): void {
    const node = ev.target as { getType?: () => string; hasName?: (n: string) => boolean } | null;
    if (!node) return;
    const isElementHit = node.hasName?.('element-hit') ?? false;
    if (!isElementHit) {
        if (editingId.value) finishEditing();
        ui.selectElement(null);
    }
}

// 切换 Move 工具时也应主动收掉编辑态——避免"Move 模式下拖一个、textarea 仍浮在另一个上"
// M9-D：切到绘制工具（line/arrow/circle/star）时也清 selection，避免"激活创建工具+持有 selection"
// 的矛盾视觉（transformer 还在但用户期望拖出新元素）
// M9-E：清掉进行中的 marquee / drawDrag 状态，避免边缘 case（拖动中按快捷键切工具）
watch(() => ui.activeTool, (next) => {
    if (editingId.value) finishEditing();
    if (isDrawTool(next)) {
        ui.clearSelection();
    }
    marqueeCancel();
    drawCancel();
});

/**
 * M9-D：cursor 跟随 activeTool。绘制工具显示 crosshair；其他默认 cursor。
 * M17 F4：hand 工具 grab；正在 pan grabbing；Alt 按下且 select/move 也 grab（提示 Alt+左键 pan）。
 */
const cursorStyle = computed(() => {
    if (isPanning.value) return 'grabbing';
    if (ui.activeTool === 'hand') return 'grab';
    if (isDrawTool(ui.activeTool)) return 'crosshair';
    return 'default';
});

// ---------- M8-F：多选 drag 同步 ----------
//
// 拖单个 node 时若其在多选集合内，记录所有选中 element 的初始位置；dragmove 时按 leader
// 的 delta 同步其他 element 位置（视觉跟随）；dragend 时为所有选中 element 各发一条
// element.transform op。单选场景 dragInitial 为空，走原 fast path。

const dragInitial = ref<Map<string, { x: number; y: number }>>(new Map());

function onDragStart(id: string): void {
    // 用户在编辑 A 时点 B 直接拖，Konva 走 mousedown → dragstart 而不触发 click。
    // 这里兜底：拖任何元素时只要有 editing 状态就先收掉，避免 textarea 滞留在前一个元素上
    if (editingId.value && editingId.value !== id) finishEditing();

    // 多选：记录所有选中 element 的初始 (x, y) 供 dragmove 同步
    if (ui.selectedCount > 1 && ui.isSelected(id)) {
        const init = new Map<string, { x: number; y: number }>();
        for (const sid of ui.selectedIds) {
            const el = project.elementById(sid);
            if (el) init.set(sid, { x: el.x, y: el.y });
        }
        dragInitial.value = init;
    } else {
        dragInitial.value = new Map();
    }
}

interface DragEvt { target: {
    id?: () => string;
    x: () => number; y: () => number;
    width: () => number; height: () => number;
    x(v: number): void; y(v: number): void;
} }

function onDragMove(ev: DragEvt, id: string): void {
    // F2: 必须同步更新 leader 自身的 store，否则底层 PreviewRenderer（依赖 deep-watch
    //   project.state）不重绘 → 用户看到顶层 Konva hit-rect 跟手但实际像素不动。
    //   仅本地 mutate + requestDraw，不发 WS（避免 60fps 塞爆服务端，落地仍走 onDragEnd）。
    const initLeader = dragInitial.value.get(id);
    if (!initLeader) return;
    const node = ev.target;
    const w = node.width();
    const h = node.height();
    const leaderX = Math.round(node.x() - w / 2);
    const leaderY = Math.round(node.y() - h / 2);
    const leaderEl = project.elementById(id);
    if (leaderEl) {
        leaderEl.x = leaderX;
        leaderEl.y = leaderY;
    }

    if (dragInitial.value.size > 1) {
        // 多选：算 delta 同步其他被选中 element 的 store + Konva pos
        const dx = leaderX - initLeader.x;
        const dy = leaderY - initLeader.y;
        const layerNode = layerRef.value?.getNode() as undefined | { findOne(sel: string): DragEvt['target'] | undefined };
        if (layerNode) {
            for (const [sid, init] of dragInitial.value) {
                if (sid === id) continue;
                const el = project.elementById(sid);
                if (!el) continue;
                const newX = init.x + dx;
                const newY = init.y + dy;
                el.x = newX;
                el.y = newY;
                const other = layerNode.findOne(`#${sid}`);
                if (other) {
                    other.x(newX + el.w / 2);
                    other.y(newY + el.h / 2);
                }
            }
        }
    }
    requestDraw();
}

function onDragEnd(ev: DragEvt, id: string): void {
    const node = ev.target;
    const w = node.width();
    const h = node.height();
    const newX = Math.round(node.x() - w / 2);
    const newY = Math.round(node.y() - h / 2);
    const el = project.elementById(id);
    if (el && (el.x !== newX || el.y !== newY)) {
        // optimistic
        el.x = newX;
        el.y = newY;
        ws.send('element.transform', { elementId: id, x: newX, y: newY });
    }

    // 多选：把 leader 的 delta 应用到其他选中 element，逐个发 ws
    // M15.3 P0-1：判等用 init 记录的初始位置而非已被 dragmove mutate 后的 otherEl.x/y，
    //   否则 dragmove 同步后 otherEl.x === otherX 恒成立 → ws.send 永不发 → 服务端漏更新
    if (dragInitial.value.size > 1) {
        const initLeader = dragInitial.value.get(id);
        if (initLeader) {
            const dx = newX - initLeader.x;
            const dy = newY - initLeader.y;
            for (const [sid, init] of dragInitial.value) {
                if (sid === id) continue;
                const otherEl = project.elementById(sid);
                if (!otherEl) continue;
                const otherX = init.x + dx;
                const otherY = init.y + dy;
                if (init.x !== otherX || init.y !== otherY) {
                    // dragmove 已经乐观更新过；这里只发 ws 确保服务端落地
                    ws.send('element.transform', { elementId: sid, x: otherX, y: otherY });
                }
            }
        }
    }
    dragInitial.value = new Map();
}

// 重绘：state 或 editingId 变就重画 canvas
watch(() => project.state, () => requestDraw(), { deep: true, immediate: true });
watch(editingId, () => requestDraw());
onMounted(() => {
    requestDraw();
    // 字体异步加载；@font-face 就绪后再重画一次确保用上真字形
    if (document.fonts && typeof document.fonts.ready?.then === 'function') {
        document.fonts.ready.then(() => requestDraw());
    }
    // 图标异步加载就绪后请求重绘（每个新 source 第一次显示时占位 ?，加载完后真图替换）
    onIconReady(() => requestDraw());
    // M11-C：PaletteLut 异步加载完成后请求重绘（dither element 首帧 fallback clean，加载后切回 dither）
    onPaletteReady(() => requestDraw());

    // M17 F4：1024px 虚空白边让 scrollWidth / scrollHeight 比 viewport 大；
    // 默认 scrollLeft/Top = 0 会停在 padding 区导致看不到画布。nextTick 后居中。
    nextTick(() => {
        const outer = outerRef.value;
        if (!outer) return;
        outer.scrollLeft = Math.max(0, (outer.scrollWidth - outer.clientWidth) / 2);
        outer.scrollTop = Math.max(0, (outer.scrollHeight - outer.clientHeight) / 2);
    });
});

let drawPending = false;
let drawRafId: number | null = null;

// M16 P4.2 Konva 清理：组件 unmount 时显式 destroy stage（级联清理 Layer / Transformer
// 内部的 listeners + 2D context + cached image data）。Vue Konva 不主动 destroy node。
onBeforeUnmount(() => {
    if (drawRafId !== null) {
        cancelAnimationFrame(drawRafId);
        drawRafId = null;
    }
    const stageNode = stageRef.value?.getNode() as undefined | { destroy(): void };
    if (stageNode && typeof stageNode.destroy === 'function') {
        try { stageNode.destroy(); } catch (err) { console.warn('[CanvasView] stage.destroy failed', err); }
    }
    // 主动 null 引用让 GC 可回收（template ref 由 Vue 自动清，但显式重置更稳）
    stageRef.value = null;
    layerRef.value = null;
    transformerRef.value = null;
});

function requestDraw(): void {
    if (drawPending) return;
    drawPending = true;
    drawRafId = requestAnimationFrame(() => {
        drawRafId = null;
        drawPending = false;
        const el = canvasEl.value;
        if (!el) return;
        if (el.width !== widthPx.value) el.width = widthPx.value;
        if (el.height !== heightPx.value) el.height = heightPx.value;
        const ctx = el.getContext('2d');
        if (!ctx) return;
        const hide = editingId.value ? new Set([editingId.value]) : undefined;
        renderProjectState(ctx, project.state, hide);
    });
}
</script>

<template>
  <section
    ref="outerRef"
    class="flex-1 relative overflow-auto bg-[color:var(--background)]"
    :style="{ cursor: cursorStyle }"
    @wheel="onWheel"
    @mousedown="onMouseDown"
    @mousemove="onMouseMove"
    @mouseup="onMouseUpOrLeave"
    @mouseleave="onMouseUpOrLeave"
    @dragover="onCanvasDragOver"
    @drop="onCanvasDrop"
  >
    <!-- 画布居中容器
         M17 F4：1024px 虚空白边——padding 让 scrollWidth / scrollHeight 虚拟扩大，
         用户可以把画布拖到 viewport 任意角落。fitToViewport 仍按 v-stage 实际尺寸计算
         不受 padding 影响；初次居中由 scrollLeft / scrollTop 中点策略实现。 -->
    <div class="min-h-full min-w-full flex items-center justify-center hc-canvas-padding">
      <div
        class="relative shadow-lg ring-1 ring-[color:var(--border)] bg-white"
        :style="{
          width: `${widthPx * ui.zoom}px`,
          height: `${heightPx * ui.zoom}px`,
        }"
      >
        <!-- 外层一个 scale wrapper，让 canvas 和 Konva 都按原始像素绘制，DOM 缩放由 CSS 做 -->
        <div
          ref="brushHostRef"
          class="absolute origin-top-left"
          :style="{
            width: `${widthPx}px`,
            height: `${heightPx}px`,
            transform: `scale(${ui.zoom})`,
          }"
          @pointerdown="onBrushPointerDown"
          @pointermove="onBrushPointerMove"
          @pointerup="onBrushPointerUp"
          @pointercancel="onBrushPointerCancel"
        >
          <canvas
            ref="canvasEl"
            class="absolute inset-0 hc-canvas-layer"
            :style="{ width: `${widthPx}px`, height: `${heightPx}px` }"
          />
          <!-- M8-E：grid overlay（仅前端预览，不入 MC）。CSS 双线性渐变实现实线网格。 -->
          <CanvasGridOverlay :grid-size="gridSize" />
          <!-- 2026-05-14 lock-state readonly overlay：locked 时拦截所有 stage 鼠标事件。
               中间显示提示；owner 看到解锁按钮，非 owner 看到 "仅作者可解锁"。 -->
          <div
            v-if="project.isLocked"
            class="absolute inset-0 z-20 flex items-center justify-center bg-black/10 backdrop-blur-[1px] cursor-not-allowed"
            @mousedown.stop.prevent
            @click.stop.prevent
            @dblclick.stop.prevent
          >
            <div class="px-3 py-1.5 rounded bg-amber-500/90 text-black text-xs font-medium shadow-lg pointer-events-none">
              {{ project.isOwner ? t.wall.lockedOwnerHint : t.wall.lockedReaderHint }}
            </div>
          </div>
          <v-stage
            ref="stageRef"
            :config="stageConfig"
            class="absolute inset-0"
            @mousedown="onStageMouseDown"
            @mousemove="onStageMouseMove"
            @mouseup="onStageMouseUp"
            @touchstart="onStageMouseDown"
            @touchmove="onStageMouseMove"
            @touchend="onStageMouseUp"
            @dblclick="onStageDblClick"
          >
            <v-layer ref="layerRef">
              <v-rect
                v-for="el in elements"
                :key="el.id"
                :config="hitConfig(el)"
                @click="(ev: any) => onHitClick(ev, el.id)"
                @tap="(ev: any) => onHitClick(ev, el.id)"
                @dblclick="(ev: any) => onHitDblClick(ev, el.id)"
                @dragstart="() => onDragStart(el.id)"
                @dragmove="(ev: any) => onDragMove(ev, el.id)"
                @dragend="(ev: any) => onDragEnd(ev, el.id)"
                @transformend="(ev: any) => onTransformEnd(ev, el.id)"
                @mouseenter="(ev: any) => onHitMouseEnter(ev, el.id)"
                @mouseleave="(ev: any) => onHitMouseLeave(ev)"
              />
              <v-transformer ref="transformerRef" :config="transformerConfig" />
            </v-layer>
            <!-- M8-F：marquee 拖框可视层；M9-E：drag-to-create 预览同 layer -->
            <v-layer v-if="marqueeConfig || drawPreview" :listening="false">
              <v-rect v-if="marqueeConfig" :config="marqueeConfig" />
              <v-line v-if="drawPreview?.kind === 'line'" :config="drawPreview.config" />
              <v-arrow v-if="drawPreview?.kind === 'arrow'" :config="drawPreview.config" />
              <v-ellipse v-if="drawPreview?.kind === 'ellipse'" :config="drawPreview.config" />
              <v-star v-if="drawPreview?.kind === 'star'" :config="drawPreview.config" />
            </v-layer>
          </v-stage>
          <!-- 就地编辑 overlay：双击文本元素弹出，背景透明 + 字体继承，营造"直接在画布上编辑"观感。
               PreviewRenderer 会跳过 editingId 对应的 element，避免画布底层字形与 textarea 重影。 -->
          <TextInlineEditor
            ref="inlineEditorRef"
            :element="editingElement"
            @input="onEditInput"
            @finish="finishEditing"
            @keydown="onEditKeydown"
          />
        </div>
      </div>
    </div>

    <!-- M13-D：上传错误 / 进度 banner（顶部居中，自动消失） -->
    <div
      v-if="uploadError || uploading"
      class="fixed top-16 left-1/2 -translate-x-1/2 z-50 px-3 py-1.5 rounded-md text-xs shadow-lg pointer-events-none"
      :class="uploadError ? 'bg-red-500/95 text-white' : 'bg-blue-500/95 text-white'"
    >
      {{ uploadError ?? t.image.uploading }}
    </div>

    <!-- M13-D：隐藏 file input（点击工具栏上传按钮触发） -->
    <input
      ref="fileInputRef"
      type="file"
      accept="image/png,image/jpeg,image/webp"
      class="hidden"
      @change="onFileInputChange"
    />

    <!-- 右下角 zoom 控件（升级版） -->
    <CanvasZoomBar
      :size-label="sizeLabel"
      :grid-size="gridSize"
      :uploading="uploading"
      @fit="fitToViewport"
      @trigger-upload="triggerFileInput"
      @grid-change="onGridChange"
    />
  </section>
</template>

<style scoped>
/* canvas / brush host 视觉样式由全局 / Tailwind 提供；
   TextInlineEditor / CanvasZoomBar 各自 scoped 自带样式。 */

/* M17 F4：1024px 虚空白边——让画布可以拖到 viewport 任意角落（Figma / PS 风格）。
   不用 Tailwind 的 p-[1024px]：scoped 下 arbitrary value 偶失效，直接写 CSS 最稳。
   注意：padding 不影响 fitToViewport 计算（按 widthPx / heightPx 而非 scrollWidth），
   但会让 scrollWidth / scrollHeight 比 viewport 大 → outer.scrollLeft/Top 有空间走。 */
.hc-canvas-padding {
    padding: 1024px;
}
</style>
