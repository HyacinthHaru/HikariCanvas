<script setup lang="ts">
/**
 * 双击文本元素弹出的就地编辑器（0.4.1 chip 化）。
 *
 * <p>0.4.1 把内部从 {@code <textarea>} 升级为 {@link VariableChipEditor}：占位符
 * {@code ${var:X}} 在画布上直接渲染成 chip pill，而不是字面字符串。背景透明 +
 * 字体继承 → 营造"直接在画布上编辑"观感不变。</p>
 *
 * <p>PreviewRenderer 由父组件传 hide set，跳过 editingId 对应的 element，避免画布底层
 * 字形与本编辑器重影。</p>
 *
 * <p>原 textarea 的 input/keydown 事件路径换成 chip editor 的 update:text / submit /
 * cancel：父组件收 update:text 后照旧 element.update 上行。</p>
 */
import { ref } from 'vue';
import VariableChipEditor from '@/components/variables/VariableChipEditor.vue';
import type { Element } from '@/types/protocol';

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
    /** 当前 wall ID；chip 用它解析 user/X namespace。 */
    wallId: string | null;
}>();

const emit = defineEmits<{
    /** 新文本（chip 编辑器序列化后的字面 text）。 */
    'update:text': [value: string];
    /** Enter（单行）/ blur → 收编辑态。 */
    finish: [];
    /** ESC → 父组件可选择恢复旧值；当前实现等同 finish。 */
    cancel: [];
    /** P3.5：用户在 inline editor 输入 ${ 或点已有 chip 时，让 CanvasView 弹 picker。 */
    insertVariableRequest: [];
    editVariableRequest: [payload: { rawName: string; fallback: string | null }];
    createVariableRequest: [payload: { rawName: string }];
}>();

const chipEditorRef = ref<InstanceType<typeof VariableChipEditor> | null>(null);

defineExpose({
    focus: () => chipEditorRef.value?.focus(),
    insertVariableChip: (rawName: string, fallback: string | null = null) =>
        chipEditorRef.value?.insertVariableChip(rawName, fallback),
    replaceVariableChip: (oldRaw: string, newRaw: string, newFallback: string | null = null) =>
        chipEditorRef.value?.replaceVariableChip(oldRaw, newRaw, newFallback),
});

function asText(el: Element | null): TextLike | null {
    return el && el.type === 'text' ? (el as unknown as TextLike) : null;
}

function onUpdateText(v: string) {
    emit('update:text', v);
}

function onSubmit() {
    emit('finish');
}

function onCancel() {
    emit('cancel');
}
</script>

<template>
  <div
    v-if="asText(props.element)"
    class="hc-edit-host"
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
    @mousedown.stop
    @dblclick.stop
  >
    <VariableChipEditor
      ref="chipEditorRef"
      class="hc-edit-chip-editor"
      :text="asText(props.element)!.text"
      :wall-id="wallId"
      :font-size="asText(props.element)!.fontSize"
      :font-family="asText(props.element)!.fontId"
      :multi-line="true"
      :auto-focus="true"
      @update:text="onUpdateText"
      @submit="onSubmit"
      @cancel="onCancel"
      @insert-variable-request="emit('insertVariableRequest')"
      @edit-variable-request="(p) => emit('editVariableRequest', p)"
      @create-variable-request="(p) => emit('createVariableRequest', p)"
    />
  </div>
</template>

<style scoped>
.hc-edit-host {
    position: absolute;
    margin: 0;
    padding: 0;
    line-height: 1;
}
/* 让内部 chip editor 完全填满 + 看起来像 textarea：透明背景 + 虚线边框 */
.hc-edit-host :deep(.hc-chip-editor) {
    height: 100%;
}
.hc-edit-host :deep(.hc-chip-editable) {
    width: 100%;
    height: 100%;
    min-height: unset;
    padding: 0;
    background: transparent;
    color: inherit;
    border: 1px dashed var(--ring);
    border-radius: 0;
    box-shadow: none;
    font-size: inherit;
    font-family: inherit;
    line-height: 1;
    caret-color: var(--ring);
    overflow: hidden;
}
.hc-edit-host :deep(.hc-chip-editable:focus) {
    border-style: solid;
    box-shadow: none;
}
</style>
