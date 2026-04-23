<script setup lang="ts">
import { Sparkles, Undo2, Redo2, Paintbrush, RadioTower, Type, Square } from 'lucide-vue-next';
import { getWsClient } from '@/network/wsClient';
import { useNetworkStore } from '@/stores/network';

const net = useNetworkStore();
const ws = getWsClient();

function runOp(op: string, payload?: unknown) {
    ws.send(op, payload);
}
</script>

<template>
  <aside class="w-12 bg-[color:var(--card)] border-r border-[color:var(--border)] flex flex-col items-center py-2 gap-1">
    <button
      class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
      :disabled="!net.authenticated"
      title="Apply hello_world template"
      @click="runOp('template.apply', { templateId: 'hello_world' })"
    >
      <Sparkles class="size-5" />
    </button>
    <button
      class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
      :disabled="!net.authenticated"
      title="Add text (M5-B 编辑器 UI 接入后走属性面板；暂走默认参数)"
      @click="runOp('element.add', {
        type: 'text',
        props: { text: 'TEXT', x: 32, y: 32, w: 192, h: 24, fontSize: 16, color: '#FFFFFF', align: 'center', fontId: 'ark_pixel' },
      })"
    >
      <Type class="size-5" />
    </button>
    <button
      class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
      :disabled="!net.authenticated"
      title="Add rect"
      @click="runOp('element.add', {
        type: 'rect',
        props: { x: 32, y: 32, w: 64, h: 64, fill: '#FF3366' },
      })"
    >
      <Square class="size-5" />
    </button>

    <div class="mt-2 mb-1 w-8 h-px bg-[color:var(--border)]"></div>

    <button
      class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
      :disabled="!net.authenticated"
      title="Undo (Ctrl+Z)"
      @click="runOp('undo', {})"
    >
      <Undo2 class="size-5" />
    </button>
    <button
      class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
      :disabled="!net.authenticated"
      title="Redo (Ctrl+Shift+Z)"
      @click="runOp('redo', {})"
    >
      <Redo2 class="size-5" />
    </button>

    <div class="mt-2 mb-1 w-8 h-px bg-[color:var(--border)]"></div>

    <button
      class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
      :disabled="!net.authenticated"
      title="Ping"
      @click="runOp('ping')"
    >
      <RadioTower class="size-5" />
    </button>
    <button
      class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
      :disabled="!net.authenticated"
      title="M1 demo: paint all maps red"
      @click="runOp('paint')"
    >
      <Paintbrush class="size-5" />
    </button>
  </aside>
</template>
