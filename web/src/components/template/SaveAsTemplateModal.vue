<script setup lang="ts">
/**
 * M14 创意工坊：把当前画布保存为模板的 modal。
 *
 * - 自动扫描所有 TextElement，按 z-order 编号 text_1 / text_2 / ...
 * - 用户可对每个 auto param 选 keep（参数化）或 drop（保留静态文本）
 * - keep 时可重命名 + 改 label + 加 description
 * - 输入 slug / displayName / description，点击 Save 走 WS op template.save
 */
import { computed, reactive, ref, watch } from 'vue';
import { X, Bookmark, Sparkles } from 'lucide-vue-next';
import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import { useUiStore } from '@/stores/ui';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';
import type { TextElement } from '@/types/protocol';

const ui = useUiStore();
const project = useProjectStore();
const net = useNetworkStore();
const ws = getWsClient();
const { t } = useI18n();

const emit = defineEmits<{ (e: 'close'): void }>();

interface ParamRow {
    autoId: string;            // text_1 / text_2 / ...
    originalText: string;
    keep: boolean;
    name: string;
    label: string;
    description: string;
}

const slug = ref('');
const displayName = ref('');
const description = ref('');
const slugError = ref<string | null>(null);
const submitting = ref(false);
const submitError = ref<string | null>(null);
const paramRows = reactive<ParamRow[]>([]);

function refreshParams() {
    paramRows.length = 0;
    const layers = project.state?.layers ?? [];
    let n = 1;
    for (const layer of layers) {
        for (const el of layer.elements) {
            if (el.type === 'text') {
                const te = el as TextElement;
                const autoId = `text_${n++}`;
                paramRows.push({
                    autoId,
                    originalText: te.text,
                    keep: true,
                    name: autoId,
                    label: te.text.length > 16 ? te.text.slice(0, 16) + '…' : te.text,
                    description: '',
                });
            }
        }
    }
}

refreshParams();
watch(() => project.state?.layers, refreshParams, { deep: false });

const slugValid = computed(() => /^[a-z0-9][a-z0-9-]{1,31}$/.test(slug.value));
const formValid = computed(() => slugValid.value && displayName.value.trim().length > 0);

function validateSlugInput() {
    if (!slug.value) { slugError.value = null; return; }
    slugError.value = slugValid.value ? null : t.value.workshop.slugPatternHint;
}

async function onSave() {
    if (!formValid.value || submitting.value) return;
    if (!net.sessionId) {
        submitError.value = t.value.workshop.noSession;
        return;
    }
    const textActions: Record<string, { action: string; name?: string; label?: string; description?: string }> = {};
    for (const row of paramRows) {
        textActions[row.autoId] = row.keep
            ? { action: 'keep', name: row.name, label: row.label, description: row.description }
            : { action: 'drop' };
    }
    submitting.value = true;
    submitError.value = null;
    try {
        const ack = await ws.sendWithAck('template.save', {
            slug: slug.value,
            displayName: displayName.value,
            description: description.value || null,
            paramConfig: { textActions },
        }, 8000);
        // 2026-05-21 bugfix: wsClient.handleAck 直接 resolve `envelope.payload`（不是整个 envelope）。
        // 之前 `(ack as ...).payload?.templateId` 多剥一层永远是 undefined → 走 else 显"发布失败"，
        // 即使 server 实际 publish 完全成功（log 含 "Templates reloaded: user=N"）。
        const ackPayload = ack as { templateId?: string } | undefined;
        if (ackPayload?.templateId) {
            emit('close');
        } else {
            submitError.value = t.value.workshop.saveFailedGeneric;
        }
    } catch (e) {
        submitError.value = (e as { message?: string }).message || t.value.workshop.saveFailedGeneric;
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
  <div class="fixed inset-0 z-50 bg-[color:var(--ctp-crust)]/50 flex items-center justify-center p-4"
       @click.self="onClose">
    <div class="bg-[color:var(--card)] text-[color:var(--foreground)] rounded-[var(--radius)] shadow-md max-w-2xl w-full max-h-[90vh] flex flex-col border border-[color:var(--border)]">
      <header class="flex items-center gap-2 px-4 h-11 border-b border-[color:var(--border)]">
        <Bookmark class="size-4 text-[color:var(--ctp-peach)]" />
        <h2 class="text-sm font-semibold">{{ t.workshop.saveTitle }}</h2>
        <button class="ml-auto p-1 rounded hover:bg-[color:var(--accent)]"
                @click="onClose" :disabled="submitting">
          <X class="size-4" />
        </button>
      </header>

      <div class="flex-1 overflow-y-auto p-4 space-y-4 text-xs">
        <!-- 基本字段 -->
        <div class="space-y-2">
          <label class="block">
            <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.workshop.slugLabel }}</span>
            <input type="text"
                   class="hc-input mt-0.5 font-mono"
                   :placeholder="t.workshop.slugPlaceholder"
                   v-model="slug"
                   @blur="validateSlugInput"
                   maxlength="32" />
            <span v-if="slugError" class="text-xs text-[color:var(--destructive)]">{{ slugError }}</span>
            <span v-else class="text-xs text-[color:var(--muted-foreground)]">{{ t.workshop.slugHint }}</span>
          </label>

          <label class="block">
            <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.workshop.displayNameLabel }}</span>
            <input type="text"
                   class="hc-input mt-0.5"
                   v-model="displayName"
                   maxlength="64" />
          </label>

          <label class="block">
            <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.workshop.descriptionLabel }}</span>
            <textarea class="hc-input mt-0.5 min-h-[3em]"
                      v-model="description"
                      :placeholder="t.workshop.descriptionPlaceholder"
                      maxlength="256"></textarea>
          </label>
        </div>

        <!-- 参数列表 -->
        <section class="space-y-2">
          <div class="flex items-center gap-1.5">
            <Sparkles class="size-3.5 text-[color:var(--ctp-blue)]" />
            <span class="font-medium">{{ t.workshop.paramsHeader }}</span>
            <span class="text-xs text-[color:var(--muted-foreground)]">({{ paramRows.length }})</span>
          </div>
          <p class="text-xs text-[color:var(--muted-foreground)]">{{ t.workshop.paramsHint }}</p>

          <div v-if="paramRows.length === 0"
               class="p-3 rounded bg-[color:var(--muted)] text-[color:var(--muted-foreground)]">
            {{ t.workshop.noTextElements }}
          </div>

          <ul v-else class="space-y-2">
            <li v-for="row in paramRows" :key="row.autoId"
                class="border border-[color:var(--border)] rounded p-2 space-y-1.5">
              <div class="flex items-center gap-2">
                <span class="font-mono text-xs text-[color:var(--muted-foreground)]">{{ row.autoId }}</span>
                <span class="flex-1 truncate text-[color:var(--foreground)] italic">"{{ row.originalText }}"</span>
                <label class="flex items-center gap-1 cursor-pointer">
                  <input type="checkbox" v-model="row.keep" />
                  <span class="text-xs">{{ row.keep ? t.workshop.keep : t.workshop.drop }}</span>
                </label>
              </div>
              <div v-if="row.keep" class="grid grid-cols-2 gap-2 pl-3">
                <label class="block">
                  <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.workshop.paramNameLabel }}</span>
                  <input type="text"
                         class="hc-input mt-0.5 font-mono text-xs"
                         v-model="row.name"
                         maxlength="32"
                         pattern="^[a-z][a-z0-9_]{0,31}$" />
                </label>
                <label class="block">
                  <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.workshop.paramLabelLabel }}</span>
                  <input type="text"
                         class="hc-input mt-0.5 text-xs"
                         v-model="row.label"
                         maxlength="64" />
                </label>
                <label class="col-span-2 block">
                  <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.workshop.paramDescLabel }}</span>
                  <input type="text"
                         class="hc-input mt-0.5 text-xs"
                         v-model="row.description"
                         maxlength="128" />
                </label>
              </div>
            </li>
          </ul>
        </section>
      </div>

      <footer class="px-4 py-3 border-t border-[color:var(--border)] flex items-center gap-2">
        <span v-if="submitError" class="text-xs text-[color:var(--destructive)] flex-1">{{ submitError }}</span>
        <span v-else class="flex-1"></span>
        <button class="hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] border border-[color:var(--border)] hover:bg-[color:var(--accent)] disabled:opacity-40"
                @click="onClose" :disabled="submitting">{{ t.workshop.cancel }}</button>
        <button class="hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)] hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed"
                @click="onSave"
                :disabled="!formValid || submitting">
          <span v-if="submitting">{{ t.workshop.saving }}</span>
          <span v-else>{{ t.workshop.save }}</span>
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
