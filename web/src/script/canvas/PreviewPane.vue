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
import { useI18n } from '@/i18n';
import { renderProjectState } from '@/render/PreviewRenderer';
import { computePreviewTransform, type PreviewTransform } from './previewCoords';

const project = useProjectStore();
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

onBeforeUnmount(() => {
    if (resizeObs) { resizeObs.disconnect(); resizeObs = null; }
    if (rafId !== 0) { cancelAnimationFrame(rafId); rafId = 0; }
});
</script>

<template>
  <div class="hc-preview-pane">
    <div class="hc-preview-title">{{ t.script.preview.title }}</div>
    <div ref="hostRef" class="hc-preview-canvas-host">
      <canvas ref="canvasRef" class="hc-preview-canvas" :style="canvasStyle()" />
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
