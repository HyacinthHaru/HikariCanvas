<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { ZoomIn, ZoomOut, RotateCcw, Maximize, ImagePlus } from 'lucide-vue-next';
import { onKeyStroke, useEventListener } from '@vueuse/core';
import { useProjectStore } from '@/stores/project';
import { isDrawTool, useUiStore, type ActiveTool } from '@/stores/ui';
import { useNetworkStore } from '@/stores/network';
import { getWsClient } from '@/network/wsClient';
import { renderProjectState, onIconReady, onPaletteReady, preloadImage } from '@/render/PreviewRenderer';
import { useI18n } from '@/i18n';
import Tooltip from '@/components/ui/Tooltip.vue';
import type { Element, PathElement } from '@/types/protocol';
import { scalePathD } from '@/render/pathScale';
import { BrushController, extractPressure } from '@/brush/BrushController';
import { useBrushStore } from '@/stores/brush';

const project = useProjectStore();
const ui = useUiStore();
const net = useNetworkStore();
const ws = getWsClient();
const { t } = useI18n();

const canvasEl = ref<HTMLCanvasElement | null>(null);
const stageRef = ref<{ getNode(): unknown } | null>(null);
const layerRef = ref<{ getNode(): unknown } | null>(null);
const transformerRef = ref<{ getNode(): unknown } | null>(null);
const editingId = ref<string | null>(null);
const editingTextarea = ref<HTMLTextAreaElement | null>(null);

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

const gridOverlayStyle = computed(() => {
    const s = gridSize.value;
    if (!s) return {};
    return {
        backgroundImage:
            'linear-gradient(to right, color-mix(in srgb, currentColor 20%, transparent) 1px, transparent 1px),' +
            'linear-gradient(to bottom, color-mix(in srgb, currentColor 20%, transparent) 1px, transparent 1px)',
        backgroundSize: `${s}px ${s}px`,
        color: 'rgb(120, 120, 130)',
    };
});

function onGridChange(ev: Event): void {
    const v = parseInt((ev.target as HTMLInputElement).value, 10);
    const size = Number.isFinite(v) ? Math.max(0, Math.min(256, v)) : 0;
    ws.send('canvas.grid', { size });
}

function hitConfig(e: Element) {
    // Konva 用 offsetX/Y 把「bbox 左上」坐标转为「绕中心旋转」
    const hovered = hoverId.value === e.id;
    const selected = ui.isSelected(e.id);
    const canDrag = !e.locked && e.visible && !project.activeLayerLocked;
    // M9-E：绘制工具激活时，element-hit 整层 listening=false，让 mousedown 穿透到 stage
    // 启动 drag-to-create（PS/Figma 行为：drawTool 下点已有元素也是开始画新元素）
    const drawing = isDrawTool(ui.activeTool);
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

function normalizeRotation(deg: number): number {
    return ((Math.round(deg) % 360) + 360) % 360;
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
    nextTick(() => editingTextarea.value?.focus());
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

// ---------- M8-F：marquee 拖框多选 ----------
//
// 行为：
// - 空白处 mousedown（非 element-hit / 非 alt/middle pan）→ 启动 marquee
// - mousemove 实时画 marquee 矩形（v-rect 渲染在最上层 v-layer）
// - mouseup 时：
//   - 拖动距离 < 3px：等价 click 空白 → 清空选中（shift 时保留）
//   - 否则：计算与 activeLayer 内 visible element bbox 相交的 → selectMany（shift = addToSelection）
//
// 仅在 activeLayer 内多选（避免跨层混乱）；rotated element 用 axis-aligned bbox 近似（M8-F MVP）。

interface MarqueeState {
    x1: number; y1: number; x2: number; y2: number;
    additive: boolean;
}
const marquee = ref<MarqueeState | null>(null);

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
    if (evt && (evt.button === 1 || evt.altKey)) return;

    const stage = node.getStage?.();
    const pos = stage?.getPointerPosition?.();
    if (!pos) return;

    // M12-C：brush 工具走 PointerEvent + BrushController（独立路径），stage Konva 事件不动
    if (ui.activeTool === 'brush') return;
    // M9-E：绘制工具激活时启动 drag-to-create；其他工具启动 marquee
    if (isDrawTool(ui.activeTool)) {
        if (editingId.value) finishEditing();
        drawDrag.value = { x1: pos.x, y1: pos.y, x2: pos.x, y2: pos.y };
        return;
    }

    marquee.value = {
        x1: pos.x, y1: pos.y, x2: pos.x, y2: pos.y,
        additive: evt?.shiftKey ?? false,
    };
}

function onStageMouseMove(ev: StageEvt): void {
    const stage = ev.target?.getStage?.();
    const pos = stage?.getPointerPosition?.();
    if (!pos) return;
    if (drawDrag.value) {
        drawDrag.value = { ...drawDrag.value, x2: pos.x, y2: pos.y };
        return;
    }
    if (marquee.value) {
        marquee.value = { ...marquee.value, x2: pos.x, y2: pos.y };
    }
}

function onStageMouseUp(): void {
    // M9-E：绘制工具的 drag-to-create 完成
    if (drawDrag.value) {
        const d = drawDrag.value;
        drawDrag.value = null;
        commitDraw(ui.activeTool, d.x1, d.y1, d.x2, d.y2);
        return;
    }

    const m = marquee.value;
    if (!m) return;
    marquee.value = null;

    const dx = Math.abs(m.x2 - m.x1);
    const dy = Math.abs(m.y2 - m.y1);

    if (dx < 3 && dy < 3) {
        // click 空白：清编辑态 + 清选中（shift 时保留现有选中）
        if (editingId.value) finishEditing();
        if (!m.additive) ui.clearSelection();
        return;
    }

    const minX = Math.min(m.x1, m.x2);
    const minY = Math.min(m.y1, m.y2);
    const maxX = Math.max(m.x1, m.x2);
    const maxY = Math.max(m.y1, m.y2);

    const layer = project.activeLayer;
    const hits: string[] = [];
    for (const el of layer.elements) {
        if (!el.visible) continue;
        if (bboxIntersects(el, minX, minY, maxX, maxY)) hits.push(el.id);
    }

    if (m.additive) {
        for (const id of hits) ui.addToSelection(id);
    } else {
        if (editingId.value) finishEditing();
        ui.selectMany(hits);
    }
}

function bboxIntersects(el: Element, x1: number, y1: number, x2: number, y2: number): boolean {
    // axis-aligned；rotation 用外接 axis-aligned bbox 近似（M8-F MVP）
    return !(el.x + el.w < x1 || el.x > x2 || el.y + el.h < y1 || el.y > y2);
}

// window 兜底：拖出窗口后松手时清掉 marquee / drawDrag
useEventListener(window, 'mouseup', () => {
    if (marquee.value) marquee.value = null;
    if (drawDrag.value) drawDrag.value = null;  // 拖出窗口 = 取消本次创建
});

const marqueeConfig = computed(() => {
    const m = marquee.value;
    if (!m) return null;
    return {
        x: Math.min(m.x1, m.x2),
        y: Math.min(m.y1, m.y2),
        width: Math.abs(m.x2 - m.x1),
        height: Math.abs(m.y2 - m.y1),
        fill: 'rgba(96, 165, 250, 0.12)',
        stroke: '#60a5fa',
        strokeWidth: 1,
        dash: [4, 3],
        listening: false,
    };
});

// ---------- M9-E：drag-to-create 状态 + 预览 ----------

interface DrawDrag { x1: number; y1: number; x2: number; y2: number; }
const drawDrag = ref<DrawDrag | null>(null);

/**
 * 当前 drawDrag 对应的预览 shape：{ kind, config }，渲染到 marquee 同 layer（listening:false）。
 * v-stage 内根据 kind 渲染 v-line / v-ellipse / v-star 之一。
 */
type PreviewKind = 'line' | 'arrow' | 'ellipse' | 'star';
const drawPreview = computed<{ kind: PreviewKind; config: Record<string, unknown> } | null>(() => {
    const d = drawDrag.value;
    if (!d) return null;
    const tool = ui.activeTool;
    const minX = Math.min(d.x1, d.x2);
    const minY = Math.min(d.y1, d.y2);
    const w = Math.abs(d.x2 - d.x1);
    const h = Math.abs(d.y2 - d.y1);

    if (tool === 'line' || tool === 'arrow') {
        // 直线/箭头预览：实线 + 半透明（明确 stroke 方向）。
        // arrow 的 pointer 尺寸与最终元素的 marker 一致：默认 stroke=2 → arrowSize=max(6,2*3)=6
        return {
            kind: tool === 'arrow' ? 'arrow' : 'line',
            config: {
                points: [d.x1, d.y1, d.x2, d.y2],
                stroke: '#000000',
                strokeWidth: 2,
                opacity: 0.6,
                pointerLength: 6,
                pointerWidth: 6,
                fill: '#000000',  // arrow head 用
                listening: false,
            },
        };
    }
    if (tool === 'circle') {
        return {
            kind: 'ellipse',
            config: {
                x: minX + w / 2,
                y: minY + h / 2,
                radiusX: w / 2,
                radiusY: h / 2,
                stroke: '#60a5fa',
                strokeWidth: 1,
                fill: 'rgba(96, 165, 250, 0.15)',
                dash: [4, 3],
                listening: false,
            },
        };
    }
    if (tool === 'star') {
        const outer = Math.min(w, h) / 2;
        return {
            kind: 'star',
            config: {
                x: minX + w / 2,
                y: minY + h / 2,
                numPoints: 5,
                innerRadius: outer * 0.5,
                outerRadius: outer,
                stroke: '#FFCC00',
                strokeWidth: 1,
                fill: 'rgba(255, 204, 0, 0.2)',
                dash: [4, 3],
                listening: false,
            },
        };
    }
    return null;
});

function commitDraw(tool: ActiveTool, x1: number, y1: number, x2: number, y2: number): void {
    if (!isDrawTool(tool)) return;
    const minX = Math.min(x1, x2);
    const minY = Math.min(y1, y2);
    const dx = Math.abs(x2 - x1);
    const dy = Math.abs(y2 - y1);
    // 距离太小视为误点：取消创建（不发 ws）
    if (dx < 3 && dy < 3) {
        ui.setTool('select');
        return;
    }

    let type = '';
    let props: Record<string, unknown> | null = null;
    switch (tool) {
        case 'line':
        case 'arrow': {
            type = 'path';
            // line/arrow 不规范化方向：保持 start→end 的箭头朝向
            const elX = Math.round(minX);
            const elY = Math.round(minY);
            const sx = Math.round(x1 - minX);
            const sy = Math.round(y1 - minY);
            const ex = Math.round(x2 - minX);
            const ey = Math.round(y2 - minY);
            props = {
                x: elX,
                y: elY,
                w: Math.max(2, Math.round(dx)),
                h: Math.max(2, Math.round(dy)),
                d: `M ${sx} ${sy} L ${ex} ${ey}`,
                stroke: { width: 2, color: '#000000' },
            };
            if (tool === 'arrow') (props as Record<string, unknown>).markerEnd = 'arrow';
            break;
        }
        case 'circle': {
            type = 'circle';
            props = {
                x: Math.round(minX),
                y: Math.round(minY),
                w: Math.max(2, Math.round(dx)),
                h: Math.max(2, Math.round(dy)),
                fill: '#3366FF',
            };
            break;
        }
        case 'star': {
            type = 'shape';
            props = {
                x: Math.round(minX),
                y: Math.round(minY),
                w: Math.max(2, Math.round(dx)),
                h: Math.max(2, Math.round(dy)),
                kind: 'star',
                sides: 5,
                fill: '#FFCC00',
            };
            break;
        }
    }

    if (type && props) {
        ws.send('element.add', { type, props });
    }
    // 一次性创建：自动切回 select 工具，让用户能直接操作新元素
    ui.setTool('select');
}

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
    marquee.value = null;
    drawDrag.value = null;
});

/** M9-D：cursor 跟随 activeTool。绘制工具显示 crosshair；其他默认 cursor。 */
const cursorStyle = computed(() => isDrawTool(ui.activeTool) ? 'crosshair' : 'default');

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
    if (dragInitial.value.size <= 1) return;  // 单选 path 不需要同步
    const initLeader = dragInitial.value.get(id);
    if (!initLeader) return;
    const node = ev.target;
    const w = node.width();
    const h = node.height();
    const leaderX = Math.round(node.x() - w / 2);
    const leaderY = Math.round(node.y() - h / 2);
    const dx = leaderX - initLeader.x;
    const dy = leaderY - initLeader.y;

    const layerNode = layerRef.value?.getNode() as undefined | { findOne(sel: string): DragEvt['target'] | undefined };
    if (!layerNode) return;
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
                if (otherEl.x !== otherX || otherEl.y !== otherY) {
                    // dragmove 已经乐观更新过；这里只发 ws 确保服务端落地
                    ws.send('element.transform', { elementId: sid, x: otherX, y: otherY });
                }
            }
        }
    }
    dragInitial.value = new Map();
}

interface TransformEvt { target: {
    x: () => number; y: () => number;
    width: () => number; height: () => number;
    scaleX: () => number; scaleY: () => number;
    rotation: () => number;
    scaleX(v: number): void; scaleY(v: number): void;
    width(v: number): void; height(v: number): void;
    rotation(v: number): void;
} }
function onTransformEnd(ev: TransformEvt, id: string): void {
    const node = ev.target;
    const sx = node.scaleX();
    const sy = node.scaleY();
    const newW = Math.max(1, Math.round(node.width() * sx));
    const newH = Math.max(1, Math.round(node.height() * sy));
    const newX = Math.round(node.x() - newW / 2);
    const newY = Math.round(node.y() - newH / 2);
    const newRot = normalizeRotation(node.rotation());
    // 重置 scale 避免累乘；同时把新 w/h 写回 node 让 Transformer 重新 layout。
    // M5-D5 Bug 6 修：显式把 rotation 回写到 snap 值——否则 Konva 视觉停留在任意角度
    // 而 element.rotation 已是 snap 后的 0/90/180/270，Preview 画布文字看起来「没转」。
    node.scaleX(1); node.scaleY(1);
    node.width(newW); node.height(newH);
    node.rotation(newRot);
    const el = project.elementById(id);
    if (!el) return;

    // 2026-05-14 Bug 修：PathElement 的几何完全由 d 字符串 + stroke.width 决定，
    // bbox 不参与渲染；transformer 改 w/h 时必须同步缩放 d 内坐标 + stroke.width，
    // 否则箭头粗细 / 形状不会跟随 resize handle 改变（用户报"调整大小方框失效"）。
    if (el.type === 'path') {
        const path = el as PathElement;
        const oldW = Math.max(1, path.w);
        const oldH = Math.max(1, path.h);
        const scaleX = newW / oldW;
        const scaleY = newH / oldH;
        const newD = scalePathD(path.d, scaleX, scaleY);
        // stroke.width 按 max(sx, sy) 缩放（保持视觉一致：宽高同比变粗）
        const oldStroke = path.stroke;
        const linearScale = Math.max(scaleX, scaleY);
        const newStrokeWidth = oldStroke
            ? Math.max(1, Math.round(oldStroke.width * linearScale))
            : null;
        // optimistic 写本地
        path.x = newX; path.y = newY; path.w = newW; path.h = newH;
        path.rotation = newRot;
        path.d = newD;
        if (oldStroke && newStrokeWidth !== null) {
            path.stroke = { ...oldStroke, width: newStrokeWidth };
        }
        // 走 element.update 一次性 patch 全部字段（element.transform 只接 x/y/w/h/rotation）
        const patch: Record<string, unknown> = {
            x: newX, y: newY, w: newW, h: newH, rotation: newRot,
            d: newD,
        };
        if (oldStroke && newStrokeWidth !== null) {
            patch.stroke = { ...oldStroke, width: newStrokeWidth };
        }
        ws.send('element.update', { elementId: id, patch });
        return;
    }

    // 其他 element 类型：bbox 即几何（rect/circle/shape）或字号自描述（text/icon）
    el.x = newX; el.y = newY;
    el.w = newW; el.h = newH;
    el.rotation = newRot;
    ws.send('element.transform', {
        elementId: id,
        x: newX, y: newY, w: newW, h: newH, rotation: newRot,
    });
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
});

let drawPending = false;
function requestDraw(): void {
    if (drawPending) return;
    drawPending = true;
    requestAnimationFrame(() => {
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

// Transformer attach：selection 变化时 nodes([...])
// M8-F：watch 改为 selectedIds（Set 引用每次 selectMany 都换新，触发 reactive）
watch(() => Array.from(ui.selectedIds).join(','), () => nextTick(attachTransformer));
watch(elements, () => nextTick(attachTransformer), { deep: true });

function attachTransformer(): void {
    const t = transformerRef.value?.getNode() as undefined | { nodes(ns: unknown[]): void };
    const l = layerRef.value?.getNode() as undefined | { findOne(sel: string): unknown };
    if (!t || !l) return;
    // Move 工具模式下不展示 transformer（PS 风格：拖拽专用，无锚点遮挡）
    if (ui.activeTool === 'move' || ui.selectedCount === 0) { t.nodes([]); return; }
    const nodes: unknown[] = [];
    for (const id of ui.selectedIds) {
        const n = l.findOne(`#${id}`);
        if (n) nodes.push(n);
    }
    t.nodes(nodes);
}

// 工具切换时立刻重附 transformer
watch(() => ui.activeTool, () => nextTick(attachTransformer));

// 快捷键
onKeyStroke(['=', '+'], (e) => { if (e.ctrlKey || e.metaKey) { e.preventDefault(); ui.zoomIn(); } });
onKeyStroke('-', (e) => { if (e.ctrlKey || e.metaKey) { e.preventDefault(); ui.zoomOut(); } });
onKeyStroke('0', (e) => { if (e.ctrlKey || e.metaKey) { e.preventDefault(); ui.zoomReset(); } });
onKeyStroke('Escape', () => {
    // M9-E：绘制工具激活时按 Esc 切回 select（watch(activeTool) 自动清 drawDrag）；
    // select / move 工具下按 Esc 等同清空选中
    if (isDrawTool(ui.activeTool)) {
        ui.setTool('select');
        return;
    }
    ui.clearSelection();
});
// PS 风格快捷键：V 切 select；M 切 move。input/textarea 内输入时跳过
function inEditable(): boolean {
    const a = document.activeElement as HTMLElement | null;
    return !!a && (a.matches?.('input, textarea, select') || a.isContentEditable);
}
onKeyStroke(['v', 'V'], () => { if (!inEditable()) ui.setTool('select'); });
onKeyStroke(['m', 'M'], () => { if (!inEditable()) ui.setTool('move'); });
// M9-D：绘制工具快捷键
onKeyStroke(['l', 'L'], () => { if (!inEditable()) ui.setTool('line'); });
onKeyStroke(['a', 'A'], (e) => {
    // 跳过 Cmd+A 全选；只接单 'A'
    if (e.ctrlKey || e.metaKey) return;
    if (!inEditable()) ui.setTool('arrow');
});
onKeyStroke(['c', 'C'], (e) => {
    // 跳过 Cmd+C 复制（虽然现在没接，但保留语义）
    if (e.ctrlKey || e.metaKey) return;
    if (!inEditable()) ui.setTool('circle');
});
onKeyStroke(['s', 'S'], (e) => {
    if (e.ctrlKey || e.metaKey) return;
    if (!inEditable()) ui.setTool('star');
});
onKeyStroke(['b', 'B'], (e) => {
    if (e.ctrlKey || e.metaKey) return;
    if (!inEditable()) ui.setTool('brush');
});

// ---------- M12-C 笔刷接入 ----------

const brushHostRef = ref<HTMLElement | null>(null);
const brushStore = useBrushStore();
const brushController = new BrushController({
    container: () => brushHostRef.value,
    canvasWidth: () => widthPx.value,
    canvasHeight: () => heightPx.value,
    getLayerId: () => null,
    onStrokeFinished: () => { /* 保持 brush 工具激活，连续画 */ },
});

function brushPropsFromUi(): import('@/brush/BrushController').BrushProps {
    return brushStore.snapshot;
}

function pointerEventToStagePoint(e: PointerEvent): { x: number; y: number; pressure: number } {
    const host = brushHostRef.value;
    if (!host) return { x: 0, y: 0, pressure: 0.5 };
    const rect = host.getBoundingClientRect();
    const x = (e.clientX - rect.left) / ui.zoom;
    const y = (e.clientY - rect.top) / ui.zoom;
    return { x, y, pressure: extractPressure(e) };
}

function onBrushPointerDown(e: PointerEvent) {
    if (ui.activeTool !== 'brush') return;
    if (project.isLocked) return;
    e.preventDefault();
    e.stopPropagation();
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    brushController.pointerDown(pointerEventToStagePoint(e), brushPropsFromUi());
}

function onBrushPointerMove(e: PointerEvent) {
    if (ui.activeTool !== 'brush') return;
    if (!brushController.isActive()) return;
    e.preventDefault();
    brushController.pointerMove(pointerEventToStagePoint(e));
}

function onBrushPointerUp(e: PointerEvent) {
    if (ui.activeTool !== 'brush') return;
    if (!brushController.isActive()) return;
    e.preventDefault();
    brushController.pointerUp(pointerEventToStagePoint(e));
}

function onBrushPointerCancel() {
    if (ui.activeTool !== 'brush') return;
    brushController.pointerCancel();
}

watch(() => ui.activeTool, (tool) => {
    if (tool !== 'brush' && brushController.isActive()) {
        brushController.pointerCancel();
    }
});

// M5-B8 画布交互：Ctrl+wheel zoom（以鼠标为中心）+ 中键或 Alt+drag pan
const outerRef = ref<HTMLElement | null>(null);

function onWheel(e: WheelEvent) {
    if (!(e.ctrlKey || e.metaKey)) return; // 非 Ctrl 让浏览器按默认处理（即 scroll pan）
    e.preventDefault();
    const outer = outerRef.value;
    if (!outer) return;
    const factor = e.deltaY < 0 ? 1.1 : 1 / 1.1;
    const oldZoom = ui.zoom;
    const newZoomClamped = Math.max(0.25, Math.min(4, oldZoom * factor));
    if (newZoomClamped === oldZoom) return;
    const rect = outer.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;
    ui.setZoom(newZoomClamped);
    const ratio = newZoomClamped / oldZoom;
    nextTick(() => {
        outer.scrollLeft = (outer.scrollLeft + mouseX) * ratio - mouseX;
        outer.scrollTop = (outer.scrollTop + mouseY) * ratio - mouseY;
    });
}

/** 缩放档位（用户实测后觉得方便的几档：50/75/100/150/200/400%）。 */
const ZOOM_PRESETS = [0.5, 0.75, 1, 1.5, 2, 4];

/** 把画布缩放到刚好放进 outerRef 的可视区（带 32px padding）。 */
function fitToViewport() {
    const outer = outerRef.value;
    if (!outer) return;
    const availW = outer.clientWidth - 64;
    const availH = outer.clientHeight - 64;
    const fitW = availW / widthPx.value;
    const fitH = availH / heightPx.value;
    const fit = Math.min(fitW, fitH);
    ui.setZoom(Math.max(0.25, Math.min(4, fit)));
    // 滚动到中心
    nextTick(() => {
        outer.scrollLeft = (outer.scrollWidth - outer.clientWidth) / 2;
        outer.scrollTop = (outer.scrollHeight - outer.clientHeight) / 2;
    });
}

/** 用户在缩放数字上点一下进入编辑模式，输入新百分比。 */
const zoomEditOpen = ref(false);
const zoomDraft = ref('');
function openZoomEdit() {
    zoomDraft.value = String(Math.round(ui.zoom * 100));
    zoomEditOpen.value = true;
}
function commitZoomEdit() {
    const n = parseFloat(zoomDraft.value);
    if (Number.isFinite(n) && n > 0) {
        ui.setZoom(Math.max(0.25, Math.min(4, n / 100)));
    }
    zoomEditOpen.value = false;
}
function cancelZoomEdit() {
    zoomEditOpen.value = false;
}

// 中键 / Alt+左键 拖拽 pan
interface PanState { active: boolean; startX: number; startY: number; scrollX: number; scrollY: number; }
const pan: PanState = { active: false, startX: 0, startY: 0, scrollX: 0, scrollY: 0 };

function onMouseDown(e: MouseEvent) {
    const middleBtn = e.button === 1;
    const altLeft = e.button === 0 && e.altKey;
    if (!middleBtn && !altLeft) return;
    if (!outerRef.value) return;
    e.preventDefault();
    pan.active = true;
    pan.startX = e.clientX;
    pan.startY = e.clientY;
    pan.scrollX = outerRef.value.scrollLeft;
    pan.scrollY = outerRef.value.scrollTop;
}

function onMouseMove(e: MouseEvent) {
    if (!pan.active || !outerRef.value) return;
    outerRef.value.scrollLeft = pan.scrollX - (e.clientX - pan.startX);
    outerRef.value.scrollTop = pan.scrollY - (e.clientY - pan.startY);
}

function onMouseUpOrLeave() {
    pan.active = false;
}

// ---------- M13-D：图片上传三入口（drop / paste / file input） ----------

const fileInputRef = ref<HTMLInputElement | null>(null);
const uploadError = ref<string | null>(null);
const uploading = ref(false);

function flashError(msg: string) {
    uploadError.value = msg;
    window.setTimeout(() => {
        if (uploadError.value === msg) uploadError.value = null;
    }, 6000);
}

async function uploadAndPlace(file: File, dropClientX?: number, dropClientY?: number) {
    if (project.isLocked) { flashError(t.value.image.lockedDenied); return; }
    if (!net.sessionId) { flashError(t.value.image.noSession); return; }
    if (!file.type.startsWith('image/')) { flashError(t.value.image.notImage); return; }

    // 客户端预读 dataUrl 作为乐观缓存：上传成功后 preloadImage 直接命中
    const dataUrl = await readFileAsDataUrl(file);

    uploading.value = true;
    try {
        const fd = new FormData();
        fd.append('sessionId', net.sessionId);
        fd.append('file', file);
        const resp = await fetch('/api/upload', { method: 'POST', body: fd });
        if (!resp.ok) {
            const body = await resp.json().catch(() => ({} as Record<string, string>));
            flashError(t.value.image.uploadFailed(resp.status, body.message || body.error || ''));
            return;
        }
        const result = await resp.json() as { source: string; width: number; height: number; bytes: number };
        if (dataUrl) preloadImage(result.source, dataUrl);

        const cv = project.state?.canvas;
        if (!cv) return;
        // 等比缩到 canvas 内（最大占 80% 短边）
        const cvW = cv.widthMaps * 128;
        const cvH = cv.heightMaps * 128;
        const limit = Math.floor(Math.min(cvW, cvH) * 0.8);
        let w = result.width;
        let h = result.height;
        if (Math.max(w, h) > limit) {
            const s = limit / Math.max(w, h);
            w = Math.max(8, Math.round(w * s));
            h = Math.max(8, Math.round(h * s));
        }
        const center = dropToCanvas(dropClientX, dropClientY) ?? { x: cvW / 2, y: cvH / 2 };
        const x = Math.max(0, Math.min(cvW - w, Math.round(center.x - w / 2)));
        const y = Math.max(0, Math.min(cvH - h, Math.round(center.y - h / 2)));
        ws.send('element.add', {
            type: 'image',
            props: { x, y, w, h, source: result.source },
        });
    } catch (e) {
        flashError(t.value.image.uploadFailed(0, (e as Error).message));
    } finally {
        uploading.value = false;
    }
}

function readFileAsDataUrl(file: File): Promise<string | null> {
    return new Promise((resolve) => {
        const reader = new FileReader();
        reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : null);
        reader.onerror = () => resolve(null);
        reader.readAsDataURL(file);
    });
}

/** 把 client 像素坐标（DragEvent / PointerEvent）转 canvas 内部像素坐标。 */
function dropToCanvas(clientX?: number, clientY?: number): { x: number; y: number } | null {
    if (clientX == null || clientY == null) return null;
    const host = (brushHostRef.value as HTMLElement | null) ?? null;
    if (!host) return null;
    const rect = host.getBoundingClientRect();
    const local = {
        x: (clientX - rect.left) / ui.zoom,
        y: (clientY - rect.top) / ui.zoom,
    };
    return local;
}

function onCanvasDragOver(e: DragEvent) {
    if (project.isLocked) return;
    // 仅当含文件时才阻止默认（允许其他 DnD 如元素重排不受影响）
    if (e.dataTransfer && Array.from(e.dataTransfer.types).includes('Files')) {
        e.preventDefault();
    }
}

function onCanvasDrop(e: DragEvent) {
    if (project.isLocked) return;
    const files = e.dataTransfer?.files;
    if (!files || files.length === 0) return;
    e.preventDefault();
    // v1：多文件 drop 只取第一个（M13 决策 3）
    const file = files[0];
    uploadAndPlace(file, e.clientX, e.clientY);
}

function onPasteImage(e: ClipboardEvent) {
    if (project.isLocked) return;
    // 编辑文本时不抢 paste（textarea 元素正在 active）
    if (document.activeElement instanceof HTMLTextAreaElement) return;
    if (document.activeElement instanceof HTMLInputElement) return;
    const items = e.clipboardData?.items;
    if (!items) return;
    for (const item of items) {
        if (item.kind === 'file' && item.type.startsWith('image/')) {
            const file = item.getAsFile();
            if (file) {
                uploadAndPlace(file);
                e.preventDefault();
                return;
            }
        }
    }
}

function onFileInputChange(e: Event) {
    const input = e.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) uploadAndPlace(file);
    input.value = '';  // 允许选同一文件再次触发 change
}

function triggerFileInput() {
    fileInputRef.value?.click();
}

useEventListener(window, 'paste', onPasteImage);
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
    <!-- 画布居中容器 -->
    <div class="min-h-full min-w-full flex items-center justify-center p-8">
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
          <div
            v-if="gridSize > 0"
            class="absolute inset-0 pointer-events-none"
            :style="gridOverlayStyle"
          />
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
          <textarea
            v-if="editingElement"
            ref="editingTextarea"
            class="hc-edit-textarea"
            :value="editingElement.text"
            :style="{
              left: `${editingElement.x}px`,
              top: `${editingElement.y}px`,
              width: `${editingElement.w}px`,
              height: `${editingElement.h}px`,
              fontSize: `${editingElement.fontSize}px`,
              fontFamily: editingElement.fontId,
              color: editingElement.color,
              textAlign: editingElement.align,
              transform: `rotate(${editingElement.rotation}deg)`,
              transformOrigin: 'center center',
            }"
            @input="onEditInput"
            @blur="finishEditing"
            @keydown="onEditKeydown"
            @mousedown.stop
            @dblclick.stop
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
    <div class="sticky bottom-3 float-right mr-3 flex items-center gap-1 bg-[color:var(--card)] border border-[color:var(--border)] rounded-lg p-1 shadow-sm text-[color:var(--foreground)]">
      <Tooltip :text="t.image.uploadTip">
        <button
          class="p-1.5 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
          :disabled="project.isLocked || uploading"
          @click="triggerFileInput"
        >
          <ImagePlus class="size-4" />
        </button>
      </Tooltip>
      <div class="w-px h-5 bg-[color:var(--border)] mx-0.5"></div>
      <Tooltip :text="t.canvas.zoomOut" shortcut="Ctrl+-">
        <button class="p-1.5 rounded hover:bg-[color:var(--accent)]" @click="ui.zoomOut()">
          <ZoomOut class="size-4" />
        </button>
      </Tooltip>
      <!-- 当前缩放：点击数字 → 输入框；点击下拉按钮 → 预设档位列表 -->
      <span class="relative">
        <input
          v-if="zoomEditOpen"
          type="number"
          class="hc-zoom-input"
          :value="zoomDraft"
          autofocus
          step="10"
          min="25"
          max="400"
          @input="(e) => zoomDraft = (e.target as HTMLInputElement).value"
          @keydown.enter="commitZoomEdit"
          @keydown.escape="cancelZoomEdit"
          @blur="commitZoomEdit"
        />
        <Tooltip v-else :text="t.canvas.zoomInputTip">
          <button
            class="w-14 px-1 py-0.5 text-center text-xs tabular-nums rounded hover:bg-[color:var(--accent)]"
            @click="openZoomEdit"
          >{{ (ui.zoom * 100).toFixed(0) }}%</button>
        </Tooltip>
      </span>
      <Tooltip :text="t.canvas.zoomIn" shortcut="Ctrl+=">
        <button class="p-1.5 rounded hover:bg-[color:var(--accent)]" @click="ui.zoomIn()">
          <ZoomIn class="size-4" />
        </button>
      </Tooltip>
      <div class="w-px h-5 bg-[color:var(--border)] mx-0.5"></div>
      <!-- 预设档位 -->
      <div class="flex items-center gap-0.5">
        <button
          v-for="p in ZOOM_PRESETS"
          :key="p"
          class="px-1.5 py-0.5 text-[10px] tabular-nums rounded transition-colors"
          :class="Math.abs(ui.zoom - p) < 0.01
            ? 'bg-[color:var(--accent)] text-[color:var(--accent-foreground)]'
            : 'hover:bg-[color:var(--accent)] text-[color:var(--muted-foreground)]'"
          @click="ui.setZoom(p)"
        >{{ (p * 100).toFixed(0) }}</button>
      </div>
      <div class="w-px h-5 bg-[color:var(--border)] mx-0.5"></div>
      <Tooltip :text="t.canvas.fit">
        <button class="p-1.5 rounded hover:bg-[color:var(--accent)]" @click="fitToViewport">
          <Maximize class="size-4" />
        </button>
      </Tooltip>
      <Tooltip :text="t.canvas.zoomReset" shortcut="Ctrl+0">
        <button class="p-1.5 rounded hover:bg-[color:var(--accent)]" @click="ui.zoomReset()">
          <RotateCcw class="size-4" />
        </button>
      </Tooltip>
      <span class="pl-2 pr-1 border-l border-[color:var(--border)] ml-1 text-[10px] text-[color:var(--muted-foreground)]">
        {{ sizeLabel }}
      </span>
      <!-- M8-E：grid 调节器（0 = 关；典型值 8/16/32） -->
      <Tooltip :text="t.canvas.gridTip">
        <label class="flex items-center gap-1 pl-2 border-l border-[color:var(--border)] ml-1">
          <span class="text-[10px] text-[color:var(--muted-foreground)]">{{ t.canvas.grid }}</span>
          <input
            type="number"
            min="0"
            max="256"
            step="1"
            class="hc-grid-input"
            :value="gridSize"
            @change="onGridChange"
          >
        </label>
      </Tooltip>
    </div>
  </section>
</template>

<style scoped>
/* 就地编辑 textarea：透明背景 + 仅 caret 可见边框，伪装成"直接在画布上写" */
.hc-edit-textarea {
    position: absolute;
    margin: 0;
    padding: 0;
    border: 1px dashed var(--ring);
    outline: none;
    resize: none;
    background: transparent;
    line-height: 1;
    overflow: hidden;
    caret-color: var(--ring);
    white-space: pre-wrap;
    word-break: break-word;
}
.hc-edit-textarea:focus {
    border-style: solid;
}
.hc-zoom-input {
    width: 3.5rem;
    padding: 0.125rem 0.25rem;
    font-size: 0.75rem;
    text-align: center;
    border-radius: 4px;
    background: var(--background);
    color: var(--foreground);
    border: 1px solid var(--ring);
    outline: none;
    font-variant-numeric: tabular-nums;
}
.hc-zoom-input::-webkit-outer-spin-button,
.hc-zoom-input::-webkit-inner-spin-button { -webkit-appearance: none; margin: 0; }
.hc-grid-input {
    width: 2.5rem;
    padding: 0.125rem 0.25rem;
    font-size: 0.7rem;
    text-align: center;
    border-radius: 3px;
    background: var(--background);
    color: var(--foreground);
    border: 1px solid var(--border);
    outline: none;
    font-variant-numeric: tabular-nums;
}
.hc-grid-input:focus {
    border-color: var(--ring);
    box-shadow: 0 0 0 1px var(--ring);
}
</style>
