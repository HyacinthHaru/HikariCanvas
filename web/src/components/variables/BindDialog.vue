<script setup lang="ts">
/**
 * 让插件接管 / 取消绑定对话框（简化版）。
 *
 * <p>Plugin Push API 落地前，没有可绑定的插件可枚举。当前简化版：</p>
 * <ul>
 *   <li>未绑：显示 empty state 提示 "插件推送 API 启用后此处显示插件列表"；</li>
 *   <li>已绑（即 {@link Variable.source} 非 manual / null）：显示当前绑定 + "取消绑定" 按钮。</li>
 * </ul>
 * 取消绑定走 {@code wsClient.sendVariableBind(fullName, null)}。
 *
 * <p>后续整体替换为插件列表 picker。</p>
 */
import { computed, ref } from 'vue';
import { X, Link2 } from 'lucide-vue-next';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';
import { useNetworkStore } from '@/stores/network';
import type { Variable } from '@/types/variable';

const props = defineProps<{
    fullName: string;
    variable: Variable;
}>();
const emit = defineEmits<{ (e: 'close'): void }>();

const { t } = useI18n();
const ws = getWsClient();
const net = useNetworkStore();

const submitting = ref(false);
const submitError = ref<string | null>(null);

/**
 * 是否当前已绑插件。判定：source 既非空 / 也非 "manual" / "system" / "papi" 时认为是插件绑定。
 * 后续改读专用的 boundTo 字段（后端已留接口位）。
 */
const boundPlugin = computed<string | null>(() => {
    const s = props.variable.source;
    if (!s) return null;
    if (s === 'manual' || s === 'system' || s === 'papi') return null;
    return s;
});

async function onUnbind() {
    if (submitting.value) return;
    submitting.value = true;
    submitError.value = null;
    try {
        await ws.sendVariableBind(props.fullName, null);
        emit('close');
    } catch (err) {
        submitError.value = (err as Error).message;
        net.pushLog('err', `variable.bind null rejected: ${(err as Error).message}`);
    } finally {
        submitting.value = false;
    }
}

function onClose() {
    if (submitting.value) return;
    emit('close');
}
</script>

<template>
  <div class="fixed inset-0 z-[60] bg-[color:var(--ctp-crust)]/50 flex items-center justify-center p-4"
       @click.self="onClose"
       @keydown.escape.prevent="onClose"
  >
    <div class="bg-[color:var(--card)] text-[color:var(--foreground)] rounded-[var(--radius)] shadow-md w-full max-w-md flex flex-col border border-[color:var(--border)]">
      <header class="flex items-center gap-2 px-4 h-11 border-b border-[color:var(--border)]">
        <Link2 class="size-4 text-[color:var(--primary)]" />
        <h2 class="text-sm font-semibold">{{ t.variables.dialogBindTitle(fullName) }}</h2>
        <button class="ml-auto p-1 rounded hover:bg-[color:var(--accent)]" @click="onClose" :disabled="submitting">
          <X class="size-4" />
        </button>
      </header>

      <div class="p-4 space-y-3 text-xs">
        <!-- 已绑（罕见但要支持） -->
        <div
          v-if="boundPlugin"
          class="p-3 rounded-[var(--radius-sm)] bg-[color:var(--ctp-green)]/10 border border-[color:var(--ctp-green)]/30"
        >
          <div class="font-medium text-[color:var(--ctp-green)]">
            {{ t.variables.bindCurrent(boundPlugin) }}
          </div>
          <button
            class="mt-2 hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] border border-[color:var(--destructive)]/40 text-[color:var(--destructive)] hover:bg-[color:var(--destructive)]/10 disabled:opacity-40"
            @click="onUnbind"
            :disabled="submitting"
          >
            {{ t.variables.bindUnbindButton }}
          </button>
        </div>

        <!-- 未绑：empty state -->
        <div
          v-else
          class="p-4 rounded-[var(--radius-sm)] bg-[color:var(--muted)] text-[color:var(--muted-foreground)] text-center"
        >
          <div class="font-medium">{{ t.variables.bindEmpty }}</div>
          <div class="mt-2 text-xs opacity-80 leading-snug">{{ t.variables.bindHint }}</div>
        </div>

        <div v-if="submitError" class="text-xs text-[color:var(--destructive)]">{{ submitError }}</div>
      </div>

      <footer class="px-4 py-3 border-t border-[color:var(--border)] flex justify-end gap-2">
        <button
          class="hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
          @click="onClose"
          :disabled="submitting"
        >
          {{ t.variables.bindCloseButton }}
        </button>
      </footer>
    </div>
  </div>
</template>
