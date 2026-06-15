<script setup lang="ts">
import { ref } from 'vue';
import { onClickOutside } from '@vueuse/core';
import { MoreHorizontal, X } from 'lucide-vue-next';
import Tooltip from '@/components/ui/Tooltip.vue';
import { useI18n } from '@/i18n';

/**
 * 0.7.4：TopBar 溢出菜单。
 *
 * 用法：当右侧容器空间不够展示全部按钮时，低频按钮折叠到此 … popover 中。
 * popover 里纵向列出项（图标 + 文字标签），比纯图标更易认。
 *
 * 触发方式遵循 SnapSettingsPopover / ThemeSwitcher 的约定：
 *   - 按钮点击才开（非 hover / select-change），防 onClickOutside 立即关
 *   - onClickOutside 关闭
 *   - ignore '.hc-tooltip'（防 tooltip teleport 误触关闭）
 *   - absolute 定位 right-0 top-full，向下展开，不被 header 裁切
 */

const { t } = useI18n();

const rootRef = ref<HTMLElement | null>(null);
const open = ref(false);

onClickOutside(rootRef, () => { open.value = false; }, { ignore: ['.hc-tooltip'] });

function toggle() { open.value = !open.value; }
function close() { open.value = false; }

/** 关闭菜单（slot 内点击捕获阶段调用，保证子按钮自身逻辑仍能执行）。 */
function handleItemClick() {
    close();
}
</script>

<template>
  <div ref="rootRef" class="relative">
    <Tooltip :text="t.topbar.more">
      <button
        type="button"
        class="hc-btn p-1.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors"
        :aria-pressed="open"
        :aria-label="t.topbar.more"
        @click="toggle"
      >
        <MoreHorizontal class="size-4" />
      </button>
    </Tooltip>

    <!-- popover：向下展开，right 对齐；z-50 同 SnapSettingsPopover -->
    <div
      v-if="open"
      class="absolute right-0 top-full mt-1 z-50 min-w-48 rounded-[var(--radius-sm)] border border-[color:var(--border)] bg-[color:var(--card)] text-[color:var(--card-foreground)] shadow-md text-xs overflow-hidden"
    >
      <header class="flex items-center justify-between px-3 h-8 border-b border-[color:var(--border)]">
        <span class="font-medium uppercase tracking-wider text-[color:var(--muted-foreground)]">
          {{ t.topbar.more }}
        </span>
        <button
          type="button"
          class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors"
          @click="close"
        >
          <X class="size-3" />
        </button>
      </header>

      <!-- 默认插槽：调用方传入各行按钮 -->
      <div class="py-1" @click.capture="handleItemClick">
        <slot />
      </div>
    </div>
  </div>
</template>
