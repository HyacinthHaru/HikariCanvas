<script setup lang="ts">
/**
 * 0.7.0-P4-C：递归积木块（一个动作 / if 条件分支）。
 *
 * <p>渲染单个 {@link ScriptAction}：左侧色条（按 def.category 着色）+ 头部 label + 参数槽。
 * <b>本阶段参数槽渲染占位</b>——每个非 {@code statements}/{@code condition} 字段显
 * “字段label: 原始值文本”；真表单控件（number/select/variable/...）留任务 F。</p>
 *
 * <p><b>if 块 = C 形</b>：condition 字段单独占位显示条件串；{@code then} / {@code else} 两个
 * 子序列槽用本组件递归渲染 —— 子积木 path 拼 {@code `${path}/then/${i}`} /
 * {@code `${path}/else/${i}`}，<b>与后端 trace blockId 逐字符同构</b>（权威
 * blockTree.ts 头注 / ScriptRunner.java）。空槽显占位提示。</p>
 *
 * <p>块根挂 {@code data-block-path}（= props.path）——供任务 D 测量插槽矩形、任务 H 按
 * trace blockId 高亮定位。lock 态（project.isLocked）下本阶段仍静态渲染（拖拽 / 交互在
 * 任务 D 接，按 K-UI-12 守卫）。</p>
 */
import { computed, inject } from 'vue';
import type { ScriptAction } from '@/types/protocol';
import { useI18n } from '@/i18n';
import { defFor, type FieldDef } from '../model/blockDefs';
import { resolveLabelKey } from './labelKey';
import { BLOCK_DRAG_KEY, NOOP_DRAG_HANDLES } from './dragInjection';

const props = defineProps<{
    /** 本块对应的动作（含 if）。 */
    action: ScriptAction;
    /** 本块在动作树中的 path（如 {@code 'actions/2'} / {@code 'actions/2/then/0'}）。 */
    path: string;
}>();

const { t } = useI18n();
/** D2 拖拽句柄（BlockCanvas provide；单独 mount 时走 no-op 兜底）。 */
const dragHandles = inject(BLOCK_DRAG_KEY, NOOP_DRAG_HANDLES);

/**
 * 块根 pointerdown：启动"拖已有块"。只接管主键（左键）。ruleId 从最近祖先 [data-rule-id] 现取
 * （BlockNode 递归不显式持 ruleId，避免一路透传 prop）。{@code startBlockDrag} 内部会
 * stopPropagation——嵌套块时只有<b>指针正下方最深的那块</b>启动拖动，外层不抢。
 *
 * <p>C/F 阶段块内会有参数输入框：表单元素聚焦不应触发拖块（避免点输入框就拖走）。这里
 * 跳过 input/textarea/select/contentEditable 目标（pointerdown 落在它们上时不拖）。</p>
 */
function onBlockPointerDown(e: PointerEvent): void {
    if (e.button !== 0) return;
    if (isFormTarget(e.target)) return;
    const host = (e.currentTarget as HTMLElement).closest('[data-rule-id]');
    const ruleId = host?.getAttribute('data-rule-id');
    if (!ruleId) return;
    dragHandles.startBlockDrag(ruleId, props.path, e);
}

function isFormTarget(target: EventTarget | null): boolean {
    const el = target as HTMLElement | null;
    return !!el && (el.matches?.('input, textarea, select, button') || el.isContentEditable);
}

/** 本块的声明定义；未知 kind → null（兜底显示 unknownBlock）。 */
const def = computed(() => defFor(props.action.type));

/** 块标题文案（def.labelKey 解析；无 def 时显示未知积木兜底）。 */
const title = computed(() =>
    def.value
        ? resolveLabelKey(t.value, def.value.labelKey)
        : `${resolveLabelKey(t.value, 'script.unknownBlock')}: ${props.action.type}`,
);

/** 色条颜色（无 def → 用边框灰）。 */
const colorVar = computed(() => (def.value ? `var(${def.value.colorVar})` : 'var(--border)'));

/** 是否 if 块（C 形布局：条件 + then/else 子槽）。 */
const isIf = computed(() => props.action.type === 'if');

/**
 * 头部要渲染的“标量参数槽”字段：排除 statements（then/else 由 C 形子槽渲染）
 * 与 condition（if 的条件单独行渲染）。占位阶段只读原始值。
 */
const scalarFields = computed<FieldDef[]>(() =>
    (def.value?.fields ?? []).filter((f) => f.type !== 'statements' && f.type !== 'condition'),
);

/** if 块的 condition 字段（单独占位行）。 */
const conditionField = computed<FieldDef | null>(
    () => def.value?.fields.find((f) => f.type === 'condition') ?? null,
);

/** 字段 label 文案。 */
function fieldLabel(field: FieldDef): string {
    return resolveLabelKey(t.value, field.labelKey);
}

/**
 * 取某字段在当前 action 上的原始值文本（占位显示用）。
 * 缺失 / undefined → 空串；对象（如 runCommand.params）→ JSON.stringify 兜底。
 */
function rawValue(field: FieldDef): string {
    const raw = (props.action as unknown as Record<string, unknown>)[field.name];
    if (raw === undefined || raw === null) return '';
    if (typeof raw === 'object') {
        try {
            return JSON.stringify(raw);
        } catch {
            return String(raw);
        }
    }
    return String(raw);
}

/** if 的 then 子序列（非 if 块为空，不渲染子槽）。 */
const thenActions = computed<ScriptAction[]>(() =>
    props.action.type === 'if' ? props.action.then : [],
);
/** if 的 else 子序列。 */
const elseActions = computed<ScriptAction[]>(() =>
    props.action.type === 'if' ? props.action.else : [],
);

/** condition 字段的原始串（占位显示）。 */
const conditionText = computed(() =>
    props.action.type === 'if' ? props.action.condition : '',
);
</script>

<template>
  <div
    class="hc-block-node"
    :data-block-path="path"
    :style="{ borderLeftColor: colorVar }"
    @pointerdown="onBlockPointerDown"
  >
    <!-- 头部：标题 + 标量参数槽（占位：字段名 + 原始值） -->
    <div class="hc-block-head">
      <span class="hc-block-title" :style="{ color: colorVar }">{{ title }}</span>
      <span
        v-for="f in scalarFields"
        :key="f.name"
        class="hc-block-param"
      >
        <span class="hc-param-label">{{ fieldLabel(f) }}:</span>
        <span class="hc-param-value">{{ rawValue(f) || '—' }}</span>
      </span>
    </div>

    <!-- if 块 C 形：条件 + then/else 子序列槽 -->
    <template v-if="isIf">
      <div v-if="conditionField" class="hc-block-condition">
        <span class="hc-param-label">{{ fieldLabel(conditionField) }}:</span>
        <span class="hc-param-value hc-condition-text">{{ conditionText || '—' }}</span>
      </div>

      <!-- then 子槽 -->
      <div class="hc-block-branch">
        <span class="hc-branch-label">{{ resolveLabelKey(t, 'script.fields.then') }}</span>
        <div class="hc-branch-slot">
          <BlockNode
            v-for="(child, i) in thenActions"
            :key="`${path}/then/${i}`"
            :action="child"
            :path="`${path}/then/${i}`"
          />
          <!-- 空 then 槽：data-slot-path 让 collectSlots 当 index=0 落点 -->
          <div
            v-if="thenActions.length === 0"
            class="hc-empty-slot"
            :data-slot-path="`${path}/then`"
          >
            {{ resolveLabelKey(t, 'script.emptySlot') }}
          </div>
        </div>
      </div>

      <!-- else 子槽 -->
      <div class="hc-block-branch">
        <span class="hc-branch-label">{{ resolveLabelKey(t, 'script.fields.else') }}</span>
        <div class="hc-branch-slot">
          <BlockNode
            v-for="(child, i) in elseActions"
            :key="`${path}/else/${i}`"
            :action="child"
            :path="`${path}/else/${i}`"
          />
          <!-- 空 else 槽：data-slot-path 让 collectSlots 当 index=0 落点 -->
          <div
            v-if="elseActions.length === 0"
            class="hc-empty-slot"
            :data-slot-path="`${path}/else`"
          >
            {{ resolveLabelKey(t, 'script.emptySlot') }}
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.hc-block-node {
    position: relative;
    border-left: 4px solid var(--border);
    border-radius: 6px;
    background: var(--card);
    box-shadow: 0 1px 2px rgba(0, 0, 0, 0.12);
    padding: 6px 8px 6px 10px;
    user-select: none;
    /* 块可拖动重排 / 跨堆 / 入 if 槽 */
    cursor: grab;
}
.hc-block-node:active {
    cursor: grabbing;
}
.hc-block-head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
}
.hc-block-title {
    font-size: 12px;
    font-weight: 600;
    white-space: nowrap;
}
.hc-block-param {
    display: inline-flex;
    align-items: baseline;
    gap: 3px;
    font-size: 11px;
    padding: 1px 6px;
    border-radius: 4px;
    background: color-mix(in srgb, var(--muted) 60%, transparent);
    max-width: 220px;
}
.hc-param-label {
    color: var(--muted-foreground);
    white-space: nowrap;
}
.hc-param-value {
    color: var(--foreground);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.hc-block-condition {
    margin-top: 5px;
    display: flex;
    align-items: baseline;
    gap: 4px;
    font-size: 11px;
}
.hc-condition-text {
    font-family: var(--font-mono, monospace);
    color: var(--ctp-green, var(--foreground));
}
.hc-block-branch {
    margin-top: 5px;
}
.hc-branch-label {
    display: inline-block;
    font-size: 10px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--muted-foreground);
    margin-bottom: 3px;
}
.hc-branch-slot {
    display: flex;
    flex-direction: column;
    gap: 5px;
    /* C 形：子序列内缩 + 左侧引导线，视觉上"嵌"在 if 里 */
    margin-left: 10px;
    padding-left: 8px;
    border-left: 2px dashed color-mix(in srgb, var(--ctp-green, var(--border)) 50%, transparent);
    min-height: 22px;
}
.hc-empty-slot {
    font-size: 11px;
    color: var(--muted-foreground);
    font-style: italic;
    padding: 3px 6px;
    border: 1px dashed var(--border);
    border-radius: 4px;
}
</style>
