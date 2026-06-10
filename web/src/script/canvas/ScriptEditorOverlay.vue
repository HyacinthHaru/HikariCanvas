<script setup lang="ts">
/**
 * 0.7.0-P4-B：积木脚本编辑器全屏 overlay 容器。
 *
 * <p>fixed 全屏（z-index 60，高于时间轴 dock）。头部工具条 = 标题 + 关闭 X + "新建规则"
 * 按钮（<b>B 阶段占位禁用</b>，真逻辑任务 D）+ zoom 显示 % + reset 按钮。主体 = 左侧
 * BlockPalette <b>占位空壳 div</b>（真 palette 任务 D）+ 右侧 {@link BlockCanvas}。</p>
 *
 * <p>挂 {@code ui.scriptEditorOpen}（App.vue 末尾 v-if 懒加载挂载）。Esc 关闭。配色走
 * Catppuccin token（--background / --card / --border 等）。i18n 在 script setup 里用
 * {@code t.value.xxx}（{@code useI18n} 返 ComputedRef）。</p>
 */
import { computed, ref } from 'vue';
import { useEventListener } from '@vueuse/core';
import { X, Puzzle, Plus, RotateCcw } from 'lucide-vue-next';
import { useUiStore } from '@/stores/ui';
import { useScriptStore } from '@/stores/scripts';
import { useI18n } from '@/i18n';
import BlockCanvas from './BlockCanvas.vue';

const ui = useUiStore();
const scripts = useScriptStore();
const { t } = useI18n();

const canvasRef = ref<InstanceType<typeof BlockCanvas> | null>(null);

/** 头部 zoom 百分比显示（读 BlockCanvas 暴露的 zoom ref）。 */
const zoomPct = computed(() => {
    const z = canvasRef.value?.zoom ?? 1;
    return Math.round((typeof z === 'number' ? z : z.value ?? 1) * 100);
});

function resetView(): void {
    canvasRef.value?.resetView();
}

function close(): void {
    ui.closeScriptEditor();
}

// Esc 关闭。capture 阶段优先，避免被画布内部吞掉。
useEventListener(document, 'keydown', (e: KeyboardEvent) => {
    if (e.key === 'Escape') {
        e.preventDefault();
        close();
    }
});
</script>

<template>
  <div class="hc-script-overlay" role="dialog" aria-modal="true">
    <header class="hc-script-header">
      <Puzzle class="size-4 text-[color:var(--ctp-mauve)]" />
      <h2 class="text-sm font-semibold">{{ t.script.editorTitle }}</h2>

      <!-- 新建规则：B 阶段占位禁用（真逻辑任务 D） -->
      <button
        class="hc-script-btn ml-3 opacity-50 cursor-not-allowed"
        disabled
        :title="t.script.newRule"
      >
        <Plus class="size-3.5" />
        <span>{{ t.script.newRule }}</span>
      </button>

      <div class="ml-auto flex items-center gap-2">
        <span class="text-xs text-[color:var(--muted-foreground)] tabular-nums w-12 text-right">
          {{ zoomPct }}%
        </span>
        <button class="hc-script-icon-btn" :title="t.script.resetView" @click="resetView">
          <RotateCcw class="size-4" />
        </button>
        <button class="hc-script-icon-btn" :title="t.script.close" @click="close">
          <X class="size-4" />
        </button>
      </div>
    </header>

    <div class="hc-script-body">
      <!-- 左侧 BlockPalette 占位空壳（真 palette 任务 D） -->
      <aside class="hc-script-palette">
        <div class="text-[10px] uppercase tracking-wide text-[color:var(--muted-foreground)] px-3 pt-3 pb-1">
          {{ t.script.paletteTitle }}
        </div>
        <div class="px-3 py-2 text-xs text-[color:var(--muted-foreground)]">
          {{ t.script.palettePlaceholder }}
        </div>
      </aside>

      <!-- 主体画布 -->
      <main class="hc-script-canvas-host">
        <BlockCanvas ref="canvasRef" />
        <!-- 空画布提示：无规则时显示（C 阶段接真规则后，有规则即隐藏） -->
        <div v-if="scripts.size === 0" class="hc-script-empty-hint">
          {{ t.script.empty }}
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.hc-script-overlay {
    position: fixed;
    inset: 0;
    z-index: 60;
    display: flex;
    flex-direction: column;
    background: var(--background);
    color: var(--foreground);
}
.hc-script-header {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    height: 2.75rem;
    padding: 0 0.75rem;
    border-bottom: 1px solid var(--border);
    background: var(--card);
    color: var(--card-foreground);
    flex-shrink: 0;
}
.hc-script-body {
    flex: 1;
    display: flex;
    min-height: 0;
}
.hc-script-palette {
    width: 200px;
    flex-shrink: 0;
    border-right: 1px solid var(--border);
    background: var(--card);
    overflow-y: auto;
}
.hc-script-canvas-host {
    flex: 1;
    position: relative;
    min-width: 0;
}
.hc-script-empty-hint {
    position: absolute;
    top: 1rem;
    left: 50%;
    transform: translateX(-50%);
    padding: 0.375rem 0.75rem;
    border-radius: var(--radius-sm);
    background: color-mix(in srgb, var(--card) 80%, transparent);
    border: 1px solid var(--border);
    font-size: 0.75rem;
    color: var(--muted-foreground);
    pointer-events: none;
    max-width: 80%;
    text-align: center;
}
.hc-script-btn {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
    padding: 0.25rem 0.625rem;
    font-size: 0.75rem;
    border-radius: var(--radius-sm);
    background: var(--primary);
    color: var(--primary-foreground);
}
.hc-script-icon-btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    padding: 0.375rem;
    border-radius: var(--radius-sm);
    color: var(--muted-foreground);
}
.hc-script-icon-btn:hover {
    background: var(--accent);
    color: var(--foreground);
}
</style>
