<script setup lang="ts">
import { ref } from 'vue';
import type { Element } from '@/types/protocol';

// 双击文本元素弹出的就地编辑 textarea；背景透明 + 字体继承，营造"直接在画布上编辑"观感。
// PreviewRenderer 由父组件传 hide set，跳过 editingId 对应的 element，避免画布底层字形与 textarea 重影。

interface TextLike {
    id: string;
    type: 'text';
    text: string;
    x: number; y: number; w: number; h: number;
    fontSize: number;
    fontId: string;
    color: string;
    align: string;
    rotation: number;
}

const props = defineProps<{
    element: Element | null;
}>();

const emit = defineEmits<{
    input: [ev: Event];
    finish: [];
    keydown: [ev: KeyboardEvent];
}>();

const textarea = ref<HTMLTextAreaElement | null>(null);

defineExpose({ focus: () => textarea.value?.focus() });

function asText(el: Element | null): TextLike | null {
    return el && el.type === 'text' ? (el as unknown as TextLike) : null;
}
</script>

<template>
  <textarea
    v-if="asText(props.element)"
    ref="textarea"
    class="hc-edit-textarea"
    :value="asText(props.element)!.text"
    :style="{
      left: `${asText(props.element)!.x}px`,
      top: `${asText(props.element)!.y}px`,
      width: `${asText(props.element)!.w}px`,
      height: `${asText(props.element)!.h}px`,
      fontSize: `${asText(props.element)!.fontSize}px`,
      fontFamily: asText(props.element)!.fontId,
      color: asText(props.element)!.color,
      textAlign: asText(props.element)!.align as any,
      transform: `rotate(${asText(props.element)!.rotation}deg)`,
      transformOrigin: 'center center',
    }"
    @input="(ev) => emit('input', ev)"
    @blur="emit('finish')"
    @keydown="(ev) => emit('keydown', ev)"
    @mousedown.stop
    @dblclick.stop
  />
</template>

<style scoped>
.hc-edit-textarea {
    position: absolute;
    margin: 0;
    padding: 0;
    border: 1px dashed var(--ring);
    outline: none;
    resize: none;
    background: transparent;
    line-height: 1;
    overflow: hidden;
    caret-color: var(--ring);
    white-space: pre-wrap;
    word-break: break-word;
}
.hc-edit-textarea:focus {
    border-style: solid;
}
</style>
