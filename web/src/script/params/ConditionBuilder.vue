<script setup lang="ts">
/**
 * 0.7.0-P5-G（K-UI-6）：if 积木 condition 字段的可视构建器 + 高级文本框 fallback。
 *
 * <p><b>双模式</b>：</p>
 * <ul>
 *   <li><b>可视模式</b>（普通用户）：逐行「[操作数] [比较符] [操作数]」拼条件，同一 {@code joiner}
 *       （且 / 或）连所有行；底部「+ 加条件」加行、每行删除按钮。操作数 = 类型下拉（变量 / 数字 /
 *       文本 / 对错）+ 对应输入（变量用 {@link VariablePicker} 按钮 + Teleport fixed 浮层，照
 *       {@code BlockParamInput} 的 memory 约束）。任意改动 → {@link buildConditionString} → emit。</li>
 *   <li><b>高级模式</b>：直接编辑 condition 字符串（复杂表达式：算术 / 取反 / 嵌套括号等超出简化
 *       文法的形态）。「试着切回可视」用 {@link tryParseCondition} 反解析——成功切可视，失败 toast
 *       「表达式较复杂，继续用文本框」。</li>
 * </ul>
 *
 * <p><b>打开时的模式判定</b>（mount / props.condition 外部变化时）：</p>
 * <ol>
 *   <li>{@code tryParseCondition(props.condition)} 成功 → 可视模式（渲染解析出的行）；</li>
 *   <li>失败<b>且</b> props.condition 非空 → 高级模式（显原串，让用户继续编辑复杂表达式）；</li>
 *   <li>空串 → 可视模式 + 默认一行（{@link defaultCondition}）。</li>
 * </ol>
 *
 * <p><b>本地模型 vs emit</b>：可视模式持一份本地 {@link SimpleCondition}（{@code local}），用户改它
 * → build 成串 emit；不直接编辑 props（单向数据流）。高级模式持一份本地草稿串 {@code rawDraft}。
 * 外部 props.condition 变化（如他人改 / undo）时，若与本地 build 出的串不一致才重新初始化模式
 * （避免自己 emit 的回声反复重置导致光标 / 模式抖动）。</p>
 *
 * <p>lock（{@code project.isLocked}）下所有控件 disabled——锁定的墙只读。</p>
 */
import { computed, onMounted, ref, watch } from 'vue';
import VariablePicker from '@/components/variables/VariablePicker.vue';
import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import { useI18n } from '@/i18n';
import {
    buildConditionString,
    tryParseCondition,
    defaultCondition,
    defaultOperand,
    CMP_OPS,
    type SimpleCondition,
    type ConditionRow,
    type Operand,
    type OperandKind,
    type CmpOp,
    type Joiner,
} from '../model/condition';

const props = defineProps<{
    /** 当前 condition 字符串（来自 action.condition）。 */
    condition: string;
    /** 所属墙 id（变量 picker 用）。 */
    wallId: string | null;
}>();

const emit = defineEmits<{
    /** condition 串变更（可视模式 build 出 / 高级模式原串）。 */
    update: [condition: string];
}>();

const project = useProjectStore();
const net = useNetworkStore();
const { t } = useI18n();

/** 锁定态：true 时所有控件 disabled。 */
const disabled = computed(() => project.isLocked);

// ============================================================================
// 模式 + 本地模型
// ============================================================================

/** 'visual'=可视行编辑；'advanced'=纯文本框。 */
const mode = ref<'visual' | 'advanced'>('visual');
/** 可视模式本地条件模型。 */
const local = ref<SimpleCondition>(defaultCondition());
/** 高级模式本地草稿串。 */
const rawDraft = ref('');

/**
 * 按 props.condition 初始化模式 + 本地态。tryParse 成功 → 可视（用解析结果）；失败且非空 →
 * 高级（显原串）；空 → 可视默认一行。
 */
function initFromProps(): void {
    const raw = props.condition ?? '';
    const parsed = tryParseCondition(raw);
    if (parsed !== null) {
        local.value = parsed;
        mode.value = 'visual';
        return;
    }
    if (raw.trim() !== '') {
        rawDraft.value = raw;
        mode.value = 'advanced';
        return;
    }
    // 空条件：可视模式默认一行（不立即 emit——等用户真改了再写）。
    local.value = defaultCondition();
    mode.value = 'visual';
}

onMounted(initFromProps);

/**
 * 外部 props.condition 变化时按需重新初始化：仅当新值与「本地当前 build 出的串」不同才重置
 * （否则自己 emit 的回声会反复打断用户编辑 / 重置模式）。
 */
watch(
    () => props.condition,
    (next) => {
        const localStr = mode.value === 'visual' ? buildConditionString(local.value) : rawDraft.value;
        if ((next ?? '') === localStr) return; // 自身回声，忽略
        initFromProps();
    },
);

/** 可视模型改动后统一 emit（build 成串）。 */
function emitVisual(): void {
    emit('update', buildConditionString(local.value));
}

// ============================================================================
// 可视模式：行 / 操作数编辑
// ============================================================================

/** 操作数类型下拉选项（label 走 i18n）。 */
const OPERAND_KINDS: readonly { kind: OperandKind; labelKey: string }[] = [
    { kind: 'var', labelKey: 'kindVar' },
    { kind: 'number', labelKey: 'kindNumber' },
    { kind: 'string', labelKey: 'kindString' },
    { kind: 'bool', labelKey: 'kindBool' },
];

const cmpOps: readonly CmpOp[] = CMP_OPS;

/** 加一行（复制默认行）。 */
function addRow(): void {
    if (disabled.value) return;
    local.value = { ...local.value, rows: [...local.value.rows, defaultOperand0Row()] };
    emitVisual();
}

/** 默认新行（与 condition.defaultRow 一致：var("") == ""）。 */
function defaultOperand0Row(): ConditionRow {
    return { lhs: defaultOperand('var'), op: '==', rhs: defaultOperand('string') };
}

/** 删某行（至少保留一行——空 rows build 成空串会被 validator 拒；删到 0 行不允许）。 */
function removeRow(idx: number): void {
    if (disabled.value) return;
    if (local.value.rows.length <= 1) return;
    const rows = local.value.rows.slice();
    rows.splice(idx, 1);
    local.value = { ...local.value, rows };
    emitVisual();
}

/** 改 joiner（且 / 或）。 */
function setJoiner(j: Joiner): void {
    if (disabled.value) return;
    if (local.value.joiner === j) return;
    local.value = { ...local.value, joiner: j };
    emitVisual();
}

/** 改某行比较符。 */
function onCmpChange(idx: number, e: Event): void {
    const op = (e.target as HTMLSelectElement).value as CmpOp;
    const rows = local.value.rows.slice();
    rows[idx] = { ...rows[idx], op };
    local.value = { ...local.value, rows };
    emitVisual();
}

/** 改某行某侧操作数的类型（切类型 → 换默认值）。 */
function onOperandKindChange(idx: number, side: 'lhs' | 'rhs', e: Event): void {
    const kind = (e.target as HTMLSelectElement).value as OperandKind;
    setOperand(idx, side, defaultOperand(kind));
}

/** 整体替换某行某侧操作数 + emit。 */
function setOperand(idx: number, side: 'lhs' | 'rhs', operand: Operand): void {
    if (disabled.value) return;
    const rows = local.value.rows.slice();
    rows[idx] = { ...rows[idx], [side]: operand };
    local.value = { ...local.value, rows };
    emitVisual();
}

/** number 操作数输入（空 / 非有限不写，保留旧值）。 */
function onNumberInput(idx: number, side: 'lhs' | 'rhs', e: Event): void {
    const raw = (e.target as HTMLInputElement).value;
    if (raw.trim() === '') return;
    const n = Number(raw);
    if (!Number.isFinite(n)) return;
    setOperand(idx, side, { kind: 'number', value: n });
}

/** string 操作数输入。 */
function onStringInput(idx: number, side: 'lhs' | 'rhs', e: Event): void {
    setOperand(idx, side, { kind: 'string', value: (e.target as HTMLInputElement).value });
}

/** bool 操作数切换。 */
function onBoolChange(idx: number, side: 'lhs' | 'rhs', e: Event): void {
    const value = (e.target as HTMLSelectElement).value === 'true';
    setOperand(idx, side, { kind: 'bool', value });
}

// ----- 受控显示辅助（按 operand kind 取当前值） -----
function operandKind(row: ConditionRow, side: 'lhs' | 'rhs'): OperandKind {
    return row[side].kind;
}
function numberOf(row: ConditionRow, side: 'lhs' | 'rhs'): number | '' {
    const o = row[side];
    return o.kind === 'number' ? o.value : '';
}
function stringOf(row: ConditionRow, side: 'lhs' | 'rhs'): string {
    const o = row[side];
    return o.kind === 'string' ? o.value : '';
}
function boolOf(row: ConditionRow, side: 'lhs' | 'rhs'): string {
    const o = row[side];
    return o.kind === 'bool' && o.value ? 'true' : 'false';
}
function varNameOf(row: ConditionRow, side: 'lhs' | 'rhs'): string {
    const o = row[side];
    return o.kind === 'var' ? o.name : '';
}

// ============================================================================
// 变量 picker（Teleport fixed 浮层；照 BlockParamInput memory 约束）
// ============================================================================

/** 当前打开 picker 的目标（行下标 + 侧）；null = 没开。 */
const pickerTarget = ref<{ idx: number; side: 'lhs' | 'rhs' } | null>(null);
const pickerStyle = ref<Record<string, string>>({});

/**
 * 打开变量 picker。memory 约束：① 必须按钮 click 打开（非 select-change）；② Teleport 到 body
 * + 用按钮屏幕坐标 fixed 定位，绕开 overlay 的 world transform / overflow 裁切，picker 向下展开。
 */
function openPicker(idx: number, side: 'lhs' | 'rhs', e: MouseEvent): void {
    if (disabled.value) return;
    const btn = (e.currentTarget as HTMLElement) ?? null;
    if (btn) {
        const r = btn.getBoundingClientRect();
        pickerStyle.value = {
            position: 'fixed',
            left: `${r.left}px`,
            top: `${r.bottom}px`,
            width: `${Math.max(r.width, 240)}px`,
            zIndex: '120',
        };
    }
    pickerTarget.value = { idx, side };
}

function onPickerSelect(fullName: string): void {
    const tgt = pickerTarget.value;
    if (tgt) setOperand(tgt.idx, tgt.side, { kind: 'var', name: fullName });
    pickerTarget.value = null;
}
function onPickerClose(): void {
    pickerTarget.value = null;
}

// ============================================================================
// 高级模式
// ============================================================================

/** 切到高级模式（把当前可视模型 build 成串作为草稿起点）。 */
function switchToAdvanced(): void {
    if (disabled.value) return;
    rawDraft.value = buildConditionString(local.value);
    mode.value = 'advanced';
}

/** 高级 textarea 输入 → 直接 emit 原串。 */
function onRawInput(e: Event): void {
    rawDraft.value = (e.target as HTMLTextAreaElement).value;
    emit('update', rawDraft.value);
}

/**
 * 「试着切回可视」：tryParse 当前草稿串。成功 → 切可视（用解析结果）；失败 → toast 提示继续用
 * 文本框（不切，不改 emit）。
 */
function tryBackToVisual(): void {
    if (disabled.value) return;
    const parsed = tryParseCondition(rawDraft.value);
    if (parsed === null) {
        net.lastError = t.value.script.condition.tooComplex;
        return;
    }
    local.value = parsed;
    mode.value = 'visual';
    // 切回可视后用规范化的 build 串 emit（让存储串与可视模型一致）。
    emitVisual();
}
</script>

<template>
  <div class="hc-cond" :class="{ 'hc-cond-disabled': disabled }">
    <!-- 可视模式 -->
    <template v-if="mode === 'visual'">
      <div
        v-for="(r, idx) in local.rows"
        :key="idx"
        class="hc-cond-row"
      >
        <!-- 行间 joiner 标签（首行不显示；显示「且 / 或」当前值，仅展示，切换在底部） -->
        <span v-if="idx > 0" class="hc-cond-joiner-tag">
          {{ local.joiner === '&&' ? t.script.condition.joinAnd : t.script.condition.joinOr }}
        </span>

        <!-- lhs 操作数 -->
        <span class="hc-cond-operand">
          <select
            class="hc-cond-kind"
            :value="operandKind(r, 'lhs')"
            :disabled="disabled"
            @change="(e) => onOperandKindChange(idx, 'lhs', e)"
          >
            <option v-for="k in OPERAND_KINDS" :key="k.kind" :value="k.kind">
              {{ t.script.condition[k.labelKey as keyof typeof t.script.condition] }}
            </option>
          </select>
          <!-- 变量按钮 -->
          <button
            v-if="operandKind(r, 'lhs') === 'var'"
            type="button"
            class="hc-cond-var-btn"
            :class="{ 'hc-cond-var-empty': !varNameOf(r, 'lhs') }"
            :disabled="disabled"
            @click="(e) => openPicker(idx, 'lhs', e)"
          >{{ varNameOf(r, 'lhs') || t.script.condition.pickVariable }}</button>
          <!-- 数字 -->
          <input
            v-else-if="operandKind(r, 'lhs') === 'number'"
            class="hc-cond-input hc-cond-num"
            type="number"
            :value="numberOf(r, 'lhs')"
            :disabled="disabled"
            @input="(e) => onNumberInput(idx, 'lhs', e)"
          >
          <!-- 文本 -->
          <input
            v-else-if="operandKind(r, 'lhs') === 'string'"
            class="hc-cond-input"
            type="text"
            :value="stringOf(r, 'lhs')"
            :disabled="disabled"
            @input="(e) => onStringInput(idx, 'lhs', e)"
          >
          <!-- 对错 -->
          <select
            v-else
            class="hc-cond-input hc-cond-bool"
            :value="boolOf(r, 'lhs')"
            :disabled="disabled"
            @change="(e) => onBoolChange(idx, 'lhs', e)"
          >
            <option value="true">{{ t.script.condition.boolTrue }}</option>
            <option value="false">{{ t.script.condition.boolFalse }}</option>
          </select>
        </span>

        <!-- 比较符 -->
        <select
          class="hc-cond-cmp"
          :value="r.op"
          :disabled="disabled"
          @change="(e) => onCmpChange(idx, e)"
        >
          <option v-for="op in cmpOps" :key="op" :value="op">{{ op }}</option>
        </select>

        <!-- rhs 操作数 -->
        <span class="hc-cond-operand">
          <select
            class="hc-cond-kind"
            :value="operandKind(r, 'rhs')"
            :disabled="disabled"
            @change="(e) => onOperandKindChange(idx, 'rhs', e)"
          >
            <option v-for="k in OPERAND_KINDS" :key="k.kind" :value="k.kind">
              {{ t.script.condition[k.labelKey as keyof typeof t.script.condition] }}
            </option>
          </select>
          <button
            v-if="operandKind(r, 'rhs') === 'var'"
            type="button"
            class="hc-cond-var-btn"
            :class="{ 'hc-cond-var-empty': !varNameOf(r, 'rhs') }"
            :disabled="disabled"
            @click="(e) => openPicker(idx, 'rhs', e)"
          >{{ varNameOf(r, 'rhs') || t.script.condition.pickVariable }}</button>
          <input
            v-else-if="operandKind(r, 'rhs') === 'number'"
            class="hc-cond-input hc-cond-num"
            type="number"
            :value="numberOf(r, 'rhs')"
            :disabled="disabled"
            @input="(e) => onNumberInput(idx, 'rhs', e)"
          >
          <input
            v-else-if="operandKind(r, 'rhs') === 'string'"
            class="hc-cond-input"
            type="text"
            :value="stringOf(r, 'rhs')"
            :disabled="disabled"
            @input="(e) => onStringInput(idx, 'rhs', e)"
          >
          <select
            v-else
            class="hc-cond-input hc-cond-bool"
            :value="boolOf(r, 'rhs')"
            :disabled="disabled"
            @change="(e) => onBoolChange(idx, 'rhs', e)"
          >
            <option value="true">{{ t.script.condition.boolTrue }}</option>
            <option value="false">{{ t.script.condition.boolFalse }}</option>
          </select>
        </span>

        <!-- 删行（仅 >1 行时可删） -->
        <button
          v-if="local.rows.length > 1"
          type="button"
          class="hc-cond-del"
          :title="t.script.condition.removeRow"
          :disabled="disabled"
          @click="removeRow(idx)"
        >×</button>
      </div>

      <!-- 底部工具条：加条件 + joiner 切换 + 切高级 -->
      <div class="hc-cond-toolbar">
        <button
          type="button"
          class="hc-cond-add"
          :disabled="disabled"
          @click="addRow"
        >+ {{ t.script.condition.addRow }}</button>

        <span v-if="local.rows.length > 1" class="hc-cond-joiner-switch">
          <button
            type="button"
            :class="{ active: local.joiner === '&&' }"
            :disabled="disabled"
            @click="setJoiner('&&')"
          >{{ t.script.condition.joinAnd }}</button>
          <button
            type="button"
            :class="{ active: local.joiner === '||' }"
            :disabled="disabled"
            @click="setJoiner('||')"
          >{{ t.script.condition.joinOr }}</button>
        </span>

        <button
          type="button"
          class="hc-cond-advanced-link"
          :disabled="disabled"
          @click="switchToAdvanced"
        >{{ t.script.condition.toAdvanced }}</button>
      </div>
    </template>

    <!-- 高级模式 -->
    <template v-else>
      <textarea
        class="hc-cond-raw"
        :value="rawDraft"
        :disabled="disabled"
        :placeholder="t.script.condition.advancedPlaceholder"
        rows="2"
        spellcheck="false"
        @input="onRawInput"
      />
      <div class="hc-cond-toolbar">
        <span class="hc-cond-advanced-hint">{{ t.script.condition.advancedHint }}</span>
        <button
          type="button"
          class="hc-cond-advanced-link"
          :disabled="disabled"
          @click="tryBackToVisual"
        >{{ t.script.condition.tryVisual }}</button>
      </div>
    </template>

    <!-- 变量 picker：Teleport 到 body + fixed 浮层（memory 约束） -->
    <Teleport to="body">
      <div v-if="pickerTarget" class="hc-cond-picker-host" :style="pickerStyle">
        <VariablePicker
          :wall-id="wallId"
          @select="onPickerSelect"
          @close="onPickerClose"
        />
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.hc-cond {
    display: flex;
    flex-direction: column;
    gap: 4px;
    font-size: 11px;
    width: 100%;
}
.hc-cond-disabled {
    opacity: 0.7;
}
.hc-cond-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 4px;
}
.hc-cond-joiner-tag {
    font-size: 10px;
    font-weight: 600;
    color: var(--ctp-mauve, var(--muted-foreground));
    text-transform: uppercase;
    letter-spacing: 0.03em;
    margin-right: 2px;
}
.hc-cond-operand {
    display: inline-flex;
    align-items: center;
    gap: 3px;
}
.hc-cond-kind,
.hc-cond-cmp,
.hc-cond-input {
    font-size: 11px;
    padding: 2px 5px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: var(--background);
    color: var(--foreground);
    outline: none;
}
.hc-cond-kind {
    cursor: pointer;
    color: var(--muted-foreground);
}
.hc-cond-cmp {
    cursor: pointer;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-weight: 600;
}
.hc-cond-input:focus,
.hc-cond-kind:focus,
.hc-cond-cmp:focus {
    border-color: var(--ring);
    box-shadow: 0 0 0 1px var(--ring);
}
.hc-cond-input:disabled,
.hc-cond-kind:disabled,
.hc-cond-cmp:disabled {
    opacity: 0.5;
    cursor: not-allowed;
}
.hc-cond-num {
    width: 72px;
}
.hc-cond-bool {
    cursor: pointer;
}
.hc-cond-var-btn {
    font-size: 11px;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    padding: 2px 8px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: var(--background);
    color: var(--foreground);
    cursor: pointer;
    max-width: 150px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.hc-cond-var-btn:hover:not(:disabled) {
    border-color: var(--ring);
    background: var(--accent);
}
.hc-cond-var-btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
}
.hc-cond-var-empty {
    color: var(--muted-foreground);
    font-style: italic;
    font-family: inherit;
}
.hc-cond-del {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: var(--background);
    color: var(--muted-foreground);
    cursor: pointer;
    font-size: 13px;
    line-height: 1;
}
.hc-cond-del:hover:not(:disabled) {
    color: var(--destructive);
    border-color: var(--destructive);
}

/* 工具条 */
.hc-cond-toolbar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin-top: 2px;
}
.hc-cond-add {
    font-size: 11px;
    padding: 2px 8px;
    border: 1px dashed var(--border);
    border-radius: 4px;
    background: transparent;
    color: var(--foreground);
    cursor: pointer;
}
.hc-cond-add:hover:not(:disabled) {
    border-color: var(--ring);
    background: var(--accent);
}
.hc-cond-joiner-switch {
    display: inline-flex;
    gap: 0;
    border: 1px solid var(--border);
    border-radius: 4px;
    overflow: hidden;
}
.hc-cond-joiner-switch button {
    font-size: 11px;
    padding: 2px 10px;
    border: none;
    background: var(--background);
    color: var(--muted-foreground);
    cursor: pointer;
}
.hc-cond-joiner-switch button.active {
    background: var(--ctp-mauve, var(--accent));
    color: var(--background);
    font-weight: 600;
}
.hc-cond-joiner-switch button:not(:last-child) {
    border-right: 1px solid var(--border);
}
.hc-cond-advanced-link {
    font-size: 11px;
    padding: 2px 6px;
    border: none;
    background: transparent;
    color: var(--ctp-blue, var(--ring));
    cursor: pointer;
    text-decoration: underline;
    margin-left: auto;
}
.hc-cond-advanced-link:hover:not(:disabled) {
    color: var(--foreground);
}
.hc-cond-advanced-link:disabled {
    opacity: 0.5;
    cursor: not-allowed;
}
.hc-cond-advanced-hint {
    font-size: 10px;
    color: var(--muted-foreground);
    font-style: italic;
}

/* 高级 textarea */
.hc-cond-raw {
    width: 100%;
    font-size: 11px;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    padding: 4px 6px;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: var(--background);
    color: var(--foreground);
    outline: none;
    resize: vertical;
    min-height: 36px;
}
.hc-cond-raw:focus {
    border-color: var(--ring);
    box-shadow: 0 0 0 1px var(--ring);
}
.hc-cond-raw:disabled {
    opacity: 0.5;
    cursor: not-allowed;
}

/* picker host（Teleport 到 body）：定位上下文，picker 内部 absolute 下拉 */
.hc-cond-picker-host {
    position: fixed;
}
</style>
