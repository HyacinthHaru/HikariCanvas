<script setup lang="ts">
/**
 * 0.7.0-P4-C：单条 {@link ScriptRule} 的积木堆 = 触发器帽子 + 动作序列。
 *
 * <p><b>帽子</b>读 {@link TRIGGER_DEFS}（按 rule.trigger.type）渲染：梯形 / 帽子视觉（peach 底），
 * 显规则名 + 触发器 label + 触发器标量参数占位。<b>动作序列</b>用 {@link BlockNode} 递归渲染，
 * 顶层动作 path = {@code `actions/${i}`}（与后端 trace blockId 同构）。</p>
 *
 * <p>堆 {@code position:absolute} 定位在 world 坐标系——坐标由父级（BlockCanvas）从
 * blockLayout 解析后通过 props.x / props.y 传入。规则名本阶段只显示（编辑留后续）。
 * 触发器帽子 path = {@code 'trigger'}（trace 中触发器步的 blockId）。</p>
 */
import { computed, inject, ref } from 'vue';
import type { ScriptRule, ScriptTrigger } from '@/types/protocol';
import { useI18n } from '@/i18n';
import { TRIGGER_DEFS, makeDefaultTrigger, type FieldDef } from '../model/blockDefs';
import { resolveLabelKey } from './labelKey';
import { BLOCK_DRAG_KEY, NOOP_DRAG_HANDLES } from './dragInjection';
import { BLOCK_HIGHLIGHT_KEY, type HighlightInject } from './highlightInjection';
import { resultColorVar, type HighlightMap } from './traceHighlight';
import { useProjectStore } from '@/stores/project';
import { useScriptEditStore } from '@/stores/scriptEdit';
import BlockNode from './BlockNode.vue';
import BlockParamInput from '../params/BlockParamInput.vue';

const props = defineProps<{
    /** 本堆对应的规则。 */
    rule: ScriptRule;
    /** world 坐标系 x（像素，左上角）。 */
    x: number;
    /** world 坐标系 y（像素，左上角）。 */
    y: number;
}>();

const { t } = useI18n();
const project = useProjectStore();
const edit = useScriptEditStore();
/** 锁定态：true 时帽子的触发类型 select + 参数控件全禁用（K-UI-12，锁定的墙只读）。 */
const locked = computed(() => project.isLocked);
/** D2 拖拽句柄（BlockCanvas provide；单独 mount 时走 no-op 兜底）。 */
const dragHandles = inject(BLOCK_DRAG_KEY, NOOP_DRAG_HANDLES);
/** H 试跑高亮（帽子 blockId = 'trigger'）；单独 mount 走空 map 兜底。 */
const EMPTY_HIGHLIGHT: HighlightInject = {
    results: ref<HighlightMap>(new Map()),
    details: ref<Map<string, string>>(new Map()),
};
const highlight = inject(BLOCK_HIGHLIGHT_KEY, EMPTY_HIGHLIGHT);
/** 帽子（trigger）的试跑结果态。 */
const hatResult = computed(() => highlight.results.value.get('trigger'));
/** 帽子的 trace detail（作 title）。 */
const hatDetail = computed(() => highlight.details.value.get('trigger'));

/**
 * 帽子 pointerdown：选中本规则 + 启动移堆（拖帽子移整堆）。只接管主键（左键）。
 * 中键留给 pan（不 stopPropagation 让其冒泡到 viewport）。
 *
 * <p>帽子里现在有触发类型 select + 参数控件（H2）：pointerdown 落在表单元素上时不应启动
 * 移堆（否则点下拉 / 输入框就把整堆拖走）。照 BlockNode.onBlockPointerDown 范式，跳过
 * input/textarea/select/button/contentEditable 目标。</p>
 */
function onHatPointerDown(e: PointerEvent): void {
    if (e.button !== 0) return; // 仅左键：移堆 + 选中
    if (isFormTarget(e.target)) return;
    dragHandles.startStackDrag(props.rule.id, e);
}

/** pointerdown 目标是否表单元素（命中则不启动移堆，留给控件自身交互）。 */
function isFormTarget(target: EventTarget | null): boolean {
    const el = target as HTMLElement | null;
    return !!el && (el.matches?.('input, textarea, select, button') || el.isContentEditable);
}

/** 堆体（非帽子区）点击：选中本规则进入编辑（不拖动）。 */
function onStackClick(): void {
    if (edit.selectedRuleId !== props.rule.id) edit.selectRule(props.rule.id);
}

/** 触发器定义；未知 type → null（兜底退灰色条）。H2 起标题文案改由 select 当前 option 承载。 */
const triggerDef = computed(() => TRIGGER_DEFS[props.rule.trigger.type] ?? null);

/** 触发器帽子色条（peach；无 def 退灰）。 */
const triggerColor = computed(() =>
    triggerDef.value ? `var(${triggerDef.value.colorVar})` : 'var(--border)',
);

/**
 * 触发器标量参数字段（H2：换成 {@link BlockParamInput} 真控件）。触发器字段都是标量
 * （variableChange→fullName / timer→intervalSeconds / playerNear→rangeBlocks），无
 * statements/condition，故整张 fields 表直接喂控件。playerJoin/playerKill/wallReady 为空。
 */
const triggerFields = computed<FieldDef[]>(() => triggerDef.value?.fields ?? []);

/**
 * 六种触发类型选项（select over TRIGGER_DEFS）：value = kind，label 走 def.labelKey
 * （= {@code script.blocks.<kind>}，与帽子标题同口径）。
 */
const triggerKindOptions = computed<{ kind: string; label: string }[]>(() =>
    Object.values(TRIGGER_DEFS).map((def) => ({
        kind: def.kind,
        label: resolveLabelKey(t.value, def.labelKey),
    })),
);

/**
 * 改触发类型：用 {@link makeDefaultTrigger} 造该类型的合法默认 trigger（带范围内默认参数 /
 * 空引用），整体替换。lock 时 no-op（控件已 disabled，这里再守一手）。
 */
function onTriggerKindChange(e: Event): void {
    if (locked.value) return;
    const kind = (e.target as HTMLSelectElement).value;
    if (kind === props.rule.trigger.type) return;
    edit.setTrigger(makeDefaultTrigger(kind));
}

/** 取触发器某字段当前值（喂给 BlockParamInput 的 value）。缺失 → undefined（控件退默认）。 */
function triggerFieldValue(field: FieldDef): unknown {
    return (props.rule.trigger as unknown as Record<string, unknown>)[field.name];
}

/**
 * 改触发器某字段值：immutable 在当前 trigger 上覆盖该单字段后整体 setTrigger
 * （setTrigger 接收完整 ScriptTrigger）。不改 type（type 由 {@link onTriggerKindChange} 切）。
 */
function onTriggerFieldUpdate(field: FieldDef, value: unknown): void {
    if (locked.value) return;
    const next = { ...props.rule.trigger, [field.name]: value } as ScriptTrigger;
    edit.setTrigger(next);
}

/** 堆的 absolute 定位样式。 */
const stackStyle = computed(() => ({
    left: `${props.x}px`,
    top: `${props.y}px`,
}));

/**
 * 帽子样式（视觉：Scratch 事件帽风）：默认实色填触发器色（饱和 peach），文字走块前景色；
 * 试跑高亮命中时整顶改用结果色 + 外发光环，让"触发器步命中 / 阻塞 / 错误"一眼可见。
 *
 * <p>用 CSS 变量 {@code --hc-block-color} 把当前底色（触发色 or 高亮结果色）传给 scoped CSS，
 * 由它驱动背景 / 凸榫 / 阴影一致着色。</p>
 */
const hatStyle = computed(() => {
    const r = hatResult.value;
    const color = r ? `var(${resultColorVar(r)})` : triggerColor.value;
    const base: Record<string, string> = { '--hc-block-color': color };
    if (r) {
        base.boxShadow = `0 0 0 2px color-mix(in srgb, ${color} 60%, transparent), 0 4px 8px rgba(0,0,0,0.22)`;
    }
    return base;
});
</script>

<template>
  <div
    class="hc-block-stack"
    :style="stackStyle"
    :data-rule-id="rule.id"
    :class="rule.id === edit.selectedRuleId ? 'hc-block-stack-active' : ''"
    @pointerdown.stop
    @click="onStackClick"
  >
    <!-- 触发器帽子（梯形 + peach 底，显规则名 + 触发器）；左键拖 = 移堆 -->
    <div
      class="hc-stack-hat"
      data-block-path="trigger"
      :data-hl-result="hatResult || null"
      :title="hatDetail || undefined"
      :style="hatStyle"
      @pointerdown="onHatPointerDown"
    >
      <div class="hc-hat-row">
        <span class="hc-hat-name">{{ rule.name }}</span>
        <span v-if="!rule.enabled" class="hc-hat-disabled">{{ t.script.close }}</span>
      </div>
      <!-- H2：可编辑触发器 —— 触发类型 select + 当前类型参数控件（lock 时 disabled） -->
      <div class="hc-hat-row hc-hat-trigger">
        <label class="hc-hat-trigger-kind">
          <span class="hc-param-label">{{ t.script.triggerKindLabel }}</span>
          <select
            class="hc-hat-kind-select"
            :value="rule.trigger.type"
            :disabled="locked"
            @change="onTriggerKindChange"
          >
            <option v-for="opt in triggerKindOptions" :key="opt.kind" :value="opt.kind">
              {{ opt.label }}
            </option>
          </select>
        </label>
        <BlockParamInput
          v-for="f in triggerFields"
          :key="f.name"
          class="hc-hat-param"
          :field="f"
          :value="triggerFieldValue(f)"
          :action-kind="rule.trigger.type"
          :disabled="locked"
          @update="(v: unknown) => onTriggerFieldUpdate(f, v)"
        />
      </div>
    </div>

    <!-- 动作序列（顶层 path = actions/i） -->
    <div class="hc-stack-actions">
      <BlockNode
        v-for="(action, i) in rule.actions"
        :key="`actions/${i}`"
        :action="action"
        :path="`actions/${i}`"
      />
      <!-- 空 actions 序列：data-slot-path="actions" 让 collectSlots 把它当顶层 index=0 落点 -->
      <div v-if="rule.actions.length === 0" class="hc-stack-empty" data-slot-path="actions">
        {{ t.script.emptySlot }}
      </div>
    </div>
  </div>
</template>

<style scoped>
/*
 * 0.7.0-P5（视觉）：Scratch 风积木堆 = 事件帽 + 焊接动作序列。
 *   - 帽子（hat）：顶部大圆角圆顶 + 实色饱和触发色，明显区别于方角动作块。
 *   - 动作序列：与帽子无缝相接（负 margin 让首块咬住帽子底）+ 块间 gap≈3px 焊接堆叠。
 * 块上文字走 --hc-block-fg（浅主题白 / 深主题深 crust），饱和块面上对比清晰。
 */
.hc-block-stack {
    position: absolute;
    width: 280px;
    display: flex;
    flex-direction: column;
    gap: 0;
}
.hc-block-stack-active {
    /* 当前编辑规则：淡 mauve 描边光环（与左侧列表高亮同色系） */
    outline: 2px solid color-mix(in srgb, var(--ctp-mauve, var(--primary)) 55%, transparent);
    outline-offset: 4px;
    border-radius: 16px;
}
.hc-stack-hat {
    position: relative;
    /* 事件帽：顶部大圆顶、底部直角（与下方动作序列焊接） */
    border-radius: 16px 16px 5px 5px;
    /* 实色帽：饱和触发色直填（模板内联 --hc-block-color） */
    background: var(--hc-block-color, var(--ctp-peach));
    color: var(--hc-block-fg);
    padding: 9px 12px 11px;
    /* 立体：底部投影 + 顶部内高光 */
    box-shadow:
        0 2px 0 color-mix(in srgb, var(--hc-block-color, var(--ctp-peach)) 60%, #000),
        0 4px 8px rgba(0, 0, 0, 0.2),
        inset 0 1px 0 color-mix(in srgb, #fff 30%, transparent);
    user-select: none;
    /* 帽子可拖动移整堆 */
    cursor: grab;
    /* H：试跑高亮描边 / 发光柔和过渡 */
    transition: box-shadow 0.12s ease, background-color 0.12s ease, filter 0.12s ease;
}
.hc-stack-hat:hover {
    filter: brightness(1.05);
}
.hc-stack-hat:active {
    cursor: grabbing;
}
.hc-hat-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
}
.hc-hat-name {
    font-size: 13.5px;
    font-weight: 800;
    color: var(--hc-block-fg);
    text-shadow: 0 1px 0 color-mix(in srgb, var(--hc-block-color, var(--ctp-peach)) 55%, #000);
}
.hc-hat-disabled {
    font-size: 10px;
    padding: 1px 6px;
    border-radius: var(--radius-full, 9999px);
    background: rgba(0, 0, 0, 0.22);
    color: var(--hc-block-fg);
}
.hc-hat-trigger {
    margin-top: 4px;
}
/* H2：触发类型 select（label + 下拉），胶囊白底浮在帽面上 */
.hc-hat-trigger-kind {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: 11px;
}
.hc-hat-kind-select {
    font-size: 11px;
    font-weight: 600;
    padding: 3px 9px;
    border: 1px solid color-mix(in srgb, #000 12%, transparent);
    /* 胶囊：白底 / 深主题随 card，浮在帽面上 */
    border-radius: var(--radius-full, 9999px);
    background: var(--card);
    color: var(--foreground);
    box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.12);
    cursor: pointer;
    max-width: 150px;
}
.hc-hat-kind-select:disabled {
    opacity: 0.55;
    cursor: not-allowed;
}
/* H2：触发参数控件（BlockParamInput）——胶囊样式由控件自身承载，这里只排版 */
.hc-hat-param {
    display: inline-flex;
    align-items: center;
    max-width: 200px;
}
.hc-param-label {
    color: var(--hc-block-fg-soft);
    white-space: nowrap;
}
.hc-stack-actions {
    display: flex;
    flex-direction: column;
    /* 块间焊接：小缝 + 块顶凸榫桥接 */
    gap: 3px;
    /* 首块负 margin 上提，咬住帽子底部（无缝相接） */
    margin-top: -1px;
    padding-top: 4px;
    /* 序列相对帽子略内缩，视觉上"挂"在帽子下 */
    margin-left: 6px;
}
.hc-stack-empty {
    font-size: 11px;
    color: var(--muted-foreground);
    font-style: italic;
    padding: 5px 9px;
    background: color-mix(in srgb, var(--muted) 40%, transparent);
    border: 1px dashed var(--border);
    border-radius: 6px;
    box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.18);
}
</style>
