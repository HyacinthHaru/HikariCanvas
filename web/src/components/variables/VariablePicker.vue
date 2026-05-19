<script setup lang="ts">
/**
 * VariablePicker（0.4.0-P2-H）：编辑器内 popover 变量选择器。
 *
 * <p>从 TextElementSection 的"插入变量"按钮 / 文本中输入 {@code ${} 自动触发；选中后把
 * 短名（{@code user/key} 或 {@code namespace/key}）回传给 caller，由 caller 拼成
 * {@code ${var:...}} 插入 textarea。</p>
 *
 * <p>纯逻辑（分组 / 过滤 / activeIndex）抽到 {@code @/variable/pickerLogic}；本文件只
 * 负责渲染 + 键盘交互 + onClickOutside。</p>
 */
import { computed, nextTick, onMounted, ref } from 'vue';
import { onClickOutside } from '@vueuse/core';
import { Search } from 'lucide-vue-next';
import { useVariableStore } from '@/stores/variables';
import { useI18n } from '@/i18n';
import {
    buildGroups,
    displayName,
    flattenGroups,
    nextActiveIndex,
    totalCount,
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
const { t } = useI18n();

const rootRef = ref<HTMLElement | null>(null);
const searchRef = ref<HTMLInputElement | null>(null);
const keyword = ref('');
const activeIndex = ref(0);

const groups = computed<PickerGroup[]>(() =>
    buildGroups(store.variables.values(), props.wallId, keyword.value),
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

onMounted(async () => {
    await nextTick();
    searchRef.value?.focus();
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
            <span class="hc-vp-value">{{ v.currentValue ?? v.defaultValue ?? '—' }}</span>
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
.hc-vp-value {
    flex-shrink: 0;
    max-width: 50%;
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
