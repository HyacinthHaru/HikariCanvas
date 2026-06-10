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
import { useProjectStore } from '@/stores/project';
import { useScriptEditStore } from '@/stores/scriptEdit';
import { defFor, type FieldDef } from '../model/blockDefs';
import { resolveLabelKey } from './labelKey';
import { BLOCK_DRAG_KEY, NOOP_DRAG_HANDLES } from './dragInjection';
import BlockParamInput, { type CommandValue } from '../params/BlockParamInput.vue';
import ConditionBuilder from '../params/ConditionBuilder.vue';

const props = defineProps<{
    /** 本块对应的动作（含 if）。 */
    action: ScriptAction;
    /** 本块在动作树中的 path（如 {@code 'actions/2'} / {@code 'actions/2/then/0'}）。 */
    path: string;
}>();

const { t } = useI18n();
const project = useProjectStore();
const edit = useScriptEditStore();
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

/** 锁定态：true 时所有参数控件 disabled（透传给 BlockParamInput）。 */
const locked = computed(() => project.isLocked);

/** 是否 runCommand 块（command 复合字段特殊处理：templateId + params 合一控件）。 */
const isRunCommand = computed(() => props.action.type === 'runCommand');

/**
 * 头部要渲染的“标量参数槽”字段（F：换成 {@link BlockParamInput} 真控件）：排除 statements
 * （then/else 由 C 形子槽渲染）与 condition（if 的条件单独行，留任务 G 的 ConditionBuilder）。
 *
 * <p><b>runCommand 特殊</b>：它有 templateId + params 两个 {@code command} 字段，但二者由<b>同一
 * 个</b> command 控件整体处理（见模板 {@code commandField} 分支）。故这里把 runCommand 的所有
 * command 字段从 scalarFields 排掉，避免重复渲染。</p>
 */
const scalarFields = computed<FieldDef[]>(() =>
    (def.value?.fields ?? []).filter((f) => {
        if (f.type === 'statements' || f.type === 'condition') return false;
        // runCommand 的 command 字段交给专用复合控件，不在通用 scalarFields 里渲染。
        if (isRunCommand.value && f.type === 'command') return false;
        return true;
    }),
);

/** runCommand 的复合 command 字段（取首个 command 字段作为控件锚——templateId）。 */
const commandField = computed<FieldDef | null>(() =>
    isRunCommand.value ? (def.value?.fields.find((f) => f.type === 'command') ?? null) : null,
);

/** if 块的 condition 字段（单独占位行；真构建器留任务 G）。 */
const conditionField = computed<FieldDef | null>(
    () => def.value?.fields.find((f) => f.type === 'condition') ?? null,
);

/** 字段 label 文案。 */
function fieldLabel(field: FieldDef): string {
    return resolveLabelKey(t.value, field.labelKey);
}

/**
 * 取某字段在当前 action 上的原始值（喂给 BlockParamInput 的 {@code value}）。
 * 标量字段返原值（string / number）；缺失 → undefined（控件按类型退默认）。
 */
function fieldValue(field: FieldDef): unknown {
    return (props.action as unknown as Record<string, unknown>)[field.name];
}

/**
 * 标量字段改值回写：调 {@code edit.updateActionField(path, {[name]: value})}。
 * command 类型不走这（runCommand 用 {@link onCommandUpdate}），但函数对 command 也安全
 * （不会被调用到）。
 */
function onFieldUpdate(field: FieldDef, value: unknown): void {
    edit.updateActionField(props.path, { [field.name]: value } as Partial<ScriptAction>);
}

/**
 * runCommand 的复合 command 控件改值：BlockParamInput emit {@link CommandValue}
 * （templateId + params 一起），整体回写到 action 的两个字段。
 */
function onCommandUpdate(value: unknown): void {
    const v = value as CommandValue;
    if (!v || typeof v !== 'object') return;
    edit.updateActionField(props.path, {
        templateId: v.templateId,
        params: v.params,
    } as Partial<ScriptAction>);
}

/**
 * runCommand 当前的复合值（{ templateId, params }）喂给 command 控件。
 * 非 runCommand 块返空壳（控件不会被渲染，仅类型安全）。
 */
const commandValue = computed<CommandValue>(() => {
    if (props.action.type !== 'runCommand') return { templateId: '', params: {} };
    return { templateId: props.action.templateId, params: props.action.params };
});

/** if 的 then 子序列（非 if 块为空，不渲染子槽）。 */
const thenActions = computed<ScriptAction[]>(() =>
    props.action.type === 'if' ? props.action.then : [],
);
/** if 的 else 子序列。 */
const elseActions = computed<ScriptAction[]>(() =>
    props.action.type === 'if' ? props.action.else : [],
);

/** condition 字段的原始串（喂给 ConditionBuilder）。 */
const conditionText = computed(() =>
    props.action.type === 'if' ? props.action.condition : '',
);

/** if 的 condition 改值回写（G：ConditionBuilder emit 出 build 后的 / 高级模式原串）。 */
function onConditionUpdate(value: string): void {
    edit.updateActionField(props.path, { condition: value } as Partial<ScriptAction>);
}
</script>

<template>
  <div
    class="hc-block-node"
    :data-block-path="path"
    :style="{ borderLeftColor: colorVar }"
    @pointerdown="onBlockPointerDown"
  >
    <!-- 头部：标题 + 标量参数槽（F：真表单控件 BlockParamInput） -->
    <div class="hc-block-head">
      <span class="hc-block-title" :style="{ color: colorVar }">{{ title }}</span>
      <BlockParamInput
        v-for="f in scalarFields"
        :key="f.name"
        class="hc-block-param-input"
        :field="f"
        :value="fieldValue(f)"
        :action-kind="action.type"
        :disabled="locked"
        @update="(v: unknown) => onFieldUpdate(f, v)"
      />
    </div>

    <!-- runCommand 复合 command 控件（templateId + 动态 params 子输入） -->
    <div v-if="commandField" class="hc-block-command">
      <BlockParamInput
        :field="commandField"
        :value="commandValue"
        :action-kind="action.type"
        :disabled="locked"
        @update="onCommandUpdate"
      />
    </div>

    <!-- if 块 C 形：条件 + then/else 子序列槽 -->
    <template v-if="isIf">
      <div v-if="conditionField" class="hc-block-condition">
        <span class="hc-param-label">{{ fieldLabel(conditionField) }}:</span>
        <ConditionBuilder
          :condition="conditionText"
          :wall-id="project.wallId"
          @update="onConditionUpdate"
        />
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
/* F：每个标量参数控件（BlockParamInput）外包一层浅底，与标题区分。 */
.hc-block-param-input {
    display: inline-flex;
    align-items: center;
    padding: 1px 6px;
    border-radius: 4px;
    background: color-mix(in srgb, var(--muted) 45%, transparent);
    max-width: 100%;
}
/* runCommand 复合 command 控件：占整行（模板下拉 + 动态 params 子表单纵向铺开）。 */
.hc-block-command {
    margin-top: 5px;
    width: 100%;
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
