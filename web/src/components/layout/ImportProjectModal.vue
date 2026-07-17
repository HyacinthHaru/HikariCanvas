<script setup lang="ts">
/**
 * .canvas 工程导入对话框。
 *
 * 流程：选文件 / 拖入 → 破坏性替换二次确认 → 调 useProjectImport().importProject(file)
 *   → loading → 展示后端 warnings 清单 / 错误。
 *
 * <p>导入成功后由后端经 WS state.snapshot 推全量工程（wsClient handleSnapshot →
 * setSnapshot），本组件不手动刷新工程。</p>
 *
 * <p><b>文件选择不设 accept 扩展名过滤</b>：自定义 .canvas 在 macOS Finder 无 UTI 注册，
 * accept=".canvas" 会把它灰掉、逼用户切「所有文件」；故放行任意文件，合法性由后端导入
 * 校验兜底（坏文件 → IMPORT_MALFORMED）。入口区同时支持<b>拖拽</b>。</p>
 *
 * <p>warning 渲染经 {@link warningText} 把后端 {@code kind} 翻成服主/玩家看得懂的大白话
 * （文案在 i18n {@code project.warn}，zh/en 镜像）；未知 kind 兜底回原始 detail。</p>
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
/** 拖拽悬停高亮态。 */
const isDragging = ref(false);

function pickFile() {
    fileRef.value?.click();
}

/** 选到 / 拖到的文件统一入口：进入破坏性替换二次确认（不按扩展名预筛，见组件注释）。 */
function acceptFile(file: File | null) {
    if (!file) return;
    pendingFile.value = file;
    phase.value = 'confirm';
}

function onPick(e: Event) {
    const input = e.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    input.value = '';   // 清空，便于再次选同名文件触发 change
    acceptFile(file);
}

function onDrop(e: DragEvent) {
    isDragging.value = false;
    acceptFile(e.dataTransfer?.files?.[0] ?? null);
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

/**
 * 把一条后端 warning 翻成大白话：按 kind 查 i18n project.warn 文案函数，未命中回原始 detail。
 * 文案函数签名统一 (d:string)=>string；无参文案忽略 detail（JS 多传参无害）。
 */
function warningText(w: ImportWarningDto): string {
    const fn = t.value.project.warn[w.kind];
    return fn ? fn(w.detail) : w.detail;
}

function onClose() {
    if (phase.value === 'importing') return;   // 导入进行中不可关
    // 重置态，下次打开干净
    phase.value = 'idle';
    pendingFile.value = null;
    isDragging.value = false;
    warnings.value = [];
    errorCode.value = null;
    errorMessage.value = null;
    emit('close');
}

defineExpose({ doImport, acceptFile, warningText });
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
        <!-- 隐藏的真实文件选择 input；不设 accept（.canvas 在 macOS Finder 无 UTI 会被灰掉） -->
        <input
          ref="fileRef"
          type="file"
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
            <FileUp class="size-5 shrink-0" />
            <span>{{ t.project.importPick }}</span>
            <span class="text-[color:var(--muted-foreground)]">{{ t.project.importDropHint }}</span>
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
                <span>{{ warningText(w) }}</span>
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
