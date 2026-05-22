<script setup lang="ts">
/**
 * 0.4.0-P2-G：变量管理主面板。右侧 fixed drawer，380px 宽。
 *
 * <p>来源契约：{@code docs/dynamic-data.md §6.1}。展示 4 个分组：</p>
 * <ul>
 *   <li>"我的变量"：当前 wall 的 {@code user:<wallId>/*}</li>
 *   <li>"由插件提供"：P3+P4 启用后插件变量；P2 empty state</li>
 *   <li>"系统（自动）"：P3 启用 System Provider 后；P2 empty state</li>
 *   <li>"PAPI"：装 PAPI 后；P2 empty state</li>
 * </ul>
 *
 * <p>关闭路径：</p>
 * <ul>
 *   <li>顶部 X 按钮 → {@code ui.closeVariablePanel()}</li>
 *   <li>ESC keydown</li>
 *   <li>onClickOutside 整个 panel</li>
 * </ul>
 *
 * <p>所有写操作（创建 / 改值 / 删除 / 绑定）走 {@code wsClient.sendVariable*}；
 * server 通过 state.patch 推变更，本组件被动消费 {@link useVariableStore}。</p>
 */
import { computed, ref } from 'vue';
import { onClickOutside, useEventListener } from '@vueuse/core';
import {
    X, Search, Plus, Users, Package, Globe, Plug,
    Minus, Pencil, Trash2, Link2, ChevronDown, ChevronRight,
    Tag, Check, Eraser,
} from 'lucide-vue-next';

import { useUiStore } from '@/stores/ui';
import { useProjectStore } from '@/stores/project';
import { useVariableStore } from '@/stores/variables';
import { useVariableAliasStore } from '@/stores/variableAliases';
import { useNetworkStore } from '@/stores/network';
import { useI18n } from '@/i18n';
import { getWsClient } from '@/network/wsClient';
import type { Variable, VarType } from '@/types/variable';
import { isUserNamespace, isUserGlobalNamespace } from '@/types/variable';
import NewVariableDialog from './NewVariableDialog.vue';
import VariableValueEditor from './VariableValueEditor.vue';
import BindDialog from './BindDialog.vue';
import NumberStepButton from './NumberStepButton.vue';

const ui = useUiStore();
const project = useProjectStore();
const store = useVariableStore();
const aliasStore = useVariableAliasStore();
const net = useNetworkStore();
const { t } = useI18n();
const ws = getWsClient();

const rootRef = ref<HTMLElement | null>(null);
const searchKeyword = ref('');

// 分组折叠态（默认全展开）
// 0.4.3：新增 global 分组（userglobal/*）
const collapsed = ref<Record<'mine' | 'global' | 'plugin' | 'system' | 'papi', boolean>>({
    mine: false,
    global: false,
    plugin: false,
    system: false,
    papi: false,
});

// 弹窗状态
const showNewDialog = ref(false);
const showValueEditor = ref<{ fullName: string; variable: Variable } | null>(null);
const showBindDialog = ref<{ fullName: string; variable: Variable } | null>(null);
/** 当前展开 "删除确认" popover 的 fullName；null = 没在确认。 */
const confirmingDeleteFor = ref<string | null>(null);
/** 0.4.2：当前 inline 改别名的 fullName；null = 没在改。 */
const editingAliasFor = ref<string | null>(null);
const aliasDraft = ref('');
const aliasError = ref<string | null>(null);
const aliasSubmitting = ref(false);
const ALIAS_MAX_LEN = 64;

// ESC + onClickOutside 关闭
useEventListener(window, 'keydown', (e: KeyboardEvent) => {
    if (!ui.variablePanelOpen) return;
    if (e.key !== 'Escape') return;
    // 任意 modal 打开时不关 panel（先关 modal）
    if (showNewDialog.value || showValueEditor.value || showBindDialog.value) return;
    // 焦点在 input 也不关（用户可能在搜索框 ESC 清搜索；这里简单全关）
    ui.closeVariablePanel();
});
onClickOutside(rootRef, () => {
    if (!ui.variablePanelOpen) return;
    // modal 打开时不应误关 panel；modal 自己用更高 z-index + 自己的 onClickOutside
    if (showNewDialog.value || showValueEditor.value || showBindDialog.value) return;
    ui.closeVariablePanel();
});

/** 我的变量 = user:<wallId>/*；store.all 已是 reactive array。 */
const myVariables = computed<Array<{ fullName: string; v: Variable }>>(() => {
    const wallId = project.wallId;
    if (!wallId) return [];
    const userPrefix = `user:${wallId}`;
    const kw = searchKeyword.value.trim().toLowerCase();
    const result: Array<{ fullName: string; v: Variable }> = [];
    for (const v of store.all) {
        if (!isUserNamespace(v.namespace)) continue;
        if (v.namespace !== userPrefix) continue;
        const fullName = `${v.namespace}/${v.key}`;
        if (kw && !matchesKeyword(fullName, v, kw)) continue;
        result.push({ fullName, v });
    }
    // 按 key 字母序排序，稳定
    result.sort((a, b) => a.v.key.localeCompare(b.v.key));
    return result;
});

function matchesKeyword(fullName: string, v: Variable, kw: string): boolean {
    if (fullName.toLowerCase().includes(kw)) return true;
    if (v.namespace.toLowerCase().includes(kw)) return true;
    if (v.key.toLowerCase().includes(kw)) return true;
    // 0.4.2：alias 也参与命中
    const alias = aliasStore.get(fullName);
    if (alias && alias.toLowerCase().includes(kw)) return true;
    return false;
}

function toggleGroup(g: 'mine' | 'global' | 'plugin' | 'system' | 'papi') {
    collapsed.value = { ...collapsed.value, [g]: !collapsed.value[g] };
}

/**
 * 0.4.3：全局用户变量列表（{@code userglobal/*}），含 owner 区分。
 * 行字段同 myVariables，加 {@code isMine}（owner = self） + {@code ownerName}（展示用）。
 */
const globalVariables = computed<Array<{
    fullName: string;
    v: Variable;
    isMine: boolean;
    ownerName: string | null;
}>>(() => {
    const selfUuid = project.selfUuid;
    const kw = searchKeyword.value.trim().toLowerCase();
    const result: Array<{ fullName: string; v: Variable; isMine: boolean; ownerName: string | null }> = [];
    for (const v of store.all) {
        if (!isUserGlobalNamespace(v.namespace)) continue;
        const fullName = `${v.namespace}/${v.key}`;
        if (kw && !matchesKeyword(fullName, v, kw)) continue;
        const isMine = !!v.ownerUuid && !!selfUuid && v.ownerUuid === selfUuid;
        result.push({ fullName, v, isMine, ownerName: v.ownerName ?? null });
    }
    // 我的全局排前；同组内字母序
    result.sort((a, b) => {
        if (a.isMine !== b.isMine) return a.isMine ? -1 : 1;
        return a.v.key.localeCompare(b.v.key);
    });
    return result;
});

// ---------- 行操作 ----------

async function incVariable(fullName: string, v: Variable, delta: number) {
    // number 类才允许；UI 已通过 v-if 隔离 +/-，这里二次防御
    if (v.type !== 'NUMBER') return;
    const cur = Number(v.currentValue ?? v.defaultValue ?? 0);
    const next = (Number.isFinite(cur) ? cur : 0) + delta;
    try {
        await ws.sendVariableSet(fullName, String(next));
        // server 会通过 state.patch 推回，store 自动更新
    } catch (err) {
        net.pushLog('err', `variable.set inc rejected: ${(err as Error).message}`);
    }
}

function openValueEditor(fullName: string, v: Variable) {
    showValueEditor.value = { fullName, variable: v };
}

function openBindDialog(fullName: string, v: Variable) {
    showBindDialog.value = { fullName, variable: v };
}

function askDelete(fullName: string) {
    confirmingDeleteFor.value = fullName;
}

function cancelDelete() {
    confirmingDeleteFor.value = null;
}

async function confirmDelete(fullName: string) {
    try {
        await ws.sendVariableDelete(fullName);
        confirmingDeleteFor.value = null;
    } catch (err) {
        net.pushLog('err', `variable.delete rejected: ${(err as Error).message}`);
        confirmingDeleteFor.value = null;
    }
}

// ---------- 0.4.2：inline alias 编辑 ----------

function openAliasEdit(fullName: string) {
    editingAliasFor.value = fullName;
    aliasDraft.value = aliasStore.get(fullName) ?? '';
    aliasError.value = null;
}

function cancelAliasEdit() {
    editingAliasFor.value = null;
    aliasDraft.value = '';
    aliasError.value = null;
    aliasSubmitting.value = false;
}

async function submitAliasEdit() {
    const fullName = editingAliasFor.value;
    if (fullName == null) return;
    const trimmed = aliasDraft.value.trim();
    if (trimmed.length === 0) {
        await clearAlias();
        return;
    }
    if (trimmed.length > ALIAS_MAX_LEN) {
        aliasError.value = t.value.variables.aliasErrorTooLong;
        return;
    }
    aliasSubmitting.value = true;
    aliasError.value = null;
    try {
        await ws.sendVariableAliasSet(fullName, trimmed);
        cancelAliasEdit();
    } catch (err) {
        aliasError.value = (err as Error).message;
    } finally {
        aliasSubmitting.value = false;
    }
}

async function clearAlias() {
    const fullName = editingAliasFor.value;
    if (fullName == null) return;
    aliasSubmitting.value = true;
    aliasError.value = null;
    try {
        await ws.sendVariableAliasClear(fullName);
        cancelAliasEdit();
    } catch (err) {
        aliasError.value = (err as Error).message;
    } finally {
        aliasSubmitting.value = false;
    }
}

function typeChipLabel(type: VarType): string {
    switch (type) {
        case 'STRING': return t.value.variables.typeString;
        case 'NUMBER': return t.value.variables.typeNumber;
        case 'BOOLEAN': return t.value.variables.typeBoolean;
        case 'COLOR': return t.value.variables.typeColor;
    }
}

/** type chip 颜色映射；走 Catppuccin accent 而非硬编码。 */
function typeChipClass(type: VarType): string {
    switch (type) {
        case 'STRING': return 'bg-[color:var(--ctp-blue)]/15 text-[color:var(--ctp-blue)]';
        case 'NUMBER': return 'bg-[color:var(--ctp-peach)]/15 text-[color:var(--ctp-peach)]';
        case 'BOOLEAN': return 'bg-[color:var(--ctp-mauve)]/15 text-[color:var(--ctp-mauve)]';
        case 'COLOR': return 'bg-[color:var(--ctp-pink)]/15 text-[color:var(--ctp-pink)]';
    }
}

/** COLOR 类的色块预览。 */
function isHexColor(s: string | null): boolean {
    return !!s && /^#([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?$/.test(s);
}
</script>

<template>
  <div
    v-if="ui.variablePanelOpen"
    ref="rootRef"
    class="fixed top-0 right-0 bottom-0 w-[380px] z-50 bg-[color:var(--card)] text-[color:var(--card-foreground)] border-l border-[color:var(--border)] shadow-lg flex flex-col"
  >
    <!-- Header -->
    <header class="flex items-center justify-between h-11 px-3 border-b border-[color:var(--border)]">
      <span class="text-sm font-semibold">{{ t.variables.panelTitle }}</span>
      <button
        class="p-1 rounded hover:bg-[color:var(--accent)]"
        @click="ui.closeVariablePanel()"
      >
        <X class="size-4" />
      </button>
    </header>

    <!-- Toolbar: 搜索 + 新建 -->
    <div class="flex items-center gap-2 p-2 border-b border-[color:var(--border)]">
      <div class="relative flex-1">
        <Search class="size-3.5 absolute left-2 top-1/2 -translate-y-1/2 text-[color:var(--muted-foreground)] pointer-events-none" />
        <input
          type="text"
          v-model="searchKeyword"
          :placeholder="t.variables.panelSearch"
          class="hc-input pl-7"
        />
      </div>
      <button
        class="hc-btn flex items-center gap-1 px-2.5 py-1 text-xs rounded-[var(--radius-sm)] bg-[color:var(--primary)] text-[color:var(--primary-foreground)] hover:opacity-90"
        @click="showNewDialog = true"
      >
        <Plus class="size-3.5" />
        <span>{{ t.variables.panelNewButton }}</span>
      </button>
    </div>

    <!-- Body: 4 个分组 -->
    <div class="flex-1 overflow-y-auto p-2 space-y-3 text-xs">
      <!-- 1) 我的变量 -->
      <section>
        <button
          class="w-full flex items-center gap-1 py-1 px-1 rounded hover:bg-[color:var(--accent)] text-left"
          @click="toggleGroup('mine')"
        >
          <ChevronDown v-if="!collapsed.mine" class="size-3.5 text-[color:var(--muted-foreground)]" />
          <ChevronRight v-else class="size-3.5 text-[color:var(--muted-foreground)]" />
          <Users class="size-3.5 text-[color:var(--ctp-mauve)]" />
          <span class="font-medium">{{ t.variables.groupMine }}</span>
          <span class="ml-auto text-[color:var(--muted-foreground)] font-mono">{{ myVariables.length }}</span>
        </button>
        <div v-if="!collapsed.mine" class="mt-1 pl-2 space-y-1.5">
          <div
            v-if="myVariables.length === 0"
            class="p-3 rounded-[var(--radius-sm)] bg-[color:var(--muted)] text-[color:var(--muted-foreground)] text-center"
          >
            {{ t.variables.emptyMine }}
          </div>
          <div
            v-for="entry in myVariables"
            :key="entry.fullName"
            class="rounded-[var(--radius-sm)] border border-[color:var(--border)] bg-[color:var(--background)] p-2 space-y-1"
          >
            <!-- 第 1 行：key + type chip + alias chip -->
            <div class="flex items-center gap-1.5 flex-wrap">
              <span class="font-mono text-xs truncate" :title="entry.fullName">{{ entry.v.key }}</span>
              <span
                class="px-1.5 py-0.5 rounded text-[10px] uppercase tracking-wider font-medium shrink-0"
                :class="typeChipClass(entry.v.type)"
              >{{ typeChipLabel(entry.v.type) }}</span>
              <span
                v-if="aliasStore.get(entry.fullName)"
                class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium shrink-0 bg-[color:var(--ctp-mauve)]/15 text-[color:var(--ctp-mauve)]"
                :title="t.variables.aliasChipPrefix + ' ' + aliasStore.get(entry.fullName)"
              >
                <Tag class="size-2.5" />
                <span class="truncate max-w-[120px]">{{ aliasStore.get(entry.fullName) }}</span>
              </span>
            </div>

            <!-- 第 2 行：current + default 值显示 -->
            <div class="flex flex-wrap items-center gap-x-3 gap-y-1 text-[color:var(--muted-foreground)]">
              <span class="flex items-center gap-1">
                <span>{{ t.variables.currentLabel }}:</span>
                <span
                  v-if="entry.v.type === 'COLOR' && isHexColor(entry.v.currentValue)"
                  class="inline-block size-3 rounded border border-[color:var(--border)]"
                  :style="{ backgroundColor: entry.v.currentValue ?? '#000' }"
                ></span>
                <span class="font-mono text-[color:var(--foreground)]">{{ entry.v.currentValue ?? '∅' }}</span>
              </span>
              <span class="flex items-center gap-1">
                <span>{{ t.variables.defaultLabel }}:</span>
                <span
                  v-if="entry.v.type === 'COLOR' && isHexColor(entry.v.defaultValue)"
                  class="inline-block size-3 rounded border border-[color:var(--border)]"
                  :style="{ backgroundColor: entry.v.defaultValue ?? '#000' }"
                ></span>
                <span class="font-mono">{{ entry.v.defaultValue ?? '∅' }}</span>
              </span>
            </div>

            <!-- 第 3 行：操作按钮（与 alias 编辑、删除确认三者互斥） -->
            <div
              v-if="confirmingDeleteFor !== entry.fullName && editingAliasFor !== entry.fullName"
              class="flex items-center gap-1 pt-0.5"
            >
              <!-- +/- 只 NUMBER 显示 -->
              <template v-if="entry.v.type === 'NUMBER'">
                <NumberStepButton
                  :title="t.variables.actionDec"
                  :delta="-1"
                  :on-step="(d) => incVariable(entry.fullName, entry.v, d)"
                >
                  <Minus class="size-3" />
                </NumberStepButton>
                <NumberStepButton
                  :title="t.variables.actionInc"
                  :delta="1"
                  :on-step="(d) => incVariable(entry.fullName, entry.v, d)"
                >
                  <Plus class="size-3" />
                </NumberStepButton>
              </template>
              <button
                class="hc-btn flex items-center gap-1 px-1.5 py-0.5 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                :title="t.variables.actionEdit"
                @click="openValueEditor(entry.fullName, entry.v)"
              >
                <Pencil class="size-3" />
                <span>{{ t.variables.actionEdit }}</span>
              </button>
              <button
                class="hc-btn flex items-center gap-1 px-1.5 py-0.5 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                :title="t.variables.actionEditAlias"
                @click="openAliasEdit(entry.fullName)"
              >
                <Tag class="size-3" />
              </button>
              <button
                class="hc-btn flex items-center gap-1 px-1.5 py-0.5 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                :title="t.variables.actionBind"
                @click="openBindDialog(entry.fullName, entry.v)"
              >
                <Link2 class="size-3" />
              </button>
              <button
                class="ml-auto hc-btn p-1 rounded text-[color:var(--destructive)] hover:bg-[color:var(--destructive)]/10"
                :title="t.variables.actionDelete"
                @click="askDelete(entry.fullName)"
              >
                <Trash2 class="size-3.5" />
              </button>
            </div>

            <!-- alias 编辑（inline） -->
            <div
              v-else-if="editingAliasFor === entry.fullName"
              class="flex flex-col gap-1 p-2 rounded bg-[color:var(--accent)]/10 border border-[color:var(--accent)]/30"
            >
              <div class="flex items-center gap-1.5">
                <Tag class="size-3 text-[color:var(--ctp-mauve)] shrink-0" />
                <input
                  type="text"
                  class="hc-input flex-1"
                  :placeholder="t.variables.dialogNewAliasPlaceholder"
                  v-model="aliasDraft"
                  :maxlength="ALIAS_MAX_LEN + 8"
                  :disabled="aliasSubmitting"
                  @keydown.enter.prevent="submitAliasEdit"
                  @keydown.escape.prevent="cancelAliasEdit"
                />
                <button
                  class="hc-btn p-1 rounded border border-[color:var(--border)] text-[color:var(--ctp-green,var(--primary))] hover:bg-[color:var(--accent)] disabled:opacity-40"
                  :title="t.variables.picker.aliasSaveButton"
                  :disabled="aliasSubmitting"
                  @click="submitAliasEdit"
                >
                  <Check class="size-3" />
                </button>
                <button
                  class="hc-btn p-1 rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] disabled:opacity-40"
                  :title="t.variables.picker.aliasClearButton"
                  :disabled="aliasSubmitting"
                  @click="clearAlias"
                >
                  <Eraser class="size-3" />
                </button>
                <button
                  class="hc-btn p-1 rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] disabled:opacity-40"
                  :title="t.variables.picker.aliasCancelButton"
                  :disabled="aliasSubmitting"
                  @click="cancelAliasEdit"
                >
                  <X class="size-3" />
                </button>
              </div>
              <span v-if="aliasError" class="text-[color:var(--destructive)] text-[10px]">{{ aliasError }}</span>
            </div>

            <!-- 删除确认 popover（inline） -->
            <div
              v-else
              class="flex items-center gap-2 p-2 rounded bg-[color:var(--destructive)]/10 border border-[color:var(--destructive)]/30"
            >
              <span class="flex-1 text-[color:var(--destructive)] truncate" :title="entry.fullName">
                {{ t.variables.deleteConfirm(entry.fullName) }}
              </span>
              <button
                class="hc-btn px-2 py-0.5 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                @click="cancelDelete"
              >
                {{ t.variables.deleteConfirmNo }}
              </button>
              <button
                class="hc-btn px-2 py-0.5 text-xs rounded bg-[color:var(--destructive)] text-[color:var(--destructive-foreground)] hover:opacity-90"
                @click="confirmDelete(entry.fullName)"
              >
                {{ t.variables.deleteConfirmYes }}
              </button>
            </div>
          </div>
        </div>
      </section>

      <!-- 1.5) 0.4.3 全局变量（userglobal/*）-->
      <section>
        <button
          class="w-full flex items-center gap-1 py-1 px-1 rounded hover:bg-[color:var(--accent)] text-left"
          @click="toggleGroup('global')"
        >
          <ChevronDown v-if="!collapsed.global" class="size-3.5 text-[color:var(--muted-foreground)]" />
          <ChevronRight v-else class="size-3.5 text-[color:var(--muted-foreground)]" />
          <Globe class="size-3.5 text-[color:var(--ctp-mauve)]" />
          <span class="font-medium">{{ t.variables.groupGlobal }}</span>
          <span class="ml-auto text-[color:var(--muted-foreground)] font-mono">{{ globalVariables.length }}</span>
        </button>
        <div v-if="!collapsed.global" class="mt-1 pl-2 space-y-1.5">
          <div
            v-if="globalVariables.length === 0"
            class="p-3 rounded-[var(--radius-sm)] bg-[color:var(--muted)] text-[color:var(--muted-foreground)] text-center"
          >
            {{ t.variables.emptyGlobal }}
          </div>
          <div
            v-for="entry in globalVariables"
            :key="entry.fullName"
            class="rounded-[var(--radius-sm)] border border-[color:var(--border)] bg-[color:var(--background)] p-2 space-y-1"
            :class="{ 'opacity-80': !entry.isMine }"
          >
            <!-- 第 1 行：key + type chip + alias chip + owner badge -->
            <div class="flex items-center gap-1.5 flex-wrap">
              <span class="font-mono text-xs truncate" :title="entry.fullName">{{ entry.v.key }}</span>
              <span
                class="px-1.5 py-0.5 rounded text-[10px] uppercase tracking-wider font-medium shrink-0"
                :class="typeChipClass(entry.v.type)"
              >{{ typeChipLabel(entry.v.type) }}</span>
              <span
                v-if="aliasStore.get(entry.fullName)"
                class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium shrink-0 bg-[color:var(--ctp-mauve)]/15 text-[color:var(--ctp-mauve)]"
                :title="t.variables.aliasChipPrefix + ' ' + aliasStore.get(entry.fullName)"
              >
                <Tag class="size-2.5" />
                <span class="truncate max-w-[120px]">{{ aliasStore.get(entry.fullName) }}</span>
              </span>
              <!-- 非 owner 时显示 owner 名 + 只读标 -->
              <span
                v-if="!entry.isMine && entry.ownerName"
                class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] shrink-0 bg-[color:var(--muted)] text-[color:var(--muted-foreground)]"
                :title="t.variables.ownerBadgePrefix + ' ' + entry.ownerName"
              >
                <Users class="size-2.5" />
                <span class="truncate max-w-[100px]">{{ entry.ownerName }}</span>
              </span>
              <span v-if="entry.isMine"
                class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium shrink-0 bg-[color:var(--ctp-mauve)]/15 text-[color:var(--ctp-mauve)]"
              >
                {{ t.variables.ownerMineBadge }}
              </span>
            </div>

            <!-- 第 2 行：current + default 值 -->
            <div class="flex flex-wrap items-center gap-x-3 gap-y-1 text-[color:var(--muted-foreground)]">
              <span class="flex items-center gap-1">
                <span>{{ t.variables.currentLabel }}:</span>
                <span
                  v-if="entry.v.type === 'COLOR' && isHexColor(entry.v.currentValue)"
                  class="inline-block size-3 rounded border border-[color:var(--border)]"
                  :style="{ backgroundColor: entry.v.currentValue ?? '#000' }"
                ></span>
                <span class="font-mono text-[color:var(--foreground)]">{{ entry.v.currentValue ?? '∅' }}</span>
              </span>
              <span class="flex items-center gap-1">
                <span>{{ t.variables.defaultLabel }}:</span>
                <span
                  v-if="entry.v.type === 'COLOR' && isHexColor(entry.v.defaultValue)"
                  class="inline-block size-3 rounded border border-[color:var(--border)]"
                  :style="{ backgroundColor: entry.v.defaultValue ?? '#000' }"
                ></span>
                <span class="font-mono">{{ entry.v.defaultValue ?? '∅' }}</span>
              </span>
            </div>

            <!-- 第 3 行：操作按钮（非 owner 时禁编辑；admin 也不在此 UI 暴露 — 走 /canvas var 命令） -->
            <div
              v-if="confirmingDeleteFor !== entry.fullName"
              class="flex items-center gap-1 pt-0.5"
            >
              <template v-if="entry.v.type === 'NUMBER' && entry.isMine">
                <NumberStepButton
                  :title="t.variables.actionDec"
                  :delta="-1"
                  :on-step="(d) => incVariable(entry.fullName, entry.v, d)"
                >
                  <Minus class="size-3" />
                </NumberStepButton>
                <NumberStepButton
                  :title="t.variables.actionInc"
                  :delta="1"
                  :on-step="(d) => incVariable(entry.fullName, entry.v, d)"
                >
                  <Plus class="size-3" />
                </NumberStepButton>
              </template>
              <button
                class="hc-btn flex items-center gap-1 px-1.5 py-0.5 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)] disabled:opacity-40 disabled:cursor-not-allowed"
                :title="entry.isMine ? t.variables.actionEdit : t.variables.actionReadonly"
                :disabled="!entry.isMine"
                @click="entry.isMine && openValueEditor(entry.fullName, entry.v)"
              >
                <Pencil class="size-3" />
                <span>{{ t.variables.actionEdit }}</span>
              </button>
              <button
                class="hc-btn flex items-center gap-1 px-1.5 py-0.5 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                :title="t.variables.actionEditAlias"
                @click="openAliasEdit(entry.fullName)"
              >
                <Tag class="size-3" />
              </button>
              <button
                v-if="entry.isMine"
                class="ml-auto hc-btn p-1 rounded text-[color:var(--destructive)] hover:bg-[color:var(--destructive)]/10"
                :title="t.variables.actionDelete"
                @click="askDelete(entry.fullName)"
              >
                <Trash2 class="size-3.5" />
              </button>
              <span v-else class="ml-auto text-[10px] text-[color:var(--muted-foreground)]">
                {{ t.variables.actionReadonly }}
              </span>
            </div>

            <!-- 删除确认 popover（inline） -->
            <div
              v-else
              class="flex items-center gap-2 p-2 rounded bg-[color:var(--destructive)]/10 border border-[color:var(--destructive)]/30"
            >
              <span class="flex-1 text-[color:var(--destructive)] truncate" :title="entry.fullName">
                {{ t.variables.deleteConfirm(entry.fullName) }}
              </span>
              <button
                class="hc-btn px-2 py-0.5 text-xs rounded border border-[color:var(--border)] hover:bg-[color:var(--accent)]"
                @click="cancelDelete"
              >
                {{ t.variables.deleteConfirmNo }}
              </button>
              <button
                class="hc-btn px-2 py-0.5 text-xs rounded bg-[color:var(--destructive)] text-[color:var(--destructive-foreground)] hover:opacity-90"
                @click="confirmDelete(entry.fullName)"
              >
                {{ t.variables.deleteConfirmYes }}
              </button>
            </div>
          </div>
        </div>
      </section>

      <!-- 2) 由插件提供 -->
      <section>
        <button
          class="w-full flex items-center gap-1 py-1 px-1 rounded hover:bg-[color:var(--accent)] text-left"
          @click="toggleGroup('plugin')"
        >
          <ChevronDown v-if="!collapsed.plugin" class="size-3.5 text-[color:var(--muted-foreground)]" />
          <ChevronRight v-else class="size-3.5 text-[color:var(--muted-foreground)]" />
          <Package class="size-3.5 text-[color:var(--ctp-peach)]" />
          <span class="font-medium">{{ t.variables.groupPlugin }}</span>
        </button>
        <div v-if="!collapsed.plugin" class="mt-1 pl-2">
          <div class="p-3 rounded-[var(--radius-sm)] bg-[color:var(--muted)] text-[color:var(--muted-foreground)] text-center text-xs">
            {{ t.variables.emptyPlugin }}
          </div>
        </div>
      </section>

      <!-- 3) 系统 -->
      <section>
        <button
          class="w-full flex items-center gap-1 py-1 px-1 rounded hover:bg-[color:var(--accent)] text-left"
          @click="toggleGroup('system')"
        >
          <ChevronDown v-if="!collapsed.system" class="size-3.5 text-[color:var(--muted-foreground)]" />
          <ChevronRight v-else class="size-3.5 text-[color:var(--muted-foreground)]" />
          <Globe class="size-3.5 text-[color:var(--ctp-blue)]" />
          <span class="font-medium">{{ t.variables.groupSystem }}</span>
        </button>
        <div v-if="!collapsed.system" class="mt-1 pl-2">
          <div class="p-3 rounded-[var(--radius-sm)] bg-[color:var(--muted)] text-[color:var(--muted-foreground)] text-center text-xs">
            {{ t.variables.emptySystem }}
          </div>
        </div>
      </section>

      <!-- 4) PAPI -->
      <section>
        <button
          class="w-full flex items-center gap-1 py-1 px-1 rounded hover:bg-[color:var(--accent)] text-left"
          @click="toggleGroup('papi')"
        >
          <ChevronDown v-if="!collapsed.papi" class="size-3.5 text-[color:var(--muted-foreground)]" />
          <ChevronRight v-else class="size-3.5 text-[color:var(--muted-foreground)]" />
          <Plug class="size-3.5 text-[color:var(--ctp-green)]" />
          <span class="font-medium">{{ t.variables.groupPapi }}</span>
        </button>
        <div v-if="!collapsed.papi" class="mt-1 pl-2">
          <div class="p-3 rounded-[var(--radius-sm)] bg-[color:var(--muted)] text-[color:var(--muted-foreground)] text-center text-xs">
            {{ t.variables.emptyPapi }}
          </div>
        </div>
      </section>
    </div>

    <!-- Footer hint when no wallId -->
    <div v-if="!project.wallId" class="p-2 border-t border-[color:var(--border)] text-xs text-[color:var(--muted-foreground)] text-center">
      {{ t.variables.panelEmpty }}
    </div>

    <!-- Modals -->
    <NewVariableDialog
      v-if="showNewDialog"
      @close="showNewDialog = false"
    />
    <VariableValueEditor
      v-if="showValueEditor"
      :full-name="showValueEditor.fullName"
      :variable="showValueEditor.variable"
      @close="showValueEditor = null"
    />
    <BindDialog
      v-if="showBindDialog"
      :full-name="showBindDialog.fullName"
      :variable="showBindDialog.variable"
      @close="showBindDialog = null"
    />
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
