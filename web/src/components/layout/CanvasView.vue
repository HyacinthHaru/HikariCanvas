<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useEventListener } from '@vueuse/core';
import { useProjectStore } from '@/stores/project';
import { isDrawTool, useUiStore } from '@/stores/ui';
import { getWsClient } from '@/network/wsClient';
import { renderProjectState, onIconReady, onPaletteReady } from '@/render/PreviewRenderer';
import { preloadMetrics, onMetricsReady } from '@/render/GlyphMetricsLut';
import { ensureLoaded as ensureFontLoaded, onFontLoaded } from '@/render/FontLoader';
import { onIconLoaded } from '@/render/IconLoader';
import { useI18n } from '@/i18n';
import type { Element } from '@/types/protocol';

import CanvasGridOverlay from '@/components/canvas/CanvasGridOverlay.vue';
import CanvasZoomBar from '@/components/canvas/CanvasZoomBar.vue';
import TextInlineEditor from '@/components/canvas/TextInlineEditor.vue';
import SnapGuideOverlay from '@/components/canvas/SnapGuideOverlay.vue';
import LivePaintHoverOverlay from '@/components/canvas/LivePaintHoverOverlay.vue';
import VariablePicker from '@/components/variables/VariablePicker.vue';

import { useMarqueeSelection } from '@/composables/useMarqueeSelection';
import { useDrawToCreate } from '@/composables/useDrawToCreate';
import { useBrushHost } from '@/composables/useBrushHost';
import { useTransformerManager } from '@/composables/useTransformerManager';
import { useCanvasShortcuts } from '@/composables/useCanvasShortcuts';
import { usePanScroll } from '@/composables/usePanScroll';
import { useCanvasUpload } from '@/composables/useCanvasUpload';
import { useSnapManager, type SnapHints } from '@/composables/useSnapManager';
// M18 Live Paint：油漆桶工具支持
import { useLivePaint, gapToPathElement, elementToPolygon, pointInPolygon } from '@/livepaint';
import type { GapPolygon } from '@/livepaint';
import { usePaintBucketStore } from '@/stores/paintBucket';
import { useNetworkStore } from '@/stores/network';
import { useVariableStore } from '@/stores/variables';

const project = useProjectStore();
const ui = useUiStore();
const ws = getWsClient();
const { t } = useI18n();
const paintBucket = usePaintBucketStore();
const net = useNetworkStore();
const variableStore = useVariableStore();

// ---------- 核心 ref ----------
const canvasEl = ref<HTMLCanvasElement | null>(null);
const stageRef = ref<{ getNode(): unknown } | null>(null);
const layerRef = ref<{ getNode(): unknown } | null>(null);
const transformerRef = ref<{ getNode(): unknown } | null>(null);
const outerRef = ref<HTMLElement | null>(null);
const brushHostRef = ref<HTMLElement | null>(null);
const editingId = ref<string | null>(null);
const inlineEditorRef = ref<{
    focus: () => void;
    insertVariableChip: (rawName: string, fallback?: string | null) => void;
    replaceVariableChip: (oldRaw: string, newRaw: string, newFallback?: string | null) => void;
} | null>(null);
const fileInputRef = ref<HTMLInputElement | null>(null);

// ---------- 0.4.1-P3.5：inline editor 的 VariablePicker 接入 ----------
//
// CanvasView 自持一个 picker overlay 实例：用户在画布内双击文本进入 chip 编辑器后，
// 输入 `${` / 点击 chip 都触发 picker，不需要回到 RightPanel。
// pickerMode 与 TextElementSection 行为一致（insertNew / replaceChip）。
type InlinePickerMode =
    | { kind: 'insertNew' }
    | { kind: 'replaceChip'; oldRawName: string };
const inlinePickerOpen = ref(false);
const inlinePickerMode = ref<InlinePickerMode>({ kind: 'insertNew' });
const inlinePickerAnchor = ref<{ x: number; y: number } | null>(null);

const editingElement = computed(() => {
    if (!editingId.value) return null;
    const el = project.elementById(editingId.value);
    return el && el.type === 'text' ? el : null;
});

const widthPx = computed(() => project.canvasPixelWidth || 256);
const heightPx = computed(() => project.canvasPixelHeight || 256);

// M17 F3：shift 临时禁用 snap；keydown/keyup + window blur 维护
const isShiftDown = ref(false);
useEventListener(window, 'keydown', (e: KeyboardEvent) => { if (e.key === 'Shift') isShiftDown.value = true; });
useEventListener(window, 'keyup', (e: KeyboardEvent) => { if (e.key === 'Shift') isShiftDown.value = false; });
useEventListener(window, 'blur', () => { isShiftDown.value = false; });
const snapManager = useSnapManager({ project, ui, bypass: () => isShiftDown.value });
/** M17.4：当前 snap 命中信息，传给 SnapGuideOverlay 画线 + 间距标注。drag/transform 结束时清。 */
const activeSnapHints = ref<SnapHints | null>(null);

const sizeLabel = computed(() => {
    if (!project.state) return t.value.canvas.empty;
    const c = project.state.canvas;
    return t.value.canvas.sizeLabel(c.widthMaps, c.heightMaps, widthPx.value, heightPx.value);
});

const stageConfig = computed(() => ({
    width: widthPx.value,
    height: heightPx.value,
}));

interface BoundBox { x: number; y: number; width: number; height: number; rotation: number }

/**
 * M17.4 F3：resize snap。Konva Transformer 每帧拖锚点 call boundBoxFunc(oldBox, newBox)。
 *
 * 简化版（v2）：跑 snapManager 拿 hints（含 candidate 轴 + delta），然后按"哪条边在动"
 * 应用 delta：
 *   - left 边在动 → 应用 X delta 到 x（width 反向调整以保 right 不动）
 *   - right 边在动 → 应用 X delta 到 width（不动 x）
 *   - 没动 → 不 snap
 * 纵向同理。能正确处理任何锚点（top-left / top-right / bottom-left / bottom-right /
 * middle-* / *-center）；shift 临时禁用走 snapManager.bypass。
 *
 * 排除：当前所有选中（含正在 resize 的）；resize 多选时整组排除避免 snap 到自己。
 * 旋转：rotation != 0 时跳过（旋转后 bbox 不再对齐画布轴；snapManager 几何不支持）。
 */
function boundBoxFunc(oldBox: BoundBox, newBox: BoundBox): BoundBox {
    if (Math.abs(newBox.rotation) > 0.01) return newBox;
    if (newBox.width < 1 || newBox.height < 1) return newBox;
    const excludeIds = new Set<string>(ui.selectedIds);
    const hints = snapManager.snap(newBox.x, newBox.y, newBox.width, newBox.height, excludeIds);
    const hasHits = hints.activeXAxes.length > 0 || hints.activeYAxes.length > 0
        || !!hints.equalGapX || !!hints.equalGapY;
    activeSnapHints.value = hasHits ? hints : null;

    const dx = hints.snappedX - newBox.x;
    const dy = hints.snappedY - newBox.y;
    const EPS = 0.01;
    // 判断哪条边在动（与 oldBox 对比）
    const leftMoved = Math.abs(newBox.x - oldBox.x) > EPS;
    const rightMoved = Math.abs((newBox.x + newBox.width) - (oldBox.x + oldBox.width)) > EPS;
    const topMoved = Math.abs(newBox.y - oldBox.y) > EPS;
    const bottomMoved = Math.abs((newBox.y + newBox.height) - (oldBox.y + oldBox.height)) > EPS;

    let x = newBox.x;
    let y = newBox.y;
    let width = newBox.width;
    let height = newBox.height;

    if (dx !== 0) {
        if (leftMoved && !rightMoved) {
            // left 在动：移动 x，反向减 width 保 right 不动
            x = newBox.x + dx;
            width = Math.max(1, newBox.width - dx);
        } else if (rightMoved && !leftMoved) {
            // right 在动：只加 width，不动 x
            width = Math.max(1, newBox.width + dx);
        }
        // 两边都没动（仅旋转 / 等比缩放？）或都动了（中心缩放）→ 不应用 X
    }
    if (dy !== 0) {
        if (topMoved && !bottomMoved) {
            y = newBox.y + dy;
            height = Math.max(1, newBox.height - dy);
        } else if (bottomMoved && !topMoved) {
            height = Math.max(1, newBox.height + dy);
        }
    }
    return { x, y, width, height, rotation: newBox.rotation };
}

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
    boundBoxFunc,
};

/** 悬停的 element id；用于画 hover 描边提示"可拖拽"。 */
const hoverId = ref<string | null>(null);

const elements = computed(() => project.state?.elements ?? []);

// ---------- M18 Live Paint ----------
// 只在 paint-bucket 工具激活时跑 worker；切走时 useLivePaint 内部跳过 send + 清空 graph。
// visibleElements 过滤 visible=false 的 element（隐藏图层 / 隐藏元素不参与 gap 计算）。
const livePaintEnabled = computed(() => ui.activeTool === 'paint-bucket');
const visibleElements = computed(() => elements.value.filter((e) => e.visible !== false));
const livePaint = useLivePaint({
    elements: visibleElements,
    canvasWidth: widthPx,
    canvasHeight: heightPx,
    enabled: livePaintEnabled,
});
/** 当前 hover 命中的 gap polygon；非 paint-bucket 工具下永远 null。 */
const hoveredGap = ref<GapPolygon | null>(null);

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

/** M17.4：包一层透传给 onTransformEnd，并在结束时清 snap visualizer。 */
function onElementTransformEnd(ev: Parameters<typeof onTransformEnd>[0], id: string): void {
    onTransformEnd(ev, id);
    activeSnapHints.value = null;
}
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

// ---------- M26.3 IconLibrary drop ----------
//
// IconLibrary 的 grid cell 通过 dataTransfer.setData('application/x-hikari-icon', id)
// 携带 icon source。这里在 outer drop 时先尝试解出 icon → 创建 IconElement；
// 不是 icon drag 才让 useCanvasUpload 的 onCanvasDrop 接管文件上传。dragover 同款
// 拓展：icon drag 也需要 preventDefault 才能触发 drop。
const ICON_DRAG_MIME = 'application/x-hikari-icon';
const ICON_SIZE = 64;

function hasIconDrag(ev: DragEvent): boolean {
    if (!ev.dataTransfer) return false;
    // dragover 阶段 types 可访问，但 getData 受同源策略限制只在 drop 才可读
    return Array.from(ev.dataTransfer.types).includes(ICON_DRAG_MIME);
}

function clientToCanvas(clientX: number, clientY: number): { x: number; y: number } | null {
    const host = brushHostRef.value;
    if (!host) return null;
    const rect = host.getBoundingClientRect();
    return {
        x: (clientX - rect.left) / ui.zoom,
        y: (clientY - rect.top) / ui.zoom,
    };
}

function onOuterDragOver(ev: DragEvent): void {
    if (project.isLocked) return;
    if (hasIconDrag(ev)) {
        // 接受 icon drop
        ev.preventDefault();
        if (ev.dataTransfer) ev.dataTransfer.dropEffect = 'copy';
        return;
    }
    // 委托给原有 image upload dragover
    onCanvasDragOver(ev);
}

function onOuterDrop(ev: DragEvent): void {
    if (project.isLocked) return;
    if (hasIconDrag(ev)) {
        ev.preventDefault();
        const id = ev.dataTransfer?.getData(ICON_DRAG_MIME);
        if (!id) return;
        // 简单形态校验：必须 pack/name 形态，长度 ≤ 64（与后端 IconElement.isValidSource 同侧约束）
        if (id.length === 0 || id.length > 64 || id.indexOf('/') < 0) return;

        const center = clientToCanvas(ev.clientX, ev.clientY);
        const cw = project.canvasPixelWidth || 256;
        const ch = project.canvasPixelHeight || 128;
        const cx = center?.x ?? cw / 2;
        const cy = center?.y ?? ch / 2;
        const x = Math.max(0, Math.min(cw - ICON_SIZE, Math.round(cx - ICON_SIZE / 2)));
        const y = Math.max(0, Math.min(ch - ICON_SIZE, Math.round(cy - ICON_SIZE / 2)));

        const activeLayer = project.activeLayer;
        if (!activeLayer || activeLayer.locked) return;

        ws.send('element.add', {
            type: 'icon',
            layerId: activeLayer.id,
            props: {
                x, y,
                w: ICON_SIZE, h: ICON_SIZE,
                rotation: 0,
                source: id,
                fill: { type: 'solid', color: '#000000' },
                locked: false,
                visible: true,
            },
        });
        return;
    }
    // 委托给原有 image upload drop
    onCanvasDrop(ev);
}

// ---------- element 命中 ----------
function hitConfig(e: Element) {
    // Konva 用 offsetX/Y 把「bbox 左上」坐标转为「绕中心旋转」
    const hovered = hoverId.value === e.id;
    const selected = ui.isSelected(e.id);
    const canDrag = !e.locked && e.visible && !project.activeLayerLocked;
    // M9-E：绘制工具激活时，element-hit 整层 listening=false，让 mousedown 穿透到 stage
    // 启动 drag-to-create（PS/Figma 行为：drawTool 下点已有元素也是开始画新元素）
    // M17 F4：hand 工具同样关闭 element-hit listening，使左键直接被 outer 的 pan 处理。
    // M18 Live Paint：paint-bucket 也关 element-hit listening，让 mousedown/move 都直达 stage
    // 由 onPaintBucketClick / hover 逻辑统一接管；P4 实装"点击 element 内部 recolor"时再开
    const drawing = isDrawTool(ui.activeTool) || ui.activeTool === 'hand' || ui.activeTool === 'paint-bucket';
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

function onEditTextUpdate(v: string) {
    const el = editingElement.value;
    if (!el) return;
    // optimistic
    (el as unknown as Record<string, unknown>).text = v;
    ws.send('element.update', { elementId: el.id, patch: { text: v } });
}

function finishEditing() {
    editingId.value = null;
    inlinePickerOpen.value = false;
    inlinePickerMode.value = { kind: 'insertNew' };
}

// ---------- 0.4.1-P3.5：inline editor 的 picker hooks ----------

function onInlineInsertVariableRequest() {
    inlinePickerMode.value = { kind: 'insertNew' };
    inlinePickerOpen.value = true;
}

function onInlineEditVariableRequest(payload: { rawName: string; fallback: string | null }) {
    inlinePickerMode.value = { kind: 'replaceChip', oldRawName: payload.rawName };
    inlinePickerOpen.value = true;
}

function onInlineCreateVariableRequest(payload: { rawName: string }) {
    // P3.4：缺失变量补创 — 简化 v1 用 native confirm；外观打磨留 v1.x
    if (typeof window === 'undefined') return;
    const ok = window.confirm(
        `${t.value.variables.chipError.notFound.replace('{name}', payload.rawName)}\n` +
        `${t.value.variables.chipError.createConfirm}`,
    );
    if (!ok) return;
    // 仅支持 user/X 短名补创（其他 namespace 由 Provider 自动注册，不在此手动创建路径）
    const trimmed = payload.rawName.trim();
    let userKey: string;
    if (trimmed.startsWith('user/')) userKey = trimmed.substring('user/'.length);
    else if (trimmed.indexOf('/') < 0 && trimmed.indexOf('.') < 0) userKey = trimmed;
    else {
        // 非用户变量域（system / scoreboard / papi / schedule / plugin）：提示用户走对应路径
        window.alert(t.value.variables.chipError.onlyUserCanCreate);
        return;
    }
    ws.send('variable.create', {
        name: userKey,
        type: 'STRING',
        defaultValue: '',
    });
}

function onInlinePickerSelect(shortName: string) {
    const editor = inlineEditorRef.value;
    if (!editor) {
        inlinePickerOpen.value = false;
        inlinePickerMode.value = { kind: 'insertNew' };
        return;
    }
    if (inlinePickerMode.value.kind === 'replaceChip') {
        editor.replaceVariableChip(inlinePickerMode.value.oldRawName, shortName, null);
    } else {
        editor.insertVariableChip(shortName, null);
    }
    inlinePickerOpen.value = false;
    inlinePickerMode.value = { kind: 'insertNew' };
    editor.focus();
}

function onInlinePickerClose() {
    inlinePickerOpen.value = false;
    inlinePickerMode.value = { kind: 'insertNew' };
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
    // M18 Live Paint：paint-bucket 单击 = 填充。stage 坐标 = 画布像素坐标（stage 无 scale）。
    if (ui.activeTool === 'paint-bucket') {
        if (editingId.value) finishEditing();
        onPaintBucketClick(pos.x, pos.y);
        return;
    }
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
    // M18 Live Paint：paint-bucket 工具下做 hover preview（point-in-polygon 查 cached graph）
    // 不进 drawMove / marqueeMove；保证不与 marquee 拖框冲突。
    if (ui.activeTool === 'paint-bucket') {
        hoveredGap.value = livePaint.findGapAt(pos.x, pos.y);
        return;
    }
    if (drawMove(pos, isShiftDown.value)) return;  // M27：Shift 锁等比传给绘制
    marqueeMove(pos);
}

/**
 * M18 Live Paint：paint-bucket 单击处理。
 *
 * 优先级：
 *   1. wall 锁定 → 拒
 *   2. graph 未就绪 / 退化 → 拒（提示 building / degraded）
 *   3. 命中 gap → 新建 PathElement 填充
 *   4. 命中 element 内部（精确 polygon hit-test）→ vector-fill 决策 A：直接改 element.fill
 *      - rect / circle / shape / path 支持 fill 字段，直接 element.update
 *      - text / image / brush 不支持（text 用 color，image 无填充语义，brush 不在 P4 范围）→ 提示
 *      - 元素锁定 → 拒
 *   5. 都没命中（理论几乎不应该——visibleElements 之外的区域应该都是 gap）→ noGapFound
 *
 * 决策 A 关键：vector-fill 走 element.update（修改既有元素 fill 字段），而非在元素上方
 * 叠加一个 PathElement。这样 dither / opacity / blendMode 等元素级状态保持完好；
 * 视觉行为最接近 Figma / PS 的"油漆桶点对象重着色"。
 *
 * 命中检测用 livepaint.elementToPolygon + pointInPolygon 精确判定（不只 bbox）——否则
 * circle / star 的角落区域会被误判为命中。bbox 判定在 polygon 内为 false 时直接跳过。
 */

/**
 * 在所有 visible elements 内倒序（z-order top-down）查找包含 (x, y) 的元素。
 * 找到第一个就返回，null = 都没命中。
 *
 * 性能：O(n) × O(polygon vertex)，~32 vertex circle / 数百 vertex path 各一次 ray-casting。
 * 单次 click 触发，不在热路径上；100 elements 远低于 1ms。
 */
function findElementAt(canvasX: number, canvasY: number): Element | null {
    const list = visibleElements.value;
    for (let i = list.length - 1; i >= 0; i--) {
        const el = list[i];
        // 先 bbox 早剪（绕旋转后 bbox 略大但仍是上界），polygon 测试只对幸存的跑
        // 旋转 element 的 polygon 已经是旋转后顶点，bbox 用 elementToPolygon 出来再算更准
        const poly = elementToPolygon(el);
        if (!poly) continue;
        if (pointInPolygon(canvasX, canvasY, poly)) return el;
    }
    return null;
}

function onPaintBucketClick(canvasX: number, canvasY: number): void {
    // P0：wall 锁定
    if (project.isLocked) {
        net.pushLog('meta', t.value.livePaint.wallLocked);
        return;
    }
    // P0：graph 还没构建（首帧 / 大量 element rebuild 中）—— 提示用户稍等再点
    if (!livePaint.graph.value) {
        net.pushLog('meta', t.value.livePaint.building);
        return;
    }
    // M18-P4：polygon-clipping 抛错时 graph.degraded=true，gaps 为空——显式不可用
    // 而不是给用户"整画布单 gap"的假象
    if (livePaint.graph.value.degraded) {
        net.pushLog('err', t.value.livePaint.degraded);
        return;
    }

    // 1. gap 优先（点击空白处）
    const gap = livePaint.findGapAt(canvasX, canvasY);
    if (gap) {
        // 决定目标 layer：active layer 优先；锁定时拒绝（不自动跳到别的 layer，避免用户困惑）
        const activeLayer = project.activeLayer;
        if (!activeLayer || activeLayer.locked) {
            net.pushLog('meta', t.value.livePaint.layerLocked);
            return;
        }

        const elementData = gapToPathElement(gap);
        if (elementData.w <= 0 || elementData.h <= 0 || !elementData.d) {
            net.pushLog('meta', t.value.livePaint.noGapFound);
            return;
        }

        // element.add payload 结构 = { type, props, layerId }
        ws.send('element.add', {
            type: 'path',
            layerId: activeLayer.id,
            props: {
                x: elementData.x,
                y: elementData.y,
                w: elementData.w,
                h: elementData.h,
                rotation: 0,
                d: elementData.d,
                fill: paintBucket.currentFill,
                locked: false,
                visible: true,
            },
        });
        return;
    }

    // 2. vector-fill 决策 A：点击 element 内部 → 直接改 element.fill
    const hitElement = findElementAt(canvasX, canvasY);
    if (hitElement) {
        if (hitElement.locked) {
            net.pushLog('meta', t.value.livePaint.elementLocked);
            return;
        }
        // 仅 4 种元素支持 fill 字段（任务约束）：rect / circle / shape / path
        if (
            hitElement.type === 'rect'
            || hitElement.type === 'circle'
            || hitElement.type === 'shape'
            || hitElement.type === 'path'
        ) {
            ws.send('element.update', {
                elementId: hitElement.id,
                patch: { fill: paintBucket.currentFill },
            });
            // 乐观本地更新（其他 update 路径同款）：fill 是公开字段，无需类型断言重型操作
            (hitElement as unknown as Record<string, unknown>).fill = paintBucket.currentFill;
            net.pushLog('meta', t.value.livePaint.recolorSuccess);
            return;
        }
        // text（color 字段）/ image（无填充语义）/ brush（笔触不在 P4 范围）→ 提示
        net.pushLog('meta', t.value.livePaint.elementUnsupported(hitElement.type));
        return;
    }

    // 3. 都没命中（理论几乎不应该——gap 覆盖空白，element 覆盖元素；缝隙极少）
    net.pushLog('meta', t.value.livePaint.noGapFound);
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
    // M17.4：也清 snap hints（防止 drag 被中断时残留 visualizer）
    activeSnapHints.value = null;
});

// M18 Live Paint：鼠标离开 outer 容器（或 window blur）时清 hover 高亮
useEventListener(window, 'blur', () => { hoveredGap.value = null; });

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
    // M18 Live Paint：切走时清 hover 高亮，避免下次切回时短暂残留旧 gap
    if (next !== 'paint-bucket') {
        hoveredGap.value = null;
    }
});

/**
 * M9-D：cursor 跟随 activeTool。绘制工具显示 crosshair；其他默认 cursor。
 * M17 F4：hand 工具 grab；正在 pan grabbing；Alt 按下且 select/move 也 grab（提示 Alt+左键 pan）。
 */
const cursorStyle = computed(() => {
    if (isPanning.value) return 'grabbing';
    if (ui.activeTool === 'hand') return 'grab';
    if (ui.activeTool === 'paint-bucket') return 'crosshair';  // M18 Live Paint
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

    // M17 F2/F3：单选 / 多选都记录初始位置 —— 单选场景 onDragMove 需要 initLeader 才能算 delta；
    // 多选场景还要给 follower 同步用。原 size>1 才 set 的逻辑会让单选 onDragMove 早 return。
    const init = new Map<string, { x: number; y: number }>();
    if (ui.selectedCount > 1 && ui.isSelected(id)) {
        for (const sid of ui.selectedIds) {
            const el = project.elementById(sid);
            if (el) init.set(sid, { x: el.x, y: el.y });
        }
    } else {
        const el = project.elementById(id);
        if (el) init.set(id, { x: el.x, y: el.y });
    }
    dragInitial.value = init;
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
    let leaderX = Math.round(node.x() - w / 2);
    let leaderY = Math.round(node.y() - h / 2);

    // M17 F3：snap manager 修正 leader 落点。excludeIds 排除整个拖动组（多选）避免 snap 到自己。
    const excludeIds = new Set<string>(dragInitial.value.keys());
    const hints = snapManager.snap(leaderX, leaderY, w, h, excludeIds);
    // M17.4：把 hints 推给 visualizer（即使无 snap 也置 null）。activeAxes / equalGap 任一非空都画。
    const hasHits = hints.activeXAxes.length > 0 || hints.activeYAxes.length > 0
        || !!hints.equalGapX || !!hints.equalGapY;
    activeSnapHints.value = hasHits ? hints : null;
    if (hints.snappedX !== leaderX || hints.snappedY !== leaderY) {
        leaderX = hints.snappedX;
        leaderY = hints.snappedY;
        // 同步 Konva 节点位置回 snap 落点（视觉跟手）；后续 follower 同步算 delta 也用 snapped 值
        node.x(leaderX + w / 2);
        node.y(leaderY + h / 2);
    }

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
    // 0.4.0 bugfix：判等必须用 dragInitial 记录的初始位置而非 mutated 后的 el.x/y——
    // onDragMove 已经乐观把 el.x/y 同步到拖后位置（line 728-729 F2 视觉跟手），
    // 这里若再判 el.x !== newX 恒为 false → ws.send 永不发 → server 漏更新元素位置。
    // M15.3 P0-1 已对多选 case 做了同样修复，但单选 path 漏修，导致所有拖动从未真正同步。
    const initLeader = dragInitial.value.get(id);
    const moved = initLeader
        ? (initLeader.x !== newX || initLeader.y !== newY)
        : (el != null && (el.x !== newX || el.y !== newY));
    if (el && moved) {
        // onDragMove 已乐观 mutate el.x/y；这里只发 ws 保证 server 落地
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
    // M17.4：drag 结束立刻清 hints（无 fade，v1.x 再加）
    activeSnapHints.value = null;
}

// 重绘：state 或 editingId 变就重画 canvas
watch(() => project.state, () => requestDraw(), { deep: true, immediate: true });
watch(editingId, () => requestDraw());
// 0.4.0 bugfix：变量值变化（Provider push / 玩家手动改 / state.patch 同步）触发画布重画，
// 让 PreviewRenderer 内的 interpolator 重新计算占位符替换值。useVariableStore.set/remove/clear
// 都走 `variables.value = new Map(...)` 替换整个 ref，无需 deep watch。
watch(() => variableStore.variables, () => requestDraw());
onMounted(() => {
    requestDraw();
    // M23：所有字体走 FontLoader（document.fonts 动态加载）。
    //     ensureLoaded 是 PreviewRenderer drawText 调用的，这里只 preload 默认两个
    //     避免首帧空白；onFontLoaded 回调注册后任何 fontId 加载完都触发重画。
    ensureFontLoaded('ark_pixel');
    ensureFontLoaded('source_han_sans');
    onFontLoaded(() => requestDraw());
    // 图标异步加载就绪后请求重绘（每个新 source 第一次显示时占位 ?，加载完后真图替换）
    onIconReady(() => requestDraw());
    // M26.2：SVG 矢量图标 path d / viewBox 异步加载完成后请求重绘（同 onIconReady pattern；
    // 前者是 PNG 路径走 /api/template-asset，后者是 SVG path 走 /api/icon/paths）
    onIconLoaded(() => requestDraw());
    // M11-C：PaletteLut 异步加载完成后请求重绘（dither element 首帧 fallback clean，加载后切回 dither）
    onPaletteReady(() => requestDraw());
    // M20-P3：GlyphMetricsLut 异步加载完成后请求重绘（text element 首帧走 canonicalCharWidth fallback，
    // 加载完切到 per-font advance；与 onIconReady / onPaletteReady 同款 pattern）
    preloadMetrics('ark_pixel');
    preloadMetrics('source_han_sans');
    onMetricsReady(() => requestDraw());

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
    @mouseleave="() => { onMouseUpOrLeave(); hoveredGap = null; }"
    @dragover="onOuterDragOver"
    @drop="onOuterDrop"
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
            class="absolute inset-0 z-20 flex items-center justify-center bg-[color:var(--ctp-crust)]/20 cursor-not-allowed"
            @mousedown.stop.prevent
            @click.stop.prevent
            @dblclick.stop.prevent
          >
            <div class="px-3 py-1.5 rounded-[var(--radius-sm)] bg-[color:var(--ctp-peach)] text-[color:var(--ctp-base)] text-xs font-medium pointer-events-none">
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
                @transformend="(ev: any) => onElementTransformEnd(ev, el.id)"
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
            <!-- M17.4 F3：snap visualizer（红色对齐线 + 绿色间距标注）。
                 仅当 activeSnapHints 非空时层 mount；drag/transform 结束立刻清。 -->
            <SnapGuideOverlay
              :hints="activeSnapHints"
              :width-px="widthPx"
              :height-px="heightPx"
            />
            <!-- M18 Live Paint：paint-bucket 工具 hover 高亮。layer listening=false，
                 让 mousedown/move 直达 stage。 -->
            <LivePaintHoverOverlay :hovered-gap="hoveredGap" />
          </v-stage>
          <!-- 就地编辑 overlay：双击文本元素弹出，背景透明 + 字体继承，营造"直接在画布上编辑"观感。
               PreviewRenderer 会跳过 editingId 对应的 element，避免画布底层字形与 textarea 重影。 -->
          <TextInlineEditor
            ref="inlineEditorRef"
            :element="editingElement"
            :wall-id="project.wallId"
            @update:text="onEditTextUpdate"
            @finish="finishEditing"
            @cancel="finishEditing"
            @insert-variable-request="onInlineInsertVariableRequest"
            @edit-variable-request="onInlineEditVariableRequest"
            @create-variable-request="onInlineCreateVariableRequest"
          />
          <!-- 0.4.1-P3.5：inline editor 触发的 VariablePicker（与 RightPanel 的 picker 不冲突，
               同一时刻只有一个 editor 在焦点） -->
          <VariablePicker
            v-if="inlinePickerOpen && editingId"
            :wall-id="project.wallId"
            class="hc-inline-var-picker"
            @select="onInlinePickerSelect"
            @close="onInlinePickerClose"
          />
        </div>
      </div>
    </div>

    <!-- M13-D：上传错误 / 进度 banner（顶部居中，自动消失） -->
    <div
      v-if="uploadError || uploading"
      class="fixed top-16 left-1/2 -translate-x-1/2 z-50 px-3 py-1.5 rounded-[var(--radius-sm)] text-xs shadow-lg pointer-events-none"
      :class="uploadError ? 'bg-[color:var(--destructive)]/95 text-white' : 'bg-[color:var(--ctp-blue)]/95 text-white'"
    >
      {{ uploadError ?? t.image.uploading }}
    </div>

    <!-- M18 Live Paint：worker 正在构建 graph 时的浮动 indicator（仅 paint-bucket 工具下显示） -->
    <div
      v-if="ui.activeTool === 'paint-bucket' && livePaint.isBuilding.value"
      class="absolute bottom-16 right-4 z-40 px-2.5 py-1 rounded-[var(--radius-sm)] text-xs bg-[color:var(--card)] border border-[color:var(--border)] text-[color:var(--muted-foreground)] shadow-md pointer-events-none flex items-center gap-1.5"
    >
      <span class="inline-block size-2 rounded-full bg-[color:var(--ctp-blue)] animate-pulse"></span>
      {{ t.livePaint.building }}
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

/* P3.5：inline picker 浮在 inline editor 上方（VariablePicker 自身是 absolute / fixed 形态，
   父容器有 relative 上下文即可让其捕获最近的 positioned ancestor）。这里让它定位到画布右上角，
   不与画布 element 重叠；具体位置可在 v1.x 用 anchor rect 精细调整。 */
.hc-inline-var-picker {
    position: absolute;
    top: 48px;
    right: 16px;
    z-index: 60;
}
</style>
