<script setup lang="ts">
/**
 * 变量预览面板的单行。数值 + 可写（user / userglobal）变量带 `−1 / +1` 快捷调
 * （复用 {@link useLongPressIncrement}：单击 ±1 / 长按连加），点击发 `variable.set`，不用跳画布。
 *
 * <p><b>乐观本地值</b>：按压期间本地累加（{@code local}），不等 server 往返；松手后由 watch 同步
 * server 权威值（set 成功的回声 / 别处改动）。按压中刻意不被回声覆盖——否则长按累加会被滞后的
 * mirror 值拉回、数值"抖动"。只读 / 非数值变量不显 stepper（只显值）。</p>
 */
import { ref, watch } from 'vue';
import { useLongPressIncrement } from '@/composables/useLongPressIncrement';
import { Minus, Plus } from 'lucide-vue-next';

const props = defineProps<{
    display: string;
    value: string;
    fullName: string;
    missing: boolean;
    /** 可写（user/userglobal）+ 当前值是有限数 → 显示 ±1。 */
    editable: boolean;
    /** 当前数值（editable 时有效）。 */
    numValue: number;
}>();
const emit = defineEmits<{ (e: 'set', value: string): void }>();

/** 乐观本地值：按压期本地累加，松手后同步 server 权威。 */
const local = ref(props.numValue);

function step(dir: number): void {
    local.value = local.value + dir;
    emit('set', String(local.value));
}

const dec = useLongPressIncrement({ onTick: () => step(-1) });
const inc = useLongPressIncrement({ onTick: () => step(1) });

// server 权威值变化 → 非按压时同步本地（接受 set 成功回声 / 别处改动）；按压中不覆盖防抖动。
watch(
    () => props.numValue,
    (v) => {
        if (!dec.isPressing.value && !inc.isPressing.value) local.value = v;
    },
);
</script>

<template>
  <li class="hc-var-watch-row" :class="missing ? 'hc-var-watch-row-missing' : ''" :title="fullName">
    <span class="hc-var-watch-name">{{ display }}</span>
    <span class="hc-var-watch-eq">=</span>
    <span class="hc-var-watch-val">{{ editable ? local : value }}</span>
    <span v-if="editable" class="hc-var-watch-steppers">
      <button
        type="button"
        class="hc-var-step"
        :class="{ 'hc-var-step-active': dec.isPressing.value }"
        aria-label="-1"
        @pointerdown="dec.onPointerDown"
        @pointerup="dec.onPointerUp"
        @pointerleave="dec.onPointerLeave"
        @pointercancel="dec.onPointerCancel"
      >
        <Minus class="size-3" />
      </button>
      <button
        type="button"
        class="hc-var-step"
        :class="{ 'hc-var-step-active': inc.isPressing.value }"
        aria-label="+1"
        @pointerdown="inc.onPointerDown"
        @pointerup="inc.onPointerUp"
        @pointerleave="inc.onPointerLeave"
        @pointercancel="inc.onPointerCancel"
      >
        <Plus class="size-3" />
      </button>
    </span>
  </li>
</template>

<style scoped>
.hc-var-watch-row {
    display: flex;
    align-items: center;
    gap: 0.3125rem;
    padding: 0.1875rem 0.375rem;
    border-radius: var(--radius-sm, 5px);
    font-size: 0.75rem;
    line-height: 1.3;
}
.hc-var-watch-row:hover {
    background: var(--accent);
}
.hc-var-watch-name {
    color: var(--ctp-mauve, var(--primary));
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 45%;
    flex-shrink: 0;
}
.hc-var-watch-eq {
    color: var(--muted-foreground);
    flex-shrink: 0;
}
.hc-var-watch-val {
    color: var(--foreground);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-variant-numeric: tabular-nums;
    flex: 1;
}
.hc-var-watch-row-missing .hc-var-watch-val {
    color: var(--ctp-peach, var(--muted-foreground));
    font-style: italic;
}
.hc-var-watch-steppers {
    display: inline-flex;
    gap: 2px;
    flex-shrink: 0;
    margin-left: auto;
}
.hc-var-step {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    padding: 0;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm, 4px);
    background: var(--card);
    color: var(--card-foreground);
    cursor: pointer;
}
.hc-var-step:hover {
    background: var(--accent);
    border-color: var(--ring);
}
.hc-var-step-active {
    background: color-mix(in srgb, var(--ctp-mauve, var(--primary)) 25%, transparent);
}
</style>
