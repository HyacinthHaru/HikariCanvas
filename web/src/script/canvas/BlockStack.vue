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
import { computed } from 'vue';
import type { ScriptRule } from '@/types/protocol';
import { useI18n } from '@/i18n';
import { TRIGGER_DEFS, type FieldDef } from '../model/blockDefs';
import { resolveLabelKey } from './labelKey';
import BlockNode from './BlockNode.vue';

const props = defineProps<{
    /** 本堆对应的规则。 */
    rule: ScriptRule;
    /** world 坐标系 x（像素，左上角）。 */
    x: number;
    /** world 坐标系 y（像素，左上角）。 */
    y: number;
}>();

const { t } = useI18n();

/** 触发器定义；未知 type → null（兜底显示触发器 type 字面量）。 */
const triggerDef = computed(() => TRIGGER_DEFS[props.rule.trigger.type] ?? null);

/** 触发器帽子标题文案。 */
const triggerTitle = computed(() =>
    triggerDef.value
        ? resolveLabelKey(t.value, triggerDef.value.labelKey)
        : `${resolveLabelKey(t.value, 'script.unknownBlock')}: ${props.rule.trigger.type}`,
);

/** 触发器帽子色条（peach；无 def 退灰）。 */
const triggerColor = computed(() =>
    triggerDef.value ? `var(${triggerDef.value.colorVar})` : 'var(--border)',
);

/** 触发器标量参数（占位：字段名 + 原始值）。触发器字段无 statements/condition。 */
const triggerFields = computed<FieldDef[]>(() => triggerDef.value?.fields ?? []);

function fieldLabel(field: FieldDef): string {
    return resolveLabelKey(t.value, field.labelKey);
}

/** 取触发器某字段原始值文本。 */
function triggerRawValue(field: FieldDef): string {
    const raw = (props.rule.trigger as unknown as Record<string, unknown>)[field.name];
    if (raw === undefined || raw === null) return '';
    return String(raw);
}

/** 堆的 absolute 定位样式。 */
const stackStyle = computed(() => ({
    left: `${props.x}px`,
    top: `${props.y}px`,
}));
</script>

<template>
  <div class="hc-block-stack" :style="stackStyle" :data-rule-id="rule.id">
    <!-- 触发器帽子（梯形 + peach 底，显规则名 + 触发器） -->
    <div
      class="hc-stack-hat"
      data-block-path="trigger"
      :style="{ background: `color-mix(in srgb, ${triggerColor} 22%, var(--card))`, borderColor: triggerColor }"
    >
      <div class="hc-hat-row">
        <span class="hc-hat-name">{{ rule.name }}</span>
        <span v-if="!rule.enabled" class="hc-hat-disabled">{{ t.script.close }}</span>
      </div>
      <div class="hc-hat-row hc-hat-trigger">
        <span class="hc-hat-trigger-label" :style="{ color: triggerColor }">{{ triggerTitle }}</span>
        <span
          v-for="f in triggerFields"
          :key="f.name"
          class="hc-hat-param"
        >
          <span class="hc-param-label">{{ fieldLabel(f) }}:</span>
          <span class="hc-param-value">{{ triggerRawValue(f) || '—' }}</span>
        </span>
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
      <div v-if="rule.actions.length === 0" class="hc-stack-empty">
        {{ t.script.emptySlot }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.hc-block-stack {
    position: absolute;
    width: 280px;
    display: flex;
    flex-direction: column;
    gap: 5px;
}
.hc-stack-hat {
    border: 2px solid;
    /* 帽子视觉：上圆角大、下圆角小，像 Scratch 触发帽 */
    border-radius: 12px 12px 6px 6px;
    padding: 7px 10px;
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.18);
    user-select: none;
}
.hc-hat-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
}
.hc-hat-name {
    font-size: 13px;
    font-weight: 700;
    color: var(--foreground);
}
.hc-hat-disabled {
    font-size: 10px;
    padding: 0 5px;
    border-radius: 4px;
    background: color-mix(in srgb, var(--muted) 70%, transparent);
    color: var(--muted-foreground);
}
.hc-hat-trigger {
    margin-top: 2px;
}
.hc-hat-trigger-label {
    font-size: 11px;
    font-weight: 600;
}
.hc-hat-param {
    display: inline-flex;
    align-items: baseline;
    gap: 3px;
    font-size: 11px;
    padding: 1px 6px;
    border-radius: 4px;
    background: color-mix(in srgb, var(--muted) 60%, transparent);
    max-width: 200px;
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
.hc-stack-actions {
    display: flex;
    flex-direction: column;
    gap: 5px;
    /* 序列相对帽子略内缩，视觉上"挂"在帽子下 */
    margin-left: 4px;
}
.hc-stack-empty {
    font-size: 11px;
    color: var(--muted-foreground);
    font-style: italic;
    padding: 4px 8px;
    border: 1px dashed var(--border);
    border-radius: 4px;
}
</style>
