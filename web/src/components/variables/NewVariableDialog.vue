<script setup lang="ts">
/**
 * 0.4.0-P2-G：新建变量对话框。
 *
 * <p>用户在 VariablePanel 点 "+ 新建变量" 触发；填名称 + 类型 + 默认值后走
 * {@code wsClient.sendVariableCreate(name, type, defaultValue)}。后端自动加
 * {@code user:<wallId>/} 前缀，不需要前端拼。</p>
 *
 * <p>校验：</p>
 * <ul>
 *   <li>name 非空</li>
 *   <li>name regex {@code ^[a-zA-Z0-9_.-]+$}</li>
 *   <li>name 长度 ≤ 64</li>
 * </ul>
 * 违规时 [创建] disabled + 输入框红边 + 提示文案。
 *
 * <p>错误处理：服务端拒绝（{@code VARIABLE_EXISTS} 等）通过 sendWithAck reject 抛错，
 * 捕获后显示在 footer，不立即关弹窗（保留用户输入便于改名重试）。</p>
 */
import { computed, ref, watchEffect, onMounted } from 'vue';
import { X, Plus } from 'lucide-vue-next';
import { getWsClient } from '@/network/wsClient';
import { useI18n } from '@/i18n';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import ColorInput from '@/components/ui/ColorInput.vue';
import type { VarType } from '@/types/variable';
import { makeUserFullName, makeUserGlobalFullName } from '@/types/variable';

/** 0.4.3：变量作用域。'wall' = per-wall user 变量（默认）；'global' = userglobal 全服共享。 */
type Scope = 'wall' | 'global';

const emit = defineEmits<{ (e: 'close'): void; (e: 'created', fullName: string): void }>();

const { t } = useI18n();
const ws = getWsClient();
const net = useNetworkStore();
const project = useProjectStore();

/** 与 P1-A `Variable.NAME_REGEX`（{@code ^[a-zA-Z0-9_.-]+$}）一致；后端会再校一遍。 */
const NAME_REGEX = /^[a-zA-Z0-9_.-]+$/;
const MAX_NAME_LEN = 64;

const name = ref('');
const scope = ref<Scope>('wall');  // 0.4.3：默认 wall（与 0.4.0 行为一致）
const type = ref<VarType>('STRING');
const defaultValueStr = ref('');           // STRING 用
const defaultValueNum = ref<number>(0);    // NUMBER 用
const defaultValueBool = ref<boolean>(false); // BOOLEAN 用
const defaultValueColor = ref('#FFFFFF');  // COLOR 用
// 0.4.2：可选 alias 字段。创建成功后两步：先 variable.create 拿 fullName，再 variable.alias.set
const aliasDraft = ref('');
const ALIAS_MAX_LEN = 64;

const submitting = ref(false);
const submitError = ref<string | null>(null);

const nameInputRef = ref<HTMLInputElement | null>(null);
onMounted(() => {
    nameInputRef.value?.focus();
});

/** 名称校验：返 i18n key 或 null。empty 不显示 error（避免初次打开就刷红）。 */
const nameValidation = computed<{ valid: boolean; errorKey: 'empty' | 'invalid' | 'tooLong' | null }>(() => {
    const v = name.value;
    if (v.length === 0) return { valid: false, errorKey: 'empty' };
    if (v.length > MAX_NAME_LEN) return { valid: false, errorKey: 'tooLong' };
    if (!NAME_REGEX.test(v)) return { valid: false, errorKey: 'invalid' };
    return { valid: true, errorKey: null };
});

/** 是否显示 error message（empty 只有 user 输入过才提示）。 */
const showNameError = computed(() => {
    return name.value.length > 0 && !nameValidation.value.valid;
});

/** 别名校验：仅长度限制（≤64）；空串合法（表示不设别名）。 */
const aliasValidation = computed<{ valid: boolean; errorKey: 'tooLong' | null }>(() => {
    const v = aliasDraft.value.trim();
    if (v.length === 0) return { valid: true, errorKey: null };
    if (v.length > ALIAS_MAX_LEN) return { valid: false, errorKey: 'tooLong' };
    return { valid: true, errorKey: null };
});
const showAliasError = computed(() => aliasDraft.value.length > 0 && !aliasValidation.value.valid);

const formValid = computed(() => nameValidation.value.valid && aliasValidation.value.valid && !submitting.value);

/** 当前 type 对应的默认值字符串（送 wire 用）。BOOLEAN 走 "true"/"false"；COLOR 走 hex；NUMBER 走 String(num)。 */
function currentDefaultValueAsString(): string {
    switch (type.value) {
        case 'STRING': return defaultValueStr.value;
        case 'NUMBER': return String(defaultValueNum.value);
        case 'BOOLEAN': return defaultValueBool.value ? 'true' : 'false';
        case 'COLOR': return defaultValueColor.value;
    }
}

async function onSubmit() {
    if (!formValid.value) return;
    submitting.value = true;
    submitError.value = null;
    const fullDefault = currentDefaultValueAsString();
    const aliasTrimmed = aliasDraft.value.trim();
    try {
        // 0.4.3：scope='global' 走 sendVariableCreate 第 4 参，后端 EditSession 路由到 createGlobalVariable
        await ws.sendVariableCreate(name.value, type.value, fullDefault, scope.value);
        // 0.4.2：若 alias 字段非空，create 成功后再发 variable.alias.set。
        // user 变量 fullName = user:<wallId>/<name>；userglobal 变量 fullName = userglobal/<name>
        if (aliasTrimmed.length > 0) {
            let fullName: string | null = null;
            if (scope.value === 'global') {
                fullName = makeUserGlobalFullName(name.value);
            } else {
                const wallId = project.wallId;
                if (wallId) fullName = makeUserFullName(wallId, name.value);
            }
            if (fullName) {
                try {
                    await ws.sendVariableAliasSet(fullName, aliasTrimmed);
                } catch (aliasErr) {
                    // 别名失败不阻塞 create 成功 emit；只记 log
                    net.pushLog('err', `variable.alias.set rejected: ${(aliasErr as Error).message}`);
                }
            }
        }
        // 创建成功；server 会通过 state.patch 推 add /variables/...，VariablePanel 自动反映
        emit('created', name.value);
        emit('close');
    } catch (err) {
        submitError.value = (err as Error).message;
        net.pushLog('err', `variable.create rejected: ${(err as Error).message}`);
    } finally {
        submitting.value = false;
    }
}

function onCancel() {
    if (submitting.value) return;
    emit('close');
}

/** 类型切换时同时重置默认值控件（避免残留旧值）。 */
watchEffect(() => {
    // 仅监听 type；避免无限递归把别的 ref 拉进 dep
    const _ = type.value;
    void _;
});

const TYPE_OPTIONS: { value: VarType; labelKey: 'typeString' | 'typeNumber' | 'typeBoolean' | 'typeColor' }[] = [
    { value: 'STRING', labelKey: 'typeString' },
    { value: 'NUMBER', labelKey: 'typeNumber' },
    { value: 'BOOLEAN', labelKey: 'typeBoolean' },
    { value: 'COLOR', labelKey: 'typeColor' },
];
</script>

<template>
  <div class="fixed inset-0 z-[60] bg-[color:var(--ctp-crust)]/50 flex items-center justify-center p-4"
       @click.self="onCancel"
       @keydown.escape.prevent="onCancel"
  >
    <div class="bg-[color:var(--card)] text-[color:var(--foreground)] rounded-[var(--radius)] shadow-md w-full max-w-md flex flex-col border border-[color:var(--border)]">
      <header class="flex items-center gap-2 px-4 h-11 border-b border-[color:var(--border)]">
        <Plus class="size-4 text-[color:var(--primary)]" />
        <h2 class="text-sm font-semibold">{{ t.variables.dialogNewTitle }}</h2>
        <button class="ml-auto p-1 rounded hover:bg-[color:var(--accent)]"
                @click="onCancel"
                :disabled="submitting">
          <X class="size-4" />
        </button>
      </header>

      <div class="p-4 space-y-3 text-xs">
        <!-- 名称 -->
        <label class="block">
          <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.variables.dialogNewNameLabel }}</span>
          <input
            ref="nameInputRef"
            type="text"
            class="hc-input mt-0.5 font-mono"
            :class="showNameError ? 'hc-input-error' : ''"
            :placeholder="t.variables.dialogNewNamePlaceholder"
            v-model="name"
            :maxlength="MAX_NAME_LEN + 8"
            @keydown.enter.prevent="onSubmit"
          />
          <span
            v-if="showNameError"
            class="text-xs text-[color:var(--destructive)]"
          >
            <template v-if="nameValidation.errorKey === 'tooLong'">{{ t.variables.nameErrorTooLong }}</template>
            <template v-else-if="nameValidation.errorKey === 'invalid'">{{ t.variables.nameErrorInvalid }}</template>
            <template v-else>{{ t.variables.nameErrorEmpty }}</template>
          </span>
          <span v-else class="text-xs text-[color:var(--muted-foreground)]">{{ t.variables.dialogNewNameHint }}</span>
        </label>

        <!-- 0.4.3：scope toggle（作用域）：本 wall vs 全局 -->
        <div class="block">
          <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.variables.dialogNewScopeLabel }}</span>
          <div class="mt-1 flex gap-1">
            <button
              type="button"
              class="hc-btn flex-1 px-2 py-1 text-xs rounded-[var(--radius-sm)] border transition-colors"
              :class="scope === 'wall'
                ? 'border-[color:var(--primary)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)]'
                : 'border-[color:var(--border)] hover:bg-[color:var(--accent)]'"
              @click="scope = 'wall'"
            >
              {{ t.variables.dialogNewScopeWall }}
            </button>
            <button
              type="button"
              class="hc-btn flex-1 px-2 py-1 text-xs rounded-[var(--radius-sm)] border transition-colors"
              :class="scope === 'global'
                ? 'border-[color:var(--primary)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)]'
                : 'border-[color:var(--border)] hover:bg-[color:var(--accent)]'"
              @click="scope = 'global'"
            >
              {{ t.variables.dialogNewScopeGlobal }}
            </button>
          </div>
          <span class="text-xs text-[color:var(--muted-foreground)] block mt-1">
            <template v-if="scope === 'wall'">{{ t.variables.dialogNewScopeWallHint }}</template>
            <template v-else>{{ t.variables.dialogNewScopeGlobalHint }}</template>
          </span>
        </div>

        <!-- 类型 button group -->
        <div class="block">
          <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.variables.dialogNewTypeLabel }}</span>
          <div class="mt-1 flex gap-1">
            <button
              v-for="opt in TYPE_OPTIONS"
              :key="opt.value"
              type="button"
              class="hc-btn flex-1 px-2 py-1 text-xs rounded-[var(--radius-sm)] border transition-colors"
              :class="type === opt.value
                ? 'border-[color:var(--primary)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)]'
                : 'border-[color:var(--border)] hover:bg-[color:var(--accent)]'"
              @click="type = opt.value"
            >
              {{ t.variables[opt.labelKey] }}
            </button>
          </div>
        </div>

        <!-- 默认值（按 type 切控件） -->
        <div class="block">
          <span class="text-xs text-[color:var(--muted-foreground)]">{{ t.variables.dialogNewDefaultLabel }}</span>
          <div class="mt-1">
            <input
              v-if="type === 'STRING'"
              type="text"
              class="hc-input"
              v-model="defaultValueStr"
              maxlength="256"
            />
            <input
              v-else-if="type === 'NUMBER'"
              type="number"
              class="hc-input font-mono"
              v-model.number="defaultValueNum"
              step="any"
            />
            <button
              v-else-if="type === 'BOOLEAN'"
              type="button"
              class="hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
              @click="defaultValueBool = !defaultValueBool"
            >
              {{ defaultValueBool ? 'true' : 'false' }}
            </button>
            <div v-else>
              <ColorInput v-model="defaultValueColor" :allow-alpha="false" />
            </div>
          </div>
        </div>

        <!-- 0.4.2：可选 alias 字段 -->
        <label class="block">
          <span class="text-xs text-[color:var(--muted-foreground)]">
            {{ t.variables.dialogNewAliasLabel }}
            <span class="opacity-60">（{{ t.variables.optional }}）</span>
          </span>
          <input
            type="text"
            class="hc-input mt-0.5"
            :class="showAliasError ? 'hc-input-error' : ''"
            :placeholder="t.variables.dialogNewAliasPlaceholder"
            v-model="aliasDraft"
            :maxlength="ALIAS_MAX_LEN + 8"
          />
          <span
            v-if="showAliasError"
            class="text-xs text-[color:var(--destructive)]"
          >
            {{ t.variables.aliasErrorTooLong }}
          </span>
          <span v-else class="text-xs text-[color:var(--muted-foreground)]">{{ t.variables.dialogNewAliasHint }}</span>
        </label>
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
          class="hc-btn px-3 py-1.5 text-xs rounded-[var(--radius-sm)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)] hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed"
          @click="onSubmit"
          :disabled="!formValid"
        >
          {{ submitting ? '…' : t.variables.dialogNewSubmit }}
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
.hc-input-error {
    border-color: var(--destructive);
}
.hc-input-error:focus {
    border-color: var(--destructive);
    box-shadow: 0 0 0 1px var(--destructive);
}
</style>
