<script setup lang="ts">
/**
 * 0.7.1-P3/P4：积木编辑器右侧「墙面预览框」。
 *
 * <p>P3：只读复用现有墙渲染（{@link renderProjectState}）显示当前墙现状 + 元素点选取当前值。
 * P4：给"移到 / 改大小 / 旋转"坐标积木叠半透明元素虚影 + 控制点，拖虚影设目标 x/y/w/h/rotation。</p>
 *
 * <p>坐标系（{@link previewCoords}）= fit-scale + 居中。<b>反算指针 → 墙坐标走 {@link clientToWall}</b>
 *（以 canvas 真实 getBoundingClientRect 为原点，消 P3 审查 M1 的 ~0.5px round 偏差）。</p>
 *
 * <p><b>渲染</b>：canvas 内部分辨率 = 墙像素，CSS 缩放（pixelated 最近邻），ctx 即墙坐标空间（1:1），
 * 虚影 / handle / 高亮都直接用墙坐标绘制。<b>重绘</b>：watch project.state（deep）+ ghostEl + 绑定 →
 * RAF 合并全量重绘。</p>
 *
 * <p><b>幽灵拖动（E10/E11/E12）</b>：聚焦坐标积木任意字段 → `activeElementBinding`=path →
 * `activeCoordBlock` 命中 → 叠该积木虚影（元素半透明真样子 + 按 kind 的 handle）。onPointerDown
 * 先判虚影 handle（命中起拖，window 监听照 splitter 范式防 capture 丢失），否则落回 P3 元素点选。</p>
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useProjectStore } from '@/stores/project';
import { useScriptEditStore } from '@/stores/scriptEdit';
import { useI18n } from '@/i18n';
import { renderProjectState, drawElement } from '@/render/PreviewRenderer';
import { elementToPolygon, pointInPolygon } from '@/livepaint';
import { getAt, parsePath } from '@/script/model/blockTree';
import { FRIENDLY_KIND_CURRENT_FIELDS } from '@/script/model/blockDefs';
import {
    isCoordKind, buildGhostElement, ghostHandlePos, hitGhostHandle, applyGhostDrag,
    rotatePoint as rotatePointLocal, type CoordKind, type GhostHandle,
} from './ghostDrag';
import type { Element, ScriptAction } from '@/types/protocol';
import { computePreviewTransform, clientToWall, type PreviewTransform } from './previewCoords';

const project = useProjectStore();
const edit = useScriptEditStore();
const { t } = useI18n();

const hostRef = ref<HTMLElement | null>(null);
const canvasRef = ref<HTMLCanvasElement | null>(null);

/** host（预览区）的 client 尺寸，ResizeObserver 维护。 */
const paneW = ref(0);
const paneH = ref(0);

/** 当前 fit 变换（显示 + handle 半径按 scale 反补偿用）。canvas CSS 尺寸 / 占位文字定位都读它。 */
const transform = ref<PreviewTransform>({ scale: 1, offsetX: 0, offsetY: 0 });

/** P4：虚影 handle 显示半径（CSS px）；墙像素半径 = 此值 / scale（显示恒定）。 */
const GHOST_HANDLE_RADIUS_CSS = 7;

/** P4：当前虚影拖动会话；null = 未拖动。startG = 拖起时虚影元素快照（写回基准，逐帧不漂移）。 */
interface GhostDragSession {
    handle: GhostHandle;
    path: string;
    kind: CoordKind;
    startG: Element;
    startWx: number;
    startWy: number;
}
let ghostDrag: GhostDragSession | null = null;

let resizeObs: ResizeObserver | null = null;
let rafId = 0;

function measure(): void {
    const host = hostRef.value;
    if (!host) return;
    paneW.value = host.clientWidth;
    paneH.value = host.clientHeight;
}

/** 真正画一帧（全量）。RAF 回调里调，不直接调（合并同帧多次请求）。 */
function paint(): void {
    rafId = 0;
    const canvas = canvasRef.value;
    if (!canvas) return;
    const state = project.state;
    const wallW = project.canvasPixelWidth;
    const wallH = project.canvasPixelHeight;

    // 空态 / 墙未就绪：canvas 收成 0 尺寸（占位文字由模板 v-if 显示），不画。
    if (!state || wallW <= 0 || wallH <= 0) {
        transform.value = { scale: 1, offsetX: 0, offsetY: 0 };
        if (canvas.width !== 0) canvas.width = 0;
        if (canvas.height !== 0) canvas.height = 0;
        return;
    }

    // 内部分辨率 = 墙像素（1:1 渲染，缩放交给 CSS + pixelated 最近邻）。
    if (canvas.width !== wallW) canvas.width = wallW;
    if (canvas.height !== wallH) canvas.height = wallH;

    transform.value = computePreviewTransform(wallW, wallH, paneW.value, paneH.value);

    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    renderProjectState(ctx, state);

    // 当前积木绑定的元素描边高亮（P3）。
    drawBindingHighlight(ctx, transform.value.scale);
    // 当前坐标积木的半透明虚影 + 控制点（P4）。
    drawGhost(ctx, transform.value.scale);
}

// ---------- P3：点选 hit-test + 取当前值 + 高亮 ----------

/** 当前墙上所有可见元素（跨层展平，保持 z-order：图层序 + 层内序）。state 未就绪 → 空。 */
function visibleElements(): Element[] {
    const s = project.state;
    if (!s || !s.layers) return [];
    const out: Element[] = [];
    for (const layer of s.layers) {
        // 图层不可见 / 全透明 → 整层元素不参与点选（镜像 renderProjectState 的 layer 守卫）。
        if (layer.visible === false || (layer.opacity ?? 1) <= 0) continue;
        for (const el of layer.elements ?? []) {
            if (el.visible !== false) out.push(el);
        }
    }
    return out;
}

/**
 * 在墙坐标 (wallX, wallY) 处倒序（顶层优先）命中第一个元素。复用 Live Paint 的
 * {@link elementToPolygon}（精确含旋转）+ {@link pointInPolygon}（射线法）。无命中 → null。
 */
function findElementAt(wallX: number, wallY: number): Element | null {
    const els = visibleElements();
    for (let i = els.length - 1; i >= 0; i--) {
        const poly = elementToPolygon(els[i]);
        if (poly && pointInPolygon(wallX, wallY, poly)) return els[i];
    }
    return null;
}

/** 取当前积木绑定的元素（activeElementBinding path 指向的 action 的 elementId 对应元素）。 */
function boundElement(): Element | null {
    const id = boundElementId();
    if (!id) return null;
    return visibleElements().find((e) => e.id === id) ?? null;
}

/** 当前活跃积木上的 elementId（friendly 与万能积木的 elementId 都在 action 顶层）。无 → null。 */
function boundElementId(): string | null {
    const path = edit.activeElementBinding;
    const rule = edit.workingCopy;
    if (!path || !rule) return null;
    const action = getAt(rule.actions, parsePath(path));
    if (!action) return null;
    const id = (action as { elementId?: unknown }).elementId;
    return typeof id === 'string' && id.length > 0 ? id : null;
}

/**
 * 画当前积木绑定元素的描边高亮（Catppuccin mauve）。ctx 处于墙像素空间（1:1）——直接用墙坐标。
 * 描边宽度按 scale 反补偿（{@code 2/scale} 墙像素 → 显示约 2 CSS px）。
 */
function drawBindingHighlight(ctx: CanvasRenderingContext2D, scale: number): void {
    const el = boundElement();
    if (!el) return;
    if (typeof ctx.strokeRect !== 'function') return; // 测试 / 退化 ctx 容错
    const lw = scale > 0 ? Math.max(1, 2 / scale) : 2;
    ctx.save();
    ctx.lineWidth = lw;
    ctx.strokeStyle = '#cba6f7';
    if (typeof ctx.setLineDash === 'function') ctx.setLineDash([]);
    ctx.strokeRect(el.x - lw / 2, el.y - lw / 2, el.w + lw, el.h + lw);
    ctx.restore();
}

// ---------- P4：虚影派生 + 渲染 ----------

/**
 * 当前"坐标积木"（activeElementBinding 指向的 action 若是 setElementProperties + kind∈
 * {moveTo,resize,rotateTo} + 已绑可见元素）。null = 不显虚影。聚焦坐标积木任意字段
 *（BlockNode focusin）→ activeElementBinding=path → 此 computed 命中 → 叠虚影（E12）。
 */
const activeCoordBlock = computed<
    { path: string; kind: CoordKind; baseEl: Element; patch: Record<string, string> } | null
>(() => {
    // lock 守卫：锁定墙只读，不显可拖虚影（否则虚影 + move 光标可点但写回被 updateActionField 挡 →
    // 拖了纹丝不动，"看着能拖实际无反馈"困惑）。lock 时连示意都不给。
    if (project.isLocked) return null;
    const path = edit.activeElementBinding;
    const rule = edit.workingCopy;
    if (!path || !rule) return null;
    const action = getAt(rule.actions, parsePath(path));
    if (!action || action.type !== 'setElementProperties') return null;
    const kind = (action as { kind?: string }).kind ?? '';
    if (!isCoordKind(kind)) return null;
    const elementId = (action as { elementId?: string }).elementId;
    if (typeof elementId !== 'string' || !elementId) return null;
    const baseEl = visibleElements().find((e) => e.id === elementId);
    if (!baseEl) return null;
    const patch = (action as { patch?: Record<string, string> }).patch ?? {};
    return { path, kind, baseEl, patch };
});

/** 当前虚影元素（绑定元素 + patch 覆盖目标几何）；无坐标积木 → null。 */
const ghostEl = computed<Element | null>(() => {
    const cb = activeCoordBlock.value;
    return cb ? buildGhostElement(cb.baseEl, cb.kind, cb.patch) : null;
});

/**
 * 画当前坐标积木的虚影（元素半透明真样子，E10）+ 控制点 handle。ctx 处于墙像素空间（1:1）。
 * 半透明 = save + globalAlpha=0.5 + drawElement + restore。handle 半径按 scale 反补偿（显示恒定）。
 */
function drawGhost(ctx: CanvasRenderingContext2D, scale: number): void {
    const cb = activeCoordBlock.value;
    const g = ghostEl.value;
    if (!cb || !g) return;
    const wallW = project.canvasPixelWidth;
    const wallH = project.canvasPixelHeight;
    // 半透明虚影本体。虚影强制非 dither：drawElement 的 dither 路径 early-return（PreviewRenderer.ts
    // §renderMode==='dither'）不经后面的 globalAlpha 复合，会让 dither 元素虚影不透明。虚影是目标
    // 几何示意，用普通路径 + globalAlpha=0.5 即可。
    ctx.save();
    ctx.globalAlpha = 0.5;
    drawElement(ctx, { ...g, renderMode: undefined } as Element, wallW, wallH);
    ctx.restore();

    // 虚影轮廓（mauve 实线）+ handle。
    if (typeof ctx.strokeRect !== 'function') return;
    const lw = scale > 0 ? Math.max(1, 1.5 / scale) : 1.5;
    const rWall = scale > 0 ? GHOST_HANDLE_RADIUS_CSS / scale : GHOST_HANDLE_RADIUS_CSS;
    ctx.save();
    ctx.strokeStyle = '#cba6f7';
    ctx.fillStyle = '#cba6f7';
    ctx.lineWidth = lw;
    if (typeof ctx.setLineDash === 'function') ctx.setLineDash([]);
    drawGhostOutline(ctx, g);

    const handles = ghostHandlePos(g, cb.kind);
    // rotate handle：画中心→手柄连杆（视觉提示"转"）。
    if (cb.kind === 'rotateTo' && handles.rotate && typeof ctx.moveTo === 'function') {
        const c = { x: g.x + g.w / 2, y: g.y + g.h / 2 };
        ctx.beginPath();
        ctx.moveTo(c.x, c.y);
        ctx.lineTo(handles.rotate.x, handles.rotate.y);
        ctx.stroke();
    }
    // 圆点 handle（resize/rotate；moveTo 无独立 handle）。
    for (const key of Object.keys(handles) as GhostHandle[]) {
        const p = handles[key];
        if (!p || typeof ctx.arc !== 'function') continue;
        ctx.beginPath();
        ctx.arc(p.x, p.y, rWall, 0, Math.PI * 2);
        ctx.fill();
    }
    ctx.restore();
}

/** 画虚影 bbox 轮廓（含 rotation：4 角连线）。 */
function drawGhostOutline(ctx: CanvasRenderingContext2D, g: Element): void {
    if (typeof ctx.beginPath !== 'function') return;
    const cx = g.x + g.w / 2;
    const cy = g.y + g.h / 2;
    const deg = g.rotation ?? 0;
    const corners = [
        [g.x, g.y], [g.x + g.w, g.y], [g.x + g.w, g.y + g.h], [g.x, g.y + g.h],
    ].map(([px, py]) => rotatePointLocal(px, py, cx, cy, deg));
    ctx.beginPath();
    ctx.moveTo(corners[0].x, corners[0].y);
    for (let i = 1; i < corners.length; i++) ctx.lineTo(corners[i].x, corners[i].y);
    ctx.closePath();
    ctx.stroke();
}

// ---------- 坐标反算（M1 正解）+ 交互 ----------

/** client → 墙坐标，以 canvas 真实 rect 为原点（M1 正解，绕开 transform.offset）。 */
function clientToWallCoord(clientX: number, clientY: number): { x: number; y: number } | null {
    const canvas = canvasRef.value;
    if (!canvas || typeof canvas.getBoundingClientRect !== 'function') return null;
    const crect = canvas.getBoundingClientRect();
    return clientToWall(crect, project.canvasPixelWidth, project.canvasPixelHeight, clientX, clientY);
}

/**
 * canvas pointerdown：P4 先判虚影控制点（命中 → 起拖，window 监听）；否则落回 P3 元素点选
 *（findElementAt → 改绑 elementId）。坐标反算走 {@link clientToWallCoord}（M1 正解）。
 */
function onPointerDown(e: PointerEvent): void {
    if (e.button !== undefined && e.button !== 0) return;
    const wallW = project.canvasPixelWidth;
    const wallH = project.canvasPixelHeight;
    if (!project.state || wallW <= 0 || wallH <= 0) return;

    // P4：先判虚影控制点（hit-test 优先于 P3 点选）。
    const cb = activeCoordBlock.value;
    const g = ghostEl.value;
    if (cb && g) {
        const w = clientToWallCoord(e.clientX, e.clientY);
        if (w) {
            const scale = transform.value.scale;
            const rWall = scale > 0 ? GHOST_HANDLE_RADIUS_CSS / scale : GHOST_HANDLE_RADIUS_CSS;
            const handle = hitGhostHandle(g, cb.kind, w.x, w.y, rWall);
            if (handle) {
                ghostDrag = { handle, path: cb.path, kind: cb.kind, startG: g, startWx: w.x, startWy: w.y };
                edit.snapshotForUndo();  // 起拖存 1 份 undo（拖动中逐帧 coalesce 不再入栈，防爆栈）
                edit.setDragging(true);  // 冻结 server 整树替换（堵第一帧前回声替换树写错积木的边缘）
                window.addEventListener('pointermove', onGhostPointerMove);
                window.addEventListener('pointerup', onGhostPointerUp);
                window.addEventListener('pointercancel', onGhostPointerUp);
                e.preventDefault();
                return; // 进拖动，不走点选
            }
        }
    }

    // P3：落回元素点选（改绑 elementId）。
    const w = clientToWallCoord(e.clientX, e.clientY);
    if (!w) return;
    const el = findElementAt(w.x, w.y);
    if (!el) return; // 点空白：不改绑定
    applyPickedElement(el);
}

/** P4：虚影拖动中。算新 patch（走唯一写回入口 updateActionField，本地 mutate 让虚影跟手）。 */
function onGhostPointerMove(e: PointerEvent): void {
    if (!ghostDrag) return;
    // 绑定已不指向本积木（切积木 / server 替换树）→ 本次拖动作废写回，防把坐标写到别的积木。
    if (edit.activeElementBinding !== ghostDrag.path) return;
    const w = clientToWallCoord(e.clientX, e.clientY);
    if (!w) return;
    const patch = applyGhostDrag(
        ghostDrag.kind, ghostDrag.handle, ghostDrag.startG,
        ghostDrag.startWx, ghostDrag.startWy, w.x, w.y,
    );
    if (Object.keys(patch).length === 0) return;
    const basePatch = activeCoordBlock.value?.patch ?? {};
    // coalesce=true：拖动逐帧不入 undo 栈（起拖已 snapshotForUndo 一份）。
    edit.updateActionField(ghostDrag.path, {
        patch: { ...basePatch, ...patch },
    } as Partial<ScriptAction>, true);
}

/** P4：松手 / 取消——解绑 window 监听 + 解冻 server 同步，结束拖动会话（写回已逐帧落，debounce save 收口）。 */
function onGhostPointerUp(): void {
    ghostDrag = null;
    edit.setDragging(false);
    window.removeEventListener('pointermove', onGhostPointerMove);
    window.removeEventListener('pointerup', onGhostPointerUp);
    window.removeEventListener('pointercancel', onGhostPointerUp);
}

/**
 * 把点选命中的元素回填到当前活跃积木：① elementId；② 按 friendly kind 取元素当前几何（深度2）。
 * 无 activeElementBinding → 不填（点选仅可能高亮已有绑定，不新增）。
 */
function applyPickedElement(el: Element): void {
    const path = edit.activeElementBinding;
    if (!path) return;
    const rule = edit.workingCopy;
    if (!rule) return;
    const action = getAt(rule.actions, parsePath(path));
    if (!action) return;

    const fieldsToTake = currentValueFieldsFor(action);
    if (fieldsToTake.length === 0) {
        edit.updateActionField(path, { elementId: el.id } as Partial<ScriptAction>);
        return;
    }
    const basePatch = (action as { patch?: Record<string, string> }).patch ?? {};
    const taken: Record<string, string> = {};
    for (const f of fieldsToTake) {
        taken[f] = elementFieldToString(el, f);
    }
    edit.updateActionField(path, {
        elementId: el.id,
        patch: { ...basePatch, ...taken },
    } as Partial<ScriptAction>);
}

/**
 * 该 action 点选时要从元素取哪些当前几何字段。仅 {@code setElementProperties}（friendly）按
 * {@code kind} 查 {@link FRIENDLY_KIND_CURRENT_FIELDS}；其它 → 空（只填 elementId）。
 */
function currentValueFieldsFor(action: ScriptAction): string[] {
    if (action.type !== 'setElementProperties') return [];
    const kind = (action as { kind?: string }).kind ?? '';
    return FRIENDLY_KIND_CURRENT_FIELDS[kind] ?? [];
}

/** 取元素某几何字段的 String 化值（写回 patch 用）。 */
function elementFieldToString(el: Element, field: string): string {
    switch (field) {
        case 'x': return String(Math.round(el.x));
        case 'y': return String(Math.round(el.y));
        case 'w': return String(Math.round(el.w));
        case 'h': return String(Math.round(el.h));
        case 'rotation': return String(Math.round(el.rotation ?? 0));
        case 'opacity': return String(el.opacity ?? 1);
        default: {
            const v = (el as unknown as Record<string, unknown>)[field];
            return v === undefined || v === null ? '' : String(v);
        }
    }
}

/** 请求一次重绘（RAF 合并）。 */
function requestPaint(): void {
    if (rafId !== 0) return;
    if (typeof requestAnimationFrame === 'function') {
        rafId = requestAnimationFrame(paint);
    } else {
        paint();
    }
}

// canvas CSS 尺寸 = 墙像素 × scale（pixelated 最近邻放大）。0 = 空态（CSS 隐藏）。
function canvasStyle(): Record<string, string> {
    const wallW = project.canvasPixelWidth;
    const wallH = project.canvasPixelHeight;
    const s = transform.value.scale;
    if (!project.state || wallW <= 0 || wallH <= 0) return { display: 'none' };
    return {
        width: `${Math.round(wallW * s)}px`,
        height: `${Math.round(wallH * s)}px`,
    };
}

onMounted(() => {
    measure();
    if (hostRef.value && typeof ResizeObserver !== 'undefined') {
        resizeObs = new ResizeObserver(() => { measure(); requestPaint(); });
        resizeObs.observe(hostRef.value);
    }
    requestPaint();
});

// 墙状态深 watch → 全量重绘。
watch(() => project.state, () => requestPaint(), { deep: true });
// 墙切换 / 尺寸变化也重画。
watch([() => project.canvasPixelWidth, () => project.canvasPixelHeight], () => requestPaint());
// 当前积木绑定的元素 / 绑定本身变化 → 重绘高亮。
watch(() => edit.activeElementBinding, () => requestPaint());
watch(() => boundElementId(), () => requestPaint());
// P4：坐标积木 patch / 虚影几何变化 → 重绘虚影。
watch(() => ghostEl.value, () => requestPaint(), { deep: true });

onBeforeUnmount(() => {
    if (resizeObs) { resizeObs.disconnect(); resizeObs = null; }
    if (rafId !== 0) { cancelAnimationFrame(rafId); rafId = 0; }
    // P4：拖动中卸载 → 解绑 window 监听防泄漏。
    if (ghostDrag) onGhostPointerUp();
});
</script>

<template>
  <div class="hc-preview-pane">
    <div class="hc-preview-title">{{ t.script.preview.title }}</div>
    <div ref="hostRef" class="hc-preview-canvas-host">
      <!-- canvas pointerdown = 虚影拖动(P4) 或 元素点选(P3) -->
      <canvas
        ref="canvasRef"
        class="hc-preview-canvas"
        :class="{
          'hc-preview-canvas-pickable': edit.activeElementBinding !== null,
          'hc-preview-canvas-ghost': activeCoordBlock !== null,
        }"
        :style="canvasStyle()"
        @pointerdown="onPointerDown"
      />
      <!-- P4：当前坐标积木 → 底部幽灵拖动提示 -->
      <div v-if="activeCoordBlock" class="hc-preview-ghost-hint">{{ t.script.preview.ghostHint }}</div>
      <!-- 空态：未选择墙 / 墙未就绪 -->
      <div v-if="!project.state" class="hc-preview-empty">{{ t.script.preview.noWall }}</div>
    </div>
  </div>
</template>

<style scoped>
.hc-preview-pane {
    display: flex;
    flex-direction: column;
    height: 100%;
    min-width: 0;
    background: var(--card);
}
.hc-preview-title {
    flex-shrink: 0;
    padding: 0.375rem 0.625rem;
    font-size: 10px;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--muted-foreground);
    border-bottom: 1px solid var(--border);
}
.hc-preview-canvas-host {
    flex: 1;
    position: relative;
    min-height: 0;
    overflow: hidden;
    display: flex;
    align-items: center;
    justify-content: center;
    /* 棋盘格底，让透明背景墙的边界可辨 */
    background-color: var(--muted);
    background-image:
        linear-gradient(45deg, color-mix(in srgb, var(--border) 60%, transparent) 25%, transparent 25%),
        linear-gradient(-45deg, color-mix(in srgb, var(--border) 60%, transparent) 25%, transparent 25%),
        linear-gradient(45deg, transparent 75%, color-mix(in srgb, var(--border) 60%, transparent) 75%),
        linear-gradient(-45deg, transparent 75%, color-mix(in srgb, var(--border) 60%, transparent) 75%);
    background-size: 16px 16px;
    background-position: 0 0, 0 8px, 8px -8px, -8px 0;
}
.hc-preview-canvas {
    display: block;
    image-rendering: pixelated;
    box-shadow: 0 0 0 1px var(--border);
}
/* 当前积木有「元素」字段在聚焦 → 预览可点选元素 */
.hc-preview-canvas-pickable {
    cursor: crosshair;
}
/* P4：当前坐标积木 → 可拖虚影 */
.hc-preview-canvas-ghost {
    cursor: move;
}
.hc-preview-ghost-hint {
    position: absolute;
    bottom: 0.25rem;
    left: 0.25rem;
    right: 0.25rem;
    padding: 0.2rem 0.4rem;
    font-size: 0.65rem;
    text-align: center;
    color: var(--muted-foreground);
    background: color-mix(in srgb, var(--card) 80%, transparent);
    border-radius: var(--radius-sm);
    pointer-events: none;
}
.hc-preview-empty {
    position: absolute;
    padding: 0.375rem 0.75rem;
    border-radius: var(--radius-sm);
    background: color-mix(in srgb, var(--card) 85%, transparent);
    border: 1px solid var(--border);
    font-size: 0.75rem;
    color: var(--muted-foreground);
    pointer-events: none;
}
</style>
