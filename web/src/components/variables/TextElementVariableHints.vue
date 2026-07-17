<script setup lang="ts">
/**
 * Text 元素变量 hints：textarea 下方 live preview + 删除警告。
 *
 * <p>仅当 text 含 {@code ${var:} 子串时挂载（外部 v-if）；内部 200ms debounce
 * 调 {@link interpolate} 算最终文本 + missingFullNames，UI 反馈给用户。</p>
 */
import { computed, ref, watch } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { AlertCircle } from 'lucide-vue-next';
import { useVariableStore } from '@/stores/variables';
import { useI18n } from '@/i18n';
import { interpolate, type InterpolateResult } from '@/variable/interpolator';

interface Props {
    text: string;
    wallId: string | null;
}
const props = defineProps<Props>();
const { t } = useI18n();
const store = useVariableStore();

const result = ref<InterpolateResult>({
    text: props.text,
    referencedFullNames: new Set(),
    missingFullNames: new Set(),
    segments: [],
});

function recompute() {
    result.value = interpolate(props.text, props.wallId, store);
}

const recomputeDebounced = useDebounceFn(recompute, 200);

// 首次立即算一次（避免首帧空白）；后续 watch 走 debounce
recompute();

watch(
    () => [props.text, props.wallId, store.variables.size] as const,
    () => recomputeDebounced(),
);

const hasPlaceholder = computed(() => (props.text ?? '').indexOf('${var:') >= 0);
const missingList = computed(() => Array.from(result.value.missingFullNames));
const referencedList = computed(() => Array.from(result.value.referencedFullNames));
const missingMessage = computed(() =>
    t.value.variables.hints.deletedWarning.replace('{names}', missingList.value.join(', ')),
);
const referencedMessage = computed(() =>
    t.value.variables.hints.referencedHint.replace('{names}', referencedList.value.join(', ')),
);
</script>

<template>
  <div v-if="hasPlaceholder" class="hc-variable-hints">
    <!-- 删除警告（仅在 missing 非空时） -->
    <div v-if="missingList.length > 0" class="hc-warning-banner">
      <AlertCircle class="size-3 shrink-0" />
      <span>{{ missingMessage }}</span>
    </div>

    <!-- live preview -->
    <div class="hc-live-preview">
      <span class="hc-preview-label">{{ t.variables.hints.previewLabel }}:</span>
      <span class="hc-preview-text">{{ result.text }}</span>
    </div>

    <!-- 引用提示（非 missing 情况下） -->
    <div
      v-if="referencedList.length > 0 && missingList.length === 0"
      class="hc-referenced-hint"
    >
      {{ referencedMessage }}
    </div>
  </div>
</template>

<style scoped>
.hc-variable-hints {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 4px 0;
}

.hc-warning-banner {
    display: flex;
    align-items: flex-start;
    gap: 4px;
    padding: 4px 6px;
    border-radius: 4px;
    background: color-mix(in oklab, var(--destructive) 10%, var(--background));
    color: var(--destructive);
    font-size: 10px;
    line-height: 1.4;
}

.hc-live-preview {
    display: flex;
    gap: 4px;
    padding: 3px 6px;
    background: var(--background);
    border: 1px dashed var(--border);
    border-radius: 4px;
    font-size: 10px;
    line-height: 1.4;
    align-items: baseline;
}
.hc-preview-label {
    color: var(--muted-foreground);
    flex-shrink: 0;
    font-weight: 600;
}
.hc-preview-text {
    color: var(--foreground);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    word-break: break-all;
    white-space: pre-wrap;
}

.hc-referenced-hint {
    font-size: 9px;
    color: var(--muted-foreground);
    padding: 0 6px;
    font-style: italic;
}
</style>
