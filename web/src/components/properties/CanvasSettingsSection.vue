<script setup lang="ts">
/**
 * M17 F5：画板设置段。RightPanel 在"未选中任何元素"时显示，提供：
 * - background：FillInput 联合（solid / linear / radial），含 alpha 通道
 *
 * 数据流：FillInput emit 新 Fill → 本组件 optimistic patch 到 store.canvas.background
 * → 发 WS op {@code canvas.background} 携带 {@code fill}（M17 F5 协议升级，与
 * legacy {@code color} 字段并存）。
 *
 * 棋盘格 UI 提示：FillInput 渲染色块本身已带 alpha 棋盘格；这里只展示画板缩略图视觉
 * 提示——alpha<1 时 swatch 用 CSS 棋盘背景；不参与 PaletteLut 查找，后端走 transparent
 * path。
 */
import { computed } from 'vue';
import { Settings } from 'lucide-vue-next';
import FillInput from '@/components/ui/FillInput.vue';
import { useProjectStore } from '@/stores/project';
import { getWsClient } from '@/network/wsClient';
import { normalizeFill } from '@/render/fill';
import { useI18n } from '@/i18n';
import type { Fill, FillCompat } from '@/types/protocol';

const project = useProjectStore();
const ws = getWsClient();
const { t } = useI18n();

const bgFill = computed<FillCompat | undefined>(() => {
    if (!project.state) return undefined;
    return project.state.canvas.background;
});

function onBgUpdate(next: Fill): void {
    if (!project.state) return;
    // optimistic：先把本地 canvas.background 替换为 object 形态（FillInput 统一 emit object）
    project.state.canvas.background = next;
    // M17 F5：协议字段 {@code fill}（Fill 对象）。后端 EditOpDispatcher 优先识别 fill，
    // 兼容旧 {@code color} 字符串字段。
    ws.send('canvas.background', { fill: next });
}

/** 是否半透明 fill（任何 stop 或 solid 颜色含 alpha<FF） */
const hasAlpha = computed(() => {
    const f = normalizeFill(bgFill.value);
    if (!f) return false;
    if (f.type === 'solid') return /^#[0-9A-Fa-f]{6}[0-9A-Fa-f]{2}$/.test(f.color) && !/FF$/i.test(f.color);
    return f.stops.some((s) => /^#[0-9A-Fa-f]{6}[0-9A-Fa-f]{2}$/.test(s.color) && !/FF$/i.test(s.color));
});
</script>

<template>
  <section v-if="project.state" class="border-t border-[color:var(--border)] mt-2 pt-2">
    <header class="flex items-center gap-2 px-3 h-8 text-xs font-medium uppercase tracking-wider text-[color:var(--muted-foreground)]">
      <Settings class="size-3" />
      <span>{{ t.canvas.settings }}</span>
    </header>
    <div class="px-3 pb-3 space-y-2 text-xs">
      <label class="flex flex-col gap-1">
        <span class="text-[color:var(--muted-foreground)]">{{ t.canvas.backgroundLabel }}</span>
        <div class="hc-canvas-bg-wrap" :class="{ 'hc-has-alpha': hasAlpha }">
          <FillInput :model-value="bgFill" @update:model-value="onBgUpdate" />
        </div>
      </label>
    </div>
  </section>
</template>

<style scoped>
/* M17 F5：半透明 background 时给 FillInput 包一层 CSS 棋盘格背景，
   提示用户「画板是半透明的」。FillInput 自身的 color swatch 已经画 alpha；
   此处只是给整段容器一个语义化的视觉指示。 */
.hc-canvas-bg-wrap.hc-has-alpha {
    background-image:
        linear-gradient(45deg, rgba(0, 0, 0, 0.06) 25%, transparent 25%),
        linear-gradient(-45deg, rgba(0, 0, 0, 0.06) 25%, transparent 25%),
        linear-gradient(45deg, transparent 75%, rgba(0, 0, 0, 0.06) 75%),
        linear-gradient(-45deg, transparent 75%, rgba(0, 0, 0, 0.06) 75%);
    background-size: 8px 8px;
    background-position: 0 0, 0 4px, 4px -4px, -4px 0;
    border-radius: 4px;
    padding: 4px;
}
</style>
