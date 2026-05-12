<script setup lang="ts">
import { Sparkles, Undo2, Redo2, Paintbrush, RadioTower, Type, Square, MousePointer2, Move } from 'lucide-vue-next';
import { getWsClient } from '@/network/wsClient';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { useTemplatesStore } from '@/stores/templates';
import { useUiStore } from '@/stores/ui';
import { useI18n } from '@/i18n';

const net = useNetworkStore();
const project = useProjectStore();
const ws = getWsClient();
const templates = useTemplatesStore();
const ui = useUiStore();
const { t } = useI18n();

function runOp(op: string, payload?: unknown) {
    ws.send(op, payload);
}

/** 计算新元素的居中坐标，避免硬编码 (32,32) 把元素堆在角落叠在模板上。 */
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
    <!-- 工具模式切换：Select（默认，含 transformer 锚点）vs Move（纯拖拽，无锚点） -->
    <button
      class="p-2 rounded transition-colors"
      :class="ui.activeTool === 'select'
        ? 'bg-[color:var(--accent)] text-[color:var(--accent-foreground)] ring-1 ring-[color:var(--ring)]'
        : 'hover:bg-[color:var(--accent)]'"
      :title="t.tools.selectTool"
      @click="ui.setTool('select')"
    >
      <MousePointer2 class="size-5" />
    </button>
    <button
      class="p-2 rounded transition-colors"
      :class="ui.activeTool === 'move'
        ? 'bg-[color:var(--accent)] text-[color:var(--accent-foreground)] ring-1 ring-[color:var(--ring)]'
        : 'hover:bg-[color:var(--accent)]'"
      :title="t.tools.moveTool"
      @click="ui.setTool('move')"
    >
      <Move class="size-5" />
    </button>

    <div class="my-1 w-8 h-px bg-[color:var(--border)]"></div>

    <button
      class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
      :disabled="!net.authenticated"
      :title="t.tools.openTemplates"
      @click="templates.openGallery()"
    >
      <Sparkles class="size-5" />
    </button>
    <button
      class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
      :disabled="!net.authenticated"
      :title="t.tools.addText"
      @click="addText"
    >
      <Type class="size-5" />
    </button>
    <button
      class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
      :disabled="!net.authenticated"
      :title="t.tools.addRect"
      @click="addRect"
    >
      <Square class="size-5" />
    </button>

    <div class="mt-2 mb-1 w-8 h-px bg-[color:var(--border)]"></div>

    <button
      class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
      :disabled="!net.authenticated"
      :title="t.tools.undo"
      @click="runOp('undo', {})"
    >
      <Undo2 class="size-5" />
    </button>
    <button
      class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
      :disabled="!net.authenticated"
      :title="t.tools.redo"
      @click="runOp('redo', {})"
    >
      <Redo2 class="size-5" />
    </button>

    <div class="mt-2 mb-1 w-8 h-px bg-[color:var(--border)]"></div>

    <button
      class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
      :disabled="!net.authenticated"
      :title="t.tools.ping"
      @click="runOp('ping')"
    >
      <RadioTower class="size-5" />
    </button>
    <button
      class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
      :disabled="!net.authenticated"
      :title="t.tools.paint"
      @click="runOp('paint')"
    >
      <Paintbrush class="size-5" />
    </button>
  </aside>
</template>
