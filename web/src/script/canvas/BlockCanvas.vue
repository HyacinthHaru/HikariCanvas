<script setup lang="ts">
/**
 * 0.7.0-P4-B：积木画布（无限画布 viewport + world）。
 *
 * <p>viewport（overflow hidden）内一个 world div，用 {@link useBlockCanvas} 的 worldStyle
 * 做 translate+scale。积木堆 `position:absolute` 定位在 world 坐标系。<b>B 阶段</b>先在 world
 * 里渲染 2-3 个假积木堆（纯色块标 trigger / action），验证 pan/zoom 跟手；真规则渲染留任务 C。</p>
 *
 * <p>pan 触发：空格按住拖 / 中键拖 / 拖空白处。空格按住状态本组件维护（keydown/keyup Space，
 * 跳过表单元素聚焦时）。缩放 = ctrl/meta + wheel 以光标为锚。</p>
 */
import { ref } from 'vue';
import { useBlockCanvas } from './useBlockCanvas';

const viewportRef = ref<HTMLElement | null>(null);

/** 空格按住态（pan 触发条件之一）。表单聚焦时不接管空格（留给输入）。 */
const spaceDown = ref(false);

const canvas = useBlockCanvas({
    viewportRef,
    isSpaceDown: () => spaceDown.value,
});

// 暴露给父级（ScriptEditorOverlay 头部显示 zoom% + reset 按钮）。
defineExpose({ zoom: canvas.zoom, resetView: canvas.resetView });

function isFormTarget(t: EventTarget | null): boolean {
    const el = t as HTMLElement | null;
    return !!el && (el.matches?.('input, textarea, select') || el.isContentEditable);
}

function onKeyDown(e: KeyboardEvent): void {
    if (e.code === 'Space' && !isFormTarget(e.target)) {
        // 阻止空格滚动页面 / 触发按钮；仅在画布聚焦上下文内。
        spaceDown.value = true;
    }
}
function onKeyUp(e: KeyboardEvent): void {
    if (e.code === 'Space') spaceDown.value = false;
}

function onPointerDown(e: PointerEvent): void {
    // B 阶段：viewport 任意空白处都可拖动 pan（假积木块标了 stop，不冒泡到这）。
    canvas.onPanPointerDown(e, true);
}

// B 阶段假积木堆（验证 pan/zoom 跟手）；真渲染留任务 C。
const fakeStacks = [
    { id: 'demo-1', x: 80, y: 60, kind: 'trigger', label: '当变量变化', color: 'var(--ctp-peach)' },
    { id: 'demo-2', x: 80, y: 130, kind: 'action', label: '设变量', color: 'var(--ctp-blue)' },
    { id: 'demo-3', x: 360, y: 220, kind: 'action', label: '播放时间轴', color: 'var(--ctp-mauve)' },
];
</script>

<template>
  <div
    ref="viewportRef"
    class="hc-block-viewport"
    tabindex="0"
    :class="canvas.isPanning.value ? 'cursor-grabbing' : 'cursor-grab'"
    @wheel="canvas.onWheel"
    @pointerdown="onPointerDown"
    @pointermove="canvas.onPanPointerMove"
    @pointerup="canvas.onPanPointerUp"
    @pointercancel="canvas.onPanPointerCancel"
    @keydown="onKeyDown"
    @keyup="onKeyUp"
  >
    <div class="hc-block-world" :style="canvas.worldStyle.value">
      <!-- B 阶段假积木堆（任务 C 替换为真 BlockStack） -->
      <div
        v-for="s in fakeStacks"
        :key="s.id"
        class="hc-fake-block"
        :style="{ left: s.x + 'px', top: s.y + 'px', borderColor: s.color, background: `color-mix(in srgb, ${s.color} 16%, var(--card))` }"
        @pointerdown.stop
      >
        <span class="hc-fake-kind" :style="{ color: s.color }">{{ s.kind }}</span>
        <span class="hc-fake-label">{{ s.label }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hc-block-viewport {
    position: relative;
    width: 100%;
    height: 100%;
    overflow: hidden;
    outline: none;
    /* 棋盘点阵背景提示"无限画布"，缩放时不跟随（始终对齐 viewport）。 */
    background-color: var(--background);
    background-image: radial-gradient(circle, color-mix(in srgb, var(--border) 60%, transparent) 1px, transparent 1px);
    background-size: 20px 20px;
}
.hc-block-world {
    position: absolute;
    top: 0;
    left: 0;
    width: 0;
    height: 0;
    will-change: transform;
}
.hc-fake-block {
    position: absolute;
    width: 200px;
    padding: 8px 10px;
    border-radius: 8px;
    border: 2px solid;
    display: flex;
    flex-direction: column;
    gap: 2px;
    user-select: none;
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.18);
}
.hc-fake-kind {
    font-size: 10px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.04em;
}
.hc-fake-label {
    font-size: 13px;
    color: var(--foreground);
}
</style>
