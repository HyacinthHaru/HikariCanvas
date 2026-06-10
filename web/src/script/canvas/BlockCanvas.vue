<script setup lang="ts">
/**
 * 0.7.0-P4-C/D2：积木画布（无限画布 viewport + world）。
 *
 * <p>viewport（overflow hidden）内一个 world div，用 {@link useBlockCanvas} 的 worldStyle
 * 做 translate+scale。积木堆 `position:absolute` 定位在 world 坐标系。每条 ScriptRule →
 * 一个 {@link BlockStack}，坐标来自各规则 {@code blockLayout}（{@link parseBlockLayout}），
 * 缺坐标走 {@link autoLayout} 纵向排布兜底。</p>
 *
 * <p><b>D2 拖拽接入</b>：本组件持唯一的 {@link useBlockDrag} 实例（它握 canvasRef +
 * screenToWorld），把"拖块 / 移堆"两句柄 {@code provide} 给递归子组件（BlockNode / BlockStack
 * 注入后在 pointerdown 调）；palette 源经父级 overlay 转发到本组件 {@code defineExpose} 的
 * {@code startPaletteDrag}。移堆拖动中用 {@code drag.stackDragPos} 覆盖该堆坐标即时跟随；
 * 拖块 / palette 拖动中渲染<b>跟手浮层 + 吸附指示线</b>（Teleport 到 body，用 viewport 坐标
 * fixed 定位，绕开 world transform）。</p>
 *
 * <p>pan 触发：空格按住拖 / 中键拖 / 拖空白处；拖块 / 移堆进行中不启动 pan。缩放 = ctrl/meta +
 * wheel 以光标为锚。</p>
 */
import { computed, provide, ref } from 'vue';
import { useBlockCanvas } from './useBlockCanvas';
import { useBlockDrag } from './useBlockDrag';
import { BLOCK_DRAG_KEY } from './dragInjection';
import { useScriptStore } from '@/stores/scripts';
import { useI18n } from '@/i18n';
import { defFor } from '../model/blockDefs';
import { resolveLabelKey } from './labelKey';
import { parseBlockLayout, autoLayout, type BlockLayout } from '../model/serialize';
import BlockStack from './BlockStack.vue';

const viewportRef = ref<HTMLElement | null>(null);
const scripts = useScriptStore();
const { t } = useI18n();

/** 空格按住态（pan 触发条件之一）。表单聚焦时不接管空格（留给输入）。 */
const spaceDown = ref(false);

const canvas = useBlockCanvas({
    viewportRef,
    isSpaceDown: () => spaceDown.value,
});

// D2：唯一拖拽实例（canvasRef = viewport 测量根；screenToWorld 给移堆换算 world 位移）。
const drag = useBlockDrag({
    canvasRef: viewportRef,
    screenToWorld: canvas.screenToWorld,
});

// 把"拖块 / 移堆"句柄 provide 给递归子组件（BlockNode / BlockStack 注入）。
provide(BLOCK_DRAG_KEY, {
    startBlockDrag: drag.startBlockDrag,
    startStackDrag: drag.startStackDrag,
});

// 暴露给父级：zoom% + reset 视图 + palette 源拖出入口。
defineExpose({
    zoom: canvas.zoom,
    resetView: canvas.resetView,
    startPaletteDrag: drag.startPaletteDrag,
});

/**
 * 每条规则在画布上的坐标：先各自解析 {@code rule.blockLayout} 取 {@code stacks[rule.id]}，
 * 缺坐标的规则统一交给 {@link autoLayout} 纵向排布兜底（按 listSorted 顺序）。
 * <b>移堆拖动中</b>：被拖堆的坐标用 {@code drag.stackDragPos} 实时覆盖（松手才提交 setStackPos）。
 */
const positionedStacks = computed(() => {
    const rules = scripts.listSorted;
    const explicit: BlockLayout = { stacks: {} };
    for (const rule of rules) {
        const layout = parseBlockLayout(rule.blockLayout);
        const coord = layout.stacks[rule.id];
        if (coord) explicit.stacks[rule.id] = coord;
    }
    const merged = autoLayout(rules.map((r) => r.id), explicit);
    const live = drag.stackDragPos.value;
    return rules.map((rule) => {
        // 移堆拖动中：覆盖被拖堆坐标，让它即时跟随指针。
        if (live && live.ruleId === rule.id) {
            return { rule, x: live.x, y: live.y };
        }
        const c = merged.stacks[rule.id] ?? { x: 40, y: 40 };
        return { rule, x: c.x, y: c.y };
    });
});

/** 跟手浮层显示的块标题（palette / block 源的 kind → label）。 */
const ghostLabel = computed(() => {
    const g = drag.ghost.value;
    if (!g) return '';
    const def = defFor(g.kind);
    return def ? resolveLabelKey(t.value, def.labelKey) : g.kind;
});

/** 跟手浮层色条颜色。 */
const ghostColor = computed(() => {
    const g = drag.ghost.value;
    if (!g) return 'var(--border)';
    const def = defFor(g.kind);
    return def ? `var(${def.colorVar})` : 'var(--border)';
});

function isFormTarget(target: EventTarget | null): boolean {
    const el = target as HTMLElement | null;
    return !!el && (el.matches?.('input, textarea, select') || el.isContentEditable);
}

function onKeyDown(e: KeyboardEvent): void {
    if (e.code === 'Space' && !isFormTarget(e.target)) {
        spaceDown.value = true;
    }
}
function onKeyUp(e: KeyboardEvent): void {
    if (e.code === 'Space') spaceDown.value = false;
}

function onPointerDown(e: PointerEvent): void {
    // 拖块 / 移堆进行中不启动 pan（理论上 capture 已握住 pointer，这里再加一道守卫）。
    if (drag.isDraggingBlock.value || drag.isDraggingStack.value) return;
    // viewport 空白处都可 pan（积木堆 / 帽子 / 块的 pointerdown 已 stopPropagation，不冒泡到这）。
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
      <!-- 每条 ScriptRule → 一个积木堆 -->
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

  <!-- 吸附指示线（命中插槽时，viewport 坐标 fixed 定位）。Teleport 到 body 绕开 world transform。 -->
  <Teleport to="body">
    <div
      v-if="drag.activeSlot.value"
      class="hc-drop-indicator"
      :style="{
        left: `${drag.activeSlot.value.x}px`,
        top: `${drag.activeSlot.value.y + drag.activeSlot.value.h / 2}px`,
        width: `${drag.activeSlot.value.w}px`,
      }"
    />
    <!-- 跟手浮层（palette / block 源拖动中）。 -->
    <div
      v-if="drag.ghost.value"
      class="hc-drag-ghost"
      :style="{
        left: `${drag.ghost.value.x}px`,
        top: `${drag.ghost.value.y}px`,
        borderLeftColor: ghostColor,
      }"
    >
      <span class="hc-drag-ghost-dot" :style="{ background: ghostColor }" />
      <span class="hc-drag-ghost-label">{{ ghostLabel }}</span>
    </div>
  </Teleport>
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
/* Teleport 到 body 的浮层用全局类（scoped 下需 :deep 或全局；这里用 fixed + 独立类名避免冲突）。 */
.hc-drop-indicator {
    position: fixed;
    height: 3px;
    border-radius: 2px;
    background: var(--ctp-blue, var(--primary));
    box-shadow: 0 0 6px color-mix(in srgb, var(--ctp-blue, var(--primary)) 70%, transparent);
    pointer-events: none;
    z-index: 70;
    transform: translateY(-50%);
}
.hc-drag-ghost {
    position: fixed;
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
    padding: 0.3rem 0.55rem;
    border-left: 3px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--card);
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.28);
    pointer-events: none;
    z-index: 71;
    opacity: 0.92;
    font-size: 0.8125rem;
    white-space: nowrap;
}
.hc-drag-ghost-dot {
    width: 8px;
    height: 8px;
    border-radius: 2px;
}
.hc-drag-ghost-label {
    color: var(--foreground);
}
</style>
