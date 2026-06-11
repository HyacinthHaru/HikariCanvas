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
import { ref } from 'vue';
import { defFor, type FieldDef } from '../model/blockDefs';
import { resolveLabelKey } from './labelKey';
import { BLOCK_DRAG_KEY, NOOP_DRAG_HANDLES } from './dragInjection';
import { BLOCK_HIGHLIGHT_KEY, type HighlightInject } from './highlightInjection';
import { resultColorVar, type HighlightMap } from './traceHighlight';
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
 * H 阶段试跑高亮（overlay provide；单独 mount 时走空 map 兜底——查不到高亮，静态渲染）。
 * 用常量空 ref 作默认值，避免每个未注入场景都新建 ref。
 */
const EMPTY_HIGHLIGHT: HighlightInject = {
    results: ref<HighlightMap>(new Map()),
    details: ref<Map<string, string>>(new Map()),
};
const highlight = inject(BLOCK_HIGHLIGHT_KEY, EMPTY_HIGHLIGHT);

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

// ---------- H：试跑高亮（按本块 path 查高亮 map）----------

/** 本块的试跑结果态（未命中 → undefined，不高亮）。 */
const highlightResult = computed(() => highlight.results.value.get(props.path));
/** 本块的 trace detail 文案（作 title 提示；无则 undefined）。 */
const highlightDetail = computed(() => highlight.details.value.get(props.path));
/**
 * 高亮态的边框色：命中 step 时用结果色（ok=green/skipped=overlay/blocked=yellow/error=red），
 * 否则用 def 的 category 色条色（与原静态渲染一致）。
 */
const effectiveBorderColor = computed(() =>
    highlightResult.value ? `var(${resultColorVar(highlightResult.value)})` : colorVar.value,
);
/** 高亮态时整块加一圈外发光描边（让"正在执行 / 命中"更醒目）。 */
const highlightStyle = computed(() => {
    const r = highlightResult.value;
    if (!r) return {};
    const v = `var(${resultColorVar(r)})`;
    return {
        boxShadow: `0 0 0 2px color-mix(in srgb, ${v} 60%, transparent), 0 1px 2px rgba(0,0,0,0.12)`,
    };
});

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

// ---------- 视觉：必填引用字段未填的"待选择"角标（不改逻辑，只读判定）----------

/**
 * 本块里"必填但还没填"的引用字段（仅 element / timeline / command 三类——它们依赖墙上
 * 有什么，{@code makeDefaultAction} 故意留空，用户必须从下拉里选）。空 = 值是空串 / 缺失。
 *
 * <p>纯读 def.fields + action 当前值，不碰 validator / 默认值逻辑。返回字段友好名数组，
 * 喂给角标 hover 提示（"还需选择：元素"）。command 复合字段只看 templateId 是否空。</p>
 */
const missingRefFields = computed<string[]>(() => {
    const out: string[] = [];
    for (const f of def.value?.fields ?? []) {
        if (f.type === 'element' || f.type === 'timeline') {
            const v = (props.action as unknown as Record<string, unknown>)[f.name];
            if (typeof v !== 'string' || v.trim() === '') {
                out.push(resolveLabelKey(t.value, f.labelKey));
            }
        } else if (f.type === 'command' && isRunCommand.value && f.name === 'templateId') {
            const v = (props.action as unknown as Record<string, unknown>).templateId;
            if (typeof v !== 'string' || v.trim() === '') {
                out.push(resolveLabelKey(t.value, f.labelKey));
            }
        }
    }
    return out;
});

/** 是否显示"待选择"角标（有空的必填引用字段且未被试跑高亮占用时）。 */
const showNeedSelect = computed(() => missingRefFields.value.length > 0);

/** 角标 hover 提示文案（"还需选择：元素 / 时间轴"）。 */
const needSelectTitle = computed(() =>
    t.value.script.param.needSelect.replace('{field}', missingRefFields.value.join(' / ')),
);
</script>

<template>
  <div
    class="hc-block-node"
    :class="[isIf ? 'hc-block-node-if' : '', highlightResult ? 'hc-block-node-hl' : '']"
    :data-block-path="path"
    :data-hl-result="highlightResult || null"
    :title="highlightDetail || undefined"
    :style="{ '--hc-block-color': effectiveBorderColor, ...highlightStyle }"
    @pointerdown="onBlockPointerDown"
  >
    <!-- 视觉：必填引用字段（元素 / 时间轴 / 命令）还没填时的温和橙色待选角标 -->
    <span
      v-if="showNeedSelect"
      class="hc-block-need-select"
      :title="needSelectTitle"
      aria-hidden="true"
    />

    <!-- 头部：标题 + 标量参数槽（F：真表单控件 BlockParamInput） -->
    <div class="hc-block-head">
      <span class="hc-block-title">{{ title }}</span>
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

    <!-- if 块 C 形：条件 + then/else 子序列槽（实色左臂实体包裹，非虚线引导线） -->
    <template v-if="isIf">
      <div v-if="conditionField" class="hc-block-condition">
        <span class="hc-param-label">{{ fieldLabel(conditionField) }}</span>
        <ConditionBuilder
          :condition="conditionText"
          :wall-id="project.wallId"
          @update="onConditionUpdate"
        />
      </div>

      <!-- then 子槽（实色 C 臂 + 实体托底，子积木嵌在臂内） -->
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

      <!-- C 形底托：闭合左臂，视觉上把 then/else 包成 C（实体托底条） -->
      <div class="hc-block-if-foot" aria-hidden="true" />
    </template>
  </div>
</template>

<style scoped>
/*
 * 0.7.0-P5（视觉）：Scratch 风积木块。
 *
 * 设计要点：
 *   - 实色块：背景直填分类色 --hc-block-color（由模板内联给，= trace 高亮色或分类色），
 *     文字走 --hc-block-fg（浅主题白 / 深主题深），对比清晰；不再 color-mix 淡化成 IDE 风。
 *   - 咬合感：::before 在块顶画一个小凸榫（Scratch 块顶的凸起），配合 BlockStack 里 gap≈0
 *     的焊接堆叠，凸榫"插进"上一块底部，连成一摞。
 *   - 立体感：轻微 box-shadow（块在画布上浮起一点）+ 顶部高光内描边。
 */

/* 块顶凸榫尺寸（与 BlockStack 焊接堆叠的负 margin 协调） */
.hc-block-node {
    --hc-notch-w: 18px;
    --hc-notch-h: 5px;
    --hc-notch-x: 14px;
    position: relative;
    border-radius: 7px;
    /* 实色块：分类色直填（模板给 --hc-block-color）；缺省退边框灰 */
    background: var(--hc-block-color, var(--border));
    color: var(--hc-block-fg);
    /* 立体：底部投影 + 顶部内高光（让块面有体积感） */
    box-shadow:
        0 2px 0 color-mix(in srgb, var(--hc-block-color, var(--border)) 62%, #000),
        0 3px 6px rgba(0, 0, 0, 0.22),
        inset 0 1px 0 color-mix(in srgb, #fff 26%, transparent);
    padding: 7px 10px 8px 11px;
    user-select: none;
    /* 块可拖动重排 / 跨堆 / 入 if 槽 */
    cursor: grab;
    /* H：试跑高亮描边 / 背景色切换，步进时柔和过渡 */
    transition: box-shadow 0.12s ease, background-color 0.12s ease, filter 0.12s ease;
}
/* 块顶凸榫：一个小圆角凸起，叠在上一块底部缝里，造"咬合"观感 */
.hc-block-node::before {
    content: "";
    position: absolute;
    top: calc(var(--hc-notch-h) * -1 + 1px);
    left: var(--hc-notch-x);
    width: var(--hc-notch-w);
    height: var(--hc-notch-h);
    background: var(--hc-block-color, var(--border));
    border-radius: 3px 3px 0 0;
    box-shadow: inset 0 1px 0 color-mix(in srgb, #fff 22%, transparent);
}
.hc-block-node:hover {
    filter: brightness(1.05);
}
.hc-block-node:active {
    cursor: grabbing;
}
.hc-block-head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 7px;
}
.hc-block-title {
    font-size: 12.5px;
    font-weight: 700;
    white-space: nowrap;
    color: var(--hc-block-fg);
    /* 让标题在饱和块面上更立体（细微暗描边） */
    text-shadow: 0 1px 0 color-mix(in srgb, var(--hc-block-color, var(--border)) 55%, #000);
}
/* F：参数控件（BlockParamInput）外壳——不再加浅底块，胶囊样式由控件自身承载；
 * 这里只保证内联排版 + 不撑破块宽。 */
.hc-block-param-input {
    display: inline-flex;
    align-items: center;
    max-width: 100%;
}
/* runCommand 复合 command 控件：占整行（模板下拉 + 动态 params 子表单纵向铺开）。 */
.hc-block-command {
    margin-top: 6px;
    width: 100%;
}
/* 块面上的字段标签（如 if 条件 / then / else 前缀）走块前景色的柔和版 */
.hc-param-label {
    color: var(--hc-block-fg-soft);
    white-space: nowrap;
}
.hc-param-value {
    color: var(--hc-block-fg);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

/* ---------- 待选择角标（必填引用字段未填）---------- */
.hc-block-need-select {
    position: absolute;
    top: -4px;
    right: -4px;
    width: 11px;
    height: 11px;
    border-radius: 50%;
    background: var(--ctp-peach, #f59e0b);
    border: 1.5px solid var(--card);
    box-shadow: 0 1px 2px rgba(0, 0, 0, 0.3);
    z-index: 3;
    cursor: help;
}

/* ---------- if = 实体 C 形 ---------- */
/* if 块整体：底部不收圆角（由底托闭合），padding 左侧让出实色臂宽 */
.hc-block-node-if {
    padding-bottom: 0;
    border-bottom-left-radius: 0;
    border-bottom-right-radius: 0;
}
.hc-block-condition {
    margin-top: 6px;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 5px;
    font-size: 11px;
}
.hc-block-branch {
    margin-top: 6px;
}
.hc-branch-label {
    display: inline-block;
    font-size: 10px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--hc-block-fg-soft);
    margin-bottom: 4px;
}
/* C 臂：实色竖条把 then/else 子序列实体包进去（非虚线引导线）。
 * 左侧负 margin 把臂铺到块左边缘（抵消块的 11px 左 padding），右侧抵消 10px 右 padding；
 * 子积木相对臂内缩 18px，左边露出的实色即 C 的左臂。 */
.hc-branch-slot {
    position: relative;
    display: flex;
    flex-direction: column;
    /* 子积木焊接堆叠：小缝 + 块顶凸榫(::before)桥接，读作"咬在一起" */
    gap: 3px;
    margin-left: -11px;
    margin-right: -10px;
    padding: 4px 8px 6px 18px;
    /* 实色左臂 + 顶部一小段条，形成 C 的内拐角 */
    background: var(--hc-block-color, var(--border));
    box-shadow: inset 0 1px 0 color-mix(in srgb, #000 14%, transparent);
    min-height: 26px;
}
/* C 形底托：实色横条闭合左臂，整体收下圆角 */
.hc-block-if-foot {
    height: 12px;
    margin-left: -11px;
    margin-right: -10px;
    background: var(--hc-block-color, var(--border));
    border-radius: 0 0 7px 7px;
    box-shadow:
        0 2px 0 color-mix(in srgb, var(--hc-block-color, var(--border)) 62%, #000),
        0 3px 6px rgba(0, 0, 0, 0.22);
}
.hc-empty-slot {
    font-size: 11px;
    color: var(--hc-block-fg-soft);
    font-style: italic;
    padding: 5px 8px;
    /* 浅色凹槽提示空槽（半透明深底 + 内阴影，像块里"挖空"了一格） */
    background: var(--hc-block-slot);
    border: 1px dashed color-mix(in srgb, var(--hc-block-fg) 35%, transparent);
    border-radius: 5px;
    box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.25);
}
</style>
