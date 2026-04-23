<script setup lang="ts">
import { Sparkles, Undo2, Redo2, Paintbrush, RadioTower, Type, Square } from 'lucide-vue-next';
import { getWsClient } from '@/network/wsClient';
import { useNetworkStore } from '@/stores/network';
import { useI18n } from '@/i18n';

const net = useNetworkStore();
const ws = getWsClient();
const { t } = useI18n();

function runOp(op: string, payload?: unknown) {
    ws.send(op, payload);
}
</script>

<template>
  <aside class="w-12 bg-[color:var(--card)] border-r border-[color:var(--border)] flex flex-col items-center py-2 gap-1">
    <button
      class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
      :disabled="!net.authenticated"
      :title="t.tools.applyHello"
      @click="runOp('template.apply', { templateId: 'hello_world' })"
    >
      <Sparkles class="size-5" />
    </button>
    <button
      class="p-2 rounded hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
      :disabled="!net.authenticated"
      :title="t.tools.addText"
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
      :title="t.tools.addRect"
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
