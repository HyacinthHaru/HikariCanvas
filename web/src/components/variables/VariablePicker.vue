<script setup lang="ts">
/**
 * VariablePicker（0.4.0-P2-H + P3-M 扩展）：编辑器内 popover 变量选择器。
 *
 * <p>从 TextElementSection 的"插入变量"按钮 / 文本中输入 {@code ${} 自动触发；选中后把
 * 短名（{@code user/key} 或 {@code namespace/key}）回传给 caller，由 caller 拼成
 * {@code ${var:...}} 插入 textarea。</p>
 *
 * <p>纯逻辑（分组 / 过滤 / activeIndex / metadata 合并）抽到 {@code @/variable/pickerLogic}；
 * 本文件只负责渲染 + 键盘交互 + onClickOutside + mount 时 fetch metadata。</p>
 *
 * <p><b>P3-M</b>：mount 时调 {@code GET /api/variable/list-all-namespaces?sessionId=&wallId=}
 * 拉所有 Provider 声明的 keys + 当前 wall 的 user 变量 metadata；与 store cached 值合并后
 * 展示——让 picker 看到所有可用 namespace/key（即使 cached value 还没来）。fetch 失败 silent
 * fallback 到原 store-only 行为，不影响基础功能。</p>
 */
import { computed, nextTick, onMounted, ref } from 'vue';
import { onClickOutside } from '@vueuse/core';
import { Search } from 'lucide-vue-next';
import { useNetworkStore } from '@/stores/network';
import { useVariableStore } from '@/stores/variables';
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

interface Props {
    wallId: string | null;
}
const props = defineProps<Props>();
const emit = defineEmits<{
    select: [fullName: string];
    close: [];
}>();

const store = useVariableStore();
const network = useNetworkStore();
const { t } = useI18n();

const rootRef = ref<HTMLElement | null>(null);
const searchRef = ref<HTMLInputElement | null>(null);
const keyword = ref('');
const activeIndex = ref(0);

/** P3-M：metadata fetch 结果；失败 / 未到位时空数组（picker 走 store-only 兜底）。 */
const metadata = ref<NamespaceMetadata[]>([]);

/** 合并 store cached + metadata declared keys → 完整可用列表。 */
const merged = computed(() => mergeMetadata(store.variables.values(), metadata.value));

const groups = computed<PickerGroup[]>(() =>
    buildGroups(merged.value, props.wallId, keyword.value),
);

const flat = computed(() => flattenGroups(groups.value));
const total = computed(() => totalCount(groups.value));

const groupTitleMap = computed<Record<PickerGroup['id'], string>>(() => ({
    mine: t.value.variables.picker.groupMine,
    plugin: t.value.variables.picker.groupPlugin,
    system: t.value.variables.picker.groupSystem,
    papi: t.value.variables.picker.groupPapi,
}));

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

    <div v-else class="hc-vp-groups">
      <section
        v-for="(group, gi) in groups"
        v-show="group.items.length > 0"
        :key="group.id"
        class="hc-vp-group"
      >
        <h4 class="hc-vp-group-title">{{ groupTitleMap[group.id] }}</h4>
        <ul class="hc-vp-list">
          <li
            v-for="(v, vi) in group.items"
            :key="`${v.namespace}/${v.key}`"
            class="hc-vp-item"
            :class="{ 'hc-vp-active': activeIndex === absoluteIdx(gi, vi) }"
            role="option"
            :aria-selected="activeIndex === absoluteIdx(gi, vi)"
            @click="selectFlat(absoluteIdx(gi, vi))"
            @mouseenter="activeIndex = absoluteIdx(gi, vi)"
          >
            <span class="hc-vp-name">{{ displayName(v) }}</span>
            <span class="hc-vp-meta">
              <span
                v-if="isDynamic(v.namespace)"
                class="hc-vp-chip hc-vp-chip-dynamic"
                :title="t.variables.picker.dynamicHint"
              >dyn</span>
              <span class="hc-vp-chip hc-vp-chip-type">{{ v.type }}</span>
              <span class="hc-vp-value">{{ v.currentValue ?? v.defaultValue ?? '—' }}</span>
            </span>
          </li>
        </ul>
      </section>
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
    max-width: 320px;
    max-height: 360px;
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

.hc-vp-groups {
    flex: 1;
    overflow-y: auto;
    padding: 4px 0;
}

.hc-vp-group {
    padding: 4px 0;
}
.hc-vp-group-title {
    padding: 2px 10px;
    font-size: 10px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--muted-foreground);
}

.hc-vp-list {
    list-style: none;
    margin: 0;
    padding: 0;
}
.hc-vp-item {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
    padding: 4px 10px;
    cursor: pointer;
    font-size: 11px;
}
.hc-vp-item.hc-vp-active {
    background: var(--accent);
    color: var(--accent-foreground);
}
.hc-vp-name {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.hc-vp-meta {
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    gap: 4px;
    max-width: 55%;
    overflow: hidden;
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
.hc-vp-value {
    flex-shrink: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--muted-foreground);
    font-variant-numeric: tabular-nums;
}

.hc-vp-footer {
    border-top: 1px solid var(--border);
    padding: 4px 10px;
    font-size: 10px;
    color: var(--muted-foreground);
    background: var(--background);
}
</style>
