<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { ZoomIn, ZoomOut, RotateCcw } from 'lucide-vue-next';
import { onKeyStroke } from '@vueuse/core';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { getWsClient } from '@/network/wsClient';
import { renderProjectState } from '@/render/PreviewRenderer';
import { useI18n } from '@/i18n';
import type { Element } from '@/types/protocol';

const project = useProjectStore();
const ui = useUiStore();
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
    // M5-D6：不再强制 snap 到 0/90/180/270，任意角度都可用；按 Shift 拖旋才 snap 到 15° 倍数
    rotationSnaps: [] as number[],
    rotationSnapTolerance: 5,
    borderStroke: '#60a5fa',
    anchorStroke: '#60a5fa',
    anchorFill: '#0b1120',
    anchorSize: 8,
    rotateAnchorOffset: 24,
};

const elements = computed(() => project.state?.elements ?? []);

function hitConfig(e: Element) {
    // Konva 用 offsetX/Y 把「bbox 左上」坐标转为「绕中心旋转」
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
        // 完全透明但 hit 可点
        fill: 'rgba(0,0,0,0.001)',
        draggable: !e.locked && e.visible,
    };
}

function normalizeRotation(deg: number): number {
    return ((Math.round(deg) % 360) + 360) % 360;
}

function onHitClick(ev: { cancelBubble?: boolean }, id: string): void {
    ui.selectElement(id);
    // 避免 click 冒到 stage 被解析成"空白"
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

function onStageMouseDown(ev: { target: { getStage?: () => unknown; getType?: () => string; hasName?: (n: string) => boolean } }): void {
    // 点击 stage 根（非任何 shape）时 deselect
    const node = ev.target as { getType?: () => string; hasName?: (n: string) => boolean } | null;
    if (!node) return;
    const type = node.getType?.();
    const isElementHit = node.hasName?.('element-hit') ?? false;
    if (!isElementHit && type !== 'Shape') {
        ui.selectElement(null);
    }
}

interface DragEvt { target: { x: () => number; y: () => number; width: () => number; height: () => number } }
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
    if (el) {
        el.x = newX; el.y = newY;
        el.w = newW; el.h = newH;
        el.rotation = newRot;
        ws.send('element.transform', {
            elementId: id,
            x: newX, y: newY, w: newW, h: newH, rotation: newRot,
        });
    }
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

// Transformer attach：selection 变化时 nodes([<konva node>])
watch(() => ui.selectedElementId, () => nextTick(attachTransformer));
watch(elements, () => nextTick(attachTransformer), { deep: true });

function attachTransformer(): void {
    const t = transformerRef.value?.getNode() as undefined | { nodes(ns: unknown[]): void };
    const l = layerRef.value?.getNode() as undefined | { findOne(sel: string): unknown };
    if (!t || !l) return;
    if (!ui.selectedElementId) { t.nodes([]); return; }
    const node = l.findOne(`#${ui.selectedElementId}`);
    t.nodes(node ? [node] : []);
}

// 快捷键
onKeyStroke(['=', '+'], (e) => { if (e.ctrlKey || e.metaKey) { e.preventDefault(); ui.zoomIn(); } });
onKeyStroke('-', (e) => { if (e.ctrlKey || e.metaKey) { e.preventDefault(); ui.zoomOut(); } });
onKeyStroke('0', (e) => { if (e.ctrlKey || e.metaKey) { e.preventDefault(); ui.zoomReset(); } });
onKeyStroke('Escape', () => ui.selectElement(null));

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
</script>

<template>
  <section
    ref="outerRef"
    class="flex-1 relative overflow-auto bg-[color:var(--background)]"
    @wheel="onWheel"
    @mousedown="onMouseDown"
    @mousemove="onMouseMove"
    @mouseup="onMouseUpOrLeave"
    @mouseleave="onMouseUpOrLeave"
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
          class="absolute origin-top-left"
          :style="{
            width: `${widthPx}px`,
            height: `${heightPx}px`,
            transform: `scale(${ui.zoom})`,
          }"
        >
          <canvas
            ref="canvasEl"
            class="absolute inset-0 hc-canvas-layer"
            :style="{ width: `${widthPx}px`, height: `${heightPx}px` }"
          />
          <v-stage
            ref="stageRef"
            :config="stageConfig"
            class="absolute inset-0"
            @mousedown="onStageMouseDown"
            @touchstart="onStageMouseDown"
          >
            <v-layer ref="layerRef">
              <v-rect
                v-for="el in elements"
                :key="el.id"
                :config="hitConfig(el)"
                @click="(ev: any) => onHitClick(ev, el.id)"
                @tap="(ev: any) => onHitClick(ev, el.id)"
                @dblclick="(ev: any) => onHitDblClick(ev, el.id)"
                @dragend="(ev: any) => onDragEnd(ev, el.id)"
                @transformend="(ev: any) => onTransformEnd(ev, el.id)"
              />
              <v-transformer ref="transformerRef" :config="transformerConfig" />
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

    <!-- 右下角 zoom 控件 -->
    <div class="sticky bottom-3 float-right mr-3 flex items-center gap-1 bg-[color:var(--card)] border border-[color:var(--border)] rounded-lg p-1 shadow-sm text-[color:var(--foreground)]">
      <button class="p-1.5 rounded hover:bg-[color:var(--accent)]" :title="t.canvas.zoomOut" @click="ui.zoomOut()">
        <ZoomOut class="size-4" />
      </button>
      <span class="w-12 text-center text-xs tabular-nums">{{ (ui.zoom * 100).toFixed(0) }}%</span>
      <button class="p-1.5 rounded hover:bg-[color:var(--accent)]" :title="t.canvas.zoomIn" @click="ui.zoomIn()">
        <ZoomIn class="size-4" />
      </button>
      <button class="p-1.5 rounded hover:bg-[color:var(--accent)]" :title="t.canvas.zoomReset" @click="ui.zoomReset()">
        <RotateCcw class="size-4" />
      </button>
      <span class="pl-2 pr-1 border-l border-[color:var(--border)] ml-1 text-[10px] text-[color:var(--muted-foreground)]">
        {{ sizeLabel }}
      </span>
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
</style>
