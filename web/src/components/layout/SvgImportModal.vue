<script setup lang="ts">
/**
 * SVG 矢量导入对话框。
 *
 * 流程：选文件 / 拖入 → 直接导入（无二次确认）→ 展示结果 / 错误。
 * SVG 是「追加」到当前工程，不替换任何东西，所以不需要破坏性替换二次确认。
 *
 * <p>文件选择用 accept=".svg,image/svg+xml"：SVG 有标准 MIME，在 macOS Finder
 * 不会被灰掉（区别于 .canvas 无 UTI 注册的问题）。同时支持拖拽。</p>
 *
 * <p>错误处理：catch {@link SvgImportError}（code 字段）或通用异常，
 * 文案由 i18n {@code svgImport.failed(code)} 翻成大白话。</p>
 */
import { ref } from 'vue';
import { X, FileImage, Loader2, CheckCircle2, AlertTriangle } from 'lucide-vue-next';
import { useI18n } from '@/i18n';
import { useSvgImport } from '@/composables/useSvgImport';
import { SvgImportError } from '@/lib/svg/svgSecurity';

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{ (e: 'close'): void }>();

const { t } = useI18n();

/** 处理阶段：idle 选文件 → importing → done / error。SVG 追加不破坏工程，无需二次确认。 */
type Phase = 'idle' | 'importing' | 'done' | 'error';
const phase = ref<Phase>('idle');
const importCount = ref(0);
const errorCode = ref<string | null>(null);
const errorMessage = ref<string | null>(null);

const fileRef = ref<HTMLInputElement | null>(null);
/** 拖拽悬停高亮态。 */
const isDragging = ref(false);

function pickFile() {
    fileRef.value?.click();
}

function onPick(e: Event) {
    const input = e.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    input.value = '';   // 清空，便于再次选同名文件触发 change
    if (file) doImport(file);
}

function onDrop(e: DragEvent) {
    isDragging.value = false;
    const file = e.dataTransfer?.files?.[0] ?? null;
    if (file) doImport(file);
}

/** 实际导入处理：读文件文本 → 调 composable → 按结果切换阶段。供测试 defineExpose。 */
async function doImport(file: File) {
    phase.value = 'importing';
    importCount.value = 0;
    errorCode.value = null;
    errorMessage.value = null;
    try {
        const text = await file.text();
        const r = await useSvgImport().importSvg(text);
        importCount.value = r.count;
        phase.value = 'done';
    } catch (err) {
        if (err instanceof SvgImportError) {
            errorCode.value = err.code;
            errorMessage.value = err.message;
        } else {
            errorCode.value = 'UNKNOWN';
            errorMessage.value = (err as Error).message ?? String(err);
        }
        phase.value = 'error';
    }
}

function onClose() {
    if (phase.value === 'importing') return;   // 导入进行中不可关
    // 重置态，下次打开干净
    phase.value = 'idle';
    isDragging.value = false;
    importCount.value = 0;
    errorCode.value = null;
    errorMessage.value = null;
    emit('close');
}

defineExpose({ doImport });
</script>

<template>
  <div
    v-if="props.open"
    class="fixed inset-0 z-50 bg-[color:var(--ctp-crust)]/50 flex items-center justify-center p-4"
    @click.self="onClose"
  >
    <div class="bg-[color:var(--card)] text-[color:var(--foreground)] rounded-[var(--radius)] shadow-md max-w-md w-full max-h-[90vh] flex flex-col border border-[color:var(--border)]">
      <header class="flex items-center gap-2 px-4 h-11 border-b border-[color:var(--border)]">
        <FileImage class="size-4 text-[color:var(--ctp-blue)]" />
        <h2 class="text-sm font-semibold">{{ t.svgImport.svgImportTitle }}</h2>
        <button
          class="ml-auto p-1 rounded hover:bg-[color:var(--accent)] disabled:opacity-40"
          :disabled="phase === 'importing'"
          @click="onClose"
        >
          <X class="size-4" />
        </button>
      </header>

      <div class="flex-1 overflow-y-auto p-4 space-y-4 text-xs">
        <!-- 隐藏的真实文件选择 input；SVG 有标准 MIME，accept 不会在 macOS Finder 被灰掉 -->
        <input
          ref="fileRef"
          type="file"
          accept=".svg,image/svg+xml"
          class="hidden"
          @change="onPick"
        />

        <!-- idle：选文件 / 拖拽入口 -->
        <template v-if="phase === 'idle'">
          <button
            class="hc-btn w-full flex flex-col items-center justify-center gap-2 px-3 py-6 rounded-[var(--radius-sm)] border border-dashed transition-colors"
            :class="isDragging
              ? 'border-[color:var(--ctp-blue)] bg-[color:var(--ctp-blue)]/10'
              : 'border-[color:var(--border)] hover:bg-[color:var(--accent)]'"
            @click="pickFile"
            @dragenter.prevent="isDragging = true"
            @dragover.prevent="isDragging = true"
            @dragleave.prevent="isDragging = false"
            @drop.prevent="onDrop"
          >
            <FileImage class="size-5 shrink-0" />
            <span>{{ t.svgImport.svgImportPick }}</span>
            <span class="text-[color:var(--muted-foreground)]">{{ t.svgImport.svgImportDropHint }}</span>
          </button>
        </template>

        <!-- importing：加载中 -->
        <template v-else-if="phase === 'importing'">
          <div class="flex items-center gap-2 text-[color:var(--muted-foreground)]">
            <Loader2 class="size-4 animate-spin shrink-0" />
            <span>{{ t.svgImport.importing }}</span>
          </div>
        </template>

        <!-- done：导入完成，显示导入数量 -->
        <template v-else-if="phase === 'done'">
          <div
            class="flex items-center gap-2"
            :class="importCount === 0 ? 'text-[color:var(--ctp-peach)]' : 'text-[color:var(--ctp-green)]'"
          >
            <CheckCircle2 class="size-4 shrink-0" />
            <span>{{ t.svgImport.done(importCount) }}</span>
          </div>
        </template>

        <!-- error：导入失败 -->
        <template v-else-if="phase === 'error'">
          <div class="flex items-start gap-2 text-[color:var(--destructive)]">
            <AlertTriangle class="size-4 shrink-0 mt-0.5" />
            <div class="space-y-0.5">
              <div>{{ t.svgImport.failed(errorCode ?? '') }}</div>
              <div v-if="errorMessage" class="text-[color:var(--muted-foreground)]">{{ errorMessage }}</div>
            </div>
          </div>
        </template>
      </div>

      <footer class="px-4 py-3 border-t border-[color:var(--border)] flex items-center gap-2 justify-end">
        <button
          class="hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] border border-[color:var(--border)] hover:bg-[color:var(--accent)] disabled:opacity-40"
          :disabled="phase === 'importing'"
          @click="onClose"
        >{{ t.workshop.cancel }}</button>
      </footer>
    </div>
  </div>
</template>
