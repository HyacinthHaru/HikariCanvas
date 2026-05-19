<script setup lang="ts">
/**
 * 0.4.0-P2-G：把 {@link useLongPressIncrement} 包成 props-driven 按钮组件。
 *
 * <p>原因：composable 必须在组件 setup 顶层调用——VariablePanel 里 v-for 行
 * 没法对每行单独调 useLongPressIncrement。封一个独立组件让每个按钮拥有自己的 timer
 * 生命周期 + onBeforeUnmount 清理。</p>
 *
 * <p>API：</p>
 * <ul>
 *   <li>{@code title} 按钮 tooltip 文案</li>
 *   <li>{@code delta} 累加增量（+1 / -1 等；负数有效）</li>
 *   <li>{@code onStep(delta)} 每 tick 回调；调用方拼具体 setVariable 等副作用</li>
 * </ul>
 * 默认 slot 是按钮内容（icon）。
 */
import { useLongPressIncrement } from '@/composables/useLongPressIncrement';

const props = defineProps<{
    title?: string;
    delta: number;
    onStep: (delta: number) => void;
}>();

const lp = useLongPressIncrement({
    onTick: () => props.onStep(props.delta),
});
</script>

<template>
  <button
    type="button"
    class="hc-btn p-1 rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
    :class="lp.isPressing ? 'bg-[color:var(--accent)]' : ''"
    :title="title"
    @pointerdown="lp.onPointerDown"
    @pointerup="lp.onPointerUp"
    @pointerleave="lp.onPointerLeave"
    @pointercancel="lp.onPointerCancel"
  >
    <slot />
  </button>
</template>
