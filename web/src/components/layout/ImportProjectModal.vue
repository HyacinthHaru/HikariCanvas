<script setup lang="ts">
/**
 * 0.8 A3：.canvas 工程导入对话框。
 *
 * 流程：选文件 → 破坏性替换二次确认 → 调 useProjectImport().importProject(file)
 *   → loading → 展示后端 warnings 清单 / 错误。
 *
 * <p>导入成功后由后端经 WS state.snapshot 推全量工程（wsClient handleSnapshot →
 * setSnapshot），本组件不手动刷新工程。文件选择照 useCanvasUpload 隐藏 input 范式，
 * accept=".canvas"。</p>
 *
 * <p>warning 渲染本期直接显示原始 detail；大白话 kind→文案映射在 0.8 A4 补 warningText。</p>
 */
import { ref } from 'vue';
import { X, Upload, FileUp, AlertTriangle, CheckCircle2, Loader2 } from 'lucide-vue-next';
import { useI18n } from '@/i18n';
import { useProjectImport } from '@/composables/useProjectImport';
import type { ImportWarningDto } from '@/types/canvasFile';

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{ (e: 'close'): void }>();

const { t } = useI18n();

/** 处理阶段：idle 选文件 → confirm 二次确认 → importing → done / error。 */
type Phase = 'idle' | 'confirm' | 'importing' | 'done' | 'error';
const phase = ref<Phase>('idle');
const warnings = ref<ImportWarningDto[]>([]);
const errorCode = ref<string | null>(null);
const errorMessage = ref<string | null>(null);

const fileRef = ref<HTMLInputElement | null>(null);
/** 待确认的文件（选好但还没过二次确认）。 */
const pendingFile = ref<File | null>(null);

function pickFile() {
    fileRef.value?.click();
}

function onPick(e: Event) {
    const input = e.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    input.value = '';   // 清空，便于再次选同名文件触发 change
    if (!file) return;
    pendingFile.value = file;
    phase.value = 'confirm';   // 进入破坏性替换二次确认
}

/** 用户确认替换后才真正导入。 */
async function confirmImport() {
    const file = pendingFile.value;
    if (!file) return;
    await doImport(file);
}

function cancelConfirm() {
    pendingFile.value = null;
    phase.value = 'idle';
}

/** 实际导入处理：调 composable POST，按结果切换阶段。供测试 defineExpose。 */
async function doImport(file: File) {
    phase.value = 'importing';
    warnings.value = [];
    errorCode.value = null;
    errorMessage.value = null;
    const r = await useProjectImport().importProject(file);
    pendingFile.value = null;
    if (r.ok) {
        warnings.value = r.warnings ?? [];
        phase.value = 'done';
    } else {
        errorCode.value = r.errorCode ?? null;
        errorMessage.value = r.errorMessage ?? null;
        phase.value = 'error';
    }
}

function onClose() {
    if (phase.value === 'importing') return;   // 导入进行中不可关
    // 重置态，下次打开干净
    phase.value = 'idle';
    pendingFile.value = null;
    warnings.value = [];
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
        <Upload class="size-4 text-[color:var(--ctp-blue)]" />
        <h2 class="text-sm font-semibold">{{ t.project.importTitle }}</h2>
        <button
          class="ml-auto p-1 rounded hover:bg-[color:var(--accent)] disabled:opacity-40"
          :disabled="phase === 'importing'"
          @click="onClose"
        >
          <X class="size-4" />
        </button>
      </header>

      <div class="flex-1 overflow-y-auto p-4 space-y-4 text-xs">
        <!-- 隐藏的真实文件选择 input；accept=".canvas" -->
        <input
          ref="fileRef"
          type="file"
          accept=".canvas"
          class="hidden"
          @change="onPick"
        />

        <!-- idle：选文件入口 -->
        <template v-if="phase === 'idle'">
          <button
            class="hc-btn w-full flex items-center justify-center gap-2 px-3 py-3 rounded-[var(--radius-sm)] border border-dashed border-[color:var(--border)] hover:bg-[color:var(--accent)] transition-colors"
            @click="pickFile"
          >
            <FileUp class="size-4 shrink-0" />
            <span>{{ t.project.importPick }}</span>
          </button>
        </template>

        <!-- confirm：破坏性替换二次确认 -->
        <template v-else-if="phase === 'confirm'">
          <div class="flex items-start gap-2 p-3 rounded-[var(--radius-sm)] bg-[color:var(--ctp-peach)]/10 text-[color:var(--ctp-peach)]">
            <AlertTriangle class="size-4 shrink-0 mt-0.5" />
            <span class="text-[color:var(--foreground)]">{{ t.project.importConfirmReplace }}</span>
          </div>
        </template>

        <!-- importing：加载中 -->
        <template v-else-if="phase === 'importing'">
          <div class="flex items-center gap-2 text-[color:var(--muted-foreground)]">
            <Loader2 class="size-4 animate-spin shrink-0" />
            <span>{{ t.project.importing }}</span>
          </div>
        </template>

        <!-- done：导入完成 + warnings 清单 -->
        <template v-else-if="phase === 'done'">
          <div class="flex items-center gap-2 text-[color:var(--ctp-green)]">
            <CheckCircle2 class="size-4 shrink-0" />
            <span>{{ t.project.importDone }}</span>
          </div>
          <section v-if="warnings.length" class="space-y-1.5">
            <div class="font-medium text-[color:var(--muted-foreground)]">{{ t.project.warnTitle }}</div>
            <ul class="space-y-1">
              <li
                v-for="(w, i) in warnings"
                :key="i"
                class="flex items-start gap-1.5 text-[color:var(--foreground)]"
              >
                <AlertTriangle class="size-3.5 shrink-0 mt-0.5 text-[color:var(--ctp-peach)]" />
                <span>{{ w.detail }}</span>
              </li>
            </ul>
          </section>
        </template>

        <!-- error：导入失败 -->
        <template v-else-if="phase === 'error'">
          <div class="flex items-start gap-2 text-[color:var(--destructive)]">
            <AlertTriangle class="size-4 shrink-0 mt-0.5" />
            <div class="space-y-0.5">
              <div>{{ t.project.importFailed(errorCode ?? '') }}</div>
              <div v-if="errorMessage" class="text-[color:var(--muted-foreground)]">{{ errorMessage }}</div>
            </div>
          </div>
        </template>
      </div>

      <footer class="px-4 py-3 border-t border-[color:var(--border)] flex items-center gap-2 justify-end">
        <!-- confirm 阶段：取消 / 确认替换 -->
        <template v-if="phase === 'confirm'">
          <button
            class="hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
            @click="cancelConfirm"
          >{{ t.workshop.cancel }}</button>
          <button
            class="hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)] hover:opacity-90"
            @click="confirmImport"
          >{{ t.project.importTitle }}</button>
        </template>
        <!-- 其它阶段：关闭 -->
        <template v-else>
          <button
            class="hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] border border-[color:var(--border)] hover:bg-[color:var(--accent)] disabled:opacity-40"
            :disabled="phase === 'importing'"
            @click="onClose"
          >{{ t.workshop.cancel }}</button>
        </template>
      </footer>
    </div>
  </div>
</template>
