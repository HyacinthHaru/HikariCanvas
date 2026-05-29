<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue';

const props = defineProps<{
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
        // P3-110：尊重 placement prop（默认 bottom）。视口边界自动翻转留待后续。
        computedPlacement.value = props.placement ?? 'bottom';
        position(target, computedPlacement.value);
        visible.value = true;
    }, DELAY_MS);
}

/**
 * P3-110：按 placement 切换 transform 锚点。
 * - top/bottom 用 translateX(-50%) 水平居中（left = trigger 中点）
 * - left 用 translate(-100%, -50%) 贴 trigger 左侧（left = trigger 左边 - GAP）
 * - right 用 translateY(-50%) 贴 trigger 右侧（left = trigger 右边 + GAP）
 */
const tooltipTransform = computed(() => {
    switch (computedPlacement.value) {
        case 'top':
            return 'translate(-50%, -100%)';
        case 'left':
            return 'translate(-100%, -50%)';
        case 'right':
            return 'translateY(-50%)';
        case 'bottom':
        default:
            return 'translateX(-50%)';
    }
});

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
        :style="{ top: `${top}px`, left: `${left}px`, transform: tooltipTransform }"
      >
        <span v-if="text">{{ text }}</span>
        <slot name="body" />
        <kbd v-if="shortcut" class="hc-kbd">{{ shortcut }}</kbd>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped>
/* M24-B：用 Catppuccin surface tones 做 tooltip，避免硬编码 #18181b / #1f2937。
   两个 flavor（latte 浅 + frappe/macchiato 深）通过 CSS 变量自适应。 */
.hc-tooltip {
    position: fixed;
    z-index: 100;
    pointer-events: none;
    /* transform 由 :style 按 placement 动态注入（P3-110）；此处不再写死 translateX(-50%) */
    padding: 0.35rem 0.6rem;
    font-size: 11px;
    line-height: 1.3;
    border-radius: var(--radius-sm);
    background: var(--ctp-surface0);
    color: var(--foreground);
    border: 1px solid var(--ctp-surface1);
    /* P3-110：改 normal + overflow-wrap，让 max-width:280px 对长文本生效（原 nowrap 使其失效横向溢出） */
    white-space: normal;
    overflow-wrap: anywhere;
    max-width: 280px;
    display: inline-flex;
    gap: 6px;
    align-items: center;
}
.hc-kbd {
    padding: 1px 5px;
    border-radius: 3px;
    background: var(--ctp-surface1);
    border: 1px solid var(--ctp-surface2);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 9.5px;
    color: var(--foreground);
}
</style>
