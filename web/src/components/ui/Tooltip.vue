<script setup lang="ts">
import { onUnmounted, ref } from 'vue';

defineProps<{
    /** 主标题文字。可省略（仅用 slot:body） */
    text?: string;
    /** 显示位置；默认 bottom（按钮在顶/左时改成相应方向） */
    placement?: 'top' | 'bottom' | 'left' | 'right';
    /** 右侧灰色 kbd 提示，例如 "V" / "Ctrl+Z" */
    shortcut?: string;
    /** 强制禁用 tooltip（disabled 按钮上挂 tooltip 时仍想出，可以设 false） */
    disabled?: boolean;
}>();

const visible = ref(false);
const triggerRef = ref<HTMLElement | null>(null);
const top = ref(0);
const left = ref(0);
const computedPlacement = ref<'top' | 'bottom' | 'left' | 'right'>('bottom');
let showTimer: number | null = null;

const DELAY_MS = 250;
const GAP_PX = 8;

function position(el: HTMLElement, placement: 'top' | 'bottom' | 'left' | 'right') {
    const rect = el.getBoundingClientRect();
    switch (placement) {
        case 'top':
            top.value = rect.top - GAP_PX;
            left.value = rect.left + rect.width / 2;
            break;
        case 'bottom':
            top.value = rect.bottom + GAP_PX;
            left.value = rect.left + rect.width / 2;
            break;
        case 'left':
            top.value = rect.top + rect.height / 2;
            left.value = rect.left - GAP_PX;
            break;
        case 'right':
            top.value = rect.top + rect.height / 2;
            left.value = rect.right + GAP_PX;
            break;
    }
}

function show(ev: MouseEvent) {
    if (showTimer) window.clearTimeout(showTimer);
    const target = ev.currentTarget as HTMLElement;
    showTimer = window.setTimeout(() => {
        if (!target) return;
        // 取 placement，默认 bottom；若 trigger 靠近视口顶部 < 60px 则强制 bottom
        // 这部分可以做更智能的视口边界检测；先简单点
        computedPlacement.value = 'bottom';
        position(target, computedPlacement.value);
        visible.value = true;
    }, DELAY_MS);
}

function hide() {
    if (showTimer) {
        window.clearTimeout(showTimer);
        showTimer = null;
    }
    visible.value = false;
}

onUnmounted(() => {
    if (showTimer) window.clearTimeout(showTimer);
});
</script>

<template>
  <span
    ref="triggerRef"
    class="inline-flex"
    @mouseenter="show"
    @mouseleave="hide"
    @focusin="show"
    @focusout="hide"
    @click="hide"
  >
    <slot />
  </span>
  <Teleport to="body">
    <transition
      enter-from-class="opacity-0 translate-y-0.5"
      enter-active-class="transition-all duration-100"
      leave-to-class="opacity-0"
      leave-active-class="transition-opacity duration-75"
    >
      <div
        v-if="visible && !disabled && (text || $slots.body)"
        class="hc-tooltip"
        :style="{ top: `${top}px`, left: `${left}px` }"
      >
        <span v-if="text">{{ text }}</span>
        <slot name="body" />
        <kbd v-if="shortcut" class="hc-kbd">{{ shortcut }}</kbd>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped>
.hc-tooltip {
    position: fixed;
    z-index: 100;
    pointer-events: none;
    transform: translateX(-50%);
    padding: 0.3rem 0.55rem;
    font-size: 11px;
    line-height: 1.3;
    border-radius: 6px;
    background: var(--popover, #18181b);
    color: var(--popover-foreground, #fafafa);
    border: 1px solid var(--border, #27272a);
    box-shadow: 0 4px 14px -2px rgba(0, 0, 0, 0.35);
    white-space: nowrap;
    max-width: 280px;
    display: inline-flex;
    gap: 6px;
    align-items: center;
}
.hc-kbd {
    padding: 1px 5px;
    border-radius: 3px;
    background: rgba(255, 255, 255, 0.1);
    border: 1px solid rgba(255, 255, 255, 0.18);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 9.5px;
    color: var(--popover-foreground, #fafafa);
}
:global(html:not(.dark)) .hc-tooltip {
    background: #1f2937;
    color: #fafafa;
    border-color: #1f2937;
}
:global(html:not(.dark)) .hc-kbd {
    background: rgba(255, 255, 255, 0.14);
    border-color: rgba(255, 255, 255, 0.22);
}
</style>
