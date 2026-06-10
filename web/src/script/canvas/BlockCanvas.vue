<script setup lang="ts">
/**
 * 0.7.0-P4-C：积木画布（无限画布 viewport + world）。
 *
 * <p>viewport（overflow hidden）内一个 world div，用 {@link useBlockCanvas} 的 worldStyle
 * 做 translate+scale。积木堆 `position:absolute` 定位在 world 坐标系。<b>C 阶段</b>把 B 阶段的
 * 假积木堆换成真渲染：{@code v-for} over {@code scripts.listSorted} → {@link BlockStack}，
 * 坐标来自每条规则自己的 {@code blockLayout}（{@link parseBlockLayout}），缺坐标走
 * {@link autoLayout} 纵向排布兜底。</p>
 *
 * <p>pan 触发：空格按住拖 / 中键拖 / 拖空白处。空格按住状态本组件维护（keydown/keyup Space，
 * 跳过表单元素聚焦时）。缩放 = ctrl/meta + wheel 以光标为锚。拖块 / 拖帽子留任务 D。</p>
 */
import { computed, ref } from 'vue';
import { useBlockCanvas } from './useBlockCanvas';
import { useScriptStore } from '@/stores/scripts';
import { parseBlockLayout, autoLayout, type BlockLayout } from '../model/serialize';
import BlockStack from './BlockStack.vue';

const viewportRef = ref<HTMLElement | null>(null);
const scripts = useScriptStore();

/** 空格按住态（pan 触发条件之一）。表单聚焦时不接管空格（留给输入）。 */
const spaceDown = ref(false);

const canvas = useBlockCanvas({
    viewportRef,
    isSpaceDown: () => spaceDown.value,
});

// 暴露给父级（ScriptEditorOverlay 头部显示 zoom% + reset 按钮）。
defineExpose({ zoom: canvas.zoom, resetView: canvas.resetView });

/**
 * 每条规则在画布上的坐标：先各自解析 {@code rule.blockLayout} 取 {@code stacks[rule.id]}，
 * 缺坐标的规则统一交给 {@link autoLayout} 纵向排布兜底（按 listSorted 顺序）。
 * 返回 {@code {rule, x, y}[]}。
 */
const positionedStacks = computed(() => {
    const rules = scripts.listSorted;
    // 收集已显式带坐标的规则（从其自身 blockLayout 读 stacks[rule.id]）。
    const explicit: BlockLayout = { stacks: {} };
    for (const rule of rules) {
        const layout = parseBlockLayout(rule.blockLayout);
        const coord = layout.stacks[rule.id];
        if (coord) explicit.stacks[rule.id] = coord;
    }
    // 给缺坐标的补纵向排布坐标。
    const merged = autoLayout(rules.map((r) => r.id), explicit);
    return rules.map((rule) => {
        const c = merged.stacks[rule.id] ?? { x: 40, y: 40 };
        return { rule, x: c.x, y: c.y };
    });
});

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
    // C 阶段：viewport 任意空白处都可拖动 pan（积木堆标了 stop，不冒泡到这）。
    canvas.onPanPointerDown(e, true);
}
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
      <!-- C 阶段：真规则上画布（每条 ScriptRule → 一个积木堆） -->
      <BlockStack
        v-for="entry in positionedStacks"
        :key="entry.rule.id"
        :rule="entry.rule"
        :x="entry.x"
        :y="entry.y"
        @pointerdown.stop
      />
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
</style>
