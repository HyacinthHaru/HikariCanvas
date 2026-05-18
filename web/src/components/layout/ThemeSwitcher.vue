<script setup lang="ts">
import { computed, ref } from 'vue';
import { onClickOutside } from '@vueuse/core';
import { Palette, Check, X } from 'lucide-vue-next';
import { useThemeStore, PRESETS, ACCENTS, RADIUS_OPTIONS, type Flavor, type Accent, type RadiusScale } from '@/stores/theme';
import { useI18n } from '@/i18n';
import Tooltip from '@/components/ui/Tooltip.vue';

/**
 * M24-B 主题切换器：扁平化 M3 风格 popover。
 *
 * 挂载在 TopBar 右侧，与 SnapSettingsPopover / locale toggle 同级。
 *
 * 内容：
 *   - 主题 preset（Catppuccin Latte / Frappé / Macchiato）
 *   - Accent 色（8 个 Catppuccin accent）
 *   - Radius scale（sm / md / lg / xl / full）
 *
 * 风格纪律（避开 AI 审美）：
 *   - 单一 surface tone（var(--card)）做底，不堆 backdrop-blur / shadow-2xl；
 *   - 选中态用 ring + check icon，不用 glow；
 *   - 圆点 swatch + radio 文本，简洁高对比度；
 *   - hover 不上 scale，按 M3 用底色加深表达交互态。
 */
const theme = useThemeStore();
const { t, locale } = useI18n();

const rootRef = ref<HTMLElement | null>(null);
const open = ref(false);
onClickOutside(rootRef, () => { open.value = false; });

function toggle(): void { open.value = !open.value; }
function close(): void { open.value = false; }

function flavorLabel(p: typeof PRESETS[number]): string {
    return locale.value === 'zh' ? p.nameZh : p.nameEn;
}

function radiusLabel(id: RadiusScale): string {
    switch (id) {
        case 'sm':   return t.value.theme.radiusSm;
        case 'md':   return t.value.theme.radiusMd;
        case 'lg':   return t.value.theme.radiusLg;
        case 'xl':   return t.value.theme.radiusXl;
        case 'full': return t.value.theme.radiusFull;
    }
}

function chooseFlavor(f: Flavor): void { theme.setFlavor(f); }
function chooseAccent(a: Accent): void { theme.setAccent(a); }
function chooseRadius(r: RadiusScale): void { theme.setRadius(r); }

const isActiveFlavor = computed(() => (f: Flavor) => theme.flavor === f);
const isActiveAccent = computed(() => (a: Accent) => theme.accent === a);
const isActiveRadius = computed(() => (r: RadiusScale) => theme.radius === r);
</script>

<template>
  <div ref="rootRef" class="relative">
    <Tooltip :text="t.theme.tipTrigger">
      <button
        type="button"
        class="hc-btn p-1.5 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors"
        :aria-pressed="open"
        @click="toggle"
      >
        <Palette class="size-4" />
      </button>
    </Tooltip>

    <div
      v-if="open"
      class="absolute right-0 top-full mt-1.5 z-50 w-72 rounded-[var(--radius)] border border-[color:var(--border)] bg-[color:var(--card)] text-[color:var(--card-foreground)] text-xs"
    >
      <header class="flex items-center justify-between px-3 h-9 border-b border-[color:var(--border)]">
        <span class="font-medium uppercase tracking-wider text-[color:var(--muted-foreground)]">
          {{ t.theme.settings }}
        </span>
        <button
          type="button"
          class="hc-btn p-1 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] transition-colors"
          @click="close"
        >
          <X class="size-3" />
        </button>
      </header>

      <!-- Preset flavor -->
      <section class="px-3 pt-3 pb-2">
        <h6 class="text-xs font-semibold uppercase tracking-wider text-[color:var(--muted-foreground)] mb-2">
          {{ t.theme.presetHeader }}
        </h6>
        <div class="flex flex-col gap-1">
          <button
            v-for="p in PRESETS"
            :key="p.id"
            type="button"
            class="hc-btn flex items-center gap-2.5 w-full px-2 py-1.5 rounded-[var(--radius-sm)] text-left transition-colors"
            :class="isActiveFlavor(p.id)
              ? 'bg-[color:var(--accent)]'
              : 'hover:bg-[color:var(--accent)]'"
            @click="chooseFlavor(p.id)"
          >
            <span class="relative inline-flex shrink-0 size-6 rounded-full overflow-hidden border border-[color:var(--border)]">
              <span class="absolute inset-0" :style="{ background: p.previewBase }"></span>
              <span class="absolute bottom-0 right-0 size-3 rounded-tl-full" :style="{ background: p.previewAccent }"></span>
            </span>
            <span class="flex-1 text-xs">{{ flavorLabel(p) }}</span>
            <Check v-if="isActiveFlavor(p.id)" class="size-3.5 text-[color:var(--primary)]" />
          </button>
        </div>
      </section>

      <hr class="border-[color:var(--border)] mx-3">

      <!-- Accent -->
      <section class="px-3 pt-3 pb-2">
        <h6 class="text-xs font-semibold uppercase tracking-wider text-[color:var(--muted-foreground)] mb-2">
          {{ t.theme.accentHeader }}
        </h6>
        <div class="grid grid-cols-8 gap-1.5">
          <button
            v-for="a in ACCENTS"
            :key="a.id"
            type="button"
            class="hc-btn relative size-6 rounded-full transition-transform hover:scale-110"
            :class="isActiveAccent(a.id)
              ? 'ring-2 ring-offset-2 ring-offset-[color:var(--card)] ring-[color:var(--foreground)]'
              : 'ring-1 ring-[color:var(--border)]'"
            :style="{ background: a.color }"
            :aria-label="a.id"
            :title="a.id"
            @click="chooseAccent(a.id)"
          ></button>
        </div>
      </section>

      <hr class="border-[color:var(--border)] mx-3">

      <!-- Radius -->
      <section class="px-3 pt-3 pb-3">
        <h6 class="text-xs font-semibold uppercase tracking-wider text-[color:var(--muted-foreground)] mb-2">
          {{ t.theme.radiusHeader }}
        </h6>
        <div class="grid grid-cols-5 gap-1.5">
          <button
            v-for="r in RADIUS_OPTIONS"
            :key="r.id"
            type="button"
            class="hc-btn flex flex-col items-center gap-1 py-2 transition-colors"
            :class="isActiveRadius(r.id)
              ? 'bg-[color:var(--accent)]'
              : 'hover:bg-[color:var(--accent)]'"
            :style="{ borderRadius: `${Math.min(r.px, 16)}px` }"
            @click="chooseRadius(r.id)"
          >
            <span
              class="size-4 bg-[color:var(--primary)]"
              :style="{ borderRadius: `${Math.min(r.px, 14)}px` }"
            ></span>
            <span class="text-xs text-[color:var(--muted-foreground)]">{{ radiusLabel(r.id) }}</span>
          </button>
        </div>
      </section>
    </div>
  </div>
</template>
