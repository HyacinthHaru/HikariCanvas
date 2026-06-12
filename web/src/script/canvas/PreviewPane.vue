<script setup lang="ts">
/**
 * 0.7.1-P3：积木编辑器右侧「墙面预览框」。
 *
 * <p>只读复用现有墙渲染（{@link renderProjectState}）显示当前墙现状，给积木编辑提供画面参照。
 * 坐标系（{@link previewCoords}）= fit-scale + 居中，P3 建立、P4「幽灵拖动设目标坐标」复用。</p>
 *
 * <p><b>渲染（T3）</b>：canvas 内部分辨率 = 墙像素（{@code project.canvasPixelWidth × Height}），
 * CSS 尺寸 = 墙像素 × fit-scale（{@code image-rendering: pixelated} 让浏览器最近邻缩放），由父
 * host 的 flex 居中——其左上角恰落在 {@link computePreviewTransform} 给的 (offsetX, offsetY)，
 * 故同一变换既驱动显示又供 P4 反算指针 → 墙坐标，单一权威。</p>
 *
 * <p><b>重绘策略（§9 决策）</b>：<b>先全量重绘</b>——{@code watch(project.state, redraw, {deep:true})}
 * + ResizeObserver 变化重测 + RAF 合并（同帧多次改只画一次）。脏区优化留实测（工具不是保姆，简单
 * 优先）。空态（{@code project.state == null}）画占位「未选择墙」。</p>
 *
 * <p><b>波2（T5/T6）</b> 才加点选 hit-test + 取当前值——本波只到「渲染 + 坐标系」。</p>
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useProjectStore } from '@/stores/project';
import { useScriptEditStore } from '@/stores/scriptEdit';
import { useI18n } from '@/i18n';
import { renderProjectState } from '@/render/PreviewRenderer';
import { elementToPolygon, pointInPolygon } from '@/livepaint';
import { getAt, parsePath } from '@/script/model/blockTree';
import { FRIENDLY_KIND_CURRENT_FIELDS } from '@/script/model/blockDefs';
import type { Element, ScriptAction } from '@/types/protocol';
import { computePreviewTransform, previewToWall, type PreviewTransform } from './previewCoords';

const project = useProjectStore();
const edit = useScriptEditStore();
const { t } = useI18n();

const hostRef = ref<HTMLElement | null>(null);
const canvasRef = ref<HTMLCanvasElement | null>(null);

/** host（预览区）的 client 尺寸，ResizeObserver 维护。 */
const paneW = ref(0);
const paneH = ref(0);

/** 当前 fit 变换（显示 + P4 hit-test 同源）。canvas CSS 尺寸 / 占位文字定位都读它。 */
const transform = ref<PreviewTransform>({ scale: 1, offsetX: 0, offsetY: 0 });

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
        // 留 1×1 内部分辨率避免某些环境下 0 尺寸 getContext 异常；CSS 隐藏。
        if (canvas.width !== 0) canvas.width = 0;
        if (canvas.height !== 0) canvas.height = 0;
        return;
    }

    // 内部分辨率 = 墙像素（1:1 渲染，缩放交给 CSS + pixelated 最近邻）。
    if (canvas.width !== wallW) canvas.width = wallW;
    if (canvas.height !== wallH) canvas.height = wallH;

    // fit-scale + 居中（host flex 居中 → canvas 左上角落在 offsetX/offsetY，与变换一致）。
    transform.value = computePreviewTransform(wallW, wallH, paneW.value, paneH.value);

    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    renderProjectState(ctx, state);

    // 波2：当前积木绑定的元素描边高亮（叠在墙渲染之上）。
    drawBindingHighlight(ctx, transform.value.scale);
}

// ---------- 波2：点选 hit-test + 取当前值 + 高亮 ----------

/** 当前墙上所有可见元素（跨层展平，保持 z-order：图层序 + 层内序）。state 未就绪 → 空。 */
function visibleElements(): Element[] {
    const s = project.state;
    if (!s || !s.layers) return [];
    const out: Element[] = [];
    for (const layer of s.layers) {
        // 图层不可见 / 全透明 → 整层元素不参与点选（镜像 renderProjectState 的 layer 守卫；
        // 否则 opacity:0 的隐形层仍可点中、绑定到看不见的元素，M2）。
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

/**
 * 取当前积木绑定的元素（activeElementBinding path 指向的 action 的 elementId 对应元素）。
 * 无绑定 / 无 workingCopy / path 非法 / 元素不存在 → null。用于高亮。
 */
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
 * 画当前积木绑定元素的描边高亮（Catppuccin mauve）。<b>ctx 处于墙像素空间（1:1）</b>——canvas
 * 内部分辨率 = 墙像素，缩放交给 CSS，故直接用墙坐标绘制即可（不需 {@code wallToPreview}，那是给
 * 「pane CSS 空间的独立覆盖层」用的；这里同一块 wall-pixel canvas，墙坐标就是 ctx 坐标）。
 * 描边宽度按 scale 反补偿（{@code 2/scale} 墙像素 → 显示约 2 CSS px），放大 / 缩小下粗细稳定。
 */
function drawBindingHighlight(ctx: CanvasRenderingContext2D, scale: number): void {
    const el = boundElement();
    if (!el) return;
    if (typeof ctx.strokeRect !== 'function') return; // 测试 / 退化 ctx 容错
    const lw = scale > 0 ? Math.max(1, 2 / scale) : 2;
    ctx.save();
    ctx.lineWidth = lw;
    // Catppuccin mauve（#cba6f7）。CSS 变量在 canvas 取不到，直引十六进制（与积木 chip 同色系）。
    ctx.strokeStyle = '#cba6f7';
    if (typeof ctx.setLineDash === 'function') ctx.setLineDash([]);
    // 沿元素 bbox 描边（含轻微外扩半个线宽让边框落在元素外沿，不盖住像素）。
    ctx.strokeRect(el.x - lw / 2, el.y - lw / 2, el.w + lw, el.h + lw);
    ctx.restore();
}

/**
 * canvas 点选：client 坐标 → pane 局部（减 host rect）→ {@link previewToWall} 墙坐标 →
 * {@link findElementAt} 命中元素 → 若有 {@code activeElementBinding} 则回填 elementId（及按
 * friendly kind 取的当前几何值）。无绑定 / 未命中 → 不动。
 *
 * <p>坐标与显示同源：transform 既驱动 canvas CSS 尺寸 / 居中，又供这里反算指针 → 墙坐标
 *（{@code transform.offsetX/Y} = canvas 在 host 内的左上位置，host rect left/top 是 host 的客户
 * 端位置，二者相减恰好抵到 canvas 左上角）。</p>
 */
function onPointerDown(e: PointerEvent): void {
    if (e.button !== undefined && e.button !== 0) return;
    const host = hostRef.value;
    if (!host) return;
    const wallW = project.canvasPixelWidth;
    const wallH = project.canvasPixelHeight;
    if (!project.state || wallW <= 0 || wallH <= 0) return;
    const rect = host.getBoundingClientRect();
    const localX = e.clientX - rect.left;
    const localY = e.clientY - rect.top;
    const { x: wallX, y: wallY } = previewToWall(transform.value, localX, localY);
    const el = findElementAt(wallX, wallY);
    if (!el) return; // 点空白：不改绑定
    applyPickedElement(el);
}

/**
 * 把点选命中的元素回填到当前活跃积木：① elementId；② 按 friendly kind 取元素当前几何
 *（深度2）。无 activeElementBinding → 不填（点选仅可能高亮已有绑定，不新增）。
 *
 * <p>下拉与点选天然同步——都改同一 workingCopy 字段（elementId / patch），BlockNode 的元素
 * 下拉 {@code :value} 绑该字段自动反映。</p>
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
        // 非坐标 friendly（show/hide/setText/setColor）/ 万能 setElementProperty → 只填 elementId。
        edit.updateActionField(path, { elementId: el.id } as Partial<ScriptAction>);
        return;
    }
    // friendly 坐标类：基于现有 patch 合并取到的当前几何值（保留 patch 里其它键）。
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
 * {@code kind} 查 {@link FRIENDLY_KIND_CURRENT_FIELDS}（moveTo→x/y 等）；万能 setElementProperty
 *（单数）/ 其它动作 → 空（只填 elementId）。
 */
function currentValueFieldsFor(action: ScriptAction): string[] {
    if (action.type !== 'setElementProperties') return [];
    const kind = (action as { kind?: string }).kind ?? '';
    return FRIENDLY_KIND_CURRENT_FIELDS[kind] ?? [];
}

/**
 * 取元素某几何字段的 String 化值（写回 patch 用）。坐标 / 尺寸 round 取整（patch 是 string，
 * 整数像素更干净）；{@code rotation} round；{@code opacity} 缺省按 1（与渲染默认一致），保留小数。
 */
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
        // 非浏览器环境（测试）兜底：同步画。
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

// 墙状态深 watch → 全量重绘（§9：先全量，脏区留实测）。
watch(() => project.state, () => requestPaint(), { deep: true });
// 墙切换 / 尺寸变化也重画。
watch([() => project.canvasPixelWidth, () => project.canvasPixelHeight], () => requestPaint());
// 波2：当前积木绑定的元素 / 绑定本身变化 → 重绘高亮（绑定切到别的元素 / 清空都要更新描边）。
watch(() => edit.activeElementBinding, () => requestPaint());
watch(() => boundElementId(), () => requestPaint());

onBeforeUnmount(() => {
    if (resizeObs) { resizeObs.disconnect(); resizeObs = null; }
    if (rafId !== 0) { cancelAnimationFrame(rafId); rafId = 0; }
});
</script>

<template>
  <div class="hc-preview-pane">
    <div class="hc-preview-title">{{ t.script.preview.title }}</div>
    <div ref="hostRef" class="hc-preview-canvas-host">
      <!-- 波2：canvas 点选 = 绑定当前积木的元素 + 取其当前坐标（@pointerdown） -->
      <canvas
        ref="canvasRef"
        class="hc-preview-canvas"
        :class="{ 'hc-preview-canvas-pickable': edit.activeElementBinding !== null }"
        :style="canvasStyle()"
        @pointerdown="onPointerDown"
      />
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
    /* 棋盘格底，让透明背景墙的边界可辨（与编辑器主画布同款提示语义） */
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
    /* 0.4.6：阴影区分画布边界（与主画布提示一致） */
    box-shadow: 0 0 0 1px var(--border);
}
/* 波2：当前积木有「元素」字段在聚焦 → 预览可点选元素，光标提示可点 */
.hc-preview-canvas-pickable {
    cursor: crosshair;
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
