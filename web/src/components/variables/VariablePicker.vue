<script setup lang="ts">
/**
 * VariablePicker（0.4.0-P2-H + P3-M + 0.4.2 表格化）：编辑器内 popover 变量选择器。
 *
 * <p>从 TextElementSection / Chip editor 的"插入变量"按钮 / 文本中输入 {@code ${} 自动触发；
 * 选中后把短名（{@code user/key} 或 {@code namespace/key}）回传给 caller，由 caller 拼成
 * {@code ${var:...}} 插入 textarea 或 chip。</p>
 *
 * <p><b>0.4.2</b>：</p>
 * <ul>
 *   <li>列表改 3 列表格：<b>别名 | 当前数值 | 变量名</b>；后面追加 ✏ inline 编辑按钮</li>
 *   <li>每行点 ✏ → 替换为 input + 保存/清空/取消 按钮 → wsClient.sendVariableAliasSet/Clear</li>
 *   <li>搜索关键字也匹配 alias（pickerLogic.buildGroups 第 4 参传入 aliasStore.aliases）</li>
 * </ul>
 *
 * <p>纯逻辑（分组 / 过滤 / activeIndex / metadata 合并）仍在 {@code @/variable/pickerLogic}。</p>
 */
import { computed, nextTick, onMounted, ref } from 'vue';
import { onClickOutside } from '@vueuse/core';
import { Search, Pencil, Check, X as XIcon, Eraser } from 'lucide-vue-next';
import { getWsClient } from '@/network/wsClient';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { useVariableStore } from '@/stores/variables';
import { useVariableAliasStore } from '@/stores/variableAliases';
import { useI18n } from '@/i18n';
import {
    buildGroups,
    displayName,
    flattenGroups,
    isDynamicNamespace,
    mergeMetadata,
    nextActiveIndex,
    totalCount,
    type NamespaceMetadata,
    type PickerGroup,
} from '@/variable/pickerLogic';
import type { Variable } from '@/types/variable';

interface Props {
    wallId: string | null;
}
const props = defineProps<Props>();
const emit = defineEmits<{
    select: [fullName: string];
    close: [];
}>();

const store = useVariableStore();
const aliasStore = useVariableAliasStore();
const network = useNetworkStore();
const project = useProjectStore();
const { t } = useI18n();
const ws = getWsClient();

const rootRef = ref<HTMLElement | null>(null);
const searchRef = ref<HTMLInputElement | null>(null);
const keyword = ref('');
const activeIndex = ref(0);

/** P3-M：metadata fetch 结果；失败 / 未到位时空数组（picker 走 store-only 兜底）。 */
const metadata = ref<NamespaceMetadata[]>([]);

/**
 * 0.4.2 inline alias 编辑状态：
 * - editingFullName：null = 没在编辑；非 null = 当前正在编辑此 fullName 的别名
 * - editingDraft：编辑框内当前文本
 * - editingError：长度等校验错误
 */
const editingFullName = ref<string | null>(null);
const editingDraft = ref('');
const editingError = ref<string | null>(null);
const editingSubmitting = ref(false);

/** 合并 store cached + metadata declared keys → 完整可用列表。 */
const merged = computed(() => mergeMetadata(store.variables.values(), metadata.value));

const groups = computed<PickerGroup[]>(() =>
    // 0.4.3：第 5 参 selfUuid 传入让 userglobal 分到 myGlobal / othersGlobal
    buildGroups(merged.value, props.wallId, keyword.value, aliasStore.aliases,
        project.selfUuid),
);

const flat = computed(() => flattenGroups(groups.value));
const total = computed(() => totalCount(groups.value));

const groupTitleMap = computed<Record<PickerGroup['id'], string>>(() => ({
    mine: t.value.variables.picker.groupMine,
    myGlobal: t.value.variables.picker.groupMyGlobal,
    othersGlobal: t.value.variables.picker.groupOthersGlobal,
    plugin: t.value.variables.picker.groupPlugin,
    system: t.value.variables.picker.groupSystem,
    papi: t.value.variables.picker.groupPapi,
}));

function variableFullName(v: Variable): string {
    return `${v.namespace}/${v.key}`;
}

function aliasOf(v: Variable): string | null {
    return aliasStore.get(variableFullName(v));
}

function absoluteIdx(groupIdx: number, innerIdx: number): number {
    let acc = 0;
    for (let i = 0; i < groupIdx; i += 1) acc += groups.value[i].items.length;
    return acc + innerIdx;
}

function selectFlat(idx: number) {
    const v = flat.value[idx];
    if (!v) return;
    emit('select', displayName(v));
}

/** 是否 dynamic namespace（picker 行 UI 加标签）。 */
function isDynamic(namespace: string): boolean {
    return isDynamicNamespace(metadata.value, namespace);
}

function onKeyDown(ev: KeyboardEvent) {
    // 在 alias 编辑态下，键盘事件由 input 自己处理，不应触发 picker 导航
    if (editingFullName.value != null) return;
    if (ev.key === 'ArrowDown') {
        ev.preventDefault();
        activeIndex.value = nextActiveIndex(activeIndex.value, 1, total.value);
    } else if (ev.key === 'ArrowUp') {
        ev.preventDefault();
        activeIndex.value = nextActiveIndex(activeIndex.value, -1, total.value);
    } else if (ev.key === 'Enter') {
        ev.preventDefault();
        if (activeIndex.value >= 0 && activeIndex.value < total.value) {
            selectFlat(activeIndex.value);
        }
    } else if (ev.key === 'Escape') {
        ev.preventDefault();
        emit('close');
    }
}

async function fetchMetadata() {
    const sid = network.sessionId;
    if (!sid) return; // 未鉴权：picker 走 store-only
    try {
        const params = new URLSearchParams({ sessionId: sid });
        if (props.wallId) params.append('wallId', props.wallId);
        const res = await fetch(`/api/variable/list-all-namespaces?${params.toString()}`);
        if (!res.ok) return;
        const json = (await res.json()) as { namespaces?: NamespaceMetadata[] };
        metadata.value = json.namespaces ?? [];
    } catch {
        // 静默：metadata 拿不到不影响 picker 基础（cached 变量仍可选）
    }
}

onMounted(async () => {
    await nextTick();
    searchRef.value?.focus();
    void fetchMetadata();
});

onClickOutside(rootRef, () => emit('close'));

// ============================================================================
// 0.4.2：inline alias 编辑
// ============================================================================

function openAliasEdit(v: Variable, ev: Event) {
    ev.stopPropagation();  // 阻止 row click → selectFlat
    const fullName = variableFullName(v);
    editingFullName.value = fullName;
    editingDraft.value = aliasOf(v) ?? '';
    editingError.value = null;
    // 下一 tick focus input
    nextTick(() => {
        const root = rootRef.value;
        if (!root) return;
        const input = root.querySelector<HTMLInputElement>('.hc-alias-edit-input');
        if (input) {
            input.focus();
            input.select();
        }
    });
}

function cancelAliasEdit(ev?: Event) {
    if (ev) ev.stopPropagation();
    editingFullName.value = null;
    editingDraft.value = '';
    editingError.value = null;
    editingSubmitting.value = false;
}

function onAliasInputKeydown(ev: KeyboardEvent) {
    // 编辑态下吞掉 picker 全局快捷键（避免冒泡到 onKeyDown 触发关闭等）
    ev.stopPropagation();
    if (ev.key === 'Enter') {
        ev.preventDefault();
        void submitAliasEdit();
    } else if (ev.key === 'Escape') {
        ev.preventDefault();
        cancelAliasEdit();
    }
}

async function submitAliasEdit(ev?: Event) {
    if (ev) ev.stopPropagation();
    const fullName = editingFullName.value;
    if (fullName == null) return;
    const trimmed = editingDraft.value.trim();
    if (trimmed.length === 0) {
        // 空字符串视为清除
        await clearAlias(ev);
        return;
    }
    if (trimmed.length > 64) {
        editingError.value = t.value.variables.picker.aliasTooLong;
        return;
    }
    editingSubmitting.value = true;
    editingError.value = null;
    try {
        await ws.sendVariableAliasSet(fullName, trimmed);
        editingFullName.value = null;
        editingDraft.value = '';
    } catch (err) {
        editingError.value = (err as Error).message;
    } finally {
        editingSubmitting.value = false;
    }
}

async function clearAlias(ev?: Event) {
    if (ev) ev.stopPropagation();
    const fullName = editingFullName.value;
    if (fullName == null) return;
    editingSubmitting.value = true;
    editingError.value = null;
    try {
        await ws.sendVariableAliasClear(fullName);
        editingFullName.value = null;
        editingDraft.value = '';
    } catch (err) {
        editingError.value = (err as Error).message;
    } finally {
        editingSubmitting.value = false;
    }
}
</script>

<template>
  <div
    ref="rootRef"
    class="hc-variable-picker"
    role="listbox"
    @keydown="onKeyDown"
  >
    <header class="hc-vp-search-wrap">
      <Search class="size-3 opacity-60" />
      <input
        ref="searchRef"
        v-model="keyword"
        type="text"
        class="hc-vp-search"
        :placeholder="t.variables.picker.searchPlaceholder"
        @keydown="onKeyDown"
      >
    </header>

    <div v-if="total === 0" class="hc-vp-empty">
      {{ t.variables.picker.emptyResults }}
    </div>

    <div v-else class="hc-vp-table-wrap">
      <table class="hc-vp-table">
        <thead>
          <tr>
            <th class="hc-vp-col-alias">{{ t.variables.picker.columnAlias }}</th>
            <th class="hc-vp-col-value">{{ t.variables.picker.columnValue }}</th>
            <th class="hc-vp-col-name">{{ t.variables.picker.columnName }}</th>
            <th class="hc-vp-col-actions"></th>
          </tr>
        </thead>
        <tbody>
          <template v-for="(group, gi) in groups" :key="group.id">
            <tr v-if="group.items.length > 0" class="hc-vp-group-row">
              <td colspan="4" class="hc-vp-group-title">{{ groupTitleMap[group.id] }}</td>
            </tr>
            <template v-for="(v, vi) in group.items" :key="`${v.namespace}/${v.key}`">
              <!-- 普通行 -->
              <tr
                v-if="editingFullName !== variableFullName(v)"
                class="hc-vp-row"
                :class="{ 'hc-vp-active': activeIndex === absoluteIdx(gi, vi) }"
                role="option"
                :aria-selected="activeIndex === absoluteIdx(gi, vi)"
                @click="selectFlat(absoluteIdx(gi, vi))"
                @mouseenter="activeIndex = absoluteIdx(gi, vi)"
              >
                <td class="hc-vp-cell hc-vp-alias-cell">
                  <span v-if="aliasOf(v)" class="hc-vp-alias-text" :title="aliasOf(v) ?? ''">
                    {{ aliasOf(v) }}
                  </span>
                  <span v-else class="hc-vp-alias-empty">{{ t.variables.picker.emptyAliasPlaceholder }}</span>
                </td>
                <td class="hc-vp-cell hc-vp-value-cell">
                  <span class="hc-vp-value">{{ v.currentValue ?? v.defaultValue ?? '—' }}</span>
                </td>
                <td class="hc-vp-cell hc-vp-name-cell">
                  <span class="hc-vp-name" :title="`${v.namespace}/${v.key}`">{{ displayName(v) }}</span>
                  <span class="hc-vp-meta">
                    <span
                      v-if="isDynamic(v.namespace)"
                      class="hc-vp-chip hc-vp-chip-dynamic"
                      :title="t.variables.picker.dynamicHint"
                    >dyn</span>
                    <span class="hc-vp-chip hc-vp-chip-type">{{ v.type }}</span>
                  </span>
                </td>
                <td class="hc-vp-cell hc-vp-action-cell">
                  <button
                    class="hc-vp-edit-btn"
                    :title="t.variables.picker.aliasEditTooltip"
                    @click="openAliasEdit(v, $event)"
                  >
                    <Pencil class="size-3" />
                  </button>
                </td>
              </tr>
              <!-- 编辑态：占整行 -->
              <tr
                v-else
                class="hc-vp-row hc-vp-edit-row"
              >
                <td colspan="4" class="hc-vp-cell hc-vp-edit-cell">
                  <div class="hc-vp-edit-line">
                    <input
                      class="hc-vp-edit-input hc-alias-edit-input"
                      v-model="editingDraft"
                      type="text"
                      :placeholder="t.variables.picker.aliasEditPlaceholder"
                      :maxlength="80"
                      :disabled="editingSubmitting"
                      @keydown="onAliasInputKeydown"
                      @click.stop
                    />
                    <button
                      class="hc-vp-edit-action hc-vp-save"
                      :title="t.variables.picker.aliasSaveButton"
                      :disabled="editingSubmitting"
                      @click="submitAliasEdit"
                    >
                      <Check class="size-3" />
                    </button>
                    <button
                      class="hc-vp-edit-action hc-vp-clear"
                      :title="t.variables.picker.aliasClearButton"
                      :disabled="editingSubmitting"
                      @click="clearAlias"
                    >
                      <Eraser class="size-3" />
                    </button>
                    <button
                      class="hc-vp-edit-action hc-vp-cancel"
                      :title="t.variables.picker.aliasCancelButton"
                      :disabled="editingSubmitting"
                      @click="cancelAliasEdit"
                    >
                      <XIcon class="size-3" />
                    </button>
                  </div>
                  <div class="hc-vp-edit-meta">
                    <span class="hc-vp-edit-target">{{ displayName(v) }}</span>
                    <span v-if="editingError" class="hc-vp-edit-error">{{ editingError }}</span>
                  </div>
                </td>
              </tr>
            </template>
          </template>
        </tbody>
      </table>
    </div>

    <footer class="hc-vp-footer">
      {{ t.variables.picker.keyboardHint }}
    </footer>
  </div>
</template>

<style scoped>
.hc-variable-picker {
    position: absolute;
    top: calc(100% + 4px);
    left: 0;
    right: 0;
    z-index: 50;
    max-width: 420px;
    max-height: 400px;
    background: var(--card);
    color: var(--card-foreground);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    box-shadow: 0 4px 16px rgba(0, 0, 0, 0.15);
    display: flex;
    flex-direction: column;
    overflow: hidden;
}

.hc-vp-search-wrap {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px 8px;
    border-bottom: 1px solid var(--border);
}
.hc-vp-search {
    flex: 1;
    background: transparent;
    border: none;
    outline: none;
    color: var(--foreground);
    font-size: 12px;
}

.hc-vp-empty {
    padding: 16px;
    text-align: center;
    color: var(--muted-foreground);
    font-size: 11px;
}

.hc-vp-table-wrap {
    flex: 1;
    overflow-y: auto;
}

.hc-vp-table {
    width: 100%;
    border-collapse: collapse;
    table-layout: auto;
    font-size: 11px;
}
.hc-vp-table thead th {
    position: sticky;
    top: 0;
    background: var(--card);
    text-align: left;
    padding: 4px 8px;
    font-size: 10px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--muted-foreground);
    border-bottom: 1px solid var(--border);
    z-index: 1;
}
.hc-vp-col-alias { width: 28%; }
.hc-vp-col-value { width: 22%; }
.hc-vp-col-name { width: 44%; }
.hc-vp-col-actions { width: 6%; }

.hc-vp-group-row td {
    padding: 4px 8px 2px;
    font-size: 10px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--muted-foreground);
    background: color-mix(in srgb, var(--muted) 35%, transparent);
    border-top: 1px solid var(--border);
}

.hc-vp-row {
    cursor: pointer;
}
.hc-vp-row.hc-vp-active {
    background: var(--accent);
    color: var(--accent-foreground);
}
.hc-vp-cell {
    padding: 4px 8px;
    vertical-align: middle;
    border-bottom: 1px dashed color-mix(in srgb, var(--border) 60%, transparent);
}
.hc-vp-alias-text {
    font-weight: 500;
    color: var(--foreground);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    display: inline-block;
    max-width: 100%;
}
.hc-vp-alias-empty {
    color: var(--muted-foreground);
    opacity: 0.6;
}
.hc-vp-value {
    font-variant-numeric: tabular-nums;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    display: inline-block;
    max-width: 100%;
    color: var(--foreground);
}
.hc-vp-name-cell {
    display: flex;
    align-items: center;
    gap: 4px;
    overflow: hidden;
}
.hc-vp-name {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    flex: 1;
    min-width: 0;
}
.hc-vp-meta {
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    gap: 4px;
}
.hc-vp-chip {
    flex-shrink: 0;
    font-size: 9px;
    line-height: 1;
    padding: 2px 4px;
    border-radius: 4px;
    background: var(--muted);
    color: var(--muted-foreground);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    text-transform: uppercase;
}
.hc-vp-chip-dynamic {
    background: var(--accent);
    color: var(--accent-foreground);
}
.hc-vp-edit-btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 22px;
    height: 22px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: var(--background);
    color: var(--muted-foreground);
    cursor: pointer;
    transition: background-color 120ms ease, color 120ms ease;
}
.hc-vp-edit-btn:hover {
    background: var(--accent);
    color: var(--accent-foreground);
}

/* inline 编辑行 */
.hc-vp-edit-row {
    background: color-mix(in srgb, var(--accent) 12%, transparent);
}
.hc-vp-edit-cell {
    padding: 6px 8px;
}
.hc-vp-edit-line {
    display: flex;
    align-items: center;
    gap: 4px;
}
.hc-vp-edit-input {
    flex: 1;
    padding: 3px 6px;
    font-size: 11px;
    border: 1px solid var(--ring);
    border-radius: 4px;
    background: var(--background);
    color: var(--foreground);
    outline: none;
}
.hc-vp-edit-input:focus {
    box-shadow: 0 0 0 1px var(--ring);
}
.hc-vp-edit-input:disabled {
    opacity: 0.5;
}
.hc-vp-edit-action {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 22px;
    height: 22px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: var(--background);
    cursor: pointer;
}
.hc-vp-edit-action:disabled {
    opacity: 0.4;
    cursor: not-allowed;
}
.hc-vp-save { color: var(--ctp-green, var(--primary)); }
.hc-vp-clear { color: var(--ctp-peach, var(--muted-foreground)); }
.hc-vp-cancel { color: var(--muted-foreground); }
.hc-vp-edit-action:hover:not(:disabled) {
    background: var(--accent);
}
.hc-vp-edit-meta {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    gap: 8px;
    margin-top: 3px;
    font-size: 10px;
}
.hc-vp-edit-target {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    color: var(--muted-foreground);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    flex: 1;
    min-width: 0;
}
.hc-vp-edit-error {
    color: var(--destructive);
    flex-shrink: 0;
}

.hc-vp-footer {
    border-top: 1px solid var(--border);
    padding: 4px 10px;
    font-size: 10px;
    color: var(--muted-foreground);
    background: var(--background);
}
</style>
