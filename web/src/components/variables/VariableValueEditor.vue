<script setup lang="ts">
/**
 * 变量当前值编辑器（modal）。
 *
 * <p>VariablePanel 行点 "改值" 后弹出；按 {@link Variable.type} 切换控件，
 * 保存时走 {@code wsClient.sendVariableSet}。</p>
 *
 * <p>BOOLEAN：toggle 按钮显示 true / false；
 *    COLOR：复用 ColorInput；
 *    NUMBER / STRING：type 对应的 input。</p>
 */
import { ref, onMounted } from 'vue';
import { X, Pencil } from 'lucide-vue-next';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';
import { useNetworkStore } from '@/stores/network';
import ColorInput from '@/components/ui/ColorInput.vue';
import type { Variable } from '@/types/variable';

const props = defineProps<{
    fullName: string;
    variable: Variable;
}>();
const emit = defineEmits<{ (e: 'close'): void }>();

const { t } = useI18n();
const ws = getWsClient();
const net = useNetworkStore();

const valueStr = ref<string>(props.variable.currentValue ?? '');
const valueNum = ref<number>(parseNumber(props.variable.currentValue) ?? 0);
const valueBool = ref<boolean>(props.variable.currentValue === 'true');
const valueColor = ref<string>(parseColorOrDefault(props.variable.currentValue));

const submitting = ref(false);
const submitError = ref<string | null>(null);

const inputRef = ref<HTMLInputElement | null>(null);
onMounted(() => inputRef.value?.focus());

function parseNumber(s: string | null): number | null {
    if (s === null) return null;
    const n = Number(s);
    return Number.isFinite(n) ? n : null;
}

function parseColorOrDefault(s: string | null): string {
    // 复用 ColorInput 的 #RRGGBB / #RRGGBBAA 解析逻辑；非法时退到白
    if (s && /^#([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?$/.test(s)) return s;
    return '#FFFFFF';
}

function currentValueAsString(): string {
    switch (props.variable.type) {
        case 'STRING': return valueStr.value;
        case 'NUMBER': return String(valueNum.value);
        case 'BOOLEAN': return valueBool.value ? 'true' : 'false';
        case 'COLOR': return valueColor.value;
    }
}

async function onSubmit() {
    if (submitting.value) return;
    submitting.value = true;
    submitError.value = null;
    try {
        await ws.sendVariableSet(props.fullName, currentValueAsString());
        emit('close');
    } catch (err) {
        submitError.value = (err as Error).message;
        net.pushLog('err', `variable.set rejected: ${(err as Error).message}`);
    } finally {
        submitting.value = false;
    }
}

function onCancel() {
    if (submitting.value) return;
    emit('close');
}
</script>

<template>
  <div class="fixed inset-0 z-[60] bg-[color:var(--ctp-crust)]/50 flex items-center justify-center p-4"
       @click.self="onCancel"
       @keydown.escape.prevent="onCancel"
  >
    <div class="bg-[color:var(--card)] text-[color:var(--foreground)] rounded-[var(--radius)] shadow-md w-full max-w-md flex flex-col border border-[color:var(--border)]">
      <header class="flex items-center gap-2 px-4 h-11 border-b border-[color:var(--border)]">
        <Pencil class="size-4 text-[color:var(--primary)]" />
        <h2 class="text-sm font-semibold">{{ t.variables.dialogValueTitle }}</h2>
        <button class="ml-auto p-1 rounded hover:bg-[color:var(--accent)]"
                @click="onCancel"
                :disabled="submitting">
          <X class="size-4" />
        </button>
      </header>

      <div class="p-4 space-y-2 text-xs">
        <div class="text-[color:var(--muted-foreground)] font-mono truncate" :title="fullName">{{ fullName }}</div>
        <div>
          <input
            v-if="variable.type === 'STRING'"
            ref="inputRef"
            type="text"
            class="hc-input"
            v-model="valueStr"
            maxlength="2048"
            @keydown.enter.prevent="onSubmit"
          />
          <input
            v-else-if="variable.type === 'NUMBER'"
            ref="inputRef"
            type="number"
            class="hc-input font-mono"
            v-model.number="valueNum"
            step="any"
            @keydown.enter.prevent="onSubmit"
          />
          <button
            v-else-if="variable.type === 'BOOLEAN'"
            type="button"
            class="hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
            @click="valueBool = !valueBool"
          >
            {{ valueBool ? 'true' : 'false' }}
          </button>
          <ColorInput
            v-else
            v-model="valueColor"
            :allow-alpha="true"
          />
        </div>
      </div>

      <footer class="px-4 py-3 border-t border-[color:var(--border)] flex items-center gap-2">
        <span v-if="submitError" class="text-xs text-[color:var(--destructive)] flex-1 truncate" :title="submitError">{{ submitError }}</span>
        <span v-else class="flex-1"></span>
        <button
          class="hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] border border-[color:var(--border)] hover:bg-[color:var(--accent)] disabled:opacity-40"
          @click="onCancel"
          :disabled="submitting"
        >
          {{ t.variables.dialogNewCancel }}
        </button>
        <button
          class="hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)] hover:opacity-90 disabled:opacity-40"
          @click="onSubmit"
          :disabled="submitting"
        >
          {{ submitting ? '…' : t.variables.dialogValueSubmit }}
        </button>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.hc-input {
    width: 100%;
    padding: 0.25rem 0.375rem;
    font-size: 0.75rem;
    line-height: 1rem;
    border-radius: 4px;
    background: var(--background);
    color: var(--foreground);
    border: 1px solid var(--border);
}
.hc-input:focus {
    outline: none;
    border-color: var(--ring);
    box-shadow: 0 0 0 1px var(--ring);
}
</style>
