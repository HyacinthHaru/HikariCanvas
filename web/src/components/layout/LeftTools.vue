<script setup lang="ts">
import { Sparkles, Undo2, Redo2, Type, Square, MousePointer2, Move, Hand, Minus, MoveRight, Circle, Star, Brush, PaintBucket, Shapes } from 'lucide-vue-next';
import { ref, computed } from 'vue';
import { useResizeObserver } from '@vueuse/core';
import { getWsClient } from '@/network/wsClient';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { useTemplatesStore } from '@/stores/templates';
import { useUiStore } from '@/stores/ui';
import { useIconLibraryStore } from '@/stores/iconLibrary';
import { useLockGuard } from '@/composables/useLockGuard';
import { useI18n } from '@/i18n';
import Tooltip from '@/components/ui/Tooltip.vue';

const net = useNetworkStore();
const project = useProjectStore();
const ws = getWsClient();
const templates = useTemplatesStore();
const ui = useUiStore();
const iconLibrary = useIconLibraryStore();
const { t } = useI18n();
// lock readonly 多入口 guard。
// 工具切换（V/M/H/L/A/C/S/B/G/I）不属于 mutation —— 这些只改前端 UI 状态，
// 不发 ws.send（select 工具下还能选元素看属性）。所以只 guard 真正下发 op 的按钮：
// addText / addRect / openTemplates / undo / redo + IconLibrary toggle 之外的按钮。
const guard = useLockGuard();

function runOp(op: string, payload?: unknown) {
    // 仅用于 undo / redo —— addText / addRect 走自己的 guard 路径
    if (!guard.guardMutation(opNameForLog(op))) return;
    ws.send(op, payload);
}

function opNameForLog(op: string): string {
    if (op === 'undo') return t.value.tools.undo;
    if (op === 'redo') return t.value.tools.redo;
    return op;
}

/** 居中坐标：避免新元素叠在角落看不见。 */
function centeredBox(w: number, h: number): { x: number; y: number; w: number; h: number } {
    const cw = project.canvasPixelWidth || 256;
    const ch = project.canvasPixelHeight || 128;
    const ww = Math.min(w, Math.max(16, cw - 16));
    const hh = Math.min(h, Math.max(16, ch - 16));
    return {
        x: Math.max(0, Math.round((cw - ww) / 2)),
        y: Math.max(0, Math.round((ch - hh) / 2)),
        w: ww,
        h: hh,
    };
}

function addText() {
    if (!guard.guardMutation(t.value.tools.addText)) return;
    const box = centeredBox(192, 48);
    ws.send('element.add', {
        type: 'text',
        props: {
            text: 'TEXT',
            x: box.x, y: box.y, w: box.w, h: box.h,
            fontSize: 32,
            color: '#FFFFFF',
            align: 'center',
            fontId: 'ark_pixel',
        },
    });
}

function addRect() {
    if (!guard.guardMutation(t.value.tools.addRect)) return;
    const box = centeredBox(80, 80);
    ws.send('element.add', {
        type: 'rect',
        props: { x: box.x, y: box.y, w: box.w, h: box.h, fill: '#FF3366' },
    });
}

/** 打开模板库不属于 mutation（仅打开 UI）；apply 时 TemplateGallery 自身再 guard。 */
function openTemplates() {
    templates.openGallery();
}

// 自动两栏——当 aside 可用高度 < 阈值时切 grid-cols-2。
// 阈值计算：15 按钮 × 36px + 4px gap × 14 + 3 分隔线 × 16px + py-2×2 ≈ 658px → 取 660px。
const SINGLE_COL_HEIGHT_THRESHOLD = 660;
const asideRef = ref<HTMLElement | null>(null);
const availableHeight = ref(window.innerHeight);

useResizeObserver(asideRef, ([entry]) => {
    availableHeight.value = entry.contentRect.height;
});

/** true = 空间够，单列；false = 空间紧，两栏 */
const isSingleCol = computed(() => availableHeight.value >= SINGLE_COL_HEIGHT_THRESHOLD);
</script>

<template>
  <aside
    ref="asideRef"
    class="bg-[color:var(--card)] border-r border-[color:var(--border)] py-2 gap-1 overflow-y-auto"
    :class="isSingleCol
      ? 'w-12 flex flex-col items-center'
      : 'w-24 grid grid-cols-2 items-center justify-items-center'"
  >
    <!-- 工具模式：Select（带 transformer 锚点）vs Move（PS 风格纯拖拽） -->
    <Tooltip :text="t.tools.selectTool" shortcut="V">
      <button
        class="hc-btn p-2 rounded-[var(--radius-sm)] transition-colors"
        :class="ui.activeTool === 'select'
          ? 'bg-[color:var(--primary)]/15 text-[color:var(--primary)] ring-1 ring-[color:var(--primary)]/40'
          : 'hover:bg-[color:var(--accent)]'"
        @click="ui.setTool('select')"
      >
        <MousePointer2 class="size-5" />
      </button>
    </Tooltip>
    <Tooltip :text="t.tools.moveTool" shortcut="M">
      <button
        class="hc-btn p-2 rounded-[var(--radius-sm)] transition-colors"
        :class="ui.activeTool === 'move'
          ? 'bg-[color:var(--primary)]/15 text-[color:var(--primary)] ring-1 ring-[color:var(--primary)]/40'
          : 'hover:bg-[color:var(--accent)]'"
        @click="ui.setTool('move')"
      >
        <Move class="size-5" />
      </button>
    </Tooltip>
    <!-- 手型工具，按住 Space 也可临时切换 -->
    <Tooltip :text="t.tools.handTool" shortcut="H">
      <button
        class="hc-btn p-2 rounded-[var(--radius-sm)] transition-colors"
        :class="ui.activeTool === 'hand'
          ? 'bg-[color:var(--primary)]/15 text-[color:var(--primary)] ring-1 ring-[color:var(--primary)]/40'
          : 'hover:bg-[color:var(--accent)]'"
        @click="ui.setTool('hand')"
      >
        <Hand class="size-5" />
      </button>
    </Tooltip>

    <div class="my-1 h-px bg-[color:var(--border)]" :class="isSingleCol ? 'w-8' : 'col-span-2 w-20'"></div>

    <!-- 绘制工具激活态切换（drag-to-create） -->
    <Tooltip :text="t.tools.lineTool" shortcut="L">
      <button
        class="hc-btn p-2 rounded-[var(--radius-sm)] transition-colors"
        :class="ui.activeTool === 'line'
          ? 'bg-[color:var(--primary)]/15 text-[color:var(--primary)] ring-1 ring-[color:var(--primary)]/40'
          : 'hover:bg-[color:var(--accent)]'"
        @click="ui.setTool('line')"
      >
        <Minus class="size-5" />
      </button>
    </Tooltip>
    <Tooltip :text="t.tools.arrowTool" shortcut="A">
      <button
        class="hc-btn p-2 rounded-[var(--radius-sm)] transition-colors"
        :class="ui.activeTool === 'arrow'
          ? 'bg-[color:var(--primary)]/15 text-[color:var(--primary)] ring-1 ring-[color:var(--primary)]/40'
          : 'hover:bg-[color:var(--accent)]'"
        @click="ui.setTool('arrow')"
      >
        <MoveRight class="size-5" />
      </button>
    </Tooltip>
    <Tooltip :text="t.tools.circleTool" shortcut="C">
      <button
        class="hc-btn p-2 rounded-[var(--radius-sm)] transition-colors"
        :class="ui.activeTool === 'circle'
          ? 'bg-[color:var(--primary)]/15 text-[color:var(--primary)] ring-1 ring-[color:var(--primary)]/40'
          : 'hover:bg-[color:var(--accent)]'"
        @click="ui.setTool('circle')"
      >
        <Circle class="size-5" />
      </button>
    </Tooltip>
    <Tooltip :text="t.tools.starTool" shortcut="S">
      <button
        class="hc-btn p-2 rounded-[var(--radius-sm)] transition-colors"
        :class="ui.activeTool === 'star'
          ? 'bg-[color:var(--primary)]/15 text-[color:var(--primary)] ring-1 ring-[color:var(--primary)]/40'
          : 'hover:bg-[color:var(--accent)]'"
        @click="ui.setTool('star')"
      >
        <Star class="size-5" />
      </button>
    </Tooltip>
    <Tooltip :text="t.tools.brushTool" shortcut="B">
      <button
        class="hc-btn p-2 rounded-[var(--radius-sm)] transition-colors"
        :class="ui.activeTool === 'brush'
          ? 'bg-[color:var(--primary)]/15 text-[color:var(--primary)] ring-1 ring-[color:var(--primary)]/40'
          : 'hover:bg-[color:var(--accent)]'"
        @click="ui.setTool('brush')"
      >
        <Brush class="size-5" />
      </button>
    </Tooltip>
    <!-- Live Paint：油漆桶工具（click 工具，非 drag-to-create） -->
    <Tooltip :text="t.tools.paintBucketTool" shortcut="G">
      <button
        class="hc-btn p-2 rounded-[var(--radius-sm)] transition-colors"
        :class="ui.activeTool === 'paint-bucket'
          ? 'bg-[color:var(--primary)]/15 text-[color:var(--primary)] ring-1 ring-[color:var(--primary)]/40'
          : 'hover:bg-[color:var(--accent)]'"
        @click="ui.setTool('paint-bucket')"
      >
        <PaintBucket class="size-5" />
      </button>
    </Tooltip>

    <div class="my-1 h-px bg-[color:var(--border)]" :class="isSingleCol ? 'w-8' : 'col-span-2 w-20'"></div>

    <!-- 图标库 panel toggle（不参与 activeTool）。快捷键 I。 -->
    <Tooltip :text="t.tools.iconLibraryTool" shortcut="I">
      <button
        data-icon-library-trigger
        class="hc-btn p-2 rounded-[var(--radius-sm)] transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
        :class="iconLibrary.open
          ? 'bg-[color:var(--primary)]/15 text-[color:var(--primary)] ring-1 ring-[color:var(--primary)]/40'
          : 'hover:bg-[color:var(--accent)]'"
        :disabled="!net.authenticated"
        :aria-pressed="iconLibrary.open"
        @click="iconLibrary.toggleOpen()"
      >
        <Shapes class="size-5" />
      </button>
    </Tooltip>
    <!-- 模板库打开本身不是 mutation；只有 applyNow 才是（已在 TemplateGallery 内 guard） -->
    <Tooltip :text="t.tools.openTemplates">
      <button
        class="hc-btn p-2 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
        :disabled="!net.authenticated"
        @click="openTemplates"
      >
        <Sparkles class="size-5" />
      </button>
    </Tooltip>
    <!-- lock 时 disabled，避免视觉误以为可点 -->
    <Tooltip :text="t.tools.addText">
      <button
        class="hc-btn p-2 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
        :disabled="!net.authenticated || guard.readonly.value"
        @click="addText"
      >
        <Type class="size-5" />
      </button>
    </Tooltip>
    <Tooltip :text="t.tools.addRect">
      <button
        class="hc-btn p-2 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
        :disabled="!net.authenticated || guard.readonly.value"
        @click="addRect"
      >
        <Square class="size-5" />
      </button>
    </Tooltip>

    <div class="my-1 h-px bg-[color:var(--border)]" :class="isSingleCol ? 'w-8' : 'col-span-2 w-20'"></div>

    <Tooltip :text="t.tools.undo" shortcut="Ctrl+Z">
      <button
        class="hc-btn p-2 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
        :disabled="!net.authenticated || guard.readonly.value"
        @click="runOp('undo', {})"
      >
        <Undo2 class="size-5" />
      </button>
    </Tooltip>
    <Tooltip :text="t.tools.redo" shortcut="Ctrl+⇧Z">
      <button
        class="hc-btn p-2 rounded-[var(--radius-sm)] hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
        :disabled="!net.authenticated || guard.readonly.value"
        @click="runOp('redo', {})"
      >
        <Redo2 class="size-5" />
      </button>
    </Tooltip>
  </aside>
</template>
