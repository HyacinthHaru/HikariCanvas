<script setup lang="ts">
/**
 * 积木参数通用表单控件。
 *
 * <p>按 {@link FieldDef.type} 渲染对应控件，改值经 {@code update} 事件回写（由 BlockNode 接到
 * {@code edit.updateActionField}）。<b>condition / statements 两类字段不走本组件</b>——condition
 * 由 ConditionBuilder 接，statements 是 BlockNode 递归子槽。</p>
 *
 * <h2>各类型 → 控件 + emit 值形态（务必与 wire 类型一致）</h2>
 *
 * <ul>
 *   <li>{@code number}：{@code <input type=number>}（min/max/step 来自 FieldDef，镜像后端
 *       validator 范围）→ emit <b>number</b>（空 / 非法不 emit，保留旧值）；</li>
 *   <li>{@code text}：{@code <input type=text>} → emit <b>string</b>；</li>
 *   <li>{@code select} / {@code op} / {@code scope}：下拉 over {@code options} → emit <b>string</b>；</li>
 *   <li>{@code variable}：「选变量」按钮显当前 fullName → 点开 {@link VariablePicker}
 *       （Teleport 到 body + fixed 浮层，绕开 world transform/overflow 裁切，遵 memory 约束）→
 *       select 回填 emit <b>string</b>；可清空（emit {@code ''}）；</li>
 *   <li>{@code timeline}：下拉 over {@code project.state.timelines} → emit <b>string</b>（timeline id）；</li>
 *   <li>{@code element}：下拉 over {@code project.allElements} → emit <b>string</b>（element id）；</li>
 *   <li>{@code sound}：{@code <input list>} + datalist（{@link SOUND_SUGGESTIONS} ~24 常用声音）→
 *       emit <b>string</b>（用户也可填任意 Registry id）；</li>
 *   <li>{@code command}：<b>复合字段</b>——模板下拉 + 选中后按模板 params 动态渲染子输入。
 *       runCommand 有 templateId + params 两个 wire 字段，本控件整体处理：emit
 *       <b>{@code { templateId: string, params: Record<string,string> }}</b>（BlockNode 合并回 action）。</li>
 * </ul>
 *
 * <p>lock 态（{@code project.isLocked}）由 BlockNode 经 {@code disabled} prop 透传——所有控件
 * 禁用（锁定的墙只读）。</p>
 */
import { computed, onMounted, ref } from 'vue';
import VariablePicker from '@/components/variables/VariablePicker.vue';
import { useProjectStore } from '@/stores/project';
import { useI18n } from '@/i18n';
import { resolveLabelKey } from '../canvas/labelKey';
import type { FieldDef } from '../model/blockDefs';
import {
    SOUND_SUGGESTIONS,
    soundLabelOf,
    type SoundSuggestion,
} from './soundSuggestions';
import { useCommandTemplates, type CommandTemplate } from './useCommandTemplates';

/** command 类型的 emit 复合值（runCommand 的 templateId + params 一起回写）。 */
export interface CommandValue {
    templateId: string;
    params: Record<string, string>;
}

const props = defineProps<{
    /** 字段声明（type 决定渲染哪个控件）。 */
    field: FieldDef;
    /**
     * 当前值。多数类型是标量（string / number）；command 类型应传整个 action 的
     * {@code { templateId, params }}（BlockNode 对 runCommand 特殊传入）。
     */
    value: unknown;
    /** 所属动作 kind（command 复合字段判定等用；目前仅传递不强依赖）。 */
    actionKind: string;
    /** 锁定态：true 时所有控件 disabled。 */
    disabled?: boolean;
}>();

const emit = defineEmits<{
    /** 值变更。command 类型 emit {@link CommandValue}；其余 emit string / number。 */
    update: [value: unknown];
}>();

const project = useProjectStore();
const { t } = useI18n();

/** 字段标签文案。 */
const label = computed(() => resolveLabelKey(t.value, props.field.labelKey));

// ============================================================================
// number
// ============================================================================

/** 当前数值（受控显示）；非 number 时为 null（input 显示空）。 */
const numberValue = computed(() => (typeof props.value === 'number' ? props.value : null));

/**
 * number input 改动：解析 + 取整（字段标了 {@code integer} 时）+ 钳位到 [min, max]。
 * 空 / 非有限数 → 不 emit（保留旧值，避免把字段写空）。
 *
 * <p><b>为什么要当场取整</b>：标了 {@code integer} 的字段（等待时长 / 重复次数 / 各种毫秒…）
 * 后端只收整数，手打一个 {@code 2.5} 会让整条脚本存不下，而且报错来自反序列化层、指不到是
 * 哪块积木。{@code step} 只管微调按钮的步长，手打的值浏览器不拦，所以在这里收口。
 * 小数字段（音量 / 音调 / 粒子偏移 …）不受影响。</p>
 */
function onNumberInput(e: Event): void {
    const raw = (e.target as HTMLInputElement).value;
    if (raw.trim() === '') return; // 空：不写（保留旧值）
    const n = Number(raw);
    if (!Number.isFinite(n)) return;
    const rounded = props.field.integer === true ? Math.round(n) : n;
    const clamped = clampNumber(rounded);
    emit('update', clamped);
}

function clampNumber(n: number): number {
    let v = n;
    if (typeof props.field.min === 'number' && v < props.field.min) v = props.field.min;
    if (typeof props.field.max === 'number' && v > props.field.max) v = props.field.max;
    return v;
}

// ============================================================================
// text
// ============================================================================

const textValue = computed(() => (typeof props.value === 'string' ? props.value : ''));
function onTextInput(e: Event): void {
    emit('update', (e.target as HTMLInputElement).value);
}

// ============================================================================
// select / op / scope（统一 options 下拉，emit string）
// ============================================================================

const selectValue = computed(() => (typeof props.value === 'string' ? props.value : ''));
function onSelectChange(e: Event): void {
    emit('update', (e.target as HTMLSelectElement).value);
}
function optionLabel(labelKey: string): string {
    return resolveLabelKey(t.value, labelKey);
}

// ============================================================================
// variable（VariablePicker 按钮 → Teleport fixed 浮层）
// ============================================================================

const variableValue = computed(() => (typeof props.value === 'string' ? props.value : ''));
/** picker 开关 + 触发按钮 ref（用于算 fixed 浮层位置）。 */
const pickerOpen = ref(false);
const varBtnRef = ref<HTMLElement | null>(null);
/** picker 浮层 fixed 定位（绕开 world transform / overflow:hidden 裁切）。 */
const pickerStyle = ref<Record<string, string>>({});

/**
 * 打开变量 picker。memory 约束：① 必须按钮 click 打开（不能 select-change，否则 picker
 * 的 onClickOutside 会被同一次点击立即触发关闭）；② picker 向下展开 + 在 overlay 里要确保
 * 浮层不被裁切——这里 Teleport 到 body + 用按钮屏幕坐标 fixed 定位 picker 容器。
 *
 * <p>{@code stopPropagation} 阻止这次"打开"click 继续冒泡到
 * 祖先（BlockStack.onStackClick / 画布 pan / 其它 picker 的 onClickOutside 窗口监听），杜绝同一次
 * 点击里"开了又被外层处理掉"。规则选中已提前在 BlockNode/BlockStack 的 pointerdown 完成，故这里
 * 截断 click 冒泡不影响进入编辑态。</p>
 */
function openPicker(e?: Event): void {
    if (props.disabled) return;
    e?.stopPropagation();
    const btn = varBtnRef.value;
    if (btn) {
        const r = btn.getBoundingClientRect();
        // picker 自身 CSS 是 top:calc(100%+4px) 的 absolute 下拉；故容器定到按钮左下，
        // 给容器一个相对定位上下文 + 与按钮等宽，picker 就会贴着按钮下方展开。
        pickerStyle.value = {
            position: 'fixed',
            left: `${r.left}px`,
            top: `${r.bottom}px`,
            width: `${Math.max(r.width, 240)}px`,
            zIndex: '120',
        };
    }
    pickerOpen.value = true;
}

function onPickerSelect(fullName: string): void {
    emit('update', fullName);
    pickerOpen.value = false;
}
function onPickerClose(): void {
    pickerOpen.value = false;
}
/** 清空变量绑定（emit 空串）。 */
function clearVariable(): void {
    if (props.disabled) return;
    emit('update', '');
}

// ============================================================================
// timeline（project.state.timelines 下拉）
// ============================================================================

const timelineValue = computed(() => (typeof props.value === 'string' ? props.value : ''));
const timelines = computed(() => project.state?.timelines ?? []);
function onTimelineChange(e: Event): void {
    emit('update', (e.target as HTMLSelectElement).value);
}

// ============================================================================
// element（project.allElements 下拉）
// ============================================================================

const elementValue = computed(() => (typeof props.value === 'string' ? props.value : ''));
const elements = computed(() => project.allElements);
function onElementChange(e: Event): void {
    emit('update', (e.target as HTMLSelectElement).value);
}

// ============================================================================
// sound（datalist 常用建议 + 任意输入）
// ============================================================================

const soundValue = computed(() => (typeof props.value === 'string' ? props.value : ''));
/** datalist id（页面内唯一即可；多个 sound 字段共用同一组建议）。 */
const SOUND_DATALIST_ID = 'hc-sound-suggest';
const soundSuggestions: readonly SoundSuggestion[] = SOUND_SUGGESTIONS;
function onSoundInput(e: Event): void {
    emit('update', (e.target as HTMLInputElement).value);
}
function soundOptionLabel(s: SoundSuggestion): string {
    return soundLabelOf(s, t.value);
}

// ============================================================================
// command（runCommand 复合字段：模板下拉 + 动态 params 子输入）
// ============================================================================

const cmd = useCommandTemplates();

/** 当前复合值（templateId + params）；非对象时退默认空。 */
const commandValue = computed<CommandValue>(() => {
    const v = props.value;
    if (v && typeof v === 'object') {
        const obj = v as Record<string, unknown>;
        const templateId = typeof obj.templateId === 'string' ? obj.templateId : '';
        const params =
            obj.params && typeof obj.params === 'object'
                ? (obj.params as Record<string, string>)
                : {};
        return { templateId, params };
    }
    return { templateId: '', params: {} };
});

/** 当前选中模板的规格（按 templateId 查 fetch 到的列表）；未选 / 未命中 → null。 */
const selectedTemplate = computed<CommandTemplate | null>(() => {
    const id = commandValue.value.templateId;
    if (!id) return null;
    return cmd.templates.value.find((tpl) => tpl.id === id) ?? null;
});

/**
 * 孤儿模板检测：templateId 非空 + 模板列表已加载 + 找不到匹配项时为 true。
 *
 * <p>服主删除 / 改名模板后，已保存的积木仍持有旧 templateId。原生 select 显示空白但
 * 底层值未清除，前端 validator 只判 templateId 非空就放行，导致积木悄悄引用无效模板。
 * 检测到孤儿时在 UI 显示红色警告，让用户能发现并修正。</p>
 */
const isOrphanTemplate = computed<boolean>(() => {
    const id = commandValue.value.templateId;
    // 只在 id 非空 + 列表已加载 + 列表中找不到时报孤儿（避免列表尚未 fetch 时误报）
    return !!id && cmd.templates.value.length > 0 && selectedTemplate.value === null;
});

/** 切模板：保留与新模板 param 名交集的旧值，丢弃其余（避免残留无关参数）。 */
function onCommandTemplateChange(e: Event): void {
    const newId = (e.target as HTMLSelectElement).value;
    const tpl = cmd.templates.value.find((x) => x.id === newId) ?? null;
    const oldParams = commandValue.value.params;
    const nextParams: Record<string, string> = {};
    if (tpl) {
        for (const p of tpl.params) {
            // 沿用同名旧值（改模板但参数名相同 → 不清用户已填内容）；否则空串起步。
            nextParams[p.name] = typeof oldParams[p.name] === 'string' ? oldParams[p.name] : '';
        }
    }
    emit('update', { templateId: newId, params: nextParams } satisfies CommandValue);
}

/** 改某个 param 子输入：immutable 重建 params + 整体 emit 复合值。 */
function onCommandParamInput(paramName: string, e: Event): void {
    const raw = (e.target as HTMLInputElement).value;
    const nextParams = { ...commandValue.value.params, [paramName]: raw };
    emit('update', {
        templateId: commandValue.value.templateId,
        params: nextParams,
    } satisfies CommandValue);
}

/** 取某 param 当前值（受控 input）。 */
function commandParamVal(paramName: string): string {
    const v = commandValue.value.params[paramName];
    return typeof v === 'string' ? v : '';
}

onMounted(() => {
    // command 字段挂载时拉模板（命中模块缓存则复用，不重复请求）。
    if (props.field.type === 'command') {
        void cmd.load();
    }
});
</script>

<template>
  <!-- number -->
  <label v-if="field.type === 'number'" class="hc-pf hc-pf-inline">
    <span class="hc-pf-label">{{ label }}</span>
    <input
      class="hc-pf-input hc-pf-number"
      type="number"
      :value="numberValue ?? ''"
      :min="field.min"
      :max="field.max"
      :step="field.step"
      :disabled="disabled"
      @input="onNumberInput"
    >
  </label>

  <!-- text -->
  <label v-else-if="field.type === 'text'" class="hc-pf hc-pf-inline">
    <span class="hc-pf-label">{{ label }}</span>
    <input
      class="hc-pf-input"
      type="text"
      :value="textValue"
      :disabled="disabled"
      @input="onTextInput"
    >
  </label>

  <!-- select / op / scope -->
  <label
    v-else-if="field.type === 'select' || field.type === 'op' || field.type === 'scope'"
    class="hc-pf hc-pf-inline"
  >
    <span class="hc-pf-label">{{ label }}</span>
    <select
      class="hc-pf-input hc-pf-select"
      :value="selectValue"
      :disabled="disabled"
      @change="onSelectChange"
    >
      <option
        v-for="opt in field.options ?? []"
        :key="opt.value"
        :value="opt.value"
      >{{ optionLabel(opt.labelKey) }}</option>
    </select>
  </label>

  <!-- variable -->
  <div v-else-if="field.type === 'variable'" class="hc-pf hc-pf-inline">
    <span class="hc-pf-label">{{ label }}</span>
    <div class="hc-pf-var-row">
      <button
        ref="varBtnRef"
        type="button"
        class="hc-pf-var-btn"
        :class="{ 'hc-pf-var-empty': !variableValue }"
        :disabled="disabled"
        @click="openPicker($event)"
      >
        <span class="hc-pf-var-text">{{ variableValue || t.script.param.pickVariable }}</span>
      </button>
      <button
        v-if="variableValue"
        type="button"
        class="hc-pf-var-clear"
        :title="t.script.param.clearVariable"
        :disabled="disabled"
        @click="clearVariable"
      >×</button>
    </div>

    <!-- picker：Teleport 到 body + fixed 浮层，绕 world transform / overflow 裁切（memory 约束）。 -->
    <Teleport to="body">
      <div v-if="pickerOpen" class="hc-pf-picker-host" :style="pickerStyle">
        <VariablePicker
          :wall-id="project.wallId"
          @select="onPickerSelect"
          @close="onPickerClose"
        />
      </div>
    </Teleport>
  </div>

  <!-- timeline -->
  <label v-else-if="field.type === 'timeline'" class="hc-pf hc-pf-inline">
    <span class="hc-pf-label">{{ label }}</span>
    <select
      v-if="timelines.length > 0"
      class="hc-pf-input hc-pf-select"
      :value="timelineValue"
      :disabled="disabled"
      @change="onTimelineChange"
    >
      <option value="">{{ t.script.param.selectPlaceholder }}</option>
      <option v-for="tl in timelines" :key="tl.id" :value="tl.id">{{ tl.name || tl.id }}</option>
    </select>
    <span v-else class="hc-pf-empty">{{ t.script.param.noTimelines }}</span>
  </label>

  <!-- element -->
  <label v-else-if="field.type === 'element'" class="hc-pf hc-pf-inline">
    <span class="hc-pf-label">{{ label }}</span>
    <select
      v-if="elements.length > 0"
      class="hc-pf-input hc-pf-select"
      :value="elementValue"
      :disabled="disabled"
      @change="onElementChange"
    >
      <option value="">{{ t.script.param.selectPlaceholder }}</option>
      <option v-for="el in elements" :key="el.id" :value="el.id">{{ el.label }}</option>
    </select>
    <span v-else class="hc-pf-empty">{{ t.script.param.noElements }}</span>
  </label>

  <!-- sound -->
  <label v-else-if="field.type === 'sound'" class="hc-pf hc-pf-inline">
    <span class="hc-pf-label">{{ label }}</span>
    <input
      class="hc-pf-input"
      type="text"
      :value="soundValue"
      :list="SOUND_DATALIST_ID"
      :placeholder="t.script.param.soundPlaceholder"
      :disabled="disabled"
      @input="onSoundInput"
    >
    <datalist :id="SOUND_DATALIST_ID">
      <option
        v-for="s in soundSuggestions"
        :key="s.id"
        :value="s.id"
      >{{ soundOptionLabel(s) }}</option>
    </datalist>
  </label>

  <!-- command（复合：模板下拉 + 动态 params 子输入） -->
  <div v-else-if="field.type === 'command'" class="hc-pf hc-pf-command">
    <label class="hc-pf-inline">
      <span class="hc-pf-label">{{ label }}</span>
      <select
        v-if="cmd.templates.value.length > 0"
        class="hc-pf-input hc-pf-select"
        :value="commandValue.templateId"
        :disabled="disabled"
        @change="onCommandTemplateChange"
      >
        <option value="">{{ t.script.param.selectPlaceholder }}</option>
        <option
          v-for="tpl in cmd.templates.value"
          :key="tpl.id"
          :value="tpl.id"
        >{{ tpl.id }}</option>
      </select>
      <span v-else class="hc-pf-empty">{{ t.script.param.noCommandTemplates }}</span>
    </label>

    <!-- 孤儿模板警告——templateId 有值但在当前列表中找不到（服主删除 / 改名模板后出现）。
         显示失效 ID 并提示用户重新选择，保留原 id 可见让用户知道原来绑定的是哪个。 -->
    <div v-if="isOrphanTemplate" class="hc-pf-cmd-orphan">
      ⚠ {{ t.script.param.orphanTemplate(commandValue.templateId) }}
    </div>

    <!-- 选中模板后按其 params 动态渲染子输入 -->
    <div v-if="selectedTemplate && selectedTemplate.params.length > 0" class="hc-pf-cmd-params">
      <label
        v-for="p in selectedTemplate.params"
        :key="p.name"
        class="hc-pf-inline hc-pf-cmd-param"
      >
        <span class="hc-pf-label hc-pf-cmd-param-label">{{ p.name }}</span>
        <input
          class="hc-pf-input"
          type="text"
          :value="commandParamVal(p.name)"
          :maxlength="p.maxLength > 0 ? p.maxLength : undefined"
          :disabled="disabled"
          @input="(e) => onCommandParamInput(p.name, e)"
        >
      </label>
    </div>
  </div>
</template>

<style scoped>
/*
 * 积木参数控件统一"胶囊"风。
 *
 * 控件嵌在饱和实色积木块里，必须明显可点 / 可填：白底（深主题随 card）+ 大圆角胶囊 +
 * 清晰边框 + 内阴影，与彩色块面对比强烈。变量"选变量"按钮做成显眼胶囊（显当前变量名或
 * 「点击选择」提示 + 图标），不再是不起眼的灰小字。
 *
 * 字段标签（label）落在彩色块面上 → 用块前景色 --hc-block-fg-soft（由块组件透传到此处的
 * CSS 自定义属性；BlockParamInput 不持主题判定，直接吃继承下来的变量）。
 */
.hc-pf {
    font-size: 11px;
}
.hc-pf-inline {
    display: inline-flex;
    align-items: center;
    gap: 5px;
}
.hc-pf-label {
    /* 块面上的字段名：吃块组件继承下来的前景色（缺省退 muted） */
    color: var(--hc-block-fg-soft, var(--muted-foreground));
    font-weight: 600;
    white-space: nowrap;
}
/* 通用胶囊输入：白底 + 大圆角 + 内阴影，浮在彩色块上 */
.hc-pf-input {
    font-size: 11px;
    font-weight: 500;
    padding: 3px 9px;
    border: 1px solid color-mix(in srgb, #000 14%, transparent);
    border-radius: var(--radius-full, 9999px);
    background: var(--card);
    color: var(--foreground);
    outline: none;
    max-width: 180px;
    box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.12);
    transition: box-shadow 0.1s ease, border-color 0.1s ease;
}
.hc-pf-input:hover:not(:disabled) {
    border-color: color-mix(in srgb, #000 24%, transparent);
}
.hc-pf-input:focus {
    border-color: var(--ring);
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--ring) 45%, transparent);
}
.hc-pf-input:disabled {
    opacity: 0.55;
    cursor: not-allowed;
}
.hc-pf-number {
    width: 72px;
    text-align: center;
}
.hc-pf-select {
    cursor: pointer;
    max-width: 160px;
    /* select 留点右侧给箭头，圆角胶囊不裁切原生箭头 */
    padding-right: 6px;
}
.hc-pf-empty {
    /* 空列表提示也做成浅胶囊，提示"这里要选但还没得选" */
    color: var(--muted-foreground);
    font-style: italic;
    font-size: 10px;
    padding: 3px 9px;
    border-radius: var(--radius-full, 9999px);
    background: color-mix(in srgb, var(--card) 70%, transparent);
    border: 1px dashed var(--border);
}

/* variable —— 显眼"选变量"胶囊（症状 3b：原 11px 灰小字极不显眼） */
.hc-pf-var-row {
    display: inline-flex;
    align-items: center;
    gap: 3px;
}
.hc-pf-var-btn {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: 11px;
    font-weight: 600;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    padding: 3px 10px;
    border: 1px solid color-mix(in srgb, #000 16%, transparent);
    border-radius: var(--radius-full, 9999px);
    background: var(--card);
    color: var(--foreground);
    cursor: pointer;
    max-width: 168px;
    overflow: hidden;
    box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.1);
    transition: filter 0.1s ease, border-color 0.1s ease, box-shadow 0.1s ease;
}
/* 前缀小图标（◆），让按钮"看起来就是个能点的变量芯片" */
.hc-pf-var-btn::before {
    content: "◆";
    font-size: 9px;
    color: var(--ctp-mauve, var(--primary));
    flex: 0 0 auto;
}
.hc-pf-var-btn:hover:not(:disabled) {
    border-color: var(--ring);
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--ring) 35%, transparent);
}
.hc-pf-var-btn:disabled {
    opacity: 0.55;
    cursor: not-allowed;
}
/* 空值态：换更醒目的"点击选择"提示 + 虚线描边，引导用户它需要填 */
.hc-pf-var-empty {
    color: var(--ctp-mauve, var(--primary));
    font-style: normal;
    font-family: inherit;
    border-style: dashed;
    border-color: color-mix(in srgb, var(--ctp-mauve, var(--primary)) 55%, transparent);
}
.hc-pf-var-empty::before {
    content: "＋";
    font-size: 11px;
}
.hc-pf-var-text {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    display: inline-block;
    max-width: 100%;
}
.hc-pf-var-clear {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 19px;
    height: 19px;
    border: 1px solid color-mix(in srgb, #000 14%, transparent);
    border-radius: 50%;
    background: var(--card);
    color: var(--muted-foreground);
    cursor: pointer;
    font-size: 13px;
    line-height: 1;
}
.hc-pf-var-clear:hover:not(:disabled) {
    color: var(--destructive);
    border-color: var(--destructive);
}

/* picker host（Teleport 到 body）：自身只作定位上下文，picker 在内部 absolute 下拉 */
.hc-pf-picker-host {
    /* fixed 定位由内联 pickerStyle 给；这里只需建立相对定位上下文供 picker 的 absolute 贴合 */
    position: fixed;
}

/* 孤儿模板警告行（templateId 不在当前列表中） */
.hc-pf-cmd-orphan {
    font-size: 10px;
    color: var(--destructive, #ef4444);
    background: color-mix(in srgb, var(--destructive, #ef4444) 10%, transparent);
    border: 1px solid color-mix(in srgb, var(--destructive, #ef4444) 35%, transparent);
    border-radius: var(--radius-sm, 4px);
    padding: 2px 7px;
    line-height: 1.4;
    word-break: break-all;
}

/* command 复合 */
.hc-pf-command {
    display: flex;
    flex-direction: column;
    gap: 5px;
    width: 100%;
}
.hc-pf-cmd-params {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin-left: 10px;
    padding-left: 10px;
    /* 子参数缩进：实色块上用半透明前景色细条引导（不再 IDE 风红虚线） */
    border-left: 2px solid color-mix(in srgb, var(--hc-block-fg, var(--border)) 35%, transparent);
}
.hc-pf-cmd-param-label {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    min-width: 48px;
}
</style>
