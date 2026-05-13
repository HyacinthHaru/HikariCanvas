<script setup lang="ts">
import { Sparkles, Undo2, Redo2, Type, Square, MousePointer2, Move, Minus, MoveRight, Circle, Star } from 'lucide-vue-next';
import { getWsClient } from '@/network/wsClient';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { useTemplatesStore } from '@/stores/templates';
import { useUiStore } from '@/stores/ui';
import { useI18n } from '@/i18n';
import Tooltip from '@/components/ui/Tooltip.vue';

const net = useNetworkStore();
const project = useProjectStore();
const ws = getWsClient();
const templates = useTemplatesStore();
const ui = useUiStore();
const { t } = useI18n();

function runOp(op: string, payload?: unknown) {
    ws.send(op, payload);
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
    const box = centeredBox(192, 48);
    runOp('element.add', {
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
    const box = centeredBox(80, 80);
    runOp('element.add', {
        type: 'rect',
        props: { x: box.x, y: box.y, w: box.w, h: box.h, fill: '#FF3366' },
    });
}
</script>

<template>
  <aside class="w-12 bg-[color:var(--card)] border-r border-[color:var(--border)] flex flex-col items-center py-2 gap-1">
    <!-- 工具模式：Select（带 transformer 锚点）vs Move（PS 风格纯拖拽） -->
    <Tooltip :text="t.tools.selectTool" shortcut="V">
      <button
        class="p-2 rounded transition-colors"
        :class="ui.activeTool === 'select'
          ? 'bg-[color:var(--accent)] text-[color:var(--accent-foreground)] ring-1 ring-[color:var(--ring)]'
          : 'hover:bg-[color:var(--accent)]'"
        @click="ui.setTool('select')"
      >
        <MousePointer2 class="size-5" />
      </button>
    </Tooltip>
    <Tooltip :text="t.tools.moveTool" shortcut="M">
      <button
        class="p-2 rounded transition-colors"
        :class="ui.activeTool === 'move'
          ? 'bg-[color:var(--accent)] text-[color:var(--accent-foreground)] ring-1 ring-[color:var(--ring)]'
          : 'hover:bg-[color:var(--accent)]'"
        @click="ui.setTool('move')"
      >
        <Move class="size-5" />
      </button>
    </Tooltip>

    <div class="my-1 w-8 h-px bg-[color:var(--border)]"></div>

    <!-- M9-D：绘制工具激活态切换（drag-to-create 在 M9-E 接入） -->
    <Tooltip :text="t.tools.lineTool" shortcut="L">
      <button
        class="p-2 rounded transition-colors"
        :class="ui.activeTool === 'line'
          ? 'bg-[color:var(--accent)] text-[color:var(--accent-foreground)] ring-1 ring-[color:var(--ring)]'
          : 'hover:bg-[color:var(--accent)]'"
        @click="ui.setTool('line')"
      >
        <Minus class="size-5" />
      </button>
    </Tooltip>
    <Tooltip :text="t.tools.arrowTool" shortcut="A">
      <button
        class="p-2 rounded transition-colors"
        :class="ui.activeTool === 'arrow'
          ? 'bg-[color:var(--accent)] text-[color:var(--accent-foreground)] ring-1 ring-[color:var(--ring)]'
          : 'hover:bg-[color:var(--accent)]'"
        @click="ui.setTool('arrow')"
      >
        <MoveRight class="size-5" />
      </button>
    </Tooltip>
    <Tooltip :text="t.tools.circleTool" shortcut="C">
      <button
        class="p-2 rounded transition-colors"
        :class="ui.activeTool === 'circle'
          ? 'bg-[color:var(--accent)] text-[color:var(--accent-foreground)] ring-1 ring-[color:var(--ring)]'
          : 'hover:bg-[color:var(--accent)]'"
        @click="ui.setTool('circle')"
      >
        <Circle class="size-5" />
      </button>
    </Tooltip>
    <Tooltip :text="t.tools.starTool" shortcut="S">
      <button
        class="p-2 rounded transition-colors"
        :class="ui.activeTool === 'star'
          ? 'bg-[color:var(--accent)] text-[color:var(--accent-foreground)] ring-1 ring-[color:var(--ring)]'
          : 'hover:bg-[color:var(--accent)]'"
        @click="ui.setTool('star')"
      >
        <Star class="size-5" />
      </button>
    </Tooltip>

    <div class="my-1 w-8 h-px bg-[color:var(--border)]"></div>

    <Tooltip :text="t.tools.openTemplates">
      <button
        class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
        :disabled="!net.authenticated"
        @click="templates.openGallery()"
      >
        <Sparkles class="size-5" />
      </button>
    </Tooltip>
    <Tooltip :text="t.tools.addText">
      <button
        class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
        :disabled="!net.authenticated"
        @click="addText"
      >
        <Type class="size-5" />
      </button>
    </Tooltip>
    <Tooltip :text="t.tools.addRect">
      <button
        class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
        :disabled="!net.authenticated"
        @click="addRect"
      >
        <Square class="size-5" />
      </button>
    </Tooltip>

    <div class="my-1 w-8 h-px bg-[color:var(--border)]"></div>

    <Tooltip :text="t.tools.undo" shortcut="Ctrl+Z">
      <button
        class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
        :disabled="!net.authenticated"
        @click="runOp('undo', {})"
      >
        <Undo2 class="size-5" />
      </button>
    </Tooltip>
    <Tooltip :text="t.tools.redo" shortcut="Ctrl+⇧Z">
      <button
        class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
        :disabled="!net.authenticated"
        @click="runOp('redo', {})"
      >
        <Redo2 class="size-5" />
      </button>
    </Tooltip>
  </aside>
</template>
