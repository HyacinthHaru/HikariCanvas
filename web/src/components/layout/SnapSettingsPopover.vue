<script setup lang="ts">
import { computed, ref } from 'vue';
import { onClickOutside } from '@vueuse/core';
import { Magnet, X } from 'lucide-vue-next';
import { useUiStore } from '@/stores/ui';
import { useI18n } from '@/i18n';
import Tooltip from '@/components/ui/Tooltip.vue';

/**
 * M17.4 Snap 设置 popover：磁铁 icon 按钮 + 弹出面板。
 *
 * 内容：
 *   - 总开关 Enable Snap
 *   - 4 子开关：To Grid / To Canvas / To Element / To Distribute
 *   - threshold 滑块 1-32
 *   - shift 临时禁用 tip
 *
 * 触发位置：TopBar 右侧，与 lock / refresh 同级（CanvasView 通过 slot 注入也行；
 * 当前直接独立挂在 TopBar 内）。
 */
const ui = useUiStore();
const { t } = useI18n();

const rootRef = ref<HTMLElement | null>(null);
const open = ref(false);

onClickOutside(rootRef, () => { open.value = false; });

function toggle() {
    open.value = !open.value;
}

function close() {
    open.value = false;
}

function onThresholdInput(ev: Event) {
    const v = parseInt((ev.target as HTMLInputElement).value, 10);
    if (Number.isFinite(v)) {
        ui.snapThreshold = Math.max(1, Math.min(32, v));
    }
}

/** 总开关 off 时子开关 disabled。 */
const childDisabled = computed(() => !ui.snapEnabled);
</script>

<template>
  <div ref="rootRef" class="relative">
    <Tooltip :text="t.snap.settings">
      <button
        class="p-1.5 rounded transition-colors"
        :class="ui.snapEnabled
          ? 'bg-[color:var(--secondary)] text-[color:var(--foreground)] hover:bg-[color:var(--accent)]'
          : 'text-[color:var(--muted-foreground)] hover:bg-[color:var(--accent)]'"
        :aria-pressed="open"
        @click="toggle"
      >
        <Magnet class="size-4" />
      </button>
    </Tooltip>

    <!-- popover -->
    <div
      v-if="open"
      class="absolute right-0 top-full mt-1 z-50 w-64 rounded-[var(--radius-sm)] border border-[color:var(--border)] bg-[color:var(--card)] text-[color:var(--card-foreground)] shadow-md text-xs"
    >
      <header class="flex items-center justify-between px-3 h-8 border-b border-[color:var(--border)]">
        <span class="font-medium uppercase tracking-wider text-[color:var(--muted-foreground)]">
          {{ t.snap.settings }}
        </span>
        <button
          class="p-1 rounded hover:bg-[color:var(--accent)] transition-colors"
          @click="close"
        >
          <X class="size-3" />
        </button>
      </header>

      <div class="px-3 py-3 space-y-3">
        <!-- 总开关 -->
        <label class="flex items-center justify-between cursor-pointer">
          <span class="font-medium">{{ t.snap.enable }}</span>
          <input type="checkbox" v-model="ui.snapEnabled" class="hc-snap-checkbox">
        </label>

        <hr class="border-[color:var(--border)]">

        <!-- 子开关：4 个 -->
        <label class="flex items-center justify-between" :class="childDisabled ? 'opacity-50' : 'cursor-pointer'">
          <span class="text-[color:var(--muted-foreground)]">{{ t.snap.toGrid }}</span>
          <input type="checkbox" v-model="ui.snapToGrid" :disabled="childDisabled" class="hc-snap-checkbox">
        </label>
        <label class="flex items-center justify-between" :class="childDisabled ? 'opacity-50' : 'cursor-pointer'">
          <span class="text-[color:var(--muted-foreground)]">{{ t.snap.toCanvas }}</span>
          <input type="checkbox" v-model="ui.snapToCanvas" :disabled="childDisabled" class="hc-snap-checkbox">
        </label>
        <label class="flex items-center justify-between" :class="childDisabled ? 'opacity-50' : 'cursor-pointer'">
          <span class="text-[color:var(--muted-foreground)]">{{ t.snap.toElement }}</span>
          <input type="checkbox" v-model="ui.snapToElement" :disabled="childDisabled" class="hc-snap-checkbox">
        </label>
        <label class="flex items-center justify-between" :class="childDisabled ? 'opacity-50' : 'cursor-pointer'">
          <span class="text-[color:var(--muted-foreground)]">{{ t.snap.toDistribute }}</span>
          <input type="checkbox" v-model="ui.snapToDistribute" :disabled="childDisabled" class="hc-snap-checkbox">
        </label>

        <hr class="border-[color:var(--border)]">

        <!-- threshold 滑块 -->
        <label class="flex flex-col gap-1" :class="childDisabled ? 'opacity-50' : ''">
          <div class="flex items-center justify-between">
            <span class="text-[color:var(--muted-foreground)]">{{ t.snap.threshold }}</span>
            <span class="font-mono tabular-nums">{{ ui.snapThreshold }}px</span>
          </div>
          <input
            type="range"
            min="1"
            max="32"
            :value="ui.snapThreshold"
            :disabled="childDisabled"
            @input="onThresholdInput"
          >
        </label>

        <p class="text-xs text-[color:var(--muted-foreground)] leading-snug">
          {{ t.snap.shiftHint }}
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hc-snap-checkbox {
    width: 0.9rem;
    height: 0.9rem;
    /* M24-B：用 primary（= 当前 accent）替代浅蓝 #60a5fa，跟随 ThemeSwitcher accent */
    accent-color: var(--primary);
}
</style>
